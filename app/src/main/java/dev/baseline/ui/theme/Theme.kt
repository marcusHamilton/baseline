package dev.baseline.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

private val Colors = darkColorScheme(
    primary = RecoveryGreen,
    onPrimary = Bg,
    background = Bg,
    onBackground = Color(0xFFE6E8EB),
    surface = Bg,
    onSurface = Color(0xFFE6E8EB),
    surfaceContainer = Card,
    surfaceContainerHigh = Card,
    secondaryContainer = Card,
    onSecondaryContainer = RecoveryGreen,
    error = RecoveryRed,
)

private val Base = Typography()

private val Type = Typography(
    displayLarge = Base.displayLarge.copy(fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold),
    displayMedium = Base.displayMedium.copy(fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold),
    displaySmall = Base.displaySmall.copy(fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold),
    headlineLarge = Base.headlineLarge.copy(fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold),
    headlineMedium = Base.headlineMedium.copy(fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold),
    headlineSmall = Base.headlineSmall.copy(fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontFamily = BarlowCondensed, fontWeight = FontWeight.Medium),
    titleMedium = Base.titleMedium.copy(fontFamily = BarlowCondensed, fontWeight = FontWeight.Medium),
    titleSmall = Base.titleSmall.copy(fontFamily = BarlowCondensed, fontWeight = FontWeight.Medium),
    bodyLarge = Base.bodyLarge.copy(fontFamily = Inter),
    bodyMedium = Base.bodyMedium.copy(fontFamily = Inter),
    bodySmall = Base.bodySmall.copy(fontFamily = Inter),
    labelLarge = Base.labelLarge.copy(fontFamily = Inter, fontWeight = FontWeight.Medium),
    labelMedium = Base.labelMedium.copy(fontFamily = Inter, fontWeight = FontWeight.Medium),
    labelSmall = Base.labelSmall.copy(fontFamily = Inter, fontWeight = FontWeight.Medium),
)

@Composable
fun BaselineTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Type, content = content)
}
