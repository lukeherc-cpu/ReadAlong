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
        var prefs by mutableStateOf(AppPreferences(batteryWarningSeen = true, setupStage = 3))
        val play = object : PlaybackControls {
            val mutable = MutableStateFlow(PlaybackState(bookId = "demo-night-orchard", positionMs = 24_000, durationMs = 216_000,
                chapters = listOf(PlaybackChapter(0,"The orchard gate",0,72_000),PlaybackChapter(1,"Across the river",72_000,72_000),PlaybackChapter(2,"A new beginning",144_000,72_000))))
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
            val entities = com.example.readalong.data.book.DemoBooks.books.mapIndexed { i,b ->
                val file = File(File(app.filesDir,"covers").apply { mkdirs() },"promo-original-$i.png"); files += file
                android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("cat /sdcard/Download/readalong-promo/covers/cover-$i.png")).use { input -> file.outputStream().use { input.copyTo(it) } }
                b.copy(coverUri=FileProvider.getUriForFile(app,"${app.packageName}.files",file).toString(),durationMs=if(i==0)216_000 else b.durationMs)
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
            compose.onNodeWithContentDescription("Sleep timer").performScrollTo().performClick();capture("sleep")
            androidx.test.espresso.Espresso.pressBack()
            compose.onNodeWithText("1.0×").performScrollTo().performClick();capture("speed")
            androidx.test.espresso.Espresso.pressBack()
            TranscriptRepository(db).addBookmark(book.id,24_000,"The orchard gate")
            compose.onNodeWithContentDescription("Bookmarks").performScrollTo().performClick();capture("bookmarks")
            androidx.test.espresso.Espresso.pressBack()
            compose.onNodeWithContentDescription("Chapters").performScrollTo().performClick();capture("chapters")
            androidx.test.espresso.Espresso.pressBack()
            compose.onNodeWithText("More",substring=false).performScrollTo().performClick()
            compose.onNodeWithText("Copy transcription diagnostics",substring=false).performScrollTo();capture("more")
            compose.runOnIdle { reading.openReader(24_000) }; show("reader")
            compose.waitUntil(10_000){compose.onAllNodesWithTag("follow_passages").fetchSemanticsNodes().isNotEmpty()};capture("reader")
            compose.runOnIdle { play.seek(54_000) };capture("reader-later")
            compose.runOnIdle { reading.setReadOnly(true,24_000) }
            compose.waitUntil(10_000){reading.readOnly.value};capture("read-only-light")
            app.bookReaderPreferences.save(ReaderPreferences(fontFamily="LORA",fontSize=21f,theme="DARK"));capture("read-only-dark")
            compose.runOnIdle { reading.setReadOnly(false,24_000) };show("appearance");capture("appearance")
            show("queue");compose.waitUntil(10_000){compose.onAllNodesWithText("The Night Orchard").fetchSemanticsNodes().isNotEmpty()};capture("queue")
            show("settings");capture("settings")
            show("model");capture("model")
            show("background");capture("background")
            show("actions");capture("book-actions")
            show("sources");capture("sources")
        } finally {
            compose.runOnIdle{models.clear()}
            epubs.remove("demo-night-orchard")
            queueIds.forEach{app.database.bookDao().removeBook(it)}
            app.appPreferences.update{old};app.readerPreferences.save(oldReader);app.bookReaderPreferences.save(oldBook)
            files.forEach{it.delete()};db.close()
        }
    }
}
