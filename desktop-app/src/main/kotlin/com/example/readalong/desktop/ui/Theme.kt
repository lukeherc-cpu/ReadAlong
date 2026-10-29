package com.example.readalong.desktop.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.loadXmlImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.xml.sax.InputSource

/** A book's colours: the dark background, the preview (card) colour and the pale accent. Same roles as the phone's CoverPalette. */
data class CoverPalette(val background: Color, val preview: Color, val accent: Color)

fun hsv(hue: Float, saturation: Float, value: Float) = Color(java.awt.Color.HSBtoRGB(hue / 360f, saturation, value) or (0xFF shl 24))

fun palette(hue: Float, saturation: Float) = CoverPalette(hsv(hue, saturation, .25f), hsv(hue, saturation, .38f), hsv(hue, .20f, .95f))

/** The phone's launch palettes; the accent colour picks one. */
data class AccentPalette(val name: String, val color: Long, val background: Long, val ink: Long, val pale: Long)
val AccentPalettes = listOf(
    AccentPalette("Blue", 0xFF496B8A, 0xFF182A3B, 0xFF496B8A, 0xFFD4E5F3),
    AccentPalette("Teal", 0xFF287A78, 0xFF143837, 0xFF246664, 0xFFAAF2F0),
    AccentPalette("Green", 0xFF477A52, 0xFF213826, 0xFF3B6645, 0xFFAAF2B9),
    AccentPalette("Amber", 0xFF8A6B25, 0xFF382D14, 0xFF665224, 0xFFF2DCAA),
    AccentPalette("Orange", 0xFFA76535, 0xFF382314, 0xFF664024, 0xFFF2C8AA),
    AccentPalette("Coral", 0xFF9A4F4A, 0xFF381D1B, 0xFF663431, 0xFFF2AEAA),
    AccentPalette("Rose", 0xFF955376, 0xFF381F2C, 0xFF663951, 0xFFF2AAD0),
    AccentPalette("Purple", 0xFF72558C, 0xFF2E2238, 0xFF533E66, 0xFFD0AAF2),
    AccentPalette("Indigo", 0xFF565F94, 0xFF212438, 0xFF3B4166, 0xFFAAB4F2),
    AccentPalette("Slate", 0xFF666B73, 0xFF323438, 0xFF5A5F66, 0xFFD7E1F2),
)
fun accentPalette(argb: Long): CoverPalette = (AccentPalettes.firstOrNull { it.color == argb } ?: AccentPalettes.first()).let {
    CoverPalette(Color(it.background), Color(it.ink), Color(it.pale))
}

/** The phone's dark scheme (readAlongScheme), every Material role set from one palette. */
fun darkScheme(palette: CoverPalette): ColorScheme = darkColorScheme(
    primary = palette.accent, onPrimary = palette.background,
    primaryContainer = palette.preview, onPrimaryContainer = Color.White, inversePrimary = palette.preview,
    secondary = palette.accent, onSecondary = palette.background, secondaryContainer = palette.preview, onSecondaryContainer = Color.White,
    tertiary = palette.accent, onTertiary = palette.background, tertiaryContainer = palette.preview, onTertiaryContainer = Color.White,
    background = Color(0xFF111416), onBackground = Color(0xFFE7E9EC), surface = Color(0xFF111416), onSurface = Color(0xFFE7E9EC),
    surfaceVariant = lerp(Color(0xFF26292D), palette.background, .35f), onSurfaceVariant = Color(0xFFBFC3C9),
    surfaceTint = palette.accent, inverseSurface = palette.accent, inverseOnSurface = palette.background,
    outline = lerp(Color(0xFF8A9097), palette.accent, .25f), outlineVariant = Color(0xFF33373C), scrim = Color.Black,
    surfaceBright = lerp(Color(0xFF34383D), palette.background, .3f), surfaceDim = Color(0xFF0D0F11),
    surfaceContainerLowest = Color(0xFF0B0D0F), surfaceContainerLow = lerp(Color(0xFF16191C), palette.background, .3f),
    surfaceContainer = lerp(Color(0xFF1B1E21), palette.background, .35f), surfaceContainerHigh = lerp(Color(0xFF23272A), palette.background, .35f),
    surfaceContainerHighest = lerp(Color(0xFF2C3034), palette.background, .35f))

object Fonts {
    private fun load(name: String, weight: FontWeight = FontWeight.Normal): FontFamily = runCatching {
        val bytes = requireNotNull(Fonts::class.java.getResourceAsStream("/fonts/$name.ttf")).use { it.readBytes() }
        FontFamily(Font("readalong-$name", bytes, weight))
    }.getOrDefault(FontFamily.SansSerif)

    val app: FontFamily by lazy { load("jakarta_extrabold", FontWeight.ExtraBold) }
    val reading = listOf("LITERATA" to "Literata", "LORA" to "Lora", "MERRIWEATHER" to "Merriweather", "BASKERVILLE" to "Libre Baskerville",
        "GARAMOND" to "EB Garamond", "SOURCE_SERIF" to "Source Serif 4", "OPEN_SANS" to "Open Sans", "LATO" to "Lato",
        "MONTSERRAT" to "Montserrat", "BITTER" to "Bitter", "JAKARTA" to "Plus Jakarta Sans")
    private val files = mapOf("LITERATA" to "literata", "LORA" to "lora", "MERRIWEATHER" to "merriweather", "BASKERVILLE" to "librebaskerville",
        "GARAMOND" to "ebgaramond", "SOURCE_SERIF" to "sourceserif4", "OPEN_SANS" to "opensans", "LATO" to "lato", "MONTSERRAT" to "montserrat",
        "BITTER" to "bitter")
    private val cache = HashMap<String, FontFamily>()
    fun reading(key: String): FontFamily = if (key == "JAKARTA") app else synchronized(cache) { cache.getOrPut(key) { load(files[key] ?: "literata") } }
}

/** The phone's icons: its Android vector drawables, loaded as ImageVectors. */
object AppIcons {
    private val cache = HashMap<String, ImageVector>()
    @Suppress("DEPRECATION")
    fun get(name: String): ImageVector = synchronized(cache) {
        cache.getOrPut(name) {
            val xml = requireNotNull(AppIcons::class.java.getResourceAsStream("/icons/$name.xml")) { "Missing icon $name" }
                .use { it.readBytes().decodeToString() }
                // Android resource references have no meaning outside Android.
                .replace("@android:color/transparent", "#00000000")
            loadXmlImageVector(InputSource(java.io.StringReader(xml)), Density(1f))
        }
    }
}

@Composable
fun ReadAlongTheme(accent: Long, content: @Composable () -> Unit) {
    val palette = accentPalette(accent)
    val font = Fonts.app
    val base = Typography()
    fun TextStyleCopy(style: androidx.compose.ui.text.TextStyle) = style.copy(fontFamily = font, fontWeight = FontWeight.ExtraBold, fontFeatureSettings = "tnum")
    val typography = Typography(
        displayLarge = TextStyleCopy(base.displayLarge), displayMedium = TextStyleCopy(base.displayMedium), displaySmall = TextStyleCopy(base.displaySmall),
        headlineLarge = TextStyleCopy(base.headlineLarge.copy(fontSize = 32.sp, lineHeight = 40.sp)), headlineMedium = TextStyleCopy(base.headlineMedium),
        headlineSmall = TextStyleCopy(base.headlineSmall), titleLarge = TextStyleCopy(base.titleLarge), titleMedium = TextStyleCopy(base.titleMedium),
        titleSmall = TextStyleCopy(base.titleSmall), bodyLarge = TextStyleCopy(base.bodyLarge.copy(lineHeight = 26.sp)), bodyMedium = TextStyleCopy(base.bodyMedium),
        bodySmall = TextStyleCopy(base.bodySmall), labelLarge = TextStyleCopy(base.labelLarge), labelMedium = TextStyleCopy(base.labelMedium),
        labelSmall = TextStyleCopy(base.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp)))
    MaterialTheme(colorScheme = darkScheme(palette), typography = typography,
        shapes = Shapes(small = androidx.compose.foundation.shape.RoundedCornerShape(Corners.small), medium = androidx.compose.foundation.shape.RoundedCornerShape(Corners.medium),
            large = androidx.compose.foundation.shape.RoundedCornerShape(Corners.large)), content = content)
}

/** The app's corner sizes: small (chips, thumbnails), medium (covers, rows), large (cards, sheets). */
object Corners {
    val small = 8.dp
    val medium = 12.dp
    val large = 20.dp
}
