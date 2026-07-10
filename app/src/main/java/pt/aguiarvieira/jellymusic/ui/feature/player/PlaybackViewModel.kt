package pt.aguiarvieira.jellymusic.ui.feature.player

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import pt.aguiarvieira.jellymusic.domain.model.Track
import pt.aguiarvieira.jellymusic.playback.PlaybackConnection
import javax.inject.Inject

@HiltViewModel
class PlaybackViewModel @Inject constructor(
    private val connection: PlaybackConnection,
) : ViewModel() {

    val state = connection.state

    fun play(tracks: List<Track>, startIndex: Int) = connection.playTracks(tracks, startIndex)
    fun togglePlayPause() = connection.togglePlayPause()
    fun next() = connection.next()
    fun previous() = connection.previous()
    fun seekTo(positionMs: Long) = connection.seekTo(positionMs)
}
