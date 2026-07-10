package pt.aguiarvieira.jellymusic.ui.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import pt.aguiarvieira.jellymusic.data.settings.SettingsStore
import pt.aguiarvieira.jellymusic.domain.model.MusicLibrary
import pt.aguiarvieira.jellymusic.domain.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibraryUiState(
    val loading: Boolean = true,
    val libraries: List<MusicLibrary> = emptyList(),
    val error: String? = null,
    val selected: Boolean = false,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            libraryRepository.getMusicLibraries()
                .onSuccess { libraries ->
                    // Always offer "All music" plus each individual library.
                    _state.update {
                        it.copy(loading = false, libraries = listOf(MusicLibrary.all()) + libraries)
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(loading = false, error = e.message ?: "Failed to load libraries")
                    }
                }
        }
    }

    fun select(library: MusicLibrary) {
        viewModelScope.launch {
            settingsStore.setSelectedLibrary(library)
            _state.update { it.copy(selected = true) }
        }
    }
}
