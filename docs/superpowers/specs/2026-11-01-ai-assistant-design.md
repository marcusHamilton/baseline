# Baseline — AI assistant (Ask and insights): design spec

Date: 2026-11-01
Status: implemented on branch claude/funny-hopper-j3ysau; on-device checks pending (§11.3)
Extends: §3 (Architecture), §6 (Storage) and §9 (Screens, Navigation) of `docs/superpowers/specs/2026-10-24-baseline-android-design.md`

## 1. Intent

Baseline shows numbers and plain-language states. It can't answer the next question: *why* recovery is low,
whether last night fits a pattern, or whether a hard session fits this week. This feature lets the user ask those
questions in their own words, about the page they're looking at or their whole history. It also puts a short AI
insight on Today, Sleep and each activity.

Two engines answer:

- **On-device:** Gemini Nano through ML Kit's Prompt API. Free, private and offline, but small.
- **Cloud:** any OpenAI-compatible endpoint (OpenRouter by default) with the user's own API key and chosen model.
  Much better at training and long-term questions.

Success: from any page, the user can ask a question about what they see, or about how it fits their training and
history, and get an answer that quotes their own numbers. They always know whether the answer was made on the phone
or in the cloud.

### Decisions made with the owner

- Three kinds of question are in scope, and suggestions offer all three: about *this page* ("How did this go?"),
  *training* ("Can I train hard today?") and *patterns over time* ("What helps my HRV?").
- One **AI mode** setting: Off, On-device only, Cloud only, Hybrid (§3.1). This replaces separate switches.
- Cloud is OpenAI-compatible only: **base URL, API key and model**. The default base URL is
  `https://openrouter.ai/api/v1` and the suggested model is `deepseek/deepseek-v4.1-flash`.
- The model is picked from the endpoint's `/models` list after the key is entered. A test request then checks the
  key and whether the model can call tools. A model without tools is allowed, but flagged (§3.3).
- Hybrid routing: page questions go to Nano when they fit. Training and pattern questions, overflow, and **every
  follow-up** go to the cloud. A follow-up keeps the chat on the cloud for the rest of the conversation. A
  "More detail ☁" link re-asks an on-device answer in the cloud.
- **Data access is hybrid:** every request starts with the page's *context pack*. The cloud also gets read-only
  *tools* over the local database for history.
- **Chats aren't saved.** Closing the sheet ends the chat. **Insights are cached** per page and day (or activity) and
  regenerate only when the underlying data changes.
- Insights generate **automatically the first time a page is opened**, then come from the cache.
- Ask opens as a **bottom sheet over the page** (draggable to full height) from a **centre Ask button in the tab
  bar**: a small filled circle inside the bar that acts on the current page and isn't a tab. It also opens from the
  insight card's "Ask about … ›" link.
- Suggestions: at most 4, unique to the page and filled from its data, stacked and left-aligned just above the text
  box, with no category prefix. They disappear after the first message.
- Each answer ends with a small muted trailing line: a copy icon, then "On-device" or "Cloud · model". On on-device
  answers in Hybrid mode, "More detail ☁" follows.
- The app now has a second user, on a Pixel 10 Pro (nano-v3). Each install has its own keys and data, so nothing here
  is shared between users.

### Decomposition

This spec is the first of three pieces:

1. **This spec:** engines, AI mode and Settings, context packs and tools, routing, the Ask sheet, insights.
2. Later: more insight placements (Trends, Calendar).
3. Later: a Chat tab with saved conversations. A long-press on the centre button is reserved for it.

### Non-goals

- Saved chats, chat history or a Chat tab (piece 3).
- Generating insights in the background (after a sync, from WorkManager). Nano can't run in the background, and doing
  it in the cloud costs money on days the app isn't opened.
- Notifications, voice input, images or any multimodal input.
- Writing anything: no tool changes data, settings or Garmin, or starts a sync.
- Cost tracking or budgets. The model picker shows a per-question estimate, and that's all.
- Native (non-OpenAI-compatible) provider APIs, or more than one cloud configuration at once.
- Medical advice. The assistant is informational, and says so (§5.4).

## 2. What we're building on

### 2.1 Gemini Nano (ML Kit GenAI Prompt API)

Checked on 2026-11-01 against Google's ML Kit GenAI and Gemini Nano pages:

- Library: `com.google.mlkit:genai-prompt`, `GenerativeModel`, running on the AICore system service. **Beta**: no
  SLA, and Google says changes may break compatibility.
- Devices: Pixel 11, 11 Pro, 11 Pro XL and 11 Pro Fold are on **nano-v4**. The Pixel 10 series (including 10 Pro) and
  Pixel 9 series are on **nano-v3**. Several Samsung, Xiaomi and OnePlus phones are on nano-v2. Output differs between
  versions. `getBaseModelName()` reports the version.
- API: `checkStatus()` (unavailable, downloadable, downloading or available), `download()` (a `Flow` of progress),
  `warmup()`, `countTokens(request)`, `getTokenLimit()` (**input plus output combined**), `generateContentStream`,
  multi-turn through a client-managed `List<Content>` with USER and MODEL roles, and `getAvailableFeatures()` (system
  prompt, structured output and so on).
- **The context window size isn't published.** Developer reports put it around 4k tokens, with output best kept under
  about 256 tokens. We never hard-code it: every request is checked with `countTokens` against `getTokenLimit()`.
- **Foreground only.** Inference while Baseline isn't the top foreground app, including from a foreground service,
  fails with `BACKGROUND_USE_BLOCKED`.
- **Quotas.** `BUSY` for short-term limits (back off), and `PER_APP_BATTERY_USE_QUOTA_EXCEEDED` for a long-term
  (daily) limit.
- There's no Nano on emulators or in the cloud container. It can only be tested on a real phone.

### 2.2 OpenAI-compatible endpoints

OpenRouter, OpenAI, Gemini's and Anthropic's compatibility endpoints, Groq, Together, and local servers (Ollama,
LM Studio) all accept `POST {baseUrl}/chat/completions` with `messages`, `tools`, `tool_choice`, `stream: true`
(server-sent events) and `max_tokens`, and list models at `GET {baseUrl}/models`. OpenRouter's `/models` also gives
`context_length`, `pricing.prompt` and `pricing.completion` (USD per token) and `supported_parameters`, which contains
`"tools"` when the model can call tools. On 2026-11-01, 386 of its 452 models listed tools.
`deepseek/deepseek-v4.1-flash` had tools, a 1,048,576-token context, and cost $0.045 per million input tokens and
$0.50 per million output tokens.

We write the client ourselves on the existing OkHttp 5 and kotlinx.serialization, with no vendor SDK (§12).

### 2.3 What already exists

- `ApiKeyVault` and `KeystoreAead`: the Keystore-encrypted secret pattern. `KeyEntry.saveAndTest` saves a key only if
  it passes its test.
- `updateHttp`: an OkHttp client with no authenticator or logging interceptor, guarded by `UpdatesUrlTest`.
- `Repository`, `TrainingLoad` and `ScorePipeline` outputs: everything a context pack needs, read from Room.
- Today's rule-based `insightSentence` (`ui/today/Insight.kt`) in the Recovery card. The AI insight is a separate card
  and doesn't replace it.
- `AppDatabase` is at version 2 with a **destructive** fallback migration (§7.3).

## 3. User experience

### 3.1 AI mode

`Settings → AI → Mode` is a single choice:

| Mode | Needs | What happens |
|---|---|---|
| **Off** | — | No AI anywhere: no centre button (the tab bar has 4 tabs again), no insight cards. |
| **On-device only** | Nano supported | Everything runs on Nano. **No AI request ever leaves the phone.** No "More detail ☁". |
| **Cloud only** | API key | Everything goes to the cloud. Offline means an error. |
| **Hybrid** | Both | Routing as in §6. |

- A mode whose engine is missing is shown greyed out with the reason: "Not supported on this phone" for On-device,
  "Add an API key" for Cloud. Hybrid shows both reasons when both apply.
- **Defaults and transitions** (the user can always change the mode afterwards):
  - **First launch, once Nano's status is known:** On-device only if Nano isn't Unavailable, otherwise Off. Until the
    status is known, the mode is undecided and nothing shows.
  - **First-run prompt** (`AiFirstRun`): once, on Today, in On-device only when Nano is `Downloadable`: "Turn on AI
    insights?" with **Download** (keeps the mode and starts the download, which runs on the app scope) and **Turn off AI**
    (sets Off). It has no other dismiss. It is never shown on a phone that doesn't support Nano, or in any other mode or
    Nano state: `applyAiDefaults` marks it done (`AiPrefs.promptDone`) after every start where it wouldn't apply, and a
    failed Nano check leaves it for the next start.
  - **A key is saved:** Off or On-device only becomes **Hybrid** if Nano is supported, otherwise **Cloud only**.
    Cloud only and Hybrid are left alone.
  - **The key is removed:** Cloud only or Hybrid becomes On-device only if Nano is supported, otherwise Off.
  - **Nano becomes Unavailable** (for example after a system update): On-device only becomes Off, and Hybrid becomes
    Cloud only. The status is re-checked on every app start.

### 3.2 Settings → AI card

From top to bottom:

1. **Mode** (§3.1).
2. **On-device:**
   - When ready: "Gemini Nano (nano-v4) · Ready · 4,096-token window", built from `getBaseModelName()` and
     `getTokenLimit()`. Showing the window makes the real limit visible on each phone.
   - When downloadable: a **Download** button, with "Uses Wi-Fi; may take a few minutes".
   - When downloading: a progress bar.
   - When unavailable: "Not supported on this phone".
3. **API key:**
   - When unset: **Add API key**, which opens the flow in §3.3.
   - When set: the host (`openrouter.ai`); the model (`deepseek-v4.1-flash`) with "✓ Tools", or "⚠ No tools: answers
     use page data only"; then **Change model**, **Replace key** and **Remove**.
   - When the key is Invalid: "Key rejected. Replace it to keep using the cloud."
4. **Hint**, shown only when the mode is On-device only, Nano is Ready **and no key is set**: "Add an API key for
   better answers to training and long-term questions."
5. **Privacy line**, shown when a key is set: "Cloud requests send this page's numbers, and any history the model
   looks up, to *host*. Never GPS, routes or names. Not medical advice."
6. **Clear AI insights.**

### 3.3 Adding a key and choosing a model

A page, `ui/settings/AiKeyScreen`, opened from Settings like the other sub-pages:

1. **Connection:**
   - **Base URL** (pre-filled with `https://openrouter.ai/api/v1`) and **API key**.
   - **Continue** checks the URL: it must be `https://`. Test builds (made with `-PgarminBaseUrl`, which already
     allow cleartext and are never released) also accept `http://127.0.0.1` and `http://localhost` for the fake server
     (§11.2). It then fetches `GET {baseUrl}/models` with the key.
2. **Model:**
   - A searchable list.
   - When the response has OpenRouter's fields, each row shows the price of a typical question (10,000 input tokens
     plus 500 output tokens, for example "≈ $0.0007 per question") and a "Tools" badge. Models with tools sort first.
   - `deepseek/deepseek-v4.1-flash` is preselected when present.
   - If `/models` fails, returns an error, or lists nothing, the list becomes a **Model ID** text field. A 401 or
     403 here stops the flow with "Key rejected".
3. **Test and save** (§4.3):
   - **Tools work:** saved, "✓ Key works · tools supported".
   - **The key works but tools don't:** saved, with "⚠ This model can't look up your history; answers will use the
     page's data only." `toolsSupported` is stored as false.
   - **Anything else:** nothing is saved, and the message says what failed (§8).

The key is held only in the screen's ViewModel memory until the test passes. As with `KeyEntry.saveAndTest`, **a key
under test never touches the vault.**

**Change model** reopens step 2 with the stored URL and key, and re-runs the test. **Replace key** opens step 1.
**Remove** deletes the URL, key and model, and applies the mode transition.

### 3.4 The centre Ask button

- The tab bar gets a fifth, centre slot: **Today · Activities · [Ask] · Trends · Settings**.
- The slot holds a **36dp filled circle** in `#A78BFA` with a sparkle glyph, sitting **inside the bar below its top
  line** (not raised). Its touch target is 48dp, its TalkBack label is "Ask about this page", and it has no text label.
- It's **an action, not a tab**. It never changes the selected tab and never navigates. It opens the Ask sheet over the
  current page, scoped to it (§3.5).
- It's hidden in mode Off, during onboarding, and wherever the tab bar is hidden.
- A long-press does nothing in this piece (it's reserved for the Chat tab).
- The main spec's navigation rules gain one line: "The centre Ask button opens the Ask sheet over the current page.
  It isn't a tab."

### 3.5 Scopes

Each page reports its **Ask scope** to the nav host. The sheet title and the context pack follow it:

| Page | Scope | Sheet title |
|---|---|---|
| Today (the shown date) | `Today(date)` | "Ask about today", or "Ask about Tue 27 Oct" for a past day |
| Sleep detail | `Sleep(date)` | "Ask about last night" (the date is today), or "Ask about the night of 27 Oct" |
| Activity detail | `Activity(id)` | "Ask about this run", "this ride", "this swim", "this session" (strength) or "this activity" |
| Trends | `Trends(range)` | "Ask about your trends" |
| Activities, Records, Calendar, Settings | `General` | "Ask about your health" |

`Sleep(date)` holds the **wake date**, the date the Sleep screen shows and the one `DayEntity` and Garmin use. "Last
night" is today's sleep; any other date is titled and described as "the night of *wake − 1 day*" (the night that began
the evening before). `General` uses Today's pack for the current date (§5.1). The insight card's "Ask about … ›" link opens the same
sheet with the same scope.

### 3.6 The Ask sheet

- **Shape:** a Material 3 modal bottom sheet. It opens at Material 3's default partial height (about half the screen) and can be
  dragged to full height. A sheet lays its content out at full height and slides it up, so the content is sized to the part
  that is on screen (`onScreen` in `AskSheet.kt`); otherwise the suggestions and the text box sit below the screen edge at the
  half-height stop. The page behind is dimmed. Closing it (✕, a drag down, or Back) cancels any request in flight and
  **discards the chat**. The chat survives rotation (it lives in the ViewModel) but not process death.
- **Empty state:**
  - The title, then one muted line: "Ask anything about *this night / today / this run …*, or how it fits your
    training and longer trends."
  - At the bottom, **just above the text box**, up to **4 suggestions**: **stacked vertically and left-aligned**,
    each a single chip with no category prefix. Each has a 48dp touch target, and a tap sends it.
  - The text box ("Ask about last night…") has a send button.
- **After the first message:**
  - **The suggestions are gone** for the rest of the chat.
  - **The user's messages** are right-aligned bubbles.
  - **Answers** are plain left-aligned text that streams in. Each ends with a **trailing line** in small muted text: a
    copy icon (copies the answer), then the engine label. The label is "On-device" or "Cloud · " plus the model ID
    after its last `/` (for example "Cloud · deepseek-v4.1-flash"). In Hybrid mode, on-device answers add
    "· More detail ☁" while the cloud is usable.
  - **While tools run**, a muted status line shows what's being looked up, for example "Looking up HRV, 1 Sep – 31 Oct".
    It stays above the answer as "Looked up: …".
  - Messages have **20dp** between them, with 12dp of padding above the first and below the last.
  - The text box shows "Ask a follow-up…".
- **Errors** appear in place of the answer, in muted text with a **Try again** link (§8).

### 3.7 Suggestions

Suggestions are **templates, not AI output**: they're instant, free, and can include the page's numbers. Each scope
has 4 slots: **2 page, 1 training, 1 pattern**. In **On-device only** mode, and in Hybrid while offline, the
training and pattern slots are replaced by the scope's two spare page questions. The hidden tag (page, training or
pattern) travels with the question to the router (§6).

| Scope | Page | Page | Training | Pattern | Spare page (on-device or offline) |
|---|---|---|---|---|---|
| Today | "Why is my recovery {recovery}%?" | "What does today's strain target mean for me?" | "Can I train hard today?" | "What's been affecting my HRV lately?" | "How does today compare to my baseline?" · "What should I focus on today?" |
| Sleep | "How did I sleep compared to usual?" | "Why was {stage} sleep {up/down}?" (the stage furthest from its baseline) | "Is my sleep keeping up with my training?" | "Do late workouts affect my sleep?" | "Why was my sleep score {score}?" · "How can I close my sleep gap?" |
| Activity, run | "How was my pacing?" | "How hard was this compared to my recent runs?" | "How long should I recover?" | "Am I getting faster?" | "What do my heart rate zones say?" · "How even were my splits?" |
| Activity, ride | "How did my power hold up?" | "How hard was this compared to my recent rides?" | "How long should I recover?" | "Is my cycling fitness improving?" | "What do my heart rate zones say?" · "How did my speed change over the ride?" |
| Activity, swim | "How consistent were my lengths?" | "How hard was this compared to my recent swims?" | "How long should I recover?" | "Is my swim pace improving?" | "How did my stroke rate change?" · "What do my heart rate zones say?" |
| Activity, strength or other | "How hard was this session?" | "How does this compare to my recent sessions?" | "How long should I recover?" | "How often do I train like this?" | "What do my heart rate zones say?" · "Which part was hardest?" |
| Trends | "What's changed over this {range}?" | "What stands out in my HRV?" | "Am I overreaching?" | "What helps my HRV?" | "How has my sleep trended?" · "How has my recovery trended?" |
| General | "How am I doing today?" | "How did I sleep last night?" | "How's my training week going?" | "What's changed this month?" | "What does my recovery mean today?" · "How was my last activity?" |

Placeholders that can't be filled, because the data is missing, drop that suggestion, and the next spare page
question takes its place.

### 3.8 The insight card

- **Where:**
  - On Today: **inside the Recovery card**, not as a card of its own. When Ready, the AI text replaces the rule-based
    sentence (a 12dp `#A78BFA` sparkle, then the text in the same style), and a tail row at the bottom of the card
    carries the muted engine label on the left and **"Ask about today ›"** (or the past day's date) in `#A78BFA` on the
    right, its own 48dp target. The training-load callout and the footer lines stay as they are. Generating, Hidden and
    mode Off leave the card exactly as it was (the rule-based sentence, no tail row, no shimmer). Couldn't generate
    keeps the rule-based sentence; the tail row shows the muted reason and **Retry** (`#3B82F6`, 48dp). The Ask and
    Retry links are separate clicks, so they don't open Trends; the rest of the card still does. TalkBack reads the
    merged card with the AI text in place of the sentence, then "Made on-device." or "Made in the cloud.", and adds the
    "Ask about today" action. With an AI insight showing, the rule-based "You're rested…" pill (Fresh week) is hidden because the
    strain-target line already says it; the Overreaching, past-the-safe-range and Detraining callouts always stay.
  - On Sleep: under the sleep-performance header (the standalone card below).
  - On an activity: under the summary tiles (the standalone card below).
  - Not on Trends in this piece.
- **Look (Sleep and activity):** a `#171A1F` card. The header is a small `#A78BFA` sparkle with "INSIGHT" in the dial-label style. The text
  is 2–3 sentences in body text. The footer has the muted engine label on the left and **"Ask about today ›"** (or "last
  night", "this run" …, as in §3.5) on the right, with a 48dp touch target. Tapping anywhere on the card also opens
  the sheet.
- **States:**
  - **Generating:** three shimmer lines at the card's final height, so the page doesn't jump. Text streams in over them.
    With reduced motion, the lines don't animate.
  - **Ready:** the text and the footer.
  - **Couldn't generate:** muted text with the reason (§8) and **Retry**.
  - **Hidden:** in mode Off, when no engine is usable, and on a day with no data for the page (for example a Today with
    no score yet). Hidden cards take no space.
- **TalkBack:** the card reads as one node: "Insight: *text*. Made on-device." It has a separate "Ask about today"
  action.

## 4. Engines (`ai/engine/`)

```kotlin
sealed interface AiEvent {
    data class Text(val delta: String) : AiEvent
    data class ToolStatus(val label: String) : AiEvent        // "Looking up HRV, 1 Sep – 31 Oct"
    data class Done(val engine: EngineLabel) : AiEvent
}
interface AiEngine { fun run(req: AiRequest): Flow<AiEvent> }  // errors are thrown as AiFailure (§8)
```

### 4.1 `NanoEngine`

- It wraps ML Kit behind a thin **`NanoBackend`** interface (status, download, token limit, count, stream), so tests
  use a fake and ML Kit is never loaded on the JVM.
- **`status: StateFlow<NanoStatus>`** is one of `Unavailable`, `Downloadable`, `Downloading(progress)`,
  `Ready(modelName, tokenLimit)` or `Resting(until)`.
  - `Resting` is set by a daily-quota error and lasts until the next local midnight (`DateTicker`).
  - The status is checked on app start and when Settings opens.
- **`fits(req)`:** `countTokens(req) + maxOutput ≤ tokenLimit`. `maxOutput` is 256 for chat and 160 for insights.
- The system prompt goes in as a system instruction when `getAvailableFeatures()` includes it, and is prepended to the
  first user turn otherwise.
- Temperature is 0.2, and the call has a 60-second timeout.
- `warmup()` runs when the sheet opens, and when a page that will generate an insight opens.

### 4.2 `CloudEngine`

- **The client.** A dedicated OkHttp client, **`aiHttp`**, built in `AppContainer` from a bare
  `OkHttpClient.Builder`: no authenticator, no interceptors, and no logging. It doesn't use `httpBuilder` or
  `GarminClient`. The timeouts are 15 s to connect and 60 s to read (per streamed chunk). An `Authorization: Bearer
  <key>` header is added per request, **only for requests whose host equals the configured base URL's host**.
- **The request.** `POST {baseUrl}/chat/completions` with `model`, `messages`, `stream: true`, `temperature: 0.3` and
  `max_tokens` (8,192 for chat and insights: reasoning models such as DeepSeek v4.x spend them on thinking before the
  answer, so a tight cap returns nothing; at 2,048 the activity insight came back empty every time. The prompts keep
  answers short, and only the tokens used are billed). `tools` and `tool_choice: "auto"` are added only when
  `toolsSupported` is true and the request is a chat.
- **Streaming.** The SSE `data:` lines are parsed by hand on OkHttp's source, stopping at `[DONE]`; this needs no
  `okhttp-sse` dependency. Text deltas are emitted as they arrive. `tool_calls` deltas are accumulated by index into
  complete calls.
- **The tool loop.** When a response ends with `finish_reason: "tool_calls"`:
  1. Run each call through `HealthTools` (§5.2) and emit a `ToolStatus` for each.
  2. Append the assistant message and the tool results.
  3. Request again.

  After **6 rounds**, one last request goes out with `tool_choice: "none"`.
- **Context budget.** Tokens are estimated at characters ÷ 4. If the conversation exceeds 60,000 estimated tokens,
  the oldest tool results are replaced by "[earlier lookup removed to save space]" until it fits.
- **Cancellation.** Cancelling the collecting coroutine cancels the OkHttp `Call`.

### 4.3 `AiKeyEntry` (the test)

`AiKeyEntry.saveAndTest(baseUrl, key, model)` sends one request, with `max_tokens: 1024` (at 16, DeepSeek v4.1 spent
them reasoning and never called the tool, so it read as "no tools"):

```json
{ "model": "<model>", "messages": [{"role": "system", "content": "Call the ping tool."}, {"role": "user", "content": "ping"}],
  "tools": [{"type": "function", "function": {"name": "ping", "description": "Health check", "parameters": {"type": "object", "properties": {}}}}],
  "tool_choice": "required", "stream": false, "max_tokens": 1024 }
```

| Response | Outcome |
|---|---|
| 200 with a `tool_calls` entry | Save, with `toolsSupported = true` |
| 200 with no tool call | Save, with `toolsSupported = false` |
| 400 or 422 | Retry once with `tool_choice: "auto"` (some providers reject `"required"`). If that fails too, retry without `tools`. A 200 then saves with `toolsSupported = false`. |
| 401 or 403 | "Key rejected." Nothing is saved. |
| 402 | "No credit left on this account." Nothing is saved. |
| 404, or an error naming the model | "This model isn't available here." Nothing is saved. |
| Network failure or timeout | "Couldn't reach *host*." Nothing is saved. |

The save writes the URL, encrypted key, model and `toolsSupported` in one DataStore edit, then applies the mode
transition (§3.1).

### 4.4 `AiKeyVault` and `AiPrefs`

- **`AiKeyVault`** stores `ai_base_url`, `ai_key` (encrypted through the existing `KeystoreAead`, under its own
  DataStore key), `ai_model`, `ai_tools_supported` and `ai_key_invalid`, in the same `baseline` DataStore.
  - `status: Flow<AiKeyStatus>` is one of `Missing`, `Ok` or `Invalid`. There's no expiry.
  - A 401 or 403 on any cloud request sets `Invalid`, and the next 200 clears it.
  - An undecryptable key resets the vault to `Missing`, like `ApiKeyVault`.
- **`AiPrefs`** stores `ai_mode` (`OFF`, `ON_DEVICE`, `CLOUD`, `HYBRID`, or absent for undecided).

## 5. What the model sees (`ai/context/`)

### 5.1 Context packs

`ContextPacks` builds plain text from `Repository` (and `TrainingLoad`) for a scope.

- **Two sizes:** **compact** for Nano (target ≤ 1,200 tokens) and **full** for the cloud.
- **Dates:** they come from `container.today` and `dateTicker.zone`, never `LocalDate.now()` or
  `ZoneId.systemDefault()`.
- **Units:** the user's units.
- **Missing values:** written as "no data", never as 0.

Every pack starts with a header:

```
Today is Sun 1 Nov 2026 (Europe/London). Units: metric.
Scope: sleep, night of Fri 31 Oct.
Glossary: Recovery 0–100% (green ≥67, amber 34–66, red ≤33) from HRV, resting HR, sleep and breathing vs 30-day baselines.
Strain 0–21 (90th-percentile day ≈ 17). Sleep performance = sleep vs need. Fitness/Fatigue = 42/7-day load averages;
Form = fitness − fatigue; week state Overreaching/Productive/Maintaining/Fresh/Detraining.
```

The body by scope:

- **`Today(date)`:**
  - Recovery % and zone, with the top contributors and their deltas.
  - HRV, RHR, sleep, respiration, Body Battery at wake, stress and steps against their 30-day baselines.
  - Strain so far and the target (eased in an Overreaching week).
  - Body Battery charge, latest level and drain.
  - The training-load state and "This week: N% of normal · ~K hard days left".
  - The day's activities (sport, start time, duration, distance, strain).
- **`Sleep(date)`** (`date` is the wake date, so the header above reads "night of *date − 1*"):
  - Sleep performance, duration, need, bed and wake times, and Garmin's score.
  - Stage minutes against their 30-day averages.
  - Overnight HRV average, lowest HR, Body Battery charged, SpO₂ and respiration.
  - That morning's recovery ("Recovery the morning after").
  - Links (below), plus "Training load is overreaching." in an Overreaching week.
  - Compact: the last 7 nights as one line of averages (duration, performance). Full: the last 30 nights, one line each.
- **`Activity(id)`:**
  - Sport, local start time, duration, distance, average pace or speed, average and maximum HR, time in each HR zone,
    Baseline strain and Garmin's training load.
  - Splits or laps (at most 20 rows; longer lists are merged into even groups), and average cadence, power and stride
    where present.
  - For swims: a summary of the lengths (count, pace per 100 m, stroke rate, spread). For strength: sets per exercise.
  - Links (below): going in (that morning's recovery, the night before against need), the day before when it was far from
    usual, the day's strain against its 30-day average, and the night after (sleep against need, bed time after the activity
    when within 3 h, HRV and resting HR against baseline, next morning's recovery), or "not recorded yet".
  - Rankings (longest, fastest of similar distance) only among at least 5 sessions of the sport; fewer say nothing.
  - Full only: the 10 previous same-sport activities, one line each. Compact leaves them out: the "Against your usual" line
    sums them up, and Nano compared this session with a single row (a 3.3-strain jog "slightly lower" than a 13.6 run).
- **`Trends(range)`:**
  - Compact: summary statistics per metric (mean, minimum, maximum and the slope over the range) plus the last 14 days
    as rows.
  - Full: one row per day for the range (recovery, HRV, RHR, sleep performance, strain, fitness, fatigue, form).
- **`General`:** the `Today(today)` pack.

**Links** (Today, Sleep and Activity). The insights are only worth reading when they tie a page to the others ("a hard
day and a late run, then a short night, then amber recovery"). Nano can't find that in rows, so `ContextPacks` works the
links out and writes them as sentences under "Links (from other pages; possible causes, not proof):". A line is written
only when it clears its threshold, so an empty block ("Links: nothing unusual on the days around it.") tells the model
there is nothing to connect. For a night (Sleep, and Today for last night):

- the day before, when its strain is 3 or more from its 30-day average, with that day's activities;
- an activity that ended within 3 h of bed ("Run, strain 9.1, ended 1h 8m before bed.");
- the bedtime, when 45 min or more from the median of the last 14 nights (local clock, minutes after noon so either side
  of midnight compares);
- the 3 nights before, when together they were 90 min or more under need.

When the day before was harder, a workout ended close to bed, the bedtime was later or the nights before were short, a
last line gives the verdict: "Overnight HRV came in −14% vs baseline, as these usually make it." or "Despite these,
overnight HRV held up (+9% vs baseline): they don't show a toll." The prompt alone didn't stop Nano crediting a hard day
for a good night, so the direction is worked out here. Today adds a run of HRV more than 5% from baseline on the same
side, 3 or more days running.

**Never included, in a pack or a tool result:** latitude, longitude, routes or polylines, place or activity names
typed by the user, device names, device IDs or serials, and Garmin user or profile IDs. Activities are referred to
by sport and start time. Tools address them by `ActivityEntity.id`, which is **Garmin's own activity id**, not a number
Baseline made up. It isn't a user or device identifier, and Garmin activities are private by default, but it does go to
the user's chosen cloud provider as part of a tool result and the model's follow-up call.

### 5.2 Health tools (cloud only)

`HealthTools` exposes read-only functions over `Repository`. Each validates its arguments and caps its result at
about 8,000 tokens (32,000 characters). When a result is cut, it ends with "(truncated — ask for a shorter range)".

| Tool | Arguments | Returns |
|---|---|---|
| `daily_metrics` | `from`, `to` (ISO dates, at most 400 days), `fields` (a subset of: recovery, hrv, rhr, sleep_performance, sleep_hours, respiration, body_battery_wake, stress, steps, strain) | One row per day |
| `activities` | `from`, `to` (at most 400 days), optional `sport` (run, ride, swim, strength, other) | One row per activity: id, sport, start, duration, distance, average HR, strain |
| `activity_detail` | `id` | The full `Activity(id)` pack body |
| `sleep_night` | `date` (the wake date, as in §3.5) | The full `Sleep(date)` pack body |
| `training_load` | `from`, `to` (at most 400 days) | One row per day: fitness, fatigue, form, state |
| `records` | optional `sport` | All-time best efforts: distance, time, date |

Tool rows use **SI units, with the unit in the key** (`duration_min`, `distance_km`), whatever the user's unit
setting; the pack bodies in §5.1 use the user's units. Bad arguments return `{"error": "…"}` as the tool result, so the model can correct itself; that counts as a round.
No tool writes, triggers a sync, or reaches the network.

### 5.3 Prompts

- **Every prompt is versioned** (`PROMPT_VERSION`). Bumping it invalidates cached insights (§7).
- **Chat, cloud:**
  - Role: "You are the assistant inside Baseline, a personal health dashboard built on the user's Garmin data."
  - Answer from the data given and from tool results only. Say when data is missing.
  - Quote the numbers you rely on, in the user's units.
  - Be concise: short paragraphs, no tables unless asked.
  - Don't invent data or diagnoses.
  - Not medical advice. For chest pain, fainting, a resting HR that stays far above baseline, or similar, suggest
    seeing a doctor.
- **Chat, Nano:** a trimmed version of the cloud prompt, plus: "If answering needs data that isn't shown above, reply
  with exactly NEEDS_HISTORY."
- **Insight** (`PROMPT_VERSION` 8), one prompt per page, each "in 2 or 3 short sentences":
  - Today: explain the day's recovery, what drove it and, from the Links, what is behind that. Don't restate the score.
  - Sleep: what was different about the night and, if a Links line accounts for it, tie the two together. Don't restate
    the sleep time, performance or Garmin score.
  - Activity: an all-time record first if it set one, else no mention of records ("personal best" only for those), how hard it was against the usual session, then the
    Links (what they came in with, how the night after and the next morning responded). Don't repeat the stat tiles.
  - All: a hard day, a late workout, a late bedtime and short nights usually lower HRV, sleep and recovery, so a result
    that went the other way held up "despite" them, not "because of" them; a change under 10% from baseline is normal and
    isn't the lead; quote the numbers behind each point; a link is "likely" or "may", never certain, and comes only from the Links
    lines; no greeting, opener or advice; no "significant" without a figure; a doctor only for something clearly abnormal.
  - History: version 1 asked for "the most useful thing" and got meta openers. Versions 2–4 asked for "the signals that
    moved furthest from their baseline", and on the phone that only restated the card ("HRV 97 ms vs a baseline of 91 ms");
    version 5 added the Links. On the phone, Nano then credited a harder day before for a good night ("may be linked to the
    preceding day being much harder") and led with a +6% HRV, so version 6 added the direction and the 10% rule (and the
    verdict line in §5.1). Version 7 stops it saying "as indicated by the link" and repeating itself.
    Version 8 stops DeepSeek opening with "No all-time record was set".
  - Insights never use tools.

### 5.4 Disclaimer

The sheet carries no banner. "Not medical advice" is in the Settings privacy line (§3.2) and the system prompt.

## 6. Routing (`ai/AiRouter`)

`AiRouter.route(turn, mode, state)` picks `NANO`, `CLOUD` or a `Note`. Its inputs:

- The question's tag (page, training or pattern; a typed question is page).
- Whether this is the first turn.
- Whether the chat has already used the cloud.
- Whether Nano is usable (`Ready`, and not `Resting`), and whether the request `fits`.
- Whether the cloud is usable (a key is set and the phone is online; an `Invalid` key still counts as usable, so the
  key can heal).

| Mode | Rule, in order |
|---|---|
| Off | Never called. |
| On-device only | Nano if it fits. Otherwise the note "This needs more room than on-device AI allows. Try a shorter question, or add an API key in Settings." |
| Cloud only | Cloud if online. Otherwise the error "You're offline." |
| Hybrid | 1. A chat that has used the cloud stays on the cloud while it's usable. 2. Any follow-up goes to the cloud when usable. 3. A training or pattern question goes to the cloud when usable. 4. Nano when it's usable and fits. 5. The cloud when usable. 6. Nano usable but the request doesn't fit: the note "You're offline, and this needs more than on-device AI can hold." 7. Otherwise the error "AI isn't available right now." |

- **NEEDS_HISTORY.**
  - An answer that is exactly `NEEDS_HISTORY` (after trimming) is never shown.
  - In Hybrid with the cloud usable, the same turn is re-run on the cloud and the chat becomes cloud from then on.
  - Otherwise the note is "On-device AI can only see this page. Add an API key in Settings for questions about your
    history" in On-device only mode, and "You're offline; this needs your history" in Hybrid.
- **More detail ☁** re-asks the turn's question on the cloud, with the conversation so far, and the chat becomes cloud.
- **Nano failures in Hybrid:**
  - `BUSY`: one retry after 1 s, then the cloud.
  - Daily quota: Nano becomes `Resting` until midnight, and the turn moves to the cloud.
  - Any other Nano error: the cloud.

  In On-device only mode, these show their errors instead (§8).
- **Insights** use the same router with the tag *page*, no follow-ups, and no NEEDS_HISTORY instruction. In Hybrid an
  insight that doesn't fit Nano goes to the cloud.

`ChatSession` holds the messages, the engine of each answer and the "used cloud" flag. It's owned by
`AskViewModel`, which is created per sheet from the container like other ViewModels.

## 7. Insights and their cache

### 7.1 Flow

1. A page with an insight (Today's Recovery card, the Sleep and Activity slots) asks `InsightService.insight(scope)` while it's on screen.
2. The service builds the scope's pack (compact if Nano will run it, full for the cloud) and computes
   `fingerprint = SHA-256(PROMPT_VERSION + "\n" + compact pack basis)`.
3. **Cache hit** (same scope and key, same fingerprint): emit Ready.
4. **Miss:** emit Generating, run the engine from the router, and stream the text.
   - When it completes, write the row and emit Ready.
   - When it fails, emit Couldn't generate. Nothing is written.
   - When it's cancelled (the page left), nothing is written.
5. A failed generation retries when the user taps Retry, reopens the page, or the basis changes (step 6).
6. **Live refresh.** While the card is collected the flow stays open. `ContextPacks.basisChanges(scope)` re-computes the
   basis whenever the Room rows the scope reads change (for Today, the day, its score, its activities and their scores, plus
   the night's Links rows; for Sleep, the last 14 nights, the day before's score, activities and their scores; for an
   activity, the activity, its score, and the days and scores from the day before it to the day after it, so the card
   updates when the night after syncs) and ticks when the basis differs from the last
   one. Each tick cancels the run in progress and repeats steps 2 to 4 (`flatMapLatest`), so a new activity or its strain
   regenerates the card in place. The first tick is immediate (opening a page never waits); later changes settle for 3 s
   before the basis is rebuilt, so a sync that writes an activity and then its score regenerates once. A change that touches
   only volatile lines ticks nothing.

**The basis** is the pack text without its volatile lines (`Pack.basis`; `Pack.text` is unchanged and is what the model
reads). Volatile lines exist only for Today's pack when the date is today (and General, which builds it), because they are
running totals that move with every sync and would regenerate the insight each visit for no new information:

- the steps line (steps so far today);
- the running number in the strain line ("Strain 8.4 so far"); the strain **target** stays in the basis;
- Body Battery "latest" and "drained"; "charged" stays;
- average stress;
- the "This week: N% of normal" line.

Everything else is in the basis: recovery and its drivers, HRV, resting HR, sleep and respiration lines, Body Battery at
wake, the training-load state line (fitness and fatigue for a date come from the days before it, so it holds all day), and
every line of the day's activity list (sport, start, duration, distance, strain), so a new activity, or its strain once
scored, regenerates the insight. For past days and for the Sleep and Activity scopes the basis equals the text. Compact and
full Today packs have the same lines, so they share a basis; Sleep and Activity packs differ by size, and the compact
basis is the one fingerprinted whichever engine runs.

The fingerprint is of the compact basis whichever engine produced the text. If the engine changes later (a different
mode), the cached text is still shown. Only a change of the stable data or the prompt version regenerates it.

### 7.2 Table

```kotlin
@Entity(tableName = "ai_insight", primaryKeys = ["scope", "scopeKey"])
data class AiInsightEntity(
    val scope: String,        // "today" | "sleep" | "activity"
    val scopeKey: String,     // ISO date, or activity id (Garmin's)
    val fingerprint: String,
    val text: String,
    val engine: String,       // "nano" | "cloud"
    val model: String?,       // cloud model id, or Nano base model name
    val createdAt: Instant,
)
```

**Clear AI insights** deletes every row. Rows are never pruned automatically; at a few hundred bytes each, years of
them take well under a megabyte.

### 7.3 Migration (version 2 → 3)

`AppDatabase` uses `fallbackToDestructiveMigration(dropAllTables = true)`. **A schema bump without a registered
migration would wipe all synced data and trigger a full backfill** of about 1,200 Garmin requests. So:

- The version goes to 3, the new entity and its DAO are added, and `app/schemas/.../3.json` is exported.
- An explicit `MIGRATION_2_3` creates the table with the SQL from the exported schema, and is registered with
  `.addMigrations(MIGRATION_2_3)`.
- The doc comment on `AppDatabase` gains a line: tables that aren't re-downloadable (`ai_insight`) need a real
  migration.
- A test (§11.1) opens a version-2 database containing rows and checks they survive the upgrade.

## 8. Error handling

The UI text is built from status codes and our own wording. **Provider error bodies are never shown, logged or put in
exceptions.**

| Case | Chat | Insight card |
|---|---|---|
| 401 or 403 | "Your API key was rejected. Check it in Settings." The key becomes `Invalid`. | Hybrid: Nano instead if it fits. Otherwise "API key rejected" and Retry. |
| 402 | "No credit left at *host*." Hybrid: page questions retry on Nano. | Hybrid: Nano. Otherwise "No credit left at *host*". |
| 404, or a model error | "This model is no longer available. Pick another in Settings." | The same text |
| 429 | One retry after `Retry-After` (at most 10 s; 2 s if absent), then "*host* is busy. Try again shortly." | The same, with Retry |
| 5xx, timeout or connection failure | Hybrid: Nano with a note "(answered on-device; couldn't reach *host*)" when the question fits. Otherwise "Couldn't reach *host*." and Try again. | Hybrid: Nano. Otherwise "Couldn't reach *host*" |
| Offline | Cloud only: "You're offline." Hybrid: routed per §6. | Hybrid: Nano. Cloud only: hidden until online. |
| Malformed stream or JSON | "Something went wrong reading the answer." and Try again | "Couldn't generate" |
| More than 6 tool rounds | A forced final answer (§4.2) | — (insights have no tools) |
| Nano `BUSY` | Hybrid: the cloud. On-device only: one retry after 1 s, then "On-device AI is busy. Try again in a moment." | The same |
| Nano daily quota | Hybrid: the cloud. On-device only: "On-device AI has reached today's limit." | Hybrid: the cloud. Otherwise hidden until midnight. |
| Nano `BACKGROUND_USE_BLOCKED` | Cancelled silently (the user left) | Cancelled. Nothing is cached. |
| Nano not downloaded | Routed elsewhere. Settings shows Download. | The same |

Nothing in the AI code path calls `Log`, and the logging interceptor is never added to `aiHttp`.

## 9. Security and privacy invariants

These are added to CLAUDE.md's invariants:

- **The AI key is a secret.** It's never logged or put in a message, exception or test output. It's stored only
  through `KeystoreAead`.
- **The AI key goes only to the configured base URL's host,** over `aiHttp`. Garmin, GitHub and any other traffic
  never carry it, and `aiHttp` never carries a Garmin credential.
- **A key under test never touches the vault** (as with Garmin).
- **On-device only mode makes no AI network requests.**
- **Nothing sent to an AI engine contains GPS, routes, names, device names or IDs, or Garmin user or profile IDs** (§5.1). An
  activity's Garmin id is sent so tools can address it.
- **AI tools are read-only.** They only read Room, and never write, sync or reach the network. The one-Garmin-request
  rule is untouched.

## 10. Code layout

```
dev/baseline/ai/
  AiMode.kt, AiPrefs.kt               mode + transitions (§3.1)
  AiKeyVault.kt, AiKeyEntry.kt        secret storage, /models, test-and-save (§3.3, §4.3, §4.4)
  AiRouter.kt, ChatSession.kt         routing table (§6), in-memory chat + tool loop driver
  engine/AiEngine.kt                  AiEvent, AiRequest, AiFailure, EngineLabel
  engine/NanoEngine.kt, NanoBackend.kt, MlKitNanoBackend.kt
  engine/CloudEngine.kt, OpenAiWire.kt  (request body, SSE chunk and tool-call parsing, built by hand)
  context/AskScope.kt, ContextPacks.kt, HealthTools.kt, Prompts.kt, Suggestions.kt
  insight/InsightService.kt
data/db/AiInsightEntity.kt, AiInsightDao.kt, Migrations.kt
ui/ai/AskSheet.kt, AskViewModel.kt, InsightCard.kt, AskButton.kt
ui/settings/AiCard.kt, AiKeyScreen.kt, AiKeyViewModel.kt
```

- **`AppContainer`** wires `aiHttp`, `aiKeyVault`, `aiPrefs`, `nanoEngine`, `cloudEngine`, `aiRouter`, `contextPacks`,
  `healthTools` and `insightService`. `nanoBackend` is `open`, so tests can swap in a fake.
- **`BaselineNavHost`:**
  - Adds the centre `AskButton` to the bar (hidden in mode Off).
  - Holds the current `AskScope`, reported by each screen through a `ReportAskScope(scope)` effect; it defaults to
    `General`.
  - Shows `AskSheet` when the button or an insight link asks for it.
- **The Today, Sleep and Activity screens** each add an `InsightCard`.

## 11. Testing

### 11.1 Unit and Robolectric (`app/src/test`)

- **`AiRouterTest`:** a table test over every mode against: Nano Ready, Resting or Unavailable; fits or not; a key
  that's Ok, Invalid or Missing; online or offline; first turn or follow-up; the "used cloud" flag; and the tag.
  NEEDS_HISTORY handling, and More detail.
- **`AiModeTest`:** the defaults and every transition in §3.1.
- **`CloudEngineTest`** (`MockWebServer`):
  - SSE text streaming.
  - Tool-call deltas split across chunks.
  - The full tool loop against a fake `HealthTools`.
  - The 6-round cap, then `tool_choice: "none"`.
  - `max_tokens` and `tools` present or absent per request kind.
  - Each error row in §8, including `Retry-After`.
  - Cancellation cancels the call.
  - The context budget drops the oldest tool results first.
- **`AiKeyEntryTest`:**
  - Every row of §4.3.
  - **A failing test leaves the vault empty.**
  - `/models` with OpenRouter fields, without them, and failing (free-text fallback).
  - Change model re-tests without rewriting the key.
  - The mode transition on save and on remove.
- **`AiSecretsTest`:**
  - `Authorization` is sent only to the configured host.
  - `aiHttp` has no interceptors or authenticator.
  - The Garmin (`httpBuilder`) and update (`updateHttp`) clients never see the AI key.
  - A provider error body containing the key never reaches an exception message or the UI state.
- **`NanoEngineTest`** (fake `NanoBackend`): the status mapping, the `fits` arithmetic, `Resting` until midnight
  under an injected `Clock`, system-prompt fallback, and `BUSY` retry.
- **`ContextPacksTest`:**
  - Golden text for each scope, from the anonymized fixtures, with an injected `Clock` and zone; passes in any host
    time zone.
  - Compact packs stay at or under 1,200 estimated tokens.
  - "no data" for missing values.
- **`HealthToolsTest`:** argument validation (ranges over 400 days, unknown fields), result caps and the truncation
  note.
- **`PrivacyScanTest`:** every pack and every tool result, built over all fixtures, contains no coordinate-like number
  pairs, no `lat`/`lon`/`polyline` keys, no fixture device or user identifiers, and no activity names.
- **`InsightServiceTest`:**
  - A cache hit skips the engine.
  - A data change regenerates.
  - A `PROMPT_VERSION` bump regenerates.
  - A failure or cancellation writes nothing.
- **`MigrationTest`:** a version-2 database with day, score and activity rows opens at version 3 with those rows
  intact, and the `ai_insight` table exists. It uses `testutil.inMemoryDatabase()` where possible, plus a file-backed
  database built at version 2 for the upgrade.
- **Compose UI:**
  - **`AskSheetTest`:**
    - At most 4 suggestions, stacked, hidden after the first send.
    - The trailing label, with "More detail ☁" only on Nano answers in Hybrid.
    - Tool status lines.
    - Close cancels.
    - 48dp targets and TalkBack labels.
  - **`InsightCardTest`:** all four states, the link, and the merged TalkBack node.
  - **`AiCardTest`:** greyed modes with reasons, the Nano status rows and the hint.
  - **`NavAskButtonTest`:** the button is present only when the mode isn't Off, opens the sheet with the current
    page's scope, doesn't change the selected tab, and leaves a 48dp target.
- **`ReleaseNotesTest`**, lint and Spotless are unchanged and must pass.

### 11.2 Emulator: `tools/fake_llm.py`

`tools/fake_llm.py` is a stdlib-only fake OpenAI-compatible server (default port 8090):

- `GET /v1/models` returns three models: one with tools, one without, and `deepseek/deepseek-v4.1-flash`, all with
  OpenRouter-style fields.
- `POST /v1/chat/completions` streams a scripted answer that quotes the pack's first number. When tools are offered,
  it first calls `daily_metrics` for the last 30 days.
- `--key` sets the accepted key (default `fake-ai-key`); any other key gets a 401. `--fail 402|429|500` makes every
  request fail with that status.
- It logs method, path, status and the request's size in tokens (never the body).
- `tools/test_fake_llm.py` covers it.

Test builds made with `-PgarminBaseUrl` accept `http://127.0.0.1` and `http://localhost` as base URLs. Those builds
already allow cleartext traffic for the fake Garmin server and are never released (`BuildConfig.ALLOW_LOOPBACK_HTTP`).
Use it with `adb reverse tcp:8090 tcp:8090` and the base URL `http://127.0.0.1:8090/v1`, next to `fake_garmin.py`. This covers Cloud only mode, Settings, the sheet, insights and
the error rows end to end.

### 11.3 On the owner's phone (Pixel 11 Pro) and the second user's (Pixel 10 Pro)

These can't be tested in the emulator or the cloud container:

- The Nano status, the download, and the real `getTokenLimit()` (shown in Settings).
- Answer quality on nano-v4 and nano-v3.
- **How reliably Nano answers `NEEDS_HISTORY`** when it should.
- Foreground-only behaviour (lock the screen mid-answer).
- Quota behaviour under heavy use.
- One real OpenRouter round with DeepSeek V4.1 Flash, including a tool loop.

### 11.4 Release

- `lintDebug` (with warnings as errors) and `spotlessCheck` pass.
- R8: ML Kit ships consumer rules, and the wire format has no `@Serializable` types (§12). A minified release has to
  build (it does, in the cloud container) and pass a smoke test against `fake_garmin.py` and `fake_llm.py`
  (CLAUDE.md's recipe), which needs an emulator and is still owed.
- The baseline profile gains the Ask sheet and insight card paths only if the smoke test shows jank.

## 12. Dependencies

- **`com.google.mlkit:genai-prompt`** (beta), pinned in `gradle/libs.versions.toml` to `1.0.0-beta5`, the version
  current at implementation. Reason: it's the only supported way to run Gemini Nano from a third-party app. It's isolated behind
  `NanoBackend`, so a breaking beta change touches one file.
- No other new dependency. The cloud client uses OkHttp 5 and kotlinx.serialization, which are already present. There are
  no `@Serializable` types: JSON is built and parsed by hand with `JsonElement`, like the rest of the app, so there is
  no kotlinx.serialization R8 concern.

## 13. Spec and doc updates in the same change

- **Main spec:**
  - §3: the `ai/` package.
  - §6: the `ai_insight` table and schema version 3.
  - §9: the insight card on Today, Sleep and Activity, the Ask sheet and the AI card in Settings.
  - Navigation: the centre Ask button rule from §3.4.
  - §1: the app now has two users, each on their own install.
- **CLAUDE.md:**
  - Architecture: `ai/` and `aiHttp`.
  - Invariants: §9.
  - Testing: the `fake_llm.py` recipe.
  - Known follow-ups: the open items below.
- **`docs/cloud-sessions.md`:** `fake_llm.py` runs in the container, and Nano can't be tested there.

## 14. Open items and risks

- **Nano's window and output limits are unpublished.** The design adapts to `getTokenLimit()` at runtime, but compact
  pack sizes (1,200 tokens) may need tuning per device once the real values are seen in Settings.
- **NEEDS_HISTORY depends on Nano following an instruction.** If it proves unreliable, Hybrid still has "More detail ☁".
  A fallback is to route typed questions with time words ("week", "month", "lately", "trend") to the cloud.
- **ML Kit's Prompt API is beta** and may change incompatibly. `NanoBackend` contains the impact.
- **Cloud data leaves the phone** to the user's chosen provider. OpenRouter routes to sub-providers, and the user
  controls data-retention and training preferences in their OpenRouter account. The Settings privacy line says what's
  sent.
- **The AI accent reuses the sleep colour `#A78BFA`** (as approved in the mockups). If it reads as "sleep" on Today or
  activities, a dedicated AI accent can be added later.
- **Cost:** with the default model, an insight or a chat question with a few lookups costs well under one US cent.
  There's no budget control (a non-goal).
