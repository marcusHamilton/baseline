package dev.baseline.update

import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.baseline.testutil.ScopeCleanup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private class FakePlatform(override val updatesDir: File) : UpdatePlatform {
    override val checking = MutableStateFlow(false)
    override val downloadWork = MutableStateFlow<DownloadWork?>(null)
    val scheduled = mutableListOf<Boolean>()
    val downloads = mutableListOf<String>()
    var cancels = 0

    @Volatile var allowed = true
    var permissionOpened = 0
    var archive: ApkFacts? = ApkFacts("dev.baseline", 5, "0.05", setOf("key"))
    var installed = ApkFacts("dev.baseline", 4, "0.04", setOf("key"))
    val installs = mutableListOf<File>()
    val liveSessions = mutableSetOf<Int>()
    val abandoned = mutableListOf<Int>()
    private var nextSession = 7

    override fun scheduleChecks(on: Boolean) {
        synchronized(this) { scheduled += on }
    }

    override fun checkNow() {}

    override fun startDownload(tag: String) {
        synchronized(this) { downloads += tag }
        downloadWork.value = DownloadWork(true)
    }

    override fun cancelDownload() {
        synchronized(this) { cancels++ }
        downloadWork.value = DownloadWork(false)
    }

    override fun canInstall() = allowed

    override fun openInstallPermission() {
        synchronized(this) { permissionOpened++ }
    }

    @Volatile var free = Long.MAX_VALUE

    override fun freeBytes() = free

    override fun archiveFacts(apk: File) = archive

    override fun installedFacts() = installed

    override fun install(apk: File): Int = synchronized(this) {
        installs += apk
        nextSession++.also { liveSessions += it }
    }

    override fun sessionExists(id: Int) = id in liveSessions

    override fun abandonSession(id: Int) {
        synchronized(this) {
            abandoned += id
            liveSessions -= id
        }
    }
}

@RunWith(RobolectricTestRunner::class)
class UpdaterTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-29T12:00:00Z"), ZoneOffset.UTC)
    private val apk = ByteArray(300_000) { (it % 251).toByte() }
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }

    @Volatile private var tag = "v0.05"

    @Volatile private var servedSha = apkSha

    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private lateinit var scopes: ScopeCleanup
    private lateinit var prefs: UpdatePrefs
    private lateinit var platform: FakePlatform
    private val updaterScopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("updater").toFile()
        scopes = ScopeCleanup()
        prefs = UpdatePrefs(PreferenceDataStoreFactory.create(scope = scopes.storeScope) { File(dir, "u.preferences_pb") })
        platform = FakePlatform(File(dir, "updates"))
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/latest" -> MockResponse().setBody(
                        """{"tag_name":"$tag","html_url":"https://x/$tag","body":"- Faster","assets":[{"name":"Baseline.apk","size":${apk.size},""" +
                            """"digest":"sha256:$servedSha","browser_download_url":"${server.url("/Baseline.apk")}"}]}""",
                    )

                    "/Baseline.apk" -> MockResponse().setBody(Buffer().write(apk))

                    else -> MockResponse().setResponseCode(404)
                }
            }
        server.start()
    }

    @After
    fun tearDown() = runBlocking {
        updaterScopes.forEach { it.cancel() }
        scopes.close()
        server.shutdown()
        dir.deleteRecursively()
        Unit
    }

    private fun updater(installedVersion: String = "0.04", enabled: Boolean = true): Updater {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { updaterScopes += it }
        val http = OkHttpClient()
        return Updater(
            prefs,
            ReleaseSource(
                http,
                server.url("/latest").toString(),
                "Baseline/test",
            ),
            http, platform, clock, installedVersion, enabled, scope, "Baseline/test",
        )
            .also { it.start() }
    }

    private suspend fun Updater.awaitState(test: (UpdateUi) -> Boolean): UpdateUi = withTimeout(5_000) { ui.first(test) }

    private suspend fun eventually(test: suspend () -> Boolean) = withTimeout(5_000) {
        while (!test()) delay(10)
    }

    private suspend fun Updater.toReady() {
        check()
        primary()
        awaitState { it.state is UpdateState.Downloading }
        download(tag, "attempt-1") { _, _ -> }
    }

    @Test
    fun aNewerReleaseIsOfferedAndAnEqualOneClearsTheOffer() = runBlocking {
        val u = updater()
        u.check()
        val offered = u.awaitState { it.state is UpdateState.Available }
        assertEquals("v0.05", (offered.state as UpdateState.Available).release.tag)
        assertEquals("• Faster", offered.state.let { (it as UpdateState.Available).release.notes })
        assertEquals(clock.instant(), offered.checkedAt)
        assertTrue(offered.showBanner)

        tag = "v0.04"
        u.check()
        u.awaitState { it.state == UpdateState.UpToDate }
        Unit
    }

    @Test
    fun aFailedCheckIsShownAndTheOfferKept() = runBlocking {
        val u = updater()
        u.check()
        u.awaitState { it.state is UpdateState.Available }
        server.shutdown()
        u.check()
        val ui = u.awaitState { it.checkError != null }
        assertEquals("Couldn't reach GitHub", ui.checkError)
        assertTrue(ui.state is UpdateState.Available)
    }

    @Test
    fun theInstallPermissionIsAskedForBeforeTheDownload() = runBlocking {
        platform.allowed = false
        val u = updater()
        u.check()
        u.primary()
        u.awaitState { it.state is UpdateState.NeedsPermission }
        assertEquals(1, platform.permissionOpened)
        assertTrue(platform.downloads.isEmpty())

        // Coming back from Settings with the permission granted carries on by itself.
        platform.allowed = true
        u.onForeground(true)
        u.awaitState { it.state is UpdateState.Downloading }
        assertEquals(listOf("v0.05"), platform.downloads)
    }

    @Test
    fun oneTapDownloadsVerifiesAndInstallsWhileOnScreen() = runBlocking {
        val u = updater()
        u.toReady()
        val ready = u.awaitState { it.state is UpdateState.Ready }
        assertTrue(File(platform.updatesDir, "v0.05.apk").exists())
        assertFalse(File(platform.updatesDir, "v0.05.apk.part").exists())
        assertTrue(platform.installs.isEmpty()) // not on screen yet
        assertTrue(ready.showBanner)

        u.onForeground(true)
        u.awaitState { it.state is UpdateState.Installing }
        eventually { prefs.current().sessionId == 7 }
        assertEquals(listOf(File(platform.updatesDir, "v0.05.apk")), platform.installs)
    }

    @Test
    fun theFirstUpdateHandsTheSystemDialogToTheActivity() = runBlocking {
        val u = updater()
        u.onForeground(true)
        u.toReady()
        eventually { platform.installs.size == 1 }
        eventually { prefs.current().sessionId != null }
        val dialog = Intent("android.content.pm.action.CONFIRM_INSTALL")
        u.onInstallResult(PackageInstaller.STATUS_PENDING_USER_ACTION, null, dialog)
        assertEquals(dialog, u.userAction.value)
        u.userActionLaunched()
        assertEquals(null, u.userAction.value)
    }

    @Test
    fun aCancelledInstallCanBeRetriedWithoutDownloadingAgain() = runBlocking {
        val u = updater()
        u.onForeground(true)
        u.toReady()
        eventually { prefs.current().sessionId != null }
        u.onInstallResult(PackageInstaller.STATUS_FAILURE_ABORTED, null, null)
        assertEquals(UpdateState.Failed(release(), "Update cancelled."), u.awaitState { it.state is UpdateState.Failed }.state)

        u.primary()
        eventually { platform.installs.size == 2 }
        assertEquals(listOf("v0.05"), platform.downloads)
    }

    @Test
    fun anInstallerFailureIsShownWithItsMessage() = runBlocking {
        val u = updater()
        u.onForeground(true)
        u.toReady()
        eventually { prefs.current().sessionId != null }
        u.onInstallResult(PackageInstaller.STATUS_FAILURE_CONFLICT, "INSTALL_FAILED_UPDATE_INCOMPATIBLE", null)
        val failed = u.awaitState { it.state is UpdateState.Failed }.state as UpdateState.Failed
        assertEquals("Android couldn't install the update: INSTALL_FAILED_UPDATE_INCOMPATIBLE", failed.message)
    }

    @Test
    fun aDownloadThatDoesNotMatchTheChecksumIsThrownAway() = runBlocking {
        servedSha = "00".repeat(32)
        val u = updater()
        u.onForeground(true)
        u.toReady()
        val failed = u.awaitState { it.state is UpdateState.Failed }.state as UpdateState.Failed
        assertEquals("Download didn't match the release checksum.", failed.message)
        assertTrue(platform.updatesDir.listFiles().isNullOrEmpty())
        assertTrue(platform.installs.isEmpty())
    }

    @Test
    fun aDifferentlySignedApkIsNeverInstalled() = runBlocking {
        platform.archive = platform.archive!!.copy(signers = setOf("someone else"))
        val u = updater()
        u.onForeground(true)
        u.toReady()
        val failed = u.awaitState { it.state is UpdateState.Failed }.state as UpdateState.Failed
        assertEquals("Release 0.05 isn't signed with Baseline's key.", failed.message)
        assertTrue(platform.installs.isEmpty())
    }

    @Test
    fun aFullPhoneFailsBeforeDownloadingAnything() = runBlocking {
        platform.free = 500_000
        val u = updater()
        u.toReady()
        val failed = u.awaitState { it.state is UpdateState.Failed }.state as UpdateState.Failed
        // Twice the APK (the download and the installer's copy) plus 32 MiB of headroom: what the check really asks for.
        assertEquals("Not enough free space: the update needs about 34 MB.", failed.message)
        assertEquals(1, server.requestCount) // the release check only, no download
    }

    @Test
    fun cancelStopsTheDownload() = runBlocking {
        val u = updater()
        u.check()
        u.primary()
        u.awaitState { it.state is UpdateState.Downloading }
        u.primary()
        u.awaitState { it.state is UpdateState.Available }
        assertEquals(1, platform.cancels)
        // A late finish from the cancelled job doesn't resurrect it.
        u.download("v0.05", "attempt-1") { _, _ -> }
        assertTrue(u.ui.value.state is UpdateState.Available)
        assertTrue(platform.updatesDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun aDownloadJobThatDiedCanBeRetried() = runBlocking {
        val u = updater()
        u.check()
        u.primary()
        u.awaitState { it.state is UpdateState.Downloading }
        platform.downloadWork.value = DownloadWork(false) // WorkManager gave up on it
        u.awaitState { it.state is UpdateState.Failed }
        u.primary()
        eventually { platform.downloads.size == 2 }
        u.awaitState { it.state is UpdateState.Downloading }
        Unit
    }

    @Test
    fun aNewerReleaseReplacesOneHalfWayThrough() = runBlocking {
        val u = updater()
        u.check()
        u.primary()
        u.awaitState { it.state is UpdateState.Downloading }
        tag = "v0.06"
        u.check()
        val ui = u.awaitState { (it.state as? UpdateState.Available)?.release?.tag == "v0.06" }
        assertEquals(1, platform.cancels)
        assertTrue(ui.showBanner)
    }

    @Test
    fun dismissingHidesTheBannerForThatReleaseOnly() = runBlocking {
        val u = updater()
        u.check()
        u.awaitState { it.showBanner }
        u.dismiss("v0.05")
        val hidden = u.awaitState { !it.showBanner }
        assertTrue(hidden.state is UpdateState.Available) // still in Settings
        tag = "v0.06"
        u.check()
        u.awaitState { it.showBanner }
        Unit
    }

    @Test
    fun theUpdatedAppClearsTheOfferAndItsFiles() = runBlocking {
        val before = updater()
        before.toReady()
        before.awaitState { it.state is UpdateState.Ready }
        updaterScopes.forEach { it.cancel() }

        // The process after the update: its version is the offered one.
        val after = updater(installedVersion = "0.05")
        after.awaitState { it.state == UpdateState.UpToDate }
        assertTrue(platform.updatesDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun aProcessKilledMidInstallWithTheSessionGoneOffersARetry() = runBlocking {
        val before = updater()
        before.onForeground(true)
        before.toReady()
        eventually { prefs.current().sessionId == 7 }
        updaterScopes.forEach { it.cancel() }
        platform.liveSessions.clear() // no answer can come now

        val after = updater()
        val failed = after.awaitState { it.state is UpdateState.Failed }.state as UpdateState.Failed
        assertEquals("The install didn't finish. Try again.", failed.message)
    }

    @Test
    fun aColdStartByTheInstallersAnswerKeepsTheSessionAndDeliversIt() = runBlocking {
        val before = updater()
        before.onForeground(true)
        before.toReady()
        eventually { prefs.current().sessionId == 7 }
        updaterScopes.forEach { it.cancel() }

        // The installer's broadcast starts a new process: start() runs first, then the answer arrives.
        val after = updater()
        delay(200)
        assertTrue(after.ui.value.state !is UpdateState.Failed)
        assertTrue(platform.abandoned.isEmpty())
        val dialog = Intent("android.content.pm.action.CONFIRM_INSTALL")
        after.onInstallResult(PackageInstaller.STATUS_PENDING_USER_ACTION, null, dialog)
        assertEquals(dialog, after.userAction.value)
        // Opening the app with the dialog still waiting to be shown doesn't throw it away.
        after.onForeground(true)
        delay(200)
        assertEquals(Phase.INSTALLING, prefs.current().phase)
        assertTrue(platform.abandoned.isEmpty())
    }

    @Test
    fun aSessionThisProcessCanNeverFinishIsFreedOnResume() = runBlocking {
        val before = updater()
        before.onForeground(true)
        before.toReady()
        eventually { prefs.current().sessionId == 7 }
        updaterScopes.forEach { it.cancel() }

        // New process, no answer delivered, and the owner opens the app: nothing can finish that session.
        val after = updater()
        after.onForeground(true)
        val failed = after.awaitState { it.state is UpdateState.Failed }.state as UpdateState.Failed
        assertEquals("Update cancelled.", failed.message)
        assertEquals(listOf(7), platform.abandoned)
    }

    @Test
    fun backingOutOfTheSystemDialogFreesTheInstall() = runBlocking {
        val u = updater()
        u.onForeground(true)
        u.toReady()
        eventually { prefs.current().sessionId == 7 }
        // Resuming before the installer answers must not abandon the commit.
        u.onForeground(true)
        delay(200)
        assertTrue(platform.abandoned.isEmpty())

        u.onInstallResult(PackageInstaller.STATUS_PENDING_USER_ACTION, null, Intent("android.content.pm.action.CONFIRM_INSTALL"))
        u.userActionLaunched()
        u.onForeground(false)
        // Back on Android's dialog: no status arrives, and the owner is back in the app.
        u.onForeground(true)
        val failed = u.awaitState { it.state is UpdateState.Failed }.state as UpdateState.Failed
        assertEquals("Update cancelled.", failed.message)
        assertEquals(listOf(7), platform.abandoned)
        // Retry installs the verified file again.
        u.primary()
        eventually { platform.installs.size == 2 }
    }

    @Test
    fun aReUploadedApkUnderTheSameTagIsDownloadedAgain() = runBlocking {
        val u = updater()
        u.toReady()
        u.awaitState { it.state is UpdateState.Ready }
        servedSha = "11".repeat(32) // same tag, new bytes on GitHub
        u.check()
        u.awaitState { it.state is UpdateState.Available }
        assertTrue(platform.updatesDir.listFiles().isNullOrEmpty())
        u.primary()
        eventually { platform.downloads.size == 2 }
    }

    @Test
    fun eachDownloadAttemptWritesItsOwnFile() = runBlocking {
        val u = updater()
        u.check()
        u.primary()
        u.awaitState { it.state is UpdateState.Downloading }
        var seen: List<String>? = null
        u.download("v0.05", "attempt-2") { _, _ -> seen = platform.updatesDir.list()?.toList() }
        // A cancelled attempt-1 unwinding can only ever delete its own file, never this one.
        assertEquals(listOf("v0.05-attempt-2.part"), seen)
    }

    @Test
    fun theDailyCheckFollowsTheSwitchAndStaysOffWhenDisabled() = runBlocking {
        val u = updater()
        eventually { platform.scheduled == listOf(true) }
        u.setAutoCheck(false)
        eventually { platform.scheduled == listOf(true, false) }
        updaterScopes.forEach { it.cancel() }

        platform.scheduled.clear()
        val debug = updater(enabled = false)
        eventually { platform.scheduled.isNotEmpty() }
        assertEquals(listOf(false), platform.scheduled)
        assertFalse(debug.awaitState { true }.showBanner)
    }

    @Test
    fun onlyAVerifiedFileCountsAsReady() = runBlocking {
        val u = updater()
        u.check()
        // A stray partial file from a killed download is never installed.
        platform.updatesDir.mkdirs()
        File(platform.updatesDir, "v0.05.apk.part").writeBytes(ByteArray(10))
        u.primary()
        u.awaitState { it.state is UpdateState.Downloading }
        assertNotNull(platform.downloads.singleOrNull())
        assertFalse(File(platform.updatesDir, "v0.05.apk.part").exists())
    }

    private fun release() = Release("v0.05", "• Faster", "https://x/v0.05", server.url("/Baseline.apk").toString(), apk.size.toLong(), apkSha)
}
