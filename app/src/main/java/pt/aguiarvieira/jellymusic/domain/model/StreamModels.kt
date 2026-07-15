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
 * ReplayGain / loudness-normalization playback preferences. When [enabled], the player applies each
 * track's Jellyfin LUFS normalization gain plus the manual [preampDb] offset; when off, audio plays
 * at its original level (bit-perfect). [preampDb] lets the user compensate globally (e.g. quieter or
 * louder target); it only takes effect while [enabled].
 */
data class ReplayGainSettings(
    val enabled: Boolean = true,
    val preampDb: Float = 0f,
) {
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
