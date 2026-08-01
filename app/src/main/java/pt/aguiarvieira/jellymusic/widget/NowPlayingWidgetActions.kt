package pt.aguiarvieira.jellymusic.widget

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import pt.aguiarvieira.jellymusic.playback.PlaybackService

private const val TAG = "NowPlayingWidget"

/**
 * Connects a short-lived Media3 [MediaController] to the running [PlaybackService] session, runs
 * [block], then releases it. This routes every widget button through the exact same session as the
 * notification and Android Auto, so state (queue, shuffle, repeat, resume point) stays consistent.
 *
 * A controller must be built and touched on the player's application (main) thread, hence
 * [Dispatchers.Main]. We bind with the application context, not the [Context] passed to the
 * callback: that one is a BroadcastReceiver context, which is not allowed to bindService().
 */
// Catch-all is intentional: Glance silently swallows anything thrown from onAction, so we catch
// everything here purely to log it — otherwise a failure in the controller path is invisible.
@Suppress("TooGenericExceptionCaught")
@UnstableApi
private suspend fun withController(context: Context, action: String, block: (MediaController) -> Unit) {
    val appContext = context.applicationContext
    try {
        withContext(Dispatchers.Main.immediate) {
            val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
            Log.d(TAG, "[$action] connecting controller…")
            val controller = MediaController.Builder(appContext, token).buildAsync().await()
            Log.d(
                TAG,
                "[$action] connected: items=${controller.mediaItemCount} " +
                    "isPlaying=${controller.isPlaying} state=${controller.playbackState} " +
                    "cmdAvailable(PLAY_PAUSE)=${controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)}",
            )
            try {
                block(controller)
                Log.d(TAG, "[$action] after command: isPlaying=${controller.isPlaying} index=${controller.currentMediaItemIndex}")
                appContext.writeNowPlayingWidgetData(controller.nowPlayingWidgetData())
                // Give the command time to round-trip to the session before we disconnect;
                // releasing in the same breath can drop an in-flight transport command.
                delay(400)
            } finally {
                controller.release()
            }
        }
        NowPlayingWidget().updateAll(appContext)
    } catch (t: Throwable) {
        // Glance swallows exceptions thrown from onAction, so surface them ourselves.
        Log.e(TAG, "[$action] failed", t)
    }
}

@UnstableApi
class TogglePlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context, "play_pause") { if (it.isPlaying) it.pause() else it.play() }
    }
}

@UnstableApi
class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context, "next") { it.seekToNextMediaItem() }
    }
}

@UnstableApi
class PreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        // seekToPrevious(), not seekToPreviousMediaItem(): past the session's
        // maxSeekToPreviousPosition this restarts the current track instead of skipping back.
        withController(context, "previous") { it.seekToPrevious() }
    }
}

@UnstableApi
class ToggleShuffleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context, "shuffle") { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }
}

@UnstableApi
class CycleRepeatAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context, "repeat") {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }
}
