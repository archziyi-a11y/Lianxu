package com.example.lianxu.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = MarathonPrimaryNeon,
    secondary = MarathonSecondaryNeon,
    background = MarathonBackgroundDark,
    surface = MarathonSurfaceDark,
    onPrimary = MarathonBackgroundDark,
    onSecondary = MarathonBackgroundDark,
    onBackground = MarathonTextPrimaryDark,
    onSurface = MarathonTextPrimaryDark,
    error = MarathonError
)

private val LightColorScheme = lightColorScheme(
    primary = MarathonPrimaryNeon,
    secondary = MarathonSecondaryNeon,
    background = MarathonBackgroundLight,
    surface = MarathonSurfaceLight,
    onPrimary = MarathonBackgroundDark,
    onSecondary = MarathonBackgroundDark,
    onBackground = MarathonTextPrimaryLight,
    onSurface = MarathonTextPrimaryLight,
    error = MarathonError
)

@Composable
fun LianxuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // We disable dynamic color by default for Marathon style to keep the distinct high-contrast neon look.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
