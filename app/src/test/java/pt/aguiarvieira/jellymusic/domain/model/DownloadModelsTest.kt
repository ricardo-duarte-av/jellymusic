package pt.aguiarvieira.jellymusic.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadModelsTest {

    @Test
    fun `album status is complete only when all tracks done`() {
        val partial = AlbumDownloadStatus(total = 10, completed = 4, downloading = true, failed = false)
        assertFalse(partial.isComplete)
        assertTrue(partial.inProgress)
        assertEquals(0.4f, partial.progress, 0.0001f)

        val done = AlbumDownloadStatus(total = 10, completed = 10, downloading = false, failed = false)
        assertTrue(done.isComplete)
        assertFalse(done.inProgress)
        assertEquals(1f, done.progress, 0.0001f)
    }

    @Test
    fun `album status with zero total is neither complete nor divides by zero`() {
        val empty = AlbumDownloadStatus(total = 0, completed = 0, downloading = false, failed = false)
        assertFalse(empty.isComplete)
        assertEquals(0f, empty.progress, 0.0001f)
    }

    @Test
    fun `track status flags follow the download state`() {
        assertTrue(TrackDownloadStatus(DownloadState.COMPLETED, 1f).isComplete)
        assertTrue(TrackDownloadStatus(DownloadState.QUEUED, -1f).isActive)
        assertTrue(TrackDownloadStatus(DownloadState.DOWNLOADING, 0.5f).isActive)
        assertTrue(TrackDownloadStatus(DownloadState.QUEUED, -1f).isIndeterminate)
        assertFalse(TrackDownloadStatus(DownloadState.DOWNLOADING, 0.5f).isIndeterminate)
        assertFalse(TrackDownloadStatus(DownloadState.FAILED, 0f).isActive)
    }

    @Test
    fun `search results emptiness`() {
        assertTrue(SearchResults().isEmpty)
        assertFalse(SearchResults(tracks = listOf(sampleTrack())).isEmpty)
    }

    private fun sampleTrack() = Track(
        id = "t1",
        name = "Song",
        artist = "Artist",
        album = "Album",
        albumId = "a1",
        artistId = "ar1",
        discNumber = 1,
        trackNumber = 1,
        durationMs = 1000,
        artworkUrl = null,
    )
}
