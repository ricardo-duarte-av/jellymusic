package pt.aguiarvieira.jellymusic.data.repository

import pt.aguiarvieira.jellymusic.data.jellyfin.JellyfinClientProvider
import pt.aguiarvieira.jellymusic.domain.model.MusicLibrary
import pt.aguiarvieira.jellymusic.domain.repository.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.operations.UserViewsApi
import org.jellyfin.sdk.model.api.CollectionType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepositoryImpl @Inject constructor(
    private val clientProvider: JellyfinClientProvider,
) : LibraryRepository {

    override suspend fun getMusicLibraries(): Result<List<MusicLibrary>> =
        withContext(Dispatchers.IO) {
            val api = clientProvider.api
                ?: return@withContext Result.failure(IllegalStateException("Not signed in"))
            runCatching {
                UserViewsApi(api).getUserViews().content.items
                    .filter { it.collectionType == CollectionType.MUSIC }
                    .map { MusicLibrary(id = it.id.toString(), name = it.name ?: "Library") }
            }
        }
}
