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

/** Audio details of the original file, from Jellyfin's media stream metadata. */
data class TrackAudioInfo(
    val codec: String?,
    val sampleRateHz: Int?,
    val bitDepth: Int?,
    val bitrateKbps: Int?,
    val channels: Int?,
)
