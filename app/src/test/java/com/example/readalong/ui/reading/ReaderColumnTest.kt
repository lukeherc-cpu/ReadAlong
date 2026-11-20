package com.example.readalong.ui.reading

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderColumnTest {
    @Test fun phoneKeepsThePreviewColumnSoLinesDoNotRewrap() {
        // 412 dp wide phone at 2.625x: the card is a little narrower than the page.
        assertEquals(950, readerColumnPx(950, 1080, Density(2.625f)))
    }

    @Test fun landscapeTabletUsesTheFullPageWidth() {
        // Xiaomi Pad 7 in landscape, about 1600 dp: the card beside the controls is about a quarter as wide.
        assertEquals(3200, readerColumnPx(820, 3200, Density(2f)))
    }

    @Test fun phoneOnItsSideUsesTheFullPageWidth() {
        assertEquals(2400, readerColumnPx(700, 2400, Density(2.625f)))
    }

    @Test fun withoutAMeasuredCardThePageIsFullWidth() {
        assertEquals(1080, readerColumnPx(0, 1080, Density(2.625f)))
        assertEquals(1080, readerColumnPx(1300, 1080, Density(2.625f)))
    }
}
