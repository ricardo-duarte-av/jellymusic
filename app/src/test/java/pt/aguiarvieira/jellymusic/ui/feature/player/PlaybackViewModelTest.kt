package pt.aguiarvieira.jellymusic.ui.feature.player

import androidx.media3.common.util.UnstableApi
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import pt.aguiarvieira.jellymusic.domain.model.AudioCodec
import pt.aguiarvieira.jellymusic.domain.model.StreamSettings
import pt.aguiarvieira.jellymusic.data.download.FavoriteDownloadSyncManager
import pt.aguiarvieira.jellymusic.domain.model.TrackAudioInfo
import pt.aguiarvieira.jellymusic.domain.repository.MusicRepository
import pt.aguiarvieira.jellymusic.playback.PlaybackConnection
import pt.aguiarvieira.jellymusic.playback.PlaybackProgress
import pt.aguiarvieira.jellymusic.playback.PlaybackUiState
import pt.aguiarvieira.jellymusic.playback.QueueItem
import pt.aguiarvieira.jellymusic.util.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(UnstableApi::class, ExperimentalCoroutinesApi::class)
class PlaybackViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<MusicRepository>()
    private val favoriteSyncManager = mockk<FavoriteDownloadSyncManager>(relaxed = true)

    private fun viewModelWith(state: PlaybackUiState): PlaybackViewModel {
        val connection = mockk<PlaybackConnection>()
        every { connection.state } returns MutableStateFlow(state)
        every { connection.progress } returns MutableStateFlow(PlaybackProgress())
        every { connection.queue } returns MutableStateFlow(emptyList<QueueItem>())
        // The VM reads the current track's favourite state on init.
        coEvery { repository.getFavorite(any()) } returns Result.success(false)
        return PlaybackViewModel(connection, repository, favoriteSyncManager)
    }

    private val flac = TrackAudioInfo(
        codec = "flac",
        sampleRateHz = 44_100,
        bitDepth = 16,
        bitrateKbps = 1000,
        channels = 2,
    )

    @Test
    fun `downloaded transcoded file reports only the transcoded format`() = runTest {
        val vm = viewModelWith(
            PlaybackUiState(
                hasMedia = true,
                trackId = "t1",
                appliedStreamSettings = StreamSettings(transcode = true, codec = AudioCodec.AAC, maxBitrateKbps = 128),
                isLocal = true,
            ),
        )
        vm.qualityLabel.test {
            assertEquals(null, awaitItem()) // initial
            advanceUntilIdle()
            assertEquals("AAC 128 kbps", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `streaming transcode reports original arrow transcoded`() = runTest {
        coEvery { repository.getTrackAudioInfo("t2") } returns Result.success(flac)
        val vm = viewModelWith(
            PlaybackUiState(
                hasMedia = true,
                trackId = "t2",
                appliedStreamSettings = StreamSettings(transcode = true, codec = AudioCodec.AAC, maxBitrateKbps = 128),
                isLocal = false,
            ),
        )
        vm.qualityLabel.test {
            assertEquals(null, awaitItem())
            advanceUntilIdle()
            val label = awaitItem()!!
            assertTrue("has arrow: $label", label.contains("→"))
            assertTrue("starts FLAC: $label", label.startsWith("FLAC"))
            assertTrue("ends transcoded: $label", label.endsWith("AAC 128 kbps"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `original playback reports the source format with no arrow`() = runTest {
        coEvery { repository.getTrackAudioInfo("t3") } returns Result.success(flac)
        val vm = viewModelWith(
            PlaybackUiState(hasMedia = true, trackId = "t3", appliedStreamSettings = StreamSettings(transcode = false)),
        )
        vm.qualityLabel.test {
            assertEquals(null, awaitItem())
            advanceUntilIdle()
            val label = awaitItem()!!
            assertTrue("starts FLAC: $label", label.startsWith("FLAC"))
            assertTrue("no arrow: $label", !label.contains("→"))
            cancelAndIgnoreRemainingEvents()
        }
    }
}
