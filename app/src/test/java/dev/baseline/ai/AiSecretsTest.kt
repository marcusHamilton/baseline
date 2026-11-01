package dev.baseline.ai

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import dev.baseline.AppContainer
import dev.baseline.ai.engine.AiFailure
import dev.baseline.ai.engine.AiRequest
import dev.baseline.ai.engine.ChatTurn
import dev.baseline.ai.engine.CloudEngine
import dev.baseline.ai.engine.NoTools
import dev.baseline.ai.engine.RequestKind
import dev.baseline.ai.engine.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

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

    /** A provider's error body can echo the key; none of it may reach the exception. */
    @Test
    fun errorBodyNeverSurfaces() = runBlocking {
        val server = MockWebServer().also { it.start() }
        val file = File.createTempFile("aisecrets", ".preferences_pb").also { it.delete() }
        try {
            val vault = AiKeyVault(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO)) { file }, { it }, { it })
            vault.save(server.url("/v1/"), "test-key", "m/odel", toolsSupported = false)
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key test-key"}}"""))
            val engine = CloudEngine(OkHttpClient(), vault, NoTools, { true })
            val request = AiRequest(RequestKind.CHAT, "s", "p", listOf(ChatTurn(Role.USER, "hi")))
            try {
                engine.run(request).toList()
                fail("expected KeyRejected")
            } catch (e: AiFailure) {
                assertNull(e.message)
                assertFalse(e.toString().contains("test-key"))
            }
        } finally {
            server.shutdown()
            file.delete()
        }
    }
}
