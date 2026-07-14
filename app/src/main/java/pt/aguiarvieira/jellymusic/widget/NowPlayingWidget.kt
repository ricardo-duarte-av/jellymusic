package pt.aguiarvieira.jellymusic.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
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
import androidx.glance.layout.fillMaxWidth
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
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import pt.aguiarvieira.jellymusic.MainActivity
import pt.aguiarvieira.jellymusic.R

/** Below this width the widget shows transport only; at or above it, shuffle + repeat are added. */
private val WIDE_BREAKPOINT = 260.dp

/** Bounded artwork size keeps the widget under the RemoteViews memory limit. */
private const val ARTWORK_PX = 512

@UnstableApi
class NowPlayingWidget : GlanceAppWidget() {

    // Two size buckets: Glance renders the largest that fits the current cell span. The wide one
    // crosses [WIDE_BREAKPOINT], unlocking the shuffle + repeat buttons.
    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(180.dp, 110.dp),
            DpSize(300.dp, 110.dp),
        ),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = context.readNowPlayingWidgetData()
        val artwork = data.artworkUri?.let { loadArtwork(context, it) }
        provideContent { WidgetBody(data, artwork) }
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
private fun WidgetBody(data: NowPlayingWidgetData, artwork: Bitmap?) {
    val context = LocalContext.current
    val wide = LocalSize.current.width >= WIDE_BREAKPOINT

    // Tapping the widget body opens the full-screen player, exactly like the media notification.
    val openPlayer = actionStartActivity(
        Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        },
    )

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(24.dp)
            .background(Color(0xFF1C1B1F))
            .clickable(openPlayer),
    ) {
        if (artwork != null) {
            Image(
                provider = ImageProvider(artwork),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier.fillMaxSize(),
            )
        }
        // Scrim so text/controls stay legible over any artwork.
        Box(modifier = GlanceModifier.fillMaxSize().background(Color(0x99000000))) {}

        Column(
            modifier = GlanceModifier.fillMaxSize().padding(16.dp),
            verticalAlignment = Alignment.Vertical.Bottom,
        ) {
            if (data.hasMedia) {
                Text(
                    text = data.title.ifEmpty { "Unknown title" },
                    style = TextStyle(color = ColorProvider(Color.White), fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                Text(
                    text = data.artist,
                    style = TextStyle(color = ColorProvider(Color(0xCCFFFFFF)), fontSize = 13.sp),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.height(10.dp))
                Controls(data, wide)
            } else {
                Text(
                    text = "Nothing playing",
                    style = TextStyle(color = ColorProvider(Color.White), fontSize = 15.sp, fontWeight = FontWeight.Bold),
                )
                Text(
                    text = "Tap to open JellyMusic",
                    style = TextStyle(color = ColorProvider(Color(0xCCFFFFFF)), fontSize = 13.sp),
                )
            }
        }
    }
}

@Composable
@UnstableApi
private fun Controls(data: NowPlayingWidgetData, wide: Boolean) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        if (wide) {
            IconButton(
                res = R.drawable.ic_widget_shuffle,
                onClick = actionRunCallback<ToggleShuffleAction>(),
                active = data.shuffleEnabled,
            )
            Spacer(GlanceModifier.width(6.dp))
        }
        IconButton(res = R.drawable.ic_widget_previous, onClick = actionRunCallback<PreviousAction>())
        Spacer(GlanceModifier.width(6.dp))
        IconButton(
            res = if (data.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            onClick = actionRunCallback<TogglePlayPauseAction>(),
            size = 48.dp,
        )
        Spacer(GlanceModifier.width(6.dp))
        IconButton(res = R.drawable.ic_widget_next, onClick = actionRunCallback<NextAction>())
        if (wide) {
            Spacer(GlanceModifier.width(6.dp))
            IconButton(
                res = if (data.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_widget_repeat_one else R.drawable.ic_widget_repeat,
                onClick = actionRunCallback<CycleRepeatAction>(),
                active = data.repeatMode != Player.REPEAT_MODE_OFF,
            )
        }
    }
}

@Composable
private fun IconButton(
    res: Int,
    onClick: Action,
    active: Boolean = true,
    size: Dp = 40.dp,
) {
    Image(
        provider = ImageProvider(res),
        contentDescription = null,
        colorFilter = ColorFilter.tint(ColorProvider(if (active) Color.White else Color(0x80FFFFFF))),
        modifier = GlanceModifier.size(size).padding(6.dp).clickable(onClick),
    )
}

@UnstableApi
class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}
