package dev.baseline.ai.engine

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.baseline.ai.AiKeyStatus
import dev.baseline.ai.AiKeyVault
import dev.baseline.testutil.ScopeCleanup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class CloudEngineTest {
    private lateinit var server: MockWebServer
    private lateinit var file: File
    private lateinit var store: DataStore<Preferences>
    private lateinit var vault: AiKeyVault
    private val delays = mutableListOf<Long>()
    private val cleanup = ScopeCleanup()
    private val http = OkHttpClient()
    private val model = "deepseek/deepseek-v4.1-flash"
    private val request = AiRequest(RequestKind.CHAT, "SYS", "PACK", listOf(ChatTurn(Role.USER, "hi")))

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        file = File.createTempFile("cloudengine", ".preferences_pb").also { it.delete() }
        store = PreferenceDataStoreFactory.create(scope = cleanup.storeScope) { file }
        vault = AiKeyVault(store, { it }, { it })
        runBlocking { vault.save(server.url("/v1/"), "test-key", model, toolsSupported = true) }
    }

    @After
    fun tearDown() {
        runBlocking { cleanup.close() }
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        server.shutdown()
        file.delete()
    }

    private fun engine(tools: ToolRunner = NoTools, online: () -> Boolean = { true }) = CloudEngine(http, vault, tools, online) { delays += it }

    private fun sse(vararg lines: String) = lines.joinToString("") { "$it\n\n" }

    private fun delta(text: String) = """data: {"choices":[{"delta":{"content":"$text"}}]}"""

    private fun ok(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)

    private val hello = sse(delta("Hel"), delta("lo"), "data: [DONE]")
    private val helloEvents = listOf(AiEvent.Text("Hel"), AiEvent.Text("lo"), AiEvent.Done(EngineLabel(EngineKind.CLOUD, model)))

    private suspend fun failure(req: AiRequest = request): AiFailure {
        try {
            engine().run(req).toList()
        } catch (e: AiFailure) {
            return e
        }
        fail("expected an AiFailure")
        throw AssertionError()
    }

    private fun sentJson(): JsonObject = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    @Test
    fun streamsTextDeltasThenDone() = runBlocking {
        server.enqueue(ok(hello))
        assertEquals(helloEvents, engine().run(request).toList())
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/v1/chat/completions", sent.path)
    }

    @Test
    fun ignoresSseCommentLines() = runBlocking {
        server.enqueue(ok(": OPENROUTER PROCESSING\n\n" + hello))
        assertEquals(helloEvents, engine().run(request).toList())
    }

    @Test
    fun parsesSseLines() {
        assertEquals(SseLine.Done, parseSseLine("data: [DONE]"))
        assertEquals(SseLine.Data("""{"a":1}"""), parseSseLine("""data: {"a":1}"""))
        assertEquals(SseLine.Data("""{"a":1}"""), parseSseLine("""data:{"a":1}"""))
        assertEquals(SseLine.Skip, parseSseLine(""))
        assertEquals(SseLine.Skip, parseSseLine(": OPENROUTER PROCESSING"))
        assertEquals(SseLine.Skip, parseSseLine("event: ping"))
    }

    @Test
    fun sendsChatAndInsightLimits() = runBlocking {
        server.enqueue(ok(hello))
        engine().run(request).toList()
        val chat = sentJson()
        assertEquals(model, chat["model"]!!.jsonPrimitive.content)
        assertEquals(1024, chat["max_tokens"]!!.jsonPrimitive.int)
        assertEquals("true", chat["stream"]!!.jsonPrimitive.content)
        assertEquals("0.3", chat["temperature"]!!.jsonPrimitive.content)
        val messages = chat["messages"]!!.jsonArray
        assertEquals(Json.parseToJsonElement("""{"role":"system","content":"SYS\n\nPACK"}"""), messages[0])
        assertEquals(Json.parseToJsonElement("""{"role":"user","content":"hi"}"""), messages[1])

        server.enqueue(ok(hello))
        engine().run(request.copy(kind = RequestKind.INSIGHT)).toList()
        val insight = sentJson()
        assertEquals(200, insight["max_tokens"]!!.jsonPrimitive.int)
        assertNull(insight["tools"])
    }

    @Test
    fun noToolsFieldWithoutToolSupport() = runBlocking {
        vault.save(server.url("/v1/"), "test-key", model, toolsSupported = false)
        server.enqueue(ok(hello))
        val tools = object : ToolRunner {
            override val definitions = Json.parseToJsonElement("""[{"type":"function","function":{"name":"x"}}]""").jsonArray

            override suspend fun call(name: String, argumentsJson: String) = ToolResult("", "{}")
        }
        engine(tools).run(request).toList()
        assertFalse(sentJson().containsKey("tools"))
    }

    @Test
    fun toolsAreOfferedWhenSupported() = runBlocking {
        server.enqueue(ok(hello))
        val tools = object : ToolRunner {
            override val definitions = Json.parseToJsonElement("""[{"type":"function","function":{"name":"x"}}]""").jsonArray

            override suspend fun call(name: String, argumentsJson: String) = ToolResult("", "{}")
        }
        engine(tools).run(request).toList()
        val sent = sentJson()
        assertEquals(1, sent["tools"]!!.jsonArray.size)
        assertEquals("auto", sent["tool_choice"]!!.jsonPrimitive.content)
    }

    @Test
    fun authorizationOnlyToConfiguredHost() = runBlocking {
        val other = MockWebServer().also { it.start() }
        try {
            server.enqueue(ok(hello))
            engine().run(request).toList()
            assertEquals("Bearer test-key", server.takeRequest().getHeader("Authorization"))
            assertEquals(0, other.requestCount)
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun unauthorizedMarksKeyInvalid() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(AiFailure.KeyRejected, failure())
        assertTrue((vault.status.first() as AiKeyStatus.Set).invalid)
        server.enqueue(ok(hello))
        engine().run(request).toList()
        assertFalse((vault.status.first() as AiKeyStatus.Set).invalid)
    }

    @Test
    fun forbiddenIsKeyRejected() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(AiFailure.KeyRejected, failure())
    }

    @Test
    fun missingKeyIsRejectedWithoutMarkingAnything() = runBlocking {
        vault.clear()
        assertEquals(AiFailure.KeyRejected, failure())
        assertEquals(AiKeyStatus.Missing, vault.status.first())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun paymentRequiredIsNoCredit() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(402))
        assertEquals(AiFailure.NoCredit(server.hostName), failure())
    }

    @Test
    fun notFoundIsModelGone() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(AiFailure.ModelGone, failure())
    }

    @Test
    fun badRequestNamingTheModelIsModelGone() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":"model_not_found","message":"x"}}"""))
        assertEquals(AiFailure.ModelGone, failure())
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"param":"model","message":"x"}}"""))
        assertEquals(AiFailure.ModelGone, failure())
    }

    @Test
    fun otherBadRequestIsMalformed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":"context_length","message":"model is mentioned here"}}"""))
        assertEquals(AiFailure.Malformed, failure())
        server.enqueue(MockResponse().setResponseCode(400).setBody("not json"))
        assertEquals(AiFailure.Malformed, failure())
    }

    @Test
    fun serverErrorIsUnreachable() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(AiFailure.Unreachable(server.hostName), failure())
    }

    @Test
    fun connectionFailureIsUnreachable() = runBlocking {
        server.shutdown()
        assertEquals(AiFailure.Unreachable(server.hostName), failure())
    }

    @Test
    fun offlineFailsFast() = runBlocking {
        try {
            engine(online = { false }).run(request).toList()
            fail("expected Offline")
        } catch (e: AiFailure) {
            assertEquals(AiFailure.Offline, e)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun retriesOnceAfterRetryAfter() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "3"))
        server.enqueue(ok(hello))
        assertEquals(helloEvents, engine().run(request).toList())
        assertEquals(listOf(3000L), delays)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aSecond429IsBusy() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1"))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1"))
        assertEquals(AiFailure.Busy(server.hostName), failure())
        assertEquals(listOf(1000L), delays)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun retryAfterIsCappedAtTen() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
        server.enqueue(ok(hello))
        engine().run(request).toList()
        assertEquals(listOf(10_000L), delays)
    }

    @Test
    fun retryAfterDefaultsToTwo() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(ok(hello))
        engine().run(request).toList()
        assertEquals(listOf(2000L), delays)
    }

    @Test
    fun emptyAnswerIsMalformed() = runBlocking {
        server.enqueue(ok(sse("data: [DONE]")))
        assertEquals(AiFailure.Malformed, failure())
    }

    @Test
    fun garbledChunkIsMalformed() = runBlocking {
        server.enqueue(ok(sse("data: {not json")))
        assertEquals(AiFailure.Malformed, failure())
    }

    @Test
    fun inStreamErrorIsMalformed() = runBlocking {
        server.enqueue(ok(sse(delta("Hel"), """data: {"error":{"message":"secret provider text"}}""")))
        assertEquals(AiFailure.Malformed, failure())
    }

    @Test
    fun cancellingCancelsTheCall() = runBlocking {
        // The first period carries the first event (padded with an SSE comment); the rest arrives 5 s later.
        val chunk = 1024
        val first = (delta("Hel") + "\n\n").let { it + ":" + " ".repeat(chunk - it.length - 2) + "\n" }
        server.enqueue(ok(first + delta("lo") + "\n\ndata: [DONE]\n\n").throttleBody(chunk.toLong(), 5, TimeUnit.SECONDS))
        val gotFirst = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) { engine().run(request).collect { gotFirst.complete(Unit) } }
        gotFirst.await()
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        val started = System.nanoTime()
        job.cancel()
        job.join()
        assertTrue("took ${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)} ms", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3))
    }
}
