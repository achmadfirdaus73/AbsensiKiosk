package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val CleanWhiteColorScheme = lightColorScheme(
    primary = PrimaryNavy,
    secondary = AccentGreen,
    tertiary = PrimaryBlue,
    background = LightBg,
    surface = PureWhite,
    onPrimary = PureWhite,
    onSecondary = PureWhite,
    onTertiary = PureWhite,
    onBackground = TextPrimaryLight,
    onSurface = TextPrimaryLight
)

@Composable
fun SmartPresensiKioskTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = CleanWhiteColorScheme,
        typography = Typography,
        content = content
    )
}
