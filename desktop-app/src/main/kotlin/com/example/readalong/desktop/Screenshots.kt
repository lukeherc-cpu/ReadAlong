package com.example.readalong.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.example.readalong.desktop.core.*
import com.example.readalong.desktop.transcription.Chunk
import com.example.readalong.desktop.transcription.TranscriptStore
import com.example.readalong.desktop.ui.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat
import org.json.JSONArray
import org.json.JSONObject
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** `ReadAlong --screenshots <dir>`: renders the main screens with a demo library, without a window. Used by CI. */
object Screenshots {
    private val books = listOf(
        Triple("The Lighthouse Keeper", "Mara Quinn", 205f), Triple("A Field Guide to Small Rivers", "Tomas Alder", 28f),
        Triple("Night Train to Lisbon Street", "Evelyn Hart", 340f), Triple("Copper & Rain", "J. D. Okafor", 160f),
        Triple("The Quiet Orchard", "Lena Morrow", 95f), Triple("Northern Lights, Southern Seas", "Ravi Shah", 250f))
    private val passage = listOf(
        "The lamp turned once every eleven seconds, and in the pause between its sweeps the keeper could hear the sea breathing against the rocks below.",
        "She had counted those seconds for nineteen winters.", "Tonight, though, the count felt different, as if the light itself were waiting for something.",
        "Down on the shingle a figure was walking, slowly, with a lantern held low.", "Nobody came to the point after dark.",
        "The keeper set down her pen, closed the logbook on the half-written line, and reached for her coat.",
        "Outside, the wind had dropped to almost nothing.", "The lantern stopped at the foot of the steps and lifted, once, as if in greeting.")

    fun run(directory: String) {
        val out = File(directory).apply { mkdirs() }
        val home = File(System.getProperty("java.io.tmpdir"), "readalong-screens-${System.nanoTime()}").apply { mkdirs() }
        System.setProperty("readalong.home", home.path)
        seed(home)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppState(scope, background = false)
        val first = app.library.books.value.first()
        fun shot(name: String, width: Int = 1360, height: Int = 860, setup: () -> Unit) {
            setup()
            val scene = ImageComposeScene(width, height, Density(1f)) { ReadAlongApp(app) }
            try {
                var image = scene.render(0)
                // Let covers, palettes and text load, and animations settle.
                for (frame in 1..40) { Thread.sleep(60); image = scene.render(frame * 100_000_000L) }
                File(out, "$name.png").writeBytes(requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
                System.err.println("Rendered $name.png")
            } finally { scene.close() }
        }
        try {
            shot("library") { app.home() }
            shot("player") { app.home(); app.open(Screen.Player(first.id)) }
            shot("player-narrow", 820, 900) { app.home(); app.open(Screen.Player(first.id)) }
            shot("reader") { app.home(); app.open(Screen.Reader(first.id)) }
            shot("reader-sepia") { app.settingsStore.update { it.copy(readerTheme = "SEPIA") }; app.home(); app.open(Screen.Reader(first.id)) }
            shot("queue") { app.home(); app.open(Screen.Queue) }
            shot("settings") { app.home(); app.open(Screen.Settings) }
            shot("library-empty") { app.library.replaceAll { emptyList() }; app.home() }
        } finally { scope.cancel(); home.deleteRecursively() }
    }

    /** A demo library: generated covers, and a transcript for the first book in the phone's chunk format. */
    private fun seed(home: File) {
        val library = JSONArray()
        books.forEachIndexed { index, (title, author, hue) ->
            val id = "demo$index"
            val cover = File(home, "cover$index.png").also { ImageIO.write(cover(title, author, hue), "png", it) }
            val duration = 3_600_000L * (index + 4)
            val chapters = (0 until 12).map { Chapter("Chapter ${it + 1}", it * duration / 12) }
            library.put(LibraryStore.encode(LibraryBook(id, title, author, "folder:/demo/$index", "/demo/$index",
                listOf(AudioPart("/demo/$index/book.m4b", duration, 0, 1, 1)), chapters, cover.path,
                positionMs = if (index < 3) duration / (index + 3) else 0, lastPlayedAt = if (index < 3) 1000L - index else null,
                finished = index == 5, addedAt = index.toLong())))
        }
        File(home, "library.json").writeText(JSONObject().put("version", 1).put("folders", JSONArray()).put("books", library).toString())
        // About four minutes of narration from 1:00:00, as 30-second chunks with word times.
        val chunks = File(home, "books/demo0").apply { mkdirs() }
        val lines = StringBuilder()
        var chunkStart = 3_600_000L
        var sentence = 0
        repeat(8) {
            val words = JSONArray(); val text = StringBuilder()
            var t = 0.4
            while (t < 27.0) {
                val s = passage[sentence++ % passage.size]
                s.split(' ').forEach { w ->
                    words.put(JSONObject().put("word", w).put("start", t).put("end", t + .32).put("probability", .93))
                    t += .36
                }
                t += .5
                if (text.isNotEmpty()) text.append(' ')
                text.append(s)
            }
            lines.append(Chunk(0, chunkStart, chunkStart + 30_000, JSONObject().put("text", text).put("words", words)).toJson()).append('\n')
            chunkStart += 30_000
        }
        File(chunks, "chunks.jsonl").writeText(lines.toString())
        File(chunks, "transcript.json").writeText(JSONObject().put("model", "desktop:whisper.cpp:small.en").put("engine", "Whisper small.en")
            .put("chunks", 8).put("totalChunks", 8).put("complete", false).toString())
        // The first book is mid-passage.
        val lib = JSONObject(File(home, "library.json").readText())
        lib.getJSONArray("books").getJSONObject(0).put("position", 3_600_000L + 41_000L)
        File(home, "library.json").writeText(lib.toString())
    }

    private fun cover(title: String, author: String, hue: Float): BufferedImage {
        val size = 600
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            paint = GradientPaint(0f, 0f, Color.getHSBColor(hue / 360f, .55f, .75f), size.toFloat(), size.toFloat(), Color.getHSBColor((hue + 30) / 360f, .65f, .35f))
            fillRect(0, 0, size, size)
            color = Color(255, 255, 255, 40)
            for (i in 0 until 6) fillOval(-120 + i * 140, 360 - i * 30, 260, 260)
            color = Color.WHITE
            font = Font(Font.SERIF, Font.BOLD, 54)
            var y = 140
            title.split(' ').chunked(2).forEach { line -> drawString(line.joinToString(" "), 50, y); y += 66 }
            font = Font(Font.SANS_SERIF, Font.PLAIN, 30)
            drawString(author.uppercase(), 50, 540)
            dispose()
        }
        return image
    }
}
