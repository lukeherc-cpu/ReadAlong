package com.example.readalong.ui.launch

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.readalong.MainActivity

/** A launcher component may be disabled by a palette change. It never owns app UI or playback. */
open class PaletteEntryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Transfer the selected system splash to the stable activity without drawing an intermediate page.
        splash.setKeepOnScreenCondition { true }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
