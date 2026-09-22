package pt.aguiarvieira.jellymusic.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Which gain [GainAudioProcessor] actually applied to a track. */
enum class GainSource {
    /** Normalization is switched off. */
    OFF,

    /** The track's own gain. */
    TRACK,

    /** The track's album gain. */
    ALBUM,

    /** Normalization is on but the server has no gain for this track (only the pre-amp applies). */
    NONE,
}

/** The normalization applied to a track: [gainDb] is the server gain used (null for OFF/NONE). */
data class AppliedGain(
    val source: GainSource,
    val gainDb: Float?,
    val preampDb: Float,
)

/**
 * The gain applied to the *audible* track, published by [PlaybackService] for the now-playing screen.
 * Same-process hand-off: the service and the UI share this singleton.
 */
@Singleton
class ReplayGainStatus @Inject constructor() {
    private val _current = MutableStateFlow<AppliedGain?>(null)
    val current: StateFlow<AppliedGain?> = _current.asStateFlow()

    fun publish(applied: AppliedGain?) {
        _current.value = applied
    }
}
