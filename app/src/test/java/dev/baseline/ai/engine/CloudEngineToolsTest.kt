package dev.baseline.ai.engine

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.baseline.ai.AiKeyVault
import dev.baseline.testutil.ScopeCleanup
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

class CloudEngineToolsTest {
    private val cleanup = ScopeCleanup()
    private val http = OkHttpClient()
    private lateinit var server: MockWebServer
    private lateinit var file: File
    private lateinit var store: DataStore<Preferences>
    private lateinit var vault: AiKeyVault
    private val model = "deepseek/deepseek-v4.1-flash"
    private val done = AiEvent.Done(EngineLabel(EngineKind.CLOUD, model))
    private val request = AiRequest(RequestKind.CHAT, "SYS", "PACK", listOf(ChatTurn(Role.USER, "hi")))
    private val definitions = Json.parseToJsonElement("""[{"type":"function","function":{"name":"daily_metrics"}}]""").jsonArray

    /** Records its calls and answers each with [content]. */
    private inner class FakeTools(val content: () -> String = { """{"ok":true}""" }) : ToolRunner {
        val calls = mutableListOf<Pair<String, String>>()
        override val definitions: JsonArray = this@CloudEngineToolsTest.definitions

        override suspend fun call(name: String, argumentsJson: String): ToolResult {
            calls += name to argumentsJson
            return ToolResult("Looking up …", content())
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        file = File.createTempFile("cloudenginetools", ".preferences_pb").also { it.delete() }
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

    private fun engine(tools: ToolRunner) = CloudEngine(http, vault, tools, { true }) { }

    private fun sse(vararg lines: String) = lines.joinToString("") { "$it\n\n" }

    private fun ok(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)

    private fun text(t: String) = ok(sse("""data: {"choices":[{"delta":{"content":"$t"}}]}""", "data: [DONE]"))

    private fun toolCall(arguments: String = "{}", id: String = "c1", name: String = "daily_metrics") = ok(
        sse(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"$id","function":{"name":"$name","arguments":${JsonPrimitive(
                arguments,
            )}}}]},"finish_reason":"tool_calls"}]}""",
            "data: [DONE]",
        ),
    )

    private fun sent(): JsonObject = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    private fun JsonObject.messages() = this["messages"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun toolCallSplitAcrossChunksIsAssembled() = runBlocking {
        server.enqueue(
            ok(
                sse(
                    """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"daily_metrics","arguments":"{\"from\":"}}]}}]}""",
                    """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"2026-10-01\"}"}}]},"finish_reason":"tool_calls"}]}""",
                    "data: [DONE]",
                ),
            ),
        )
        server.enqueue(text("ok"))
        val tools = FakeTools()
        engine(tools).run(request).toList()
        assertEquals(listOf("daily_metrics" to """{"from":"2026-10-01"}"""), tools.calls)
    }

    @Test
    fun runsToolsThenAnswers() = runBlocking {
        server.enqueue(toolCall("""{"from":"2026-10-01"}"""))
        server.enqueue(ok(sse("""data: {"choices":[{"delta":{"content":"Hel"}}]}""", """data: {"choices":[{"delta":{"content":"lo"}}]}""", "data: [DONE]")))
        val tools = FakeTools()
        val events = engine(tools).run(request).toList()
        assertEquals(listOf(AiEvent.ToolStatus("Looking up …"), AiEvent.Text("Hel"), AiEvent.Text("lo"), done), events)

        val first = sent()
        assertEquals("auto", first["tool_choice"]!!.jsonPrimitive.content)
        val second = sent().messages()
        assertEquals(
            Json.parseToJsonElement(
                """{"role":"assistant","tool_calls":[{"id":"c1","type":"function","function":{"name":"daily_metrics","arguments":"{\"from\":\"2026-10-01\"}"}}]}""",
            ),
            second[second.size - 2],
        )
        assertEquals(Json.parseToJsonElement("""{"role":"tool","tool_call_id":"c1","content":"{\"ok\":true}"}"""), second.last())
    }

    @Test
    fun parallelCallsInOneRoundAreAllRun() = runBlocking {
        server.enqueue(
            ok(
                sse(
                    """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"a","function":{"name":"daily_metrics","arguments":"{}"}},{"index":1,"id":"b","function":{"name":"records","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
                    "data: [DONE]",
                ),
            ),
        )
        server.enqueue(text("ok"))
        val tools = FakeTools()
        engine(tools).run(request).toList()
        assertEquals(listOf("daily_metrics", "records"), tools.calls.map { it.first })
        server.takeRequest()
        val tail = sent().messages().takeLast(3)
        assertEquals(listOf("assistant", "tool", "tool"), tail.map { it["role"]!!.jsonPrimitive.content })
        assertEquals(listOf("a", "b"), tail.drop(1).map { it["tool_call_id"]!!.jsonPrimitive.content })
        assertEquals(2, tail[0]["tool_calls"]!!.jsonArray.size)
    }

    @Test
    fun stopsAfterSixRoundsWithToolChoiceNone() = runBlocking {
        repeat(7) { server.enqueue(toolCall()) }
        server.enqueue(text("never read"))
        val tools = FakeTools()
        try {
            engine(tools).run(request).toList()
            fail("a forced round that still asks for tools has no answer")
        } catch (e: AiFailure) {
            assertEquals(AiFailure.Malformed, e)
        }
        assertEquals(7, server.requestCount)
        assertEquals(6, tools.calls.size)
        repeat(6) { assertEquals("auto", sent()["tool_choice"]!!.jsonPrimitive.content) }
        val last = sent()
        assertEquals("none", last["tool_choice"]!!.jsonPrimitive.content)
        assertEquals(1, last["tools"]!!.jsonArray.size)
    }

    @Test
    fun theForcedRequestAnswersInText() = runBlocking {
        repeat(6) { server.enqueue(toolCall()) }
        server.enqueue(text("Final"))
        val tools = FakeTools()
        val events = engine(tools).run(request).toList()
        assertEquals(6, tools.calls.size)
        assertEquals(listOf(AiEvent.ToolStatus("Looking up …")), events.take(1))
        assertEquals(listOf(AiEvent.Text("Final"), done), events.takeLast(2))
        assertEquals(7, server.requestCount)
    }

    @Test
    fun emptyArgumentsMeanNoArguments() = runBlocking {
        server.enqueue(toolCall(arguments = ""))
        server.enqueue(text("ok"))
        val tools = FakeTools()
        engine(tools).run(request).toList()
        assertEquals(listOf("daily_metrics" to "{}"), tools.calls)
    }

    @Test
    fun unknownToolIsAnErrorResult() = runBlocking {
        server.enqueue(toolCall(name = "nope"))
        server.enqueue(text("ok"))
        val events = engine(noToolsWithDefinitions).run(request).toList()
        // NoTools answers with an empty label, so there is no status to show.
        assertEquals(listOf(AiEvent.Text("ok"), done), events)
        server.takeRequest()
        assertEquals("""{"error":"unknown tool"}""", sent().messages().last()["content"]!!.jsonPrimitive.content)
    }

    private val noToolsWithDefinitions =
        object : ToolRunner {
            override val definitions: JsonArray = this@CloudEngineToolsTest.definitions

            override suspend fun call(name: String, argumentsJson: String) = NoTools.call(name, argumentsJson)
        }

    @Test
    fun aToolCallWithoutOfferedToolsIsMalformed() = runBlocking {
        server.enqueue(toolCall())
        try {
            engine(NoTools).run(request).toList()
            fail("expected Malformed")
        } catch (e: AiFailure) {
            assertEquals(AiFailure.Malformed, e)
        }
        assertEquals(1, server.requestCount)
        assertFalse(sent().containsKey("tools"))
    }

    @Test
    fun aToolCallWithoutANameIsMalformed() = runBlocking {
        server.enqueue(ok(sse("""data: {"choices":[{"delta":{"tool_calls":[{"index":0}]},"finish_reason":"tool_calls"}]}""", "data: [DONE]")))
        try {
            engine(FakeTools()).run(request).toList()
            fail("expected Malformed")
        } catch (e: AiFailure) {
            assertEquals(AiFailure.Malformed, e)
        }
    }

    @Test
    fun insightsNeverOfferTools() = runBlocking {
        server.enqueue(text("ok"))
        engine(FakeTools()).run(request.copy(kind = RequestKind.INSIGHT)).toList()
        assertNull(sent()["tools"])
    }

    @Test
    fun aRateLimitedRoundIsRetriedOnce() = runBlocking {
        server.enqueue(toolCall())
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1"))
        server.enqueue(text("ok"))
        val delays = mutableListOf<Long>()
        val events = CloudEngine(http, vault, FakeTools(), { true }) { delays += it }.run(request).toList()
        assertEquals(listOf(1000L), delays)
        assertEquals(done, events.last())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun budgetDropsOldestToolResultsFirst() = runBlocking {
        repeat(3) { server.enqueue(toolCall(id = "c$it")) }
        server.enqueue(text("ok"))
        // 200,000 characters is 50,000 estimated tokens: one result fits the 60,000 budget, two don't.
        var n = 0
        val tools = FakeTools { "r${n++}" + "x".repeat(199_998) }
        engine(tools).run(request).toList()
        repeat(3) { server.takeRequest() }
        val fourth = sent().messages().filter { it["role"]!!.jsonPrimitive.content == "tool" }.map { it["content"]!!.jsonPrimitive.content }
        assertEquals(3, fourth.size)
        assertEquals("[earlier lookup removed to save space]", fourth[0])
        assertEquals("[earlier lookup removed to save space]", fourth[1])
        assertEquals(200_000, fourth[2].length)
        assertEquals('r', fourth[2][0])
    }

    @Test
    fun nothingIsDroppedWhileItFits() = runBlocking {
        server.enqueue(toolCall())
        server.enqueue(text("ok"))
        engine(FakeTools { "x".repeat(100_000) }).run(request).toList()
        server.takeRequest()
        assertEquals(100_000, sent().messages().last()["content"]!!.jsonPrimitive.content.length)
    }
}
