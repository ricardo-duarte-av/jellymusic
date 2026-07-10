package pt.aguiarvieira.jellymusic.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Root theme for JellyMusic.
 *
 * Uses [MaterialExpressiveTheme] so every screen gets expressive motion, shapes and typography
 * defaults. Dynamic color (Material You) is used on API 31+, falling back to the expressive
 * palettes otherwise.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun JellyMusicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        // material3 1.5.0-alpha only ships an expressive *light* scheme so far; use the
        // standard dark scheme as the non-dynamic dark fallback.
        darkTheme -> darkColorScheme()
        else -> expressiveLightColorScheme()
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = JellyMusicTypography,
        content = content,
    )
}
