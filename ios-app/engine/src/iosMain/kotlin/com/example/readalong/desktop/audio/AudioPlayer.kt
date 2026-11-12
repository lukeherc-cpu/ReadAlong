package com.example.readalong.desktop.audio

import com.example.readalong.compat.*
import com.example.readalong.desktop.core.LibraryBook
import com.example.readalong.desktop.core.LibraryStore
import com.example.readalong.desktop.core.SettingsStore
import com.example.readalong.desktop.platform.onMain
import com.example.readalong.desktop.platform.toNSData
import java.io.File
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import platform.AVFAudio.*
import platform.AVFoundation.*
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.*
import platform.Foundation.*
import platform.MediaPlayer.*
import platform.UIKit.UIImage
import platform.darwin.NSObjectProtocol

data class PlaybackState(
    val bookId: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val sleepEndsAt: Long? = null,
    val sleepAtChapterEnd: Boolean = false,
    val error: String? = null,
)

/**
 * The desktop player's API over AVPlayer. A book's files become one AVMutableComposition, so the timeline, the
 * lock screen's scrubber and Control Center see one book; speed changes keep the pitch (the spoken-word time-domain
 * algorithm); playback carries on with the screen locked (the audio background mode) and pauses for calls.
 */
class AudioPlayer(private val scope: CoroutineScope, private val library: LibraryStore, private val settings: SettingsStore) {
    private val mutableState = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = mutableState

    private var player: AVPlayer? = null
    private var book: LibraryBook? = null
    private var endObserver: NSObjectProtocol? = null
    private var interruptionObserver: NSObjectProtocol? = null
    private var artworkFor: String? = null

    init {
        onMain { setUpSession(); setUpRemoteCommands() }
        // Save the listening position, run the sleep timer and keep the lock screen current.
        scope.launch {
            while (isActive) {
                delay(500)
                val current = mutableState.value
                val id = current.bookId ?: continue
                if (!current.playing) continue
                val position = positionNow()
                mutableState.update { it.copy(positionMs = position) }
                library.update(id) { it.copy(positionMs = position, lastPlayedAt = System.currentTimeMillis(),
                    finished = it.finished || position >= it.durationMs - 1000) }
                val sleep = current.sleepEndsAt
                if (sleep != null && System.currentTimeMillis() >= sleep) { pause(); mutableState.update { it.copy(sleepEndsAt = null) } }
                if (current.sleepAtChapterEnd) {
                    val b = library.book(id)
                    val chapter = b?.chapterAt(position) ?: -1
                    val next = b?.chapters?.getOrNull(chapter + 1)?.startMs
                    if (next != null && position >= next - 400) { pause(); mutableState.update { it.copy(sleepAtChapterEnd = false) } }
                }
                updateNowPlaying()
            }
        }
    }

    private fun setUpSession() {
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, mode = AVAudioSessionModeSpokenAudio, options = 0u, error = null)
        interruptionObserver = NSNotificationCenter.defaultCenter.addObserverForName(AVAudioSessionInterruptionNotification, null, NSOperationQueue.mainQueue) { note ->
            val type = (note?.userInfo?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber)?.unsignedIntegerValue
            if (type == AVAudioSessionInterruptionTypeBegan) { if (mutableState.value.playing) pause() }
        }
        // Headphones unplugged: stop rather than play out loud.
        NSNotificationCenter.defaultCenter.addObserverForName(AVAudioSessionRouteChangeNotification, null, NSOperationQueue.mainQueue) { note ->
            val reason = (note?.userInfo?.get(AVAudioSessionRouteChangeReasonKey) as? NSNumber)?.unsignedIntegerValue
            if (reason == AVAudioSessionRouteChangeReasonOldDeviceUnavailable && mutableState.value.playing) pause()
        }
    }

    private fun setUpRemoteCommands() {
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        center.playCommand.addTargetWithHandler { play(); MPRemoteCommandHandlerStatusSuccess }
        center.pauseCommand.addTargetWithHandler { pause(); MPRemoteCommandHandlerStatusSuccess }
        center.togglePlayPauseCommand.addTargetWithHandler { toggle(); MPRemoteCommandHandlerStatusSuccess }
        val s = settings.value
        center.skipBackwardCommand.preferredIntervals = listOf(NSNumber(int = s.skipBackSeconds))
        center.skipForwardCommand.preferredIntervals = listOf(NSNumber(int = s.skipForwardSeconds))
        center.skipBackwardCommand.addTargetWithHandler { skip(-settings.value.skipBackSeconds * 1000L); MPRemoteCommandHandlerStatusSuccess }
        center.skipForwardCommand.addTargetWithHandler { skip(settings.value.skipForwardSeconds * 1000L); MPRemoteCommandHandlerStatusSuccess }
        center.changePlaybackPositionCommand.addTargetWithHandler { event ->
            (event as? MPChangePlaybackPositionCommandEvent)?.let { seekTo((it.positionTime * 1000).toLong()) }
            MPRemoteCommandHandlerStatusSuccess
        }
        center.nextTrackCommand.enabled = false
        center.previousTrackCommand.enabled = false
    }

    private fun composition(b: LibraryBook): AVPlayerItem {
        val composition = AVMutableComposition()
        val track = composition.addMutableTrackWithMediaType(AVMediaTypeAudio, kCMPersistentTrackID_Invalid)
            ?: throw java.io.IOException("This audio couldn't be played.")
        var at = kCMTimeZero.readValue()
        val options = mapOf<Any?, Any?>(AVURLAssetPreferPreciseDurationAndTimingKey to NSNumber(bool = true))
        for (part in b.parts) {
            val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(part.path), options = options)
            val source = asset.tracksWithMediaType(AVMediaTypeAudio).firstOrNull() as? AVAssetTrack ?: continue
            // Each part sits at the book's own offset for it, so positions match the library's chapters and the transcript.
            val offset = CMTimeMake(part.offsetMs, 1000)
            val length = CMTimeMake(part.durationMs, 1000)
            track.insertTimeRange(CMTimeRangeMake(kCMTimeZero.readValue(), length), source, offset, null)
            at = CMTimeAdd(offset, length)
        }
        return AVPlayerItem(asset = composition).apply {
            audioTimePitchAlgorithm = AVAudioTimePitchAlgorithmTimeDomain
        }
    }

    fun open(bookId: String, play: Boolean = true, positionMs: Long? = null) {
        val b = library.book(bookId) ?: return
        val start = positionMs ?: if (b.finished || b.positionMs >= b.durationMs - 2000) 0 else b.positionMs
        if (b.finished && positionMs == null) library.update(bookId) { it.copy(finished = false) }
        mutableState.value = PlaybackState(bookId, play, start, b.durationMs, b.speed)
        onMain {
            try {
                AVAudioSession.sharedInstance().setActive(true, error = null)
                val item = composition(b)
                val p = player ?: AVPlayer().also { player = it }
                p.replaceCurrentItemWithPlayerItem(item)
                p.volume = settings.value.volume
                book = b
                endObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
                endObserver = NSNotificationCenter.defaultCenter.addObserverForName(AVPlayerItemDidPlayToEndTimeNotification, item, NSOperationQueue.mainQueue) { finishedBook() }
                p.seekToTime(CMTimeMake(start, 1000), kCMTimeZero.readValue(), kCMTimeZero.readValue()) { _ -> }
                if (play) p.playImmediatelyAtRate(b.speed) else p.pause()
                artworkFor = null
                updateNowPlaying()
            } catch (failure: Throwable) {
                mutableState.update { it.copy(playing = false, error = failure.message ?: "This audio couldn't be played.") }
            }
        }
    }

    private fun finishedBook() {
        val b = book ?: return
        mutableState.update { it.copy(playing = false, positionMs = b.durationMs) }
        library.update(b.id) { it.copy(positionMs = b.durationMs, finished = true) }
        updateNowPlaying()
    }

    fun toggle() { if (mutableState.value.playing) pause() else play() }
    fun play() {
        val id = mutableState.value.bookId ?: return
        if (book == null || player?.currentItem == null) { open(id); return }
        mutableState.update { it.copy(playing = true, error = null) }
        onMain { AVAudioSession.sharedInstance().setActive(true, error = null); player?.playImmediatelyAtRate(mutableState.value.speed); updateNowPlaying() }
        library.update(id) { it.copy(lastPlayedAt = System.currentTimeMillis()) }
    }
    fun pause() {
        val position = positionNow()
        mutableState.update { it.copy(playing = false, positionMs = position) }
        onMain { player?.pause(); updateNowPlaying() }
        mutableState.value.bookId?.let { id -> library.update(id) { it.copy(positionMs = position) } }
    }
    fun seekTo(positionMs: Long) {
        val b = book ?: library.book(mutableState.value.bookId ?: return) ?: return
        val target = positionMs.coerceIn(0, (b.durationMs - 250).coerceAtLeast(0))
        mutableState.update { it.copy(positionMs = target) }
        library.update(b.id) { it.copy(positionMs = target) }
        onMain { player?.seekToTime(CMTimeMake(target, 1000), kCMTimeZero.readValue(), kCMTimeZero.readValue()) { _ -> updateNowPlaying() } }
    }
    fun skip(deltaMs: Long) = seekTo(positionNow() + deltaMs)
    fun setSpeed(value: Float) {
        val s = value.coerceIn(0.5f, 3f)
        mutableState.update { it.copy(speed = s) }
        mutableState.value.bookId?.let { id -> library.update(id) { it.copy(speed = s) } }
        onMain { player?.let { p -> if (mutableState.value.playing) p.rate = s }; updateNowPlaying() }
    }
    fun setVolume(volume: Float) { settings.update { it.copy(volume = volume.coerceIn(0f, 1f)) }; onMain { player?.volume = volume.coerceIn(0f, 1f) } }
    fun sleepAfter(minutes: Int?) = mutableState.update { it.copy(sleepEndsAt = minutes?.let { m -> System.currentTimeMillis() + m * 60_000L }, sleepAtChapterEnd = false) }
    fun sleepAtChapterEnd() = mutableState.update { it.copy(sleepEndsAt = null, sleepAtChapterEnd = true) }
    fun close() {
        mutableState.value.bookId?.let { id -> val p = positionNow(); library.update(id) { it.copy(positionMs = p) } }
        mutableState.value = PlaybackState()
        onMain {
            player?.pause(); player?.replaceCurrentItemWithPlayerItem(null); book = null
            MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
            AVAudioSession.sharedInstance().setActive(false, withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, error = null)
        }
    }

    /** The book position being heard right now, from AVPlayer's clock (read from any thread). */
    fun positionNow(): Long {
        val p = player ?: return mutableState.value.positionMs
        if (p.currentItem == null) return mutableState.value.positionMs
        val seconds = CMTimeGetSeconds(p.currentTime())
        return if (seconds.isNaN()) mutableState.value.positionMs else (seconds * 1000).toLong()
    }

    /** Title, chapter, cover and position on the lock screen and in Control Center. */
    private fun updateNowPlaying() = onMain {
        val b = book ?: return@onMain
        val s = mutableState.value
        val info = HashMap<Any?, Any?>()
        val position = positionNow()
        val chapter = b.chapters.getOrNull(b.chapterAt(position))?.title
        info[MPMediaItemPropertyTitle] = chapter ?: b.title
        info[MPMediaItemPropertyAlbumTitle] = b.title
        b.author?.let { info[MPMediaItemPropertyArtist] = it }
        info[MPMediaItemPropertyPlaybackDuration] = NSNumber(double = b.durationMs / 1000.0)
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = NSNumber(double = position / 1000.0)
        info[MPNowPlayingInfoPropertyPlaybackRate] = NSNumber(double = if (s.playing) s.speed.toDouble() else 0.0)
        info[MPNowPlayingInfoPropertyDefaultPlaybackRate] = NSNumber(double = s.speed.toDouble())
        info[MPNowPlayingInfoPropertyMediaType] = NSNumber(unsignedInteger = MPNowPlayingInfoMediaTypeAudio)
        val cover = b.coverPath
        if (cover != null) {
            if (artworkFor != cover) artworkFor = cover
            UIImage.imageWithContentsOfFile(cover)?.let { image ->
                info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize = image.size) { _ -> image }
            }
        }
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = info
    }

    companion object {
        const val RATE = 44_100
    }
}
