package pt.aguiarvieira.jellymusic.data.auth

import pt.aguiarvieira.jellymusic.core.util.Logx
import pt.aguiarvieira.jellymusic.data.jellyfin.JellyfinClientProvider
import pt.aguiarvieira.jellymusic.domain.model.QuickConnectSession
import pt.aguiarvieira.jellymusic.domain.model.Server
import pt.aguiarvieira.jellymusic.domain.model.UserSession
import pt.aguiarvieira.jellymusic.domain.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.operations.QuickConnectApi
import org.jellyfin.sdk.api.operations.SystemApi
import org.jellyfin.sdk.api.operations.UserApi
import org.jellyfin.sdk.model.api.AuthenticateUserByName
import org.jellyfin.sdk.model.api.QuickConnectDto
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val clientProvider: JellyfinClientProvider,
    private val credentialStore: CredentialStore,
) : AuthRepository {

    override val session: StateFlow<UserSession?> = clientProvider.session

    override suspend fun restoreSession(): Unit = withContext(Dispatchers.IO) {
        credentialStore.load()?.let { clientProvider.activateSession(it) }
        Unit
    }

    override suspend fun discoverServers(): List<Server> = withContext(Dispatchers.IO) {
        runCatching {
            clientProvider.discovery.discoverLocalServers().toList().map {
                Server(id = it.id, name = it.name, address = it.address)
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun connect(input: String): Result<Server> = withContext(Dispatchers.IO) {
        val candidates = runCatching { clientProvider.discovery.getAddressCandidates(input) }
            .getOrElse {
                Logx.w(TAG, "getAddressCandidates failed for \"$input\"", it)
                listOf(input)
            }
        Logx.d(TAG, "connect(\"$input\") candidates=$candidates")

        var lastError: Throwable? = null
        for (candidate in candidates) {
            val result = runCatching {
                val api = clientProvider.unauthenticatedApi(candidate)
                val info = SystemApi(api).getPublicSystemInfo().content
                Server(
                    id = info.id,
                    name = info.serverName ?: candidate,
                    address = candidate,
                )
            }
            result
                .onSuccess { Logx.d(TAG, "connect ok via $candidate -> ${it.name}") }
                .onFailure {
                    lastError = it
                    Logx.w(TAG, "connect attempt failed for $candidate: ${it.message}", it)
                }
            if (result.isSuccess) return@withContext result
        }
        // Surface the real reason (SSL / DNS / engine / timeout) instead of a generic message.
        val reason = lastError?.let { "${it::class.simpleName}: ${it.message}" } ?: "unknown error"
        Result.failure(IllegalStateException("Couldn't reach a Jellyfin server at \"$input\" ($reason)"))
    }

    override suspend fun loginWithPassword(
        server: Server,
        username: String,
        password: String,
    ): Result<UserSession> = withContext(Dispatchers.IO) {
        runCatching {
            val api = clientProvider.unauthenticatedApi(server.address)
            val auth = UserApi(api)
                .authenticateUserByName(AuthenticateUserByName(username = username, pw = password))
                .content
            auth.toSession(server).also { activateAndPersist(it) }
        }
    }

    override suspend fun initiateQuickConnect(server: Server): Result<QuickConnectSession> =
        withContext(Dispatchers.IO) {
            runCatching {
                val api = clientProvider.unauthenticatedApi(server.address)
                val state = QuickConnectApi(api).initiateQuickConnect().content
                QuickConnectSession(
                    code = state.code ?: error("Server returned no Quick Connect code"),
                    secret = state.secret ?: error("Server returned no Quick Connect secret"),
                )
            }
        }

    override suspend fun pollQuickConnect(
        server: Server,
        secret: String,
    ): Result<UserSession?> = withContext(Dispatchers.IO) {
        runCatching {
            val api = clientProvider.unauthenticatedApi(server.address)
            val state = QuickConnectApi(api).getQuickConnectState(secret).content
            if (!state.authenticated) {
                null
            } else {
                val auth = UserApi(api)
                    .authenticateWithQuickConnect(QuickConnectDto(secret = secret))
                    .content
                auth.toSession(server).also { activateAndPersist(it) }
            }
        }
    }

    override fun logout() {
        credentialStore.clear()
        clientProvider.clear()
    }

    private fun activateAndPersist(session: UserSession) {
        credentialStore.save(session)
        clientProvider.activateSession(session)
    }

    private fun org.jellyfin.sdk.model.api.AuthenticationResult.toSession(server: Server): UserSession {
        val user = user ?: error("Authentication returned no user")
        return UserSession(
            serverUrl = server.address,
            serverName = server.name,
            userId = user.id.toString(),
            userName = user.name ?: "",
            accessToken = accessToken ?: error("Authentication returned no access token"),
        )
    }

    private companion object {
        const val TAG = "JellyMusicAuth"
    }
}
