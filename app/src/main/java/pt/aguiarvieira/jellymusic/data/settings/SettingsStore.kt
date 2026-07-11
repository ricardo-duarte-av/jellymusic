package pt.aguiarvieira.jellymusic.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import pt.aguiarvieira.jellymusic.domain.model.AlbumSort
import pt.aguiarvieira.jellymusic.domain.model.AudioCodec
import pt.aguiarvieira.jellymusic.domain.model.AudioQuality
import pt.aguiarvieira.jellymusic.domain.model.MusicLibrary
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

    val downloadQuality: Flow<AudioQuality> = context.dataStore.data.map { prefs ->
        prefs[KEY_DOWNLOAD_QUALITY].toAudioQuality(AudioQuality.HIGH)
    }

    val albumSort: Flow<AlbumSort> = context.dataStore.data.map { prefs ->
        prefs[KEY_ALBUM_SORT]?.let { runCatching { AlbumSort.valueOf(it) }.getOrNull() }
            ?: AlbumSort.DEFAULT
    }

    val albumSortDescending: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_ALBUM_SORT_DESC] ?: false
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

    suspend fun setDownloadQuality(quality: AudioQuality) {
        context.dataStore.edit { it[KEY_DOWNLOAD_QUALITY] = quality.name }
    }

    suspend fun setAlbumSort(sort: AlbumSort) {
        context.dataStore.edit { it[KEY_ALBUM_SORT] = sort.name }
    }

    suspend fun setAlbumSortDescending(descending: Boolean) {
        context.dataStore.edit { it[KEY_ALBUM_SORT_DESC] = descending }
    }

    private fun String?.toAudioQuality(default: AudioQuality): AudioQuality =
        this?.let { runCatching { AudioQuality.valueOf(it) }.getOrNull() } ?: default

    private companion object {
        val KEY_LIBRARY_ID = stringPreferencesKey("selected_library_id")
        val KEY_LIBRARY_NAME = stringPreferencesKey("selected_library_name")
        val KEY_STREAM_TRANSCODE = booleanPreferencesKey("stream_transcode")
        val KEY_STREAM_CODEC = stringPreferencesKey("stream_codec")
        val KEY_STREAM_BITRATE = intPreferencesKey("stream_bitrate_kbps")
        val KEY_DOWNLOAD_QUALITY = stringPreferencesKey("download_quality")
        val KEY_ALBUM_SORT = stringPreferencesKey("album_sort")
        val KEY_ALBUM_SORT_DESC = booleanPreferencesKey("album_sort_descending")
    }
}
