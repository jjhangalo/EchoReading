package com.echoreading.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun EcoTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var themeMode by remember { mutableStateOf(prefs.getString("theme", "system") ?: "system") }
    var dynamicColors by remember { mutableStateOf(prefs.getBoolean("dynamic_colors", false)) }

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            when (key) {
                "theme" -> themeMode = sp.getString("theme", "system") ?: "system"
                "dynamic_colors" -> dynamicColors = sp.getBoolean("dynamic_colors", false)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val isDark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }

    val colorScheme = when {
        dynamicColors && isDark -> dynamicDarkColorScheme(context)
        dynamicColors && !isDark -> dynamicLightColorScheme(context)
        isDark -> StitchDarkColorScheme
        else -> StitchLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

val StitchLightColorScheme = lightColorScheme(
    primary = Color(0xFF3525CD),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF4F46E5),
    onPrimaryContainer = Color(0xFFDAD7FF),
    secondary = Color(0xFF712AE2),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF8A4CFC),
    onSecondaryContainer = Color(0xFFFFFBFF),
    tertiary = Color(0xFF00505F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF006A7C),
    onTertiaryContainer = Color(0xFF93E8FF),
    background = Color(0xFFFCF8FF),
    onBackground = Color(0xFF1A1A2A),
    surface = Color(0xFFFCF8FF),
    onSurface = Color(0xFF1A1A2A),
    surfaceVariant = Color(0xFFE3E0F7),
    onSurfaceVariant = Color(0xFF464555),
    surfaceContainer = Color(0xFFEFECFF),
    surfaceContainerLow = Color(0xFFF5F2FF),
    surfaceContainerHigh = Color(0xFFE8E6FC),
    surfaceContainerHighest = Color(0xFFE3E0F7),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    outline = Color(0xFF777587),
    outlineVariant = Color(0xFFC7C4D8),
)

val StitchDarkColorScheme = darkColorScheme(
    primary = Color(0xFFC3C0FF),
    onPrimary = Color(0xFF0F0069),
    primaryContainer = Color(0xFF4F46E5),
    onPrimaryContainer = Color(0xFFDAD7FF),
    secondary = Color(0xFFD2BBFF),
    onSecondary = Color(0xFF25005A),
    secondaryContainer = Color(0xFF712AE2),
    onSecondaryContainer = Color(0xFFFFFBFF),
    tertiary = Color(0xFF4CD7F6),
    onTertiary = Color(0xFF001F26),
    background = Color(0xFF141422),
    onBackground = Color(0xFFFCF8FF),
    surface = Color(0xFF1A1A2A),
    onSurface = Color(0xFFFCF8FF),
    surfaceVariant = Color(0xFF333348),
    onSurfaceVariant = Color(0xFFC7C4D8),
    surfaceContainer = Color(0xFF252538),
    surfaceContainerLow = Color(0xFF1F1F30),
    surfaceContainerHigh = Color(0xFF2C2C40),
    surfaceContainerHighest = Color(0xFF333348),
    surfaceContainerLowest = Color(0xFF141422),
    outline = Color(0xFF8E8B9E),
    outlineVariant = Color(0xFF464555),
)
