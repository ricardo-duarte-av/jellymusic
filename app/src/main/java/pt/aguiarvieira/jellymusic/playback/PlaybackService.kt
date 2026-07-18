package pt.aguiarvieira.jellymusic.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.PlayMethod
import pt.aguiarvieira.jellymusic.MainActivity
import pt.aguiarvieira.jellymusic.data.settings.QueueStore
import pt.aguiarvieira.jellymusic.data.settings.SettingsStore
import pt.aguiarvieira.jellymusic.domain.model.toTrack
import androidx.glance.appwidget.updateAll
import pt.aguiarvieira.jellymusic.widget.NowPlayingWidget
import pt.aguiarvieira.jellymusic.widget.nowPlayingWidgetData
import pt.aguiarvieira.jellymusic.widget.writeNowPlayingWidgetData
import javax.inject.Inject

private const val PROGRESS_REPORT_INTERVAL_MS = 10_000L

// Custom session commands backing the notification / Android Auto shuffle & repeat buttons.
private const val CMD_TOGGLE_SHUFFLE = "pt.aguiarvieira.jellymusic.TOGGLE_SHUFFLE"
private const val CMD_CYCLE_REPEAT = "pt.aguiarvieira.jellymusic.CYCLE_REPEAT"

/**
 * Max children returned for a single browse node. Android Auto requests all children in one Binder
 * transaction (see [PlaybackService.LibraryCallback.onGetChildren]); this keeps even the largest
 * node (Albums) safely under the ~1MB transaction limit.
 */
private const val MAX_CHILDREN_PER_NODE = 500

/** Browse nodes whose contents depend on the selected library; re-queried when it changes. */
private val LIBRARY_SCOPED_BROWSE_NODES = listOf(
    MediaItemTree.ROOT_ID,
    MediaItemTree.RECENTLY_PLAYED_ID,
    MediaItemTree.MOST_PLAYED_ID,
    MediaItemTree.RECENTLY_ADDED_ID,
    MediaItemTree.FAVORITES_ID,
    MediaItemTree.ALBUMS_ID,
    MediaItemTree.ARTISTS_ID,
    MediaItemTree.PLAYLISTS_ID,
)

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

    @Inject
    lateinit var authRepository: pt.aguiarvieira.jellymusic.domain.repository.AuthRepository

    @Inject
    lateinit var clientProvider: pt.aguiarvieira.jellymusic.data.jellyfin.JellyfinClientProvider

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaLibrarySession

    /** Applies per-track ReplayGain in the audio pipeline; gain is (re)set on transitions/settings. */
    private val gainProcessor = GainAudioProcessor()
    private var replayGainEnabled = true
    private var replayGainPreampDb = 0f

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

    /** Re-applies the ReplayGain level each time the playing track changes. */
    private val gainListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = applyGainForCurrentItem()
    }

    /**
     * Computes and pushes the effective gain for the current track into [gainProcessor]: the track's
     * Jellyfin normalization gain plus the manual preamp when ReplayGain is on, else unity (bypass).
     */
    private fun applyGainForCurrentItem() {
        val trackGainDb = StreamSettingsExtras.gainDbFrom(player.currentMediaItem?.mediaMetadata?.extras)
        gainProcessor.setGainDb(
            if (replayGainEnabled) (trackGainDb ?: 0f) + replayGainPreampDb else null,
        )
    }

    /**
     * Keeps the shuffle/repeat buttons' icons in sync with the player (whatever toggled them — the
     * in-app UI, the notification, or Android Auto) and persists the modes so they survive a restart.
     */
    private val modeListener = object : Player.Listener {
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = onPlaybackModesChanged()
        override fun onRepeatModeChanged(repeatMode: Int) = onPlaybackModesChanged()
    }

    private fun onPlaybackModesChanged() {
        mediaSession.setMediaButtonPreferences(mediaButtonPreferences())
        serviceScope.launch { settingsStore.setPlaybackModes(player.shuffleModeEnabled, player.repeatMode) }
    }

    /**
     * Mirrors the player's now-playing state into the home-screen widget's store and refreshes it.
     * The widget renders from that store (not a live player), so it must be kept current here — the
     * one place always alive during playback.
     */
    private val widgetListener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) = pushWidgetUpdate()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = pushWidgetUpdate()
        override fun onIsPlayingChanged(isPlaying: Boolean) = pushWidgetUpdate()
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = pushWidgetUpdate()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = pushWidgetUpdate()
        override fun onRepeatModeChanged(repeatMode: Int) = pushWidgetUpdate()
    }

    private fun pushWidgetUpdate() {
        val data = player.nowPlayingWidgetData()
        serviceScope.launch {
            // The write is what actually refreshes the widget: its composition collects this store as
            // state (see NowPlayingWidget), so a write recomposes the live session. updateAll() stays
            // as a belt-and-suspenders trigger for the case where no session is currently running.
            applicationContext.writeNowPlayingWidgetData(data)
            NowPlayingWidget().updateAll(applicationContext)
        }
    }

    /** The custom buttons (expanded-notification overflow + Android Auto), reflecting current state. */
    private fun mediaButtonPreferences(): List<CommandButton> = listOf(
        CommandButton.Builder(
            if (player.shuffleModeEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF,
        )
            .setDisplayName(if (player.shuffleModeEnabled) "Shuffle on" else "Shuffle off")
            .setSessionCommand(SessionCommand(CMD_TOGGLE_SHUFFLE, Bundle.EMPTY))
            // Overflow slot only → shows in the expanded notification (not the compact view) and Auto.
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(
            when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
                Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
                else -> CommandButton.ICON_REPEAT_OFF
            },
        )
            .setDisplayName(
                when (player.repeatMode) {
                    Player.REPEAT_MODE_ONE -> "Repeat one"
                    Player.REPEAT_MODE_ALL -> "Repeat all"
                    else -> "Repeat off"
                },
            )
            .setSessionCommand(SessionCommand(CMD_CYCLE_REPEAT, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
    )

    override fun onCreate() {
        super.onCreate()
        // Constant-bitrate seeking lets ADTS-AAC/MP3 files be scrubbed even though they carry no seek
        // index — this is what makes seeking work on downloaded (transcoded-AAC) tracks, which are
        // played as local progressive files. Without it, seekTo on those is a no-op.
        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true)
        // Install the ReplayGain processor in the audio pipeline via a custom sink. Force float
        // output so every bit depth (incl. 24-bit hi-res FLAC) reaches the processor as float PCM —
        // its float branch then normalizes all of them, and boost gains avoid 16-bit clip
        // quantization. Media3 falls back to 16-bit where the device can't do float, which the
        // processor's 16-bit branch still handles.
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(true)
                .setAudioProcessors(arrayOf(gainProcessor))
                .build()
        }
        player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, extractorsFactory))
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

        // Restore persisted shuffle/repeat, then keep them (and the buttons) in sync going forward.
        // Setting the modes here also refreshes the layout via [modeListener] when they differ from
        // the player's defaults.
        serviceScope.launch {
            val modes = settingsStore.playbackModes.first()
            player.shuffleModeEnabled = modes.shuffle
            player.repeatMode = modes.repeatMode
            mediaSession.setMediaButtonPreferences(mediaButtonPreferences())
        }
        player.addListener(modeListener)
        player.addListener(widgetListener)
        player.addListener(gainListener)
        pushWidgetUpdate()

        // Keep ReplayGain settings live: re-apply to the current track whenever the toggle or preamp
        // changes (and once on startup to seed the values).
        serviceScope.launch {
            settingsStore.replayGainSettings.collect { rg ->
                replayGainEnabled = rg.enabled
                replayGainPreampDb = rg.preampDb
                applyGainForCurrentItem()
            }
        }

        // When the active library changes (e.g. picked from Android Auto's "Libraries" node, or in
        // the app), the play-history/catalogue browse nodes now serve a different library — tell any
        // subscribed browser (Android Auto) to re-query them. Skip the initial emission on startup.
        serviceScope.launch {
            settingsStore.selectedLibrary
                .drop(1)
                .distinctUntilChanged()
                .collect {
                    LIBRARY_SCOPED_BROWSE_NODES.forEach {
                        mediaSession.notifyChildrenChanged(it, Int.MAX_VALUE, null)
                    }
                }
        }

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

    /**
     * Restores the persisted Jellyfin session if none is active. Idempotent and cheap when already
     * signed in; needed because the browse/playback entry points can run before (or without) the app
     * UI, which is otherwise the only thing that restores the session.
     */
    private suspend fun ensureSession() {
        if (clientProvider.session.value == null) authRepository.restoreSession()
    }

    override fun onDestroy() {
        reportedItemId?.let { playbackReporter.reportStop(it, player.currentPosition) }
        mediaSession.release()
        player.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        /** Advertise the custom shuffle/repeat commands and publish the initial button layout. */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(CMD_TOGGLE_SHUFFLE, Bundle.EMPTY))
                .add(SessionCommand(CMD_CYCLE_REPEAT, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setMediaButtonPreferences(mediaButtonPreferences())
                .build()
        }

        /** Toggle shuffle / cycle repeat when a button is tapped; [modeListener] refreshes the icons. */
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_TOGGLE_SHUFFLE -> player.shuffleModeEnabled = !player.shuffleModeEnabled
                CMD_CYCLE_REPEAT -> player.repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /** Resume from cold (Bluetooth/media button with no active session) using the saved queue. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = serviceScope.future {
            ensureSession()
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
                // Android Auto can start this service cold (car connected, app never opened), in which
                // case no Jellyfin session has been restored and every query returns empty. Restore it
                // before serving any browse request so the tree is populated without opening the app.
                ensureSession()
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
