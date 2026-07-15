package pt.aguiarvieira.jellymusic.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Whether album art should retint the album/player surfaces. Provided from the user setting. */
val LocalDynamicColorEnabled = staticCompositionLocalOf { true }

/**
 * Wraps [content] in an M3 color scheme derived from [artworkUrl]'s cover, when dynamic album color
 * is enabled and a seed can be extracted. Otherwise renders [content] under the ambient theme.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AlbumTheme(artworkUrl: String?, content: @Composable () -> Unit) {
    val seed = if (LocalDynamicColorEnabled.current) rememberAlbumSeed(artworkUrl) else null
    if (seed == null) {
        content()
        return
    }
    // TonalSpot (the stock Android dynamic-color style) keeps the accent hue close to the seed, so
    // the UI visibly matches the album art. Expressive was livelier but hue-rotated the accents away
    // from the cover's actual colours.
    val scheme = rememberDynamicColorScheme(
        seedColor = seed,
        isDark = isSystemInDarkTheme(),
        isAmoled = false,
        style = PaletteStyle.TonalSpot,
    )
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        typography = JellyMusicTypography,
        content = content,
    )
}

@Composable
private fun rememberAlbumSeed(artworkUrl: String?): Color? {
    val context = LocalContext.current
    var seed by remember(artworkUrl) { mutableStateOf<Color?>(null) }
    LaunchedEffect(artworkUrl) {
        seed = artworkUrl?.let { extractSeed(context, it) }
    }
    return seed
}

/** Loads the cover (software bitmap) and picks a representative seed color via Palette. */
private suspend fun extractSeed(context: Context, url: String): Color? =
    withContext(Dispatchers.Default) {
        val request = ImageRequest.Builder(context)
            .data(url)
            .allowHardware(false) // Palette needs to read pixels off a software bitmap.
            .build()
        val bitmap = (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
            ?: return@withContext null
        val palette = Palette.from(bitmap).generate()
        val rgb = palette.vibrantSwatch?.rgb
            ?: palette.dominantSwatch?.rgb
            ?: palette.mutedSwatch?.rgb
            ?: return@withContext null
        Color(rgb)
    }
