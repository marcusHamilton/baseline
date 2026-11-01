package dev.baseline.ai

import androidx.test.core.app.ApplicationProvider
import dev.baseline.AppContainer
import okhttp3.Authenticator
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AiSecretsTest {
    /** The AI key goes out per request, only to its own host: the client itself carries nothing of Garmin's or GitHub's. */
    @Test
    fun aiClientCarriesNothing() {
        val container = AppContainer(ApplicationProvider.getApplicationContext())
        try {
            assertSame(Authenticator.NONE, container.aiHttp.authenticator)
            assertTrue(container.aiHttp.interceptors.isEmpty())
            assertTrue(container.aiHttp.networkInterceptors.isEmpty())
            assertNotSame(container.updateHttp, container.aiHttp)
        } finally {
            container.close()
        }
    }
}
