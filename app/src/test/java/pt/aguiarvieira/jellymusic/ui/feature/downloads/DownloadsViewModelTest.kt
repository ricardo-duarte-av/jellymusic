package pt.aguiarvieira.jellymusic.ui.feature.downloads

import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import pt.aguiarvieira.jellymusic.core.network.NetworkMonitor
import pt.aguiarvieira.jellymusic.data.db.AlbumDownloadEntity
import pt.aguiarvieira.jellymusic.data.db.DownloadDao
import pt.aguiarvieira.jellymusic.data.db.PlaylistDownloadEntity
import pt.aguiarvieira.jellymusic.data.db.TrackDownloadEntity
import pt.aguiarvieira.jellymusic.data.download.MusicDownloadManager
import pt.aguiarvieira.jellymusic.data.settings.SettingsStore
import pt.aguiarvieira.jellymusic.domain.model.StreamSettings
import pt.aguiarvieira.jellymusic.util.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val downloadManager = mockk<MusicDownloadManager>(relaxed = true)
    private val networkMonitor = mockk<NetworkMonitor>(relaxed = true)
    private val settingsStore = mockk<SettingsStore>()

    private fun viewModel(
        tracks: List<TrackDownloadEntity>,
        albums: List<AlbumDownloadEntity> = emptyList(),
        playlists: List<PlaylistDownloadEntity> = emptyList(),
    ): DownloadsViewModel {
        val dao = mockk<DownloadDao>()
        every { dao.observeTracks() } returns flowOf(tracks)
        every { dao.observeAlbums() } returns flowOf(albums)
        every { dao.observePlaylists() } returns flowOf(playlists)
        every { settingsStore.streamSettings } returns flowOf(StreamSettings())
        return DownloadsViewModel(downloadManager, networkMonitor, dao, settingsStore)
    }

    @Test
    fun `transcoded track without a server length gets an estimated determinate progress`() = runTest {
        // 128 kbps, 200 s => ~3.2 MB expected; 1.6 MB downloaded => ~0.5.
        val track = trackEntity(
            state = "DOWNLOADING",
            transcoded = true,
            bitrateKbps = 128,
            durationMs = 200_000,
            downloadedBytes = 1_600_000,
            totalBytes = 0,
        )
        viewModel(listOf(track)).trackStatuses.test {
            var map = awaitItem()
            while (map.isEmpty()) map = awaitItem()
            val status = map.getValue("t")
            assertTrue("determinate: ${status.progress}", !status.isIndeterminate)
            assertEquals(0.5f, status.progress, 0.02f)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `album status aggregates completed and in-flight tracks`() = runTest {
        val album = AlbumDownloadEntity(
            albumId = "a1",
            name = "Album",
            artist = "Artist",
            artworkUrl = null,
            totalTracks = 2,
            transcoded = false,
            requestedAt = 0,
        )
        val done = trackEntity(trackId = "t1", albumId = "a1", state = "COMPLETED")
        val queued = trackEntity(trackId = "t2", albumId = "a1", state = "QUEUED")
        viewModel(listOf(done, queued), listOf(album)).albumStatuses.test {
            var map = awaitItem()
            while (map.isEmpty()) map = awaitItem()
            val status = map.getValue("a1")
            assertEquals(2, status.total)
            assertEquals(1, status.completed)
            assertTrue(status.downloading)
            assertTrue(!status.isComplete)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `playlist status aggregates its member tracks by id`() = runTest {
        val playlist = PlaylistDownloadEntity(
            playlistId = "p1",
            name = "Mix",
            artworkUrl = null,
            totalTracks = 2,
            trackIds = listOf("t1", "t2"),
            transcoded = false,
            requestedAt = 0,
        )
        // t1 belongs to a downloaded album too, but the playlist still counts it via its id.
        val done = trackEntity(trackId = "t1", albumId = "a1", state = "COMPLETED")
        val queued = trackEntity(trackId = "t2", albumId = null, state = "QUEUED")
        viewModel(listOf(done, queued), playlists = listOf(playlist)).playlistStatuses.test {
            var map = awaitItem()
            while (map.isEmpty()) map = awaitItem()
            val status = map.getValue("p1")
            assertEquals(2, status.total)
            assertEquals(1, status.completed)
            assertTrue(status.downloading)
            assertTrue(!status.isComplete)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun trackEntity(
        trackId: String = "t",
        albumId: String? = "a",
        state: String = "COMPLETED",
        transcoded: Boolean = false,
        bitrateKbps: Int? = null,
        durationMs: Long? = 1000,
        downloadedBytes: Long = 0,
        totalBytes: Long = 0,
    ) = TrackDownloadEntity(
        trackId = trackId,
        albumId = albumId,
        title = "Title",
        artist = "Artist",
        album = "Album",
        discNumber = 1,
        trackNumber = 1,
        durationMs = durationMs,
        artworkUrl = null,
        transcoded = transcoded,
        codec = if (transcoded) "AAC" else null,
        bitrateKbps = bitrateKbps,
        state = state,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        filePath = null,
        updatedAt = 0,
    )
}
