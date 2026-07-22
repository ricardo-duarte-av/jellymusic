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
    private const val KEY_GAIN_DB = "jm_gain_db"
    private const val KEY_ALBUM_ID = "jm_album_id"
    private const val KEY_ARTIST_ID = "jm_artist_id"

    fun toBundle(
        settings: StreamSettings,
        isLocal: Boolean,
        gainDb: Float? = null,
        albumId: String? = null,
        artistId: String? = null,
    ): Bundle = Bundle().apply {
        putBoolean(KEY_TRANSCODE, settings.transcode)
        putString(KEY_CODEC, settings.codec.name)
        putInt(KEY_BITRATE, settings.maxBitrateKbps)
        putBoolean(KEY_LOCAL, isLocal)
        // Jellyfin's LUFS normalization gain (dB) for this track, applied by GainAudioProcessor.
        if (gainDb != null) putFloat(KEY_GAIN_DB, gainDb)
        // Ids so the now-playing screen can navigate to this track's album / artist.
        if (albumId != null) putString(KEY_ALBUM_ID, albumId)
        if (artistId != null) putString(KEY_ARTIST_ID, artistId)
    }

    /** The track's normalization gain in dB, or null when the server hasn't scanned it. */
    fun gainDbFrom(extras: Bundle?): Float? =
        if (extras?.containsKey(KEY_GAIN_DB) == true) extras.getFloat(KEY_GAIN_DB) else null

    /** The track's album id, for navigating to the album detail screen. */
    fun albumIdFrom(extras: Bundle?): String? = extras?.getString(KEY_ALBUM_ID)

    /** The track's artist id, for navigating to the artist detail screen. */
    fun artistIdFrom(extras: Bundle?): String? = extras?.getString(KEY_ARTIST_ID)

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
