plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The watch app carries the phone app's version, so a release bump in app/build.gradle.kts covers both.
val phoneBuild = rootProject.file("app/build.gradle.kts").readText()
fun phoneValue(name: String) = Regex("$name = \"?([^\"\\n]+)\"?").find(phoneBuild)!!.groupValues[1].trim()

// The ReadAlong watch app. It controls the phone's player over the Wearable data layer, so it has the
// phone app's id (and signing key) in each flavor: Wear OS only connects apps with the same id.
android {
    namespace = "com.example.readalong.wear"
    compileSdk { version = release(37) }

    defaultConfig {
        applicationId = "com.example.readalong"
        minSdk = 30
        targetSdk = 36
        versionCode = phoneValue("versionCode").toInt()
        versionName = phoneValue("versionName")
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("github") { dimension = "distribution" }
        create("play") { dimension = "distribution"; applicationId = "io.github.lukeherc_cpu.readalong" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation("androidx.wear.compose:compose-material:1.4.1")
    implementation("androidx.wear.compose:compose-foundation:1.4.1")
    implementation("androidx.wear.compose:compose-navigation:1.4.1")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
}
