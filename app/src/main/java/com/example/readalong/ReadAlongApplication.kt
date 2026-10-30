package com.example.readalong

import android.app.Application
import kotlinx.coroutines.*
import androidx.room.withTransaction
import com.example.readalong.data.book.RoomBookRepository
import com.example.readalong.data.local.ReadAlongDatabase
import com.example.readalong.domain.repository.BookRepository
import com.example.readalong.playback.DemoPlaybackController

class ReadAlongApplication : Application() {
    // One database/repository for the application; no global mutable state or DI framework.
    val database by lazy { ReadAlongDatabase.create(this) }
    val audioImporter by lazy { com.example.readalong.data.importing.AudioImporter(this, database, onEpubFound = { id, uri -> importedEpub.import(id, uri) }, onImported = { processing.imported(it) }, onTranscriptFound = { id, uri ->
        com.example.readalong.data.transcription.DesktopTranscriptImporter(this, database, processing, generatedEpub).import(id, uri)
        if (database.importedEpubDao().find(id) != null) importedEpub.preference(id, "EPUB")
        importedEpub.requestAlignment(id)
    }, onProgressFound = { id, location -> folderProgress.sync(id, location) }) }
    /** Listening progress shared with ReadAlong for Windows through the audiobook folder. */
    val folderProgress by lazy { com.example.readalong.data.importing.FolderProgressSync(this, database,
        playing = { id -> playbackStore.state.value.let { it.bookId == id && it.isPlaying } },
        moved = { id, position -> if (playbackStore.state.value.bookId == id) withContext(Dispatchers.Main) { playback.seek(position) } }) }
    /** Folders kept in step with a linked ReadAlong Desktop over the home network; a sync rescans the phone folder it changed. */
    val folderLink by lazy { com.example.readalong.data.link.FolderLinkSync(this, com.example.readalong.data.link.LinkStore(this)) { uri ->
        // A phone folder chosen only for syncing joins the library too.
        if (database.mediaDao().folder(uri.toString()) != null) audioImporter.scan(uri)
        else audioImporter.addFolder(uri, com.example.readalong.data.importing.FolderMode.LIBRARY, persistAccess = false)
    } }
    val bookRepository: BookRepository by lazy {
        RoomBookRepository(database)
    }
    val speechModel by lazy { com.example.readalong.data.transcription.SpeechModelStore(this) }
    val whistleEngine by lazy { com.example.readalong.data.transcription.WhistleTranscriptionEngine(this, speechModel) }
    val processing by lazy { com.example.readalong.data.processing.ProcessingRepository(this, database, speechModel) }
    @Volatile var appearanceSettings = com.example.readalong.data.preferences.AppPreferences()
        private set
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.example.readalong.data.diagnostics.BackgroundFailures.handler("Application"))
    override fun onCreate() {
        super.onCreate()
        val processName = if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName()
            else java.io.File("/proc/self/cmdline").readText().substringBefore('\u0000')
        com.example.readalong.data.diagnostics.CrashDiagnostics.install(this)
        com.example.readalong.data.diagnostics.BackgroundFailures.install(this)
        if (processName != packageName) return
        // The launcher icon and launch colors switch once no ReadAlong screen is showing (see LaunchAppearance).
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) = com.example.readalong.ui.launch.LaunchAppearance.activityStarted()
            override fun onActivityStopped(activity: android.app.Activity) {
                if (com.example.readalong.ui.launch.LaunchAppearance.activityStopped()) applicationScope.launch {
                    try { com.example.readalong.ui.launch.LaunchAppearance.flush(this@ReadAlongApplication) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { android.util.Log.w("ReadAlong", "Launcher appearance not applied: ${failure.javaClass.simpleName}") }
                }
            }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        applicationScope.launch { appPreferences.preferences.collect {
            appearanceSettings = it
            // A launcher-icon switch failure must not end this collector or the process:
            // appearance updates for the rest of the session depend on it.
            try { com.example.readalong.ui.launch.LaunchAppearance.request(this@ReadAlongApplication, it) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { android.util.Log.w("ReadAlong", "Launcher appearance not applied: ${failure.javaClass.simpleName}") }
        } }
        val powerReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                applicationScope.launch {
                    try { processing.powerChanged() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { android.util.Log.w("ReadAlong", "Power recovery will retry through WorkManager.") }
                }
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(this, powerReceiver,
            android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_POWER_CONNECTED)
                addAction(android.content.Intent.ACTION_POWER_DISCONNECTED)
                addAction(android.content.Intent.ACTION_BATTERY_CHANGED)
            }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)

        applicationScope.launch {
            // Keep progress cards alive for the whole process: one transient database or
            // notification error must not stop updates until the next app launch.
            while (isActive) {
                try { com.example.readalong.data.transcription.TranscriptionNotifications.observe(this@ReadAlongApplication) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { android.util.Log.w("ReadAlong", "Transcription notifications restarting after ${failure.javaClass.simpleName}.") }
                delay(5_000)
            }
        }
        applicationScope.launch {
            // Each pause writes the book's progress beside it, for ReadAlong for Windows to pick up.
            var wasPlaying: String? = null
            playbackStore.state.collect { state ->
                val stopped = wasPlaying?.takeIf { state.bookId != it || !state.isPlaying }
                wasPlaying = state.bookId?.takeIf { state.isPlaying }
                if (stopped != null) launch {
                    delay(1_500) // Let the player save its position first.
                    try { folderProgress.push(stopped) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { }
                }
            }
        }
        applicationScope.launch {
            // A linked computer: catch up when the app opens (on Wi-Fi), and every few hours after.
            if (folderLink.store.value.pairs.isNotEmpty()) {
                com.example.readalong.data.link.LinkSyncWorker.schedule(this@ReadAlongApplication)
                com.example.readalong.data.link.LinkSyncWorker.syncNow(this@ReadAlongApplication)
            }
        }
        applicationScope.launch {
            try { com.example.readalong.data.transcription.AutomaticModelDownload.ensure(this@ReadAlongApplication, speechModel) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { android.util.Log.w("ReadAlong", "Automatic model setup will retry at next launch.") }
        }
        applicationScope.launch {
            // Recognition scratch files never contain book metadata. Only remove abandoned files, not active inputs.
            listOf(java.io.File(filesDir, "recognition"), java.io.File(cacheDir, "recognition")).forEach { dir ->
                dir.listFiles()?.filter { it.isFile && System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
            }
            try { speechModel.cleanAbandonedImports(); processing.recover(); importedEpub.recover() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { android.util.Log.w("ReadAlong", "Background preparation recovery will retry when requested.") }
        }
    }
    fun advanceTranscriptionQueue() {
        applicationScope.launch {
            delay(250) // Finish foreground teardown before arming the next durable job.
            try { processing.advanceQueue() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { android.util.Log.w("ReadAlong", "Queue handoff will retry on app launch.") }
        }
    }
    fun transcriptionInterrupted(bookId: String, workId: String, reason: String = "Your ReadAlong stopped in the background.") {
        applicationScope.launch {
            try {
                delay(250) // Let foreground teardown and explicit-pause state settle first.
                processing.recoverInterrupted(bookId, workId, reason)
            } catch (_: Exception) { android.util.Log.w("ReadAlong", "The durable transcription guard will retry recovery.") }
        }
    }
    val generatedEpub by lazy { com.example.readalong.data.epub.GeneratedEpubRepository(this, database) }
    val importedEpub by lazy { com.example.readalong.data.epub.ImportedEpubRepository(this, database) }
    val appPreferences by lazy { com.example.readalong.data.preferences.AppPreferencesStore(this) }
    val bookReaderPreferences by lazy { com.example.readalong.data.preferences.ReaderPreferencesStore.forBooks(this) }
    val readerPreferences by lazy { com.example.readalong.data.preferences.ReaderPreferencesStore(this) }
    val transcriptRepository by lazy { com.example.readalong.data.reading.TranscriptRepository(database) }
    val playbackStore by lazy { com.example.readalong.playback.PlaybackStore() }
    val playback by lazy { com.example.readalong.playback.PlaybackCoordinator(this, bookRepository, database, playbackStore) }
}
