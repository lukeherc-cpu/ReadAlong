// ReadAlong for Windows. A separate Gradle build so the Android release build never needs the
// desktop toolchain (and this one never needs the Android SDK). Build it with ../gradlew -p desktop-app.
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
rootProject.name = "ReadAlong-Desktop"
