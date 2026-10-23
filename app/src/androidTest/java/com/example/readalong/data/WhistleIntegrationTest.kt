package com.example.readalong.data

import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.transcription.*
import com.example.readalong.domain.alignment.EpubAligner
import com.example.readalong.domain.epub.EpubBlock
import com.example.readalong.domain.epub.EpubParser
import com.example.readalong.domain.model.TranscriptSegment
import com.example.readalong.domain.reading.sentenceCues
import com.example.readalong.domain.transcription.TranscriptionInput
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.abs

class WhistleIntegrationTest {
    private val app get() = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    private fun copy(name: String, file: File) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat /sdcard/Download/$name")).use { input ->
            file.outputStream().use { output -> input.copyTo(output, 65536) }
        }
    }
    @Before fun installModel() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("whistleModel") == "true")
        if (!app.speechModel.available()) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat /sdcard/Download/readalong-whistle.cact")).use { app.speechModel.install(it) }
        }
        Unit
    }
    private suspend fun recognize(wave: File, start: Long = 0, duration: Long = 30_000, fileStart: Long = 0): List<TranscriptSegment> {
        val result = mutableListOf<TranscriptSegment>()
        RecognitionConnection(app).withSession { connection ->
            WhistleTranscriptionEngine(app, app.speechModel, connection = connection).transcribe(TranscriptionInput(
                "whistle-reference", "one", FileProvider.getUriForFile(app,"${app.packageName}.files",wave).toString(),start,start+duration,false,fileStartMs=fileStart)).collect { result += it }
        }
        return result
    }
    @Test fun nativeWordTimesKeepAllEpubSentencesPastMusic() = runBlocking {
        val wave = File(File(app.cacheDir,"exports").apply { mkdirs() },"whistle-music.wav")
        val outputs = mutableListOf<List<TranscriptSegment>>()
        try {
            for (name in listOf("natural.wav","natural-effects.wav")) {
                copy("readalong-$name",wave)
                val speech = recognize(wave)
                val text = "God as a direct consequence of the sin which man thus punished had given her a lovely child. Whose place was on that same dishonoured bosom to connect her parent for ever with the race and descent of mortals. And to be finally a blessed soul in heaven."
                val aligned = EpubAligner().align(listOf(EpubBlock(0,0,"one",text)),speech)
                assertTrue("Actual Whistle recognition must match the EPUB: ${aligned.coverage}",aligned.coverage>.85f)
                val cues = sentenceCues(TranscriptSegment("epub:one","whistle-reference","one",-1,-1,text,anchors=aligned.blocks.single().anchors),speech.associateBy{it.id})
                assertEquals(3,cues.size)
                assertTrue(cues.all{it.audioStartMs!=null})
                assertTrue(cues.zipWithNext().all{(a,b)->b.audioStartMs!!>a.audioStartMs!!})
                assertTrue(speech.flatMap{it.words}.all{it.startMs>=0 && it.endMs<=30_000})
                outputs+=speech
            }
            val a=outputs[0].flatMap{it.words}.first{it.word.startsWith("child",true)}.startMs
            val b=outputs[1].flatMap{it.words}.first{it.word.startsWith("child",true)}.startMs
            assertTrue("Music offset must be 5s, was ${b-a}",abs(b-a-5000)<=320)
            instrumentation.sendStatus(0,android.os.Bundle().apply{putString("whistle_music","offset=${b-a}ms; cleanWords=${outputs[0].sumOf{it.words.size}}; musicWords=${outputs[1].sumOf{it.words.size}}")})
        } finally {wave.delete()}
    }
    @Test fun actualMistbornMusicAndEffectsMatchTheFullPublisherEpub() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("privateMistborn")=="true")
        val epub=File(app.cacheDir,"whistle-mistborn.epub")
        val audio=File(File(app.cacheDir,"exports").apply{mkdirs()},"whistle-mistborn.mp3")
        try {
            copy("readalong-mistborn/reference.epub",epub);copy("readalong-mistborn/reference.mp3",audio)
            val blocks=EpubParser().parse(epub).blocks
            val speech=mutableListOf<TranscriptSegment>()
            RecognitionConnection(app).withSession { connection ->
                for (seconds in 180..330 step 30) {
                    WhistleTranscriptionEngine(app,app.speechModel,connection=connection).transcribe(TranscriptionInput(
                        "whistle-reference","one",FileProvider.getUriForFile(app,"${app.packageName}.files",audio).toString(),
                        seconds*1000L,(seconds+30)*1000L,false,fileStartMs=seconds*1000L)).collect{speech+=it}
                }
            }
            assertTrue(speech.size>=12)
            val result=EpubAligner().align(blocks,speech)
            val sources=speech.associateBy{it.id}
            val positions=result.blocks.filter{it.anchors.isNotEmpty()}.flatMap{block ->
                sentenceCues(TranscriptSegment("epub:${block.block.ordinal}","whistle-reference","one",-1,-1,block.block.text,anchors=block.anchors),sources)
                    .mapNotNull{it.audioStartMs}
            }
            assertTrue("Mistborn must reliably match original wording, coverage=${result.coverage}",result.coverage>=.75f)
            assertTrue("Publisher sentences must have usable locators",positions.size>=25)
            assertTrue(positions.all{it in 180_000..360_000})
            assertTrue(positions.zipWithNext().all{(a,b)->b>=a})
            val baseline = (180..330 step 30).flatMap { seconds ->
                TimedRecognitionResult.segments(String(shell("cat /sdcard/Download/readalong-mistborn/mistborn-$seconds.json")),
                    TranscriptionInput("reference", "one", "local", seconds*1000L, (seconds+30)*1000L, false))
            }
            fun locators(rows: List<TranscriptSegment>): Map<String,Long> {
                val aligned = EpubAligner().align(blocks, rows)
                return aligned.blocks.flatMap { block ->
                    sentenceCues(TranscriptSegment("epub:${block.block.ordinal}", "reference", "one", -1,-1,block.block.text,anchors=block.anchors),rows.associateBy{it.id})
                        .mapNotNull { cue -> cue.audioStartMs?.let { "${block.block.ordinal}:${cue.start}" to it } }
                }.toMap()
            }
            val reference = locators(baseline)
            val differences = locators(speech).mapNotNull { (key,time) -> reference[key]?.let { abs(it-time) } }.sorted()
            assertTrue("Need enough independently timed reference sentences", differences.size>=20)
            assertTrue("Sentence taps must not drift several sentences: $differences", differences[differences.size/2]<=800)
            assertTrue("Every shared sentence must remain near its reference beginning", differences.last()<=1500)
            instrumentation.sendStatus(0,android.os.Bundle().apply { putString("whistle_sentence_reference", "shared=${differences.size}; median=${differences[differences.size/2]}ms; p95=${differences[(differences.size-1)*95/100]}ms; max=${differences.last()}ms") })
            instrumentation.sendStatus(0,android.os.Bundle().apply{putString("whistle_mistborn","${speech.size} recognized sentences; ${result.matchedSegments} matched; coverage=${result.coverage}; ${positions.size} publisher sentence starts")})
        } finally {epub.delete();audio.delete()}
    }
    @Test fun wholeBookSavesEveryCheckpointOutsideTheAppAndReleasesItsModel() = runBlocking {
        val audio=File(File(app.cacheDir,"exports").apply{mkdirs()},"whistle-whole.wav")
        var book:String?=null
        try {
            copy("readalong-natural-loop.wav",audio)
            book=app.audioImporter.importFiles(listOf(FileProvider.getUriForFile(app,"${app.packageName}.files",audio)),false).bookIds.single()
            app.processing.transcribeNext(book)
            shell("input keyevent KEYCODE_HOME")
            val ready=withTimeout(600_000){app.processing.observe(book).first{it?.state in listOf("READY","FAILED")}}!!
            assertEquals(ready.message,"READY",ready.state)
            assertEquals(SpeechModelStore.ID,ready.engineId)
            assertEquals(3,ready.completedUnits)
            assertTrue(app.database.readingDao().segments(book).any{it.startMs>=60_000})
            assertNotNull(app.database.readingDao().findVersion(book)!!.generatedUri)
            withTimeout(10_000){while(com.example.readalong.data.processing.AudiobookTranscriptionService.activeBook!=null)delay(50)}
        } finally {book?.let{app.processing.cancel(it);app.database.bookDao().removeBook(it)};audio.delete()}
    }
    @Test fun downloadWorkerInstallsOnlyWhistleAndInvalidImportsPreserveIt() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("networkModels")=="true")
        val manager=WorkManager.getInstance(app)
        val request=ModelInstallWorker.request()
        manager.enqueueUniqueWork(ModelInstallWorker.WORK_NAME,ExistingWorkPolicy.REPLACE,request).result.get()
        withTimeout(300_000){while(manager.getWorkInfoById(request.id).get()?.state?.isFinished!=true)delay(500)}
        assertEquals(app.speechModel.status.first().message,WorkInfo.State.SUCCEEDED,manager.getWorkInfoById(request.id).get()!!.state)
        assertTrue(app.speechModel.available())
        assertTrue(runCatching{app.speechModel.install(java.io.ByteArrayInputStream(ByteArray(20)))}.isFailure)
        assertTrue(app.speechModel.available())
        assertEquals(SpeechModelStore.ID,app.speechModel.selectedModel().id)
    }
    @Test fun originalIvanAndMysticExcerptsKeepSentenceSpellingAndAudioStarts() = runBlocking {
        val wave = File(File(app.cacheDir,"exports").apply{mkdirs()},"whistle-publisher.wav")
        val epub = File(app.cacheDir,"whistle-publisher.epub")
        val fixtures = listOf(
            Triple("ivan-90.wav", 90_000L, "<h1>Hello</h1><p>I am Ivan. I am a gorilla. It's not as easy as it looks.</p><h1>Names</h1><p>People call me the Freeway Gorilla. The Ape at Exit 8. The One and Only Ivan.</p>"),
            Triple("mystic-60.wav", 60_000L, "<h1>The Point and the Flats</h1><p>When Sean Devine and Jimmy Marcus were kids, their fathers worked together at the Coleman Candy plant and carried the stench of warm chocolate back home with them.</p>")
        )
        try {
            for ((name, offset, body) in fixtures) {
                copy("readalong-whistle-tests/$name",wave)
                val entries = mapOf("mimetype" to "application/epub+zip", "META-INF/container.xml" to "<container><rootfile full-path=\"package.opf\"/></container>",
                    "package.opf" to "<package><metadata><title>Publisher excerpt</title></metadata><manifest><item id=\"one\" href=\"one.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"one\"/></spine></package>",
                    "one.xhtml" to "<html><body>$body</body></html>")
                java.util.zip.ZipOutputStream(epub.outputStream()).use { zip -> entries.forEach { (path,text) -> zip.putNextEntry(java.util.zip.ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry() } }
                val speech=recognize(wave,offset)
                val blocks=EpubParser().parse(epub).blocks
                val aligned=EpubAligner().align(blocks,speech)
                val paragraph=aligned.blocks.first{if(name.startsWith("ivan")) it.block.text.contains("I am a gorilla") else it.block.text.startsWith("When")}
                val cues=sentenceCues(TranscriptSegment("epub:excerpt","whistle-reference","one",-1,-1,paragraph.block.text,anchors=paragraph.anchors),speech.associateBy{it.id})
                if(name.startsWith("ivan")) {
                    assertTrue(speech.any{it.text.contains("guerrilla",true)})
                    val gorilla=cues.first{paragraph.block.text.substring(it.start,it.end).contains("I am a gorilla")}
                    assertNotNull("Misheard gorilla must keep the correct sentence locator",gorilla.audioStartMs)
                    assertTrue("Ivan sentence start: ${gorilla.audioStartMs}",abs(gorilla.audioStartMs!!-105_840L)<=400)
                    assertTrue(gorilla.audioEndMs!!>=106_500L)
                } else {
                    assertNotNull(cues.first().audioStartMs)
                    val whenWord=speech.flatMap{it.words}.first{it.word.equals("When",true)}
                    assertEquals("Tap must start narration, not the chapter heading",whenWord.startMs,cues.first().audioStartMs)
                }
                instrumentation.sendStatus(0,android.os.Bundle().apply{putString("whistle_excerpt","$name: ${cues.map{it.audioStartMs}}")})
            }
        } finally{wave.delete();epub.delete()}
    }

    @Test fun nativeFailureCancellationAndFloatHeadroomLeaveTheAppUsable() = runBlocking {
        val pcm=File(app.cacheDir,"whistle-native-guard.pcm")
        val wave=File(File(app.cacheDir,"exports").apply{mkdirs()},"whistle-cancel.wav")
        try {
            app.speechModel.withModel { model ->
                pcm.writeBytes(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN).array())
                assertTrue(runCatching{RecognitionConnection(app).transcribe(model,pcm)}.isFailure)
                val buffer=java.nio.ByteBuffer.allocate(16000*4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                repeat(16000){buffer.putFloat(1.05f)};pcm.writeBytes(buffer.array())
                assertTrue(org.json.JSONObject(RecognitionConnection(app).transcribe(model,pcm)).has("text"))
                copy("readalong-natural.wav",wave)
                AudioDecoder(app).decode(FileProvider.getUriForFile(app,"${app.packageName}.files",wave),0,30_000,pcm)
                val began=CompletableDeferred<Unit>()
                val work=async{RecognitionConnection(app).withSession { connection ->
                    connection.transcribe(model,pcm){if(!began.isCompleted)began.complete(Unit)}
                }}
                withTimeout(30_000){began.await()};work.cancelAndJoin()
                pcm.writeBytes(ByteArray(16000*4))
                var recovered:String?=null
                for(attempt in 0..2){
                    try {recovered=RecognitionConnection(app).transcribe(model,pcm);break}
                    catch(_:java.io.IOException){delay(250)}
                }
                assertNotNull("Private recognition must recover after cancellation",recovered)
                assertEquals("",org.json.JSONObject(recovered!!).getString("text"))
            }
            assertTrue(app.speechModel.available())
        } finally {pcm.delete();wave.delete()}
    }

}
