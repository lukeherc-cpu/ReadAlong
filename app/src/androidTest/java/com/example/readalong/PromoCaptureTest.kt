package com.example.readalong

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.readalong.data.local.*
import com.example.readalong.data.preferences.*
import com.example.readalong.data.processing.*
import com.example.readalong.data.reading.*
import com.example.readalong.data.epub.*
import com.example.readalong.domain.model.Book
import com.example.readalong.playback.*
import com.example.readalong.ui.reading.*
import com.example.readalong.ui.library.*
import com.example.readalong.ui.player.*
import com.example.readalong.ui.theme.*
import com.example.readalong.ui.transcription.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in capture only: original demonstration content, no private books, no real recognition. */
class PromoCaptureTest {
    @get:Rule val compose = createComposeRule()
    @Test fun captureOriginalDemonstrationScreens() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("promo") == "true")
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val old = app.appPreferences.preferences.first()
        val oldReader = app.readerPreferences.preferences.first()
        val oldBook = app.bookReaderPreferences.preferences.first()
        val epubs = ImportedEpubRepository(app, db)
        val models = androidx.lifecycle.ViewModelStore()
        val output = File(app.getExternalFilesDir(null), "promo-captures").apply { mkdirs() }
        val files = mutableListOf<File>()
        val queueIds = (0..3).map { "promo-queue-$it" }
        var page by mutableStateOf("library")
        var prefs by mutableStateOf(AppPreferences(batteryWarningSeen = true, setupStage = 3, longPressTipHidden = true, homeGuideHidden = true, playerGuideHidden = true, readerGuideHidden = true, readAlongExplained = true, supportPromptOff = true))
        val play = object : PlaybackControls {
            val mutable = MutableStateFlow(PlaybackState(bookId = "demo-night-orchard", positionMs = 24_000, durationMs = 216_000,
                chapters = listOf(PlaybackChapter(0,"Chapter 1",0,72_000),PlaybackChapter(1,"Chapter 2",72_000,72_000),PlaybackChapter(2,"Chapter 3",144_000,72_000))))
            override val state = mutable.asStateFlow()
            override fun select(book: Book) { }
            override fun pause() { mutable.value = mutable.value.copy(isPlaying = false) }
            override fun play() { mutable.value = mutable.value.copy(isPlaying = true) }
            override fun listenFrom(positionMs: Long) { seek(positionMs); play() }
            override fun toggle() { mutable.value = mutable.value.copy(isPlaying = !mutable.value.isPlaying) }
            override fun seek(positionMs: Long) { mutable.value = mutable.value.copy(positionMs = positionMs) }
            override fun skip(deltaMs: Long) { seek(mutable.value.positionMs + deltaMs) }
            override fun speed(value: Float) { mutable.value = mutable.value.copy(speed = value) }
            override fun sleep(minutes: Int?) { }
            override fun sleepAtChapterEnd() { }
            override fun chapter(index: Int) { seek(index * 72_000L) }
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun capture(name: String) {
            compose.waitForIdle(); android.os.SystemClock.sleep(500)
            val bitmap = requireNotNull(automation.takeScreenshot())
            File(output,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        }
        fun show(value: String) { compose.runOnIdle { page = value }; compose.waitForIdle() }
        try {
            app.appPreferences.update { prefs }
            app.readerPreferences.save(ReaderPreferences(fontFamily="LORA", fontSize=21f, lineSpacing=1.6f, theme="DARK"))
            app.bookReaderPreferences.save(ReaderPreferences(fontFamily="LORA", fontSize=21f, lineSpacing=1.6f, theme="LIGHT"))
            // Optional real titles for the covers pushed beside them (promo only): one "Title|Author" per line.
            val realBooks = runCatching { android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("cat /sdcard/Download/readalong-promo/titles.txt")).bufferedReader().readLines()
                .mapNotNull { line -> line.split("|").takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } } }.getOrDefault(emptyList())
            val entities = com.example.readalong.data.book.DemoBooks.books.mapIndexed { i,b ->
                val file = File(File(app.filesDir,"covers").apply { mkdirs() },"promo-original-$i.png"); files += file
                android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("cat /sdcard/Download/readalong-promo/covers/cover-$i.png")).use { input -> file.outputStream().use { input.copyTo(it) } }
                val real = realBooks.getOrNull(i)
                b.copy(coverUri=FileProvider.getUriForFile(app,"${app.packageName}.files",file).toString(),durationMs=if(i==0)216_000 else b.durationMs,
                    title=real?.first ?: b.title, author=real?.second ?: b.author)
            }
            db.bookDao().insertBooks(entities)
            db.bookDao().insertProgress(com.example.readalong.data.book.DemoBooks.progress.map { if(it.bookId=="demo-night-orchard") it.copy(audioPositionMs=24_000) else it })
            val books = entities.map { b -> BookWithProgress(b, com.example.readalong.data.book.DemoBooks.progress.find { it.bookId==b.id }?.let { if(b.id=="demo-night-orchard")it.copy(audioPositionMs=24_000)else it }).toBook() }
            val book = books.first().copy(isDemo=false)
            val processing = ProcessingRepository(app,db,app.speechModel,launchPreparation={error("Capture must not launch recognition")})
            val reading = ReadingViewModel(book.id,db,TranscriptRepository(db),app.readerPreferences,GeneratedEpubRepository(app,db),processing,epubs,app.bookReaderPreferences)
            models.put("reader",reading)
            TranscriptRepository(db).prepareDemo(book.id)
            val epub = File(File(app.cacheDir,"exports").apply{mkdirs()},"promo-original.epub");files+=epub
            java.util.zip.ZipOutputStream(epub.outputStream()).use { zip ->
                val prose = DemoTranscript.segments(book.id).joinToString("") { "<p>${it.text.replace("&","&amp;")}</p>" }
                mapOf("mimetype" to "application/epub+zip","META-INF/container.xml" to "<container><rootfile full-path='package.opf'/></container>",
                    "package.opf" to "<package><metadata><title>The Night Orchard</title></metadata><manifest><item id='one' href='one.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='one'/></spine></package>",
                    "one.xhtml" to "<html><body><h1>The Orchard Gate</h1>$prose</body></html>").forEach{(path,text)->zip.putNextEntry(java.util.zip.ZipEntry(path));zip.write(text.toByteArray());zip.closeEntry()}
            }
            epubs.import(book.id,FileProvider.getUriForFile(app,"${app.packageName}.files",epub));epubs.align(book.id)
            val queueModel = androidx.lifecycle.ViewModelProvider(models,QueueViewModel.factory(app))[QueueViewModel::class.java]
            for(i in 0..3) {
                app.database.bookDao().upsertBooks(listOf(entities[i].copy(id=queueIds[i],isDemo=false)))
                val plan=(0 until 100).map{n->com.example.readalong.domain.transcription.TranscriptionInput(queueIds[i],"chapter", "content://promo/audio",n*30_000L,(n+1)*30_000L,false,n*30_000L)}
                app.database.processingDao().put(ProcessingJobEntity(queueIds[i],"promo-display",com.example.readalong.data.transcription.SpeechModelStore.ID,
                    listOf("PROCESSING","WAITING_QUEUE","CANCELLED","WAITING_SELECTION")[i],if(i==0)42 else if(i==2)18 else 0,100,
                    if(i==0)"Preparing readable text" else null,System.currentTimeMillis(),TranscriptionPlan.encode(plan),queuePosition=-1000L+i))
            }
            compose.setContent {
                val state by play.state.collectAsState()
                CompositionLocalProvider(LocalAppSettings provides prefs) { ReadAlongTheme(light=prefs.libraryLight) { Surface(Modifier.fillMaxSize()) {
                    key(page) {
                        when(page) {
                            "library","sources","model","background","actions" -> {
                                LibraryScreen(LibraryUiState.Ready(books),selectedBookId=book.id,onRetry={},onAbout={},onBook={},
                                    miniPlayer={MiniPlayer(book,state,{},{})},importControls={IconButton(onClick={}){Icon(painterResource(R.drawable.ic_add),"Add audiobook")}})
                                if(page=="sources") com.example.readalong.ui.importing.ReadAlongSourceDialog(false,null,false,{}, {_,_->})
                                if(page=="model") com.example.readalong.ui.transcription.ModelScreen(androidx.lifecycle.ViewModelProvider(models, com.example.readalong.ui.transcription.ModelViewModel.factory(app, app.speechModel, app.processing))[com.example.readalong.ui.transcription.ModelViewModel::class.java]) {}
                                if(page=="background") BackgroundPreparationPrompt(app,force=true)
                                if(page=="actions") BookActions(book,androidx.lifecycle.ViewModelProvider(models,BookActionsViewModel.factory(app))[BookActionsViewModel::class.java],{})
                            }
                            "player" -> PlayerScreen(book,state,play,{},{},reading,{}, {})
                            "reader" -> FollowScreen(book,state,play,reading,{})
                            "appearance" -> { FollowScreen(book,state,play,reading,{}); ReaderAppearance(ReaderPreferences(fontFamily="LORA",fontSize=21f),onDismiss={},onSave={}) }
                            "settings" -> com.example.readalong.ui.about.SettingsScreen(app,{},{},{})
                            "queue" -> QueueScreen(queueModel,{})
                        }
                    }
                } } }
            }
            if (InstrumentationRegistry.getArguments().getString("tablet") == "true") {
                capture("tablet-library")
                show("player");capture("tablet-player")
                compose.runOnIdle { reading.openReader(24_000) };show("reader")
                compose.waitUntil(10_000){compose.onAllNodesWithTag("follow_passages").fetchSemanticsNodes().isNotEmpty()};capture("tablet-reader")
                return@runBlocking
            }
            capture("library-light")
            compose.runOnIdle { prefs=prefs.copy(libraryLight=false) };capture("library-dark")
            compose.runOnIdle { prefs=prefs.copy(libraryLight=true) }
            show("player");capture("player")
            runCatching { compose.onNodeWithContentDescription("Sleep timer").also{n->runCatching{n.performScrollTo()}}.performClick();capture("sleep") }.onFailure { android.util.Log.w("PromoCapture", "step failed: ${it.message}") }
            runCatching { androidx.test.espresso.Espresso.pressBack() }.onFailure { android.util.Log.w("PromoCapture", "step failed: ${it.message}") }
            runCatching { compose.onNodeWithText("1.0×").also{n->runCatching{n.performScrollTo()}}.performClick();capture("speed") }.onFailure { android.util.Log.w("PromoCapture", "step failed: ${it.message}") }
            runCatching { androidx.test.espresso.Espresso.pressBack() }.onFailure { android.util.Log.w("PromoCapture", "step failed: ${it.message}") }
            runCatching { TranscriptRepository(db).addBookmark(book.id,24_000,"Chapter 1") }.onFailure { android.util.Log.w("PromoCapture", "step failed: ${it.message}") }
            runCatching { compose.onNodeWithContentDescription("Bookmarks").also{n->runCatching{n.performScrollTo()}}.performClick();capture("bookmarks") }.onFailure { android.util.Log.w("PromoCapture", "step failed: ${it.message}") }
            runCatching { androidx.test.espresso.Espresso.pressBack() }.onFailure { android.util.Log.w("PromoCapture", "step failed: ${it.message}") }
            runCatching { compose.onNodeWithContentDescription("Chapters").also{n->runCatching{n.performScrollTo()}}.performClick();capture("chapters") }.onFailure { android.util.Log.w("PromoCapture", "step failed: ${it.message}") }
            runCatching { androidx.test.espresso.Espresso.pressBack() }.onFailure { android.util.Log.w("PromoCapture", "step failed: ${it.message}") }
            runCatching { compose.onNodeWithText("More",substring=false).also{n->runCatching{n.performScrollTo()}}.performClick() }.onFailure { android.util.Log.w("PromoCapture", "step failed: ${it.message}") }
            runCatching { runCatching{compose.onNodeWithText("Copy transcription diagnostics",substring=false).performScrollTo()};capture("more");androidx.test.espresso.Espresso.pressBack() }.onFailure { android.util.Log.w("PromoCapture", "step failed: ${it.message}") }
            runCatching { show("queue");compose.waitUntil(10_000){compose.onAllNodesWithText(entities[0].title).fetchSemanticsNodes().isNotEmpty()};capture("queue") }.onFailure { android.util.Log.w("PromoCapture", "step failed: ${it.message}") }
            runCatching { show("settings");capture("settings") }.onFailure { android.util.Log.w("PromoCapture", "step failed: ${it.message}") }
            runCatching { show("model");capture("model") }.onFailure { android.util.Log.w("PromoCapture", "step failed: ${it.message}") }
            runCatching { show("background");capture("background") }.onFailure { android.util.Log.w("PromoCapture", "step failed: ${it.message}") }
            runCatching { show("actions");capture("book-actions") }.onFailure { android.util.Log.w("PromoCapture", "step failed: ${it.message}") }
            runCatching { show("sources");capture("sources") }.onFailure { android.util.Log.w("PromoCapture", "step failed: ${it.message}") }
        } finally {
            compose.runOnIdle{models.clear()}
            epubs.remove("demo-night-orchard")
            queueIds.forEach{app.database.bookDao().removeBook(it)}
            app.appPreferences.update{old};app.readerPreferences.save(oldReader);app.bookReaderPreferences.save(oldBook)
            files.forEach{it.delete()};db.close()
        }
    }
}
