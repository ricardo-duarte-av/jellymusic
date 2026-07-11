package pt.aguiarvieira.jellymusic.playback

import android.os.Bundle
import pt.aguiarvieira.jellymusic.domain.model.AudioCodec
import pt.aguiarvieira.jellymusic.domain.model.StreamSettings

/**
 * Persists, inside a track's MediaItem metadata, what the app is actually playing for that item: the
 * [StreamSettings] it resolved to (the download's real format when local, else the streaming
 * settings) and whether it plays from a local file. Lets the UI report the current track's true
 * source/quality, which never changes mid-track.
 */
object StreamSettingsExtras {
    private const val KEY_TRANSCODE = "jm_transcode"
    private const val KEY_CODEC = "jm_codec"
    private const val KEY_BITRATE = "jm_bitrate"
    private const val KEY_LOCAL = "jm_local"

    fun toBundle(settings: StreamSettings, isLocal: Boolean): Bundle = Bundle().apply {
        putBoolean(KEY_TRANSCODE, settings.transcode)
        putString(KEY_CODEC, settings.codec.name)
        putInt(KEY_BITRATE, settings.maxBitrateKbps)
        putBoolean(KEY_LOCAL, isLocal)
    }

    fun settingsFrom(extras: Bundle?): StreamSettings {
        if (extras == null || !extras.containsKey(KEY_TRANSCODE)) return StreamSettings()
        return StreamSettings(
            transcode = extras.getBoolean(KEY_TRANSCODE),
            codec = extras.getString(KEY_CODEC)
                ?.let { runCatching { AudioCodec.valueOf(it) }.getOrNull() } ?: AudioCodec.OPUS,
            maxBitrateKbps = extras.getInt(KEY_BITRATE, 320),
        )
    }

    fun isLocal(extras: Bundle?): Boolean = extras?.getBoolean(KEY_LOCAL, false) ?: false
}
