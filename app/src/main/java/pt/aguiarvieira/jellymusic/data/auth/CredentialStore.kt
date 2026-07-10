package pt.aguiarvieira.jellymusic.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import pt.aguiarvieira.jellymusic.domain.model.UserSession
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the authenticated [UserSession] (including the access token) in
 * [EncryptedSharedPreferences] so it survives process death without exposing the token in plain
 * text.
 */
@Singleton
class CredentialStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(): UserSession? {
        val serverUrl = prefs.getString(KEY_SERVER_URL, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        val userId = prefs.getString(KEY_USER_ID, null) ?: return null
        return UserSession(
            serverUrl = serverUrl,
            serverName = prefs.getString(KEY_SERVER_NAME, "") ?: "",
            userId = userId,
            userName = prefs.getString(KEY_USER_NAME, "") ?: "",
            accessToken = token,
        )
    }

    fun save(session: UserSession) {
        prefs.edit()
            .putString(KEY_SERVER_URL, session.serverUrl)
            .putString(KEY_SERVER_NAME, session.serverName)
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_USER_NAME, session.userName)
            .putString(KEY_TOKEN, session.accessToken)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val FILE_NAME = "jellymusic_credentials"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_SERVER_NAME = "server_name"
        const val KEY_USER_ID = "user_id"
        const val KEY_USER_NAME = "user_name"
        const val KEY_TOKEN = "access_token"
    }
}
