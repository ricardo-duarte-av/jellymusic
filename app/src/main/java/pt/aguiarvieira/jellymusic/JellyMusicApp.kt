package pt.aguiarvieira.jellymusic

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.util.DebugLogger
import dagger.hilt.android.HiltAndroidApp

/**
 * Coil's OkHttp network fetcher auto-registers via service loading (the `coil-network-okhttp`
 * artifact ships the service file + ProGuard keep rules), so no explicit ImageLoader setup is
 * needed for `AsyncImage` to load remote artwork.
 *
 * We only override [newImageLoader] to attach a [DebugLogger] in debug builds — it logs each
 * artwork request/result (and the failure cause) under the `coil3` tag, which is what the
 * screenshot CI job greps to diagnose why some thumbnails don't load. Release builds get the
 * plain loader (no logger).
 */
@HiltAndroidApp
class JellyMusicApp : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .apply { if (BuildConfig.DEBUG) logger(DebugLogger()) }
            .build()
}
