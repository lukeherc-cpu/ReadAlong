plugins {
    kotlin("multiplatform")
}

// The desktop app's library, transcription, audio, reading and linking code (everything but its Compose screens),
// compiled for the iPhone. The files in `iosOwn` are replaced by iPhone versions in src/iosMain/kotlin: AVFoundation
// for playback and decoding, whisper.cpp in the app's own process, and the phone's side of linking with a computer.
// No Compose here, so this module also type-checks on Linux without Google's Maven repository.
val desktop = "../../desktop-app/src/main/kotlin/com/example/readalong/desktop"
val iosOwn = listOf(
    "audio/AudioPlayer.kt", "audio/AudioReader.kt",
    "core/AppPaths.kt", "core/MediaProbe.kt",
    "link/DeviceLink.kt",
    "transcription/WhisperNative.kt", "transcription/Worker.kt", "transcription/TranscriberProcess.kt",
)
val compatImport: (String) -> String = { line -> if (line.startsWith("package ")) "$line\n\nimport com.example.readalong.compat.*" else line }

/** Kotlin/Native keeps Dispatchers.IO internal, so the shared code's IO work goes to compat's IoDispatcher. */
val ioDispatcher: (String) -> String = { line -> line.replace(Regex("\\bDispatchers\\.IO\\b"), "com.example.readalong.compat.IoDispatcher") }

/**
 * `@Synchronized fun x(…) { … }` and `@Synchronized fun x(…) = …` take the JVM object's monitor; there is no such
 * annotation in Kotlin/Native, so each body is wrapped in compat's synchronized { } instead.
 */
fun wrapSynchronized(text: String): String {
    val out = StringBuilder()
    var i = 0
    while (true) {
        val at = text.indexOf("@Synchronized", i)
        if (at < 0) { out.append(text, i, text.length); break }
        out.append(text, i, at)
        var p = at + "@Synchronized".length
        // The signature runs to the first `{` or `=` outside parentheses.
        var depth = 0
        while (p < text.length) {
            val c = text[p]
            if (c == '(') depth++ else if (c == ')') depth--
            else if (depth == 0 && (c == '{' || (c == '=' && text.getOrNull(p + 1) != '='))) break
            p++
        }
        val signature = text.substring(at + "@Synchronized".length, p).trimStart()
        if (text[p] == '{') {
            var d = 0; var end = p
            while (end < text.length) { if (text[end] == '{') d++; if (text[end] == '}') { d--; if (d == 0) break }; end++ }
            out.append(signature).append("{ synchronized(this) {").append(text, p + 1, end).append("} }")
            i = end + 1
        } else {
            // An expression body ends at the end of its line, unless brackets carry it on.
            var end = p + 1; var d = 0
            while (end < text.length) {
                val c = text[end]
                if (c == '(' || c == '{' || c == '[') d++ else if (c == ')' || c == '}' || c == ']') d--
                else if (c == '\n' && d <= 0) break
                end++
            }
            out.append(signature).append("= synchronized(this) {").append(text, p + 1, end).append(" }")
            i = end
        }
    }
    return out.toString()
}

val desktopEngine by tasks.registering(Sync::class) {
    from(desktop) {
        include("core/**", "audio/**", "link/**", "transcription/**", "reading/**")
        exclude(iosOwn)
    }
    filter(compatImport)
    filter(ioDispatcher)
    into(layout.buildDirectory.dir("desktop/com/example/readalong/desktop"))
    doLast {
        fileTree(destinationDir).matching { include("**/*.kt") }.forEach { f ->
            val text = f.readText()
            if ("@Synchronized" in text) f.writeText(wrapSynchronized(text))
        }
    }
}

kotlin {
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        iosMain {
            kotlin.srcDir(desktopEngine.map { layout.buildDirectory.dir("desktop").get() })
            dependencies {
                api(project(":core"))
                api("org.jetbrains.skiko:skiko:0.9.22.2")
            }
        }
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
            languageSettings.optIn("kotlinx.cinterop.BetaInteropApi")
            languageSettings.optIn("kotlin.time.ExperimentalTime")
        }
    }
}
