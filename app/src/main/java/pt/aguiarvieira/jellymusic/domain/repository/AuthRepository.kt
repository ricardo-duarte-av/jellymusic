package pt.aguiarvieira.jellymusic.domain.repository

import pt.aguiarvieira.jellymusic.domain.model.QuickConnectSession
import pt.aguiarvieira.jellymusic.domain.model.Server
import pt.aguiarvieira.jellymusic.domain.model.UserSession
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns everything about connecting to a Jellyfin server and authenticating.
 *
 * The authenticated [session] is the single source of truth for whether the user is signed in;
 * the rest of the app (and the [pt.aguiarvieira.jellymusic.data.jellyfin.JellyfinClientProvider]) reacts to it.
 */
interface AuthRepository {

    val session: StateFlow<UserSession?>

    /** Restore a previously persisted session (called once on app start). */
    suspend fun restoreSession()

    /** Best-effort LAN discovery of Jellyfin servers. Returns empty on failure. */
    suspend fun discoverServers(): List<Server>

    /**
     * Validate and normalize a manually entered address (handles missing scheme/port via the SDK's
     * address candidates + public system info).
     */
    suspend fun connect(input: String): Result<Server>

    suspend fun loginWithPassword(
        server: Server,
        username: String,
        password: String,
    ): Result<UserSession>

    /** Begin a Quick Connect handshake; the returned code is shown to the user. */
    suspend fun initiateQuickConnect(server: Server): Result<QuickConnectSession>

    /**
     * Poll a Quick Connect handshake. Returns a [UserSession] once approved, or `null` while still
     * pending.
     */
    suspend fun pollQuickConnect(server: Server, secret: String): Result<UserSession?>

    fun logout()
}
