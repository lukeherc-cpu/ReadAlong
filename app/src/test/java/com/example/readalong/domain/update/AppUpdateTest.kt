package com.example.readalong.domain.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppUpdateTest {
    private fun v(text: String) = AppVersion.parse(text)!!

    @Test fun versionsCompareNumerically() {
        assertTrue(v("0.44.10") > v("0.44.9"))
        assertTrue(v("v0.45.0") > v("0.44.1"))
        assertTrue(v("1.0") > v("0.99.99"))
        assertEquals(0, v("0.44.1").compareTo(v("v0.44.1")))
        assertEquals(0, v("0.44").compareTo(v("0.44.0")))
    }

    @Test fun previewComesBeforeItsRelease() {
        assertTrue(v("0.45.0-preview") < v("0.45.0"))
        assertTrue(v("0.45.0-preview") > v("0.44.1"))
        assertEquals("0.45.0-preview", v("0.45.0-preview").toString())
    }

    @Test fun notAVersion() {
        assertNull(AppVersion.parse("dev"))
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse(null))
        assertNull(AppVersion.parse("release-candidate"))
    }

    private fun release(tag: String, vararg assets: String) = JSONObject().put("tag_name", tag).put("html_url", "https://github.com/x/releases/tag/$tag")
        .put("body", "## What's new\n\n- Faster\n- Smoother\n")
        .put("assets", JSONArray(assets.map { JSONObject().put("name", it).put("browser_download_url", "https://github.com/x/releases/download/$tag/$it") }))

    @Test fun readsGitHubRelease() {
        val r = AppRelease.fromGitHub(release("v0.45.0", "ReadAlong-0.45.0.apk", "ReadAlong-0.45.0.apk.sha256", "ReadAlong-Windows.msi"))!!
        assertEquals(v("0.45.0"), r.version)
        assertEquals("https://github.com/x/releases/download/v0.45.0/ReadAlong-0.45.0.apk", r.apkUrl)
        assertEquals("• Faster\n• Smoother", UpdateCheck.summary(r.notes))
        // GitHub's generated notes.
        val generated = "## What's Changed\n* Add sheet: +N more opens out to every found book by @lukeherc-cpu in https://github.com/lukeherc-cpu/ReadAlong/pull/41\n\n\n" +
            "**Full Changelog**: https://github.com/lukeherc-cpu/ReadAlong/compare/v0.44.0...v0.44.1"
        assertEquals("• Add sheet: +N more opens out to every found book", UpdateCheck.summary(generated))
        assertNull(AppRelease.fromGitHub(release("v0.45.0").put("draft", true)))
        assertNull(AppRelease.fromGitHub(release("nightly")))
    }

    @Test fun picksTheDesktopDownloadForThisComputer() {
        val r = AppRelease.fromGitHub(release("v0.45.0", "ReadAlong-Windows.msi", "ReadAlong-Windows-portable.zip", "ReadAlong-macOS-AppleSilicon.dmg",
            "ReadAlong-macOS-Intel.dmg", "ReadAlong-Linux.flatpak", "ReadAlong-Linux-x86_64.tar.gz"))!!
        assertTrue(r.desktopDownload("Windows 11", "amd64").endsWith("ReadAlong-Windows.msi"))
        assertTrue(r.desktopDownload("Mac OS X", "aarch64").endsWith("AppleSilicon.dmg"))
        assertTrue(r.desktopDownload("Mac OS X", "x86_64").endsWith("Intel.dmg"))
        assertTrue(r.desktopDownload("Linux", "amd64").endsWith("Linux.flatpak"))
        // No installer for this computer: the release page.
        val bare = AppRelease.fromGitHub(release("v0.45.0", "ReadAlong-0.45.0.apk"))!!
        assertEquals("https://github.com/x/releases/tag/v0.45.0", bare.desktopDownload("Windows 11", "amd64"))
    }

    @Test fun offersOnlyNewerUnskippedReleases() {
        val r = AppRelease.fromGitHub(release("v0.45.0"))!!
        assertEquals(r, UpdateCheck.offer("0.44.1", r))
        assertNull(UpdateCheck.offer("0.45.0", r))
        assertNull(UpdateCheck.offer("0.46.0", r))
        assertNull(UpdateCheck.offer("dev", r))
        assertNull(UpdateCheck.offer("0.44.1", null))
        assertNull(UpdateCheck.offer("0.44.1", r, skipped = "0.45.0"))
        assertEquals(r, UpdateCheck.offer("0.44.1", r, skipped = "0.44.5"))
        // A preview build is offered the stable release of the same version.
        assertEquals(r, UpdateCheck.offer("0.45.0-preview", r))
    }

    @Test fun checksOnceADay() {
        val day = UpdateCheck.INTERVAL_MS
        assertTrue(UpdateCheck.due(0, 1_000))
        assertFalse(UpdateCheck.due(10 * day, 10 * day + day / 2))
        assertTrue(UpdateCheck.due(10 * day, 11 * day))
        assertTrue(UpdateCheck.due(10 * day, 9 * day)) // the clock went backwards
    }

    @Test fun formatVersions() {
        assertTrue(FormatVersion.readable(JSONObject().put("version", 1), 1))
        assertTrue(FormatVersion.readable(JSONObject(), 1)) // written before versions
        assertFalse(FormatVersion.readable(JSONObject().put("version", 2), 1))
        assertTrue(FormatVersion.readable(FormatVersion.stamp(JSONObject(), 2, minReader = 1), 1))
        assertFalse(FormatVersion.readable(FormatVersion.stamp(JSONObject(), 3, minReader = 2), 1))
        assertTrue(FormatVersion.readable(FormatVersion.stamp(JSONObject(), 3, minReader = 2), 3))
        assertFalse(FormatVersion.readable(JSONObject().put("version", 0), 1))
        assertFalse(FormatVersion.stamp(JSONObject(), 1).has("minReaderVersion"))
    }
}
