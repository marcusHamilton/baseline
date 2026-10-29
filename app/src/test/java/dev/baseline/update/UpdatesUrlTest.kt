package dev.baseline.update

import androidx.test.core.app.ApplicationProvider
import dev.baseline.AppContainer
import dev.baseline.BuildConfig
import dev.baseline.DEFAULT_UPDATES_URL
import okhttp3.Authenticator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdatesUrlTest {
    /** -PupdatesUrl (tools/fake_garmin.py --release-apk) is for emulator builds; without it the app checks the real repo. */
    @Test
    fun defaultBuildChecksTheRealRepo() {
        assertEquals("https://api.github.com/repos/marcusHamilton/baseline/releases/latest", BuildConfig.UPDATES_URL)
        assertEquals(DEFAULT_UPDATES_URL, BuildConfig.UPDATES_URL)
    }

    @Test
    fun onlyRealReleaseBuildsCheckGitHub() {
        val real = DEFAULT_UPDATES_URL
        val fake = "http://127.0.0.1:8080/repos/marcusHamilton/baseline/releases/latest"
        assertTrue(updatesEnabled(real, debug = false, versionName = "0.04"))
        // A debug key or a -fake build can never install a real release, so it doesn't offer one.
        assertFalse(updatesEnabled(real, debug = true, versionName = "0.04"))
        assertFalse(updatesEnabled(real, debug = false, versionName = "0.04-fake"))
        // Pointed at a test release, anything checks: that's the emulator update test.
        assertTrue(updatesEnabled(fake, debug = true, versionName = "0.05-fake"))
    }

    /** GitHub traffic must never pick up the Garmin authenticator or the debug logger: no Garmin credential may reach it. */
    @Test
    fun theUpdateClientCarriesNothingOfGarmins() {
        val container = AppContainer(ApplicationProvider.getApplicationContext())
        try {
            assertSame(Authenticator.NONE, container.updateHttp.authenticator)
            assertTrue(container.updateHttp.interceptors.isEmpty())
            assertTrue(container.updateHttp.networkInterceptors.isEmpty())
        } finally {
            container.close()
        }
    }
}
