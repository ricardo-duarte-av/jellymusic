package pt.aguiarvieira.jellymusic.ui.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import pt.aguiarvieira.jellymusic.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Decides where the app starts: restore a persisted session, then route to Home or onboarding. */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

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
