package com.example.readalong.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.*
import com.example.readalong.MainActivity
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.local.AudioFileEntity
import com.example.readalong.data.local.AudioChapterEntity
import com.example.readalong.data.local.LibraryMetadataEntity
import com.example.readalong.data.epub.TimedEpubChapter
import com.example.readalong.data.epub.epubChapters
import com.example.readalong.data.epub.useForReading
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AudiobookService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + com.example.readalong.data.diagnostics.BackgroundFailures.handler("Playback service"))
    private val selectLock = Mutex()
    private val saveLock = Mutex()
    private lateinit var player: ExoPlayer
    private lateinit var sessionPlayer: AudiobookSessionPlayer
    private var session: MediaSession? = null
    private val app get() = application as ReadAlongApplication
    private val database get() = app.database
    private val bookFlow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private var bookId: String?
        get() = bookFlow.value
        set(value) { bookFlow.value = value }
    private val soundProcessor = SoundProcessor()
    private var files = emptyList<AudioFileEntity>()
    private var embeddedChapters = emptyList<AudioChapterEntity>()
    private var cachedFiles = emptyList<AudioFileEntity>()
    private var cachedEmbedded = emptyList<AudioChapterEntity>()
    private var epubContents = emptyList<TimedEpubChapter>()
    private var cachedEpub = emptyList<TimedEpubChapter>()
    private var epubJob: Job? = null
    private var bookTitle = ""
    private var cachedChapters = emptyList<PlaybackChapter>()
    private var fileOffsets = emptyList<Long>()
    private var totalDuration = 0L
    private var sleepEndsAt: Long? = null
    /** The minutes last chosen, so a shake can start the same timer again. */
    private var sleepMinutes = 0
    @Volatile private var settings = com.example.readalong.data.preferences.AppPreferences()
    /** When play was last paused (elapsed time), for going back a little on resume; cleared by a seek. */
    private var pausedAt: Long? = null
    private var shake: SleepShake? = null
    private var sleepChapter: PlaybackChapter? = null
    private var error: String? = null
    private var loadingBook = false

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setRenderersFactory(object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
                // The equalizer, noise reduction and volume boost run before skip silence and speed.
                override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean) =
                    androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context).setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams).setAudioProcessors(arrayOf(soundProcessor)).build()
            })
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this, com.example.readalong.data.audio.AudiobookExtractorsFactory(this)))
            .setSeekBackIncrementMs(20_000).setSeekForwardIncrementMs(30_000).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    checkChapterSleep()
                    if (player.playbackState == Player.STATE_ENDED) sleepChapter = null
                    publish()
                    if (!loadingBook && (events.contains(Player.EVENT_IS_PLAYING_CHANGED) || events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
                        events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED))) scope.launch { safelySave() }
                    val index = player.currentMediaItemIndex
                    val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
                    if (duration != null && index in files.indices && files[index].durationMs != duration) {
                        val item = files[index]
                        files = files.toMutableList().apply { set(index, item.copy(durationMs = duration)) }
                        // Saving a corrected duration is optional; a storage error must not stop playback.
                        scope.launch { try { com.example.readalong.data.processing.TranscriptionPlan.correctDuration(database, item.bookId, item.id, duration) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { } }
                    }
                }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady) { pausedAt = android.os.SystemClock.elapsedRealtime(); return }
                    val since = pausedAt ?: return
                    pausedAt = null
                    if (!loadingBook) scope.launch { rewindOnResume(android.os.SystemClock.elapsedRealtime() - since) }
                }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    // A deliberate jump (a tapped sentence, a chapter) is where you want to be: don't move it back.
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) pausedAt = null
                }
                override fun onPlayerError(failure: PlaybackException) {
                    error = "This audio couldn't be played. Check that the file is available and uses a supported codec. (${failure.errorCodeName})"
                    publish()
                }
            })
        }
        val activity = PendingIntent.getActivity(this, 0, com.example.readalong.ui.launch.LaunchAppearance.intent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        sessionPlayer = AudiobookSessionPlayer(player) { delta ->
            val result = SettableFuture.create<Void>()
            scope.launch {
                try { selectLock.withLock { sleepChapter = null; seekAbsolute(snapshot().positionMs + delta) }; result.set(null) }
                catch (failure: Exception) { result.setException(failure) }
            }
            result
        }
        scope.launch { bookFlow.flatMapLatest { app.sound.effective(it) }.collect(::applySound) }
        scope.launch { app.appPreferences.preferences.collect { settings -> this@AudiobookService.settings = settings; sessionPlayer.intervals(settings.playerBack * 1000L, settings.playerForward * 1000L) } }
        val buttons = listOf(
            CommandButton.Builder(CommandButton.ICON_SKIP_BACK).setDisplayName("Rewind").setPlayerCommand(Player.COMMAND_SEEK_BACK).setSlots(CommandButton.SLOT_BACK).build(),
            CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD).setDisplayName("Forward").setPlayerCommand(Player.COMMAND_SEEK_FORWARD).setSlots(CommandButton.SLOT_FORWARD).build())
        session = MediaSession.Builder(this, sessionPlayer).setMediaButtonPreferences(buttons).setSessionActivity(activity).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                if (controller.packageName == packageName) listOf(SELECT_BOOK, SEEK_BOOK, SEEK_CHAPTER, SLEEP_TIMER, CLEAR_BOOK).forEach { commands.add(SessionCommand(it, Bundle.EMPTY)) }
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands.build()).build()
            }
            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                if (controller.packageName != packageName) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                val result = SettableFuture.create<SessionResult>()
                scope.launch {
                    try {
                        selectLock.withLock {
                            when (customCommand.customAction) {
                                CLEAR_BOOK -> {
                                    player.pause()
                                    saveLock.withLock {
                                        bookId = null; files = emptyList(); embeddedChapters = emptyList(); clearEpubChapters()
                                        sleepEndsAt = null; sleepChapter = null; player.clearMediaItems()
                                        app.playbackStore.publish(PlaybackState())
                                    }
                                }
                                SELECT_BOOK -> {
                                    loadingBook = true
                                    try { loadBook(requireNotNull(args.getString("bookId"))) } finally { loadingBook = false }
                                }
                                SEEK_BOOK -> {
                                    sleepChapter = null
                                    seekAbsolute(args.getLong("positionMs"))
                                    if (args.getBoolean("play")) { if (player.playerError != null) player.prepare(); player.play() }
                                }
                                SEEK_CHAPTER -> {
                                    sleepChapter = null
                                    val chapter = snapshot().chapters.getOrNull(args.getInt("chapterIndex")) ?: error("Missing chapter")
                                    player.seekTo(chapter.fileIndex, chapter.fileStartMs)
                                    publish(); save()
                                }
                                SLEEP_TIMER -> {
                                    sleepChapter = if (args.getBoolean("chapterEnd")) snapshot().let { it.chapters.getOrNull(it.chapterIndex) } else null
                                    sleepMinutes = args.getInt("minutes")
                                    sleepEndsAt = sleepMinutes.takeIf { it > 0 }?.let { System.currentTimeMillis() + it * 60_000L }
                                    publish()
                                }
                                else -> error("Unsupported command")
                            }
                        }
                        result.set(SessionResult(SessionResult.RESULT_SUCCESS))
                    } catch (cancelled: CancellationException) { result.cancel(false); throw cancelled }
                    catch (_: Exception) {
                        if (customCommand.customAction == SELECT_BOOK) { player.pause(); player.clearMediaItems(); files = emptyList(); embeddedChapters = emptyList(); clearEpubChapters(); bookId = args.getString("bookId") }
                        error = "Audio access was lost. Rescan or add its folder again to restore access."
                        publish()
                        result.set(SessionResult(SessionError.ERROR_IO))
                    }
                }
                return result
            }
        }).build()
        // Counts listening time for the "Enjoying ReadAlong?" card, saved every 30 s.
        scope.launch {
            var unsaved = 0L
            var last = android.os.SystemClock.elapsedRealtime()
            while (isActive) {
                delay(1000)
                val now = android.os.SystemClock.elapsedRealtime()
                if (player.isPlaying) unsaved += (now - last).coerceIn(0, 5000)
                last = now
                if (unsaved >= 30_000) { val add = unsaved; unsaved = 0; runCatching { app.appPreferences.update { it.copy(listenedMs = it.listenedMs + add) } } }
            }
        }
        scope.launch {
            var ticks = 0
            while (isActive) {
                delay(250)
                if (sleepEndsAt?.let { System.currentTimeMillis() >= it } == true) { player.pause(); sleepEndsAt = null }
                checkChapterSleep()
                sleepFade()
                publish()
                if (player.isPlaying && ++ticks % 8 == 0) safelySave()
            }
        }
    }

    private suspend fun loadBook(id: String) {
        if (bookId == id && player.mediaItemCount > 0) return
        save()
        player.pause()
        val book = requireNotNull(database.bookDao().findBook(id))
        check(!book.isDemo) { "Samples don't contain audio." }
        val audio = database.mediaDao().audioFiles(id)
        check(audio.isNotEmpty())
        try {
            contentResolver.openFileDescriptor(Uri.parse(audio.first().uri), "r")?.use { } ?: error("Missing audio")
        } catch (failure: Exception) { database.mediaDao().mediaState(id, "MISSING"); throw failure }
        files = audio; embeddedChapters = database.mediaDao().chapters(id); bookId = id; error = null; sleepEndsAt = null; sleepChapter = null
        bookTitle = book.title
        watchEpubChapters(id)
        val progress = database.bookDao().progress(id)?.audioPositionMs ?: 0
        val previousFileId = database.bookDao().metadata("file:$id")
        val savedIndex = audio.indexOfFirst { it.id == previousFileId }
        val (index, position) = if (savedIndex >= 0) savedIndex to (database.bookDao().metadata("file_position:$id")?.toLongOrNull() ?: 0)
            else locate(progress)
        val items = audio.map { file ->
            MediaItem.Builder().setMediaId(file.id).setUri(file.uri).setMediaMetadata(MediaMetadata.Builder()
                .setTitle(book.title).setArtist(book.author).setSubtitle(file.name).setAlbumTitle(book.title)
                .setArtworkUri(book.coverUri?.let(Uri::parse)).build()).build()
        }
        player.setMediaItems(items, index, position.coerceAtLeast(0))
        val ownSpeed = if (app.appPreferences.preferences.first().speedPerBook) database.bookDao().metadata(speedKey(id)) else null
        player.setPlaybackSpeed(com.example.readalong.domain.model.roundSpeed((ownSpeed ?: database.bookDao().metadata("playback_speed"))?.toFloatOrNull() ?: 1f))
        pausedAt = null
        player.prepare()
        database.bookDao().putMetadata(LibraryMetadataEntity("current_book", id))
        database.mediaDao().mediaState(id, "AVAILABLE")
        publish()
    }

    /** The book's sound: filters in [soundProcessor], skip silence and pitch in the player (speed is kept). */
    private fun applySound(value: com.example.readalong.domain.model.SoundSettings) {
        soundProcessor.settings = value
        player.skipSilenceEnabled = value.skipSilence
        if (player.playbackParameters.pitch != value.pitchFactor) player.playbackParameters = player.playbackParameters.withPitch(value.pitchFactor)
    }

    private fun speedKey(id: String) = "playback_speed:$id"

    /** Goes back a little when play resumes, by the "Rewind when resuming" setting. */
    private suspend fun rewindOnResume(awayMs: Long) = selectLock.withLock {
        val id = bookId ?: return@withLock
        val position = snapshot().positionMs
        val mode = settings.resumeRewind
        val sentence = if (mode == "SENTENCE") runCatching { database.readingDao().segmentAt(id, position)?.startMs }.getOrNull()?.let { position - it } else null
        val back = com.example.readalong.domain.model.resumeRewindMs(mode, awayMs, sentence)
        if (back > 0 && player.playWhenReady) seekAbsolute((position - back).coerceAtLeast(0))
    }

    /** Volume eases down over the sleep timer's last 20 seconds; a shake (when on) starts the timer again. */
    private fun sleepFade() {
        val remaining = sleepEndsAt?.let { it - System.currentTimeMillis() } ?: sleepChapter?.let { target ->
            val end = target.durationMs?.let { target.startMs + it }
            if (end == null || totalDuration <= 0) null else ((end - snapshot().positionMs) / player.playbackParameters.speed.coerceAtLeast(.1f)).toLong()
        }
        val volume = com.example.readalong.domain.model.sleepFadeVolume(remaining)
        if (player.volume != volume) player.volume = volume
        val listen = settings.shakeToReset && (sleepEndsAt != null || sleepChapter != null)
        if (listen && shake == null) shake = SleepShake(this) { resetSleepTimer() }.also { it.start() }
        if (!listen && shake != null) { shake?.stop(); shake = null }
    }

    private fun resetSleepTimer() {
        if (sleepEndsAt == null && sleepChapter == null) return
        if (sleepEndsAt != null && sleepMinutes > 0) sleepEndsAt = System.currentTimeMillis() + sleepMinutes * 60_000L
        // A chapter-end timer keeps its chapter; the shake still brings the volume back while it's fading.
        player.volume = 1f
        SleepShake.playResetSound(this, settings)
        publish()
    }

    private fun clearEpubChapters() { epubJob?.cancel(); epubJob = null; epubContents = emptyList() }
    /** Matched EPUB chapters replace audio-file chapters; alignment and manual sync updates touch the edition row. */
    private fun watchEpubChapters(id: String) {
        clearEpubChapters()
        epubJob = scope.launch {
            database.importedEpubDao().observe(id).collect { edition ->
                val chapters = try {
                    edition?.takeIf { it.useForReading() }?.let { withContext(Dispatchers.IO) { database.epubChapters(it) } }.orEmpty()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { emptyList() }
                if (bookId == id) { epubContents = chapters; publish() }
            }
        }
    }
    private fun locate(positionMs: Long): Pair<Int, Long> {
        var remaining = positionMs.coerceAtLeast(0)
        files.forEachIndexed { index, file ->
            val duration = file.durationMs
            if (duration == null || remaining < duration || index == files.lastIndex) return index to (duration?.let { remaining.coerceAtMost(it) } ?: remaining)
            remaining -= duration
        }
        return 0 to 0
    }
    private suspend fun seekAbsolute(positionMs: Long) {
        if (files.isEmpty()) return
        val (index, position) = locate(positionMs)
        player.seekTo(index, position)
        publish(); save()
    }
    private fun checkChapterSleep() {
        val target = sleepChapter ?: return
        val targetEnd = target.durationMs?.let { target.startMs + it }
        if (totalDuration > 0 && targetEnd != null) {
            // Known file lengths: chapters may span files, so compare book positions.
            if ((fileOffsets.getOrNull(player.currentMediaItemIndex) ?: 0L) + player.currentPosition >= targetEnd) {
                sleepChapter = null
                player.pause()
                val (index, position) = locate(targetEnd)
                player.seekTo(index, position)
            }
            return
        }
        val end = target.durationMs?.let { target.fileStartMs + it }
        if (player.currentMediaItemIndex != target.fileIndex || (end != null && player.currentPosition >= end)) {
            // Clear before seeking: listener callbacks can be reentrant.
            sleepChapter = null
            player.pause()
            if (end != null) {
                if (files.getOrNull(target.fileIndex)?.durationMs == end && target.fileIndex < files.lastIndex)
                    player.seekTo(target.fileIndex + 1, 0)
                else player.seekTo(target.fileIndex, end)
            }
        }
    }
    private fun refreshChapterCache() {
        if (cachedFiles === files && cachedEmbedded === embeddedChapters && cachedEpub === epubContents) return
        cachedFiles = files; cachedEmbedded = embeddedChapters; cachedEpub = epubContents
        var offset = 0L
        val chapters = mutableListOf<PlaybackChapter>()
        val offsets = mutableListOf<Long>()
        val byFile = embeddedChapters.groupBy { it.fileId }
        files.forEachIndexed { index, file ->
            offsets += offset
            val embedded = byFile[file.id].orEmpty()
            if (embedded.isEmpty()) chapters += PlaybackChapter(chapters.size, file.name, offset, file.durationMs, index)
            else {
                if (embedded.first().startMs > 0) chapters += PlaybackChapter(chapters.size, file.name, offset, embedded.first().startMs, index)
                embedded.forEach { chapter -> chapters += PlaybackChapter(chapters.size, chapter.title,
                    offset + chapter.startMs, chapter.endMs?.let { it - chapter.startMs }, index, chapter.startMs) }
            }
            offset += file.durationMs ?: 0
        }
        totalDuration = if (files.all { it.durationMs != null }) offset else 0
        val fromEpub = if (totalDuration > 0) epubPlaybackChapters(epubContents, files.map { it.durationMs ?: 0 }, bookTitle) else emptyList()
        cachedChapters = fromEpub.ifEmpty { chapters }; fileOffsets = offsets
    }
    private fun snapshot(): PlaybackState {
        refreshChapterCache()
        val chapters = cachedChapters
        val fileIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val currentOffset = fileOffsets.getOrNull(fileIndex) ?: 0L
        val filePosition = player.currentPosition.coerceAtLeast(0)
        val position = currentOffset + filePosition
        val chapterIndex = (if (totalDuration > 0) chapters.indexOfLast { it.startMs <= position }
            else chapters.indexOfLast { it.fileIndex == fileIndex && it.fileStartMs <= filePosition }).coerceAtLeast(0)
        return PlaybackState(bookId, position, totalDuration, player.isPlaying,
            player.playbackParameters.speed, sleepEndsAt, sleepChapter != null, error,
            chapters = chapters, chapterIndex = chapterIndex, filePositionMs = filePosition, isLoading = player.playbackState == Player.STATE_BUFFERING)
    }
    private fun publish() { app.playbackStore.publish(snapshot()) }
    private suspend fun save() = saveLock.withLock {
        val id = bookId ?: return@withLock
        val state = snapshot()
        app.bookRepository.updateListeningPosition(id, state.positionMs)
        val dao = database.bookDao()
        dao.putMetadata(LibraryMetadataEntity("file:$id", files.getOrNull(player.currentMediaItemIndex)?.id.orEmpty()))
        dao.putMetadata(LibraryMetadataEntity("file_position:$id", state.filePositionMs.toString()))
        // Each book keeps its own speed; with that off, one speed is shared (and is where new books start).
        dao.putMetadata(LibraryMetadataEntity(if (settings.speedPerBook) speedKey(id) else "playback_speed", state.speed.toString()))
        if (player.playbackState == Player.STATE_ENDED) app.bookRepository.setFinished(id, true)
    }
    private suspend fun safelySave() {
        try { save() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Playback continues, but progress couldn't be saved. Check device storage."; publish() }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session
    override fun onTaskRemoved(rootIntent: Intent?) {
        player.pause()
        scope.launch { safelySave(); stopSelf() }
    }
    override fun onDestroy() {
        // Periodic saves and pause/discontinuity saves bound abrupt process-death loss to ~2 seconds.
        scope.cancel()
        shake?.stop(); shake = null
        session?.release(); player.release(); session = null
        app.playbackStore.publish(app.playbackStore.state.value.copy(isPlaying = false, sleepEndsAt = null, sleepAtChapterEnd = false))
        super.onDestroy()
    }
}
