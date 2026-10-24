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

fun appTypography(font: FontFamily, extraBold: Boolean = false): Typography = Typography.copy(
    displayLarge = Typography.displayLarge.copy(fontFamily = font, fontWeight = if (extraBold) FontWeight.ExtraBold else Typography.displayLarge.fontWeight),
    displayMedium = Typography.displayMedium.copy(fontFamily = font, fontWeight = if (extraBold) FontWeight.ExtraBold else Typography.displayMedium.fontWeight),
    displaySmall = Typography.displaySmall.copy(fontFamily = font, fontWeight = if (extraBold) FontWeight.ExtraBold else Typography.displaySmall.fontWeight),
    headlineLarge = Typography.headlineLarge.copy(fontFamily = font, fontWeight = if (extraBold) FontWeight.ExtraBold else Typography.headlineLarge.fontWeight),
    headlineMedium = Typography.headlineMedium.copy(fontFamily = font, fontWeight = if (extraBold) FontWeight.ExtraBold else Typography.headlineMedium.fontWeight),
    headlineSmall = Typography.headlineSmall.copy(fontFamily = font, fontWeight = if (extraBold) FontWeight.ExtraBold else Typography.headlineSmall.fontWeight),
    titleLarge = Typography.titleLarge.copy(fontFamily = font, fontWeight = if (extraBold) FontWeight.ExtraBold else Typography.titleLarge.fontWeight),
    titleMedium = Typography.titleMedium.copy(fontFamily = font, fontWeight = if (extraBold) FontWeight.ExtraBold else Typography.titleMedium.fontWeight),
    titleSmall = Typography.titleSmall.copy(fontFamily = font, fontWeight = if (extraBold) FontWeight.ExtraBold else Typography.titleSmall.fontWeight),
    bodyLarge = Typography.bodyLarge.copy(fontFamily = font, fontWeight = if (extraBold) FontWeight.ExtraBold else Typography.bodyLarge.fontWeight),
    bodyMedium = Typography.bodyMedium.copy(fontFamily = font, fontWeight = if (extraBold) FontWeight.ExtraBold else Typography.bodyMedium.fontWeight),
    bodySmall = Typography.bodySmall.copy(fontFamily = font, fontWeight = if (extraBold) FontWeight.ExtraBold else Typography.bodySmall.fontWeight),
    labelLarge = Typography.labelLarge.copy(fontFamily = font, fontWeight = if (extraBold) FontWeight.ExtraBold else Typography.labelLarge.fontWeight),
    labelMedium = Typography.labelMedium.copy(fontFamily = font, fontWeight = if (extraBold) FontWeight.ExtraBold else Typography.labelMedium.fontWeight),
    // 12 sp floor: nothing in the app is smaller (Material's default is 11 sp).
    labelSmall = Typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp, fontFamily = font, fontWeight = if (extraBold) FontWeight.ExtraBold else Typography.labelSmall.fontWeight),
)
