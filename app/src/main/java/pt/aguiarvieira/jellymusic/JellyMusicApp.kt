package pt.aguiarvieira.jellymusic

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.util.DebugLogger
import dagger.hilt.android.HiltAndroidApp

/**
 * Coil's OkHttp network fetcher auto-registers via service loading (the `coil-network-okhttp`
 * artifact ships the service file + ProGuard keep rules), so no explicit ImageLoader setup is
 * needed for `AsyncImage` to load remote artwork.
 *
 * We override [newImageLoader] only for **debug** builds (the screenshot CI job):
 *  - a [DebugLogger] so each artwork request/result is visible in logcat, and
 *  - the disk cache disabled — on the emulator, the burst of ~28 concurrent search-result
 *    thumbnail writes contends the Coil `DiskLruCache` journal lock ("Long monitor contention"
 *    in logcat) and those images never finish loading, leaving grey rows. Skipping the disk
 *    cache removes that bottleneck. Release builds keep the default loader (disk cache on).
 */
@HiltAndroidApp
class JellyMusicApp : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .apply {
                if (BuildConfig.DEBUG) {
                    logger(DebugLogger())
                    diskCachePolicy(CachePolicy.DISABLED)
                }
            }
            .build()
}
