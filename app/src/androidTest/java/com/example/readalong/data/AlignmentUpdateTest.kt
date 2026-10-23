package com.example.readalong.data

import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.epub.*
import com.example.readalong.data.local.*
import com.example.readalong.data.processing.ProcessingRepository
import com.example.readalong.data.reading.*
import com.example.readalong.domain.model.*
import com.example.readalong.domain.reading.*
import com.example.readalong.ui.reading.ReadingViewModel
import com.example.readalong.ui.reading.currentPassage
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AlignmentUpdateTest {
    @Test fun savedGapTimingDrivesTapsAndPreviewThenGivesWayToRecognizedWords() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val file = File(app.cacheDir,"gap-${System.nanoTime()}.epub")
        val first = "The keeper opened the garden gate and carried a bright silver lantern."
        val unknown = "Nobody noticed the quiet visitor."
        val last = "At sunrise three small boats crossed the river toward the distant town."
        val entries = mapOf("mimetype" to "application/epub+zip", "META-INF/container.xml" to "<container><rootfile full-path=\"package.opf\"/></container>",
            "package.opf" to """<package><metadata><title>Gap test</title></metadata><manifest><item id="c" href="c.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c"/></spine></package>""",
            "c.xhtml" to "<html><body><p>$first</p><p>$unknown</p><p>$last</p></body></html>")
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name,text) -> zip.putNextEntry(ZipEntry(name));zip.write(text.toByteArray());zip.closeEntry() } }
        val models=ViewModelStore(); val repo=ImportedEpubRepository(app,db)
        try {
            val id="gap-test-${System.nanoTime()}"
            db.bookDao().insertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id=id,isDemo=false,durationMs=30_000)))
            db.readingDao().putVersion(ReadableVersionEntity(id,"PARTIAL_READY",null,0,1,null,0,transcriptRevision=1))
            fun source(key:String,text:String,start:Long)=TranscriptSegment(key,id,"audio",start,start+text.split(' ').size*300,text,
                words=text.split(' ').mapIndexed { i,w -> TranscriptWord(w,start+i*300,start+i*300+250) })
            val a=source("first",first,1000);val c=source("last",last,12_000)
            db.readingDao().putSegments(listOf(a,c).map { it.toEntity() })
            repo.import(id,Uri.fromFile(file));assertTrue(repo.align(id))
            val edition=db.importedEpubDao().find(id)!!
            val rows=db.importedEpubDao().blocks(id,edition.editionId).map { it.toReadingSegment() }
            val gap=rows.single { it.text==unknown };val cue=sentenceCues(gap).single()
            assertTrue(cue.estimated);assertEquals(a.words.last().endMs,cue.audioStartMs);assertEquals(c.startMs,cue.audioEndMs)
            assertEquals(gap.id,currentPassage(rows+listOf(a,c),cue.audioStartMs!!)?.id)
            assertEquals(rows.last().id,currentPassage(rows+listOf(a,c),12_000)?.id)
            val model=ReadingViewModel(id,db,TranscriptRepository(db),app.readerPreferences,GeneratedEpubRepository(app,db),ProcessingRepository(app,db,app.speechModel),repo)
            models.put(id,model)
            for (offset in listOf(0,unknown.length/2,unknown.lastIndex)) assertEquals(cue.audioStartMs,model.sentenceTarget(gap,offset))
            val published=db.bookDao().metadata("epub_alignment_published:$id")!!.toLong()
            assertEquals(30_000L,repo.alignmentDelayMs(id,published));assertEquals(20_000L,repo.alignmentDelayMs(id,published+10_000));assertEquals(0L,repo.alignmentDelayMs(id,published+31_000))
            // Status-only changes must not invalidate the full-screen page source.
            val paging=db.importedEpubDao().paging(id,edition.editionId)
            paging.load(PagingSource.LoadParams.Refresh(null,40,true))
            db.importedEpubDao().put(edition.copy(state="ALIGNING",message="Matching new text"))
            delay(150);assertFalse(paging.invalid)
            val b=source("middle",unknown,8000)
            db.readingDao().putSegments(listOf(b.toEntity()))
            db.readingDao().putVersion(db.readingDao().findVersion(id)!!.copy(transcriptRevision=2))
            assertTrue(repo.align(id))
            val updated=db.importedEpubDao().blocks(id,edition.editionId).single { it.text==unknown }.toReadingSegment()
            assertFalse(updated.anchors.any { it.estimated })
            assertEquals(8000L,sentenceCues(updated,mapOf(b.id to b)).single().audioStartMs)
            assertEquals(a,db.readingDao().sourceSegments(id,listOf(a.id)).single().toSegment())
            repo.remove(id)
        } finally { withContext(Dispatchers.Main) {models.clear()};db.close();file.delete() }
    }
}
