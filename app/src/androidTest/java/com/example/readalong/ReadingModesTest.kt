package com.example.readalong

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.graphics.asAndroidBitmap
import com.example.readalong.data.local.*
import com.example.readalong.data.preferences.ReaderPreferences
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ReadingModesTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun plainEpubModeHasIndependentColorsNoPlayerAndRestoresItsReadingPosition() {
        val app = compose.activity.application as ReadAlongApplication
        val original = runBlocking { app.appPreferences.preferences.first() }
        val follow = runBlocking { app.readerPreferences.preferences.first() }
        val plain = runBlocking { app.bookReaderPreferences.preferences.first() }
        val id = "modes-${System.nanoTime()}"
        val title = "Mode switch verification"
        val text = (0..70).joinToString(" ") { "At marker $it the reader follows an original paragraph without changing the saved audio position." }
        val wave = com.example.readalong.data.createTestWave(app, "$id.wav", 60)
        val epub = File(File(app.cacheDir, "exports").apply { mkdirs() }, "$id.epub")
        try {
            runBlocking {
                app.appPreferences.update { it.copy(setupStage = 3, batteryWarningSeen = true, openingView = "READ_ONLY") }
                app.bookReaderPreferences.save(ReaderPreferences(theme = "LIGHT"))
                app.database.bookDao().upsertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id = id, title = title, isDemo = false, durationMs = 120_000)))
                app.database.mediaDao().putAudio(listOf(AudioFileEntity("$id-audio", id, androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", wave).toString(), "Mode chapter", 0, 60_000, null)))
                app.database.bookDao().insertProgress(listOf(BookProgressEntity(id, audioPositionMs = 42_000)))
                java.util.zip.ZipOutputStream(epub.outputStream()).use { zip ->
                    mapOf("mimetype" to "application/epub+zip", "META-INF/container.xml" to "<container><rootfile full-path='package.opf'/></container>",
                        "package.opf" to "<package><metadata><title>$title</title></metadata><manifest><item id='one' href='one.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='one'/></spine></package>",
                        "one.xhtml" to "<html><body><p>$text</p></body></html>").forEach { (path, data) -> zip.putNextEntry(java.util.zip.ZipEntry(path)); zip.write(data.toByteArray()); zip.closeEntry() }
                }
                app.importedEpub.import(id, androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", epub))
                app.importedEpub.preference(id, "GENERATED") // Plain reading must still show the original EPUB.
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Search library").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Search library").performClick()
            compose.onNodeWithText("Search title or author").performTextInput(title)
            compose.onNodeWithTag("library_grid").performScrollToNode(hasTestTag("book:$id"))
            compose.onNodeWithTag("book:$id").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Read only").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Read only").assertIsSelected()
            compose.onNodeWithContentDescription("Play").assertDoesNotExist()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
            val styled = compose.onNodeWithText(text).fetchSemanticsNode().config[SemanticsProperties.Text].single()
            assertTrue("Original EPUB text must have no spoken highlighting", styled.spanStyles.none { it.item.color != androidx.compose.ui.graphics.Color.Unspecified })
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            assertEquals(android.graphics.Color.WHITE, bitmap.getPixel(4, bitmap.height/2))
            File(app.getExternalFilesDir(null), "verification-read-only-light.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            compose.onNodeWithTag("follow_passages").performTouchInput { swipeUp(startY = height * .65f, endY = height * .25f) }
            compose.waitForIdle()
            val before = compose.onNodeWithTag("follow_passages").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue(before > 0f)
            if (compose.onAllNodesWithContentDescription("ReadAlong").fetchSemanticsNodes().isEmpty()) compose.onNodeWithTag("reader_reveal_controls").performTouchInput { click(androidx.compose.ui.geometry.Offset(8f, 8f)) }
            compose.onNodeWithContentDescription("Read only").performClick() // The selected half must toggle too.
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isNotEmpty() }
            val saved = runBlocking { app.database.bookDao().metadata("book_viewport:$id") }!!
            assertTrue(org.json.JSONObject(saved).getInt("character") > 0)
            compose.onNodeWithContentDescription("ReadAlong").performClick() // Either half toggles the whole switch.
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isEmpty() }
            compose.waitUntil(10_000) { compose.onNodeWithTag("follow_passages").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f }
            val restored = compose.onNodeWithTag("follow_passages").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue("The same reading passage should be restored: $before vs $restored", kotlin.math.abs(before-restored) < 160f)
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Dark").performScrollTo().performClick()
            compose.onNodeWithText("Apply").performClick()
            compose.waitUntil(5_000) { runBlocking { app.bookReaderPreferences.preferences.first().theme == "DARK" } }
            assertEquals(follow, runBlocking { app.readerPreferences.preferences.first() })
            compose.waitForIdle()
            compose.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap().let { image ->
                File(app.getExternalFilesDir(null), "verification-read-only-dark.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            assertFalse(app.playback.state.value.isPlaying)
            assertEquals(42_000L, runBlocking { app.database.bookDao().progress(id)!!.audioPositionMs })
        } finally {
            runBlocking { app.playback.releaseBook(id); app.importedEpub.remove(id); app.database.bookDao().removeBook(id); app.appPreferences.update { original }; app.readerPreferences.save(follow); app.bookReaderPreferences.save(plain) }
            epub.delete(); wave.delete()
        }
    }
}
