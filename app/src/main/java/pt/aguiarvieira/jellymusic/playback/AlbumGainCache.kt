package pt.aguiarvieira.jellymusic.playback

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pt.aguiarvieira.jellymusic.data.db.AlbumGainDao
import pt.aguiarvieira.jellymusic.data.db.AlbumGainEntity
import pt.aguiarvieira.jellymusic.domain.repository.MusicRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Album ReplayGain for the Album/Auto normalization modes. Jellyfin puts the album gain on the album
 * item only, not on its tracks, so it has to be looked up separately.
 *
 * [gainDb] is read by [GainAudioProcessor] on the playback thread at every track start, so it only
 * ever reads memory. The memory map is seeded from the `album_gains` table (so it works offline and
 * is ready before the network answers) and each album is re-fetched from the server once per process,
 * picking up rescans.
 */
@Singleton
class AlbumGainCache @Inject constructor(
    private val dao: AlbumGainDao,
    private val musicRepository: MusicRepository,
) {
    // albumId -> gain dB, with NaN meaning "the server has no album gain" (the map can't hold nulls).
    private val gains = ConcurrentHashMap<String, Float>()
    private val fetchedThisProcess = mutableSetOf<String>()
    private val mutex = Mutex()
    private var loadedFromDb = false

    /** The album's gain in dB, or null when it's unknown or the album has none. Safe on any thread. */
    fun gainDb(albumId: String): Float? = gains[albumId]?.takeUnless { it.isNaN() }

    /**
     * Makes sure [albumIds] are cached: loads the stored gains on first use, then fetches the albums
     * not yet fetched in this process. Returns true when any gain became available or changed, so the
     * caller can re-evaluate the track being played.
     */
    suspend fun ensure(albumIds: Collection<String>): Boolean = mutex.withLock {
        var changed = false
        if (!loadedFromDb) {
            dao.all().forEach { gains[it.albumId] = it.gainDb ?: Float.NaN }
            loadedFromDb = true
            changed = gains.isNotEmpty()
        }
        val missing = albumIds.filterNot { it in fetchedThisProcess }.distinct()
        if (missing.isEmpty()) return@withLock changed
        // Offline or failed: keep what's stored and retry on the next queue change.
        val fetched = musicRepository.getNormalizationGains(missing).getOrNull() ?: return@withLock changed
        fetchedThisProcess += missing
        // Only ids the server answered for; a missing one might be a transient permission/library issue.
        val rows = fetched.map { (id, db) -> AlbumGainEntity(id, db) }
        dao.upsert(rows)
        rows.forEach {
            val value = it.gainDb ?: Float.NaN
            if (gains.put(it.albumId, value)?.equals(value) != true) changed = true
        }
        changed
    }
}
