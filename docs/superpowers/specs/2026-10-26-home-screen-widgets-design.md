# Baseline — Android Home-Screen Widgets: Design Spec

Date: 2026-10-26
Status: draft for review
Supersedes: the "Home-screen widgets" v1 non-goal in `docs/superpowers/specs/2026-10-24-baseline-android-design.md` (§1)

## 1. Intent and Why

Baseline already computes daily Recovery %, Sleep %, Day Strain (0–21) with an optimal target band, 30-day physiological baselines, Body Battery curves, sleep stage / tonight's sleep-need guidance, and weekly training summaries on the device. Until now, checking any of those numbers meant opening the app.

Home-screen widgets bring those metrics directly onto the Android launcher so the user can glance at morning readiness, live daytime energy and strain accumulation, overnight vital-sign trends, tonight's bedtime target, and weekly training progress—and trigger a background sync or jump straight to the matching screen with one tap.

## 2. Goals, Non-Goals, and Anti-Goals

### Goals
- Ship five home-screen widgets covering the full daily and weekly loop:
  1. **Daily Readiness & Strain** (responsive 2×2 compact tile and 4×2 full card)
  2. **Health Monitor (Baseline Vitals)** (4×2)
  3. **Live Body Battery & Stress** (4×2)
  4. **Sleep & Tonight's Bedtime** (4×2)
  5. **Weekly Summary & Latest Activity** (4×2)
- Match Baseline's dark visual identity (`#0E1013` / `#171A1F` surfaces, `#2E333B` hairline border, `BarlowCondensed` dial/chart numerals, recovery green/amber/red, strain blue, sleep purple, and Body Battery band colors).
- Keep widgets fresh automatically whenever sync completes, scores recalculate, display/unit preferences change, or the local date/timezone rolls over—plus offer a one-tap `↻` sync action on every widget.
- Deep-link taps on each widget to the corresponding destination inside Baseline.

### Non-Goals
- Per-widget configuration screens (`android:configure`), light mode, or Material You dynamic wallpaper theming (Baseline is dark-only per main spec §9).
- Lock-screen complications, Wear OS tiles, or Android Live Updates / ongoing workout notifications.
- Fetching new Garmin endpoints; all five widgets read data already stored in Room and DataStore.

### Anti-Goals (Failure Modes Even If Technically Working)
- **Stale yesterday data masquerading as today:** Before the user's watch uploads morning sleep to Garmin Connect, today's sleep and recovery must show `"—"`, never yesterday's score labeled as today.
- **Lying relative timestamps:** Writing `"12m ago"` into static `RemoteViews` text goes stale when Android throttles background work. Sync pills use wall-clock sync time (or `"Syncing…"` while active) so the displayed label stays truthful between updates.
- **Direct network calls from a widget receiver:** A `BroadcastReceiver` / `AppWidgetProvider` must never call `GarminClient` directly; all network work goes through `SyncScheduler` / `SyncWorker`.
- **Binder / bitmap memory blowups:** Full-bleed high-density bitmaps across five 4×2 widgets can trip Android's `RemoteViews` bitmap memory cap; card surfaces, inner boxes, pills, and text grids use XML `<shape>` drawables and `TextView`s, reserving bitmaps strictly for arcs, sparklines, and proportional bars.

## 3. Constraints and Project Invariants

- **Stack:** Kotlin 2.4, AGP 9.4, `minSdk 29`, `targetSdk 37`, Room 3, DataStore, WorkManager, R8 minification and resource shrinking enabled on release (`app/proguard-rules.pro`), `lintDebug` with `warningsAsErrors = true`, Spotless/ktlint formatting.
- **Zero new dependencies:** Built entirely on the Android SDK (`android.appwidget`, `android.widget.RemoteViews`, `android.graphics.Canvas`) and existing app modules.
- **Single-client sync invariant:** `SyncWorker` remains the only caller of `GarminClient`. Widget sync actions enqueue work via `SyncScheduler.syncNow()`.
- **Secret safety:** No key material or tokens ever touch widget state, intents, or logs.
- **Accessibility (main spec §9):** Interactive targets on widgets (specifically the `↻` sync button) are at least `48dp × 48dp`. Every bitmap (`ImageView`) and actionable region carries a clear TalkBack `contentDescription`, and color is never the sole indicator (deltas keep `▲`/`▼` arrows and verdicts).
- **Time-zone independence:** All date/time formatting and unit tests take an injected `Clock` / `ZoneId`, never hard-coding the host JVM's default zone.

## 4. Approaches Considered

### Approach 1: Native `AppWidgetProvider` + XML `RemoteViews` + targeted `Canvas` bitmaps *(Chosen)*
- **How it works:** Uses Android's `AppWidgetProvider` and XML `RemoteViews` layouts with `<shape>` drawables for rounded `#171A1F` cards, `#0E1013` inner tiles, and pills; native `TextView`s for headers, labels, values, and delta arrows; and small `android.graphics.Canvas` bitmaps (using bundled `R.font.barlow_condensed_*` typefaces) only where `RemoteViews` cannot draw natively: circular score dials, the Body Battery sparkline, and the sleep stage bar. On API 31+, the responsive Readiness widget supplies a `Map<SizeF, RemoteViews>` so launcher resizing between 2×2 and 4×2 switches layouts immediately, with an `onAppWidgetOptionsChanged` width check (`OPTION_APPWIDGET_MIN_WIDTH`) fallback on API 29–30.
- **Tradeoffs:** Layouts live in XML rather than Compose syntax, and XML `TextView`s may render in the system sans-serif on launchers that ignore bundled `@font/` resources in `RemoteViews` (while dial numerals always render in `BarlowCondensed`). In exchange, it requires zero new dependencies, creates no extra WorkManager workers, keeps IPC bitmap memory tiny even with all five widgets placed at once, preserves OS `sp` font scaling and granular TalkBack nodes, and can be tested directly in Robolectric by inflating the `RemoteViews`.

### Approach 2: Jetpack Glance (`androidx.glance:glance-appwidget`) + targeted `Canvas` bitmaps
- **How it works:** Adds Jetpack Glance to write widget trees in Glance's `@Composable` DSL while still drawing dials, sparklines, and stage bars to `Canvas` bitmaps (since Glance compiles to `RemoteViews` and has no `Canvas`).
- **Tradeoffs:** Gives Kotlin `@Composable` syntax and `SizeMode.Responsive`, but adds a new dependency tree, introduces Glance's internal `SessionWorker` on WorkManager (requiring care around Baseline's custom `Configuration.Provider` and WorkManager tests), still cannot reuse `androidx.compose.ui` composables, and risks version friction with Kotlin `2.4.21` / Compose BOM `2026.09`.

### Approach 3: Native `AppWidgetProvider` + full-card `Canvas` bitmaps + tap overlays
- **How it works:** Renders each entire 4×2 widget card as a single high-DPI `Bitmap` with `Canvas` and bundled fonts, using `RemoteViews` only as a frame with transparent clickable overlays.
- **Tradeoffs:** Guarantees pixel-identical `BarlowCondensed` / `Inter` typography everywhere, but a single 4×2 `ARGB_8888` bitmap at 3.5× density exceeds 3 MB—risking `RemoteViews` Binder memory-cap crashes when multiple widgets are active—and sacrifices OS font scaling and per-element TalkBack focus.

**Why Approach 1 was chosen:** The user chose Approach 1 for strong visual fidelity and reliable runtime performance without dependency bloat or bitmap memory risk.

## 5. Widget Catalog and Behavior

### Shared Shell & Header (All 5 Widgets)
- **Card styling:** `#171A1F` background (`Card`), `1dp` `#2E333B` stroke (`Outline`), `24dp` corner radius (using `@android:dimen/system_app_widget_background_radius` on API 31+ via `values-v31` so corners match the host launcher's mask, `24dp` fallback on API 29–30). Inner sub-cards use `#0E1013` (`Bg`) with `12dp`–`14dp` radius (`16dp` / `@android:dimen/system_app_widget_inner_radius` where appropriate).
- **Top-right sync control (`≥ 48dp × 48dp` hit target):**
  - Displays a `#262A31` (`Surface2`) pill inside the `48dp` touch target:
    - While any `SyncWorker` is `RUNNING`: `"Syncing…"`
    - When `KeyStatus` is `Invalid` or `Expired`: amber/red warning dot + `"Key error"` (`#FECACA` on `#3B1A1A`). Tapping this pill when the key is invalid/expired opens **Settings** so the user can paste a new key; otherwise tapping the pill triggers `SyncScheduler.syncNow()` via a `BroadcastReceiver` action (`ACTION_WIDGET_SYNC`). *(Designer's call: routing a tap on `"Key error"` to Settings instead of firing a doomed sync gets the user straight to the fix, while normal scheduled sync still keeps retrying per the key-healing invariant.)*
    - Otherwise, when `lastSyncedAt` exists: `"↻ 7:41 AM"` if synced today (respecting the device's 12/24-hour setting via `DateFormat.is24HourFormat`), or `"↻ Oct 22"` if last synced on a prior day. *(Designer's call: wall-clock time instead of `"12m ago"` so the pill never drifts between updates.)*
    - Before the first completed sync: `"↻ Sync"`.
- **Missing API key (`KeyStatus.Missing`):**
  - Instead of broken empty grids, any placed widget renders a centered prompt (`"Connect Garmin API key to start"`, `#9AA3AE`) inside the dark card shell; tapping anywhere opens `MainActivity` (which routes to Onboarding).
- **Unreadable day data (`Repository.dayUnreadable(today)` is true):**
  - Day-based widgets (1–4) show `"Couldn't read today's data"` in `#FBBF24` (`RecoveryAmber`) in their subtitle/footer rather than silently showing dashes without context, matching spec §10. *(Designer's call, for parity with `TodayScreen` and `SleepScreen`.)*

---

### Widget 1: Daily Readiness & Strain (`ReadinessWidgetProvider`)
- **Sizing:** Default `4×2`, resizable down to `2×2` (`minResizeWidth` / `minResizeHeight` for 2×2; `targetCellWidth="4"`, `targetCellHeight="2"` on API 31+).
  - **Compact layout (`width < 250dp`, ~2×2):**
    - Header: `"BASELINE"` + compact `↻` sync button (`48dp` hit target).
    - Two large side-by-side numeric readouts (no Sleep): **Recovery %** (colored by `RecoveryZone`: `#34D399` green ≥ 67, `#FBBF24` amber 34–66, `#F87171` red < 34; `"—"` if null) and **Strain** (`#60A5FA`, rounded to `0.1`, with `"Target 14–18"` beneath when `strainTarget` is present).
    - Bottom `#0E1013` bar: **Body Battery** latest level (`0–100` or `"—"`) + horizontal fill bar colored by `bodyBatteryColor(level)`.
  - **Expanded layout (`width ≥ 250dp`, ~4×2):**
    - Header: `"TODAY · <EEE MMM d>"` + `↻ <time>` sync pill.
    - Three `Canvas`-rendered circular dials matching `Dial.kt`: **Recovery %** (`#34D399`/`#FBBF24`/`#F87171`, or `"Partial"` note when `recovery.partial` is true), **Strain** (`#60A5FA` with target range note inside the ring, e.g. `"14–18"`), and **Sleep %** (`#A78BFA`). Missing values render an empty `#262A31` track and `"—"`.
    - Bottom `#0E1013` strip: left side shows `"Target strain 14.0–18.0"` (or `recoveryReason` such as `"Calibrating — need 7 nights"` / `"No sleep recorded"` when Recovery is unavailable); right side shows live Body Battery badge (`"BB 64"` colored by Garmin band, or `"BB —"`).
- **Tap action:** Body opens `MainActivity` on **Today** (resetting Today to the current date, just like tapping the Today tab).

---

### Widget 2: Health Monitor / Baseline Vitals (`VitalsWidgetProvider`)
- **Sizing:** `4×2` (`minWidth` for 4 cells, `minHeight` for 2 cells, horizontally/vertically resizable).
- **Data selection:** Reuses the exact baseline math and row ordering from `TodayViewModel` (`DEFAULT_BASELINE_ORDER` + `UserPrefs.baselineOrder` minus `UserPrefs.hiddenRows`, over the 30-day window ending `today.minusDays(1)`).
  - Picks the first **4** visible rows. If the user hid more than 3 rows in Today's Edit mode (leaving fewer than 4 visible), only the remaining visible rows are shown (empty slots hidden cleanly rather than overriding hidden preferences; if all 7 are hidden, shows `"All baseline metrics hidden in Today"`). *(Designer's call: respects explicit hide choices instead of resurfacing hidden metrics on the home screen.)*
- **Layout:**
  - Header: `"VS. 30-DAY BASELINE"` + status summary next to the sync pill (e.g. `"3/4 better/normal"` or `"1 worse"` in `#FBBF24` when any visible row has `better == false`, omitted when no values have arrived today).
  - `2×2` grid of `#0E1013` vital boxes, each showing:
    - Metric label (shortened cleanly for widget width where needed: `"HRV"`, `"Resting HR"`, `"Sleep"`, `"Respiration"`, `"Wake BB"`, `"Avg stress"`, `"Steps"`).
    - Today's formatted value + unit (`"—"` when missing).
    - Delta badge (`"▲ 8%"`, `"▼ 2"`, `"same"`, `"normal"`, or `"so far"` for today's steps), colored `#6EE7B7` (`BetterGreen`) when `better == true`, `#F87171` (`RecoveryRed`) when `better == false`, and `#9AA3AE` (`TextMuted`) when neutral/null.
- **Tap action:** Body opens **Today**.

---

### Widget 3: Live Body Battery & Stress (`BodyBatteryWidgetProvider`)
- **Sizing:** `4×2`.
- **Data:** Today's `bodyBatteryCurve(today)` (3-minute samples `0..100`), `DayEntity.bodyBatteryWake`, `DayEntity.bodyBatteryCharged`, and `DayEntity.stressAvg`.
- **Layout:**
  - Header: `"BODY BATTERY & STRESS"` + `↻` sync pill.
  - Top summary row:
    - Left: Large current Body Battery level (`curve.lastOrNull()?.second`, colored by Garmin band `#34D399` / `#A3D94C` / `#FBBF24` / `#F87171`, or `"—"`).
    - Right: Secondary metadata (`"Wake 88 · Charged +58 · Stress 24"`, omitting absent parts cleanly; `"—"` if none).
  - Bottom `#0E1013` chart box: `Canvas`-rendered daytime sparkline across `[00:00 .. 24:00]` in the current local `ZoneId`:
    - Plots the 3-minute curve from midnight up to the latest sample with a subtle translucent gradient fill beneath the line, a dot on the latest point, and a dashed horizontal guide across the remaining hours of the day, with `"12a · 6a · 12p · 6p"` (or `"00 · 06 · 12 · 18"` in 24h mode) axis labels.
    - When fewer than 2 samples exist for today, renders a centered `"No Body Battery curve yet today"` muted caption inside the chart box.
- **Tap action:** Body opens **Today**.

---

### Widget 4: Sleep & Tonight's Bedtime (`SleepWidgetProvider`)
- **Sizing:** `4×2`.
- **Data:** Reuses `SleepViewModel`'s calculations on `DayEntity` and `ScoreEntity` for `today`:
  - Last night's `sleepSeconds`, `sleepNeedMinutes`, `sleepPct` (`SleepPerformance`), `sleepScore` (Garmin score), and stage seconds (`deepSeconds`, `remSeconds`, `lightSeconds`, `awakeSeconds`).
  - Tonight's guidance from `nextSleepNeedBaselineMin`, `nextSleepNeedActualMin`, and `recommendedBedtimeMin`.
- **Layout:**
  - Header: `"SLEEP & TONIGHT'S NEED"` + `↻` sync pill.
  - Primary row:
    - Left: Asleep duration (`"7h 42m"`, or `"—"` when `sleepSeconds == null || sleepSeconds <= 0`) + `"/ 8h 20m need"` (`sleepPct` shown as `"92%"` in `#A78BFA`).
    - Right: `"Score 86"` (`#C4B5FD`) when Garmin sleep score is present.
  - Proportional stage bar (`Canvas` bitmap): rounded horizontal bar split proportionally into Deep (`#5B48C2`), REM (`#7DD3FC`), Light (`#A78BFA`), and Awake (`#FBBF24`) matching `SleepScreen.kt` stage colors, or an empty `#262A31` track when stage seconds are absent.
  - Bottom `#0E1013` footer:
    - Left: `"Tonight's need: 8h 05m"` (from `nextSleepNeedActualMin ?: nextSleepNeedBaselineMin`, or `"Tonight's need: —"`).
    - Right: `"Bedtime 10:15 PM"` (formatted via `Formatters.time` using 12/24h setting when `recommendedBedtimeMin` is present).
- **Tap action:** Body opens **Sleep detail for today** (`sleep/<today>`).

---

### Widget 5: Weekly Summary & Latest Activity (`WeeklyActivityWidgetProvider`)
- **Sizing:** `4×2`.
- **Data:**
  - Current week totals (`WeekSummary` from `ActivitiesViewModel`: activity count, total duration, total distance formatted in km or mi per `Repository.imperial`, starting on `Repository.firstDayOfWeek`).
  - Most recent activity across all history (`activities.maxByOrNull { it.startTimeLocal }`, not limited to the current week so Monday morning before a workout still shows your last session with its date!), formatted with `sportIcon(family)`, name (`prettyType` fallback), date/time + `Formatters.meta(...)`, and `activityScore.strain`. *(Designer's call: showing the latest activity even if it was late last week avoids an empty card every Monday morning while the header clearly scopes the totals to `"THIS WEEK · 0 ACTIVITIES"`.)*
  - Current week's 7 days (`weekStart .. weekStart.plusDays(6)`): day-of-week single-letter labels (`M T W T F S S` or `S M T W T F S` per `firstDayOfWeek`), each underlined/accented by that day's `ScoreEntity.recovery` zone color (`#34D399` / `#FBBF24` / `#F87171`, or `#262A31` when un-scored / future) and a small dot when the day has ≥ 1 activity.
- **Layout:**
  - Header: `"THIS WEEK · 4 ACTIVITIES"` + summary stats (`"5h 18m · 42.6 km"`) + `↻` sync pill.
  - Middle `#0E1013` workout card: sport icon badge (`#1E2A3A` rounded square with `#93C5FD` icon), title + metadata line, and strain pill (`"14.2"` in `#60A5FA`, or `"—"`). When no activities exist at all in the database, shows `"No activities synced yet"`.
  - Bottom 7-day strip (`Canvas` bitmap or 7-column `LinearLayout`): the 7 day cells with recovery bar + activity dot.
- **Tap action:**
  - Tapping the latest-activity card opens **that Activity's detail screen** (`activity/<id>`).
  - Tapping the week header or 7-day strip (or when there is no activity yet) opens the **Activities tab** (`activities`).

---

## 6. Architecture, Sync, and Update Lifecycle

### Package Layout
New package `dev.baseline.widget/`:
- `WidgetSnapshot.kt` — Pure data models and snapshot builder functions reading from `AppDatabase`, `UserPrefs`, `ApiKeyVault`, `WorkManager` running state, and `Clock`. Reuses `Baselines`, `Recovery`, `SleepPerformance`, `Formatters`, and shared helpers extracted from `TodayViewModel`, `SleepViewModel`, and `ActivitiesViewModel` (extracting internal pure functions where needed rather than duplicating formulas).
- `WidgetGraphics.kt` — Pure `android.graphics.Canvas` → `Bitmap` renderers for circular dials, the Body Battery sparkline, and the proportional sleep stage bar (taking explicit pixel/dp sizes and loaded `Typeface`s, with safe fallback to `Typeface.DEFAULT_BOLD` if font loading is unavailable in a test environment).
- `WidgetUpdater.kt` — Queries `AppWidgetManager` first: **if zero Baseline widgets are currently placed on any launcher, returns immediately without querying Room or rendering bitmaps.** Otherwise builds the snapshot once on `Dispatchers.Default` and updates every placed Baseline widget instance.
- `WidgetActionReceiver.kt` — Manifest `BroadcastReceiver` (non-exported) handling `ACTION_WIDGET_SYNC`: calls `goAsync()`, checks `ApiKeyVault.status`, and calls `container.scheduler.syncNow()` + triggers an immediate `WidgetUpdater.updateAll()` so the pill flips to `"Syncing…"`.
- Five `AppWidgetProvider` subclasses (`ReadinessWidgetProvider`, `VitalsWidgetProvider`, `BodyBatteryWidgetProvider`, `SleepWidgetProvider`, `WeeklyActivityWidgetProvider`) delegating `onUpdate` and `onAppWidgetOptionsChanged` (via `goAsync()`) to `WidgetUpdater`, plus `<appwidget-provider>` metadata XMLs in `res/xml/` and preview layouts/descriptions for the Android widget picker.

### When Widgets Update
1. **Sync lifecycle (`SyncWorker` / `AfterSync`):**
   - When `SyncWorker.doWork()` begins and when it finishes (after `afterSync` and `markSynced`), `WidgetUpdater.updateAll()` runs so widgets show `"Syncing…"` during a run and immediately display fresh scores, activities, and sync timestamp when it finishes.
2. **In-app preference & recalculation changes (`BaselineApp` background collector):**
   - A lightweight collector in `BaselineApp` observes `combine` of `userPrefs.baselineOrder`, `userPrefs.hiddenRows`, `repository.imperial`, `repository.firstDayOfWeek`, `apiKeyVault.status`, `historyRecalculator.running`, and `dateTicker.today` / `dateTicker.zone` (debounced / `distinctUntilChanged`), calling `WidgetUpdater.updateAll()`. Because `WidgetUpdater` short-circuits in microseconds when no widgets are placed, users with zero widgets pay zero Room/bitmap cost.
3. **System broadcasts & passive safety net:**
   - Each `<appwidget-provider>` sets `android:updatePeriodMillis="1800000"` (30 minutes, Android's minimum; deferred automatically by the OS while the device is asleep) **only to refresh cached Room/clock display**—`onUpdate` never triggers a network sync. This guarantees that if the app process died overnight while on `SyncInterval.MANUAL` or offline, crossing midnight still rolls widgets forward to the new `LocalDate` when the user wakes the phone.
   - `WidgetActionReceiver` (or widget providers) also listens for `Intent.ACTION_TIMEZONE_CHANGED`, `Intent.ACTION_TIME_CHANGED`, and `Intent.ACTION_MY_PACKAGE_REPLACED` to refresh `dateTicker` and re-render active widgets after travel, clock edits, or app updates.

### Deep-Link Navigation (`MainActivity` & `BaselineNavHost`)
- Widget `PendingIntent`s launch `MainActivity` (`android:launchMode="singleTop"` in `AndroidManifest.xml`, `PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE`, distinct `requestCode` or `Intent.data` URI per target so Android never collapses different widget `PendingIntent`s into one!).
- Intent carries a URI / extra specifying one of:
  - `today` → navigates to `Routes.TODAY` and resets `SELECTED_DATE` to `container.today.value`.
  - `sleep/today` → navigates to `Routes.TODAY` as root, then pushes `sleep/<today>` so Back returns cleanly to Today.
  - `activities` → navigates to `Routes.ACTIVITIES` (`popUpTo(Routes.TODAY)`, `launchSingleTop = true`, highlighting the Activities tab).
  - `activity/<id>` → navigates to `Routes.ACTIVITIES` (or `Routes.TODAY`) on the stack and pushes `activity/<id>` so Back stays inside Baseline.
  - `settings` → navigates to `Routes.SETTINGS` (`popUpTo(Routes.TODAY)`, `launchSingleTop = true`).
- Both cold starts (`MainActivity.onCreate`) and warm starts (`MainActivity.onNewIntent`) forward the deep-link target into `BaselineNavHost` via a consumed state/channel, ignored only when `KeyStatus` is `Missing` (which stays on `Routes.ONBOARDING`).

## 7. Testing, Lint, and Release Verification

- **Unit & Robolectric tests (`app/src/test/java/dev/baseline/widget/`):**
  - `WidgetSnapshotTest`: pure state tests with injected `Clock` across time zones—verifying morning pre-sync `"—"` behavior (no yesterday leak), `KeyStatus.Missing` / `Invalid` / `Expired`, unreadable day warning, custom row order & hidden rows in Vitals, 12h/24h bedtime & sync time formatting, metric/imperial weekly summary, and Monday-morning latest-activity fallback.
  - `WidgetGraphicsTest`: verifies dial, sparkline, and stage-bar bitmaps render non-empty, finite-dimension bitmaps across edge cases (null fractions, 0/1/500 curve points, zero stage totals) without throwing.
  - `WidgetUpdaterTest` (Robolectric): places widget instances via `Shadows.shadowOf(AppWidgetManager)`, runs `WidgetUpdater`, inflates the resulting `RemoteViews`, and asserts rendered text, visibility, content descriptions, `≥ 48dp` sync target sizing, distinct `PendingIntent` targets, and no-op short-circuit when zero widgets are installed.
  - `WidgetDeepLinkTest` (Compose / Robolectric nav test): verifies cold start and `onNewIntent` deep-links land on Today, Sleep detail, Activities, Activity detail, Settings, or Onboarding (when key is missing), and that Back navigation stays inside the app.
- **Release & R8:**
  - AAPT2 automatically generates `-keep` rules for `<receiver>` classes in `AndroidManifest.xml`; document this in `app/proguard-rules.pro` and smoke-test a minified release build against `tools/fake_garmin.py` on the emulator, adding all 5 widgets to the launcher and testing sync, resize, and deep-links.
- **Quality gates:** `./gradlew spotlessApply`, `./gradlew :app:lintDebug`, `./gradlew :app:testDebugUnitTest`, and PII grep checks before committing.

## 8. Left to the Builder

- Exact XML view IDs, `@drawable` shape resource names, and helper splitting between `TodayViewModel` / `SleepViewModel` / `ActivitiesViewModel` and `dev.baseline.widget`.
- Exact dp font sizes, padding, and bitmap pixel density clamp (keeping total `RemoteViews` bitmap payload well under 500 KB per widget update while staying crisp on 3.5× screens).
- Widget picker preview layout / description strings (`android:previewLayout` on API 31+ and `android:previewImage` / `android:description` metadata).
