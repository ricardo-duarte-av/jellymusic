package pt.aguiarvieira.jellymusic.widget

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.Player
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The snapshot of now-playing state the home-screen widget renders. It lives in its own tiny
 * DataStore because the widget runs in the app process but outside the normal UI lifecycle — it
 * can be asked to render at any time (on add, on host refresh, after a reboot) when
 * [pt.aguiarvieira.jellymusic.playback.PlaybackConnection] / the ExoPlayer may not be alive yet.
 * [pt.aguiarvieira.jellymusic.playback.PlaybackService] keeps this store fresh on every relevant
 * player event and triggers a widget refresh.
 */
data class NowPlayingWidgetData(
    val hasMedia: Boolean = false,
    val isPlaying: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val artworkUri: String? = null,
    val shuffleEnabled: Boolean = false,
    /** Raw Media3 repeat mode: Player.REPEAT_MODE_OFF / ONE / ALL. */
    val repeatMode: Int = 0,
)

/**
 * Snapshots a [Player]'s now-playing state. Must be read on the player's application thread (main).
 * A Media3 [androidx.media3.session.MediaController] applies transport commands to its local state
 * optimistically, so calling this right after issuing a command reflects the new state — which lets
 * the widget refresh instantly instead of waiting for the service to echo the change back.
 */
fun Player.nowPlayingWidgetData(): NowPlayingWidgetData {
    val metadata = mediaMetadata
    return NowPlayingWidgetData(
        hasMedia = currentMediaItem != null,
        isPlaying = isPlaying,
        title = metadata.title?.toString().orEmpty(),
        artist = metadata.artist?.toString().orEmpty(),
        artworkUri = metadata.artworkUri?.toString(),
        shuffleEnabled = shuffleModeEnabled,
        repeatMode = repeatMode,
    )
}

private val Context.nowPlayingWidgetDataStore by preferencesDataStore(name = "jellymusic_now_playing_widget")

private val KEY_HAS_MEDIA = booleanPreferencesKey("has_media")
private val KEY_IS_PLAYING = booleanPreferencesKey("is_playing")
private val KEY_TITLE = stringPreferencesKey("title")
private val KEY_ARTIST = stringPreferencesKey("artist")
private val KEY_ARTWORK = stringPreferencesKey("artwork_uri")
private val KEY_SHUFFLE = booleanPreferencesKey("shuffle")
private val KEY_REPEAT = intPreferencesKey("repeat")

private fun Preferences.toNowPlayingWidgetData() = NowPlayingWidgetData(
    hasMedia = this[KEY_HAS_MEDIA] ?: false,
    isPlaying = this[KEY_IS_PLAYING] ?: false,
    title = this[KEY_TITLE].orEmpty(),
    artist = this[KEY_ARTIST].orEmpty(),
    artworkUri = this[KEY_ARTWORK],
    shuffleEnabled = this[KEY_SHUFFLE] ?: false,
    repeatMode = this[KEY_REPEAT] ?: 0,
)

suspend fun Context.readNowPlayingWidgetData(): NowPlayingWidgetData =
    nowPlayingWidgetDataStore.data.first().toNowPlayingWidgetData()

/**
 * Live view of the widget's now-playing store. The widget composition collects this so a write from
 * [pt.aguiarvieira.jellymusic.playback.PlaybackService] recomposes the *existing* Glance session —
 * updateAll() alone doesn't, because it recomposes rather than re-running provideGlance, so content
 * that read the store only once would stay frozen.
 */
fun Context.nowPlayingWidgetDataFlow(): Flow<NowPlayingWidgetData> =
    nowPlayingWidgetDataStore.data.map { it.toNowPlayingWidgetData() }

suspend fun Context.writeNowPlayingWidgetData(data: NowPlayingWidgetData) {
    nowPlayingWidgetDataStore.edit { prefs ->
        prefs[KEY_HAS_MEDIA] = data.hasMedia
        prefs[KEY_IS_PLAYING] = data.isPlaying
        prefs[KEY_TITLE] = data.title
        prefs[KEY_ARTIST] = data.artist
        if (data.artworkUri != null) prefs[KEY_ARTWORK] = data.artworkUri else prefs.remove(KEY_ARTWORK)
        prefs[KEY_SHUFFLE] = data.shuffleEnabled
        prefs[KEY_REPEAT] = data.repeatMode
    }
}
