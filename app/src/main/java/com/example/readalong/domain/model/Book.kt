package com.example.readalong.domain.model

/** One logical library entry; audio and reading editions will share this identity. */
data class Book(
    val id: String,
    val title: String,
    val author: String? = null,
    // Content URI, not a raw filesystem path. No permission is inferred from this value.
    val coverUri: String? = null,
    val description: String? = null,
    val durationMs: Long? = null,
    val positionMs: Long = 0,
    val isFinished: Boolean = false,
    val lastPlayedAt: Long? = null,
    val addedAt: Long = 0,
    val isDemo: Boolean = false,
    val coverStyle: Int = 0,
    val mediaState: String = "NONE",
) {
    val section: LibrarySection
        get() = when {
            isFinished -> LibrarySection.FINISHED
            positionMs > 0 -> LibrarySection.CURRENT
            else -> LibrarySection.NOT_STARTED
        }

    // Only an explicit completion flag displays 100%; unknown durations stay safe.
    val progressPercent: Int
        get() {
            if (isFinished) return 100
            val duration = durationMs?.takeIf { it > 0 } ?: return 0
            return (positionMs.coerceIn(0, duration).toDouble() / duration * 100).toInt().coerceAtMost(99)
        }

    val remainingMs: Long?
        get() = durationMs?.takeIf { it > 0 }?.let {
            if (isFinished) 0 else it - positionMs.coerceIn(0, it)
        }
}

enum class LibrarySection { CURRENT, NOT_STARTED, FINISHED }
