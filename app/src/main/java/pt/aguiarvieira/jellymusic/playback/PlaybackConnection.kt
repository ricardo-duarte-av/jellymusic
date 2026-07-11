package pt.aguiarvieira.jellymusic.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pt.aguiarvieira.jellymusic.data.download.MusicDownloadManager
import pt.aguiarvieira.jellymusic.data.jellyfin.StreamUrlBuilder
import pt.aguiarvieira.jellymusic.data.settings.SettingsStore
import pt.aguiarvieira.jellymusic.domain.model.StreamSettings
import pt.aguiarvieira.jellymusic.domain.model.Track
import javax.inject.Inject
import javax.inject.Singleton

enum class RepeatMode { OFF, ALL, ONE }

data class PlaybackUiState(
    val hasMedia: Boolean = false,
    val isPlaying: Boolean = false,
    val trackId: String? = null,
    val title: String = "",
    val artist: String = "",
    val artworkUri: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    /** The settings the current track was actually enqueued with (fixed for its lifetime). */
    val appliedStreamSettings: StreamSettings = StreamSettings(),
    /** True when the current track plays from a local downloaded file rather than streaming. */
    val isLocal: Boolean = false,
)

/**
 * App-process bridge to [PlaybackService] via a Media3 [MediaController]. Exposes player state as a
 * [StateFlow] for Compose and forwards transport controls. Playable items are built with
 * [MediaItemTree.trackMediaItem] so phone and Android Auto share identical media.
 */
@UnstableApi
@Singleton
class PlaybackConnection @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaItemTree: MediaItemTree,
    private val urlBuilder: StreamUrlBuilder,
    private val downloadManager: MusicDownloadManager,
    settingsStore: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private var controller: MediaController? = null

    @Volatile
    private var streamSettings: StreamSettings = StreamSettings()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = updateState()
    }

    init {
        scope.launch {
            var first = true
            settingsStore.streamSettings.collect { newSettings ->
                val changed = !first && newSettings != streamSettings
                streamSettings = newSettings
                first = false
                // A settings change should affect only upcoming tracks, never the current one.
                if (changed) rebuildUpcomingQueue()
            }
        }

        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                controller = future.get().apply { addListener(listener) }
                updateState()
            },
            ContextCompat.getMainExecutor(context),
        )

        // Position ticker so the seek bar advances during playback.
        scope.launch {
            while (isActive) {
                delay(500)
                if (controller?.isPlaying == true) updateState()
            }
        }
    }

    fun playTracks(tracks: List<Track>, startIndex: Int) {
        val c = controller ?: return
        val items = tracks.map { mediaItemTree.trackMediaItem(it, streamSettings) }
        if (items.isEmpty()) return
        c.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), 0L)
        c.prepare()
        c.play()
    }

    /** Rebuilds the stream URL of every queued track after the current one with the new settings. */
    private fun rebuildUpcomingQueue() {
        val c = controller ?: return
        val count = c.mediaItemCount
        val current = c.currentMediaItemIndex
        if (current < 0 || current >= count - 1) return
        val rebuilt = ((current + 1) until count).map { index ->
            val item = c.getMediaItemAt(index)
            val trackId = item.mediaId.removePrefix("track/")
            val localUri = downloadManager.localFileUri(trackId)
            val isLocal = localUri != null
            val playbackSettings = if (isLocal) downloadManager.localFormat(trackId) ?: streamSettings else streamSettings
            val metadata = item.mediaMetadata.buildUpon()
                .setExtras(StreamSettingsExtras.toBundle(playbackSettings, isLocal))
                .build()
            item.buildUpon()
                .setUri(localUri ?: urlBuilder.audioStreamUrl(trackId, streamSettings))
                .setMediaMetadata(metadata)
                .build()
        }
        c.replaceMediaItems(current + 1, count, rebuilt)
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        controller?.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    /** Cycles repeat: off → all → one → off. */
    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    private fun updateState() {
        val c = controller ?: return
        val metadata = c.mediaMetadata
        _state.value = PlaybackUiState(
            hasMedia = c.currentMediaItem != null,
            isPlaying = c.isPlaying,
            trackId = c.currentMediaItem?.mediaId?.removePrefix("track/"),
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString().orEmpty(),
            artworkUri = metadata.artworkUri?.toString(),
            positionMs = c.currentPosition.coerceAtLeast(0L),
            durationMs = c.duration.coerceAtLeast(0L),
            shuffleEnabled = c.shuffleModeEnabled,
            repeatMode = when (c.repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                else -> RepeatMode.OFF
            },
            appliedStreamSettings = StreamSettingsExtras.settingsFrom(c.currentMediaItem?.mediaMetadata?.extras),
            isLocal = StreamSettingsExtras.isLocal(c.currentMediaItem?.mediaMetadata?.extras),
        )
    }
}
