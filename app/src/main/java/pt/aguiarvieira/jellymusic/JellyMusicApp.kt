package pt.aguiarvieira.jellymusic

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Coil's OkHttp network fetcher auto-registers via service loading (the `coil-network-okhttp`
 * artifact ships the service file + ProGuard keep rules), so no explicit ImageLoader setup is
 * needed for `AsyncImage` to load remote artwork.
 */
@HiltAndroidApp
class JellyMusicApp : Application()
