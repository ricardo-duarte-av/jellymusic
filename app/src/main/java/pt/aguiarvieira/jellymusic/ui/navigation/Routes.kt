package pt.aguiarvieira.jellymusic.ui.navigation

import kotlinx.serialization.Serializable

/** Type-safe navigation destinations. */
object Routes {

    @Serializable
    object ConnectServer

    @Serializable
    data class Login(
        val serverId: String?,
        val serverName: String,
        val serverAddress: String,
    )

    @Serializable
    object LibraryPicker

    /** The browse shell (Albums / Artists / Playlists) — the app's main surface. */
    @Serializable
    object Home

    @Serializable
    data class AlbumDetail(val albumId: String, val albumName: String)

    @Serializable
    data class ArtistDetail(val artistId: String, val artistName: String)

    @Serializable
    data class PlaylistDetail(val playlistId: String, val playlistName: String)

    /** Full-screen now-playing. */
    @Serializable
    object Player
}
