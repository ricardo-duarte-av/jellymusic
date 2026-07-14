package pt.aguiarvieira.jellymusic.widget

import android.content.ComponentName
import android.content.Context
import androidx.glance.GlanceId
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import pt.aguiarvieira.jellymusic.playback.PlaybackService

/**
 * Connects a short-lived Media3 [MediaController] to the running [PlaybackService] session, runs
 * [block], then releases it. This routes every widget button through the exact same session as the
 * notification and Android Auto, so state (queue, shuffle, repeat, resume point) stays consistent.
 *
 * Building the controller starts the service if it isn't running; the session's
 * `onPlaybackResumption` then restores the saved queue, so pressing play from a cold widget works.
 *
 * After the command, the controller's optimistically-updated state is written straight to the
 * widget store and the widget is refreshed, so the button reflects the new state immediately —
 * without waiting for the service to echo the change back. A controller must be built and touched
 * on the player's application (main) thread, hence [Dispatchers.Main].
 */
@UnstableApi
private suspend fun withController(context: Context, block: (MediaController) -> Unit) {
    withContext(Dispatchers.Main.immediate) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controller = MediaController.Builder(context, token).buildAsync().await()
        try {
            block(controller)
            context.writeNowPlayingWidgetData(controller.nowPlayingWidgetData())
        } finally {
            controller.release()
        }
    }
    NowPlayingWidget().updateAll(context)
}

@UnstableApi
class TogglePlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context) { if (it.isPlaying) it.pause() else it.play() }
    }
}

@UnstableApi
class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context) { it.seekToNextMediaItem() }
    }
}

@UnstableApi
class PreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context) { it.seekToPreviousMediaItem() }
    }
}

@UnstableApi
class ToggleShuffleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context) { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }
}

@UnstableApi
class CycleRepeatAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context) {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }
}
