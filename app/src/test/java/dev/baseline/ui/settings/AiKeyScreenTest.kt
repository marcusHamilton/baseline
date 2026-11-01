package dev.baseline.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.baseline.ai.AiKeyEntry
import dev.baseline.ai.AiKeyStatus
import dev.baseline.ai.AiKeyVault
import dev.baseline.ai.AiPrefs
import dev.baseline.ai.parseBaseUrl
import dev.baseline.testutil.ScopeCleanup
import dev.baseline.ui.theme.BaselineTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AiKeyScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val cleanup = ScopeCleanup()
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
    private lateinit var server: MockWebServer
    private lateinit var file: File
    private lateinit var store: DataStore<Preferences>
    private lateinit var vault: AiKeyVault
    private lateinit var prefs: AiPrefs
    private val suggested = "deepseek/deepseek-v4.1-flash"
    private val key = "sk-or-test-key"

    // What the fake provider answers; tests change these.
    private var modelsCode = 200
    private var modelsBody =
        """{"data":[
            {"id":"zeta/other-model","pricing":{"prompt":"0.000001","completion":"0.000002"},"supported_parameters":[]},
            {"id":"$suggested","pricing":{"prompt":"0.00000006","completion":"0.0000002"},"supported_parameters":["tools"]},
            {"id":"alpha/tool-model","pricing":{"prompt":"0.000002","completion":"0.000004"},"supported_parameters":["tools"]}
        ]}"""
    private var chatCode = 200
    private var chatBody = TOOL_CALL
    private val chatBodies = mutableListOf<String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.endsWith("/models") -> json(modelsBody, modelsCode)

                    request.path!!.endsWith("/chat/completions") -> {
                        chatBodies += request.body.readUtf8()
                        json(chatBody, chatCode)
                    }

                    else -> MockResponse().setResponseCode(404)
                }
            }
        server.start()
        file = File.createTempFile("aikeyscreen", ".preferences_pb").also { it.delete() }
        store = PreferenceDataStoreFactory.create(scope = cleanup.storeScope) { file }
        vault = AiKeyVault(store, { it }, { it })
        prefs = AiPrefs(store)
    }

    @After
    fun tearDown() {
        rule.runOnIdle { focus?.clearFocus(true) }
        rule.waitForIdle()
        runBlocking { cleanup.close() }
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        server.shutdown()
        file.delete()
    }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun entry(allowLoopbackHttp: Boolean = true) = AiKeyEntry(http, vault, prefs, { true }, allowLoopbackHttp)

    private fun serverUrl() = server.url("/v1").toString()

    private fun viewModel(startAtModel: Boolean = false, entry: AiKeyEntry = entry()) = cleanup.track(AiKeyViewModel(entry, vault, startAtModel))

    private var backs = 0
    private val empty = SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))

    // Kept so tearDown can drop the focus a text field took. A focused field's cursor keeps blinking until the activity is
    // destroyed, and where the test ends in that cycle decides whether the next test in this JVM still recomposes
    // (TodayScreenTest's edge-scroll drag never did after a short typing test; advancing the clock by 1 s, 2 s or 3 s cured it, 0.5 s or 1.5 s did not).
    private var focus: FocusManager? = null

    private fun show(vm: AiKeyViewModel) = rule.setContent {
        focus = LocalFocusManager.current
        BaselineTheme { AiKeyScreen(vm, onBack = { backs++ }) }
    }

    private fun typeConnection(url: String = serverUrl(), apiKey: String = key) {
        rule.onNodeWithTag("ai-base-url").performTextReplacement(url)
        rule.onNodeWithTag("ai-api-key").performTextInput(apiKey)
    }

    private fun waitForText(text: String) = rule.waitUntil(10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForTag(tag: String) = rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun toModelStep() {
        typeConnection()
        rule.onNodeWithText("Continue").performClick()
        waitForTag("ai-model-$suggested")
    }

    @Test
    fun theBaseUrlIsPrefilledWithOpenRouter() {
        show(viewModel())
        rule.onNodeWithTag("ai-base-url").assert(hasText("https://openrouter.ai/api/v1"))
    }

    @Test
    fun continueWaitsForAKey() {
        show(viewModel())
        rule.onNodeWithText("Continue").assertIsNotEnabled()
        rule.onNodeWithTag("ai-api-key").performTextInput(key)
        rule.onNodeWithText("Continue").assertIsEnabled()
    }

    @Test
    fun rejectsHttpUrl() {
        show(viewModel(entry = entry(allowLoopbackHttp = false)))
        typeConnection(url = server.url("/v1").toString()) // http://127.0.0.1
        rule.onNodeWithText("Continue").performClick()
        rule.onNodeWithText("Use an https:// address.").assertIsDisplayed()
        assertEquals(0, server.requestCount)
    }

    @Test
    fun anIncompleteKeyIsRefusedBeforeAnyRequest() {
        show(viewModel())
        typeConnection(apiKey = "sk-or half pasted")
        rule.onNodeWithText("Continue").performClick()
        rule.onNodeWithText("That doesn't look like a complete key.").assertIsDisplayed()
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aRejectedKeyStaysOnStepOneWithItsInputs() {
        modelsCode = 401
        show(viewModel())
        typeConnection()
        rule.onNodeWithText("Continue").performClick()
        waitForText("Key rejected.")
        rule.onNodeWithTag("ai-base-url").assert(hasText(serverUrl()))
        rule.onNodeWithText("Continue").assertIsEnabled()
    }

    @Test
    fun anUnreachableHostSaysSo() {
        val dead = serverUrl()
        val host = server.hostName
        server.shutdown()
        show(viewModel())
        typeConnection(url = dead)
        rule.onNodeWithText("Continue").performClick()
        waitForText("Couldn't reach $host.")
        rule.onNodeWithText("Continue").assertIsEnabled()
    }

    @Test
    fun listedModelsSuggestedPreselected() {
        show(viewModel())
        toModelStep()
        rule.onNodeWithTag("ai-model-$suggested").assertIsSelected()
        rule.onNodeWithText("≈ $0.0007 per question").assertIsDisplayed()
        rule.onNodeWithText("Test and save").assertIsEnabled()
        rule.onAllNodesWithText("Tools").fetchSemanticsNodes().let { assertEquals(2, it.size) }
        // The Model ID field is only for the fallback.
        rule.onAllNodes(hasTestTag("ai-model-id")).assertCountEquals(0)
    }

    @Test
    fun withoutTheSuggestedModelNothingIsSelectedAndTestIsDisabledUntilAPick() {
        modelsBody = """{"data":[{"id":"alpha/tool-model"},{"id":"beta/model"}]}"""
        show(viewModel())
        typeConnection()
        rule.onNodeWithText("Continue").performClick()
        waitForTag("ai-model-alpha/tool-model")
        rule.onNodeWithText("Test and save").assertIsNotEnabled()
        rule.onNodeWithTag("ai-model-beta/model").performClick()
        rule.onNodeWithTag("ai-model-beta/model").assertIsSelected()
        rule.onNodeWithText("Test and save").assertIsEnabled()
    }

    @Test
    fun searchFiltersTheListBySubstringIgnoringCase() {
        show(viewModel())
        toModelStep()
        rule.onNodeWithTag("ai-model-search").performTextInput("TOOL-mod")
        rule.onAllNodes(hasTestTag("ai-model-alpha/tool-model")).assertCountEquals(1)
        rule.onAllNodes(hasTestTag("ai-model-zeta/other-model")).assertCountEquals(0)
        rule.onAllNodes(hasTestTag("ai-model-$suggested")).assertCountEquals(0)
    }

    @Test
    fun freeTextFallback() {
        modelsCode = 500
        show(viewModel())
        typeConnection()
        rule.onNodeWithText("Continue").performClick()
        waitForTag("ai-model-id")
        rule.onNodeWithTag("ai-model-id").assert(empty) // nothing preselected
        rule.onNodeWithText("Test and save").assertIsNotEnabled()
        rule.onNodeWithTag("ai-model-id").performTextInput("my/model")
        rule.onNodeWithText("Test and save").assertIsEnabled()
        rule.onNodeWithText("Test and save").performClick()
        waitForText("✓ Key works · tools supported")
        assertEquals("my/model", (runBlocking { vault.status.first() } as AiKeyStatus.Set).model)
    }

    @Test
    fun savedWithToolsShowsTheGoodOutcome() {
        show(viewModel())
        toModelStep()
        rule.onNodeWithText("Test and save").performClick()
        waitForText("✓ Key works · tools supported")
        assertNotNull(runBlocking { vault.config() })
    }

    @Test
    fun savedWithoutToolsShowsWarning() {
        chatBody = """{"choices":[{"message":{"role":"assistant","content":"pong"}}]}"""
        show(viewModel())
        toModelStep()
        rule.onNodeWithText("Test and save").performClick()
        waitForText("⚠ This model can't look up your history; answers will use the page's data only.")
        val status = runBlocking { vault.status.first() } as AiKeyStatus.Set
        assertEquals(false, status.toolsSupported)
    }

    @Test
    fun failedShowsMessageAndKeepsInputs() {
        chatCode = 401
        show(viewModel())
        toModelStep()
        rule.onNodeWithTag("ai-model-alpha/tool-model").performClick()
        rule.onNodeWithText("Test and save").performClick()
        waitForText("Key rejected.")
        // Back on the model step with the pick intact, and nothing saved.
        rule.onNodeWithTag("ai-model-alpha/tool-model").assertIsSelected()
        rule.onNodeWithText("Test and save").assertIsEnabled()
        assertNull(runBlocking { vault.config() })
        assertEquals(AiKeyStatus.Missing, runBlocking { vault.status.first() })
    }

    @Test
    fun aFailedFreeTextModelKeepsWhatWasTyped() {
        modelsCode = 500
        chatCode = 404
        show(viewModel())
        typeConnection()
        rule.onNodeWithText("Continue").performClick()
        waitForTag("ai-model-id")
        rule.onNodeWithTag("ai-model-id").performTextInput("gone/model")
        rule.onNodeWithText("Test and save").performClick()
        waitForText("This model isn't available here.")
        rule.onNodeWithTag("ai-model-id").assert(hasText("gone/model"))
    }

    @Test
    fun backFromTheModelStepReturnsToTheConnectionStepWithItsInputs() {
        show(viewModel())
        toModelStep()
        rule.onNode(hasContentDescription("Back")).performClick()
        rule.onNodeWithTag("ai-base-url").assert(hasText(serverUrl()))
        assertEquals(0, backs)
    }

    @Test
    fun backFromDonePopsToSettings() {
        show(viewModel())
        toModelStep()
        rule.onNodeWithText("Test and save").performClick()
        waitForText("✓ Key works · tools supported")
        rule.onNode(hasContentDescription("Back")).performClick()
        assertEquals(1, backs)
    }

    @Test
    fun changeModelStartsAtTheModelStepWithTheStoredKey() {
        runBlocking { vault.save(parseBaseUrl(serverUrl(), true)!!, key, "old/model", false) }
        show(viewModel(startAtModel = true))
        waitForTag("ai-model-$suggested")
        rule.onAllNodes(hasTestTag("ai-api-key")).assertCountEquals(0)
        rule.onNodeWithText("Test and save").performClick()
        waitForText("✓ Key works · tools supported")
        val status = runBlocking { vault.status.first() } as AiKeyStatus.Set
        assertEquals(suggested, status.model)
        assertEquals(true, status.toolsSupported)
        assertEquals(key, runBlocking { vault.config() }!!.key)
    }

    @Test
    fun keyNotInSavedState() {
        val restoration = StateRestorationTester(rule)
        val vm = viewModel()
        restoration.setContent { BaselineTheme { AiKeyScreen(vm, onBack = {}) } }
        typeConnection(url = "https://example.com/v2")
        rule.onNodeWithText("Continue").assertIsEnabled()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("ai-base-url").assert(hasText("https://example.com/v2"))
        rule.onNodeWithTag("ai-api-key").assert(empty)
        rule.onNodeWithText("Continue").assertIsNotEnabled()
    }

    @Test
    fun clearingTheKeyFieldDisablesContinueAgain() {
        show(viewModel())
        rule.onNodeWithTag("ai-api-key").performTextInput(key)
        rule.onNodeWithTag("ai-api-key").performTextClearance()
        rule.onNodeWithText("Continue").assertIsNotEnabled()
    }

    private companion object {
        const val TOOL_CALL =
            """{"choices":[{"message":{"role":"assistant","content":null,""" +
                """"tool_calls":[{"id":"c1","type":"function","function":{"name":"ping","arguments":"{}"}}]}}]}"""
    }
}
