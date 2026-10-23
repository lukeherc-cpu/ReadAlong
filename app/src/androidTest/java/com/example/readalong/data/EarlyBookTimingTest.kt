package com.example.readalong.data

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.epub.*
import com.example.readalong.data.local.*
import com.example.readalong.data.reading.*
import com.example.readalong.data.transcription.WhistleResult
import com.example.readalong.domain.reading.*
import com.example.readalong.domain.transcription.TranscriptionInput
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class EarlyBookTimingTest {
    @Test fun recordedCirceAndHarryPotterPassagesKeepConfirmedStartsAndFillOnlyBoundedGaps() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("privateEarlyBooks")=="true" || InstrumentationRegistry.getArguments().getString("privateOpenings")=="true")
        val openingFixtures = InstrumentationRegistry.getArguments().getString("privateOpenings") == "true"
        val fixtureFolder = if (openingFixtures) "readalong-opening-tests" else "readalong-early-tests"
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db=Room.inMemoryDatabaseBuilder(app,ReadAlongDatabase::class.java).build()
        val directory=File(app.cacheDir,"early-${System.nanoTime()}").apply {mkdirs()}
        fun copy(path:String):File {
            val f=File(directory,path.substringAfterLast('/'))
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat /sdcard/Download/$fixtureFolder/$path")).use { input -> f.outputStream().use { input.copyTo(it) } }
            return f
        }
        val repo=ImportedEpubRepository(app,db)
        try {
            val manifest=JSONArray(copy("manifest.json").readText())
            for (i in 0 until manifest.length()) {
                val book=manifest.getJSONObject(i);val key=book.getString("key")
                db.bookDao().insertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id=key,isDemo=false)))
                db.readingDao().putVersion(ReadableVersionEntity(key,"PARTIAL_READY",null,0,1,null,0,transcriptRevision=1))
                val samples=book.getJSONArray("samples")
                val raw=(0 until samples.length()).flatMap { n ->
                    val sample=samples.getJSONObject(n);val start=sample.getLong("seconds")*1000
                    WhistleResult.segments(copy(sample.getString("json")).readText(),TranscriptionInput(key,"audio","local",start,start+30_000,false))
                }
                db.readingDao().putSegments(raw.map {it.toEntity()})
                repo.import(key,Uri.fromFile(copy("$key/reference.epub"))); assertTrue(repo.align(key))
                val edition=db.importedEpubDao().find(key)!!
                val rows=db.importedEpubDao().blocks(key,edition.editionId).map {it.toReadingSegment()}
                val sources=raw.associateBy {it.id}
                var known=0;var estimates=0
                val lines=rows.flatMap { p -> sentenceCues(p,sources).mapNotNull { c ->
                    if(c.audioStartMs==null) null else {
                        if(c.estimated) estimates++ else known++
                        assertTrue(c.audioEndMs!!>c.audioStartMs)
                        "${c.audioStartMs}-${c.audioEndMs} ${if(c.estimated) "ESTIMATE" else "MATCH"} ${p.text.substring(c.start,c.end)}"
                    }
                } }
                instrumentation.sendStatus(0,android.os.Bundle().apply {putString("early_$key","$known confirmed, $estimates estimated\n"+lines.joinToString("\n"))})
                assertTrue(known >= if (key == "harry") 2 else 5)
                if (key == "harry") {
                    val p=rows.first {it.text.startsWith("The two men appeared")}
                    assertEquals(67920L,sentenceCues(p,sources).first().audioStartMs)
                }
                if (key == "circe") {
                    val p=rows.first {it.text.contains("I see her arrange her dress")}
                    val cue=sentenceCues(p,sources).first {p.text.substring(it.start,it.end).startsWith("I see her arrange")}
                    assertEquals(155840L,cue.audioStartMs)
                }
                if (key == "hail-mary" || key == "mistborn") {
                    val phrase = if (key == "hail-mary") "What’s two plus two?" else "Sometimes, I worry"
                    val p = rows.first { it.text.contains(phrase) }
                    val cue = sentenceCues(p, sources).first { p.text.substring(it.start, it.end).contains(phrase) }
                    assertFalse(cue.estimated)
                    assertEquals(if (key == "hail-mary") 25040L else 102320L, cue.audioStartMs)
                    for (offset in cue.start until cue.end) assertEquals(cue, sentenceAt(sentenceCues(p, sources), offset))
                    assertTrue(rows.none { it.text.contains("All rights reserved", ignoreCase = true) })
                    assertTrue(db.bookDao().metadata("epub_contents:${edition.editionId}")!!.length > 2)
                }
                val saved=db.readingDao().segments(key).map {it.toSegment()}
                assertEquals(raw.sortedBy {it.startMs},saved)
                repo.remove(key)
            }
        }finally{db.close();directory.deleteRecursively()}
    }
}
