package pt.aguiarvieira.jellymusic.ui.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import pt.aguiarvieira.jellymusic.core.util.Logx
import pt.aguiarvieira.jellymusic.domain.model.Server
import pt.aguiarvieira.jellymusic.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConnectUiState(
    val discovering: Boolean = false,
    val discoveredServers: List<Server> = emptyList(),
    val manualInput: String = "",
    val connecting: Boolean = false,
    val error: String? = null,
    /** Set when a server is validated; the screen navigates to Login and then calls [onNavigated]. */
    val connectedServer: Server? = null,
)

@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectUiState())
    val state = _state.asStateFlow()

    // No automatic discovery on open: LAN discovery needs the local-network permission (Android 17+),
    // and users with a remote server should never be prompted. Discovery is user-initiated.

    fun discover() {
        viewModelScope.launch {
            _state.update { it.copy(discovering = true) }
            val servers = authRepository.discoverServers()
            _state.update { it.copy(discovering = false, discoveredServers = servers) }
        }
    }

    fun onManualInputChange(value: String) {
        _state.update { it.copy(manualInput = value, error = null) }
    }

    fun connectToManual() = connect(_state.value.manualInput.trim())

    fun connectToDiscovered(server: Server) = connect(server.address)

    private fun connect(input: String) {
        if (input.isBlank() || _state.value.connecting) return
        Logx.d(TAG, "ViewModel.connect(\"$input\")")
        viewModelScope.launch {
            _state.update { it.copy(connecting = true, error = null) }
            authRepository.connect(input)
                .onSuccess { server ->
                    Logx.d(TAG, "ViewModel.connect success: ${server.name}")
                    _state.update { it.copy(connecting = false, connectedServer = server) }
                }
                .onFailure { e ->
                    Logx.w(TAG, "ViewModel.connect failure", e)
                    _state.update {
                        it.copy(connecting = false, error = e.message ?: "Could not connect")
                    }
                }
        }
    }

    fun onLocalNetworkDenied() {
        _state.update {
            it.copy(error = "Local network access is needed to find or reach servers on your network.")
        }
    }

    fun onNavigated() {
        _state.update { it.copy(connectedServer = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    private companion object {
        const val TAG = "JellyMusicAuth"
    }
}
