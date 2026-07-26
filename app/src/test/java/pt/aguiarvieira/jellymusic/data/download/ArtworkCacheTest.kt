package pt.aguiarvieira.jellymusic.data.download

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers the cache-hit decision in [ArtworkCache], i.e. when a file already on disk counts as a
 * usable cover. Getting this wrong is not a transient glitch: the returned path is persisted into
 * `artworkPath` in Room and rendered as `file://…` from then on, so a bad hit shows a broken cover
 * for the life of the download.
 *
 * These cases deliberately never reach the network — a non-HTTP URL fails at the HttpURLConnection
 * cast, which is enough to exercise "cache missed, fetch didn't produce anything".
 */
class ArtworkCacheTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val itemId = "album-1"

    /** A URL that is well-formed but cannot yield an HTTP connection, so the fetch fails immediately. */
    private val unfetchableUrl = "file:///does-not-exist.jpg"

    private fun cache(): ArtworkCache {
        val context = mockk<Context>()
        every { context.filesDir } returns temporaryFolder.root
        return ArtworkCache(context)
    }

    private fun artworkFile() = File(File(temporaryFolder.root, "artwork"), "$itemId.img")

    private fun writeArtwork(contents: ByteArray): File = artworkFile().apply {
        parentFile?.mkdirs()
        writeBytes(contents)
    }

    @Test
    fun `returns null when there is no url to cache`() = runTest {
        assertNull(cache().cache(itemId, null))
        assertNull(cache().cache(itemId, "  "))
    }

    @Test
    fun `serves an existing non-empty cover without refetching`() = runTest {
        val existing = writeArtwork(byteArrayOf(1, 2, 3))

        // The URL is unfetchable, so a non-null result can only have come from the cached file.
        assertEquals(existing.absolutePath, cache().cache(itemId, unfetchableUrl))
    }

    @Test
    fun `treats a zero-length cover as a miss and clears it`() = runTest {
        writeArtwork(ByteArray(0))

        // A zero-length file is the debris of an interrupted write. Returning its path would pin a
        // broken cover in Room forever, so it must be discarded and re-fetched instead.
        assertNull(cache().cache(itemId, unfetchableUrl))
        assertFalse("empty cover should have been deleted", artworkFile().exists())
    }

    @Test
    fun `leaves no scratch file behind when the fetch fails`() = runTest {
        assertNull(cache().cache(itemId, unfetchableUrl))

        val scratch = File(File(temporaryFolder.root, "artwork"), "$itemId.tmp")
        assertFalse("orphan .tmp should be cleaned up", scratch.exists())
    }
}
