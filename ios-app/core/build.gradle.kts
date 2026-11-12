plugins {
    kotlin("multiplatform")
}

// The phone app's reading, EPUB, alignment, timing, link and Audible code, compiled unchanged for iOS (the same
// files desktop-app compiles). The Java and Android library calls in them resolve to the small iOS versions in
// src/iosMain/kotlin/java, javax and org, so the phone's files need no edits. Each copied file also gets
// `import com.example.readalong.compat.*`, for the few JVM-only standard library calls (String.format, Charsets).
// Kotlin/Native's regex engine has no \p{N} (any number); it does know its parts Nd, Nl and No. Every use in the
// phone's code is inside a character class, where the three together mean the same.
val unicodeClasses: (String) -> String = { line -> line.replace("\\\\p{N}", "\\\\p{Nd}\\\\p{Nl}\\\\p{No}") }

val shared = "../../app/src/main/java/com/example/readalong"
val sharedSources by tasks.registering(Sync::class) {
    from(shared) {
        include("domain/**")
        include("data/transcription/TimedRecognitionResult.kt", "data/transcription/WhistleResult.kt", "data/link/LinkClient.kt")
    }
    from("../../app/src/github/java/com/example/readalong") {
        include("audible/**")
        // On Android and desktop the Audible engine and the app that uses it are one module; here they are two.
        filter { line -> line.replace(Regex("^(\\s*)internal "), "$1") }
    }
    filter { line -> if (line.startsWith("package ")) "$line\n\nimport com.example.readalong.compat.*" else line }
    filter(unicodeClasses)
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
    filter(unicodeClasses)
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

tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    // These two read the developer's own books when present and skip otherwise; kotlin.test has no skip.
    filter.excludeTestsMatching("*.privateReferenceWhenProvidedPreservesMusicalGlyphsWithoutBundlingItsText")
    filter.excludeTestsMatching("*.optionalPrivateCollectionMatchesIndependentlyProbedChapterCounts")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = false
    }
}
