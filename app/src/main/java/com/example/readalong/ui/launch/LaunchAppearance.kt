package com.example.readalong.ui.launch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.example.readalong.data.preferences.AppPreferences

/** Static launcher resources are selected as a unit; exact colors never depend on a late DataStore emission. */
object LaunchAppearance {
    private var applied: ComponentName? = null
    private fun cache(context: Context) = context.getSharedPreferences("launch_appearance", Context.MODE_PRIVATE)
    fun read(context: Context): AppPreferences {
        val saved = cache(context)
        return AppPreferences(libraryLight = saved.getBoolean("light", true), basicColor = LaunchPalettes.closest(saved.getLong("color", 0xFF496B8A)).color)
    }
    fun component(context: Context, value: AppPreferences): ComponentName {
        val palette = LaunchPalettes.closest(value.basicColor)
        return ComponentName(context.packageName, "${context.packageName}.ui.launch.${palette.name}${if (value.libraryLight) "Light" else "Dark"}Launcher")
    }
    fun theme(context: Context, value: AppPreferences): Int = context.resources.getIdentifier(
        "Launch_${LaunchPalettes.closest(value.basicColor).name}_${if (value.libraryLight) "Light" else "Dark"}", "style", context.packageName)
    fun intent(context: Context): Intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(component(context, read(context))).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

    @Synchronized fun apply(context: Context, value: AppPreferences) {
        val selected = component(context, value)
        if (applied == selected) return
        val manager = context.packageManager
        val changes = LaunchPalettes.all.flatMap { palette -> listOf(true, false).map { light ->
            val name = component(context, value.copy(basicColor = palette.color, libraryLight = light))
            name to (name == selected)
        } }.filter { (name, enabled) ->
            val override = manager.getComponentEnabledSetting(name)
            val actual = if (override == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) name.className.endsWith("BlueLightLauncher")
                else override == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            actual != enabled
        }
        // Commit launch colors before making the new component discoverable.
        check(cache(context).edit().putBoolean("light", value.libraryLight).putLong("color", LaunchPalettes.closest(value.basicColor).color).commit())
        if (Build.VERSION.SDK_INT >= 33 && changes.isNotEmpty()) {
            manager.setComponentEnabledSettings(changes.map { (name, enabled) ->
                PackageManager.ComponentEnabledSetting(name, if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            })
        } else changes.sortedByDescending { it.second }.forEach { (name, enabled) ->
            manager.setComponentEnabledSetting(name, if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
        applied = selected
    }
}
