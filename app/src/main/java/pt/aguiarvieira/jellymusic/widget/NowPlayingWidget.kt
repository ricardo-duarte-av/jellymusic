package pt.aguiarvieira.jellymusic.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.palette.graphics.Palette
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import pt.aguiarvieira.jellymusic.MainActivity
import pt.aguiarvieira.jellymusic.R

/** Circle background + glyph tint for the app-icon badge, both pulled from the album scheme. */
private data class IconColors(val background: Color, val glyph: Color)

/** The two cover renders the widget needs: [sharp] for the compact background + corner thumbnail,
 *  [blurred] for the tall layout's frosted background. Both null when there's no artwork. */
private data class WidgetArtwork(val sharp: Bitmap? = null, val blurred: Bitmap? = null)

/** Below this width the widget shows transport only; at or above it, shuffle + repeat are added. */
private val WIDE_BREAKPOINT = 260.dp

/** Below this height the widget uses the single-row compact layout (e.g. a 3x1 / 4x1 cell). */
private val COMPACT_HEIGHT = 90.dp

/** Size of the tall-layout (4x2) corner album-art thumbnail. */
private val CORNER_THUMB = 120.dp

/** Bounded artwork size keeps the widget under the RemoteViews memory limit. */
private const val ARTWORK_PX = 512

@UnstableApi
class NowPlayingWidget : GlanceAppWidget() {

    // Size buckets: Glance renders the largest that fits the current cell span. Width crossing
    // [WIDE_BREAKPOINT] unlocks shuffle + repeat; height below [COMPACT_HEIGHT] switches to the
    // single-row compact layout so the widget works down to a 3x1 / 4x1 cell.
    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(180.dp, 110.dp),
            DpSize(300.dp, 110.dp),
            DpSize(180.dp, 50.dp),
            DpSize(300.dp, 50.dp),
        ),
    )

    // NOTE: read the store *inside* provideContent, not once here. Glance keeps the widget's
    // composition session alive; updateAll() recomposes that session rather than re-running
    // provideGlance, so a value read here would freeze at the session's first render. Collecting the
    // store as state means a write from PlaybackService recomposes the live widget.
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = context.readNowPlayingWidgetData()
        provideContent {
            val data by remember { context.nowPlayingWidgetDataFlow() }.collectAsState(initial)
            // Artwork is a suspend load; re-run it only when the cover actually changes. The tall
            // layout also needs a blurred copy for its frosted background, so build both here (once)
            // rather than blurring inside composition.
            val artwork by produceState(initialValue = WidgetArtwork(), key1 = data.artworkUri) {
                val sharp = data.artworkUri?.let { loadArtwork(context, it) }
                value = WidgetArtwork(sharp = sharp, blurred = sharp?.let(::blurArtwork))
            }
            WidgetBody(data, artwork.sharp, artwork.blurred, albumIconColors(artwork.sharp))
        }
    }

    /** Cheap frosted blur for the tall-widget background: collapse the cover to a tiny bitmap so
     * detail is destroyed, then upscale with bilinear filtering for a smooth wash of the album's
     * colours. API-agnostic (no RenderScript / RenderEffect), and the result stays small. */
    private fun blurArtwork(bitmap: Bitmap): Bitmap {
        val tiny = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        return Bitmap.createScaledBitmap(tiny, 256, 256, true)
    }

    /**
     * Colours for the app-icon badge, derived from the current cover the same way the app themes its
     * album/player surfaces: a Palette seed fed through MaterialKolor's TonalSpot scheme (matching the
     * in-app tint, so the icon reflects the album's real hue rather than a rotated one). We build a
     * dark scheme (the widget always sits on a dark scrim) and take the primaryContainer / on-pair so
     * the note stays legible on its disc. Falls back to a neutral disc + white note when there's no
     * art or no seed can be extracted.
     */
    private fun albumIconColors(bitmap: Bitmap?): IconColors {
        val fallback = IconColors(background = Color(0x33FFFFFF), glyph = Color.White)
        bitmap ?: return fallback
        val palette = Palette.from(bitmap).generate()
        val rgb = palette.vibrantSwatch?.rgb
            ?: palette.dominantSwatch?.rgb
            ?: palette.mutedSwatch?.rgb
            ?: return fallback
        val scheme = dynamicColorScheme(
            seedColor = Color(rgb),
            isDark = true,
            isAmoled = false,
            style = PaletteStyle.TonalSpot,
        )
        return IconColors(background = scheme.primaryContainer, glyph = scheme.onPrimaryContainer)
    }

    private suspend fun loadArtwork(context: Context, uri: String): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(uri)
            // RemoteViews needs a software bitmap; bound the size to stay within widget memory.
            .allowHardware(false)
            .size(ARTWORK_PX, ARTWORK_PX)
            .build()
        val result = context.imageLoader.execute(request)
        return (result as? SuccessResult)?.image?.toBitmap()
    }
}

@Composable
@UnstableApi
private fun WidgetBody(
    data: NowPlayingWidgetData,
    artwork: Bitmap?,
    blurredArtwork: Bitmap?,
    iconColors: IconColors,
) {
    val context = LocalContext.current
    val size = LocalSize.current
    val wide = size.width >= WIDE_BREAKPOINT
    val compact = size.height < COMPACT_HEIGHT
    // Compact (3x1 / 4x1) keeps the sharp cover as a darkened background; tall layouts (4x2+) use
    // the blurred cover instead and surface the sharp art as a corner thumbnail.
    val background = if (compact) artwork else (blurredArtwork ?: artwork)
    // Shuffle/repeat need a wide cell; in the single-row compact form the app icon takes that room
    // instead, so toggles only show in the tall wide layout. The icon identifies the widget and is
    // shown everywhere except the tightest 3x1 (compact + narrow).
    val showToggles = wide && !compact
    val showIcon = wide || !compact

    // Tapping the widget body opens the full-screen player, exactly like the media notification.
    val openPlayer = actionStartActivity(
        Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        },
    )

    // NOTE: Glance/RemoteViews does not reliably dispatch a clickable child's action when it is
    // nested under a clickable ancestor — you get the ripple but the child's PendingIntent is
    // swallowed. So no clickable here may be an ancestor of another: the outer Box is NOT
    // clickable; "open player" lives on the full-size scrim and the text leaves, and each transport
    // button is a sibling leaf with its own action.
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(if (compact) 16.dp else 24.dp)
            .background(Color(0xFF1C1B1F)),
    ) {
        if (background != null) {
            Image(
                provider = ImageProvider(background),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier.fillMaxSize(),
            )
        }
        // Scrim so text/controls stay legible over any artwork; also the "tap body → open player"
        // surface behind the content.
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(openPlayer),
        ) {}

        when {
            !data.hasMedia -> EmptyState(compact, openPlayer)
            compact -> CompactContent(data, showToggles, showIcon, iconColors, openPlayer)
            else -> FullContent(data, showToggles, openPlayer)
        }

        // Tall layouts pin the sharp cover thumbnail to the top-right, with the app icon badged on
        // top of it (the compact form places the icon inline at the end of the row instead — see
        // CompactContent).
        if (!compact) CornerArtwork(artwork, iconColors)
    }
}

/**
 * Top-right corner overlay for the tall (4x2) layout: the sharp, square album-art thumbnail (a crisp
 * focal point against the frosted background) with the album-coloured app-icon badge sitting on its
 * top-left corner, identifying the widget. The thumbnail is pinned to the corner with equal top and
 * right padding. Falls back to just the badge when there's no artwork.
 */
@Composable
private fun CornerArtwork(artwork: Bitmap?, iconColors: IconColors) {
    Box(
        modifier = GlanceModifier.fillMaxSize().padding(10.dp),
        contentAlignment = Alignment.TopEnd,
    ) {
        if (artwork == null) {
            // No cover: just the identifying badge in the corner.
            AppIcon(iconColors, 24.dp)
            return@Box
        }
        Box(
            modifier = GlanceModifier.size(CORNER_THUMB),
            contentAlignment = Alignment.TopStart,
        ) {
            Image(
                provider = ImageProvider(artwork),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier.size(CORNER_THUMB),
            )
            AppIcon(iconColors, 22.dp)
        }
    }
}

/** The launcher music-note glyph on a circular album-coloured disc: disc = background, note = glyph. */
@Composable
private fun AppIcon(iconColors: IconColors, size: Dp) {
    Box(
        modifier = GlanceModifier
            .size(size)
            .cornerRadius(size / 2)
            .background(iconColors.background),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_launcher_foreground),
            contentDescription = "JellyMusic",
            colorFilter = ColorFilter.tint(ColorProvider(iconColors.glyph)),
            modifier = GlanceModifier.size(size),
        )
    }
}

/** Tall layout: title/artist stacked at the bottom with the transport row beneath. */
@Composable
@UnstableApi
private fun FullContent(data: NowPlayingWidgetData, showToggles: Boolean, openPlayer: Action) {
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(16.dp),
        verticalAlignment = Alignment.Vertical.Bottom,
    ) {
        Text(
            text = data.title.ifEmpty { "Unknown title" },
            style = TextStyle(color = ColorProvider(Color.White), fontSize = 15.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
            modifier = GlanceModifier.clickable(openPlayer),
        )
        Text(
            text = data.artist,
            style = TextStyle(color = ColorProvider(Color(0xCCFFFFFF)), fontSize = 13.sp),
            maxLines = 1,
            modifier = GlanceModifier.clickable(openPlayer),
        )
        Spacer(GlanceModifier.height(10.dp))
        Controls(data, showToggles, compact = false)
    }
}

/** Single-row layout for short cells (3x1 / 4x1): text on the left, transport (+ icon) on the right. */
@Composable
@UnstableApi
private fun CompactContent(
    data: NowPlayingWidgetData,
    showToggles: Boolean,
    showIcon: Boolean,
    iconColors: IconColors,
    openPlayer: Action,
) {
    Row(
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Column(modifier = GlanceModifier.defaultWeight().clickable(openPlayer)) {
            Text(
                text = data.title.ifEmpty { "Unknown title" },
                style = TextStyle(color = ColorProvider(Color.White), fontSize = 13.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Text(
                text = data.artist,
                style = TextStyle(color = ColorProvider(Color(0xCCFFFFFF)), fontSize = 11.sp),
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.width(8.dp))
        Controls(data, showToggles, compact = true)
        // Single-row form has no top corner, so the icon goes inline at the end (only when wide
        // enough — a 4x1; a 3x1 omits it entirely).
        if (showIcon) {
            Spacer(GlanceModifier.width(8.dp))
            AppIcon(iconColors, 22.dp)
        }
    }
}

@Composable
private fun EmptyState(compact: Boolean, openPlayer: Action) {
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(16.dp).clickable(openPlayer),
        verticalAlignment = if (compact) Alignment.Vertical.CenterVertically else Alignment.Vertical.Bottom,
    ) {
        Text(
            text = "Nothing playing",
            style = TextStyle(color = ColorProvider(Color.White), fontSize = 15.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        if (!compact) {
            Text(
                text = "Tap to open JellyMusic",
                style = TextStyle(color = ColorProvider(Color(0xCCFFFFFF)), fontSize = 13.sp),
                maxLines = 1,
            )
        }
    }
}

@Composable
@UnstableApi
private fun Controls(data: NowPlayingWidgetData, showToggles: Boolean, compact: Boolean) {
    val iconSize = if (compact) 34.dp else 40.dp
    val playSize = if (compact) 40.dp else 48.dp
    val gap = if (compact) 2.dp else 6.dp
    Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
        if (showToggles) {
            IconButton(
                res = R.drawable.ic_widget_shuffle,
                onClick = actionRunCallback<ToggleShuffleAction>(),
                active = data.shuffleEnabled,
                highlightWhenActive = true,
                size = iconSize,
            )
            Spacer(GlanceModifier.width(gap))
        }
        IconButton(res = R.drawable.ic_widget_previous, onClick = actionRunCallback<PreviousAction>(), size = iconSize)
        Spacer(GlanceModifier.width(gap))
        IconButton(
            res = if (data.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            onClick = actionRunCallback<TogglePlayPauseAction>(),
            size = playSize,
        )
        Spacer(GlanceModifier.width(gap))
        IconButton(res = R.drawable.ic_widget_next, onClick = actionRunCallback<NextAction>(), size = iconSize)
        if (showToggles) {
            Spacer(GlanceModifier.width(gap))
            IconButton(
                res = if (data.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_widget_repeat_one else R.drawable.ic_widget_repeat,
                onClick = actionRunCallback<CycleRepeatAction>(),
                active = data.repeatMode != Player.REPEAT_MODE_OFF,
                highlightWhenActive = true,
                size = iconSize,
            )
        }
    }
}

@Composable
private fun IconButton(
    res: Int,
    onClick: Action,
    active: Boolean = true,
    highlightWhenActive: Boolean = false,
    size: Dp = 40.dp,
) {
    Box(
        modifier = GlanceModifier.size(size).clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Subtle translucent disc behind an engaged toggle (shuffle/repeat) so "on" reads clearly.
        if (highlightWhenActive && active) {
            Box(
                modifier = GlanceModifier
                    .size(size)
                    .background(ImageProvider(R.drawable.widget_highlight)),
                content = {},
            )
        }
        Image(
            provider = ImageProvider(res),
            contentDescription = null,
            colorFilter = ColorFilter.tint(ColorProvider(if (active) Color.White else Color(0x80FFFFFF))),
            modifier = GlanceModifier.size(size).padding(6.dp),
        )
    }
}

@UnstableApi
class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}
