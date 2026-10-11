package com.darkcascade.spendable.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import com.darkcascade.spendable.R

object Neon {
    val Night = Color(0xFF0E0A1F)
    val Plum = Color(0xFF1C1338)
    val Card = Color(0xFF221A40)
    val Lime = Color(0xFFC6FF3D)
    val Cyan = Color(0xFF5FE6FF)
    val Pink = Color(0xFFFF5FD2)
    val Gold = Color(0xFFFFC94A)
    val Violet = Color(0xFF9B6BFF)
    val Amber = Color(0xFFFFA63D)
    val Muted = Color(0xFFA79FC7)
    val Poop = Color(0xFF7B4B2A)
    val PoopLight = Color(0xFFB07A4F)
}

private val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

/** Chunky display face for the title, the Go button and the money numbers. */
val Display = FontFamily(Font(GoogleFont("Bungee"), provider))

/** Body face. Falls back to the system font if the font provider is unavailable. */
val Body = FontFamily(
    Font(GoogleFont("Space Grotesk"), provider, FontWeight.Normal),
    Font(GoogleFont("Space Grotesk"), provider, FontWeight.Medium),
    Font(GoogleFont("Space Grotesk"), provider, FontWeight.Bold),
)

private val base = Typography()

private val typography = Typography(
    displayLarge = TextStyle(fontFamily = Display, fontSize = 44.sp, lineHeight = 48.sp),
    displayMedium = TextStyle(fontFamily = Display, fontSize = 34.sp, lineHeight = 40.sp),
    displaySmall = TextStyle(fontFamily = Display, fontSize = 26.sp, lineHeight = 32.sp),
    headlineMedium = TextStyle(fontFamily = Display, fontSize = 22.sp, lineHeight = 28.sp),
    headlineSmall = TextStyle(fontFamily = Display, fontSize = 18.sp, lineHeight = 24.sp),
    titleLarge = base.titleLarge.copy(fontFamily = Body, fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontFamily = Body, fontWeight = FontWeight.Bold),
    titleSmall = base.titleSmall.copy(fontFamily = Body, fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge.copy(fontFamily = Body),
    bodyMedium = base.bodyMedium.copy(fontFamily = Body),
    bodySmall = base.bodySmall.copy(fontFamily = Body),
    labelLarge = base.labelLarge.copy(fontFamily = Body, fontWeight = FontWeight.Bold),
    labelMedium = base.labelMedium.copy(fontFamily = Body, fontWeight = FontWeight.Medium),
    labelSmall = base.labelSmall.copy(fontFamily = Body),
)

private val colors = darkColorScheme(
    primary = Neon.Lime,
    onPrimary = Neon.Night,
    secondary = Neon.Cyan,
    onSecondary = Neon.Night,
    tertiary = Neon.Pink,
    background = Neon.Night,
    onBackground = Color.White,
    surface = Neon.Plum,
    onSurface = Color.White,
    surfaceVariant = Neon.Card,
    onSurfaceVariant = Neon.Muted,
    surfaceContainer = Neon.Plum,
    surfaceContainerHigh = Neon.Card,
    surfaceContainerLow = Neon.Plum,
    outline = Color(0xFF4A3F75),
)

@Composable
fun SpendableTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
