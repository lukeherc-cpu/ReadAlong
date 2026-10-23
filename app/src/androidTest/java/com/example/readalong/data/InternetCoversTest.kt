package com.example.readalong.data

import com.example.readalong.data.book.InternetCovers
import org.junit.Assert.*
import org.junit.Test

class InternetCoversTest {
    @Test fun coverSearchUsesAudiobookArtworkAndRejectsNonHttpsImages() {
        val covers = InternetCovers.parseApple("""{"results":[{"collectionName":"A book","artistName":"An author","artworkUrl100":"https://example.test/100x100bb.jpg"},{"artworkUrl100":"https://example.test/100x100bb.jpg"},{"artworkUrl100":"file:///private/image"}]}""")
        assertEquals(1, covers.size)
        assertEquals("https://example.test/600x600bb.jpg", covers.single().url)
        assertEquals("A book", covers.single().title)
    }
    @Test fun optionalLiveAudiobookSearchAndImageDownload() = kotlinx.coroutines.runBlocking {
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("liveCoverSearch") == "true")
        val covers = InternetCovers.search("East of Eden audiobook cover")
        assertTrue(covers.isNotEmpty())
        val bitmap = InternetCovers.image(covers.first().url)
        assertTrue(bitmap.width > 100 && bitmap.height > 100)
        bitmap.recycle()
    }

}
