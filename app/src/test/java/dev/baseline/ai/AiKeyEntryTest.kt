package dev.baseline.ai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.baseline.testutil.ScopeCleanup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class AiKeyEntryTest {
    private val cleanup = ScopeCleanup()
    private val clients = mutableListOf<OkHttpClient>()
    private lateinit var server: MockWebServer
    private lateinit var file: File
    private lateinit var store: DataStore<Preferences>
    private lateinit var vault: AiKeyVault
    private lateinit var prefs: AiPrefs
    private var nano = true
    private val model = "deepseek/deepseek-v4.1-flash"
    private val key = "sk-or-test-key"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        file = File.createTempFile("aikeyentry", ".preferences_pb").also { it.delete() }
        store = PreferenceDataStoreFactory.create(scope = cleanup.storeScope) { file }
        vault = AiKeyVault(store, { it }, { it })
        prefs = AiPrefs(store)
    }

    @After
    fun tearDown() {
        runBlocking { cleanup.close() }
        clients.forEach {
            it.dispatcher.executorService.shutdown()
            it.connectionPool.evictAll()
        }
        server.shutdown()
        file.delete()
    }

    // No silent resend of a request that died on the wire: each disconnect should cost exactly one queued response.
    // A fresh client per entry, so a connection one case broke is never reused by the next; tearDown shuts them all down.
    private fun entry(): AiKeyEntry {
        val http = OkHttpClient.Builder().retryOnConnectionFailure(false).build().also { clients += it }
        return AiKeyEntry(http, vault, prefs, { nano }, allowLoopbackHttp = true)
    }

    private fun base() = parseBaseUrl(server.url("/v1").toString(), allowLoopbackHttp = true)!!

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private val toolCall =
        """{"choices":[{"message":{"role":"assistant","content":null,""" +
            """"tool_calls":[{"id":"c1","type":"function","function":{"name":"ping","arguments":"{}"}}]}}]}"""
    private val plainText = """{"choices":[{"message":{"role":"assistant","content":"pong"}}]}"""

    private fun RecordedRequest.bodyJson(): JsonObject = Json.parseToJsonElement(body.readUtf8()) as JsonObject

    @Test
    fun parsesAndValidatesBaseUrl() {
        assertNotNull(parseBaseUrl("https://openrouter.ai/api/v1", false))
        assertNull(parseBaseUrl("http://openrouter.ai/api/v1", false))
        assertNull(parseBaseUrl("http://127.0.0.1:8090/v1", false))
        assertNotNull(parseBaseUrl("http://127.0.0.1:8090/v1", true))
        assertNotNull(parseBaseUrl("http://localhost:8090/v1", true))
        assertNull(parseBaseUrl("http://10.0.2.2/v1", true))
        assertNull(parseBaseUrl("not a url", false))
        assertNull(parseBaseUrl("", false))
        assertEquals("https://openrouter.ai/api/v1", parseBaseUrl("  https://openrouter.ai/api/v1/ ", false).toString())
    }

    @Test
    fun trailingSlashBaseUrlJoinsCleanly() = runBlocking {
        val url = parseBaseUrl(server.url("/v1/").toString(), true)!!
        server.enqueue(json("""{"data":[{"id":"m"}]}"""))
        server.enqueue(json(toolCall))
        entry().listModels(url, key)
        assertEquals("/v1/models", server.takeRequest().path)
        entry().saveAndTest(url, key, model)
        assertEquals("/v1/chat/completions", server.takeRequest().path)
    }

    @Test
    fun trimsKeyAndRejectsInnerWhitespace() {
        val e = entry()
        assertEquals("sk-or-abc", e.cleanKey("  sk-or-abc\n"))
        assertNull(e.cleanKey("sk or abc"))
        assertNull(e.cleanKey("  "))
        assertNull(e.cleanKey(""))
    }

    @Test
    fun listsOpenRouterModelsToolsFirst() = runBlocking {
        server.enqueue(
            json(
                """{"data":[
                {"id":"zeta/plain","context_length":8000,"pricing":{"prompt":"0.000001","completion":"0.000002"},
                "supported_parameters":["temperature"]},
                {"id":"$model","context_length":128000,"pricing":{"prompt":"0.000000045","completion":"0.0000005"},"supported_parameters":["tools","temperature"]},
                {"id":"alpha/tools","context_length":32000,"pricing":{"prompt":"0.000003","completion":"0.000015"},"supported_parameters":["tools"]}
                ]}""",
            ),
        )
        val result = entry().listModels(base(), key)
        val listed = (result as ModelsResult.Listed).models
        assertEquals(listOf("alpha/tools", model, "zeta/plain"), listed.map { it.id })
        assertEquals(listOf(true, true, false), listed.map { it.tools })
        val deepseek = listed[1]
        assertEquals(128000, deepseek.contextLength)
        assertEquals(0.0007, deepseek.pricePerQuestion!!, 1e-9)
        assertEquals("≈ $0.0007 per question", formatPricePerQuestion(0.0007))
        assertEquals("≈ $0.072 per question", formatPricePerQuestion(0.0721))
        assertEquals("Bearer $key", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun modelsWithoutMetadataOrFailingFallBackToFreeText() = runBlocking {
        server.enqueue(json("""{"data":[{"id":"m"}]}"""))
        val bare = (entry().listModels(base(), key) as ModelsResult.Listed).models.single()
        assertEquals("m", bare.id)
        assertNull(bare.contextLength)
        assertNull(bare.promptPrice)
        assertNull(bare.completionPrice)
        assertNull(bare.tools)
        assertNull(bare.pricePerQuestion)

        server.enqueue(json("oops", 500))
        assertEquals(ModelsResult.FreeText, entry().listModels(base(), key))
        server.enqueue(json("""{"data":[]}"""))
        assertEquals(ModelsResult.FreeText, entry().listModels(base(), key))
        server.enqueue(json("not json"))
        assertEquals(ModelsResult.FreeText, entry().listModels(base(), key))
        server.enqueue(json("{}", 401))
        assertEquals(ModelsResult.KeyRejected, entry().listModels(base(), key))
        server.enqueue(json("{}", 403))
        assertEquals(ModelsResult.KeyRejected, entry().listModels(base(), key))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(ModelsResult.Unreachable(server.hostName), entry().listModels(base(), key))
    }

    @Test
    fun listModelsCleansTheKey() = runBlocking {
        server.enqueue(json("""{"data":[{"id":"m"}]}"""))
        assertTrue(entry().listModels(base(), "  sk-test\n") is ModelsResult.Listed)
        assertEquals("Bearer sk-test", server.takeRequest().getHeader("Authorization"))

        assertEquals(ModelsResult.KeyRejected, entry().listModels(base(), "sk test"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun toolCallSavesWithTools() = runBlocking {
        prefs.setMode(AiMode.ON_DEVICE)
        server.enqueue(json(toolCall))
        val outcome = entry().saveAndTest(base(), key, model)
        assertEquals(TestOutcome.Saved(true), outcome)
        val config = vault.config()!!
        assertEquals(base(), config.baseUrl)
        assertEquals(key, config.key)
        assertEquals(model, config.model)
        assertTrue(config.toolsSupported)
        assertEquals(AiMode.HYBRID, prefs.mode.first())

        val request = server.takeRequest()
        assertEquals("Bearer $key", request.getHeader("Authorization"))
        val body = request.bodyJson()
        assertEquals(model, body["model"]!!.jsonPrimitive.content)
        assertEquals("required", body["tool_choice"]!!.jsonPrimitive.content)
        assertEquals(false, body["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(16, body["max_tokens"]!!.jsonPrimitive.int)
        assertEquals("ping", body["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray
        assertEquals("Call the ping tool.", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("ping", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun noToolCallSavesWithoutTools() = runBlocking {
        server.enqueue(json(plainText))
        assertEquals(TestOutcome.Saved(false), entry().saveAndTest(base(), key, model))
        assertEquals(false, vault.config()!!.toolsSupported)
        // No mode was set and Nano is supported, so the transition picks HYBRID.
        assertEquals(AiMode.HYBRID, prefs.mode.first())
    }

    @Test
    fun requiredRejectedFallsBackToAutoThenNoTools() = runBlocking {
        server.enqueue(json("""{"error":{"message":"bad tool_choice"}}""", 400))
        server.enqueue(json("""{"error":{"message":"bad tool_choice"}}""", 422))
        server.enqueue(json(plainText))
        assertEquals(TestOutcome.Saved(false), entry().saveAndTest(base(), key, model))
        val bodies = (1..3).map { server.takeRequest().bodyJson() }
        assertEquals("required", bodies[0]["tool_choice"]!!.jsonPrimitive.content)
        assertEquals("auto", bodies[1]["tool_choice"]!!.jsonPrimitive.content)
        assertNull(bodies[2]["tools"])
        assertNull(bodies[2]["tool_choice"])
        assertEquals(false, vault.config()!!.toolsSupported)
    }

    @Test
    fun autoRetryThatCallsToolSavesWithTools() = runBlocking {
        server.enqueue(json("""{"error":{"message":"nope"}}""", 400))
        server.enqueue(json(toolCall))
        assertEquals(TestOutcome.Saved(true), entry().saveAndTest(base(), key, model))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun badRequestNamingTheModelStopsWithoutFallbacks() = runBlocking {
        server.enqueue(json("""{"error":{"code":"invalid_model","message":"echo $key"}}""", 400))
        val outcome = entry().saveAndTest(base(), key, model)
        assertEquals(TestOutcome.Failed("This model isn't available here."), outcome)
        assertEquals(1, server.requestCount)
        assertEquals(AiKeyStatus.Missing, vault.status.first())
    }

    @Test
    fun failingTestLeavesVaultEmpty() = runBlocking {
        prefs.setMode(AiMode.ON_DEVICE)
        val host = server.hostName
        val cases =
            listOf(
                json("""{"error":{"message":"bad key $key"}}""", 401) to "Key rejected.",
                json("{}", 403) to "Key rejected.",
                json("{}", 402) to "No credit left on this account.",
                json("{}", 404) to "This model isn't available here.",
                json("{}", 500) to "Couldn't reach $host.",
                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) to "Couldn't reach $host.",
            )
        for ((response, message) in cases) {
            server.enqueue(response)
            val outcome = entry().saveAndTest(base(), key, model)
            assertEquals(TestOutcome.Failed(message), outcome)
            assertEquals(AiKeyStatus.Missing, vault.status.first())
            assertEquals(AiMode.ON_DEVICE, prefs.mode.first())
        }
    }

    @Test
    fun incompleteKeyFailsWithoutARequest() = runBlocking {
        assertEquals(TestOutcome.Failed("That doesn't look like a complete key."), entry().saveAndTest(base(), "sk or abc", model))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun changeModelRetestsWithoutRewritingKey() = runBlocking {
        server.enqueue(json(toolCall))
        entry().saveAndTest(base(), key, model)
        server.takeRequest()

        server.enqueue(json(plainText))
        assertEquals(TestOutcome.Saved(false), entry().changeModel("other"))
        val request = server.takeRequest()
        assertEquals("Bearer $key", request.getHeader("Authorization"))
        assertEquals("other", request.bodyJson()["model"]!!.jsonPrimitive.content)
        val config = vault.config()!!
        assertEquals("other", config.model)
        assertEquals(false, config.toolsSupported)
        assertEquals(key, config.key)

        server.enqueue(json("{}", 404))
        assertEquals(TestOutcome.Failed("This model isn't available here."), entry().changeModel("gone"))
        assertEquals("other", vault.config()!!.model)
    }

    @Test
    fun changeModelWithoutAStoredKeyFails() = runBlocking {
        assertEquals(TestOutcome.Failed("Key rejected."), entry().changeModel("other"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun removeAppliesTransition() = runBlocking {
        server.enqueue(json(toolCall))
        prefs.setMode(AiMode.HYBRID)
        entry().saveAndTest(base(), key, model)
        assertTrue(vault.status.first() is AiKeyStatus.Set)
        entry().remove()
        assertEquals(AiKeyStatus.Missing, vault.status.first())
        assertEquals(AiMode.ON_DEVICE, prefs.mode.first())
    }
}
