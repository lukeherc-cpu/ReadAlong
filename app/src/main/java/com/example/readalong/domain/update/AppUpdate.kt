package com.example.readalong.domain.update

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * A ReadAlong version such as "0.44.1" or "0.45.0-preview". Shared by the phone and desktop apps, which
 * always carry the same version (desktop reads the phone's versionName).
 */
data class AppVersion(val parts: List<Int>, val preview: Boolean) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val difference = parts.getOrElse(i) { 0 }.compareTo(other.parts.getOrElse(i) { 0 })
            if (difference != 0) return difference
        }
        // 0.45.0-preview comes before 0.45.0.
        return other.preview.compareTo(preview)
    }

    override fun toString() = parts.joinToString(".") + if (preview) "-preview" else ""

    companion object {
        /** Reads "v0.44.1", "0.44.1" or "0.45.0-preview"; null when it isn't a version. */
        fun parse(text: String?): AppVersion? {
            val clean = text?.trim()?.removePrefix("v")?.removePrefix("V")?.takeIf { it.isNotEmpty() } ?: return null
            val numbers = clean.substringBefore('-').substringBefore('+').split('.')
            val parts = numbers.map { it.toIntOrNull() ?: return null }
            return AppVersion(parts, clean.contains('-'))
        }
    }
}

/** A published GitHub release: its version, page, notes and downloadable files. */
data class AppRelease(val version: AppVersion, val tag: String, val pageUrl: String, val notes: String, val assets: Map<String, String>) {
    /** The phone app's APK, which installs over the current one (same signing key). */
    val apkUrl: String? get() = assets.entries.firstOrNull { it.key.endsWith(".apk") }?.value

    /** The desktop download for this computer, or the release page when there isn't one. */
    fun desktopDownload(os: String = System.getProperty("os.name").orEmpty(), arch: String = System.getProperty("os.arch").orEmpty()): String {
        val lower = os.lowercase()
        val wanted = when {
            lower.contains("win") -> listOf("Windows.msi", "Windows-portable.zip")
            lower.contains("mac") -> if (arch == "aarch64" || arch == "arm64") listOf("macOS-AppleSilicon.dmg") else listOf("macOS-Intel.dmg")
            else -> listOf("Linux.flatpak", "Linux-x86_64.tar.gz")
        }
        return wanted.firstNotNullOfOrNull { suffix -> assets.entries.firstOrNull { it.key.endsWith(suffix) }?.value } ?: pageUrl
    }

    companion object {
        /** Reads one release from the GitHub REST API ("GET /repos/{owner}/{repo}/releases/latest"). */
        fun fromGitHub(json: JSONObject): AppRelease? {
            if (json.optBoolean("draft")) return null
            val tag = json.optString("tag_name")
            val version = AppVersion.parse(tag) ?: return null
            val files = json.optJSONArray("assets")
            val assets = buildMap {
                for (i in 0 until (files?.length() ?: 0)) {
                    val asset = files!!.getJSONObject(i)
                    val name = asset.optString("name"); val url = asset.optString("browser_download_url")
                    if (name.isNotBlank() && url.startsWith("https://")) put(name, url)
                }
            }
            return AppRelease(version, tag, json.optString("html_url").ifBlank { "${UpdateCheck.RELEASES_PAGE}/tag/$tag" }, json.optString("body").trim(), assets)
        }
    }
}

/**
 * Asks GitHub for the newest ReadAlong release. Checks run at most once a day unless asked for, and a
 * release the person chose to skip isn't offered again; a newer one is.
 */
object UpdateCheck {
    const val REPOSITORY = "lukeherc-cpu/ReadAlong"
    const val RELEASES_PAGE = "https://github.com/$REPOSITORY/releases"
    private const val LATEST = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val INTERVAL_MS = 24L * 60 * 60 * 1000

    /** Whether an automatic check is due: never checked, a day has passed, or the clock went backwards. */
    fun due(lastChecked: Long, now: Long): Boolean = lastChecked <= 0 || now - lastChecked >= INTERVAL_MS || now < lastChecked

    /** The release to offer, or null when [current] is up to date, the release was skipped, or [current] can't be read. */
    fun offer(current: String, latest: AppRelease?, skipped: String? = null): AppRelease? {
        val installed = AppVersion.parse(current) ?: return null
        val release = latest?.takeIf { it.version > installed } ?: return null
        val skip = AppVersion.parse(skipped)
        return release.takeIf { skip == null || it.version > skip }
    }

    /** The latest stable release (GitHub's "latest" never points at a pre-release). Throws [IOException] when GitHub can't be reached. */
    fun fetchLatest(userAgent: String): AppRelease? {
        val connection = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", userAgent)
        }
        try {
            val code = connection.responseCode
            if (code == 404) return null // no release published yet
            if (code !in 200..299) throw IOException("GitHub answered $code.")
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            return AppRelease.fromGitHub(JSONObject(text))
        } finally { connection.disconnect() }
    }

    /**
     * The first changes in a release's notes, for a short dialog. GitHub's generated notes ("What's Changed",
     * "* Title by @someone in https://…", "**Full Changelog**: …") become plain "• Title" lines.
     */
    fun summary(notes: String, maxLines: Int = 6): String = notes.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.contains("Full Changelog") && !it.startsWith("<!--") }
        .map { line ->
            val bullet = line.startsWith("* ") || line.startsWith("- ")
            val text = line.removePrefix("* ").removePrefix("- ").replace(Regex("\\s+by @\\S+ in https://\\S+$"), "").replace("**", "").trim()
            if (bullet) "• $text" else text
        }
        .filter { it.isNotBlank() && it != "•" }.take(maxLines).joinToString("\n")
}
