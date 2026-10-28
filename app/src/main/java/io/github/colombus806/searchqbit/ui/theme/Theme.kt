package io.github.colombus806.searchqbit.ui.theme

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

private val LightColors = lightColorScheme(
    primary = Color(0xFF00506E),
    primaryContainer = Color(0xFFC4E7FF),
    onPrimaryContainer = Color(0xFF001E2C),
    secondary = Color(0xFF486271),
    tertiary = Color(0xFF613A73),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF87CFF9),
    primaryContainer = Color(0xFF005C7D),
    onPrimaryContainer = Color(0xFFC4E7FF),
    secondary = Color(0xFFB0CADC),
    tertiary = Color(0xFFE6B5F8),
)

/** Wallpaper colours on Android 12+, a fixed palette before. */
@Composable
fun SearchQBitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
