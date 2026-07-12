package pt.aguiarvieira.jellymusic.ui.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import pt.aguiarvieira.jellymusic.data.settings.SettingsStore
import pt.aguiarvieira.jellymusic.domain.model.UserSession
import pt.aguiarvieira.jellymusic.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Decides where the app starts: restore a persisted session, then route to Home or onboarding. */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    settingsStore: SettingsStore,
) : ViewModel() {

    /** Whether album art should retint album/player surfaces (user setting). */
    val dynamicAlbumTheme: StateFlow<Boolean> = settingsStore.dynamicAlbumTheme
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** The active session; becomes null on sign-out or when the token is rejected mid-run. */
    val session: StateFlow<UserSession?> = authRepository.session

    private val _startState = MutableStateFlow<StartState>(StartState.Loading)
    val startState: StateFlow<StartState> = _startState.asStateFlow()

    /** Set when the app is launched from the media notification; consumed after navigating. */
    private val _openPlayer = MutableStateFlow(false)
    val openPlayer: StateFlow<Boolean> = _openPlayer.asStateFlow()

    fun requestOpenPlayer() {
        _openPlayer.value = true
    }

    fun consumeOpenPlayer() {
        _openPlayer.value = false
    }

    init {
        viewModelScope.launch {
            authRepository.restoreSession()
            _startState.value = if (authRepository.session.value != null) {
                StartState.Home
            } else {
                StartState.Onboarding
            }
        }
    }
}

sealed interface StartState {
    data object Loading : StartState
    data object Onboarding : StartState
    data object Home : StartState
}
