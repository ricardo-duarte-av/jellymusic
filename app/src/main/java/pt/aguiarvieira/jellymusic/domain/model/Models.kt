package pt.aguiarvieira.jellymusic.domain.model

/** A Jellyfin server, either discovered on the LAN or entered/validated manually. */
data class Server(
    val id: String?,
    val name: String,
    val address: String,
)

/** An authenticated user session against a specific server. */
data class UserSession(
    val serverUrl: String,
    val serverName: String,
    val userId: String,
    val userName: String,
    val accessToken: String,
)

/** In-progress Quick Connect handshake: the [code] is shown to the user, [secret] is polled. */
data class QuickConnectSession(
    val code: String,
    val secret: String,
)

/** A browsable music library (Jellyfin "view" of collectionType == music). */
data class MusicLibrary(
    val id: String,
    val name: String,
) {
    companion object {
        /** Sentinel representing "browse everything across all music libraries". */
        const val ALL_ID = "__all__"

        fun all(): MusicLibrary = MusicLibrary(ALL_ID, "All music")
    }
}
