package pt.aguiarvieira.jellymusic.ui.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pt.aguiarvieira.jellymusic.domain.model.Lyrics
import pt.aguiarvieira.jellymusic.domain.model.StreamSettings
import pt.aguiarvieira.jellymusic.domain.model.Track
import pt.aguiarvieira.jellymusic.domain.model.TrackAudioInfo
import pt.aguiarvieira.jellymusic.data.download.FavoriteDownloadSyncManager
import pt.aguiarvieira.jellymusic.data.settings.SettingsStore
import pt.aguiarvieira.jellymusic.domain.repository.MusicRepository
import pt.aguiarvieira.jellymusic.playback.PlaybackConnection
import javax.inject.Inject

/** Lyrics availability for the current track: still fetching, none published, or here they are. */
sealed interface LyricsState {
    data object Loading : LyricsState
    data object None : LyricsState
    data class Available(val lyrics: Lyrics) : LyricsState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlaybackViewModel @Inject constructor(
    private val connection: PlaybackConnection,
    private val musicRepository: MusicRepository,
    private val favoriteSyncManager: FavoriteDownloadSyncManager,
    settingsStore: SettingsStore,
) : ViewModel() {

    val state = connection.state
    val progress = connection.progress
    val queue = connection.queue

    // Favourite state of the currently-playing track. Re-fetched whenever the track changes and
    // flipped optimistically on toggle.
    private val _isFavorite = MutableStateFlow(false)
    val isFavorite: StateFlow<Boolean> = _isFavorite.asStateFlow()

    init {
        viewModelScope.launch {
            connection.state.map { it.trackId }.distinctUntilChanged().collectLatest { trackId ->
                _isFavorite.value = trackId?.let { musicRepository.getFavorite(it).getOrDefault(false) } ?: false
            }
        }
    }

    fun toggleFavorite() {
        val trackId = state.value.trackId ?: return
        val target = !_isFavorite.value
        _isFavorite.value = target
        viewModelScope.launch {
            musicRepository.setFavorite(trackId, target)
                .onSuccess { favoriteSyncManager.requestSync() }
                .onFailure { _isFavorite.value = !target }
        }
    }

    /**
     * Human-readable quality of the current track: the original file's codec/rate/depth/bitrate, or
     * "original → transcoded" when it is being transcoded. Keyed off the settings the track was
     * *actually* enqueued with (from playback state), not the live settings — flipping transcode in
     * Settings only affects the next track, so the label must not change for the current one.
     */
    val qualityLabel: StateFlow<String?> =
        connection.state
            .map { Triple(it.trackId, it.appliedStreamSettings, it.isLocal) }
            .distinctUntilChanged()
            .mapLatest { (trackId, settings, isLocal) ->
                if (trackId == null) {
                    null
                } else {
                    // A downloaded transcoded file already IS the transcoded format, so the server's
                    // original details are irrelevant; skip the (possibly offline) fetch.
                    val info = if (isLocal && settings.transcode) {
                        null
                    } else {
                        musicRepository.getTrackAudioInfo(trackId).getOrNull()
                    }
                    buildQualityLabel(info, settings, isLocal)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Lyrics for the current track, refetched whenever the track changes. Kept out of
     * [PlaybackUiState] because it is a UI-only concern the playback service knows nothing about.
     */
    val lyrics: StateFlow<LyricsState> =
        combine(
            connection.state.map { it.trackId }.distinctUntilChanged(),
            settingsStore.lyricsEnabled,
        ) { trackId, enabled -> trackId.takeIf { enabled } }
            .distinctUntilChanged()
            .mapLatest { trackId ->
                // Null covers both "nothing playing" and "lyrics switched off" — no fetch either way.
                if (trackId == null) {
                    LyricsState.None
                } else {
                    musicRepository.getLyrics(trackId).getOrNull()
                        ?.let { LyricsState.Available(it) }
                        ?: LyricsState.None
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LyricsState.Loading)

    fun play(tracks: List<Track>, startIndex: Int) = connection.playTracks(tracks, startIndex)
    fun playShuffled(tracks: List<Track>) = connection.playTracksShuffled(tracks)
    fun togglePlayPause() = connection.togglePlayPause()
    fun stop() = connection.stop()
    fun next() = connection.next()
    fun previous() = connection.previous()
    fun seekTo(positionMs: Long) = connection.seekTo(positionMs)
    fun toggleShuffle() = connection.toggleShuffle()
    fun cycleRepeat() = connection.cycleRepeat()
    fun playIndex(index: Int) = connection.playIndex(index)
    fun removeFromQueue(index: Int) = connection.removeFromQueue(index)

    private fun buildQualityLabel(info: TrackAudioInfo?, settings: StreamSettings, isLocal: Boolean): String? {
        val original = formatOriginal(info)
        val transcoded = "${settings.codec.label} ${settings.maxBitrateKbps} kbps"
        return when {
            // Downloaded transcoded file: you're hearing the transcoded format itself.
            settings.transcode && isLocal -> transcoded
            // Streaming with transcode: original file, transcoded on the fly.
            settings.transcode && original != null -> "$original  →  $transcoded"
            settings.transcode -> "→  $transcoded"
            // Original (streamed or downloaded original).
            else -> original
        }
    }

    private fun formatOriginal(info: TrackAudioInfo?): String? {
        if (info == null) return null
        val parts = buildList {
            info.codec?.let { add(it.uppercase()) }
            info.sampleRateHz?.let { add("%.1f kHz".format(it / 1000.0)) }
            info.bitDepth?.let { add("$it-bit") }
            info.bitrateKbps?.let { add("$it kbps") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}
