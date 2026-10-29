package com.kyrus.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Kyrus Manager lineage: cream + deep teal (not purple AI defaults).
private val Cream = Color(0xFFF5F1E8)
private val Ink = Color(0xFF1C2421)
private val Teal = Color(0xFF176C5B)
private val TealDim = Color(0xFF0F4A3E)
private val Slate = Color(0xFF366F9D)
private val Mist = Color(0xFFE8E2D6)
private val Night = Color(0xFF121816)
private val NightCard = Color(0xFF1A221F)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5EDE6),
    onPrimaryContainer = TealDim,
    secondary = Slate,
    onSecondary = Color.White,
    background = Cream,
    onBackground = Ink,
    surface = Cream,
    onSurface = Ink,
    surfaceVariant = Mist,
    onSurfaceVariant = Color(0xFF3D4743),
    outline = Color(0xFFB7B0A3),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5FCBB3),
    onPrimary = TealDim,
    primaryContainer = TealDim,
    onPrimaryContainer = Color(0xFFD5EDE6),
    secondary = Color(0xFF8BB4D9),
    onSecondary = Color(0xFF0D2A40),
    background = Night,
    onBackground = Color(0xFFE6EAE8),
    surface = Night,
    onSurface = Color(0xFFE6EAE8),
    surfaceVariant = NightCard,
    onSurfaceVariant = Color(0xFFB8C2BE),
    outline = Color(0xFF4A5551),
)

private val KyrusTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp),
)

@Composable
fun KyrusTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = KyrusTypography,
        content = content,
    )
}
