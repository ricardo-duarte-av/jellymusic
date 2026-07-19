package pt.aguiarvieira.jellymusic.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import pt.aguiarvieira.jellymusic.domain.model.AlbumSort
import pt.aguiarvieira.jellymusic.domain.model.AudioCodec
import pt.aguiarvieira.jellymusic.domain.model.MusicLibrary
import pt.aguiarvieira.jellymusic.domain.model.PlaybackModes
import pt.aguiarvieira.jellymusic.domain.model.ReplayGainSettings
import pt.aguiarvieira.jellymusic.domain.model.StreamSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "jellymusic_settings")

/**
 * Non-secret user settings: the selected music library and audio-quality preferences. Backed by
 * DataStore (the access token lives in [pt.aguiarvieira.jellymusic.data.auth.CredentialStore] instead).
 */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val selectedLibrary: Flow<MusicLibrary?> = context.dataStore.data.map { prefs ->
        val id = prefs[KEY_LIBRARY_ID] ?: return@map null
        MusicLibrary(id = id, name = prefs[KEY_LIBRARY_NAME] ?: "")
    }

    val streamSettings: Flow<StreamSettings> = context.dataStore.data.map { prefs ->
        StreamSettings(
            transcode = prefs[KEY_STREAM_TRANSCODE] ?: false,
            codec = prefs[KEY_STREAM_CODEC]?.let { runCatching { AudioCodec.valueOf(it) }.getOrNull() }
                ?: AudioCodec.OPUS,
            maxBitrateKbps = prefs[KEY_STREAM_BITRATE] ?: 320,
        )
    }

    /** ReplayGain (loudness-normalization) playback preferences. Defaults to enabled, 0 dB preamp. */
    val replayGainSettings: Flow<ReplayGainSettings> = context.dataStore.data.map { prefs ->
        ReplayGainSettings(
            enabled = prefs[KEY_REPLAYGAIN_ENABLED] ?: true,
            preampDb = prefs[KEY_REPLAYGAIN_PREAMP_DB] ?: 0f,
        )
    }

    /** Whether the album/player surfaces retint to the album art. Defaults on. */
    val dynamicAlbumTheme: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_DYNAMIC_ALBUM_THEME] ?: true
    }

    /** Whether the user's favourites are auto-downloaded for offline listening. Defaults off. */
    val downloadFavorites: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_DOWNLOAD_FAVORITES] ?: false
    }

    /** Whether favourite auto-downloads may run on a metered connection. Defaults off (Wi-Fi only). */
    val downloadFavoritesOnMetered: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_DOWNLOAD_FAVORITES_METERED] ?: false
    }

    val albumSort: Flow<AlbumSort> = context.dataStore.data.map { prefs ->
        prefs[KEY_ALBUM_SORT]?.let { runCatching { AlbumSort.valueOf(it) }.getOrNull() }
            ?: AlbumSort.DEFAULT
    }

    val albumSortDescending: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_ALBUM_SORT_DESC] ?: false
    }

    /** Persisted shuffle/repeat, restored by the playback service so they survive restarts. */
    val playbackModes: Flow<PlaybackModes> = context.dataStore.data.map { prefs ->
        PlaybackModes(
            shuffle = prefs[KEY_PLAYBACK_SHUFFLE] ?: false,
            repeatMode = prefs[KEY_PLAYBACK_REPEAT] ?: 0,
        )
    }

    suspend fun setSelectedLibrary(library: MusicLibrary) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LIBRARY_ID] = library.id
            prefs[KEY_LIBRARY_NAME] = library.name
        }
    }

    suspend fun setStreamTranscode(enabled: Boolean) {
        context.dataStore.edit { it[KEY_STREAM_TRANSCODE] = enabled }
    }

    suspend fun setStreamCodec(codec: AudioCodec) {
        context.dataStore.edit { it[KEY_STREAM_CODEC] = codec.name }
    }

    suspend fun setStreamBitrate(kbps: Int) {
        context.dataStore.edit { it[KEY_STREAM_BITRATE] = kbps }
    }

    suspend fun setReplayGainEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_REPLAYGAIN_ENABLED] = enabled }
    }

    suspend fun setReplayGainPreampDb(db: Float) {
        context.dataStore.edit { it[KEY_REPLAYGAIN_PREAMP_DB] = db }
    }

    suspend fun setDynamicAlbumTheme(enabled: Boolean) {
        context.dataStore.edit { it[KEY_DYNAMIC_ALBUM_THEME] = enabled }
    }

    suspend fun setDownloadFavorites(enabled: Boolean) {
        context.dataStore.edit { it[KEY_DOWNLOAD_FAVORITES] = enabled }
    }

    suspend fun setDownloadFavoritesOnMetered(enabled: Boolean) {
        context.dataStore.edit { it[KEY_DOWNLOAD_FAVORITES_METERED] = enabled }
    }

    suspend fun setAlbumSort(sort: AlbumSort) {
        context.dataStore.edit { it[KEY_ALBUM_SORT] = sort.name }
    }

    suspend fun setAlbumSortDescending(descending: Boolean) {
        context.dataStore.edit { it[KEY_ALBUM_SORT_DESC] = descending }
    }

    suspend fun setPlaybackModes(shuffle: Boolean, repeatMode: Int) {
        context.dataStore.edit {
            it[KEY_PLAYBACK_SHUFFLE] = shuffle
            it[KEY_PLAYBACK_REPEAT] = repeatMode
        }
    }

    private companion object {
        val KEY_LIBRARY_ID = stringPreferencesKey("selected_library_id")
        val KEY_LIBRARY_NAME = stringPreferencesKey("selected_library_name")
        val KEY_STREAM_TRANSCODE = booleanPreferencesKey("stream_transcode")
        val KEY_STREAM_CODEC = stringPreferencesKey("stream_codec")
        val KEY_STREAM_BITRATE = intPreferencesKey("stream_bitrate_kbps")
        val KEY_REPLAYGAIN_ENABLED = booleanPreferencesKey("replaygain_enabled")
        val KEY_REPLAYGAIN_PREAMP_DB = floatPreferencesKey("replaygain_preamp_db")
        val KEY_DYNAMIC_ALBUM_THEME = booleanPreferencesKey("dynamic_album_theme")
        val KEY_DOWNLOAD_FAVORITES = booleanPreferencesKey("download_favorites")
        val KEY_DOWNLOAD_FAVORITES_METERED = booleanPreferencesKey("download_favorites_metered")
        val KEY_ALBUM_SORT = stringPreferencesKey("album_sort")
        val KEY_ALBUM_SORT_DESC = booleanPreferencesKey("album_sort_descending")
        val KEY_PLAYBACK_SHUFFLE = booleanPreferencesKey("playback_shuffle")
        val KEY_PLAYBACK_REPEAT = intPreferencesKey("playback_repeat_mode")
    }
}
