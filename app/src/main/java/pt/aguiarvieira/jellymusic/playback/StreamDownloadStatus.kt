package pt.aguiarvieira.jellymusic.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import pt.aguiarvieira.jellymusic.domain.model.StreamSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How much of a streamed track is in the cache. [totalBytes] is null while a transcode is still
 * coming down (its size is only known at the end).
 */
data class StreamDownload(
    val bytes: Long,
    val totalBytes: Long?,
    val complete: Boolean,
) {
    /**
     * Downloaded share of the track, for the seek bar; null once complete. A transcode's size is
     * estimated from [durationMs] and its bitrate cap, and kept short of the end until it's known.
     */
    fun fraction(durationMs: Long, quality: StreamSettings): Float? {
        if (complete) return null
        totalBytes?.takeIf { it > 0 }?.let { return (bytes.toFloat() / it).coerceIn(0f, 1f) }
        val estimate = durationMs * quality.maxBitrateKbps / 8 // kbps × ms / 8 = bytes
        return if (estimate > 0) (bytes.toFloat() / estimate).coerceIn(0f, MAX_ESTIMATED_FRACTION) else 0f
    }

    private companion object {
        const val MAX_ESTIMATED_FRACTION = 0.95f
    }
}

/**
 * Download progress of the tracks the [StreamCache] is keeping, by track id, for the seek bars.
 * Same-process hand-off: the cache and the UI share this singleton.
 */
@Singleton
class StreamDownloadStatus @Inject constructor() {
    private val _downloads = MutableStateFlow<Map<String, StreamDownload>>(emptyMap())
    val downloads: StateFlow<Map<String, StreamDownload>> = _downloads.asStateFlow()

    fun publish(trackId: String, download: StreamDownload) {
        _downloads.update { if (it[trackId] == download) it else it + (trackId to download) }
    }

    /** Forgets tracks outside [trackIds]. */
    fun retain(trackIds: Collection<String>) {
        _downloads.update { current -> current.filterKeys { it in trackIds } }
    }
}
