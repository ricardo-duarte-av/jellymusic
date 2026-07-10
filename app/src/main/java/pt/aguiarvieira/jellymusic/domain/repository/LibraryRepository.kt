package pt.aguiarvieira.jellymusic.domain.repository

import pt.aguiarvieira.jellymusic.domain.model.MusicLibrary

interface LibraryRepository {
    /** The music libraries (Jellyfin views with collectionType == music) for the current user. */
    suspend fun getMusicLibraries(): Result<List<MusicLibrary>>
}
