import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.ksp) apply false
}

// Development-only validation tool; never packaged in the Android application.
val epubCheck by configurations.creating {
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
    }
}
dependencies { epubCheck("org.w3c:epubcheck:5.3.0") }
tasks.register<JavaExec>("validateSampleEpub") {
    group = "verification"
    description = "Validate the generated JVM-test publication with official EPUBCheck."
    dependsOn(":app:testDebugUnitTest")
    classpath = epubCheck
    mainClass.set("com.adobe.epubcheck.tool.Checker")
    args(layout.projectDirectory.file("app/build/verification/epub-structure.epub").asFile.absolutePath)
}
