package com.airsink.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.airsink.R

/** Apple's system colors (iOS 17 values), light and dark. */
@Immutable
data class IosColors(
    val isDark: Boolean,
    val background: Color,          // systemGroupedBackground
    val card: Color,                // secondarySystemGroupedBackground
    val elevated: Color,            // tertiarySystemGroupedBackground
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    val fill: Color,                // systemFill (toggles off, sliders)
    val secondaryFill: Color,
    val material: Color,            // translucent bars
    val blue: Color,
    val green: Color,
    val orange: Color,
    val red: Color,
    val indigo: Color,
    val teal: Color,
    val pink: Color,
    val yellow: Color,
    val gray: Color,
)

val LightIos = IosColors(
    isDark = false,
    background = Color(0xFFF2F2F7),
    card = Color.White,
    elevated = Color(0xFFF2F2F7),
    label = Color.Black,
    secondaryLabel = Color(0x993C3C43),
    tertiaryLabel = Color(0x4D3C3C43),
    separator = Color(0x4A3C3C43),
    fill = Color(0x33787880),
    secondaryFill = Color(0x29787880),
    material = Color(0xCCF9F9F9),
    blue = Color(0xFF007AFF),
    green = Color(0xFF34C759),
    orange = Color(0xFFFF9500),
    red = Color(0xFFFF3B30),
    indigo = Color(0xFF5856D6),
    teal = Color(0xFF30B0C7),
    pink = Color(0xFFFF2D55),
    yellow = Color(0xFFFFCC00),
    gray = Color(0xFF8E8E93),
)

val DarkIos = IosColors(
    isDark = true,
    background = Color.Black,
    card = Color(0xFF1C1C1E),
    elevated = Color(0xFF2C2C2E),
    label = Color.White,
    secondaryLabel = Color(0x99EBEBF5),
    tertiaryLabel = Color(0x4DEBEBF5),
    separator = Color(0x99545458),
    fill = Color(0x5C787880),
    secondaryFill = Color(0x52787880),
    material = Color(0xB31C1C1E),
    blue = Color(0xFF0A84FF),
    green = Color(0xFF30D158),
    orange = Color(0xFFFF9F0A),
    red = Color(0xFFFF453A),
    indigo = Color(0xFF5E5CE6),
    teal = Color(0xFF40C8E0),
    pink = Color(0xFFFF375F),
    yellow = Color(0xFFFFD60A),
    gray = Color(0xFF8E8E93),
)

/** Inter is the closest open-licensed match to SF Pro. */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** iOS Dynamic Type sizes at the default ("Large") setting. */
@Immutable
data class IosType(
    val largeTitle: TextStyle = style(34, 41, FontWeight.Bold, -0.022f),
    val title1: TextStyle = style(28, 34, FontWeight.Bold, -0.021f),
    val title2: TextStyle = style(22, 28, FontWeight.Bold, -0.019f),
    val title3: TextStyle = style(20, 25, FontWeight.SemiBold, -0.017f),
    val headline: TextStyle = style(17, 22, FontWeight.SemiBold, -0.024f),
    val body: TextStyle = style(17, 22, FontWeight.Normal, -0.024f),
    val callout: TextStyle = style(16, 21, FontWeight.Normal, -0.02f),
    val subheadline: TextStyle = style(15, 20, FontWeight.Normal, -0.015f),
    val footnote: TextStyle = style(13, 18, FontWeight.Normal, -0.006f),
    val caption1: TextStyle = style(12, 16, FontWeight.Normal, 0f),
    val caption2: TextStyle = style(11, 13, FontWeight.Medium, 0.006f),
)

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Float) = TextStyle(
    fontFamily = Inter,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = tracking.em,
)

val LocalIosColors = staticCompositionLocalOf { LightIos }
val LocalIosType = staticCompositionLocalOf { IosType() }

object Ios {
    val colors: IosColors @Composable get() = LocalIosColors.current
    val type: IosType @Composable get() = LocalIosType.current
}

@Composable
fun AirSinkTheme(themePref: String = "system", content: @Composable () -> Unit) {
    val dark = when (themePref) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val colors = if (dark) DarkIos else LightIos
    val material = if (dark) {
        darkColorScheme(primary = colors.blue, background = colors.background, surface = colors.card)
    } else {
        lightColorScheme(primary = colors.blue, background = colors.background, surface = colors.card)
    }
    CompositionLocalProvider(LocalIosColors provides colors, LocalIosType provides IosType()) {
        MaterialTheme(colorScheme = material, content = content)
    }
}
