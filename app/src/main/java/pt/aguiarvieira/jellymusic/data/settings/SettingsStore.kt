package pt.aguiarvieira.jellymusic.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import pt.aguiarvieira.jellymusic.domain.model.AudioQuality
import pt.aguiarvieira.jellymusic.domain.model.MusicLibrary
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

    val streamingQuality: Flow<AudioQuality> = context.dataStore.data.map { prefs ->
        prefs[KEY_STREAMING_QUALITY].toAudioQuality(AudioQuality.ORIGINAL)
    }

    val downloadQuality: Flow<AudioQuality> = context.dataStore.data.map { prefs ->
        prefs[KEY_DOWNLOAD_QUALITY].toAudioQuality(AudioQuality.HIGH)
    }

    suspend fun setSelectedLibrary(library: MusicLibrary) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LIBRARY_ID] = library.id
            prefs[KEY_LIBRARY_NAME] = library.name
        }
    }

    suspend fun setStreamingQuality(quality: AudioQuality) {
        context.dataStore.edit { it[KEY_STREAMING_QUALITY] = quality.name }
    }

    suspend fun setDownloadQuality(quality: AudioQuality) {
        context.dataStore.edit { it[KEY_DOWNLOAD_QUALITY] = quality.name }
    }

    private fun String?.toAudioQuality(default: AudioQuality): AudioQuality =
        this?.let { runCatching { AudioQuality.valueOf(it) }.getOrNull() } ?: default

    private companion object {
        val KEY_LIBRARY_ID = stringPreferencesKey("selected_library_id")
        val KEY_LIBRARY_NAME = stringPreferencesKey("selected_library_name")
        val KEY_STREAMING_QUALITY = stringPreferencesKey("streaming_quality")
        val KEY_DOWNLOAD_QUALITY = stringPreferencesKey("download_quality")
    }
}
