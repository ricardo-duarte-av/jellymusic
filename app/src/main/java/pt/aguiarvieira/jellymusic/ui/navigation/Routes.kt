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

    /** Search across songs / albums / artists / playlists. */
    @Serializable
    object Search

    @Serializable
    object Settings

    /** Offline downloads manager. */
    @Serializable
    object Downloads

    /** Per-version list of changes (hand-maintained). */
    @Serializable
    object Changelog

    /** App info: version, source links. */
    @Serializable
    object About
}
