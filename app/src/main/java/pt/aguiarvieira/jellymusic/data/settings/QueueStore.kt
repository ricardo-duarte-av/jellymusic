package pt.aguiarvieira.jellymusic.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import pt.aguiarvieira.jellymusic.domain.model.PersistedQueue
import javax.inject.Inject
import javax.inject.Singleton

private val Context.queueDataStore by preferencesDataStore(name = "jellymusic_queue")

/** Persists the play queue so it can be restored after the process is killed. */
@Singleton
class QueueStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun save(queue: PersistedQueue) {
        context.queueDataStore.edit { it[KEY] = json.encodeToString(PersistedQueue.serializer(), queue) }
    }

    suspend fun load(): PersistedQueue? {
        val raw = context.queueDataStore.data.first()[KEY] ?: return null
        return runCatching { json.decodeFromString(PersistedQueue.serializer(), raw) }.getOrNull()
    }

    suspend fun clear() {
        context.queueDataStore.edit { it.remove(KEY) }
    }

    private companion object {
        val KEY = stringPreferencesKey("queue_json")
        val json = Json { ignoreUnknownKeys = true }
    }
}
