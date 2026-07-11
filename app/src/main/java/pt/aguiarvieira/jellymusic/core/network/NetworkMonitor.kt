package pt.aguiarvieira.jellymusic.core.network

import android.content.Context
import android.net.ConnectivityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Thin wrapper over [ConnectivityManager] to tell whether the active network is metered. */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val connectivityManager: ConnectivityManager?
        get() = context.getSystemService(ConnectivityManager::class.java)

    /**
     * True when the active connection is metered (typically mobile data). Falls back to `true`
     * (the cautious choice) when connectivity can't be determined, so the user is still warned.
     */
    fun isActiveNetworkMetered(): Boolean =
        connectivityManager?.isActiveNetworkMetered ?: true
}
