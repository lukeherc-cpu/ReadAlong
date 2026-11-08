plugins {
    kotlin("multiplatform")
}

// The phone app's reading, EPUB, alignment, timing, link and Audible code, compiled unchanged for iOS (the same
// files desktop-app compiles). The Java and Android library calls in them resolve to the small iOS versions in
// src/iosMain/kotlin/java, javax and org, so the phone's files need no edits. Each copied file also gets
// `import com.example.readalong.compat.*`, for the few JVM-only standard library calls (String.format, Charsets).
val shared = "../../app/src/main/java/com/example/readalong"
val sharedSources by tasks.registering(Sync::class) {
    from(shared) {
        include("domain/**")
        include("data/transcription/TimedRecognitionResult.kt", "data/transcription/WhistleResult.kt")
    }
    from("../../app/src/github/java/com/example/readalong") { include("audible/**") }
    filter { line -> if (line.startsWith("package ")) "$line\n\nimport com.example.readalong.compat.*" else line }
    into(layout.buildDirectory.dir("shared/com/example/readalong"))
}

// The phone's own unit tests for that code, run on the iOS simulator (iosSimulatorArm64Test) against the iOS
// versions of the Java libraries. EpubWriterTest checks its output with the JDK's DOM parser, which iOS lacks.
val sharedTests by tasks.registering(Sync::class) {
    from("../../app/src/test/java/com/example/readalong") {
        include("domain/**")
        exclude("domain/EpubWriterTest.kt")
        exclude("domain/TranscriptIndexTest.kt") // uses @Test(expected = ...), which kotlin.test has no form of
    }
    filter { line -> if (line.startsWith("package ")) "$line\n\nimport com.example.readalong.compat.*" else line }
    into(layout.buildDirectory.dir("shared-tests/com/example/readalong"))
}

kotlin {
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        iosMain {
            kotlin.srcDir(sharedSources.map { layout.buildDirectory.dir("shared").get() })
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                api("com.fleeksoft.ksoup:ksoup:0.2.5")
            }
        }
        iosTest {
            kotlin.srcDir(sharedTests.map { layout.buildDirectory.dir("shared-tests").get() })
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
            languageSettings.optIn("kotlinx.cinterop.BetaInteropApi")
            languageSettings.optIn("kotlin.time.ExperimentalTime")
            languageSettings.optIn("kotlin.concurrent.atomics.ExperimentalAtomicApi")
        }
    }
}
