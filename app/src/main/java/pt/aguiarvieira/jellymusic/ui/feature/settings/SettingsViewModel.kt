package pt.aguiarvieira.jellymusic.ui.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pt.aguiarvieira.jellymusic.data.settings.SettingsStore
import pt.aguiarvieira.jellymusic.domain.model.AudioCodec
import pt.aguiarvieira.jellymusic.domain.model.StreamSettings
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val streamSettings = settingsStore.streamSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StreamSettings())

    fun setTranscode(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setStreamTranscode(enabled) }
    }

    fun setCodec(codec: AudioCodec) {
        viewModelScope.launch { settingsStore.setStreamCodec(codec) }
    }

    fun setBitrate(kbps: Int) {
        viewModelScope.launch { settingsStore.setStreamBitrate(kbps) }
    }
}
