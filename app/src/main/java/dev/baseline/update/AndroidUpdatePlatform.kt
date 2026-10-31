package dev.baseline.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** [UpdatePlatform] on a real device. [workManager] is lazy: building the updater must not initialise WorkManager. */
class AndroidUpdatePlatform(private val context: Context, workManager: () -> WorkManager) : UpdatePlatform {
    private val work by lazy(workManager)

    override val updatesDir = File(context.noBackupFilesDir, "updates")

    override val checking: Flow<Boolean>
        get() = work.getWorkInfosForUniqueWorkFlow(CHECK_NOW).map { infos ->
            infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        }

    override val downloadWork: Flow<DownloadWork?>
        get() = work.getWorkInfosForUniqueWorkFlow(DOWNLOAD).map { infos ->
            val info = infos.lastOrNull() ?: return@map null
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED ->
                    DownloadWork(true, info.progress.getLong(UpdateDownloadWorker.DONE, 0), info.progress.getLong(UpdateDownloadWorker.TOTAL, 0))

                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> DownloadWork(false)

                // The worker sets READY or FAILED before it succeeds, so a finished job says nothing more.
                WorkInfo.State.SUCCEEDED -> null
            }
        }

    override fun scheduleChecks(on: Boolean) {
        if (!on) {
            work.cancelUniqueWork(CHECK_PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS).setConstraints(connected()).build()
        work.enqueueUniquePeriodicWork(CHECK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** No network constraint: offline, the check fails at once with "Couldn't reach GitHub" instead of waiting. */
    override fun checkNow() {
        val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>().build()
        work.enqueueUniqueWork(CHECK_NOW, ExistingWorkPolicy.KEEP, request)
    }

    /** Any network: the owner tapped Update, and the button shows the size. Expedited to start now; the worker then goes foreground. */
    override fun startDownload(tag: String) {
        val request =
            OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
                .setInputData(workDataOf(UpdateDownloadWorker.RELEASE_TAG to tag))
                .setConstraints(connected())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
        work.enqueueUniqueWork(DOWNLOAD, ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancelDownload() {
        work.cancelUniqueWork(DOWNLOAD)
    }

    override fun canInstall() = context.packageManager.canRequestPackageInstalls()

    /** Settings › Install unknown apps › Baseline. Called while Baseline is on screen, so the activity start is allowed. */
    override fun openInstallPermission() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Unknown (an IOException resolving the volume) counts as enough: the download then fails on its own if it isn't. */
    override fun freeBytes(): Long = runCatching {
        val storage = context.getSystemService(StorageManager::class.java)
        storage.getAllocatableBytes(storage.getUuidForPath(updatesDir.apply { mkdirs() }))
    }.getOrDefault(Long.MAX_VALUE)

    override fun archiveFacts(apk: File): ApkFacts? {
        val pm = context.packageManager
        val info =
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            }
        return info?.facts()
    }

    override fun installedFacts(): ApkFacts {
        val pm = context.packageManager
        val info =
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            }
        return info.facts()
    }

    override fun install(apk: File): Int {
        val installer = context.packageManager.packageInstaller
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
                setInstallReason(PackageManager.INSTALL_REASON_USER)
                // Skips "Update this app?" once Baseline is its own installer of record; the system may still ask (spec §6).
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                // Mutable: the installer fills in the status extras. The intent is explicit, so nothing else can receive it.
                val status =
                    PendingIntent.getBroadcast(
                        context,
                        id,
                        Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                session.commit(status.intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(id)
            throw e
        }
        return id
    }

    override fun sessionExists(id: Int) = context.packageManager.packageInstaller.getSessionInfo(id) != null

    override fun abandonSession(id: Int) {
        runCatching { context.packageManager.packageInstaller.abandonSession(id) }
    }

    private fun connected() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun PackageInfo.facts() = ApkFacts(
        packageName,
        longVersionCode,
        versionName,
        signingInfo?.apkContentsSigners?.map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }?.toSet(),
    )

    companion object {
        const val CHECK_PERIODIC = "update-check"
        const val CHECK_NOW = "update-check-now"
        const val DOWNLOAD = "update-download"
    }
}
