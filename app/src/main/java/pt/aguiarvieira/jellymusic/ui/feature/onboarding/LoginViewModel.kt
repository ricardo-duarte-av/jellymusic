package pt.aguiarvieira.jellymusic.ui.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import pt.aguiarvieira.jellymusic.domain.model.Server
import pt.aguiarvieira.jellymusic.domain.repository.AuthRepository
import pt.aguiarvieira.jellymusic.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val serverName: String = "",
    val username: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val quickConnectCode: String? = null,
    val authenticated: Boolean = false,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val args = savedStateHandle.toRoute<Routes.Login>()
    private val server = Server(id = args.serverId, name = args.serverName, address = args.serverAddress)

    private val _state = MutableStateFlow(LoginUiState(serverName = server.name))
    val state = _state.asStateFlow()

    private var quickConnectJob: Job? = null

    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, error = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun login() {
        val current = _state.value
        if (current.loading || current.username.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            authRepository.loginWithPassword(server, current.username, current.password)
                .onSuccess { _state.update { s -> s.copy(loading = false, authenticated = true) } }
                .onFailure { e ->
                    _state.update { it.copy(loading = false, error = e.friendlyMessage()) }
                }
        }
    }

    fun startQuickConnect() {
        if (quickConnectJob?.isActive == true) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            authRepository.initiateQuickConnect(server)
                .onSuccess { session ->
                    _state.update { it.copy(loading = false, quickConnectCode = session.code) }
                    pollQuickConnect(session.secret)
                }
                .onFailure { e ->
                    _state.update { it.copy(loading = false, error = e.friendlyMessage()) }
                }
        }
    }

    fun cancelQuickConnect() {
        quickConnectJob?.cancel()
        _state.update { it.copy(quickConnectCode = null) }
    }

    private fun pollQuickConnect(secret: String) {
        quickConnectJob = viewModelScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val result = authRepository.pollQuickConnect(server, secret)
                val session = result.getOrNull()
                if (session != null) {
                    _state.update { it.copy(authenticated = true, quickConnectCode = null) }
                    return@launch
                }
            }
        }
    }

    private fun Throwable.friendlyMessage(): String = when {
        message?.contains("401") == true -> "Incorrect username or password"
        else -> message ?: "Sign-in failed"
    }

    private companion object {
        const val POLL_INTERVAL_MS = 3_000L
    }
}
