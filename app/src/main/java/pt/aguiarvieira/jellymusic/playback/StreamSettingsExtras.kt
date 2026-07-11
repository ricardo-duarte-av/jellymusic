package pt.aguiarvieira.jellymusic.playback

import android.os.Bundle
import pt.aguiarvieira.jellymusic.domain.model.AudioCodec
import pt.aguiarvieira.jellymusic.domain.model.StreamSettings

/**
 * Persists the [StreamSettings] a track was enqueued with inside its MediaItem metadata, so the UI
 * can report the *actual* stream method of the currently-playing track (which never changes
 * mid-track) rather than the live settings.
 */
object StreamSettingsExtras {
    private const val KEY_TRANSCODE = "jm_transcode"
    private const val KEY_CODEC = "jm_codec"
    private const val KEY_BITRATE = "jm_bitrate"

    fun toBundle(settings: StreamSettings): Bundle = Bundle().apply {
        putBoolean(KEY_TRANSCODE, settings.transcode)
        putString(KEY_CODEC, settings.codec.name)
        putInt(KEY_BITRATE, settings.maxBitrateKbps)
    }

    fun fromBundle(extras: Bundle?): StreamSettings {
        if (extras == null || !extras.containsKey(KEY_TRANSCODE)) return StreamSettings()
        return StreamSettings(
            transcode = extras.getBoolean(KEY_TRANSCODE),
            codec = extras.getString(KEY_CODEC)
                ?.let { runCatching { AudioCodec.valueOf(it) }.getOrNull() } ?: AudioCodec.OPUS,
            maxBitrateKbps = extras.getInt(KEY_BITRATE, 320),
        )
    }
}
