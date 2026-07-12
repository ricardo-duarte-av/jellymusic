package pt.aguiarvieira.jellymusic.data.jellyfin

import pt.aguiarvieira.jellymusic.data.auth.CredentialStore
import pt.aguiarvieira.jellymusic.data.settings.QueueStore
import pt.aguiarvieira.jellymusic.domain.model.UserSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.discovery.DiscoveryService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central holder for the Jellyfin SDK instance and the currently active [ApiClient].
 *
 * The active session is exposed as a [StateFlow] so repositories, the player, and the UI all react
 * to sign-in / sign-out from one place.
 */
@Singleton
class JellyfinClientProvider @Inject constructor(
    private val jellyfin: Jellyfin,
    private val credentialStore: CredentialStore,
    private val queueStore: QueueStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _session = MutableStateFlow<UserSession?>(null)
    val session: StateFlow<UserSession?> = _session.asStateFlow()

    @Volatile
    private var _api: ApiClient? = null

    /** The authenticated API client, or null when signed out. */
    val api: ApiClient? get() = _api

    val discovery: DiscoveryService get() = jellyfin.discovery

    /** An unauthenticated client for a given base URL (used during the connect/login flow). */
    fun unauthenticatedApi(baseUrl: String): ApiClient =
        jellyfin.createApi(baseUrl = baseUrl)

    /** Activate an authenticated session; subsequent [api] calls use its token. */
    fun activateSession(session: UserSession) {
        _api = jellyfin.createApi(
            baseUrl = session.serverUrl,
            accessToken = session.accessToken,
        )
        _session.value = session
    }

    fun clear() {
        _api = null
        _session.value = null
        // A queue belongs to the signed-in session; drop the persisted copy on sign-out/expiry.
        scope.launch { queueStore.clear() }
    }

    /**
     * The server rejected our access token (401). Drop the session *and* its persisted credentials
     * so we don't restore the dead token on next launch; the app reacts to [session] becoming null.
     */
    fun invalidateSession() {
        credentialStore.clear()
        clear()
    }
}
