package com.example.readalong

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import com.example.readalong.data.preferences.AppPreferences
import com.example.readalong.ui.launch.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class LauncherPaletteTest {
    @Test fun everySupportedColorHasExactlyOneMatchingLauncherAndNativeSplashTheme() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val previous = app.appPreferences.preferences.first()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            for (palette in LaunchPalettes.all) for (light in listOf(true, false)) {
                app.appPreferences.update { it.copy(basicColor = palette.color, libraryLight = light) }
                val chosen = app.appPreferences.preferences.first()
                assertEquals(palette.color, chosen.basicColor)
                assertEquals(palette.color, LaunchAppearance.read(app).basicColor)
                val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.packageName)
                val entries = app.packageManager.queryIntentActivities(query, 0)
                assertEquals(1, entries.size)
                assertEquals(LaunchAppearance.component(app, chosen).className, entries.single().activityInfo.name)
                assertEquals(LaunchAppearance.theme(app, chosen), entries.single().activityInfo.themeResource)
                val themed = android.view.ContextThemeWrapper(app, LaunchAppearance.theme(app, chosen))
                val attrs = themed.obtainStyledAttributes(intArrayOf(android.R.attr.windowSplashScreenBackground, android.R.attr.windowSplashScreenAnimatedIcon))
                try {
                    assertEquals(palette.background(light).toInt(), attrs.getColor(0, 0))
                    assertNotNull(attrs.getDrawable(1))
                    val title = themed.obtainStyledAttributes(intArrayOf(android.R.attr.windowNoTitle))
                    try { assertTrue("Startup must not flash a platform title bar", title.getBoolean(0, false)) } finally { title.recycle() }
                } finally { attrs.recycle() }
                assertEquals(palette.color, LaunchPalettes.closest(palette.color).color)
                scenario.onActivity { assertFalse("Icon changes must leave the current app running", it.isFinishing) }
            }
        } finally { app.appPreferences.update { previous }; scenario.close() }
    }
}
