package com.example.readalong.domain.link

/**
 * Which way listening progress goes between this device and the shared progress file, shared by the phone and
 * desktop apps. Comparing the two devices' clocks alone let a computer whose clock ran a few minutes fast win
 * over newer listening on the phone. So each device remembers what both sides said at the end of its last sync
 * ([seenRemote], [seenLocal]): when only one side has changed since, that side wins whatever the clocks say,
 * and the clocks decide only when both changed (or the book was never synced).
 */
object ProgressMerge {
    enum class Direction { TAKE, WRITE, NONE }
    /** Positions within this of each other are the same listening. */
    const val SAME_MS = 2_000L

    fun decide(localAt: Long?, remoteAt: Long?, seenRemote: Long? = null, seenLocal: Long? = null): Direction {
        if (seenRemote != null || seenLocal != null) {
            val remoteChanged = remoteAt != null && remoteAt != seenRemote
            val localChanged = localAt != null && localAt != seenLocal
            when {
                remoteChanged && !localChanged -> return Direction.TAKE
                localChanged && !remoteChanged -> return Direction.WRITE
                !remoteChanged && !localChanged -> return if (remoteAt == null && localAt != null) Direction.WRITE else Direction.NONE
            }
        }
        return byClock(localAt, remoteAt)
    }

    fun byClock(localAt: Long?, remoteAt: Long?): Direction = when {
        remoteAt != null && remoteAt > (localAt ?: 0) + SAME_MS -> Direction.TAKE
        localAt != null && (remoteAt == null || localAt > remoteAt + SAME_MS) -> Direction.WRITE
        else -> Direction.NONE
    }

    /** What to remember after a sync: "<remote updatedAt>:<local lastPlayedAt>". */
    fun encodeSeen(remoteAt: Long?, localAt: Long?): String = "${remoteAt ?: ""}:${localAt ?: ""}"
    fun decodeSeen(text: String?): Pair<Long?, Long?>? = text?.split(':')?.takeIf { it.size == 2 }?.let { it[0].toLongOrNull() to it[1].toLongOrNull() }
}
