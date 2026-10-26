package com.example.readalong.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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

/**
 * Every Material colour role, set from one palette: Material's own defaults (lavender containers,
 * dialog and menu surfaces) never show through.
 */
fun readAlongScheme(light: Boolean, palette: com.example.readalong.ui.components.CoverPalette, page: Color = Color(0xFFF1F5F9)): ColorScheme {
    return if (light) lightColorScheme(
        primary = palette.preview, onPrimary = Color.White,
        primaryContainer = palette.accent, onPrimaryContainer = palette.background,
        inversePrimary = palette.accent,
        secondary = palette.preview, onSecondary = Color.White,
        secondaryContainer = androidx.compose.ui.graphics.lerp(palette.accent, Color.White, .25f), onSecondaryContainer = palette.background,
        tertiary = palette.preview, onTertiary = Color.White, tertiaryContainer = palette.accent, onTertiaryContainer = palette.background,
        background = page, onBackground = Color(0xFF1F2428), surface = page, onSurface = Color(0xFF1F2428),
        surfaceVariant = androidx.compose.ui.graphics.lerp(palette.accent, Color.White, .28f), onSurfaceVariant = androidx.compose.ui.graphics.lerp(Color(0xFF59616A), palette.preview, .22f),
        surfaceTint = palette.preview, inverseSurface = palette.background, inverseOnSurface = Color.White,
        outline = androidx.compose.ui.graphics.lerp(Color(0xFF7D858E), palette.preview, .3f), outlineVariant = androidx.compose.ui.graphics.lerp(page, palette.preview, .18f),
        scrim = Color.Black,
        surfaceBright = Color.White, surfaceDim = androidx.compose.ui.graphics.lerp(page, palette.accent, .45f),
        surfaceContainerLowest = Color.White, surfaceContainerLow = androidx.compose.ui.graphics.lerp(page, Color.White, .55f),
        surfaceContainer = androidx.compose.ui.graphics.lerp(page, palette.accent, .22f), surfaceContainerHigh = androidx.compose.ui.graphics.lerp(page, palette.accent, .36f),
        surfaceContainerHighest = androidx.compose.ui.graphics.lerp(page, palette.accent, .5f))
    else darkColorScheme(
        primary = palette.accent, onPrimary = palette.background,
        primaryContainer = palette.preview, onPrimaryContainer = Color.White,
        inversePrimary = palette.preview,
        secondary = palette.accent, onSecondary = palette.background,
        secondaryContainer = palette.preview, onSecondaryContainer = Color.White,
        tertiary = palette.accent, onTertiary = palette.background, tertiaryContainer = palette.preview, onTertiaryContainer = Color.White,
        background = Color(0xFF111416), onBackground = Color(0xFFE7E9EC), surface = Color(0xFF111416), onSurface = Color(0xFFE7E9EC),
        surfaceVariant = androidx.compose.ui.graphics.lerp(Color(0xFF26292D), palette.background, .35f), onSurfaceVariant = Color(0xFFBFC3C9),
        surfaceTint = palette.accent, inverseSurface = palette.accent, inverseOnSurface = palette.background,
        outline = androidx.compose.ui.graphics.lerp(Color(0xFF8A9097), palette.accent, .25f), outlineVariant = Color(0xFF33373C),
        scrim = Color.Black,
        surfaceBright = androidx.compose.ui.graphics.lerp(Color(0xFF34383D), palette.background, .3f), surfaceDim = Color(0xFF0D0F11),
        surfaceContainerLowest = Color(0xFF0B0D0F), surfaceContainerLow = androidx.compose.ui.graphics.lerp(Color(0xFF16191C), palette.background, .3f),
        surfaceContainer = androidx.compose.ui.graphics.lerp(Color(0xFF1B1E21), palette.background, .35f), surfaceContainerHigh = androidx.compose.ui.graphics.lerp(Color(0xFF23272A), palette.background, .35f),
        surfaceContainerHighest = androidx.compose.ui.graphics.lerp(Color(0xFF2C3034), palette.background, .35f))
}

/** The player and reader's dark scheme, from the book's cover palette. */
fun playerScheme(palette: com.example.readalong.ui.components.CoverPalette, background: Color = Color(0xFF101418)): ColorScheme =
    readAlongScheme(false, palette).copy(background = background, surface = background, onPrimary = palette.background)

@Composable
fun ReadAlongTheme(light: Boolean = true, content: @Composable () -> Unit) {
    val palette = basicPalette(LocalAppSettings.current.basicColor)
    MaterialTheme(
        colorScheme = readAlongScheme(light, palette, Color(com.example.readalong.ui.launch.LaunchPalettes.closest(LocalAppSettings.current.basicColor).page)),
        typography = appTypography(com.example.readalong.ui.reading.readingFont(LocalAppSettings.current.appFont), LocalAppSettings.current.appFont == "JAKARTA"), content = content)
}

/** The app's corner sizes: small (chips, thumbnails), medium (covers, rows), large (cards, sheets). */
object Corners {
    val small = 8.dp
    val medium = 12.dp
    val large = 20.dp
}
