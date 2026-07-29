package pt.aguiarvieira.jellymusic.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.delay
import pt.aguiarvieira.jellymusic.MainActivity
import pt.aguiarvieira.jellymusic.R
import pt.aguiarvieira.jellymusic.ui.theme.AlbumSeed

/** Circle background + glyph tint for the app-icon badge, both pulled from the album scheme. */
private data class IconColors(val background: Color, val glyph: Color)

/** Neutral disc + white note, used when there's no art or no seed can be extracted. */
private val FALLBACK_ICON_COLORS = IconColors(background = Color(0x33FFFFFF), glyph = Color.White)

/** Everything the widget renders for one cover: [sharp] for the compact background + corner
 *  thumbnail, [blurred] for the tall layout's frosted background, and the [iconColors] derived from
 *  it. During a track change these hold pre-blended crossfade frames. Nulls when there's no artwork. */
private data class WidgetArtwork(
    val sharp: Bitmap? = null,
    val blurred: Bitmap? = null,
    val iconColors: IconColors = FALLBACK_ICON_COLORS,
)

/** Cover crossfade on track change: this many frames, this far apart (~270ms total). Glance has no
 *  animation API, so we push pre-blended frames instead. */
private const val FADE_STEPS = 6
private const val FADE_STEP_MS = 45L

/** Last cover the widget settled on, kept module-level so the next track's [produceState] — which
 *  relaunches when the artwork URI changes — can crossfade from it. */
@Volatile private var lastSettledArtwork = WidgetArtwork()

/** Below this width the widget shows transport only; at or above it, shuffle + repeat are added. */
private val WIDE_BREAKPOINT = 260.dp

/** Below this height the widget uses the single-row compact layout (e.g. a 3x1 / 4x1 cell). */
private val COMPACT_HEIGHT = 90.dp

/** Size of the tall-layout (4x2) corner album-art thumbnail. */
private val CORNER_THUMB = 120.dp

/** Bounded artwork size keeps the widget under the RemoteViews memory limit. */
private const val ARTWORK_PX = 512

/** Frosted-background blur: working resolution + box-blur radius/pass count (see [NowPlayingWidget.blurArtwork]). */
private const val BLUR_WORK_PX = 128
private const val BLUR_RADIUS = 8
private const val BLUR_PASSES = 3

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
            // Artwork is a suspend load; re-run it only when the cover actually changes. We build the
            // sharp cover, its blurred copy (tall background) and the derived icon colours here — once
            // per track — then crossfade from the previously shown cover to the new one by emitting a
            // short run of pre-blended frames (Glance has no animation API).
            val artwork by produceState(initialValue = lastSettledArtwork, key1 = data.artworkUri) {
                val sharp = data.artworkUri?.let { loadArtwork(context, it) }
                val target = WidgetArtwork(
                    sharp = sharp,
                    blurred = sharp?.let(::blurArtwork),
                    iconColors = albumIconColors(sharp),
                )
                val from = lastSettledArtwork
                if (sharp != null && target.blurred != null) {
                    for (i in 1 until FADE_STEPS) {
                        val t = i / FADE_STEPS.toFloat()
                        value = target.copy(
                            sharp = crossfade(from.sharp, sharp, t),
                            blurred = crossfade(from.blurred, target.blurred, t),
                        )
                        delay(FADE_STEP_MS)
                    }
                }
                value = target
                lastSettledArtwork = target
            }
            WidgetBody(data, artwork.sharp, artwork.blurred, artwork.iconColors)
        }
    }

    /** Frosted blur for the tall-widget background. Downscale to a small working size (cheap, and
     * already drops fine detail), then run a few separable box-blur passes — three boxes approximate
     * a Gaussian — for a smooth wash of the album's colours without the blocky low-frequency banding
     * a bilinear-upscaled 24px thumbnail produced. API-agnostic (no RenderScript / RenderEffect, so
     * it works on API 29+), and the result stays small. The Image view upscales the wash to fill. */
    private fun blurArtwork(bitmap: Bitmap): Bitmap {
        val work = Bitmap.createScaledBitmap(bitmap, BLUR_WORK_PX, BLUR_WORK_PX, true)
        val out = if (work.isMutable) work else work.copy(Bitmap.Config.ARGB_8888, true)
        boxBlur(out, radius = BLUR_RADIUS, passes = BLUR_PASSES)
        return out
    }

    /** In-place separable box blur: [passes] horizontal+vertical sweeps over the pixel buffer, each
     *  averaging a (2·[radius]+1)-wide window via a running sum so cost is independent of radius. */
    private fun boxBlur(bitmap: Bitmap, radius: Int, passes: Int) {
        val w = bitmap.width
        val h = bitmap.height
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        bitmap.getPixels(a, 0, w, 0, 0, w, h)
        repeat(passes) {
            boxBlurPass(a, b, w, h, radius, horizontal = true)
            boxBlurPass(b, a, h, w, radius, horizontal = false)
        }
        bitmap.setPixels(a, 0, w, 0, 0, w, h)
    }

    /**
     * One box-blur sweep, reading [src] and writing [dst]. For [horizontal] sweeps [len] is the row
     * width and [lines] the row count; for vertical sweeps they swap (columns walked with a stride),
     * which lets the same running-sum loop serve both directions. Edge samples are clamped.
     */
    private fun boxBlurPass(src: IntArray, dst: IntArray, len: Int, lines: Int, r: Int, horizontal: Boolean) {
        val div = r * 2 + 1
        for (line in 0 until lines) {
            // Horizontal: element i sits at line*len + i (stride 1). Vertical: at i*len + line
            // (stride len). Fold that into a base + step so one loop handles both.
            val base = if (horizontal) line * len else line
            val step = if (horizontal) 1 else len
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val c = src[base + i.coerceIn(0, len - 1) * step]
                sa += (c ushr 24) and 0xff; sr += (c ushr 16) and 0xff
                sg += (c ushr 8) and 0xff; sb += c and 0xff
            }
            for (i in 0 until len) {
                dst[base + i * step] =
                    ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = src[base + (i + r + 1).coerceIn(0, len - 1) * step]
                val rem = src[base + (i - r).coerceIn(0, len - 1) * step]
                sa += ((add ushr 24) and 0xff) - ((rem ushr 24) and 0xff)
                sr += ((add ushr 16) and 0xff) - ((rem ushr 16) and 0xff)
                sg += ((add ushr 8) and 0xff) - ((rem ushr 8) and 0xff)
                sb += (add and 0xff) - (rem and 0xff)
            }
        }
    }

    /**
     * Colours for the app-icon badge, derived from the current cover through the shared [AlbumSeed]
     * pipeline and MaterialKolor's TonalSpot scheme — the same seed the now-playing screen, the shade
     * notification and the Android Auto player use, so the badge can't drift to a different hue than
     * the rest of the app.
     *
     * The scheme is always built dark, unlike [AlbumTheme] which follows the system: the badge sits on
     * the widget's own dark scrim over the artwork (see the `0x99000000` background below), not on the
     * system surface, so a light readout here would be illegible regardless of the device theme.
     * Falls back to a neutral disc + white note when there's no art or no seed can be extracted.
     */
    private fun albumIconColors(bitmap: Bitmap?): IconColors {
        bitmap ?: return FALLBACK_ICON_COLORS
        val seed = AlbumSeed.from(bitmap) ?: return FALLBACK_ICON_COLORS
        val scheme = dynamicColorScheme(
            seedColor = seed,
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

    /**
     * One crossfade frame at progress [t] (0→1): the new cover [to] drawn opaque, with the previous
     * cover [from] fading out on top of it. With no previous cover, the new one fades in from the
     * scrim instead. [from] is rescaled if its dimensions differ (non-square covers).
     */
    private fun crossfade(from: Bitmap?, to: Bitmap, t: Float): Bitmap {
        if (from == null) return withAlpha(to, t)
        val out = Bitmap.createBitmap(to.width, to.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(to, 0f, 0f, null)
        val scaled = if (from.width == to.width && from.height == to.height) {
            from
        } else {
            Bitmap.createScaledBitmap(from, to.width, to.height, true)
        }
        canvas.drawBitmap(scaled, 0f, 0f, Paint().apply { alpha = alpha255(1f - t) })
        return out
    }

    /** [bmp] drawn at alpha [t] over transparency — a fade-in over whatever sits behind the widget. */
    private fun withAlpha(bmp: Bitmap, t: Float): Bitmap {
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bmp, 0f, 0f, Paint().apply { alpha = alpha255(t) })
        return out
    }

    private fun alpha255(f: Float): Int = (f * 255f).toInt().coerceIn(0, 255)
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
            else -> FullContent(data, showToggles, iconColors, openPlayer)
        }

        // Tall layouts pin the sharp cover thumbnail to the top-right, with the app icon badged on
        // top of it (the compact form places the icon inline at the end of the row instead — see
        // CompactContent).
        if (!compact) CornerArtwork(artwork, iconColors)
    }
}

/**
 * Top-corner overlays for the tall (4x2) layout: the album-coloured app-icon badge in the widget's
 * top-left corner (identifying the widget), and the sharp, square album-art thumbnail pinned to the
 * top-right corner (a crisp focal point against the frosted background). The thumbnail is omitted
 * when there's no artwork; the icon always shows.
 */
@Composable
private fun CornerArtwork(artwork: Bitmap?, iconColors: IconColors) {
    // App icon in the widget's top-left corner.
    Box(
        modifier = GlanceModifier.fillMaxSize().padding(10.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        AppIcon(iconColors, 44.dp)
    }
    // Album-art thumbnail in the widget's top-right corner.
    if (artwork != null) {
        Box(
            modifier = GlanceModifier.fillMaxSize().padding(10.dp),
            contentAlignment = Alignment.TopEnd,
        ) {
            Image(
                provider = ImageProvider(artwork),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier.size(CORNER_THUMB).cornerRadius(12.dp),
            )
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
private fun FullContent(data: NowPlayingWidgetData, showToggles: Boolean, iconColors: IconColors, openPlayer: Action) {
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
        Controls(data, showToggles, iconColors, compact = false)
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
        Controls(data, showToggles, iconColors, compact = true)
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
private fun Controls(data: NowPlayingWidgetData, showToggles: Boolean, iconColors: IconColors, compact: Boolean) {
    val iconSize = if (compact) 34.dp else 40.dp
    val playSize = if (compact) 40.dp else 48.dp
    val gap = if (compact) 2.dp else 6.dp
    Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
        if (showToggles) {
            IconButton(
                res = R.drawable.ic_widget_shuffle,
                onClick = actionRunCallback<ToggleShuffleAction>(),
                active = data.shuffleEnabled,
                activeColor = iconColors.background,
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
                activeColor = iconColors.background,
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
    activeColor: Color? = null,
    size: Dp = 40.dp,
) {
    // Toggles (shuffle/repeat) pass an [activeColor] — the album's M3 accent, matching the app-icon
    // disc — and tint the glyph with it when engaged; off reads as dimmed white. Transport buttons
    // pass no colour and stay plain white. (An engaged toggle used to sit on a translucent disc.)
    val tint = when {
        active && activeColor != null -> activeColor
        active -> Color.White
        else -> Color(0x80FFFFFF)
    }
    Box(
        modifier = GlanceModifier.size(size).clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(res),
            contentDescription = null,
            colorFilter = ColorFilter.tint(ColorProvider(tint)),
            modifier = GlanceModifier.size(size).padding(6.dp),
        )
    }
}

@UnstableApi
class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}
