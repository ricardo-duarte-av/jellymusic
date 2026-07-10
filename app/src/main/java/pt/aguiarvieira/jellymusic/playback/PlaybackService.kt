package pt.aguiarvieira.jellymusic.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
import kotlinx.coroutines.guava.future
import javax.inject.Inject

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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaLibrarySession

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

        mediaSession = MediaLibrarySession.Builder(this, player, LibraryCallback()).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession =
        mediaSession

    override fun onDestroy() {
        mediaSession.release()
        player.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

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
                val children = mediaItemTree.getChildren(parentId)
                LibraryResult.ofItemList(ImmutableList.copyOf(children), params)
            }
    }
}
