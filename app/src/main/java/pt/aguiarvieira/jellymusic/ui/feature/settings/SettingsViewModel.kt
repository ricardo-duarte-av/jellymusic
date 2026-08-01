package pt.aguiarvieira.jellymusic.ui.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pt.aguiarvieira.jellymusic.core.image.ImageCacheManager
import pt.aguiarvieira.jellymusic.data.download.FavoriteDownloadSyncManager
import pt.aguiarvieira.jellymusic.data.settings.SettingsStore
import pt.aguiarvieira.jellymusic.domain.model.AudioCodec
import pt.aguiarvieira.jellymusic.domain.model.ReplayGainSettings
import pt.aguiarvieira.jellymusic.domain.model.StreamSettings
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val favoriteSyncManager: FavoriteDownloadSyncManager,
    private val imageCache: ImageCacheManager,
) : ViewModel() {

    val streamSettings = settingsStore.streamSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StreamSettings())

    val downloadSettings = settingsStore.downloadSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StreamSettings())

    val replayGainSettings = settingsStore.replayGainSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReplayGainSettings())

    val dynamicAlbumTheme = settingsStore.dynamicAlbumTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val lyricsEnabled = settingsStore.lyricsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val downloadFavorites = settingsStore.downloadFavorites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val downloadFavoritesOnMetered = settingsStore.downloadFavoritesOnMetered
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setReplayGainEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setReplayGainEnabled(enabled) }
    }

    fun setReplayGainPreampDb(db: Float) {
        viewModelScope.launch { settingsStore.setReplayGainPreampDb(db) }
    }

    fun setDynamicAlbumTheme(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setDynamicAlbumTheme(enabled) }
    }

    fun setLyricsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setLyricsEnabled(enabled) }
    }

    fun setDownloadFavorites(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setDownloadFavorites(enabled)
            if (enabled) favoriteSyncManager.requestSync() else favoriteSyncManager.requestDisable()
        }
    }

    fun setDownloadFavoritesOnMetered(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setDownloadFavoritesOnMetered(enabled)
            // Re-run so the favourite worker picks up the new network constraint.
            if (settingsStore.downloadFavorites.first()) favoriteSyncManager.requestSync()
        }
    }

    fun setTranscode(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setStreamTranscode(enabled) }
    }

    fun setCodec(codec: AudioCodec) {
        viewModelScope.launch { settingsStore.setStreamCodec(codec) }
    }

    fun setBitrate(kbps: Int) {
        viewModelScope.launch { settingsStore.setStreamBitrate(kbps) }
    }

    fun setDownloadTranscode(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setDownloadTranscode(enabled) }
    }

    fun setDownloadCodec(codec: AudioCodec) {
        viewModelScope.launch { settingsStore.setDownloadCodec(codec) }
    }

    fun setDownloadBitrate(kbps: Int) {
        viewModelScope.launch { settingsStore.setDownloadBitrate(kbps) }
    }

    /** Wipes Coil's entire image cache (memory + disk), then invokes [onCleared] on completion. */
    fun clearImageCache(onCleared: () -> Unit) {
        viewModelScope.launch {
            imageCache.clearAll()
            onCleared()
        }
    }
}
