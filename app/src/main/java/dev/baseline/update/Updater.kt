package dev.baseline.update

import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import dev.baseline.DEFAULT_UPDATES_URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

/**
 * Whether this build checks for updates. Debug and `-fake` builds are signed with another key and can never take a
 * real release, so they check only when pointed at a test release (-PupdatesUrl, tools/fake_garmin.py --release-apk).
 */
fun updatesEnabled(updatesUrl: String, debug: Boolean, versionName: String): Boolean =
    updatesUrl != DEFAULT_UPDATES_URL || (!debug && !versionName.endsWith("-fake"))

/** The download job as WorkManager reports it. [active] is false once it FAILED or was CANCELLED. */
data class DownloadWork(val active: Boolean, val done: Long = 0, val total: Long = 0)

/** What the updater needs from Android: WorkManager, PackageManager, PackageInstaller and Settings. A fake in tests. */
interface UpdatePlatform {
    val updatesDir: File
    val checking: Flow<Boolean>
    val downloadWork: Flow<DownloadWork?>

    fun scheduleChecks(on: Boolean)

    fun checkNow()

    fun startDownload(tag: String)

    fun cancelDownload()

    /** Whether the owner has allowed Baseline to install apps ("Install unknown apps"). */
    fun canInstall(): Boolean

    fun openInstallPermission()

    /** Bytes that can be written under [updatesDir], counting cache Android would clear to make room. */
    fun freeBytes(): Long

    fun archiveFacts(apk: File): ApkFacts?

    fun installedFacts(): ApkFacts

    /** Opens a PackageInstaller session for [apk] and commits it; the result arrives at [Updater.onInstallResult]. */
    fun install(apk: File): Int

    fun sessionExists(id: Int): Boolean

    fun abandonSession(id: Int)
}

sealed interface UpdateState {
    data object UpToDate : UpdateState

    /** A newer release is on offer; the subtypes are where the one-tap flow is. */
    sealed interface Offer : UpdateState {
        val release: Release
    }

    data class Available(override val release: Release) : Offer

    data class NeedsPermission(override val release: Release) : Offer

    data class Downloading(override val release: Release, val done: Long, val total: Long) : Offer

    data class Ready(override val release: Release) : Offer

    data class Installing(override val release: Release) : Offer

    data class Failed(override val release: Release, val message: String) : Offer
}

/** What Settings' App card and Today's banner draw. [enabled] is false in debug builds that weren't pointed at a test server. */
data class UpdateUi(
    val installed: String,
    val enabled: Boolean,
    val autoCheck: Boolean = true,
    val checking: Boolean = false,
    val checkedAt: Instant? = null,
    val checkError: String? = null,
    val state: UpdateState = UpdateState.UpToDate,
    val dismissedTag: String? = null,
) {
    /** Today's banner: any offer the owner hasn't dismissed. Dismissing hides that tag only. */
    val showBanner: Boolean get() = enabled && state is UpdateState.Offer && state.release.tag != dismissedTag
}

/**
 * One-tap updates from GitHub releases (spec: `2026-10-29-in-app-updates-design.md`). A periodic check offers a newer
 * release; [primary] walks it through permission, download (in [UpdateDownloadWorker]), verification and the system
 * installer. State lives in [prefs], so it survives the process restarts that a permission grant or an update causes.
 */
class Updater(
    private val prefs: UpdatePrefs,
    private val source: ReleaseSource,
    private val http: OkHttpClient,
    private val platform: UpdatePlatform,
    private val clock: Clock,
    private val installedVersion: String,
    val enabled: Boolean,
    private val scope: CoroutineScope,
    private val userAgent: String,
) {
    /** Serialises every phase change: taps, the download's end and installer results must not interleave. */
    private val lock = Mutex()
    private val foreground = MutableStateFlow(false)
    private val _userAction = MutableStateFlow<Intent?>(null)

    /** The session this process committed, and the one whose dialog it launched; both forgotten with the process. */
    @Volatile private var committedSession: Int? = null

    @Volatile private var launchedSession: Int? = null

    /** The system's "Update this app?" screen, waiting for a visible activity to launch it ([MainActivity]). */
    val userAction: StateFlow<Intent?> = _userAction.asStateFlow()

    val ui: StateFlow<UpdateUi> =
        combine(prefs.record, platform.checking, platform.downloadWork) { r, checking, work ->
            UpdateUi(installedVersion, enabled, r.autoCheck, checking, r.checkedAt, r.checkError, stateOf(r, work), r.dismissedTag)
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), UpdateUi(installedVersion, enabled))

    /** Called once from Application.onCreate: tidies up after an update or a killed process, then keeps the schedule in step. */
    fun start() {
        scope.launch { guarded("start") { onAppStart() } }
        scope.launch {
            prefs.record.map { it.autoCheck && enabled }.distinctUntilChanged().collect { on ->
                guarded("schedule") { platform.scheduleChecks(on) }
            }
        }
        // One tap means the install follows the download by itself, but only while the app is on screen (spec §6).
        scope.launch {
            combine(prefs.record, foreground) { r, fg -> r.release != null && r.phase == Phase.READY && fg }
                .distinctUntilChanged()
                .filter { it }
                .collect { guarded("install") { lock.withLock { installLocked() } } }
        }
    }

    /**
     * MainActivity's onResume/onPause. Resuming picks up a flow that was waiting for the install permission, and frees
     * an install nobody will answer: Back on Android's dialog doesn't always send a status.
     */
    fun onForeground(visible: Boolean) {
        foreground.value = visible
        if (!visible) return
        scope.launch {
            guarded("resume") {
                lock.withLock {
                    val r = prefs.current()
                    when {
                        r.release == null -> {}

                        r.phase == Phase.NEEDS_PERMISSION && platform.canInstall() -> startLocked(r.release)

                        r.phase == Phase.INSTALLING && installAbandonedLocked(r) -> {
                            r.sessionId?.let { if (platform.sessionExists(it)) platform.abandonSession(it) }
                            prefs.setPhase(Phase.FAILED, "Update cancelled.")
                        }
                    }
                }
            }
        }
    }

    /**
     * Back in the app while INSTALLING: true once no answer can still come. Not while a dialog waits to be launched,
     * nor while this process's commit hasn't been answered; yes once its dialog was shown (the owner is back without
     * finishing it), or when the session is gone, or when this process never had the session's intent.
     */
    private fun installAbandonedLocked(r: UpdateRecord): Boolean {
        val id = r.sessionId ?: return true
        if (_userAction.value != null) return false
        if (!platform.sessionExists(id)) return true
        return launchedSession == id || committedSession != id
    }

    /** The one button: Update, Allow, Cancel, Install or Retry, depending on the state. */
    fun primary() {
        scope.launch {
            guarded("primary") {
                lock.withLock {
                    val r = prefs.current()
                    val release = r.release ?: return@withLock
                    when (r.phase) {
                        Phase.AVAILABLE, Phase.FAILED, Phase.NEEDS_PERMISSION -> startLocked(release)

                        // Retry when the job died under us (shown as Failed), Cancel while it's running.
                        Phase.DOWNLOADING -> if (platform.downloadWork.first()?.active == false) startLocked(release) else cancelLocked()

                        Phase.READY -> installLocked()

                        Phase.INSTALLING -> {}
                    }
                }
            }
        }
    }

    fun dismiss(tag: String) {
        scope.launch { guarded("dismiss") { prefs.dismiss(tag) } }
    }

    fun checkNow() = platform.checkNow()

    fun setAutoCheck(on: Boolean) {
        scope.launch { guarded("auto") { prefs.setAutoCheck(on) } }
    }

    /** MainActivity launched [userAction]; it must not be launched twice. */
    fun userActionLaunched() {
        _userAction.value = null
        launchedSession = committedSession
    }

    /** [UpdateCheckWorker]: one look at the latest release. */
    suspend fun check() {
        val result = source.latest(prefs.current().etag)
        lock.withLock {
            val now = Instant.now(clock)
            when (result) {
                LatestRelease.NotModified -> prefs.checked(now, null)

                is LatestRelease.Failed -> prefs.checked(now, result.message)

                is LatestRelease.Found -> {
                    val offered = prefs.current().release
                    if (offered != null && !offered.sameAsset(result.release)) {
                        // A newer release, or the same tag with a re-uploaded APK, replaces the one in hand: its download
                        // and any verified file are for other bytes.
                        platform.cancelDownload()
                        prune(keepTag = null)
                    }
                    if (isNewer(result.release.tag, installedVersion)) prefs.offer(result.release) else prefs.clearRelease()
                    prefs.checked(now, null, result.etag)
                }
            }
        }
    }

    /** [UpdateDownloadWorker]: fetches and verifies [tag]'s APK; leaves the phase READY or FAILED. */
    suspend fun download(tag: String, attempt: String, progress: suspend (done: Long, total: Long) -> Unit) {
        val release = prefs.current().release?.takeIf { it.tag == tag } ?: return
        val dir = platform.updatesDir.apply { mkdirs() }
        // One file per attempt: a cancelled job still unwinding must not delete the file its replacement is writing.
        val part = File(dir, "$tag-$attempt.part")
        val apk = apkFile(tag)
        try {
            val needed = release.apkSize * 2 + SPACE_MARGIN
            if (platform.freeBytes() < needed) return failDownload(tag, "Not enough free space: the update needs about ${megabytes(needed)} MB.")
            val (size, sha) = fetch(release, part, progress)
            val problem = verifyApk(release, size, sha, platform.archiveFacts(part), platform.installedFacts())
            if (problem != null) {
                part.delete()
                return failDownload(tag, problem)
            }
            apk.delete()
            if (!part.renameTo(apk)) return failDownload(tag, "Couldn't save the update.")
            lock.withLock {
                val r = prefs.current()
                if (r.release?.tag == tag && r.phase == Phase.DOWNLOADING) prefs.setPhase(Phase.READY) else apk.delete()
            }
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: IOException) {
            part.delete()
            Log.w(TAG, "download: ${e.javaClass.simpleName}: ${e.message}")
            failDownload(tag, "Download failed. Check the connection and try again.")
        } catch (e: Exception) {
            // PackageManager can throw on a damaged archive; the job must still end in a state the owner can retry from.
            part.delete()
            Log.w(TAG, "verify: ${e.javaClass.simpleName}: ${e.message}")
            failDownload(tag, "Couldn't check the download. Try again.")
        }
    }

    /** [InstallResultReceiver]: PackageInstaller's answer to a commit. A successful self-update usually kills the process first. */
    suspend fun onInstallResult(status: Int, message: String?, action: Intent?) {
        lock.withLock {
            if (prefs.current().phase != Phase.INSTALLING) return
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION ->
                    if (action != null) _userAction.value = action else prefs.setPhase(Phase.FAILED, "Android didn't offer the install screen.")

                PackageInstaller.STATUS_SUCCESS -> {}

                PackageInstaller.STATUS_FAILURE_ABORTED -> prefs.setPhase(Phase.FAILED, "Update cancelled.")

                else -> prefs.setPhase(Phase.FAILED, "Android couldn't install the update" + (message?.let { ": $it" } ?: "."))
            }
        }
    }

    internal suspend fun onAppStart() {
        lock.withLock {
            val r = prefs.current()
            val release = r.release
            when {
                // This process is the update (or a newer manual install): the offer is done.
                release == null || !isNewer(release.tag, installedVersion) -> {
                    // Files first: once the offer is cleared, nothing points at them any more.
                    prune(keepTag = null)
                    if (release != null) prefs.clearRelease()
                }

                // The process died after a commit whose session is gone: no answer will come. A live session is left
                // alone, because the installer's answer may be what started this process; onForeground frees it later.
                r.phase == Phase.INSTALLING && r.sessionId?.let(platform::sessionExists) != true ->
                    prefs.setPhase(Phase.FAILED, "The install didn't finish. Try again.")

                r.phase == Phase.READY && !apkFile(release.tag).exists() -> prefs.setPhase(Phase.AVAILABLE)
            }
        }
    }

    private suspend fun startLocked(release: Release) {
        if (!platform.canInstall()) {
            platform.openInstallPermission()
            prefs.setPhase(Phase.NEEDS_PERMISSION)
            return
        }
        prune(keepTag = release.tag)
        if (apkFile(release.tag).exists()) {
            // Verified earlier (only verified files get this name); the install was cancelled or failed.
            prefs.setPhase(Phase.READY)
            return
        }
        platform.startDownload(release.tag)
        prefs.setPhase(Phase.DOWNLOADING)
    }

    private suspend fun cancelLocked() {
        platform.cancelDownload()
        prune(keepTag = null)
        prefs.setPhase(Phase.AVAILABLE)
    }

    private suspend fun installLocked() {
        val r = prefs.current()
        val release = r.release ?: return
        if (r.phase != Phase.READY || !foreground.value) return
        val apk = apkFile(release.tag)
        if (!apk.exists()) return prefs.setPhase(Phase.AVAILABLE)
        prefs.setPhase(Phase.INSTALLING)
        try {
            val session = withContext(Dispatchers.IO) { platform.install(apk) }
            committedSession = session
            prefs.setPhase(Phase.INSTALLING, sessionId = session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "install: ${e.javaClass.simpleName}: ${e.message}")
            prefs.setPhase(Phase.FAILED, "Couldn't start the installer.")
        }
    }

    private suspend fun failDownload(tag: String, message: String) {
        lock.withLock {
            val r = prefs.current()
            if (r.release?.tag == tag && r.phase == Phase.DOWNLOADING) prefs.setPhase(Phase.FAILED, message)
        }
    }

    /** Streams the APK to [part], hashing as it goes. The size is capped at the release's, so a wrong answer can't fill the disk. */
    private suspend fun fetch(release: Release, part: File, progress: suspend (Long, Long) -> Unit): Pair<Long, String> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(release.apkUrl).header("User-Agent", userAgent).build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            var reportedAt = 0L
            r.body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > release.apkSize) throw IOException("larger than the release's ${release.apkSize} bytes")
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        // At most a few times a second: each report is a WorkManager write and a notification update.
                        val now = System.nanoTime()
                        if (now - reportedAt >= PROGRESS_EVERY_NS) {
                            reportedAt = now
                            progress(total, release.apkSize)
                        }
                    }
                }
            }
            progress(total, release.apkSize)
            total to digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private fun apkFile(tag: String) = File(platform.updatesDir, "$tag.apk")

    /** Same tag and the same bytes: a re-uploaded APK under an old tag is a different offer. */
    private fun Release.sameAsset(other: Release) = tag == other.tag && apkUrl == other.apkUrl && apkSize == other.apkSize && sha256 == other.sha256

    /** Deletes downloads other than [keepTag]'s finished APK. */
    private fun prune(keepTag: String?) {
        platform.updatesDir.listFiles()?.forEach { if (keepTag == null || it.name != "$keepTag.apk") it.delete() }
    }

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun stateOf(r: UpdateRecord, work: DownloadWork?): UpdateState {
        val release = r.release ?: return UpdateState.UpToDate
        return when (r.phase) {
            Phase.AVAILABLE -> UpdateState.Available(release)

            Phase.NEEDS_PERMISSION -> UpdateState.NeedsPermission(release)

            // No work info yet means it was only just enqueued.
            Phase.DOWNLOADING -> if (work?.active == false) {
                UpdateState.Failed(release, "Download stopped. Try again.")
            } else {
                UpdateState.Downloading(release, work?.done ?: 0, work?.total?.takeIf { it > 0 } ?: release.apkSize)
            }

            Phase.READY -> UpdateState.Ready(release)

            Phase.INSTALLING -> UpdateState.Installing(release)

            Phase.FAILED -> UpdateState.Failed(release, r.failure ?: "Update failed.")
        }
    }

    private companion object {
        const val TAG = "Updater"

        /** Headroom on top of twice the APK (the download plus the installer's copy). */
        const val SPACE_MARGIN = 32L shl 20
        const val PROGRESS_EVERY_NS = 500_000_000L
    }
}
