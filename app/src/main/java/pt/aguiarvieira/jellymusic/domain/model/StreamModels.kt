package pt.aguiarvieira.jellymusic.domain.model

/** Target codec when streaming transcoding is enabled. */
enum class AudioCodec(val label: String, val jellyfinCodec: String, val container: String) {
    OPUS("Opus", "opus", "opus"),
    AAC("AAC", "aac", "aac"),
    MP3("MP3", "mp3", "mp3"),
}

/**
 * Streaming transcode preferences. When [transcode] is off the server direct-plays the original
 * file; when on it transcodes to [codec] capped at [maxBitrateKbps].
 */
data class StreamSettings(
    val transcode: Boolean = false,
    val codec: AudioCodec = AudioCodec.OPUS,
    val maxBitrateKbps: Int = 320,
)

val STREAM_BITRATE_OPTIONS = listOf(320, 256, 192, 128, 96)

/**
 * Which of Jellyfin's LUFS normalization gains to apply. Jellyfin stores the same `NormalizationGain`
 * field on each audio item (track gain) and on each album (album gain).
 */
enum class ReplayGainMode {
    /** No normalization: audio plays at its original level (bit-perfect). */
    OFF,

    /** Every track levelled on its own — even loudness across everything. */
    TRACK,

    /** Every track takes its album's gain, preserving the loudness differences within an album. */
    ALBUM,

    /**
     * Album gain while an album is played in order (shuffle off, and a neighbouring track in the queue
     * is from the same album); track gain for shuffle and mixed playlists.
     */
    AUTO,
}

/**
 * ReplayGain / loudness-normalization playback preferences. Unless [mode] is [ReplayGainMode.OFF], the
 * player applies the chosen Jellyfin normalization gain (falling back to the track's own gain when the
 * album has none) plus the manual [preampDb] offset. [preampDb] lets the user compensate globally
 * (e.g. quieter or louder target); it only takes effect while normalization is on.
 */
data class ReplayGainSettings(
    val mode: ReplayGainMode = ReplayGainMode.TRACK,
    val preampDb: Float = 0f,
) {
    val enabled: Boolean get() = mode != ReplayGainMode.OFF

    companion object {
        const val PREAMP_MIN_DB = -12f
        const val PREAMP_MAX_DB = 12f
    }
}

/** Audio details of the original file, from Jellyfin's media stream metadata. */
data class TrackAudioInfo(
    val codec: String?,
    val sampleRateHz: Int?,
    val bitDepth: Int?,
    val bitrateKbps: Int?,
    val channels: Int?,
)
