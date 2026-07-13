package pt.aguiarvieira.jellymusic.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.PlayMethod
import pt.aguiarvieira.jellymusic.MainActivity
import pt.aguiarvieira.jellymusic.data.settings.QueueStore
import pt.aguiarvieira.jellymusic.data.settings.SettingsStore
import pt.aguiarvieira.jellymusic.domain.model.toTrack
import javax.inject.Inject

private const val PROGRESS_REPORT_INTERVAL_MS = 10_000L

/**
 * Max children returned for a single browse node. Android Auto requests all children in one Binder
 * transaction (see [PlaybackService.LibraryCallback.onGetChildren]); this keeps even the largest
 * node (Albums) safely under the ~1MB transaction limit.
 */
private const val MAX_CHILDREN_PER_NODE = 500

/**
 * The single [MediaLibraryService] that powers playback on the phone/tablet AND Android Auto. It
 * owns one [ExoPlayer] wrapped in a [MediaLibraryService.MediaLibrarySession]; the browse tree is
 * served from [MediaItemTree].
 */
@UnstableApi
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject
    lateinit var mediaItemTree: MediaItemTree

    @Inject
    lateinit var queueStore: QueueStore

    @Inject
    lateinit var settingsStore: SettingsStore

    @Inject
    lateinit var playbackReporter: PlaybackReporter

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaLibrarySession

    // Track being reported to the server, and its last observed position.
    private var reportedItemId: String? = null
    private var lastPositionMs: Long = 0L

    /** Reports playback lifecycle to Jellyfin (play counts, resume points, now-playing). */
    private val reporterListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            reportedItemId?.let { playbackReporter.reportStop(it, lastPositionMs) }
            reportedItemId = mediaItem?.mediaId?.removePrefix("track/")
            lastPositionMs = player.currentPosition
            reportedItemId?.let { playbackReporter.reportStart(it, lastPositionMs, currentPlayMethod()) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            lastPositionMs = player.currentPosition
            reportedItemId?.let { playbackReporter.reportProgress(it, lastPositionMs, !isPlaying, currentPlayMethod()) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                reportedItemId?.let { playbackReporter.reportStop(it, lastPositionMs) }
                reportedItemId = null
            }
        }
    }

    private fun currentPlayMethod(): PlayMethod {
        val extras = player.currentMediaItem?.mediaMetadata?.extras
        val transcode = StreamSettingsExtras.settingsFrom(extras).transcode
        val isLocal = StreamSettingsExtras.isLocal(extras)
        return if (transcode && !isLocal) PlayMethod.TRANSCODE else PlayMethod.DIRECT_PLAY
    }

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(reporterListener)

        mediaSession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(openPlayerPendingIntent())
            .build()

        // Periodic progress reports so the server's resume point/now-playing stays fresh.
        serviceScope.launch {
            while (isActive) {
                delay(PROGRESS_REPORT_INTERVAL_MS)
                if (player.isPlaying) {
                    lastPositionMs = player.currentPosition
                    reportedItemId?.let {
                        playbackReporter.reportProgress(it, lastPositionMs, isPaused = false, currentPlayMethod())
                    }
                }
            }
        }
    }

    /** Tapping the media notification opens the app on the full-screen player. */
    private fun openPlayerPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession =
        mediaSession

    override fun onDestroy() {
        reportedItemId?.let { playbackReporter.reportStop(it, player.currentPosition) }
        mediaSession.release()
        player.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        /** Resume from cold (Bluetooth/media button with no active session) using the saved queue. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = serviceScope.future {
            val saved = queueStore.load()
            if (saved == null || saved.items.isEmpty()) {
                throw UnsupportedOperationException("No saved queue to resume")
            }
            val settings = settingsStore.streamSettings.first()
            val items = saved.items.map { mediaItemTree.trackMediaItem(it.toTrack(), settings) }
            MediaSession.MediaItemsWithStartPosition(
                items,
                saved.index.coerceIn(0, items.lastIndex),
                saved.positionMs.coerceAtLeast(0L),
            )
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(mediaItemTree.rootItem(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            serviceScope.future {
                // Android Auto asks for every child in one call (page=0, pageSize=Int.MAX_VALUE), so the
                // whole list crosses the Binder in a single transaction. A large node (typically Albums)
                // exceeds the ~1MB transaction limit and throws TransactionTooLargeException, which surfaces
                // as an *empty* tab. Cap the payload to keep every node well under that limit.
                val children = mediaItemTree.getChildren(parentId).let {
                    if (it.size > MAX_CHILDREN_PER_NODE) it.subList(0, MAX_CHILDREN_PER_NODE) else it
                }
                LibraryResult.ofItemList(ImmutableList.copyOf(children), params)
            }
    }
}
