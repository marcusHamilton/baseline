package dev.baseline.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.baseline.BaselineApp
import kotlinx.coroutines.CancellationException

/** The daily (and "Check now") look at GitHub's latest release. A failed check is state, not a retry: the next run tries again. */
class UpdateCheckWorker(ctx: Context, params: WorkerParameters, private val updater: Updater) : CoroutineWorker(ctx, params) {
    /** WorkManager's reflective constructor. */
    constructor(ctx: Context, params: WorkerParameters) : this(ctx, params, (ctx.applicationContext as BaselineApp).container.updater)

    override suspend fun doWork(): Result {
        updater.check()
        return Result.success()
    }
}

/**
 * Downloads and verifies one release's APK. Like SyncWorker it starts expedited and then runs as a dataSync foreground
 * service: expedited work alone has a time budget that a 55 MB download on a slow network can overrun.
 */
class UpdateDownloadWorker(ctx: Context, params: WorkerParameters, private val updater: Updater) : CoroutineWorker(ctx, params) {
    /** WorkManager's reflective constructor. */
    constructor(ctx: Context, params: WorkerParameters) : this(ctx, params, (ctx.applicationContext as BaselineApp).container.updater)

    override suspend fun doWork(): Result {
        val tag = inputData.getString(RELEASE_TAG) ?: return Result.failure()
        var foreground = goForeground(tag, 0, 0)
        updater.download(tag, id.toString()) { done, total ->
            setProgress(workDataOf(DONE to done, TOTAL to total))
            // One refusal (background start limits) ends the notification updates; the download carries on.
            if (foreground) foreground = goForeground(tag, done, total)
        }
        return Result.success()
    }

    /** Expedited work on API < 31 asks for this before doWork starts. */
    override suspend fun getForegroundInfo() = foregroundInfo(inputData.getString(RELEASE_TAG).orEmpty(), 0, 0)

    private suspend fun goForeground(tag: String, done: Long, total: Long): Boolean = try {
        setForeground(foregroundInfo(tag, done, total))
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private fun foregroundInfo(tag: String, done: Long, total: Long): ForegroundInfo {
        val percent = percentOf(done, total)
        val n =
            NotificationCompat
                .Builder(applicationContext, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentText("Downloading Baseline ${tag.removePrefix("v")}")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, percent, total <= 0)
                .build()
        return ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val RELEASE_TAG = "tag"
        const val DONE = "done"
        const val TOTAL = "total"
        private const val CHANNEL = "updates"
        private const val NOTIFICATION_ID = 2

        /** Called once from Application.onCreate, next to SyncWorker's channel. */
        fun createChannel(context: Context) {
            context
                .getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_LOW))
        }
    }
}
