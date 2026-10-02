package pt.aguiarvieira.jellymusic.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pt.aguiarvieira.jellymusic.data.jellyfin.JellyfinClientProvider
import pt.aguiarvieira.jellymusic.data.jellyfin.StreamUrlBuilder
import pt.aguiarvieira.jellymusic.data.settings.SettingsStore
import pt.aguiarvieira.jellymusic.domain.model.AudioCodec
import pt.aguiarvieira.jellymusic.domain.model.STREAM_BITRATE_OPTIONS
import pt.aguiarvieira.jellymusic.domain.model.StreamSettings
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "StreamCache"

/** URI scheme of streamed tracks; [StreamCacheDataSource] serves it from the cache. */
private const val STREAM_SCHEME = "jellymusic-stream"

/** Tracks fetched ahead of the current one. */
private const val PREFETCH_AHEAD = 3

/**
 * Look-ahead refill threshold. Fetching starts only once fewer than this many upcoming tracks are
 * ready, and then fills all [PREFETCH_AHEAD]. Several tracks therefore come down in one burst every
 * couple of songs, instead of one small download per song. Each burst costs one radio wake-up.
 */
private const val PREFETCH_LOW_WATER = 1

/**
 * Size of the files a download is committed to the cache in. A track becomes readable one fragment
 * at a time, so this is also how much of an original file has to arrive before it starts playing.
 * Only applies to downloads flagged [DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION].
 */
private const val FRAGMENT_BYTES = 1L shl 20 // 1 MiB

/** A failed download isn't retried for this long, unless the player asks for that track. */
private const val RETRY_BACKOFF_MS = 15_000L

private const val BYTES_PER_GB = 1L shl 30

/**
 * The streaming cache: every streamed track is downloaded **whole**, into a large on-disk cache, and
 * played from there ([StreamCacheDataSource]). This replaces ExoPlayer trickling the stream, which
 * fetched a little every few seconds and kept the mobile radio permanently powered.
 *
 * **Downloading.** One background worker fetches the current track and the next [PREFETCH_AHEAD] (in
 * play order, see [setWindow]) one after another. Each download runs at full speed and then the
 * network goes quiet. Look-ahead downloads come in bursts (see [PREFETCH_LOW_WATER]).
 *
 * **Quality.** Each quality of a track is cached under its own key ("original", or codec + bitrate),
 * chosen per track from the Wi-Fi or mobile settings ([currentStreamSettings]). A complete copy of
 * *equal or higher* quality always wins over downloading the desired one. So an original cached on
 * Wi-Fi is replayed as-is on mobile data, and a mobile transcode is never written over it. Once a
 * better copy is complete, lower ones of that track are deleted.
 *
 * **Pins.** The quality picked for a track is pinned while it's in the window, so the player and the
 * downloader agree on what to read and fetch. Pins of tracks that haven't started are re-decided
 * when the window moves, the network changes or the settings change. [pins] publishes them so the
 * now-playing quality label can show what is really playing.
 *
 * Transcodes have no length and no byte ranges, and the server's output differs on every run. So a
 * partial transcode is thrown away rather than resumed, and the player waits for the whole file
 * before opening one (a 4-minute track transcodes in a couple of seconds). That way it plays from a
 * file of known length, and seeking works. Originals are range-resumable and start playing once their
 * first fragment lands.
 */
@OptIn(UnstableApi::class)
@Singleton
class StreamCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val urlBuilder: StreamUrlBuilder,
    private val clientProvider: JellyfinClientProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir = File(context.noBackupFilesDir, "stream_cache")

    @Volatile private var wifiSettings = StreamSettings()

    @Volatile private var mobileSettings = StreamSettings()

    @Volatile private var metered = true

    private val evictor = StreamCacheEvictor(dir) { protectedKeys }

    @Volatile private var protectedKeys: Set<String> = emptySet()

    @Volatile private var simpleCache: SimpleCache? = null
    private val cacheReady = CountDownLatch(1)

    private val writerFactory by lazy {
        val cache = cache()
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(
                DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(15_000)
                    .setReadTimeoutMs(20_000),
            )
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache).setFragmentSize(FRAGMENT_BYTES))
            .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
    }

    // ---- Worker state, guarded by [lock]. ----
    private val lock = Any()
    private var window: List<String> = emptyList()
    private var urgent: String? = null
    private var filling = false
    private var running: Running? = null
    private val openTracks = HashMap<String, Int>()

    private val pinMap = ConcurrentHashMap<String, StreamSettings>()
    private val completeTracks: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val failures = ConcurrentHashMap<String, Long>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val _pins = MutableStateFlow<Map<String, StreamSettings>>(emptyMap())

    /** Quality pinned per track id, for the tracks in the window. */
    val pins: StateFlow<Map<String, StreamSettings>> = _pins.asStateFlow()

    /** Called (on a background thread) whenever a download starts, i.e. the radio is about to be up. */
    @Volatile
    var onFetchStarted: (() -> Unit)? = null

    private class Running(val trackId: String, val key: String, val writer: CacheWriter) {
        @Volatile var cancelled = false
    }

    init {
        trackMeteredNetwork()
        scope.launch {
            wifiSettings = settingsStore.streamSettings.first()
            mobileSettings = settingsStore.mobileStreamSettings.first()
            // The limit must be set before the cache opens: opening replays every stored span
            // through the evictor, which would otherwise see a limit of 0 and empty the cache.
            evictor.maxBytes = settingsStore.streamCacheGb.first() * BYTES_PER_GB
            simpleCache = runCatching { SimpleCache(dir, evictor, StandaloneDatabaseProvider(context)) }
                .onFailure { Log.e(TAG, "Stream cache unavailable", it) }
                .getOrNull()
            cacheReady.countDown()

            launch { settingsStore.streamSettings.distinctUntilChanged().drop(1).collect { wifiSettings = it; repin() } }
            launch { settingsStore.mobileStreamSettings.distinctUntilChanged().drop(1).collect { mobileSettings = it; repin() } }
            launch {
                settingsStore.streamCacheGb.distinctUntilChanged().drop(1).collect { gb ->
                    evictor.maxBytes = gb * BYTES_PER_GB
                    simpleCache?.let { evictor.evict(it, 0) }
                }
            }
            if (simpleCache != null) workerLoop()
        }
    }

    /** Settings for a track streamed right now: the mobile ones on a metered network, else Wi-Fi's. */
    fun currentStreamSettings(): StreamSettings =
        (if (metered) mobileSettings else wifiSettings).let { if (it.transcode) it else ORIGINAL }

    /**
     * Sets the tracks to keep downloaded: the current track first, then the upcoming ones in play
     * order. Main thread; never touches the disk.
     */
    fun setWindow(trackIds: List<String>) {
        val ids = trackIds.distinct().take(1 + PREFETCH_AHEAD)
        synchronized(lock) {
            if (ids == window) return
            window = ids
            val r = running
            // Stop a download that's no longer wanted, or that's in the way of a current track that
            // still has to come down. An original keeps what it got and resumes later.
            val current = ids.firstOrNull()
            if (r != null && (r.trackId !in ids || (current != r.trackId && current !in completeTracks))) {
                r.cancelled = true
                r.writer.cancel()
            }
            dropUnstartedPinsLocked()
        }
        wake.trySend(Unit)
    }

    /** The settings or the network changed: re-decide the quality of tracks not yet started. */
    private fun repin() {
        synchronized(lock) { dropUnstartedPinsLocked() }
        wake.trySend(Unit)
    }

    private fun dropUnstartedPinsLocked() {
        val current = window.firstOrNull()
        pinMap.keys.retainAll { id ->
            id in window && (id == current || id == running?.trackId || id in completeTracks || id in openTracks)
        }
        publishPinsLocked()
    }

    private fun publishPinsLocked() {
        _pins.value = HashMap(pinMap)
        protectedKeys = pinMap.map { (id, s) -> keyFor(id, s) }.toSet()
    }

    // ---- Used by StreamCacheDataSource, on the player's loading thread. ----

    /** The cache, once opened. Blocks; never call it from the main thread. */
    internal fun cache(): SimpleCache {
        cacheReady.await()
        return simpleCache ?: throw IOException("Stream cache unavailable")
    }

    /** The quality to play [trackId] in, pinning it if nothing has yet. Blocks until the cache is open. */
    internal fun pinFor(trackId: String): StreamSettings {
        cache() // Wait for the cache outside the lock, so the main thread never queues behind it.
        return synchronized(lock) { pinForLocked(trackId) }
    }

    /**
     * Asks for [trackId] to be fetched next. [retry] also clears a failure backoff; the player's
     * reader passes it when (re)opening a track, so each player retry gets a fresh download attempt.
     */
    internal fun demand(trackId: String, retry: Boolean = false) {
        synchronized(lock) {
            urgent = trackId
            if (retry) pinMap[trackId]?.let { failures.remove(keyFor(trackId, it)) }
        }
        wake.trySend(Unit)
    }

    /** Whether a download of [key] failed after [sinceMs]. */
    internal fun failedSince(key: String, sinceMs: Long): Boolean = (failures[key] ?: 0L) > sinceMs

    internal fun readerOpened(trackId: String) = synchronized(lock) {
        openTracks[trackId] = (openTracks[trackId] ?: 0) + 1
    }

    internal fun readerClosed(trackId: String) = synchronized(lock) {
        val n = (openTracks[trackId] ?: 1) - 1
        if (n <= 0) openTracks.remove(trackId) else openTracks[trackId] = n
    }

    internal fun isComplete(key: String): Boolean {
        val cache = cache()
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return length != C.LENGTH_UNSET.toLong() && cache.isCached(key, 0, length)
    }

    // ---- Settings screen. ----

    suspend fun usedBytes(): Long = withContext(Dispatchers.IO) { runCatching { cache().cacheSpace }.getOrDefault(0L) }

    /** Empties the cache, except tracks being played right now. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        val cache = runCatching { cache() }.getOrNull() ?: return@withContext
        val keep = synchronized(lock) {
            running?.let { it.cancelled = true; it.writer.cancel() }
            openTracks.keys.toSet()
        }
        cache.keys.filter { it.substringBefore('|') !in keep }.forEach { cache.removeResource(it) }
        completeTracks.retainAll(keep)
        wake.trySend(Unit)
    }

    // ---- Downloading. ----

    private suspend fun workerLoop() {
        while (true) {
            val next = runCatching { synchronized(lock) { nextJobLocked() } }
                .onFailure { Log.w(TAG, "Picking the next download failed", it) }
                .getOrNull()
            if (next == null) {
                wake.receive()
                continue
            }
            download(next.first, next.second)
        }
    }

    private fun pinForLocked(trackId: String): StreamSettings {
        pinMap[trackId]?.let { return it }
        return chooseQuality(trackId).also {
            pinMap[trackId] = it
            publishPinsLocked()
        }
    }

    /** The best complete copy at or above the desired quality, else the desired quality itself. */
    private fun chooseQuality(trackId: String): StreamSettings {
        val desired = currentStreamSettings()
        return qualities(desired)
            .filter { rank(it) >= rank(desired) && isComplete(keyFor(trackId, it)) }
            .maxByOrNull(::rank)
            ?: desired
    }

    private fun nextJobLocked(): Pair<String, StreamSettings>? {
        val now = System.currentTimeMillis()

        fun pending(id: String): Pair<String, StreamSettings>? {
            val quality = pinForLocked(id)
            val key = keyFor(id, quality)
            if (isComplete(key)) {
                completeTracks += id
                if (urgent == id) urgent = null
                return null
            }
            completeTracks -= id
            val failedAt = failures[key]
            if (failedAt != null && now - failedAt < RETRY_BACKOFF_MS) return null
            return id to quality
        }

        window.firstOrNull()?.let(::pending)?.let { return it }
        urgent?.let(::pending)?.let { return it }

        val ahead = window.drop(1)
        if (!filling) {
            val ready = ahead.takeWhile { pending(it) == null }.size
            if (ready >= minOf(PREFETCH_LOW_WATER, ahead.size)) return null
            filling = true
        }
        return ahead.firstNotNullOfOrNull(::pending).also { if (it == null) filling = false }
    }

    private fun download(trackId: String, quality: StreamSettings) {
        val key = keyFor(trackId, quality)
        val cache = cache()
        val url = urlBuilder.audioStreamUrl(trackId, quality)
        if (url == null || clientProvider.session.value == null) {
            failures[key] = System.currentTimeMillis()
            return
        }
        // A partial transcode can't be resumed: the next run's bytes wouldn't line up with these.
        if (quality.transcode && cache.getCachedSpans(key).isNotEmpty()) cache.removeResource(key)

        val writer = CacheWriter(
            writerFactory.createDataSource(),
            // Without FLAG_ALLOW_CACHE_FRAGMENTATION the sink ignores FRAGMENT_BYTES and commits the
            // whole file as one span at the very end, so nothing could play until it had all arrived.
            DataSpec.Builder()
                .setUri(url)
                .setKey(key)
                .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
                .build(),
            null,
            null,
        )
        val job = Running(trackId, key, writer)
        synchronized(lock) { running = job }
        onFetchStarted?.invoke()
        val started = System.currentTimeMillis()
        try {
            writer.cache()
            Log.d(TAG, "Cached $key in ${System.currentTimeMillis() - started} ms")
            failures.remove(key)
            completeTracks += trackId
            removeLowerQualities(trackId, quality)
        } catch (e: IOException) {
            if (!job.cancelled) {
                Log.w(TAG, "Download of $key failed", e)
                failures[key] = System.currentTimeMillis()
            }
            if (quality.transcode) runCatching { cache.removeResource(key) }
        } finally {
            synchronized(lock) {
                running = null
                if (urgent == trackId && trackId in completeTracks) urgent = null
                publishPinsLocked()
            }
        }
    }

    private fun removeLowerQualities(trackId: String, kept: StreamSettings) {
        val cache = cache()
        val playing = synchronized(lock) { trackId in openTracks }
        if (playing && pinMap[trackId] != kept) return
        qualities(kept)
            .filter { rank(it) < rank(kept) }
            .forEach { cache.removeResource(keyFor(trackId, it)) }
    }

    private fun trackMeteredNetwork() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        metered = cm.isActiveNetworkMetered
        cm.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val nowMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    if (nowMetered != metered) {
                        metered = nowMetered
                        repin()
                    }
                }
            },
        )
    }

    companion object {
        /** Direct play. Its codec/bitrate fields mean nothing, so every original compares equal. */
        private val ORIGINAL = StreamSettings(transcode = false)

        fun streamUri(trackId: String): String = "$STREAM_SCHEME://track/$trackId"

        fun isStreamUri(uri: Uri?): Boolean = uri?.scheme == STREAM_SCHEME

        fun trackIdOf(uri: Uri): String? = uri.lastPathSegment

        internal fun keyFor(trackId: String, quality: StreamSettings): String =
            if (quality.transcode) "$trackId|${quality.codec.name}-${quality.maxBitrateKbps}" else "$trackId|orig"

        /** The original outranks every transcode; transcodes rank by bitrate. */
        private fun rank(quality: StreamSettings): Int =
            if (quality.transcode) quality.maxBitrateKbps else Int.MAX_VALUE

        /** Every quality a track can be cached in, plus [desired] in case it isn't a listed option. */
        private fun qualities(desired: StreamSettings): List<StreamSettings> =
            listOf(ORIGINAL, desired) +
                AudioCodec.entries.flatMap { codec ->
                    STREAM_BITRATE_OPTIONS.map { StreamSettings(transcode = true, codec = codec, maxBitrateKbps = it) }
                }
    }
}
