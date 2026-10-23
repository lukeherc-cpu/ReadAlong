package com.example.readalong.domain

import com.example.readalong.domain.model.audioNameComparator
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioOrderTest {
    @Test fun chapterNumbersSortNumericallyWithoutOverflow() {
        val files = listOf("Chapter 10.mp3", "Chapter 2.mp3", "Chapter 999999999999999999999999.mp3", "Chapter 01.mp3")
        assertEquals(listOf(files[3], files[1], files[0], files[2]), files.sortedWith(audioNameComparator))
    }
}
