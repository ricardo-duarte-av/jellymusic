package pt.aguiarvieira.jellymusic.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

private const val CHANNEL_ID = "downloads"
private const val NOTIFICATION_ID = 42

/**
 * Runs the download queue in the background via WorkManager, so downloads continue when the app is
 * backgrounded and resume automatically after the process is killed. Dependencies are resolved
 * through a Hilt [EntryPoint] (no hilt-work / custom WorkManager init required).
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

        ensureChannel()
        setForeground(foregroundInfo("Preparing downloads…"))

        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        processor.processAll { track ->
            manager?.notify(NOTIFICATION_ID, buildNotification("Downloading ${track.title}"))
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

    private fun foregroundInfo(text: String): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIFICATION_ID, buildNotification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, buildNotification(text))
        }

    companion object {
        const val WORK_NAME = "music_downloads"
    }
}
