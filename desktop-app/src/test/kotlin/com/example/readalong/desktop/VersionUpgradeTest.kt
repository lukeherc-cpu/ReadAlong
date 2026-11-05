package com.example.readalong.desktop

import com.example.readalong.desktop.core.VersionUpgrade
import com.example.readalong.desktop.core.versionNote
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class VersionUpgradeTest {
    @Test fun backsUpDataWhenTheVersionChanges() {
        val data = createTempDirectory("readalong").toFile()
        File(data, "library.json").writeText("""{"version":1,"books":[]}""")
        File(data, "settings.json").writeText("{}")
        // 0.44.1 and earlier wrote no marker.
        assertNull(VersionUpgrade.run("0.45.0", data))
        assertEquals("""{"version":1,"books":[]}""", File(data, "backups/before-0.45.0/library.json").readText())
        assertTrue(File(data, "backups/before-0.45.0/settings.json").isFile)
        assertNull(VersionUpgrade.run("0.45.0", data), "the same version again changes nothing")
        assertEquals(1, File(data, "backups").listFiles()!!.size)
        File(data, "library.json").writeText("""{"version":1,"books":[1]}""")
        assertEquals("0.45.0", VersionUpgrade.run("0.46.0", data))
        assertEquals("""{"version":1,"books":[1]}""", File(data, "backups/0.45.0/library.json").readText())
        data.deleteRecursively()
    }

    @Test fun keepsAnUnreadableFile() {
        val data = createTempDirectory("readalong").toFile()
        val library = File(data, "library.json").apply { writeText("{ from a newer version") }
        val kept = VersionUpgrade.setAside(library)!!
        assertEquals("{ from a newer version", kept.readText())
        assertNull(VersionUpgrade.setAside(File(data, "missing.json")))
        data.deleteRecursively()
    }

    @Test fun namesWhichSideToUpdate() {
        assertNull(versionNote(null, "0.45.0"))
        assertEquals("ReadAlong 0.45.0", versionNote("0.45.0", "0.45.0"))
        assertTrue(versionNote("0.44.1", "0.45.0")!!.contains("Update the phone"))
        assertTrue(versionNote("0.46.0", "0.45.0")!!.contains("Update ReadAlong here"))
        assertEquals("ReadAlong 0.45.0", versionNote("0.45.0", null))
    }
}
