package com.faygath.waku.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val WakuColorScheme = darkColorScheme(
    primary = WakuPrimary,
    secondary = WakuGold,
    tertiary = WakuPink,
    background = WakuBg,
    surface = WakuSurface,
    onPrimary = WakuText,
    onSecondary = WakuBg,
    onTertiary = WakuBg,
    onBackground = WakuText,
    onSurface = WakuText
)

@Composable
fun WakuTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = WakuColorScheme,
        typography = Typography,
        content = content
    )
}