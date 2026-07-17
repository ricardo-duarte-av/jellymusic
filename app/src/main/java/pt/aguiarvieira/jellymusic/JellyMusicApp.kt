package pt.aguiarvieira.jellymusic

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.util.DebugLogger
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.Dispatchers

/**
 * Coil's OkHttp network fetcher auto-registers via service loading (the `coil-network-okhttp`
 * artifact ships the service file + ProGuard keep rules), so no explicit ImageLoader setup is
 * needed for `AsyncImage` to load remote artwork.
 *
 * We override [newImageLoader] only for **debug** builds (the screenshot CI job): a
 * [DebugLogger], plus limited fetch/decode parallelism. The search screen fires ~28 concurrent
 * thumbnail loads at once; on the emulator that burst appears to overwhelm Coil's pipeline and
 * most rows never render (no network error — they just don't finish), while the album grid
 * (~6 images) is fine. Capping parallelism serialises the burst. Release builds keep the
 * default loader.
 */
@HiltAndroidApp
class JellyMusicApp : Application(), SingletonImageLoader.Factory {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .apply {
                if (BuildConfig.DEBUG) {
                    logger(DebugLogger())
                    fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(3))
                    decoderCoroutineContext(Dispatchers.IO.limitedParallelism(3))
                }
            }
            .build()
}
