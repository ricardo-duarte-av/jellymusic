package pt.aguiarvieira.jellymusic.ui.theme

import android.content.Context
import android.util.LruCache
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
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
@Composable
fun AlbumTheme(artworkUrl: String?, content: @Composable () -> Unit) {
    ArtworkTheme(rememberArtworkColorScheme(artworkUrl), content)
}

/**
 * Applies [scheme] to [content], or renders it under the ambient theme when [scheme] is null (no
 * seed, or the user turned album colour off). Split out of [AlbumTheme] so callers that need the
 * scheme's individual roles — e.g. a playlist tinting each track card from *that track's* cover —
 * can resolve it once with [rememberArtworkColorScheme] and then both read it and apply it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ArtworkTheme(scheme: ColorScheme?, content: @Composable () -> Unit) {
    if (scheme == null) {
        content()
        return
    }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        typography = JellyMusicTypography,
        content = content,
    )
}

/**
 * The M3 scheme seeded from [artworkUrl]'s cover, or null while it loads, when no seed qualifies, or
 * when the user disabled album colour.
 */
@Composable
fun rememberArtworkColorScheme(artworkUrl: String?): ColorScheme? {
    val seed = if (LocalDynamicColorEnabled.current) rememberAlbumSeed(artworkUrl) else null
    if (seed == null) return null
    // TonalSpot (the stock Android dynamic-color style) keeps the accent hue close to the seed, so
    // the UI visibly matches the album art. Expressive was livelier but hue-rotated the accents away
    // from the cover's actual colours.
    return rememberDynamicColorScheme(
        seedColor = seed,
        isDark = isSystemInDarkTheme(),
        isAmoled = false,
        style = PaletteStyle.TonalSpot,
    )
}

/**
 * Seeds are cached by URL because lists tint *per row*: a playlist scrolling through 200 tracks
 * would otherwise decode and quantize the same covers over and over as rows leave and re-enter
 * composition. [Color.Unspecified] is the "this cover has no seed" marker, so unscoreable covers are
 * not retried forever either.
 */
private val seedCache = LruCache<String, Color>(512)

@Composable
private fun rememberAlbumSeed(artworkUrl: String?): Color? {
    val context = LocalContext.current
    // Seed synchronously from the cache when we've already scored this cover, so a recycled row
    // paints tinted on its first frame instead of flashing neutral.
    var seed by remember(artworkUrl) { mutableStateOf(artworkUrl?.let(seedCache::get)) }
    LaunchedEffect(artworkUrl) {
        if (artworkUrl == null || seed != null) return@LaunchedEffect
        seed = extractSeed(context, artworkUrl)
    }
    return seed?.takeIf { it != Color.Unspecified }
}

/** Loads the cover (software bitmap) and picks its seed color via the shared [AlbumSeed] pipeline. */
private suspend fun extractSeed(context: Context, url: String): Color? =
    withContext(Dispatchers.Default) {
        val request = ImageRequest.Builder(context)
            .data(url)
            .allowHardware(false) // Seeding needs to read pixels off a software bitmap.
            .build()
        // A failed *load* (offline, 404) is not "no seed" — leave the cache untouched so the next
        // composition retries, rather than remembering the cover as colourless forever.
        val bitmap = (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
            ?: return@withContext null
        val seed = AlbumSeed.from(bitmap) ?: Color.Unspecified
        seedCache.put(url, seed)
        seed
    }
