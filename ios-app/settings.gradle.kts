// ReadAlong for iPhone. A separate Gradle build, like desktop-app, so the Android and desktop builds never need
// the Kotlin/Native toolchain. Build it with ../gradlew -p ios-app (the framework link step needs macOS).
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}
rootProject.name = "ReadAlong-iOS"
include(":core", ":engine")
