package com.example.readalong.data.book

import com.example.readalong.data.local.BookEntity
import com.example.readalong.data.local.BookProgressEntity

/** Fictional metadata and original artwork; these entries contain no audio or prose. */
object DemoBooks {
    private const val HOUR = 3_600_000L
    private const val ADDED_AT = 1_790_000_000_000L

    val books = listOf(
        book("night-orchard", "The Night Orchard", "Mira Ellison", 10 * HOUR, 0),
        book("beyond-tides", "Beyond the Tides", "Rowan Hale", 8 * HOUR, 1),
        book("quiet-places", "A Map of Quiet Places", "Ada Mercer", 12 * HOUR, 2),
        book("letters-north", "Letters from the North", "Theo Vale", 7 * HOUR, 3),
        book("last-observatory", "The Last Observatory", "Elin Brooks", 11 * HOUR, 4),
        book("summer-slowly", "Summer, Slowly", "June Ellis", 6 * HOUR, 5),
    )

    val progress = listOf(
        BookProgressEntity("demo-night-orchard", 198 * 60_000L, lastPlayedAt = ADDED_AT + 3),
        BookProgressEntity("demo-beyond-tides", 39 * 60_000L, lastPlayedAt = ADDED_AT + 2),
        BookProgressEntity("demo-quiet-places", 562 * 60_000L, lastPlayedAt = ADDED_AT + 1),
        BookProgressEntity("demo-letters-north"),
        BookProgressEntity("demo-last-observatory"),
        BookProgressEntity("demo-summer-slowly", 6 * HOUR, isFinished = true, lastPlayedAt = ADDED_AT),
    )

    private fun book(id: String, title: String, author: String, duration: Long, style: Int) = BookEntity(
        id = "demo-$id",
        title = title,
        author = author,
        coverUri = null,
        description = "A fictional sample book for exploring your ReadAlong library. No audio or reading text is included.",
        durationMs = duration,
        addedAt = ADDED_AT,
        isDemo = true,
        coverStyle = style,
    )
}
