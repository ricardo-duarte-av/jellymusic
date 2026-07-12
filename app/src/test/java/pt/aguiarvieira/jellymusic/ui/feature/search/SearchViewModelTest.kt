package pt.aguiarvieira.jellymusic.ui.feature.search

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import pt.aguiarvieira.jellymusic.data.settings.SettingsStore
import pt.aguiarvieira.jellymusic.domain.model.MusicLibrary
import pt.aguiarvieira.jellymusic.domain.model.SearchResults
import pt.aguiarvieira.jellymusic.domain.model.Track
import pt.aguiarvieira.jellymusic.domain.repository.MusicRepository
import pt.aguiarvieira.jellymusic.util.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<MusicRepository>()
    private val settingsStore = mockk<SettingsStore>()

    private fun viewModel(): SearchViewModel {
        every { settingsStore.selectedLibrary } returns flowOf<MusicLibrary?>(null)
        return SearchViewModel(repository, settingsStore)
    }

    @Test
    fun `a valid query produces results`() = runTest {
        coEvery { repository.search("abc", null) } returns
            Result.success(SearchResults(tracks = listOf(track())))
        val vm = viewModel()
        vm.state.test {
            assertEquals(SearchUiState.Idle, awaitItem())
            vm.onQueryChange("abc")
            var state = awaitItem()
            while (state !is SearchUiState.Results) state = awaitItem()
            assertEquals(1, state.results.tracks.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a too-short query stays idle`() = runTest {
        val vm = viewModel()
        vm.state.test {
            assertEquals(SearchUiState.Idle, awaitItem())
            vm.onQueryChange("a")
            advanceUntilIdle()
            expectNoEvents() // still Idle; no new distinct state
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a failed search surfaces an error`() = runTest {
        coEvery { repository.search("boom", null) } returns Result.failure(IllegalStateException("nope"))
        val vm = viewModel()
        vm.state.test {
            assertEquals(SearchUiState.Idle, awaitItem())
            vm.onQueryChange("boom")
            var state = awaitItem()
            while (state !is SearchUiState.Error) state = awaitItem()
            assertTrue(state.message.contains("nope"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun track() = Track(
        id = "t1",
        name = "Song",
        artist = "Artist",
        album = "Album",
        albumId = "a1",
        discNumber = 1,
        trackNumber = 1,
        durationMs = 1000,
        artworkUrl = null,
    )
}
