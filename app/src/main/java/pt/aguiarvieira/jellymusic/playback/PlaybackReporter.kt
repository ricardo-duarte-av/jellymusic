package pt.aguiarvieira.jellymusic.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.operations.PlayStateApi
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import pt.aguiarvieira.jellymusic.data.jellyfin.JellyfinClientProvider
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TICKS_PER_MS = 10_000L

/**
 * Reports playback start/progress/stop to Jellyfin so the server tracks play counts, resume points
 * and "now playing". Best-effort: failures (e.g. offline) are swallowed.
 */
@Singleton
class PlaybackReporter @Inject constructor(
    private val clientProvider: JellyfinClientProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun reportStart(trackId: String, positionMs: Long, playMethod: PlayMethod) = report { api ->
        PlayStateApi(api).reportPlaybackStart(
            PlaybackStartInfo(
                itemId = trackId.toUuid(),
                positionTicks = positionMs.toTicks(),
                isPaused = false,
                isMuted = false,
                canSeek = true,
                playMethod = playMethod,
                repeatMode = RepeatMode.REPEAT_NONE,
                playbackOrder = PlaybackOrder.DEFAULT,
            ),
        )
    }

    fun reportProgress(trackId: String, positionMs: Long, isPaused: Boolean, playMethod: PlayMethod) =
        report { api ->
            PlayStateApi(api).reportPlaybackProgress(
                PlaybackProgressInfo(
                    itemId = trackId.toUuid(),
                    positionTicks = positionMs.toTicks(),
                    isPaused = isPaused,
                    isMuted = false,
                    canSeek = true,
                    playMethod = playMethod,
                    repeatMode = RepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT,
                ),
            )
        }

    fun reportStop(trackId: String, positionMs: Long) = report { api ->
        PlayStateApi(api).reportPlaybackStopped(
            PlaybackStopInfo(
                itemId = trackId.toUuid(),
                positionTicks = positionMs.toTicks(),
                failed = false,
            ),
        )
    }

    private fun report(block: suspend (ApiClient) -> Unit) {
        val api = clientProvider.api ?: return
        scope.launch { runCatching { block(api) } }
    }

    private fun String.toUuid(): UUID = UUID.fromString(this)
    private fun Long.toTicks(): Long = coerceAtLeast(0L) * TICKS_PER_MS
}
