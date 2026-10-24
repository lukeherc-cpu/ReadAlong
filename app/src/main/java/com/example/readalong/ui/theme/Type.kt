package com.example.readalong.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// App typography follows the selected family; the bundled default is Jakarta ExtraBold.
val Typography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 16.sp,
        lineHeight = 26.sp,
    ),
)

/**
 * Three weights only: regular for reading, semi-bold for titles, labels and buttons, extra-bold for
 * the big headlines. (Every style used to be extra-bold, which left no hierarchy.)
 */
fun appTypography(font: FontFamily, @Suppress("UNUSED_PARAMETER") extraBold: Boolean = false): Typography {
    // Tabular digits everywhere, so counting times and percentages don't shift sideways.
    fun TextStyle.as_(weight: FontWeight) = copy(fontFamily = font, fontWeight = weight, fontFeatureSettings = "tnum")
    return Typography.copy(
        displayLarge = Typography.displayLarge.as_(FontWeight.ExtraBold),
        displayMedium = Typography.displayMedium.as_(FontWeight.ExtraBold),
        displaySmall = Typography.displaySmall.as_(FontWeight.ExtraBold),
        headlineLarge = Typography.headlineLarge.as_(FontWeight.ExtraBold),
        headlineMedium = Typography.headlineMedium.as_(FontWeight.ExtraBold),
        headlineSmall = Typography.headlineSmall.as_(FontWeight.SemiBold),
        titleLarge = Typography.titleLarge.as_(FontWeight.SemiBold),
        titleMedium = Typography.titleMedium.as_(FontWeight.SemiBold),
        titleSmall = Typography.titleSmall.as_(FontWeight.SemiBold),
        bodyLarge = Typography.bodyLarge.as_(FontWeight.Normal),
        bodyMedium = Typography.bodyMedium.as_(FontWeight.Normal),
        bodySmall = Typography.bodySmall.as_(FontWeight.Normal),
        labelLarge = Typography.labelLarge.as_(FontWeight.SemiBold),
        labelMedium = Typography.labelMedium.as_(FontWeight.SemiBold),
        // 12 sp floor: nothing in the app is smaller (Material's default is 11 sp).
        labelSmall = Typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp).as_(FontWeight.SemiBold),
    )
}
