import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.2.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
    id("org.jetbrains.compose") version "1.9.3"
}

val appVersion = "1.0.0"
group = "com.example.readalong"
version = appVersion

kotlin { jvmToolchain(21) }

// The phone app's reading, EPUB, alignment and timing code, compiled unchanged into the desktop app.
// Only files with no Android dependency are listed; the desktop app supplies its own storage and UI shell.
val shared = "../app/src/main/java/com/example/readalong"
val sharedSources by tasks.registering(Sync::class) {
    from(shared) {
        include("domain/**")
        include("data/transcription/TimedRecognitionResult.kt", "data/transcription/WhistleResult.kt")
        include("ui/reading/SmoothFollow.kt", "ui/reading/SentenceFade.kt", "ui/reading/ReadingHighlight.kt", "ui/reading/ParagraphText.kt")
        include("ui/components/PlayPauseIcon.kt", "ui/components/MarqueeTitle.kt")
        include("ui/theme/Color.kt")
    }
    into(layout.buildDirectory.dir("shared/com/example/readalong"))
}
// The phone app's fonts and icons (Android vector drawables load as Compose ImageVectors on desktop too).
val sharedResources by tasks.registering(Sync::class) {
    from("../app/src/main/res/font") { include("*.ttf"); into("fonts") }
    from("../app/src/main/res/drawable") { include("ic_*.xml"); exclude("ic_launcher*", "ic_splash*", "ic_notification*"); into("icons") }
    into(layout.buildDirectory.dir("shared-resources"))
}
sourceSets.main {
    kotlin.srcDir(sharedSources.map { layout.buildDirectory.dir("shared").get() })
    resources.srcDir(sharedResources.map { layout.buildDirectory.dir("shared-resources").get() })
}

// FFmpeg decodes every audiobook format; only the natives for the platform being packaged are bundled.
val javacpp = "1.5.14"
val ffmpeg = "8.1.2-$javacpp"
val nativePlatform = providers.gradleProperty("nativePlatform").orNull ?: run {
    val os = System.getProperty("os.name").lowercase()
    when {
        os.contains("win") -> "windows-x86_64"
        os.contains("mac") -> if (System.getProperty("os.arch") == "aarch64") "macosx-arm64" else "macosx-x86_64"
        else -> "linux-x86_64"
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("org.json:json:20240303")
    implementation("org.bytedeco:javacv:$javacpp") { isTransitive = false }
    implementation("org.bytedeco:javacpp:$javacpp")
    implementation("org.bytedeco:javacpp:$javacpp:$nativePlatform")
    implementation("org.bytedeco:ffmpeg:$ffmpeg")
    implementation("org.bytedeco:ffmpeg:$ffmpeg:$nativePlatform")
    implementation("net.java.dev.jna:jna-platform:5.18.1")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test { useJUnitPlatform() }

// whisper.cpp (built by native/CMakeLists.txt) is copied here so the packaged app finds it next to its jars.
val nativeDir = layout.projectDirectory.dir("native/build/out")

compose.desktop {
    application {
        mainClass = "com.example.readalong.desktop.MainKt"
        jvmArgs += listOf("-Xmx2g")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "ReadAlong"
            packageVersion = appVersion
            description = "Audiobook player that highlights the text as the narrator reads"
            vendor = "ReadAlong"
            copyright = "GPL-3.0"
            licenseFile.set(project.file("../LICENSE"))
            appResourcesRootDir.set(project.layout.projectDirectory.dir("native/build/resources"))
            modules("java.sql", "jdk.unsupported", "java.net.http", "jdk.crypto.ec")
            windows {
                menuGroup = "ReadAlong"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "7f4c1d1e-3b8a-4f0e-9a51-5c2b7d9e4a10"
                iconFile.set(project.file("src/main/resources/icon/readalong.ico"))
            }
        }
        buildTypes.release.proguard { isEnabled.set(false) }
    }
}
