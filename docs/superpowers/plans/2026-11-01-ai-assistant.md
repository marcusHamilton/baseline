# AI Assistant (Ask and insights) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user ask questions about their health data from any page (an Ask sheet opened from a centre button in the tab bar), and show a cached AI insight on Today, Sleep and each activity. Answers come from on-device Gemini Nano, an OpenAI-compatible cloud endpoint with the user's own key, or both.

**Architecture:** A new `dev.baseline.ai` package has these layers:

- **Storage:** mode and key (`AiPrefs`, `AiKeyVault`).
- **Engines:** `NanoEngine` over a `NanoBackend` interface, and `CloudEngine` over OkHttp with a hand-written SSE and tool loop.
- **What the model sees:** `ContextPacks`, `HealthTools`, `Prompts`, `Suggestions`.
- **Orchestration:** `AiRouter` (a pure routing table), `ChatSession` (one chat) and `InsightService` (insights with a Room cache).

The UI adds `ui/ai/` (the sheet, the insight card, the centre button) and an AI card plus key screen in Settings. Everything is wired in `AppContainer`. Screens get insight cards as slots, so their ViewModels don't change.

**Tech Stack:** Kotlin 2.4, Compose (Material 3 `ModalBottomSheet`), Room 3, DataStore, OkHttp 5 and `MockWebServer`, kotlinx.serialization, ML Kit GenAI Prompt API (`com.google.mlkit:genai-prompt`, beta), JUnit4 with Robolectric and Compose UI tests, and a Python 3.12 stdlib fake server.

**Spec:** `docs/superpowers/specs/2026-11-01-ai-assistant-design.md`. Read it first; this plan argues from it, and section numbers (§) refer to it.

## Global Constraints

- **Read first in a cloud container:** `docs/cloud-sessions.md`, then run `tools/cloud_setup.sh`. There's no emulator, no Nano and no device there.
- **Dependencies:** only one new one, `com.google.mlkit:genai-prompt`, pinned in `gradle/libs.versions.toml` to the newest version on Google Maven at implementation time. If its `minSdk` is above 29, stop and ask the owner. No `okhttp-sse`, no vendor AI SDK.
- **`AppDatabase` version 2 → 3** with an explicit `MIGRATION_2_3` registered through `.addMigrations(...)`. The destructive fallback stays, but must never run for 2 → 3.
- **Secrets:**
  - The AI key is never logged, and never appears in an exception message, UI text or test output.
  - Provider error bodies are never shown, logged or put in exceptions.
  - Nothing under `dev.baseline.ai` calls `android.util.Log`.
- **`aiHttp`** is built from a bare `OkHttpClient.Builder()`: no interceptors, no authenticator, connect 15 s, read 60 s. `Authorization: Bearer <key>` is added per request, only when the request host equals the configured base URL host.
- **Privacy:** nothing sent to any engine contains latitude, longitude, polylines or routes, `ActivityEntity.name`, device names, IDs or serials, or Garmin user or profile IDs.
- **Engine limits:**
  - Nano: `maxOutput` is 256 for chat and 160 for insights; temperature 0.2; 60 s timeout.
  - Cloud: `max_tokens` is 1,024 for chat and 200 for insights; temperature 0.3.
  - At most 6 tool rounds, then one request with `tool_choice: "none"`.
  - Tool results are capped at 32,000 characters. Conversations are budgeted at 60,000 estimated tokens (characters ÷ 4).
  - 400-day maximum range for tools. The compact pack is at most 1,200 estimated tokens.
- **Defaults:**
  - Base URL `https://openrouter.ai/api/v1`; suggested model `deepseek/deepseek-v4.1-flash`.
  - The price estimate assumes 10,000 input tokens plus 500 output tokens.
  - `Retry-After` is capped at 10 s (2 s when absent); Nano `BUSY` retries once after 1 s.
- **Copy:** every user-visible string in §3 and §8 is used verbatim. Ellipses are `…` (U+2026), and the separators are `·` (U+00B7) and `☁` (U+2601).
- **Colours:** the AI accent is `SleepPurple` (`#A78BFA`); the insight card background is `#171A1F` (the existing card colour).
- **Accessibility:** touch targets are at least 48dp; the centre button's visual circle is 36dp; TalkBack labels are as in §3.4 and §3.8.
- **Time:**
  - ViewModels and services take dates from `container.today` and the zone from `container.dateTicker.zone`, never `LocalDate.now()` or `ZoneId.systemDefault()`.
  - Tests inject a `Clock`, and must pass in any host time zone.
- **Tests:**
  - Room tests use `testutil.inMemoryDatabase()`.
  - DataStore tests use `PreferenceDataStoreFactory.create(scope = …) { file }`, as `ApiKeyVaultTest` does.
  - Cipher functions are the identity (`{ it }`) in tests, as `NavShellTest` does.
- **Before each commit:** `./gradlew spotlessApply`. **Before the last commit:** `./gradlew :app:testDebugUnitTest :app:lintDebug spotlessCheck` must all be green.
- **Commits:** Conventional Commits (`feat:`, `fix:`, `test:`, `docs:`), each ending with the session's attribution lines.

## Review Focus

These are inputs the spec implies but its test list doesn't pin. Each has a test in the task named.

1. **OpenRouter keep-alive comments in the SSE stream** (lines starting with `:`, such as `: OPENROUTER PROCESSING`) must be ignored, not parsed or failed on. Task 3: `ignoresSseCommentLines`.
2. **A pasted key with surrounding whitespace or a newline** must be trimmed. A key with inner whitespace is rejected with "That doesn't look like a complete key." OkHttp throws on a newline in a header value, so an untrimmed key would crash. Task 5: `trimsKeyAndRejectsInnerWhitespace`.
3. **A base URL with a trailing slash** (`https://openrouter.ai/api/v1/`) must give `…/api/v1/chat/completions`, never `…/v1//chat/completions`. Task 5: `trailingSlashBaseUrlJoinsCleanly`.
4. **A 200 response with no text and no tool calls** shows "Something went wrong reading the answer." and Try again, never an empty bubble. Task 3: `emptyAnswerIsMalformed`.
5. **Tool calls with `""` as arguments, or an unknown tool name,** don't crash. `""` means `{}`. An unknown name returns `{"error":"unknown tool"}` as the result and counts as a round. Task 4: `emptyArgumentsMeanNoArguments`, `unknownToolIsAnErrorResult`.

---

## File map

```
app/src/main/java/dev/baseline/ai/
  AiMode.kt            AiMode, AiModes (defaults, transitions, availability)              Task 1
  AiPrefs.kt           mode in DataStore                                                    Task 1
  AiKeyVault.kt        AiConfig, AiKeyStatus, encrypted key storage                         Task 1
  AiKeyEntry.kt        parseBaseUrl, listModels, saveAndTest, changeModel, remove           Task 5
  AiRouter.kt          QuestionTag, RouteInput, Route, AiRouter                             Task 10
  ChatSession.kt       ChatMessage, ChatSession                                             Task 10
  AiStartup.kt         startAi(): Nano refresh + mode defaults on app start                 Task 6
  engine/AiEngine.kt   EngineKind, EngineLabel, ChatTurn, AiRequest, AiEvent, AiFailure, AiEngine   Task 2
  engine/OpenAiWire.kt @Serializable wire types + SSE line parser                           Task 3
  engine/CloudEngine.kt                                                                     Tasks 3–4
  engine/NanoBackend.kt, NanoEngine.kt, MlKitNanoBackend.kt                                 Task 6
  context/AskScope.kt  AskScope + titles + insight keys                                     Task 7
  context/ContextPacks.kt  Pack, PackSize, PackFacts, ContextPacks                          Task 7
  context/HealthTools.kt   ToolRunner, ToolResult, HealthTools                              Task 8
  context/Prompts.kt, Suggestions.kt                                                        Task 9
  insight/InsightService.kt  InsightState, InsightService                                   Task 12
app/src/main/java/dev/baseline/data/db/
  AiInsightEntity.kt, AiInsightDao.kt (or in Daos.kt), Migrations.kt; AppDatabase.kt        Task 11
app/src/main/java/dev/baseline/ui/ai/
  AskSheet.kt, AskViewModel.kt                                                              Task 14
  InsightCard.kt, InsightViewModel.kt                                                       Task 15
  AskButton.kt, AskScopeHolder.kt                                                           Task 16
app/src/main/java/dev/baseline/ui/settings/
  AiCard.kt, AiSettingsViewModel.kt, AiKeyScreen.kt, AiKeyViewModel.kt                      Task 13
app/src/main/java/dev/baseline/AppContainer.kt, BaselineApp.kt, ui/nav/BaselineNavHost.kt   Tasks 2–16
tools/fake_llm.py, tools/test_fake_llm.py                                                   Task 17
```

---

### Task 1: AI mode, prefs and key vault

**Files:**
- Create: `app/src/main/java/dev/baseline/ai/AiMode.kt`, `ai/AiPrefs.kt`, `ai/AiKeyVault.kt`
- Test: `app/src/test/java/dev/baseline/ai/AiModeTest.kt`, `ai/AiKeyVaultTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class AiMode { OFF, ON_DEVICE, CLOUD, HYBRID }
  data class ModeOption(val mode: AiMode, val enabled: Boolean, val reason: String?)
  object AiModes {
      fun initial(nanoSupported: Boolean): AiMode
      fun afterKeySaved(current: AiMode?, nanoSupported: Boolean): AiMode
      fun afterKeyRemoved(current: AiMode?, nanoSupported: Boolean): AiMode
      fun afterNanoUnavailable(current: AiMode): AiMode
      fun options(nanoSupported: Boolean, keySet: Boolean): List<ModeOption>   // OFF, ON_DEVICE, CLOUD, HYBRID in that order
  }
  class AiPrefs(store: DataStore<Preferences>) { val mode: Flow<AiMode?>; suspend fun setMode(mode: AiMode) }
  data class AiConfig(val baseUrl: HttpUrl, val key: String, val model: String, val toolsSupported: Boolean)
  sealed interface AiKeyStatus {
      data object Missing : AiKeyStatus
      data class Set(val host: String, val model: String, val toolsSupported: Boolean, val invalid: Boolean) : AiKeyStatus
  }
  class AiKeyVault(store: DataStore<Preferences>, encrypt: (ByteArray) -> ByteArray, decrypt: (ByteArray) -> ByteArray) {
      val status: Flow<AiKeyStatus>
      suspend fun config(): AiConfig?          // null when absent or undecryptable (which also clears)
      suspend fun save(baseUrl: HttpUrl, key: String, model: String, toolsSupported: Boolean)   // one edit; clears invalid
      suspend fun setModel(model: String, toolsSupported: Boolean)
      suspend fun markInvalid(); suspend fun markValid(); suspend fun clear()
  }
  ```
  DataStore keys: `ai_mode`, `ai_base_url`, `ai_key`, `ai_model`, `ai_tools_supported`, `ai_key_invalid`.

- [ ] **Step 1: Write the failing tests**

`AiModeTest`, with these cases:
- `initial(true) == ON_DEVICE`, `initial(false) == OFF`.
- `afterKeySaved`: `OFF`, `ON_DEVICE` and `null` become `HYBRID` when Nano is supported, and `CLOUD` when it isn't. `CLOUD` and `HYBRID` are unchanged.
- `afterKeyRemoved`: `CLOUD` and `HYBRID` become `ON_DEVICE` when supported, and `OFF` when not. `ON_DEVICE` and `OFF` are unchanged.
- `afterNanoUnavailable`: `ON_DEVICE` → `OFF`, `HYBRID` → `CLOUD`, and the others are unchanged.
- `options(nanoSupported = false, keySet = false)`:
  - OFF is enabled with no reason.
  - ON_DEVICE has the reason `"Not supported on this phone"`.
  - CLOUD has the reason `"Add an API key"`.
  - HYBRID has the reason `"Not supported on this phone · Add an API key"`.

`AiKeyVaultTest`, using identity ciphers and a temp-file DataStore:
- After `save`, `config()` round-trips. `status` is `Set("openrouter.ai", "deepseek/deepseek-v4.1-flash", true, invalid = false)`.
- `markInvalid` sets `invalid = true`, and `markValid` clears it.
- `clear()` gives `Missing`.
- A decrypt that throws `GeneralSecurityException` makes `config()` return null and `status` become `Missing`.
- `save` after `markInvalid` resets `invalid` to false.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'dev.baseline.ai.AiModeTest' --tests 'dev.baseline.ai.AiKeyVaultTest'`
Expected: compilation failure (types not defined).

- [ ] **Step 3: Implement `AiMode.kt`, `AiPrefs.kt` and `AiKeyVault.kt`**

Follow `ApiKeyVault`: Base64 over the cipher output, and Keystore work on `Dispatchers.IO`.

- [ ] **Step 4: Run the tests to verify they pass** (same command; expected PASS)

- [ ] **Step 5: Commit:** `feat(ai): AI mode, prefs and encrypted AI key vault`

---

### Task 2: Engine contract and `aiHttp`

**Files:**
- Create: `app/src/main/java/dev/baseline/ai/engine/AiEngine.kt`
- Modify: `app/src/main/java/dev/baseline/AppContainer.kt`: add `internal val aiHttp`, `val aiKeyVault`, `val aiPrefs`
- Test: `app/src/test/java/dev/baseline/ai/AiSecretsTest.kt` (the client half; Task 3 adds the request half), `ai/engine/EngineLabelTest.kt`

**Interfaces:**
- Consumes: Task 1.
- Produces:
  ```kotlin
  enum class EngineKind { NANO, CLOUD }
  data class EngineLabel(val kind: EngineKind, val model: String?) { val text: String }   // "On-device" | "Cloud · <id after last '/'>"
  enum class Role { USER, ASSISTANT }
  data class ChatTurn(val role: Role, val text: String)
  enum class RequestKind { CHAT, INSIGHT }
  data class AiRequest(val kind: RequestKind, val system: String, val pack: String, val turns: List<ChatTurn>)
      // the engines send system + "\n\n" + pack as the system message, then turns
  sealed interface AiEvent {
      data class Text(val delta: String) : AiEvent
      data class ToolStatus(val label: String) : AiEvent
      data class Done(val label: EngineLabel) : AiEvent
  }
  sealed class AiFailure : Exception() {   // message is never set from provider data
      data object KeyRejected; data object NoCredit; data object ModelGone
      data class Busy(val host: String); data class Unreachable(val host: String)
      data object Offline; data object Malformed
      data object NanoBusy; data object NanoQuota; data object NanoBackground; data object NanoUnavailable
  }
  fun AiFailure.chatMessage(): String     // §8 "Chat" column, verbatim
  fun AiFailure.insightMessage(): String  // §8 "Insight card" column ("Couldn't generate" for Malformed)
  interface AiEngine { fun run(req: AiRequest): Flow<AiEvent> }
  ```
  `AiFailure` subclasses are all `AiFailure()` objects or classes; write them as nested declarations in the file.

- [ ] **Step 1: Write the failing tests**

`EngineLabelTest`:
- `EngineLabel(NANO, "nano-v4").text == "On-device"`.
- `EngineLabel(CLOUD, "deepseek/deepseek-v4.1-flash").text == "Cloud · deepseek-v4.1-flash"`.
- `EngineLabel(CLOUD, "gpt-x").text == "Cloud · gpt-x"`.
- `AiFailure.KeyRejected.chatMessage() == "Your API key was rejected. Check it in Settings."`.
- `AiFailure.Unreachable("openrouter.ai").chatMessage() == "Couldn't reach openrouter.ai."`.
- `AiFailure.Busy("openrouter.ai").chatMessage() == "openrouter.ai is busy. Try again shortly."`.
- `AiFailure.Malformed.chatMessage() == "Something went wrong reading the answer."`.
- Every `AiFailure` has a null `message`.

`AiSecretsTest.aiClientCarriesNothing` (Robolectric, modelled on `UpdatesUrlTest.theUpdateClientCarriesNothingOfGarmins`):
- `container.aiHttp.authenticator` is `Authenticator.NONE`, and both interceptor lists are empty.
- `aiHttp` is not the same instance as `updateHttp`.

- [ ] **Step 2: Run the tests to verify they fail:** `./gradlew :app:testDebugUnitTest --tests 'dev.baseline.ai.*'` (compile failure)

- [ ] **Step 3: Implement `AiEngine.kt`, then add `aiHttp`, `aiKeyVault` (using `aead::encrypt` and `aead::decrypt`) and `aiPrefs` to `AppContainer`.** Put a comment above `aiHttp` in the style of the `updateHttp` comment: "The AI provider, not Garmin or GitHub: no Authenticator, no interceptors (spec: AI assistant §4.2)."

- [ ] **Step 4: Run the tests to verify they pass**

- [ ] **Step 5: Commit:** `feat(ai): engine contract and a bare aiHttp client`

---

### Task 3: `CloudEngine` streaming and errors

**Files:**
- Create: `app/src/main/java/dev/baseline/ai/engine/OpenAiWire.kt`, `ai/engine/CloudEngine.kt`
- Modify: `AppContainer.kt` (`val cloudEngine`, wired with the `NoTools` runner until Task 8)
- Test: `app/src/test/java/dev/baseline/ai/engine/CloudEngineTest.kt`, `ai/AiSecretsTest.kt` (add)

**Interfaces:**
- Consumes: Tasks 1–2.
- Produces:
  ```kotlin
  interface ToolRunner {                      // implemented by HealthTools in Task 8
      val definitions: JsonArray              // OpenAI "tools" array
      suspend fun call(name: String, argumentsJson: String): ToolResult
  }
  data class ToolResult(val statusLabel: String, val content: String)
  object NoTools : ToolRunner                 // empty definitions; call() returns {"error":"unknown tool"}
  class CloudEngine(
      http: OkHttpClient,
      vault: AiKeyVault,
      tools: ToolRunner,
      online: () -> Boolean,
      delay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
  ) : AiEngine
  fun parseSseLine(line: String): SseLine     // in OpenAiWire.kt
  sealed interface SseLine { data class Data(val json: String) : SseLine; data object Done : SseLine; data object Skip : SseLine }
  ```

**Behaviour this task pins:**
- Request: `POST {baseUrl}/chat/completions` with `model`, `messages`, `stream: true`, `temperature: 0.3`, and `max_tokens` (1,024 for CHAT, 200 for INSIGHT).
- `Authorization: Bearer <key>` only when `request.url.host == config.baseUrl.host`.
- Lines: `data: [DONE]` is Done; `data: {…}` is Data; blank lines and lines starting with `:` are Skip.
- Status mapping:
  - 401 or 403: `vault.markInvalid()`, then `KeyRejected`.
  - 402: `NoCredit`.
  - 404, or a 400 whose body has `"model"` in `error.code` or `error.param`: `ModelGone`. The body is read only for that match, then discarded.
  - 429: wait `min(Retry-After, 10) s` (2 s if absent), retry once, then `Busy(host)`.
  - 5xx, `IOException` or a timeout: `Unreachable(host)`.
  - Not `online()` before the call: `Offline`.
- Any 200: `vault.markValid()` when the key was invalid.
- A stream that finishes with no text and no tool calls is `Malformed`.

- [ ] **Step 1: Write the failing tests** (`MockWebServer` serving `text/event-stream` bodies; a vault saved with the server's URL)

- `streamsTextDeltasThenDone`: two chunks `{"choices":[{"delta":{"content":"Hel"}}]}` and `…"lo"…`, then `[DONE]`. The events are `Text("Hel")`, `Text("lo")`, `Done(EngineLabel(CLOUD, model))`.
- `ignoresSseCommentLines`: the body begins with `: OPENROUTER PROCESSING\n\n`. The same events as above, with no error.
- `sendsChatAndInsightLimits`: the recorded request JSON has `"max_tokens":1024`, `"stream":true` and `"temperature":0.3` for CHAT, and 200 for INSIGHT. The first message is `{"role":"system","content":"<system>\n\n<pack>"}`.
- `noToolsFieldWithoutToolSupport`: with `toolsSupported = false`, the body has no `"tools"` key.
- `authorizationOnlyToConfiguredHost`: the recorded request has `Authorization: Bearer test-key`. A second `MockWebServer` on another host is never contacted (assert its `requestCount == 0`).
- `unauthorizedMarksKeyInvalid`: a 401 throws `AiFailure.KeyRejected`, and `vault.status.first()` has `invalid = true`. A later 200 clears it.
- `paymentRequiredIsNoCredit` (402), `notFoundIsModelGone` (404), `serverErrorIsUnreachable` (503), `offlineFailsFast` (`online = { false }`, so `server.requestCount == 0`).
- `retriesOnceAfterRetryAfter`: a 429 with `Retry-After: 3`, then a 200. The recorded delays are `[3000]`, and the answer streams. A second 429 throws `Busy("127.0.0.1")` (or the server's host).
- `retryAfterIsCappedAtTen`: `Retry-After: 60` gives a delay of 10,000.
- `emptyAnswerIsMalformed`: `[DONE]` with no deltas throws `AiFailure.Malformed`.
- `cancellingCancelsTheCall`: a server body with a 5 s throttle; cancel the collecting job after the first event; `server.takeRequest()` completes, and the job ends without waiting 5 s.

`AiSecretsTest.errorBodyNeverSurfaces`: a 401 whose body is `{"error":{"message":"bad key test-key"}}` gives an exception with a null `message`, and `toString()` doesn't contain `test-key`.

- [ ] **Step 2: Run them to verify they fail:** `./gradlew :app:testDebugUnitTest --tests 'dev.baseline.ai.engine.CloudEngineTest' --tests 'dev.baseline.ai.AiSecretsTest'`

- [ ] **Step 3: Implement the wire types (`@Serializable`, `ignoreUnknownKeys = true`), `parseSseLine` and `CloudEngine.run`**

Read the response with `body.source().readUtf8Line()` in a `callbackFlow` or `flow` on `Dispatchers.IO`. Cancel the `Call` in `awaitClose` or `finally`. The tool loop stays a stub in this task: a `finish_reason: "tool_calls"` is treated as `Malformed`, and Task 4 replaces that.

- [ ] **Step 4: Run them to verify they pass**

- [ ] **Step 5: Commit:** `feat(ai): OpenAI-compatible streaming client with error mapping`

---

### Task 4: `CloudEngine` tool loop and context budget

**Files:**
- Modify: `app/src/main/java/dev/baseline/ai/engine/CloudEngine.kt`
- Test: `app/src/test/java/dev/baseline/ai/engine/CloudEngineToolsTest.kt`

**Interfaces:**
- Consumes: `ToolRunner` and `ToolResult` (Task 3).
- Produces: no new API. The behaviour is §4.2's tool loop, with tools offered only when `toolsSupported && req.kind == CHAT`.

**Algorithm:**
1. Accumulate `delta.tool_calls[i].id`, `.function.name` and `.function.arguments` by `index` across chunks.
2. On `finish_reason == "tool_calls"`:
   - Append `{"role":"assistant","tool_calls":[…]}`.
   - For each call: emit `ToolStatus(result.statusLabel)` and append `{"role":"tool","tool_call_id":…,"content":result.content}`.
   - Request again.
3. Round 7 is sent with `"tool_choice":"none"` and no further tool execution.
4. **Budget:** before each request, if `sum(content.length)/4 > 60_000`, replace the oldest `role:"tool"` contents with `"[earlier lookup removed to save space]"`, oldest first, until it fits.

- [ ] **Step 1: Write the failing tests** (`MockWebServer` with queued responses; a fake `ToolRunner` that records calls)

- `toolCallSplitAcrossChunksIsAssembled`: chunk 1 has `{"index":0,"id":"c1","function":{"name":"daily_metrics","arguments":"{\"from\":"}}` and chunk 2 has `{"index":0,"function":{"arguments":"\"2026-10-01\"}"}}`, with `finish_reason: "tool_calls"`. The fake receives `("daily_metrics", "{\"from\":\"2026-10-01\"}")`.
- `runsToolsThenAnswers`: response 1 is a tool call, response 2 is text. The events are `ToolStatus("Looking up …")`, the `Text` events, then `Done`. Request 2's messages end with the assistant `tool_calls` message and `{"role":"tool","tool_call_id":"c1",…}`.
- `stopsAfterSixRoundsWithToolChoiceNone`: 7 queued tool-call responses, then text. `server.requestCount == 7` before the text; request 7 has `"tool_choice":"none"`; the fake was called 6 times.
- `emptyArgumentsMeanNoArguments`: arguments `""` reach the runner as `"{}"`.
- `unknownToolIsAnErrorResult`: with `NoTools`, a call to `"nope"` appends `{"error":"unknown tool"}` and counts as a round.
- `insightsNeverOfferTools`: an INSIGHT request body has no `"tools"` even with `toolsSupported = true`.
- `budgetDropsOldestToolResultsFirst`: three rounds whose tool results are each 100,000 characters. The 4th request has the first two replaced by the placeholder text, and the third intact.

- [ ] **Step 2: Run them to verify they fail:** `./gradlew :app:testDebugUnitTest --tests 'dev.baseline.ai.engine.CloudEngineToolsTest'`
- [ ] **Step 3: Implement the loop and the budget in `CloudEngine`**
- [ ] **Step 4: Run `CloudEngineTest` and `CloudEngineToolsTest` to verify both pass**
- [ ] **Step 5: Commit:** `feat(ai): cloud tool loop with a six-round cap and context budget`

---

### Task 5: `AiKeyEntry`: URL, models, test and save

**Files:**
- Create: `app/src/main/java/dev/baseline/ai/AiKeyEntry.kt`
- Modify: `app/build.gradle.kts`: `buildConfigField("boolean", "ALLOW_LOOPBACK_HTTP", "$testBuild")`. `AppContainer.kt`: `val aiKeyEntry`.
- Test: `app/src/test/java/dev/baseline/ai/AiKeyEntryTest.kt`

**Interfaces:**
- Consumes: Tasks 1–2.
- Produces:
  ```kotlin
  fun parseBaseUrl(input: String, allowLoopbackHttp: Boolean): HttpUrl?   // trims; https only, or http to 127.0.0.1/localhost when allowed; drops one trailing '/'
  data class ModelInfo(val id: String, val contextLength: Int?, val promptPrice: Double?, val completionPrice: Double?, val tools: Boolean?) {
      val pricePerQuestion: Double?   // 10_000 * promptPrice + 500 * completionPrice, null if either is null
  }
  fun formatPricePerQuestion(usd: Double): String   // "≈ $0.0007 per question" (two significant digits)
  sealed interface ModelsResult { data class Listed(val models: List<ModelInfo>); data object FreeText; data object KeyRejected; data class Unreachable(val host: String) }
  sealed interface TestOutcome { data class Saved(val toolsSupported: Boolean) : TestOutcome; data class Failed(val message: String) : TestOutcome }
  class AiKeyEntry(http: OkHttpClient, vault: AiKeyVault, prefs: AiPrefs, nanoSupported: suspend () -> Boolean, allowLoopbackHttp: Boolean) {
      fun cleanKey(input: String): String?                       // trimmed; null if empty or has inner whitespace
      suspend fun listModels(baseUrl: HttpUrl, key: String): ModelsResult   // Listed sorted: tools == true first, then by id
      suspend fun saveAndTest(baseUrl: HttpUrl, key: String, model: String): TestOutcome
      suspend fun changeModel(model: String): TestOutcome        // uses the stored URL and key; never rewrites the key
      suspend fun remove()                                       // vault.clear(), then the afterKeyRemoved transition
  }
  const val DEFAULT_AI_BASE_URL = "https://openrouter.ai/api/v1"
  const val SUGGESTED_MODEL = "deepseek/deepseek-v4.1-flash"
  ```
  The `saveAndTest` request body is exactly §4.3's JSON. The outcomes follow §4.3's table. The failure messages are:
  - `"Key rejected."`
  - `"No credit left on this account."`
  - `"This model isn't available here."`
  - `"Couldn't reach <host>."`
  - For a bad key format: `"That doesn't look like a complete key."`

  On `Saved`: `vault.save(...)` in one edit, then `prefs.setMode(AiModes.afterKeySaved(prefs.mode.first(), nanoSupported()))`. Nothing touches the vault before the test passes.

- [ ] **Step 1: Write the failing tests** (`MockWebServer`)

- `parsesAndValidatesBaseUrl`:
  - `parseBaseUrl("https://openrouter.ai/api/v1", false)` is not null.
  - `"http://openrouter.ai/api/v1"` gives null.
  - `"http://127.0.0.1:8090/v1"` gives null when `false`, and is not null when `true`.
  - `"http://10.0.2.2/v1"` gives null even when `true`.
  - `"not a url"` gives null.
- `trailingSlashBaseUrlJoinsCleanly`: with `parseBaseUrl("<server>/v1/", true)`, `saveAndTest` posts to path `/v1/chat/completions` and `listModels` gets `/v1/models`.
- `trimsKeyAndRejectsInnerWhitespace`:
  - `cleanKey("  sk-or-abc\n") == "sk-or-abc"`.
  - `cleanKey("sk or abc")` and `cleanKey("  ")` are null.
- `listsOpenRouterModelsToolsFirst`: a body of three models with `supported_parameters`, `pricing` and `context_length` gives `Listed`, with the tool models first. `deepseek/deepseek-v4.1-flash` has `pricePerQuestion ≈ 0.0007` (from `0.000000045` and `0.0000005`), and `formatPricePerQuestion(0.0007) == "≈ $0.0007 per question"`.
- `modelsWithoutMetadataOrFailingFallBackToFreeText`: a plain `{"data":[{"id":"m"}]}` gives `Listed` with null metadata. A 500 or an empty `data` gives `FreeText`. A 401 gives `KeyRejected`.
- `toolCallSavesWithTools`: a 200 with `tool_calls` gives `Saved(true)`, the vault config equals the inputs, and the mode moves from `ON_DEVICE` to `HYBRID`.
- `noToolCallSavesWithoutTools`: a 200 with text gives `Saved(false)`.
- `requiredRejectedFallsBackToAutoThenNoTools`: 400, then 400, then 200. The three request bodies have `"tool_choice":"required"`, then `"tool_choice":"auto"`, then no `"tools"`. The result is `Saved(false)`.
- `failingTestLeavesVaultEmpty`: for each of 401, 402, 404 and a disconnect, the result is `Failed` with the exact message above, `vault.status.first() == Missing`, and the mode is unchanged.
- `changeModelRetestsWithoutRewritingKey`: after a save, `changeModel("other")` uses the stored key (assert the recorded `Authorization` header) and the stored model becomes `other`. A failing re-test leaves the old model in place.
- `removeAppliesTransition`: from `HYBRID` with Nano supported, `remove()` leaves `Missing` and `ON_DEVICE`.

- [ ] **Step 2: Run them to verify they fail:** `./gradlew :app:testDebugUnitTest --tests 'dev.baseline.ai.AiKeyEntryTest'`
- [ ] **Step 3: Implement `AiKeyEntry.kt` and the BuildConfig field. Wire `aiKeyEntry` in `AppContainer`, using `{ nanoEngine.status.value !is NanoStatus.Unavailable }` once Task 6 lands, and `{ false }` until then.**
- [ ] **Step 4: Run them to verify they pass**
- [ ] **Step 5: Commit:** `feat(ai): key entry with model list, tool-call test and test-then-save`

---

### Task 6: Gemini Nano engine and startup

**Files:**
- Modify: `gradle/libs.versions.toml` (`mlkitGenaiPrompt` and the `mlkit-genai-prompt` library), `app/build.gradle.kts` (`implementation(libs.mlkit.genai.prompt)`)
- Create: `app/src/main/java/dev/baseline/ai/engine/NanoBackend.kt`, `NanoEngine.kt`, `MlKitNanoBackend.kt`, `ai/AiStartup.kt`
- Modify: `AppContainer.kt` (`open val nanoBackend: NanoBackend by lazy { MlKitNanoBackend(appContext) }`, `val nanoEngine`), `BaselineApp.kt` (call `container.startAi()` next to `Updater.start()`)
- Test: `app/src/test/java/dev/baseline/ai/engine/NanoEngineTest.kt`, `ai/AiStartupTest.kt`

**Interfaces:**
- Consumes: Tasks 1–2.
- Produces:
  ```kotlin
  enum class NanoAvailability { UNAVAILABLE, DOWNLOADABLE, DOWNLOADING, AVAILABLE }
  enum class NanoError { BUSY, QUOTA, BACKGROUND, NOT_AVAILABLE, OTHER }
  class NanoBackendException(val error: NanoError) : Exception()
  interface NanoBackend {
      suspend fun availability(): NanoAvailability
      fun download(): Flow<Float?>                 // progress 0..1 or null if unknown; completes when done
      suspend fun baseModelName(): String
      suspend fun tokenLimit(): Int
      suspend fun systemPromptSupported(): Boolean
      suspend fun countTokens(system: String, turns: List<ChatTurn>, systemAsInstruction: Boolean): Int
      fun stream(system: String, turns: List<ChatTurn>, systemAsInstruction: Boolean, maxOutput: Int, temperature: Float): Flow<String>
      suspend fun warmup()
  }
  sealed interface NanoStatus {
      data object Unavailable; data object Downloadable; data class Downloading(val progress: Float?)
      data class Ready(val modelName: String, val tokenLimit: Int); data class Resting(val until: Instant)
  }
  class NanoEngine(backend: NanoBackend, clock: Clock, zone: StateFlow<ZoneId>) : AiEngine {
      val status: StateFlow<NanoStatus>       // starts Unavailable until refresh()
      val known: StateFlow<Boolean>           // false until the first refresh() completes
      suspend fun refresh()
      fun download(): Flow<Float?>            // drives status through Downloading to Ready
      suspend fun fits(req: AiRequest): Boolean   // countTokens + maxOutput (256 CHAT, 160 INSIGHT) <= tokenLimit
      suspend fun warmup()
  }
  suspend fun AppContainer.startAi()          // in AiStartup.kt
  ```
- **`NanoEngine.run` behaviour:**
  - It throws `NanoBusy`, `NanoQuota`, `NanoBackground` or `NanoUnavailable` for the matching `NanoError`.
  - `QUOTA` also sets `Resting(until = the next local midnight in zone.value)`.
  - `refresh()` returns from `Resting` to `Ready` only once `clock.instant() >= until`.
  - It applies a 60 s timeout (`AiFailure.NanoBusy` on timeout).
  - It emits `Done(EngineLabel(NANO, modelName))`.
  - It does **no** `BUSY` retry; `ChatSession` owns retries.
- **`startAi()`:**
  1. Calls `nanoEngine.refresh()`.
  2. If `aiPrefs.mode.first() == null`, sets `AiModes.initial(status !is Unavailable)`.
  3. Otherwise, if the status is `Unavailable`, sets `afterNanoUnavailable(current)`.

  It runs on `appScope`.

- [ ] **Step 1: Write the failing tests** (a `FakeNanoBackend` with scripted values)

- `mapsAvailabilityToStatus`: AVAILABLE gives `Ready("nano-v4", 4096)`; DOWNLOADABLE gives `Downloadable`; UNAVAILABLE gives `Unavailable`; `known` becomes true.
- `fitsUsesTokenLimitAndOutputReserve`: limit 4096 and count 3840 gives true for CHAT (3840 + 256 = 4096) and true for INSIGHT. Count 3841 gives false for CHAT.
- `systemPromptFallsBackToPrependedWhenUnsupported`: with `systemPromptSupported = false`, the fake records `systemAsInstruction = false` and the first turn text starts with the system text.
- `quotaRestsUntilLocalMidnight`: with clock 2026-11-01T22:00Z and zone `Asia/Tokyo` (where it's already 07:00 on 2 Nov), `QUOTA` gives `Resting(2026-11-02T15:00:00Z)`. `refresh()` at 14:59Z is still `Resting`; at 15:00Z it's `Ready`.
- `errorsMapToFailures`: BUSY gives `NanoBusy`, BACKGROUND gives `NanoBackground`, NOT_AVAILABLE gives `NanoUnavailable`.
- `streamsAndLabels`: the deltas, then `Done(EngineLabel(NANO, "nano-v4"))`.

`AiStartupTest` (Robolectric, a container subclass overriding `nanoBackend`):
- A null mode with AVAILABLE becomes `ON_DEVICE`.
- A null mode with UNAVAILABLE becomes `OFF`.
- `HYBRID` with UNAVAILABLE becomes `CLOUD`.

- [ ] **Step 2: Run them to verify they fail:** `./gradlew :app:testDebugUnitTest --tests 'dev.baseline.ai.engine.NanoEngineTest' --tests 'dev.baseline.ai.AiStartupTest'`

- [ ] **Step 3: Add the dependency and implement `NanoEngine` and `startAi`**

Then implement `MlKitNanoBackend` against ML Kit's current `GenerativeModel` API:
- `checkStatus` and `FeatureStatus`, `download()`, `getBaseModelName`, `getTokenLimit`, `getAvailableFeatures`, `countTokens`, `generateContentStream` and `warmup`.
- Map `GenAiException` error codes to `NanoError` (BUSY, PER_APP_BATTERY_USE_QUOTA_EXCEEDED → QUOTA, BACKGROUND_USE_BLOCKED → BACKGROUND, NOT_AVAILABLE).

This file isn't unit-tested, since ML Kit can't load on the JVM. Keep it the only file that imports `com.google.mlkit`.

- [ ] **Step 4: Run them to verify they pass, then run `./gradlew :app:assembleDebug` (the ML Kit dependency resolves and compiles)**
- [ ] **Step 5: Commit:** `feat(ai): Gemini Nano engine behind NanoBackend, with mode defaults on start`

---

### Task 7: Scopes and context packs

**Files:**
- Create: `app/src/main/java/dev/baseline/ai/context/AskScope.kt`, `context/ContextPacks.kt`
- Modify: `AppContainer.kt` (`val contextPacks`)
- Test: `app/src/test/java/dev/baseline/ai/context/AskScopeTest.kt`, `ContextPacksTest.kt`, `PrivacyScanTest.kt`

**Interfaces:**
- Consumes: `Repository`, `AppDatabase` DAOs (days, scores, activities, laps, swim lengths, strength sets, best efforts), `Baselines.at`, `Recovery.compute`, `TrainingLoad.series` and `weekBudget`, `UserPrefs.strainCalibration`, `Repository.imperial` and `firstDayOfWeek`.
- Produces:
  ```kotlin
  sealed interface AskScope {
      data class Today(val date: LocalDate) : AskScope
      data class Sleep(val date: LocalDate) : AskScope
      data class Activity(val id: Long, val sport: SportFamily?) : AskScope
      data class Trends(val days: Int) : AskScope           // 7, 30 or 90 (TrendRange.days); keeps ai/ free of ui types
      data object General : AskScope
  }
  fun AskScope.title(today: LocalDate): String          // §3.5 table, verbatim
  fun AskScope.subject(today: LocalDate): String        // "today" | "last night" | "this run" … | "your trends" | "your health"; used in hints, links and placeholders
  fun AskScope.insightKey(): Pair<String, String>?      // ("today", iso) | ("sleep", iso) | ("activity", id); null for Trends and General
  enum class PackSize { COMPACT, FULL }
  data class PackFacts(val recovery: Int?, val sleepScore: Int?, val stageFurthest: Pair<String, Boolean>?, val rangeLabel: String?)
      // stageFurthest = ("deep"|"light"|"REM", aboveBaseline)
  data class Pack(val text: String, val facts: PackFacts, val hasData: Boolean)
  class ContextPacks(db: AppDatabase, repository: Repository, userPrefs: UserPrefs, today: StateFlow<LocalDate>, zone: StateFlow<ZoneId>) {
      suspend fun build(scope: AskScope, size: PackSize): Pack     // General builds Today(today.value)
      suspend fun activityBody(id: Long, size: PackSize): String?  // reused by HealthTools.activity_detail
      suspend fun sleepBody(date: LocalDate, size: PackSize): String?   // reused by HealthTools.sleep_night
  }
  fun estimateTokens(text: String): Int = (text.length + 3) / 4
  ```
- **Pack content:** §5.1, in this order:
  1. The header lines exactly as in §5.1's example: `Today is EEE d MMM yyyy (<zone id>). Units: metric|imperial.`, the `Scope:` line, then the glossary block.
  2. The scope body.

  Missing values are written `no data`. `hasData` is false when the scope's main row is absent: no `ScoreEntity` and no `DayEntity` for Today; no `sleepSeconds` for Sleep; no activity for Activity. An activity is described by its sport and local start time (`Run, Sun 1 Nov 07:05`) and never by its `name`. Compact packs keep at most 7 nights, 3 previous activities and 14 trend rows; full packs keep 30 nights, 10 previous activities and every day in the range.

- [ ] **Step 1: Write the failing tests**

`AskScopeTest`, with today = 2026-11-01:
- `Today(2026-11-01).title == "Ask about today"`, and `Today(2026-10-27).title == "Ask about Tue 27 Oct"`.
- `Sleep(2026-10-31).title == "Ask about last night"`, and `Sleep(2026-10-27).title == "Ask about the night of 27 Oct"`.
- Activity with RUN gives `"Ask about this run"`, RIDE `"this ride"`, SWIM `"this swim"`, STRENGTH `"this session"`, OTHER or null `"this activity"`.
- Trends gives `"Ask about your trends"`; General gives `"Ask about your health"`.
- `insightKey` values as specified.

`ContextPacksTest` (in-memory DB seeded with hand-built entities; `Clock.fixed(2026-11-01T07:00Z)`; zone `Europe/London`; runs the same under `TimeZone.setDefault(Asia/Tokyo)`):
- `todayPackHeaderAndBody`: the text starts with `"Today is Sun 1 Nov 2026 (Europe/London). Units: metric.\nScope: today, Sun 1 Nov."` and contains `"Recovery 71% (green)"`, `"HRV 58 ms vs 30-day baseline 53 ms (+9%)"` and `"This week: 67% of normal"`.
- `missingValuesSayNoData`: a day with no `restingHr` gives `"Resting HR no data"`, and the text contains no `"null"`.
- `imperialUnits`: with an imperial profile, a 10,200 m run reads `"6.34 mi"` and pace `"min/mi"`.
- `activityPackHasNoName`: an activity whose `name` is `"Secret Lane loop"` gives a pack containing `"Run, Sun 1 Nov 07:05"` and not the name.
- `compactStaysUnderBudget`: for every scope over 120 seeded days, `estimateTokens(compact.text) <= 1200`.
- `fullTrendsHasEveryDay`: `Trends(90)` FULL has 90 day rows; COMPACT has 14 rows plus a summary line per metric.
- `hasDataFalseWithoutMainRow`: `Sleep(date)` with no sleep gives `hasData == false`.
- `factsFilled`: `recovery == 71`; `stageFurthest == ("deep", true)` when deep is furthest above its average.

`PrivacyScanTest`:
- Ingest every fixture in `app/src/test/resources/fixtures/` (JSON through `RawIngest.apply` as `HistorySanityTest` does, FIT through `FitIngest` for the activities that have a `.fit`).
- Build every scope's pack at both sizes, plus every activity's `activityBody`.
- Assert that no text matches `-?\d{1,3}\.\d{4,}\s*,\s*-?\d{1,3}\.\d{4,}` (coordinate pairs) or `(?i)\b(lat|lon|lng|latitude|longitude|polyline)\b`.
- Assert that no text contains any `ActivityEntity.name`, the profile's display name or user ID, or any device ID or serial value from the fixtures (read those values from the fixture JSON in the test).

- [ ] **Step 2: Run them to verify they fail:** `./gradlew :app:testDebugUnitTest --tests 'dev.baseline.ai.context.*'`
- [ ] **Step 3: Implement `AskScope.kt` and `ContextPacks.kt`.** Reuse `Formatters` for durations, paces and distances, so units match the screens.
- [ ] **Step 4: Run them to verify they pass**
- [ ] **Step 5: Commit:** `feat(ai): ask scopes and context packs with a privacy scan`

---

### Task 8: Health tools

**Files:**
- Create: `app/src/main/java/dev/baseline/ai/context/HealthTools.kt`
- Modify: `AppContainer.kt` (`val healthTools`, then pass it to `cloudEngine` in place of `NoTools`)
- Test: `app/src/test/java/dev/baseline/ai/context/HealthToolsTest.kt`, and extend `PrivacyScanTest` with tool results

**Interfaces:**
- Consumes: `ToolRunner` and `ToolResult` (Task 3), `ContextPacks.activityBody` and `sleepBody` (Task 7).
- Produces: `class HealthTools(db: AppDatabase, packs: ContextPacks, userPrefs: UserPrefs, today: StateFlow<LocalDate>) : ToolRunner`, with the six tools in §5.2. Names, arguments and JSON-schema `parameters` are exactly as in that table.
- **Status labels:**
  - `daily_metrics`: `"Looking up <fields joined by ", ">, <from d MMM> – <to d MMM>"`, for example `"Looking up HRV, 1 Sep – 31 Oct"`. Field display names: hrv → HRV, rhr → resting HR, sleep_performance → sleep, sleep_hours → sleep hours, respiration → breathing rate, body_battery_wake → Body Battery, stress → stress, steps → steps, recovery → recovery, strain → strain.
  - `activities`: `"Looking up activities, …"` (with the sport when given: `"Looking up runs, …"`).
  - `activity_detail`: `"Looking up an activity"`.
  - `sleep_night`: `"Looking up sleep, <d MMM>"`.
  - `training_load`: `"Looking up training load, …"`.
  - `records`: `"Looking up records"`.
- **Results:** compact JSON rows; missing values are JSON `null`. Content over 32,000 characters is cut at a row boundary and ends with `"(truncated — ask for a shorter range)"`. Argument errors return `{"error":"<reason>"}` with the label `"Looking up …"`.

- [ ] **Step 1: Write the failing tests**
  - `definitionsMatchSpec`: names in order, and `daily_metrics.parameters.properties.fields.items.enum` equals the ten §5.2 fields.
  - `dailyMetricsRowsAndLabel`: 30 seeded days give 30 rows with the requested fields only, and the label `"Looking up HRV, 2 Oct – 31 Oct"`.
  - `rejectsOverLongRange`: 401 days gives `{"error":"range is longer than 400 days"}`.
  - `rejectsUnknownField`: gives `{"error":"unknown field: vo2"}`.
  - `rejectsBadDate`: gives `{"error":"from must be an ISO date (yyyy-mm-dd)"}`.
  - `activitiesFilterBySport`, `trainingLoadRows`, `recordsBySport`.
  - `truncatesAtCap`: 400 days of all fields with a padded fake gives `content.length <= 32_000`, ending with the truncation note.
  - `activityDetailUnknownId`: gives `{"error":"no activity with id 999"}`.
- [ ] **Step 2: Run them to verify they fail:** `./gradlew :app:testDebugUnitTest --tests 'dev.baseline.ai.context.HealthToolsTest' --tests 'dev.baseline.ai.context.PrivacyScanTest'`
- [ ] **Step 3: Implement `HealthTools` and swap it into `cloudEngine`**
- [ ] **Step 4: Run them to verify they pass**
- [ ] **Step 5: Commit:** `feat(ai): read-only health tools for the cloud model`

---

### Task 9: Prompts and suggestions

**Files:**
- Create: `app/src/main/java/dev/baseline/ai/context/Prompts.kt`, `context/Suggestions.kt`
- Test: `app/src/test/java/dev/baseline/ai/context/SuggestionsTest.kt`, `PromptsTest.kt`

**Interfaces:**
- Consumes: `AskScope`, `PackFacts` (Task 7), `EngineKind` (Task 2).
- Produces:
  ```kotlin
  object Prompts {
      const val PROMPT_VERSION = 1
      const val NEEDS_HISTORY = "NEEDS_HISTORY"
      fun chat(engine: EngineKind): String      // §5.3 cloud rules; NANO = trimmed + the NEEDS_HISTORY instruction
      fun insight(scope: AskScope): String      // §5.3 insight prompt with {day / night / activity}
  }
  enum class QuestionTag { PAGE, TRAINING, PATTERN }   // lives in AiRouter.kt (Task 10); Suggestions imports it
  data class Suggestion(val text: String, val tag: QuestionTag)
  object Suggestions {
      fun forScope(scope: AskScope, facts: PackFacts, cloudSlots: Boolean): List<Suggestion>   // at most 4
  }
  ```
  Define `QuestionTag` in `ai/AiRouter.kt` in this task (an enum only), so Task 10 adds the router beside it.
- **Copy:** every suggestion string in §3.7 is used verbatim, with `{recovery}`, `{stage}`, `{up/down}`, `{score}` and `{range}` filled from `PackFacts`. `{range}` is "week" for 7 days, "month" for 30 and "3 months" for 90. Order: page, page, training, pattern. When `cloudSlots` is false, the two spare page questions replace the training and pattern slots. A suggestion whose placeholder has no fact is dropped, and the next spare page question fills the slot. The result is never more than 4.

- [ ] **Step 1: Write the failing tests**
  - `todayWithCloud`: `Today` with `recovery = 71` gives `["Why is my recovery 71%?", "What does today's strain target mean for me?", "Can I train hard today?", "What's been affecting my HRV lately?"]`, with tags `[PAGE, PAGE, TRAINING, PATTERN]`.
  - `todayOnDevice`: gives `["Why is my recovery 71%?", "What does today's strain target mean for me?", "How does today compare to my baseline?", "What should I focus on today?"]`, all `PAGE`.
  - `missingFactDropsAndBackfills`: `Today` with `recovery = null` and `cloudSlots = true` gives `["What does today's strain target mean for me?", "How does today compare to my baseline?", "Can I train hard today?", "What's been affecting my HRV lately?"]`.
  - `sleepStageFilled`: `("deep", true)` gives `"Why was deep sleep up?"`.
  - `activityBySport`: RUN's first suggestion is `"How was my pacing?"`; SWIM's is `"How consistent were my lengths?"`; STRENGTH's is `"How hard was this session?"`.
  - `trendsRange`: 30 days gives `"What's changed over this month?"`.
  - `neverMoreThanFour`: for every scope and `cloudSlots` value, the size is at most 4.
  - `PromptsTest`:
    - `chat(NANO)` contains `"reply with exactly NEEDS_HISTORY"`; `chat(CLOUD)` doesn't.
    - Both contain `"Not medical advice"`.
    - `insight(Sleep(…))` contains `"this night"`.
- [ ] **Step 2: Run them to verify they fail:** `./gradlew :app:testDebugUnitTest --tests 'dev.baseline.ai.context.SuggestionsTest' --tests 'dev.baseline.ai.context.PromptsTest'`
- [ ] **Step 3: Implement `Prompts`, `Suggestions` and the `QuestionTag` enum**
- [ ] **Step 4: Run them to verify they pass**
- [ ] **Step 5: Commit:** `feat(ai): prompts and per-page suggestions`

---

### Task 10: Router and chat session

**Files:**
- Modify: `app/src/main/java/dev/baseline/ai/AiRouter.kt`
- Create: `app/src/main/java/dev/baseline/ai/ChatSession.kt`
- Test: `app/src/test/java/dev/baseline/ai/AiRouterTest.kt`, `ai/ChatSessionTest.kt`

**Interfaces:**
- Consumes: Tasks 1, 2, 6, 7, 9.
- Produces:
  ```kotlin
  data class RouteInput(
      val mode: AiMode, val tag: QuestionTag, val firstTurn: Boolean, val usedCloud: Boolean,
      val nanoUsable: Boolean, val fits: Boolean, val keySet: Boolean, val online: Boolean,
  ) { val cloudUsable get() = keySet && online }
  sealed interface Route { data object Nano : Route; data object Cloud : Route; data class Note(val text: String) : Route; data class Error(val text: String) : Route }
  object AiRouter {
      fun route(i: RouteInput): Route                 // §6 table, in order
      fun afterNeedsHistory(i: RouteInput): Route     // Cloud if Hybrid and cloud usable; else the §6 note for the mode
  }
  data class ChatMessage(
      val role: Role, val text: String, val label: EngineLabel? = null,
      val lookedUp: List<String> = emptyList(), val status: String? = null,   // status = the live tool line while it runs
      val error: String? = null, val note: Boolean = false, val streaming: Boolean = false,
  ) { val canMoreDetail: Boolean }   // set by ChatSession: Nano answer && mode == HYBRID && cloud usable
  class ChatSession(
      scope: AskScope, packs: ContextPacks, nano: NanoEngine, cloud: AiEngine,
      mode: () -> AiMode, keySet: () -> Boolean, online: () -> Boolean,
      delay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
  ) {
      val messages: StateFlow<List<ChatMessage>>
      suspend fun send(question: String, tag: QuestionTag)
      suspend fun moreDetail(index: Int)     // re-asks the user question before message[index] on the cloud; the chat becomes cloud
      suspend fun retry()                    // re-runs the last user turn
  }
  ```
  `usedCloud`, the turn count and the packs (one compact and one full, built on first use) are private state.
- **Notes and errors are verbatim from §6.** The Hybrid offline overflow note is `"You're offline, and this needs more than on-device AI can hold."`. The On-device overflow note is §6's On-device line.
- **NEEDS_HISTORY detection:** buffer Nano's deltas while the accumulated trimmed text is a prefix of `"NEEDS_HISTORY"`. Release the buffer as soon as it isn't. A final text exactly equal to `"NEEDS_HISTORY"` is never shown, and goes to `afterNeedsHistory`.
- **Fallbacks:**
  - In Hybrid:
    - Nano `NanoBusy`: retry once after 1,000 ms, then the cloud.
    - `NanoQuota`, `NanoUnavailable`, or any other Nano failure except `NanoBackground`: the cloud.
    - `NanoBackground` ends the turn silently: the in-progress message is removed.
    - Cloud `NoCredit`, or `Unreachable` for a PAGE question that fits Nano: Nano, with the answer's text suffixed by the note `"(answered on-device; couldn't reach <host>)"` for `Unreachable`.
  - In On-device only, Nano failures show `chatMessage()` with Try again.

- [ ] **Step 1: Write the failing tests**

`AiRouterTest`: a parameterized table that includes at least these rows:

| mode | tag | first | usedCloud | nanoUsable | fits | keySet | online | expected |
|---|---|---|---|---|---|---|---|---|
| ON_DEVICE | PAGE | true | false | true | true | false | true | Nano |
| ON_DEVICE | PAGE | false | false | true | false | false | true | Note(on-device overflow) |
| CLOUD | PAGE | true | false | true | true | true | true | Cloud |
| CLOUD | PAGE | true | false | true | true | true | false | Error("You're offline.") |
| HYBRID | PAGE | true | false | true | true | true | true | Nano |
| HYBRID | TRAINING | true | false | true | true | true | true | Cloud |
| HYBRID | PAGE | false | false | true | true | true | true | Cloud |
| HYBRID | PAGE | true | true | true | true | true | true | Cloud |
| HYBRID | PAGE | true | false | true | false | true | true | Cloud |
| HYBRID | PAGE | false | true | true | true | true | false | Nano |
| HYBRID | PAGE | true | false | true | false | true | false | Note("You're offline, and this needs more than on-device AI can hold.") |
| HYBRID | PATTERN | true | false | false | false | true | false | Error("AI isn't available right now.") |

Plus `afterNeedsHistory`: Hybrid online gives Cloud; Hybrid offline gives `Note("You're offline; this needs your history")`; On-device gives `Note("On-device AI can only see this page. Add an API key in Settings for questions about your history")`.

`ChatSessionTest` (fake engines; a packs fake or seeded DB):
- `nanoAnswerIsLabelledWithMoreDetail`: Hybrid, a page question. The message has `label.kind == NANO` and `canMoreDetail == true`.
- `followUpGoesToCloudAndStays`: the second `send` goes to the cloud, and a third `send` too.
- `needsHistoryIsHiddenAndEscalates`: Nano streams `"NEEDS_"` then `"HISTORY"`. No assistant message ever contains `"NEEDS"`; the cloud fake receives the same question; `usedCloud` holds.
- `needsHistoryPrefixReleasedWhenNotMatching`: Nano streams `"NEE"` then `"d more sleep"`, giving the text `"NEEd more sleep"`.
- `moreDetailReasksOnCloud`: the cloud receives turns `[user q, assistant nanoAnswer, user q]`, and `canMoreDetail` is false on the new message.
- `nanoBusyRetriesOnceThenCloud`: delays `[1000]`; Nano called twice; then the cloud.
- `nanoBackgroundEndsSilently`: no assistant message remains and there's no error.
- `cloudUnreachableFallsBackToNanoWithNote`: the text ends with `"(answered on-device; couldn't reach openrouter.ai)"`.
- `toolStatusBecomesLookedUp`: `ToolStatus("Looking up HRV, 1 Sep – 31 Oct")` shows as `status` while streaming, then in `lookedUp` when done.
- `errorThenRetry`: cloud `KeyRejected` sets `error = "Your API key was rejected. Check it in Settings."`, and `retry()` re-sends the same question.
- `cloudGetsFullPackNanoGetsCompact`: the requests' `pack` matches the FULL and COMPACT builds respectively.

- [ ] **Step 2: Run them to verify they fail:** `./gradlew :app:testDebugUnitTest --tests 'dev.baseline.ai.AiRouterTest' --tests 'dev.baseline.ai.ChatSessionTest'`
- [ ] **Step 3: Implement `AiRouter.route`, `afterNeedsHistory` and `ChatSession`**
- [ ] **Step 4: Run them to verify they pass**
- [ ] **Step 5: Commit:** `feat(ai): routing table and chat session with escalation and fallbacks`

---

### Task 11: Insight table and the 2 → 3 migration

**Files:**
- Create: `app/src/main/java/dev/baseline/data/db/AiInsightEntity.kt`, `data/db/Migrations.kt`
- Modify: `data/db/Daos.kt` (`AiInsightDao`), `data/db/AppDatabase.kt` (entity, `version = 3`, `abstract fun aiInsightDao()`, `.addMigrations(MIGRATION_2_3)`, a doc-comment line), `app/schemas/dev.baseline.data.db.AppDatabase/3.json` (generated by the build)
- Test: `app/src/test/java/dev/baseline/data/db/MigrationTest.kt`, `data/db/AiInsightDaoTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  @Entity(tableName = "ai_insight", primaryKeys = ["scope", "scopeKey"])
  data class AiInsightEntity(val scope: String, val scopeKey: String, val fingerprint: String, val text: String,
                             val engine: String, val model: String?, val createdAt: Instant)
  @Dao interface AiInsightDao {
      @Query("SELECT * FROM ai_insight WHERE scope = :scope AND scopeKey = :key") suspend fun get(scope: String, key: String): AiInsightEntity?
      @Upsert suspend fun put(row: AiInsightEntity)
      @Query("DELETE FROM ai_insight") suspend fun clear()
  }
  val MIGRATION_2_3: Migration   // CREATE TABLE copied from 3.json's createSql for ai_insight
  ```
  The doc-comment line to add to `AppDatabase`: "Tables that aren't re-downloadable (`ai_insight`) need a real migration; a destructive rebuild would also re-run the ~1,200-request backfill."

- [ ] **Step 1: Write the failing tests**
  - `AiInsightDaoTest`: put, get, put again with the same key (replaces), and clear (uses `inMemoryDatabase()`).
  - `MigrationTest.upgradeKeepsData`:
    1. Create a **file** database at version 2. Use a `SupportSQLiteOpenHelper` or the `androidx.room3` testing helper if available; otherwise execute `2.json`'s `createSql` statements on a raw SQLite file and set `PRAGMA user_version = 2` along with Room's `room_master_table` identity hash from `2.json`.
    2. Insert one `days`, one `scores` and one `activities` row.
    3. Open it with `AppDatabase.create(context, name)`.
    4. Assert those rows are still readable through the DAOs, and that `aiInsightDao().get("today", "2026-11-01")` returns null without throwing.
- [ ] **Step 2: Run them to verify they fail:** `./gradlew :app:testDebugUnitTest --tests 'dev.baseline.data.db.MigrationTest' --tests 'dev.baseline.data.db.AiInsightDaoTest'`
- [ ] **Step 3: Implement the entity, the DAO and the version bump; build once to export `3.json`; write `MIGRATION_2_3` from it; register it**
- [ ] **Step 4: Run them to verify they pass, then run the whole `dev.baseline.data.*` and `dev.baseline.scores.*` suites (no other DB test regresses)**
- [ ] **Step 5: Commit:** `feat(db): ai_insight table with an explicit 2→3 migration` (include `app/schemas/.../3.json`)

---

### Task 12: Insight service

**Files:**
- Create: `app/src/main/java/dev/baseline/ai/insight/InsightService.kt`
- Modify: `AppContainer.kt` (`val insightService`)
- Test: `app/src/test/java/dev/baseline/ai/insight/InsightServiceTest.kt`

**Interfaces:**
- Consumes: Tasks 1, 2, 6, 7, 9, 10 (`AiRouter` with tag `PAGE`, `firstTurn = true`), 11.
- Produces:
  ```kotlin
  sealed interface InsightState {
      data object Hidden : InsightState
      data class Generating(val partial: String) : InsightState
      data class Ready(val text: String, val label: EngineLabel) : InsightState
      data class Failed(val message: String) : InsightState
  }
  class InsightService(packs: ContextPacks, nano: NanoEngine, cloud: AiEngine, dao: AiInsightDao,
                       mode: Flow<AiMode?>, keyStatus: Flow<AiKeyStatus>, online: Flow<Boolean>, clock: Clock) {
      fun insight(scope: AskScope): Flow<InsightState>   // cold; cancelling the collector cancels generation
      suspend fun clearAll()
  }
  fun fingerprint(pack: String): String   // lowercase hex SHA-256 of "${Prompts.PROMPT_VERSION}\n$pack"
  ```
- **Behaviour:**
  1. **Hidden** when: the mode is OFF or null; the scope has no `insightKey()`; the pack's `hasData` is false; or no engine is usable (Cloud only and offline included).
  2. **Pick the engine** with `AiRouter.route`. A `Note` or `Error` route means Hidden.
  3. **Build the pack** at the engine's size.
  4. **Cache hit** on the same fingerprint: `Ready` from the row, with its engine and model.
  5. **Miss:** emit `Generating("")` and accumulate the deltas as `Generating(partial)`.
     - **Success:** `dao.put`, then `Ready`.
     - **Failure in Hybrid:** retry once on the other engine, as in §8's insight column.
     - **Otherwise:** `Failed(failure.insightMessage())`.
     - **Cancellation:** nothing is written.

  The cached row's engine label is shown even if the mode changed since.

- [ ] **Step 1: Write the failing tests**
  - `cacheHitSkipsEngine`: a pre-put row with a matching fingerprint gives `[Ready(text, EngineLabel(NANO, "nano-v4"))]`, with the fake engine calls at 0.
  - `dataChangeRegenerates`: a row with an old fingerprint gives `Generating`, then `Ready(new)`, and the row is updated.
  - `promptVersionIsPartOfFingerprint`: `fingerprint("x") != sha256("x")`, and equals `sha256("1\nx")`.
  - `failureWritesNothing`: the cloud throws `Unreachable` in CLOUD mode, giving `Failed("Couldn't reach openrouter.ai")`, and `dao.get` returns null.
  - `cancelWritesNothing`: cancel the collector during `Generating`; `dao.get` returns null.
  - `hybridFallsBackToCloud`: Nano throws `NanoQuota`, so the cloud's text becomes `Ready` with `label.kind == CLOUD`.
  - `hiddenCases`: OFF; `Trends` scope; a pack without data; CLOUD mode offline.
- [ ] **Step 2: Run them to verify they fail:** `./gradlew :app:testDebugUnitTest --tests 'dev.baseline.ai.insight.InsightServiceTest'`
- [ ] **Step 3: Implement `InsightService`**
- [ ] **Step 4: Run them to verify they pass**
- [ ] **Step 5: Commit:** `feat(ai): insight service with a fingerprinted cache`

---

### Task 13: Settings AI card and key screen

**Files:**
- Create: `app/src/main/java/dev/baseline/ui/settings/AiCard.kt`, `AiSettingsViewModel.kt`, `AiKeyScreen.kt`, `AiKeyViewModel.kt`
- Modify: `ui/settings/SettingsScreen.kt` (an `aiCard: @Composable () -> Unit = {}` slot, placed above the App card), `ui/nav/BaselineNavHost.kt` (route `Routes.AI_KEY = "settings/ai-key?step={step}"`; build both ViewModels from the container; fill the slot)
- Test: `app/src/test/java/dev/baseline/ui/settings/AiCardTest.kt`, `AiKeyScreenTest.kt`

**Interfaces:**
- Consumes: Tasks 1, 5, 6, 12 (`insightService.clearAll`).
- Produces:
  ```kotlin
  data class AiSettingsState(val mode: AiMode?, val options: List<ModeOption>, val nano: NanoStatus, val nanoKnown: Boolean,
                             val key: AiKeyStatus, val showHint: Boolean)
  class AiSettingsViewModel(prefs: AiPrefs, vault: AiKeyVault, nano: NanoEngine, entry: AiKeyEntry, insights: InsightService) : ViewModel() {
      val state: StateFlow<AiSettingsState>
      fun setMode(mode: AiMode); fun downloadNano(); fun removeKey(); fun clearInsights()
  }
  sealed interface AiKeyStep { data object Connection; data class Model(val result: ModelsResult); data object Testing; data class Done(val tools: Boolean) }
  class AiKeyViewModel(entry: AiKeyEntry, vault: AiKeyVault, startAtModel: Boolean) : ViewModel() {
      val step: StateFlow<AiKeyStep>; val error: StateFlow<String?>
      fun submitConnection(baseUrl: String, key: String); fun pickModel(id: String)
  }
  @Composable fun AiCard(state: AiSettingsState, onMode: (AiMode) -> Unit, onDownload: () -> Unit, onAddKey: () -> Unit,
                         onChangeModel: () -> Unit, onReplaceKey: () -> Unit, onRemove: () -> Unit, onClearInsights: () -> Unit)
  ```
- **Copy (§3.2, verbatim):**
  - Nano rows:
    - Ready: `"Gemini Nano (<modelName>) · Ready · <tokenLimit with thousands separator>-token window"`.
    - Downloadable: `"Download"`, with `"Uses Wi-Fi; may take a few minutes"`.
    - Downloading: a progress bar.
    - Unavailable: `"Not supported on this phone"`.
  - Key rows:
    - `"Add API key"`.
    - Host and model rows with `"✓ Tools"`, or `"⚠ No tools: answers use page data only"`.
    - `"Change model"`, `"Replace key"`, `"Remove"`.
    - When Invalid: `"Key rejected. Replace it to keep using the cloud."`
  - Hint (ON_DEVICE and Nano Ready only): `"Add an API key for better answers to training and long-term questions."`
  - Privacy (key set): `"Cloud requests send this page's numbers, and any history the model looks up, to <host>. Never GPS, routes or names. Not medical advice."`
  - Button: `"Clear AI insights"`.
- **Key screen:**
  - Step 1 has two `OutlinedTextField`s (Base URL pre-filled `DEFAULT_AI_BASE_URL`; API key with password masking) and `Continue`.
  - Step 2 is a search field plus a `LazyColumn` of `ModelInfo` rows: id, `formatPricePerQuestion`, and a "Tools" badge. The `SUGGESTED_MODEL` row is preselected. On `FreeText`, a `"Model ID"` text field replaces the list.
  - Step 3 shows the outcome text from §3.3:
    - `"✓ Key works · tools supported"`
    - `"⚠ This model can't look up your history; answers will use the page's data only."`
    - or the `Failed` message.
  - Back from a `Done` step pops to Settings.
  - **The key string is held only in `AiKeyViewModel` memory and never in `SavedStateHandle`.**

- [ ] **Step 1: Write the failing tests** (Compose, Robolectric)
  - `AiCardTest`:
    - `modesGreyedWithReasons`: Nano Unavailable and no key, so the ON_DEVICE row is disabled and shows "Not supported on this phone".
    - `nanoReadyRow`: shows "Gemini Nano (nano-v4) · Ready · 4,096-token window".
    - `hintOnlyOnDeviceReady`.
    - `privacyLineWithHost`.
    - `invalidKeyMessage`.
    - `clearInsightsCallsBack`.
    - Every clickable has height ≥ 48dp (`assertHeightIsAtLeast(48.dp)`).
  - `AiKeyScreenTest`:
    - `rejectsHttpUrl`: shows "Use an https:// address." (the error copy for a bad URL).
    - `listedModelsSuggestedPreselected`.
    - `freeTextFallback`.
    - `savedWithoutToolsShowsWarning`.
    - `failedShowsMessageAndKeepsInputs`.
    - `keyNotInSavedState`: after a configuration change, the key field is empty and the URL is kept.
- [ ] **Step 2: Run them to verify they fail:** `./gradlew :app:testDebugUnitTest --tests 'dev.baseline.ui.settings.AiCardTest' --tests 'dev.baseline.ui.settings.AiKeyScreenTest'`
- [ ] **Step 3: Implement the composables and ViewModels, the route, and the slot**
- [ ] **Step 4: Run them to verify they pass, along with the existing `dev.baseline.ui.settings.*` and `dev.baseline.ui.nav.*` suites**
- [ ] **Step 5: Commit:** `feat(settings): AI card, mode picker and key/model screen`

---

### Task 14: The Ask sheet

**Files:**
- Create: `app/src/main/java/dev/baseline/ui/ai/AskSheet.kt`, `ui/ai/AskViewModel.kt`
- Test: `app/src/test/java/dev/baseline/ui/ai/AskSheetTest.kt`

**Interfaces:**
- Consumes: `ChatSession` (Task 10), `Suggestions` (Task 9), `AskScope.title` and `subject` (Task 7), `ContextPacks.build(…).facts`.
- Produces:
  ```kotlin
  data class AskUiState(val title: String, val hint: String, val placeholder: String,
                        val suggestions: List<Suggestion>, val messages: List<ChatMessage>, val busy: Boolean)
  class AskViewModel(scope: AskScope, session: ChatSession, packs: ContextPacks, mode: Flow<AiMode?>, online: Flow<Boolean>,
                     today: StateFlow<LocalDate>) : ViewModel() {
      val state: StateFlow<AskUiState>
      fun send(text: String); fun pick(s: Suggestion); fun moreDetail(index: Int); fun retry()
  }
  @Composable fun AskSheet(vm: AskViewModel, onDismiss: () -> Unit)
  ```
- **Layout (§3.6 and the approved mockups):**
  - A `ModalBottomSheet` with `skipPartiallyExpanded = false`, opening partly expanded.
  - The title in Barlow Condensed SemiBold 18sp, with a ✕ button.
  - The hint: `"Ask anything about <subject>, or how it fits your training and longer trends."`
  - Messages in a `LazyColumn`, with `Arrangement.spacedBy(20.dp)` and 12dp of vertical content padding.
  - User bubbles are right-aligned on `ChipActive`. Answers are plain text.
  - **Trailing row:**
    - The copy icon (TalkBack "Copy answer"; copies through `LocalClipboard`).
    - The label text in muted 11sp.
    - When `canMoreDetail`: `"· More detail ☁"` as a 48dp-tall clickable in `StrainBlue`.
  - **Tools:** a live `status` line, and `lookedUp` shown as `"Looked up: <joined with "; ">"`, both muted, above the answer text.
  - **Suggestions:** a `Column(horizontalAlignment = Start, verticalArrangement = spacedBy(6.dp))` of chips directly above the text field, shown only while `messages.isEmpty()`. Each is at least 48dp tall.
  - **Text field:** the placeholder is `"Ask about <subject>…"` before the first message and `"Ask a follow-up…"` after, with a send `IconButton`.
  - **Errors** show the message and a `"Try again"` link.
  - Dismissing calls `onDismiss`. The ViewModel is cleared with its back stack owner, which cancels the session's job.
- `cloudSlots` for suggestions is `mode == CLOUD || (mode == HYBRID && online)`.

- [ ] **Step 1: Write the failing tests** (Compose, with a fake `ChatSession` driven through a fake engine)
  - `suggestionsStackedMaxFourAboveInput`: 4 nodes; each node's left bound equals the first's; each top is below the previous one's bottom; the last is above the text field.
  - `suggestionsHideAfterFirstSend`.
  - `trailingLabelAndMoreDetail`: a Nano answer in Hybrid shows "On-device" and "More detail ☁"; a cloud answer shows "Cloud · deepseek-v4.1-flash" and no "More detail".
  - `toolStatusThenLookedUp`.
  - `errorShowsTryAgain`, and clicking it calls `retry`.
  - `placeholderChangesAfterFirstMessage`.
  - `copyPutsAnswerOnClipboard`.
  - `touchTargets48dp`.
- [ ] **Step 2: Run them to verify they fail:** `./gradlew :app:testDebugUnitTest --tests 'dev.baseline.ui.ai.AskSheetTest'`
- [ ] **Step 3: Implement `AskViewModel` and `AskSheet`**
- [ ] **Step 4: Run them to verify they pass**
- [ ] **Step 5: Commit:** `feat(ui): Ask sheet with page suggestions and trailing engine labels`

---

### Task 15: The insight card on Today, Sleep and Activity

**Files:**
- Create: `app/src/main/java/dev/baseline/ui/ai/InsightCard.kt`, `ui/ai/InsightViewModel.kt`
- Modify:
  - `ui/today/TodayScreen.kt`: an `insight: @Composable () -> Unit = {}` slot directly after `BodyBatteryStrip` (line ~204), before the Recovery card.
  - `ui/sleep/SleepScreen.kt`: the same slot under the sleep-performance header.
  - `ui/activity/ActivityScreen.kt`: the same slot after `SummaryGrid` (line ~86).
- Test: `app/src/test/java/dev/baseline/ui/ai/InsightCardTest.kt`

**Interfaces:**
- Consumes: `InsightService.insight` (Task 12), `AskScope.subject` (Task 7).
- Produces:
  ```kotlin
  class InsightViewModel(service: InsightService) : ViewModel() {
      fun state(scope: AskScope): StateFlow<InsightState>   // re-keys on scope; retry() re-collects
      fun retry()
  }
  @Composable fun InsightCard(state: InsightState, subject: String, onAsk: () -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier)
  ```
  The nav host fills each screen's slot with `InsightCard`, built from one `InsightViewModel` per back stack entry. The scopes are:
  - Today: `AskScope.Today(<the Today ViewModel's selected date>)`.
  - Sleep: `AskScope.Sleep(<route date>)`.
  - Activity: `AskScope.Activity(id, sport)`.

  `onAsk` opens the Ask sheet with the same scope (Task 16 provides the opener; until then, pass a no-op).
- **Look (§3.8):**
  - The `Card` background, a 14dp corner and 12dp padding.
  - The header: a 13dp `SleepPurple` sparkle with `"INSIGHT"` in the dial-label style.
  - The body: 2–3 sentences at 12.5sp with 1.5 line height.
  - The footer: the muted label on the left, and `"Ask about <subject> ›"` in `SleepPurple` on the right (48dp touch). The whole card is clickable and calls `onAsk`.
- **States:**
  - Generating: three shimmer bars at 96%, 88% and 54% width, keeping the final height. The animation is disabled when `Settings.Global.ANIMATOR_DURATION_SCALE == 0` or reduced motion is set; follow the existing reduced-motion handling if there is one, otherwise a static fill.
  - Failed: the muted message and a `"Retry"` link in `StrainBlue`.
  - Hidden: emits nothing (no spacer).
- **TalkBack:** `Modifier.semantics(mergeDescendants = true)` with the content description `"Insight: <text>. Made on-device."` (or `"Made in the cloud."`), and a custom action `"Ask about <subject>"`.

- [ ] **Step 1: Write the failing tests**
  - `readyShowsTextLabelAndLink`.
  - `generatingKeepsHeight`: the generating height is within 2dp of the ready height for a 3-line text.
  - `failedShowsRetry`, and clicking it calls `onRetry`.
  - `hiddenTakesNoSpace`: the slot's measured height is 0.
  - `talkBackMergedDescription`, plus the custom action.
  - `askLinkAndCardClickCallOnAsk`.
  - Screen tests: Today, Sleep and Activity each render the slot content at the specified position (assert the slot's top is greater than the Body Battery strip's bottom and less than the Recovery card's top on Today). Use the existing screen tests' setup.
- [ ] **Step 2: Run them to verify they fail:** `./gradlew :app:testDebugUnitTest --tests 'dev.baseline.ui.ai.InsightCardTest'`
- [ ] **Step 3: Implement the card and the ViewModel, add the slots, and fill them in the nav host**
- [ ] **Step 4: Run them to verify they pass, along with `dev.baseline.ui.today.*`, `dev.baseline.ui.sleep.*` and `dev.baseline.ui.activity.*`**
- [ ] **Step 5: Commit:** `feat(ui): AI insight card on Today, Sleep and activities`

---

### Task 16: The centre Ask button, scope reporting and the sheet host

**Files:**
- Create: `app/src/main/java/dev/baseline/ui/ai/AskButton.kt`, `ui/ai/AskScopeHolder.kt`
- Modify: `ui/nav/BaselineNavHost.kt`
- Test: `app/src/test/java/dev/baseline/ui/nav/NavAskButtonTest.kt`

**Interfaces:**
- Consumes: Tasks 7, 10, 14, 15.
- Produces:
  ```kotlin
  class AskScopeHolder { fun report(entryId: String, scope: AskScope); fun current(entryId: String?): AskScope }   // unknown entry -> General
  @Composable fun ReportAskScope(scope: AskScope)   // reads LocalAskScopeHolder + LocalViewModelStoreOwner's back stack entry id
  @Composable fun AskButton(onClick: () -> Unit)    // 36dp SleepPurple circle, sparkle 16dp, 48dp touch, contentDescription "Ask about this page"
  ```
- **Nav host:**
  - Build the bar as `Today, Activities, <AskButton>, Trends, Settings`. The `AskButton` is a `NavigationBarItem`-sized slot that is never `selected`, and its click never calls `nav.navigate`.
  - Show the button only when `aiPrefs.mode` is not `OFF` or null (otherwise the bar has the 4 tabs as before).
  - Each `composable(...)` block calls `ReportAskScope`:
    - TODAY: `Today(selectedDate)`.
    - SLEEP: `Sleep(date)`.
    - ACTIVITY: `Activity(id, sport from the ViewModel state once loaded)`.
    - TRENDS: `Trends(range.days)`.
    - Others report nothing, which means General.
  - Tapping the button sets `askFor = holder.current(currentEntry.id)` and shows `AskSheet` for it. `InsightCard.onAsk` sets `askFor` to the card's scope.
  - `AskViewModel` is created with `viewModel(key = "ask-$scope")` per open, and `ChatSession` is built from the container.
  - Closing sets `askFor = null`, and the ViewModel's store is cleared, using a dedicated `ViewModelStoreOwner` per sheet (`remember { ViewModelStore() }`, cleared in `onDispose`) so chats are never saved.
  - Long-press does nothing.

- [ ] **Step 1: Write the failing tests** (`NavShellTest`'s container pattern, with `aiPrefs` set to HYBRID and fake engines)
  - `buttonHiddenWhenOff`: 4 tabs; no node with content description "Ask about this page".
  - `buttonOpensSheetScopedToPage`: on Sleep for 2026-10-31 with today 2026-11-01, the button opens the sheet with the title "Ask about last night".
  - `buttonDoesNotChangeSelectedTab`: the Activities tab is still `isSelected()` after opening and closing on an activity page.
  - `generalScopeOnSettings`: shows "Ask about your health".
  - `insightLinkOpensSameScope`: on Today, "Ask about today ›" opens "Ask about today".
  - `closingDiscardsChat`: send, close, reopen; the suggestions are visible again and there are no messages.
  - `touchTarget48dp`: the button node's bounds are at least 48dp in both dimensions.
- [ ] **Step 2: Run them to verify they fail:** `./gradlew :app:testDebugUnitTest --tests 'dev.baseline.ui.nav.NavAskButtonTest'`
- [ ] **Step 3: Implement it**
- [ ] **Step 4: Run them to verify they pass, along with `NavShellTest` (the existing navigation is unchanged)**
- [ ] **Step 5: Commit:** `feat(nav): centre Ask button that opens Ask over the current page`

---

### Task 17: Fake AI server, docs, and final verification

**Files:**
- Create: `tools/fake_llm.py`, `tools/test_fake_llm.py`
- Modify:
  - `docs/superpowers/specs/2026-10-24-baseline-android-design.md`: §1 (two users), §3 (`ai/`), §6 (`ai_insight`, version 3), §9 (the insight card, the Ask sheet, the AI card, and the navigation line from §3.4).
  - `CLAUDE.md`:
    - Architecture: `ai/` and `aiHttp`.
    - Invariants: §9 of the AI spec, one bullet each.
    - The fake server recipe.
    - Known follow-ups: §14's first three items.
  - `docs/cloud-sessions.md`: `fake_llm.py` works in the container; Nano isn't available there.
  - `app/proguard-rules.pro`: only if the smoke test below shows R8 breakage.
- Test: `tools/test_fake_llm.py`

**Interfaces:** `fake_llm.py` behaves as specified in §11.2:
- Port 8090; `--port`, `--key` (default `fake-ai-key`), `--fail 402|429|500`.
- `GET /v1/models` returns three models with OpenRouter fields, including `deepseek/deepseek-v4.1-flash`.
- `POST /v1/chat/completions`:
  - Honours `stream`.
  - With `tools`, on the first round, it calls `daily_metrics` for the last 30 days.
  - Answers `"Your first number here is <first number in the system message>."`
  - Answers the `ping` test with a `tool_calls` response.
- Logs method, path, status and an approximate token count to stderr, never the body.
- Stdlib only, and run with `uv run --python 3.12 --no-project python -I`.

- [ ] **Step 1: Write `tools/test_fake_llm.py`**

It uses `unittest`, starts the server on a free port in a thread, and checks:
- A 401 for a wrong key.
- The models list has 3 entries.
- The ping test returns `tool_calls`.
- The streamed answer ends with `data: [DONE]`.
- `--fail 429` returns 429 with `Retry-After: 1`.

- [ ] **Step 2: Run it to verify it fails:** `uv run --python 3.12 --no-project python -I tools/test_fake_llm.py` (expected: ImportError or connection failures)
- [ ] **Step 3: Implement `tools/fake_llm.py`**
- [ ] **Step 4: Run it to verify it passes** (same command; expected `OK`)
- [ ] **Step 5: Update the docs listed above.** In CLAUDE.md, add an "AI fake server" block under the fake Garmin server section:

```bash
uv run --python 3.12 --no-project python -I tools/fake_llm.py              # :8090; key fake-ai-key
adb reverse tcp:8090 tcp:8090
```

It also says to use a `-PgarminBaseUrl` build and to enter `http://127.0.0.1:8090/v1` with `fake-ai-key` in Settings → AI.

- [ ] **Step 6: Full verification**

Run: `./gradlew :app:testDebugUnitTest :app:lintDebug spotlessCheck`
Expected: BUILD SUCCESSFUL, with no lint findings (the build treats warnings as errors).

Run the first `git grep` from CLAUDE.md's "No personal data in the repo" section (it isn't repeated here: the pattern
would match this file).
Expected: no output.

Where an emulator exists (the owner's Mac): build a minified release with `-PgarminBaseUrl=http://127.0.0.1:8080`, sign it with `/tmp/test.jks`, and install it on `Medium_Phone_API_35`. Then, with both fake servers running and `adb reverse` for 8080 and 8090:
1. Enter `fake-garmin-key`, then `http://127.0.0.1:8090/v1` with `fake-ai-key`.
2. Check that the mode becomes Cloud only (the emulator has no Nano).
3. Check that the insight card fills on Today, that Ask opens from the centre button on Sleep and answers with a "Looked up" line, and that `--fail 402` shows "No credit left at 127.0.0.1".

In the cloud container, write in the handoff that this step was skipped.

- [ ] **Step 7: Write the handoff note**

Write `docs/superpowers/handoff/2026-11-01-ai-assistant.md` covering: what was built, what was verified where, and the on-phone checklist from spec §11.3, which is the owner's to run.

- [ ] **Step 8: Commit:** `docs: AI assistant in the main spec and CLAUDE.md; tools: fake AI server`

---

## Self-review notes

**Spec coverage:**

| Spec section | Task(s) |
|---|---|
| §3.1 | 1, 6 |
| §3.2 | 13 |
| §3.3 | 5, 13 |
| §3.4 | 16 |
| §3.5 | 7, 16 |
| §3.6 | 14 |
| §3.7 | 9 |
| §3.8 | 15 |
| §4.1 | 6 |
| §4.2 | 3, 4 |
| §4.3 | 5 |
| §4.4 | 1 |
| §5.1 | 7 |
| §5.2 | 8 |
| §5.3 | 9 |
| §5.4 | 9, 13 |
| §6 | 10 |
| §7.1 | 12 |
| §7.2–7.3 | 11 |
| §8 | 2, 3, 10, 12 |
| §9 | 2, 3, 5, 7, 8 (tests); 17 (CLAUDE.md) |
| §10 | the file map |
| §11.1 | each task's tests |
| §11.2 | 17 |
| §11.3 | 17 (handoff) |
| §11.4 | 17 |
| §12 | 6 |
| §13 | 17 |

**Names checked across tasks:**
- `AiKeyStatus.Set`, `NanoStatus.Ready(modelName, tokenLimit)`, `EngineLabel.text`, `AskScope.subject` and `insightKey`.
- `QuestionTag`: defined in Task 9 in `AiRouter.kt`, extended in Task 10.
- `ToolRunner`: defined in Task 3, implemented in Task 8.
