package com.example.readalong.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val LogoBlue = Color(0xFF496B8A)
val LogoPaleBlue = Color(0xFFD4E5F3)

fun basicPalette(argb: Long): com.example.readalong.ui.components.CoverPalette {
    com.example.readalong.ui.launch.LaunchPalettes.all.firstOrNull { it.color == argb }?.let {
        return com.example.readalong.ui.components.CoverPalette(Color(it.background), Color(it.ink), Color(it.pale))
    }
    val color = argb.toInt()
    if (argb == 0xFF496B8A) return com.example.readalong.ui.components.CoverPalette(Color(0xFF182A3B), LogoBlue, LogoPaleBlue)
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color, hsv)
    fun tone(s: Float, v: Float) = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], s, v)))
    return com.example.readalong.ui.components.CoverPalette(tone(hsv[1].coerceAtMost(.65f), .22f), tone(hsv[1].coerceAtMost(.65f), .40f), tone(hsv[1].coerceAtMost(.30f), .95f))
}

@Composable
fun ReadAlongTheme(light: Boolean = true, content: @Composable () -> Unit) {
    val palette = basicPalette(LocalAppSettings.current.basicColor)
    MaterialTheme(
        colorScheme = if (light) lightColorScheme(
            primary = palette.preview, onPrimary = Color.White,
            primaryContainer = palette.accent, onPrimaryContainer = palette.background,
            secondary = palette.preview, onSecondary = Color.White,
            secondaryContainer = androidx.compose.ui.graphics.lerp(palette.accent, Color.White, .25f), onSecondaryContainer = palette.background,
            background = Color(com.example.readalong.ui.launch.LaunchPalettes.closest(LocalAppSettings.current.basicColor).page), surface = Color(com.example.readalong.ui.launch.LaunchPalettes.closest(LocalAppSettings.current.basicColor).page),
            onBackground = Color(0xFF202529), onSurface = Color(0xFF202529),
            surfaceVariant = androidx.compose.ui.graphics.lerp(palette.accent, Color.White, .28f),
            onSurfaceVariant = palette.preview, outline = palette.preview.copy(alpha = .6f))
        else darkColorScheme(primary = palette.accent, onPrimary = palette.background,
            primaryContainer = palette.preview, onPrimaryContainer = Color.White,
            secondary = palette.accent, secondaryContainer = palette.preview, onSecondaryContainer = Color.White,
            background = Color(0xFF111416), surface = Color(0xFF111416),
            onBackground = Color(0xFFE7E9EC), onSurface = Color(0xFFE7E9EC),
            surfaceVariant = Color(0xFF27282E),
            onSurfaceVariant = Color(0xFFBFC3C9), outline = palette.accent.copy(alpha = .6f)),
        typography = appTypography(com.example.readalong.ui.reading.readingFont(LocalAppSettings.current.appFont), LocalAppSettings.current.appFont == "JAKARTA"), content = content)
}
