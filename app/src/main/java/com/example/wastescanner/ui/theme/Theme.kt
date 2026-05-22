package com.example.wastescanner.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Przypisujemy nasze surowe kolory do ról w CIEMNYM motywie
private val DarkColorScheme = darkColorScheme(
    primary = GreenPrimary,
    secondary = GreenSecondary,
    tertiary = GreenTertiary,
    background = BackgroundDark,
    surface = SurfaceDark,
    onPrimary = TextLight, // Kolor tekstu na głównym przycisku
    onBackground = TextLight
)

// Przypisujemy nasze surowe kolory do ról w JASNYM motywie
private val LightColorScheme = lightColorScheme(
    primary = GreenPrimary,
    secondary = GreenSecondary,
    tertiary = GreenTertiary,
    background = BackgroundLight,
    surface = Color(0xFFFFFFFF),
    onPrimary = Color(0xFFFFD8A6),
    onBackground = TextDark
)

// To jest "Opakowanie", którego będziesz używać w MainActivity
@Composable
fun WasteScannerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true, // Pozwala na użycie kolorów z tapety na nowszych Androidach
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
        content = content
    )
}