plugins {
    kotlin("multiplatform") version "2.2.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
    id("org.jetbrains.compose") version "1.9.3"
}

// One version for every ReadAlong app: the phone's versionName.
val appVersion = Regex("versionName = \"([^\"]+)\"").find(file("../app/build.gradle.kts").readText())!!.groupValues[1]
group = "com.example.readalong"
version = appVersion

// The desktop app's Compose screens (library, player, reader, queue, Audible, settings), compiled for the iPhone
// unchanged where they have no desktop-only dependency, the same way desktop-app reuses the phone's code. The engine
// module holds the desktop's non-UI code. The screens in `iosOwn` are replaced by iPhone versions under
// src/iosMain/kotlin/com/example/readalong/desktop/ui.
val desktop = "../desktop-app/src/main/kotlin/com/example/readalong/desktop"
val iosOwn = listOf(
    "ui/App.kt", "ui/SmoothWheel.kt", "ui/CoverSearch.kt", "ui/Dialogs.kt",
    "ui/PhoneLink.kt", "ui/AudibleSignInWindow.kt", "ui/WindowsTitleBar.kt",
)
val compatImport: (String) -> String = { line -> if (line.startsWith("package ")) "$line\n\nimport com.example.readalong.compat.*" else line }

/**
 * The desktop's file dialogs block until a file is chosen; iOS pickers answer later. Each `chooseFile(…)?.let { f -> … }`
 * becomes `pickFile(…) { f -> … }` (ui/Dialogs.kt for iOS), which runs the same block once something is picked.
 */
fun asyncPickers(line: String): String {
    var out = line
    for ((blocking, async) in listOf("chooseFile" to "pickFile", "chooseFolder" to "pickFolder", "chooseSave" to "pickSave", "chooseAudioFile" to "pickAudioFile")) {
        var from = 0
        while (true) {
            val at = out.indexOf("$blocking(", from)
            if (at < 0) break
            from = at + 1
            val before = out.getOrNull(at - 1)
            if (before != null && (before.isLetterOrDigit() || before == '.' || before == ':')) continue
            var depth = 0
            var end = at + blocking.length
            while (end < out.length) {
                if (out[end] == '(') depth++
                if (out[end] == ')') { depth--; if (depth == 0) break }
                end++
            }
            val rest = out.substring(minOf(end + 1, out.length))
            val call = async + out.substring(at + blocking.length, end + 1)
            if (rest.startsWith("?.let {")) out = out.substring(0, at) + call + " {" + rest.removePrefix("?.let {")
            else if (rest.startsWith("?.let(")) {
                // `?.let(app::chooseFolder)`: the function reference is called with the picked file.
                var d = 0; var close = "?.let".length
                while (close < rest.length) { if (rest[close] == '(') d++; if (rest[close] == ')') { d--; if (d == 0) break }; close++ }
                val target = rest.substring("?.let(".length, close)
                out = out.substring(0, at) + call + " { ($target)(it) }" + rest.substring(close + 1)
            }
        }
    }
    return out
}

val desktopSources by tasks.registering(Sync::class) {
    from(desktop) { include("ui/**"); exclude(iosOwn) }
    filter(compatImport)
    filter(::asyncPickers)
    into(layout.buildDirectory.dir("desktop/com/example/readalong/desktop"))
}
// The phone's reading UI pieces the desktop app also compiles (see desktop-app/build.gradle.kts).
val phoneSources by tasks.registering(Sync::class) {
    from("../app/src/main/java/com/example/readalong") {
        include("ui/reading/SmoothFollow.kt", "ui/reading/SentenceFade.kt", "ui/reading/ReadingHighlight.kt", "ui/reading/ParagraphText.kt")
        include("ui/components/PlayPauseIcon.kt", "ui/components/MarqueeTitle.kt")
        include("ui/theme/Color.kt")
    }
    filter(compatImport)
    into(layout.buildDirectory.dir("phone/com/example/readalong"))
}
// Fonts and icons go into the app bundle (project.yml lists this folder); the app reads them with NSBundle.
val bundleResources by tasks.registering(Sync::class) {
    from("../app/src/main/res/font") { include("*.ttf"); into("fonts") }
    from("../app/src/main/res/drawable") { include("ic_*.xml", "ic_launcher_foreground.xml"); exclude("ic_launcher_background*", "ic_launcher_padded*", "ic_splash*", "ic_notification*"); into("icons") }
    from("../app/src/github/res/drawable") { include("ic_tip_jar.xml"); into("icons") }
    from("../desktop-app/src/main/resources") { include("icon/**") }
    into(layout.buildDirectory.dir("bundle-resources"))
    doLast { file(layout.buildDirectory.file("bundle-resources/readalong-version.txt")).writeText(appVersion) }
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ReadAlongKit"
            isStatic = true
        }
    }
    sourceSets {
        iosMain {
            kotlin.srcDir(desktopSources.map { layout.buildDirectory.dir("desktop").get() })
            kotlin.srcDir(phoneSources.map { layout.buildDirectory.dir("phone").get() })
            dependencies {
                implementation(project(":engine"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
            }
        }
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
            languageSettings.optIn("kotlinx.cinterop.BetaInteropApi")
            languageSettings.optIn("kotlin.time.ExperimentalTime")
        }
    }
}

tasks.matching { it.name.startsWith("link") && it.name.contains("Framework") }.configureEach { dependsOn(bundleResources) }
