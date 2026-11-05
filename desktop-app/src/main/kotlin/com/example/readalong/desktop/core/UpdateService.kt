package com.example.readalong.desktop.core

import com.example.readalong.domain.update.AppRelease
import com.example.readalong.domain.update.UpdateCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What Settings shows about updates. */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Available(val release: AppRelease) : UpdateStatus
    data class Failed(val message: String) : UpdateStatus
}

/**
 * Looks for a newer ReadAlong release on GitHub: once a day at start-up when Settings allows it, and
 * whenever Check for updates is pressed. Nothing is downloaded or installed without the person choosing to.
 */
class UpdateService(private val scope: CoroutineScope, private val settings: SettingsStore,
    val current: String = System.getProperty("readalong.version") ?: "dev",
    private val fetch: () -> AppRelease? = { UpdateCheck.fetchLatest("ReadAlong-Desktop/$current") }) {
    private val mutableStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = mutableStatus
    private val mutableOffer = MutableStateFlow<AppRelease?>(null)
    /** A release to offer in a dialog: set by the automatic check, cleared once answered. */
    val offer: StateFlow<AppRelease?> = mutableOffer

    fun checkIfDue(now: Long = System.currentTimeMillis()) {
        val s = settings.value
        if (!s.checkUpdates || !UpdateCheck.due(s.lastUpdateCheck, now)) return
        scope.launch { check(manual = false) }
    }

    /** A manual check offers even a skipped version; an automatic one stays quiet on failure. */
    suspend fun check(manual: Boolean = true) {
        if (mutableStatus.value == UpdateStatus.Checking) return
        mutableStatus.value = UpdateStatus.Checking
        val result = withContext(Dispatchers.IO) { runCatching { fetch() } }
        settings.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
        result.onSuccess { latest ->
            val available = UpdateCheck.offer(current, latest, skipped = if (manual) null else settings.value.skippedVersion)
            mutableStatus.value = if (available != null) UpdateStatus.Available(available) else UpdateStatus.UpToDate
            if (available != null) mutableOffer.value = available
        }.onFailure {
            mutableStatus.value = if (manual) UpdateStatus.Failed("Couldn't reach GitHub. Check your internet connection and try again.") else UpdateStatus.Idle
        }
    }

    fun later() { mutableOffer.value = null }
    fun skip(release: AppRelease) { settings.update { it.copy(skippedVersion = release.version.toString()) }; mutableOffer.value = null }
    fun downloadUrl(release: AppRelease) = release.desktopDownload()
}
