package pt.aguiarvieira.jellymusic.ui.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import pt.aguiarvieira.jellymusic.data.settings.SettingsStore
import pt.aguiarvieira.jellymusic.domain.model.StreamSettings
import pt.aguiarvieira.jellymusic.domain.model.Track
import pt.aguiarvieira.jellymusic.domain.model.TrackAudioInfo
import pt.aguiarvieira.jellymusic.domain.repository.MusicRepository
import pt.aguiarvieira.jellymusic.playback.PlaybackConnection
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlaybackViewModel @Inject constructor(
    private val connection: PlaybackConnection,
    private val musicRepository: MusicRepository,
    settingsStore: SettingsStore,
) : ViewModel() {

    val state = connection.state

    /**
     * Human-readable quality of the current track: the original file's codec/rate/depth/bitrate, or
     * "original → transcoded" when streaming transcode is on.
     */
    val qualityLabel: StateFlow<String?> = combine(
        connection.state.map { it.trackId }.distinctUntilChanged(),
        settingsStore.streamSettings,
    ) { trackId, settings -> trackId to settings }
        .mapLatest { (trackId, settings) ->
            if (trackId == null) {
                null
            } else {
                val info = musicRepository.getTrackAudioInfo(trackId).getOrNull()
                buildQualityLabel(info, settings)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun play(tracks: List<Track>, startIndex: Int) = connection.playTracks(tracks, startIndex)
    fun togglePlayPause() = connection.togglePlayPause()
    fun next() = connection.next()
    fun previous() = connection.previous()
    fun seekTo(positionMs: Long) = connection.seekTo(positionMs)
    fun toggleShuffle() = connection.toggleShuffle()
    fun cycleRepeat() = connection.cycleRepeat()

    private fun buildQualityLabel(info: TrackAudioInfo?, settings: StreamSettings): String? {
        val original = formatOriginal(info)
        val transcoded = "${settings.codec.label} ${settings.maxBitrateKbps} kbps"
        return when {
            settings.transcode && original != null -> "$original  →  $transcoded"
            settings.transcode -> "→  $transcoded"
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
