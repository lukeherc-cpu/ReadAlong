package com.example.readalong.data.transcription

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Each slot owns its own process: upstream native globals are never shared concurrently. */
internal object RecognitionSlots {
    private val permits = Semaphore(1)
    private val lock = Mutex()
    private val used = BooleanArray(1)
    private val services = listOf(RecognitionService::class.java)
    suspend fun <T> use(block: suspend (Class<out RecognitionService>) -> T): T = permits.withPermit {
        val slot = lock.withLock { used.indexOfFirst { !it }.also { index -> check(index >= 0); used[index] = true } }
        try { block(services[slot]) } finally { withContext(NonCancellable) { lock.withLock { used[slot] = false } } }
    }
}
class SecondRecognitionService : RecognitionService()
class ThirdRecognitionService : RecognitionService()
