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
    // The packaged app passes the version as a JVM flag; the build also writes it into the jar, so a launch without
    // the flag still knows it (with neither, "dev" turns update offers off).
    val current: String = System.getProperty("readalong.version")
        ?: UpdateService::class.java.getResourceAsStream("/readalong-version.txt")?.use { it.readBytes().decodeToString().trim() }?.ifBlank { null }
        ?: "dev",
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
        result.onSuccess { latest ->
            // Only a check that reached GitHub counts: offline at start-up tries again next time.
            settings.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
            val available = UpdateCheck.offer(current, latest, skipped = if (manual) null else settings.value.skippedVersion)
            mutableStatus.value = if (available != null) UpdateStatus.Available(available) else UpdateStatus.UpToDate
            if (available != null) mutableOffer.value = available
        }.onFailure {
            mutableStatus.value = if (manual) UpdateStatus.Failed("Couldn't reach GitHub. Check your internet connection and try again.") else UpdateStatus.Idle
        }
    }

    fun later() { mutableOffer.value = null }
    fun skip(release: AppRelease) { settings.update { it.copy(skippedVersion = release.version.toString()) }; mutableOffer.value = null }
    fun downloadUrl(release: AppRelease) = release.desktopDownload(portable = portable)
    /**
     * Running as a Flatpak. Since 0.55 every Flatpak, from the .flatpakref or the bundle, updates from ReadAlong's
     * signed repository on GitHub Pages, so the update comes through the software centre or `flatpak update`
     * rather than a download.
     */
    val flatpak: Boolean = System.getenv("FLATPAK_ID") != null
    val flatpakUpdateCommand: String = "flatpak update ${System.getenv("FLATPAK_ID") ?: "io.github.lukeherc_cpu.ReadAlong"}"
    /** Running from the portable zip or tarball: an update is the same kind of download again. */
    val portable: Boolean by lazy { InstallKind.portable(flatpak) }
}

/** How this copy of ReadAlong got onto the computer, so an update offers the same kind of file. */
object InstallKind {
    fun portable(flatpak: Boolean): Boolean = when {
        AppPaths.isMac -> false
        AppPaths.isWindows -> !installedByMsi()
        else -> !flatpak
    }

    /** The MSI leaves an entry under "Apps & features"; the portable zip doesn't. Assumes the installer when it can't tell. */
    private fun installedByMsi(): Boolean = runCatching {
        val exe = ProcessHandle.current().info().command().orElse(null)?.let { java.io.File(it).canonicalPath } ?: return true
        val roots = listOf(com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER, com.sun.jna.platform.win32.WinReg.HKEY_LOCAL_MACHINE)
        val path = "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall"
        var entries = 0
        for (root in roots) {
            val keys = runCatching { com.sun.jna.platform.win32.Advapi32Util.registryGetKeys(root, path) }.getOrNull() ?: continue
            for (key in keys) {
                val values = runCatching { com.sun.jna.platform.win32.Advapi32Util.registryGetValues(root, "$path\\$key") }.getOrNull() ?: continue
                if (values["DisplayName"]?.toString() != "ReadAlong") continue
                entries++
                val location = values["InstallLocation"]?.toString()?.takeIf { it.isNotBlank() } ?: return true
                if (exe.startsWith(java.io.File(location).canonicalPath, ignoreCase = true)) return true
            }
        }
        // An entry that names another folder means the installed copy is elsewhere and this one is the zip.
        entries == 0 && exe.contains("\\AppData\\Local\\ReadAlong\\", ignoreCase = true)
    }.getOrDefault(true)
}
