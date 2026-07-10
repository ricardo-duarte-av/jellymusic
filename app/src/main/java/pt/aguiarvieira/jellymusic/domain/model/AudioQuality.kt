package pt.aguiarvieira.jellymusic.domain.model

/**
 * User-selectable audio quality. Drives server-side transcoding via the Jellyfin universal audio
 * endpoint: [Original] direct-plays/remuxes with no cap; the capped values force a max bitrate and
 * target codec, and the server transcodes when the source exceeds them.
 *
 * The same model is reused for streaming and for downloads (Build Order step 4/5).
 */
enum class AudioQuality(
    val label: String,
    /** Max streaming bitrate in bits per second, or null for no cap (Original). */
    val maxBitrate: Int?,
) {
    ORIGINAL("Original", null),
    HIGH("High (320 kbps)", 320_000),
    MEDIUM("Medium (256 kbps)", 256_000),
    LOW("Low (192 kbps)", 192_000),
    DATA_SAVER("Data saver (128 kbps)", 128_000);

    val isTranscoded: Boolean get() = maxBitrate != null
}
