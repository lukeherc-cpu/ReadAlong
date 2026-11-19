package com.example.readalong.data.preferences

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey

/**
 * New installs start in the dark theme; anyone updating keeps the theme they already had.
 *
 * Before 0.55 the theme was light unless the person turned "Light theme" off, and the choice was only written once
 * some setting changed. So a missing "library_light" means either a brand new install (start dark) or an older install
 * that never saved it (keep light, which is what that person has been seeing). The decision is written once, so it
 * never changes on a later update.
 */
object FirstInstallTheme {
    val key = booleanPreferencesKey("library_light")

    /** True (light) for anything that looks like an existing install; false (dark) only for a fresh one. */
    fun startsLight(hasSavedSettings: Boolean, hasLaunchCache: Boolean, packageUpdated: Boolean): Boolean =
        hasSavedSettings || hasLaunchCache || packageUpdated

    /** The app has been updated in place at least once since it was installed (Play Store or sideloaded update). */
    fun packageUpdated(context: Context): Boolean = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.lastUpdateTime != info.firstInstallTime
    }.getOrDefault(true)

    /** The launch colors cache written by every earlier version once the app had run. */
    fun hasLaunchCache(context: Context): Boolean =
        context.getSharedPreferences("launch_appearance", Context.MODE_PRIVATE).contains("light")

    /** Used before the saved settings are readable (the launch screen): dark only on a fresh install. */
    fun launchDefaultLight(context: Context): Boolean = packageUpdated(context)

    fun migration(context: Context) = object : DataMigration<Preferences> {
        override suspend fun shouldMigrate(currentData: Preferences) = currentData[key] == null
        override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().apply {
            this[key] = startsLight(currentData.asMap().isNotEmpty(), hasLaunchCache(context), packageUpdated(context))
        }.toPreferences()
        override suspend fun cleanUp() {}
    }
}
