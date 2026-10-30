package com.example.readalong.ui.launch

/** Bundled palettes keep launcher, system starting window and Compose exactly in agreement. */
data class LaunchPalette(val name: String, val color: Long, val background: Long, val ink: Long, val pale: Long, val page: Long) {
    fun background(light: Boolean) = if (light) page else 0xFF111416
    fun ink(light: Boolean) = if (light) ink else pale
}
object LaunchPalettes {
    val all = listOf(
        LaunchPalette("Blue", 0xFF496B8A, 0xFF182A3B, 0xFF496B8A, 0xFFD4E5F3, 0xFFFBFCFE),
        LaunchPalette("Teal", 0xFF287A78, 0xFF143837, 0xFF246664, 0xFFAAF2F0, 0xFFF7FEFE),
        LaunchPalette("Green", 0xFF477A52, 0xFF213826, 0xFF3B6645, 0xFFAAF2B9, 0xFFF7FEF8),
        LaunchPalette("Amber", 0xFF8A6B25, 0xFF382D14, 0xFF665224, 0xFFF2DCAA, 0xFFFEFCF7),
        LaunchPalette("Orange", 0xFFA76535, 0xFF382314, 0xFF664024, 0xFFF2C8AA, 0xFFFEFAF7),
        LaunchPalette("Coral", 0xFF9A4F4A, 0xFF381D1B, 0xFF663431, 0xFFF2AEAA, 0xFFFEF7F7),
        LaunchPalette("Rose", 0xFF955376, 0xFF381F2C, 0xFF663951, 0xFFF2AAD0, 0xFFFEF7FA),
        LaunchPalette("Purple", 0xFF72558C, 0xFF2E2238, 0xFF533E66, 0xFFD0AAF2, 0xFFFAF7FE),
        LaunchPalette("Indigo", 0xFF565F94, 0xFF212438, 0xFF3B4166, 0xFFAAB4F2, 0xFFF7F8FE),
        LaunchPalette("Slate", 0xFF666B73, 0xFF323438, 0xFF5A5F66, 0xFFD7E1F2, 0xFFFBFCFE),
    )
    fun closest(color: Long): LaunchPalette = all.minBy { palette ->
        val a = hsv(color.toInt())
        val b = hsv(palette.color.toInt())
        val hue = kotlin.math.abs(a[0] - b[0]).let { minOf(it, 360f - it) } / 180f
        hue * hue * minOf(a[1], b[1]) * 4 + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]) * .25f
    }
}
private fun hsv(color: Int) = FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) }
