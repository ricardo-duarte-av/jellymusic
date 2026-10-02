package pt.aguiarvieira.jellymusic.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.operations.PlayStateApi
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import pt.aguiarvieira.jellymusic.core.network.NetworkMonitor
import pt.aguiarvieira.jellymusic.data.jellyfin.JellyfinClientProvider
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TICKS_PER_MS = 10_000L
private const val REPORT_TIMEOUT_MS = 5_000L

/**
 * Longest a report waits in the batch on mobile data. Comfortably inside the time Jellyfin keeps an
 * idle "now playing" session around.
 */
private const val MAX_BATCH_AGE_MS = 4 * 60_000L

/**
 * Reports playback start/progress/stop to Jellyfin so the server tracks play counts, resume points
 * and "now playing". Best-effort: failures (e.g. offline) are swallowed.
 *
 * On Wi-Fi reports go out straight away. On mobile data they're **batched**: every request would
 * otherwise wake the radio on its own, and it then stays powered for several seconds. Queued reports
 * are sent together, in order, when the radio is up anyway ([flush], called when the streaming cache
 * starts a download), on pause, or after [MAX_BATCH_AGE_MS]. Consecutive progress reports for the
 * same track collapse into the latest one.
 */
@Singleton
class PlaybackReporter @Inject constructor(
    private val clientProvider: JellyfinClientProvider,
    private val networkMonitor: NetworkMonitor,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Pending(val progressOf: String?, val send: suspend (ApiClient) -> Unit)

    private val lock = Any()
    private val pending = ArrayList<Pending>()
    private var deadline: Job? = null

    // Sends run one batch at a time, so reports reach the server in the order they happened.
    private val sendMutex = Mutex()

    fun reportStart(trackId: String, positionMs: Long, playMethod: PlayMethod) = report(null) { api ->
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
        report(progressOf = trackId) { api ->
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

    fun reportStop(trackId: String, positionMs: Long) = report(null) { api ->
        PlayStateApi(api).reportPlaybackStopped(
            PlaybackStopInfo(
                itemId = trackId.toUuid(),
                positionTicks = positionMs.toTicks(),
                failed = false,
            ),
        )
    }

    /** Sends everything queued now. */
    fun flush() {
        val batch = synchronized(lock) {
            deadline?.cancel()
            deadline = null
            pending.toList().also { pending.clear() }
        }
        if (batch.isEmpty()) return
        val api = clientProvider.api ?: return
        scope.launch {
            sendMutex.withLock {
                // Bound each call: when playing a downloaded track offline (screen off, Wi-Fi asleep)
                // the POST can otherwise stall on the socket timeout. Best-effort — drop it early.
                batch.forEach { runCatching { withTimeoutOrNull(REPORT_TIMEOUT_MS) { it.send(api) } } }
            }
        }
    }

    private fun report(progressOf: String?, send: suspend (ApiClient) -> Unit) {
        if (clientProvider.api == null) return
        synchronized(lock) {
            val last = pending.lastOrNull()
            if (progressOf != null && last?.progressOf == progressOf) pending.removeAt(pending.lastIndex)
            pending += Pending(progressOf, send)
            if (deadline == null) {
                deadline = scope.launch {
                    delay(MAX_BATCH_AGE_MS)
                    flush()
                }
            }
        }
        if (!networkMonitor.isActiveNetworkMetered()) flush()
    }

    private fun String.toUuid(): UUID = UUID.fromString(this)
    private fun Long.toTicks(): Long = coerceAtLeast(0L) * TICKS_PER_MS
}
