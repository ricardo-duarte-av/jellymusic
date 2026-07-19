package pt.aguiarvieira.jellymusic.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

private const val CHANNEL_ID = "downloads"
private const val NOTIFICATION_ID = 42

/**
 * Runs the download queue as a regular WorkManager background job, so downloads continue when the
 * app is backgrounded and resume automatically after the process is killed. Progress is surfaced
 * through an ordinary (non-foreground) notification.
 *
 * This intentionally does NOT run as a foreground service: user-initiated media downloads don't need
 * one, and avoiding it keeps us off the `FOREGROUND_SERVICE_DATA_SYNC` permission (which Google Play
 * flags and discourages for this use case). Per-track state is persisted to Room as each file
 * completes, so if the system stops the worker (execution-time limit, lost constraints) the pending
 * tracks stay queued and WorkManager reruns to finish them. Dependencies are resolved through a Hilt
 * [EntryPoint] (no hilt-work / custom WorkManager init required).
 */
class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun downloadProcessor(): DownloadProcessor
    }

    override suspend fun doWork(): Result {
        val processor = EntryPointAccessors
            .fromApplication(applicationContext, Deps::class.java)
            .downloadProcessor()

        val scope = runCatching {
            DownloadScope.valueOf(inputData.getString(KEY_SCOPE) ?: DownloadScope.MANUAL.name)
        }.getOrDefault(DownloadScope.MANUAL)

        ensureChannel()
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification("Preparing downloads…"))
        try {
            processor.processAll(scope) { track ->
                manager?.notify(NOTIFICATION_ID, buildNotification("Downloading ${track.title}"))
            }
        } finally {
            // Not a foreground service, so the notification isn't auto-removed; clear it ourselves.
            manager?.cancel(NOTIFICATION_ID)
        }
        return Result.success()
    }

    private fun ensureChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("JellyMusic")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .build()

    companion object {
        const val WORK_NAME = "music_downloads"
        const val FAVORITE_WORK_NAME = "music_favorite_downloads"
        const val KEY_SCOPE = "scope"
    }
}
