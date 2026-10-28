# Android Home-Screen Widgets Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add five dark-themed Android home-screen widgets (Daily Readiness & Strain in 2×2/4×2, Baseline Vitals, Live Body Battery & Stress, Sleep & Tonight's Bedtime, and Weekly Summary & Latest Activity) backed by cached Room/DataStore state, one-tap background sync, and deep-link navigation.

**Architecture:** Plain Android `AppWidgetProvider` + `RemoteViews` XML layouts with `<shape>` drawables for `#171A1F`/`#0E1013` cards and `android.graphics.Canvas` bitmaps (using bundled `BarlowCondensed`) for circular dials, the Body Battery sparkline, proportional bars, and the 7-day recovery strip. A single `WidgetUpdater` short-circuits when zero widgets are placed and otherwise pushes snapshots built from existing `Repository`/`TodayViewModel`/`SleepViewModel`/`ActivitiesViewModel` math whenever sync runs, preferences/scores change, or the date/timezone rolls over.

**Tech Stack:** Kotlin 2.4, Android SDK (`minSdk 29`, `targetSdk 37`), `AppWidgetProvider` / `RemoteViews` / `Canvas`, Room 3, DataStore, WorkManager, Navigation Compose, JUnit 4 + Robolectric 4.17.

**Spec:** `docs/superpowers/specs/2026-10-26-home-screen-widgets-design.md`

## Global Constraints

- Gradle environment: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` before running `./gradlew`.
- Zero new Gradle dependencies (`gradle/libs.versions.toml` and `app/build.gradle.kts` add no libraries).
- `SyncWorker` remains the sole caller of `GarminClient`; widget sync taps enqueue via `SyncScheduler.syncNow()`.
- Never include API keys, tokens, or home paths in code, intents, logs, or docs.
- Before morning sleep syncs for `today`, today's missing metrics render `"—"` (never yesterday's sleep/recovery labeled as today).
- Sync status pills show wall-clock sync time (`"↻ 7:41 AM"` / `"↻ 07:41"` today, `"↻ Oct 25"` on an earlier day), `"Syncing…"` while active, `"Key error"` on `Invalid`/`Expired` key, or `"↻ Sync"` before first sync—never relative `"12m ago"` strings.
- Interactive widget controls (the `↻` sync target) are at least `48dp × 48dp`, every `ImageView` sets a TalkBack `contentDescription` (or `@null` when decorative next to text), and color is paired with arrows/labels.
- All time/date logic and tests inject `Clock` / `ZoneId` and pass on any host time zone.
- Every task must keep `./gradlew spotlessApply`, `./gradlew :app:lintDebug` (`warningsAsErrors = true`, so no `UnusedResources`, `HardcodedText`, `RemoteViewLayout`, or `UnsafeProtectedBroadcastReceiver` warnings), and `./gradlew :app:testDebugUnitTest` green before committing.

## Review Focus

- Non-finite (`Double.NaN`, `Double.POSITIVE_INFINITY`) or negative Room numbers (`ScoreEntity.strain`, `DayEntity.sleepSeconds`, `ActivityEntity.distanceM`): must render `"—"` and `0f` dial/bar fill instead of `"NaN"` or throwing in `roundToInt()` (pinned in Tasks 1 & 2).
- `PendingIntent` target collapse via `Intent.filterEquals`: two `MainActivity` intents that differ only in extras overwrite each other under `FLAG_UPDATE_CURRENT`; every `WidgetTarget` must encode a distinct `Intent.data` URI (`baseline://widget/...`) so tapping Readiness, Sleep, Activity `<id>`, Activities, and Settings stay independent (pinned in Tasks 3 & 6).
- DST 23-hour and 25-hour local days on the Body Battery sparkline: x-coordinates must map `[today.atStartOfDay(zone).toInstant() .. today.plusDays(1).atStartOfDay(zone).toInstant()]` rather than assuming `86_400` seconds, keeping late-evening points aligned across DST transitions (pinned in Task 2).
- Zero or oversized widget bounds from third-party launchers (`OPTION_APPWIDGET_MAX_WIDTH == 0` or huge tablet dp values): `WidgetGraphics` must clamp dimensions to `[1 .. MAX_BITMAP_PX]` and fall back to default dp sizes when options report `<= 0` so `Bitmap.createBitmap` never throws and Binder payload stays small (pinned in Tasks 2 & 4).
- Deep-link replay on screen rotation / recreation: consuming a cold-start or `onNewIntent` `WidgetTarget` must clear it so rotating the device after navigating elsewhere does not yank the user back to the widget's route (pinned in Task 3).

---

## File Map

- Modify: `app/src/main/java/dev/baseline/ui/today/TodayViewModel.kt` — expose `internal fun statRows(...)` so widget vitals share identical 30-day baseline math.
- Create: `app/src/main/java/dev/baseline/widget/WidgetSnapshot.kt` — pure widget UI models, `buildWidgetSnapshot(...)`, and suspending `loadWidgetSnapshot(...)`.
- Create: `app/src/main/java/dev/baseline/widget/WidgetGraphics.kt` — clamped `Canvas` → `Bitmap` builders for dials, Body Battery sparkline, sleep stage bar, horizontal fill bar, and 7-day recovery/activity strip.
- Create: `app/src/main/java/dev/baseline/widget/WidgetRoutes.kt` — `WidgetTarget` sealed hierarchy and distinct `baseline://widget/...` URI `Intent` builder/parser.
- Modify: `app/src/main/java/dev/baseline/MainActivity.kt` — `onCreate` + `onNewIntent` `WidgetTarget` state forwarding.
- Modify: `app/src/main/java/dev/baseline/ui/nav/BaselineNavHost.kt` — consume `WidgetTarget` deep links while preserving `Routes.TODAY` back-stack root and `KeyStatus.Missing` onboarding guard.
- Create: `app/src/main/java/dev/baseline/widget/WidgetActionReceiver.kt` — non-exported broadcast receiver for `ACTION_WIDGET_SYNC` and time/timezone/package-update refreshes.
- Create: `app/src/main/java/dev/baseline/widget/WidgetUpdater.kt` — zero-widget fast-path check, `RemoteViews` builders for all 5 widgets (including responsive 2×2/4×2 Readiness), and `AppWidgetManager` push logic.
- Create: `app/src/main/java/dev/baseline/widget/ReadinessWidgetProvider.kt`, `VitalsWidgetProvider.kt`, `BodyBatteryWidgetProvider.kt`, `SleepWidgetProvider.kt`, `WeeklyActivityWidgetProvider.kt` — `AppWidgetProvider` entrypoints.
- Create: `app/src/main/res/values/strings.xml`, `app/src/main/res/values/dimens.xml`, `app/src/main/res/values-v31/dimens.xml`, `app/src/main/res/drawable/widget_*.xml`, `app/src/main/res/layout/widget_*.xml`, `app/src/main/res/xml/widget_*_info.xml` — widget shapes, layouts, and picker metadata.
- Modify: `app/src/main/AndroidManifest.xml` — `MainActivity` `singleTop`, `WidgetActionReceiver`, and the 5 widget `<receiver>` declarations.
- Modify: `app/src/main/java/dev/baseline/AppContainer.kt`, `app/src/main/java/dev/baseline/sync/SyncWorker.kt`, `app/src/main/java/dev/baseline/BaselineApp.kt`, `app/proguard-rules.pro`, `app/src/main/baseline-prof.txt` — wire `WidgetUpdater` into sync start/finish, preference/date collectors, R8 docs, and baseline profile.
- Tests: `app/src/test/java/dev/baseline/widget/WidgetSnapshotTest.kt`, `WidgetGraphicsTest.kt`, `WidgetDeepLinkTest.kt`, `ReadinessWidgetTest.kt`, `VitalsAndBodyBatteryWidgetTest.kt`, `SleepAndWeeklyWidgetTest.kt`, `WidgetLifecycleTest.kt`.

---

### Task 1: Pure Widget Snapshot Models and Builder

**Files:**
- Modify: `app/src/main/java/dev/baseline/ui/today/TodayViewModel.kt:378-386`
- Create: `app/src/main/java/dev/baseline/widget/WidgetSnapshot.kt`
- Test: `app/src/test/java/dev/baseline/widget/WidgetSnapshotTest.kt`

**Interfaces:**
- Consumes: `DayEntity`, `ScoreEntity`, `ActivityEntity`, `KeyStatus`, `RecoveryZone`, `SportFamily`, `Baselines.at`, `Recovery.compute`, `SleepPerformance.of`, `Formatters`, `statRows` (promoted to `internal` in `TodayViewModel.kt`), `prettyType` (`TodayViewModel.kt`).
- Produces in `dev.baseline.widget`:
  - `sealed interface SyncPillUi { data object Syncing : SyncPillUi; data object KeyError : SyncPillUi; data class Ready(val label: String) : SyncPillUi }`
  - `data class ReadinessModel(val headerDate: String, val recoveryPct: Int?, val recoveryZone: RecoveryZone?, val recoveryPartial: Boolean, val recoveryReason: String?, val strain: Double?, val strainText: String, val strainTargetText: String?, val sleepPct: Int?, val bodyBattery: Int?, val unreadable: Boolean)`
  - `data class VitalTileModel(val id: String, val shortLabel: String, val valueText: String, val unit: String, val deltaText: String?, val better: Boolean?)`
  - `data class VitalsModel(val statusSummary: String?, val statusWarn: Boolean, val tiles: List<VitalTileModel>, val allHidden: Boolean, val unreadable: Boolean)`
  - `data class BodyBatteryModel(val current: Int?, val metaLine: String, val curve: List<Pair<Instant, Int>>, val axisLabels: List<String>, val unreadable: Boolean)`
  - `data class SleepWidgetModel(val asleepText: String, val needText: String?, val sleepPctText: String?, val garminScoreText: String?, val deepSec: Int?, val remSec: Int?, val lightSec: Int?, val awakeSec: Int?, val tonightNeedText: String, val bedtimeText: String?, val unreadable: Boolean)`
  - `data class WeekDayCell(val letter: String, val recoveryZone: RecoveryZone?, val hasActivity: Boolean)`
  - `data class LatestActivityUi(val id: Long, val title: String, val family: SportFamily, val metaLine: String, val strainText: String)`
  - `data class WeeklyActivityModel(val headerTitle: String, val weekTotalsText: String, val latest: LatestActivityUi?, val days: List<WeekDayCell>)`
  - `data class WidgetSnapshot(val today: LocalDate, val zone: ZoneId, val keyMissing: Boolean, val syncPill: SyncPillUi, val readiness: ReadinessModel, val vitals: VitalsModel, val bodyBattery: BodyBatteryModel, val sleep: SleepWidgetModel, val weekly: WeeklyActivityModel)`
  - `fun buildWidgetSnapshot(today: LocalDate, zone: ZoneId, is24h: Boolean, keyStatus: KeyStatus, syncing: Boolean, lastSynced: Instant?, day: DayEntity?, score: ScoreEntity?, history: List<DayEntity>, unreadable: Boolean, bodyBatteryCurve: List<Pair<Instant, Int>>, order: List<String>, hidden: Set<String>, imperial: Boolean, firstDayOfWeek: DayOfWeek, recentActivities: List<ActivityEntity>, activityStrains: Map<Long, Double>, activeSets: Map<Long, Int>, weekScores: Map<LocalDate, ScoreEntity>): WidgetSnapshot`
  - `suspend fun loadWidgetSnapshot(container: AppContainer, is24h: Boolean, forceSyncing: Boolean = false): WidgetSnapshot`

- [ ] **Step 1: Write the failing tests in `app/src/test/java/dev/baseline/widget/WidgetSnapshotTest.kt`**

```kotlin
@Test fun morningBeforeSyncDoesNotLeakYesterday() {
    val snap = sampleSnapshot(today = LocalDate.of(2026, 10, 26), day = null, score = null, history = scoredHistory(14))
    assertNull(snap.readiness.recoveryPct)
    assertNull(snap.readiness.sleepPct)
    assertEquals("—", snap.readiness.strainText)
    assertEquals("—", snap.sleep.asleepText)
}

@Test fun syncPillStatesAndWallClockZones() {
    val zone = ZoneId.of("America/Los_Angeles")
    val today = LocalDate.of(2026, 10, 26)
    val morning = ZonedDateTime.of(today, LocalTime.of(7, 41), zone).toInstant()
    val yesterday = ZonedDateTime.of(today.minusDays(1), LocalTime.of(21, 5), zone).toInstant()
    assertEquals(SyncPillUi.Syncing, sampleSnapshot(syncing = true, lastSynced = morning).syncPill)
    assertEquals(SyncPillUi.KeyError, sampleSnapshot(keyStatus = KeyStatus.Invalid, syncing = true).syncPill)
    assertEquals(SyncPillUi.Ready("↻ Sync"), sampleSnapshot(lastSynced = null).syncPill)
    assertEquals(SyncPillUi.Ready("↻ 7:41 AM"), sampleSnapshot(zone = zone, is24h = false, lastSynced = morning).syncPill)
    assertEquals(SyncPillUi.Ready("↻ 07:41"), sampleSnapshot(zone = zone, is24h = true, lastSynced = morning).syncPill)
    assertEquals(SyncPillUi.Ready("↻ Oct 25"), sampleSnapshot(zone = zone, is24h = false, lastSynced = yesterday).syncPill)
}

@Test fun vitalsRespectsOrderAndHiddenWithoutResurfacing() {
    val hiddenFive = setOf("hrv", "stress", "sleep", "resp", "bb")
    val snap = sampleSnapshot(order = listOf("steps", "rhr", "hrv"), hidden = hiddenFive)
    assertEquals(listOf("steps", "rhr"), snap.vitals.tiles.map { it.id })
    assertFalse(snap.vitals.allHidden)
    val allHidden = sampleSnapshot(hidden = DEFAULT_BASELINE_ORDER.toSet())
    assertTrue(allHidden.vitals.allHidden)
    assertTrue(allHidden.vitals.tiles.isEmpty())
}

@Test fun sleepAndWeeklyAndNonFiniteEdgeCases() {
    // Also assert: sleep "7h 42m", "/ 8h 20m need", "Score 86", "Tonight's need: 8h 5m", "Bedtime 10:15 PM" / "Bedtime 22:15";
    // Monday 2026-10-26 with 0 activities this week + Sunday 2026-10-25 trail run -> "THIS WEEK · 0 ACTIVITIES" + Sunday latest card + metric/imperial;
    // Double.NaN strain, negative sleepSeconds, Double.POSITIVE_INFINITY distanceM -> "—"; unreadable = true propagates to readiness/vitals/bodyBattery/sleep.
}
```

- [ ] **Step 2: Run the unit test to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests 'dev.baseline.widget.WidgetSnapshotTest'`
Expected: FAIL to compile (`Unresolved reference: buildWidgetSnapshot`).

- [ ] **Step 3: Expose `internal fun statRows` in `TodayViewModel.kt` and implement `WidgetSnapshot.kt`**

- Change `private fun statRows(` on `TodayViewModel.kt:378` to `internal fun statRows(`.
- Implement `buildWidgetSnapshot` and `loadWidgetSnapshot` in `app/src/main/java/dev/baseline/widget/WidgetSnapshot.kt`:
  - Short labels: `"hrv" -> "HRV"`, `"rhr" -> "Resting HR"`, `"sleep" -> "Sleep"`, `"resp" -> "Respiration"`, `"bb" -> "Wake BB"`, `"stress" -> "Avg stress"`, `"steps" -> "Steps"`.
  - Vitals `statusSummary`: count visible tiles with non-dash values; if `worseCount > 0`, `"$worseCount worse"` (`statusWarn = true`), else if `measuredCount > 0`, `"$measuredCount in range"` (`statusWarn = false`), else `null`.
  - `ReadinessModel.headerDate`: `"TODAY · " + today.format(DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)).uppercase(Locale.US)`.
  - `SleepWidgetModel.tonightNeedText`: `"Tonight's need: " + (day?.let { it.nextSleepNeedActualMin ?: it.nextSleepNeedBaselineMin }?.takeIf { m -> m > 0 }?.let { Formatters.hoursMinutes(Duration.ofMinutes(it.toLong())) } ?: "—")`.

- [ ] **Step 4: Run tests, lint, and formatting**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew spotlessApply :app:testDebugUnitTest --tests 'dev.baseline.widget.WidgetSnapshotTest' :app:lintDebug`
Expected: PASS with 0 warnings/errors.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/baseline/ui/today/TodayViewModel.kt app/src/main/java/dev/baseline/widget/WidgetSnapshot.kt app/src/test/java/dev/baseline/widget/WidgetSnapshotTest.kt
git commit -m "feat(widget): add pure widget snapshot models and data loader"
```

---

### Task 2: Canvas Bitmap Renderers (`WidgetGraphics`)

**Files:**
- Create: `app/src/main/java/dev/baseline/widget/WidgetGraphics.kt`
- Test: `app/src/test/java/dev/baseline/widget/WidgetGraphicsTest.kt`

**Interfaces:**
- Consumes: `RecoveryZone`, `WeekDayCell` (`Task 1`), `R.font.barlow_condensed_bold`.
- Produces in `dev.baseline.widget.WidgetGraphics`:
  - ARGB constants: `BG = 0xFF0E1013.toInt()`, `SURFACE_2 = 0xFF262A31.toInt()`, `TEXT_PRIMARY = 0xFFF2F4F7.toInt()`, `TEXT_MUTED = 0xFF9AA3AE.toInt()`, `TEXT_BODY = 0xFFC9CFD6.toInt()`, `RECOVERY_GREEN = 0xFF34D399.toInt()`, `RECOVERY_AMBER = 0xFFFBBF24.toInt()`, `RECOVERY_RED = 0xFFF87171.toInt()`, `STRAIN_BLUE = 0xFF60A5FA.toInt()`, `SLEEP_PURPLE = 0xFFA78BFA.toInt()`, `SLEEP_PURPLE_LIGHT = 0xFFC4B5FD.toInt()`, `BETTER_GREEN = 0xFF6EE7B7.toInt()`, `STAGE_DEEP = 0xFF5B48C2.toInt()`, `STAGE_REM = 0xFF7DD3FC.toInt()`, `STAGE_LIGHT = 0xFFA78BFA.toInt()`, `STAGE_AWAKE = 0xFFFBBF24.toInt()`, `MAX_BITMAP_PX = 900`.
  - `fun recoveryArgb(zone: RecoveryZone?): Int`
  - `fun bodyBatteryArgb(level: Int?): Int`
  - `fun dpToClampedPx(dp: Float, density: Float, maxPx: Int = MAX_BITMAP_PX): Int`
  - `fun loadCondensedBold(context: Context): Typeface`
  - `fun renderDialBitmap(fraction: Float?, value: String, suffix: String, note: String?, accentArgb: Int, sizePx: Int, strokePx: Float, typeface: Typeface = Typeface.DEFAULT_BOLD): Bitmap`
  - `fun renderHorizontalFillBar(fraction: Float?, accentArgb: Int, widthPx: Int, heightPx: Int): Bitmap`
  - `fun renderSleepStageBar(deepSec: Int?, remSec: Int?, lightSec: Int?, awakeSec: Int?, widthPx: Int, heightPx: Int): Bitmap`
  - `fun renderBodyBatterySparkline(curve: List<Pair<Instant, Int>>, accentArgb: Int, today: LocalDate, zone: ZoneId, widthPx: Int, heightPx: Int): Bitmap`
  - `fun renderWeekStripBitmap(days: List<WeekDayCell>, widthPx: Int, heightPx: Int, typeface: Typeface = Typeface.DEFAULT_BOLD): Bitmap`

- [ ] **Step 1: Write the failing Robolectric tests in `app/src/test/java/dev/baseline/widget/WidgetGraphicsTest.kt`**

```kotlin
@RunWith(RobolectricTestRunner::class)
class WidgetGraphicsTest {
    @Test fun dialHandlesValidNullAndNaNFractions() {
        val full = WidgetGraphics.renderDialBitmap(0.75f, "74", "%", null, WidgetGraphics.RECOVERY_GREEN, 160, 14f)
        assertEquals(160, full.width)
        val empty = WidgetGraphics.renderDialBitmap(Float.NaN, "—", "", null, WidgetGraphics.STRAIN_BLUE, 160, 14f)
        assertEquals(160, empty.width)
    }

    @Test fun sparklineUsesActualZoneDayBoundsAcrossDstSpringForward() {
        val zone = ZoneId.of("America/Los_Angeles")
        val dstDay = LocalDate.of(2026, 3, 8) // 23-hour day
        val pts = listOf(
            ZonedDateTime.of(dstDay, LocalTime.of(6, 0), zone).toInstant() to 85,
            ZonedDateTime.of(dstDay, LocalTime.of(23, 30), zone).toInstant() to 40,
        )
        val bmp = WidgetGraphics.renderBodyBatterySparkline(pts, WidgetGraphics.RECOVERY_GREEN, dstDay, zone, 300, 80)
        // Assert accent dot pixel is drawn past x = 285 (22.5h / 23h = 0.978 * 300 = 293px).
        assertTrue((286 until 300).any { x -> (0 until 80).any { y -> bmp.getPixel(x, y) != 0 } })
    }

    @Test fun stageBarWeekStripAndDimensionClamping() {
        val stage = WidgetGraphics.renderSleepStageBar(3600, 3600, 7200, 0, 200, 16)
        assertEquals(WidgetGraphics.STAGE_DEEP, stage.getPixel(20, 8))
        assertEquals(WidgetGraphics.STAGE_LIGHT, stage.getPixel(150, 8))
        val clampedZero = WidgetGraphics.renderHorizontalFillBar(0.5f, WidgetGraphics.RECOVERY_GREEN, 0, -5)
        assertTrue(clampedZero.width >= 1 && clampedZero.height >= 1)
        val clampedHuge = WidgetGraphics.renderWeekStripBitmap(List(7) { WeekDayCell("M", RecoveryZone.GREEN, true) }, 10_000, 5_000)
        assertEquals(WidgetGraphics.MAX_BITMAP_PX, clampedHuge.width)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests 'dev.baseline.widget.WidgetGraphicsTest'`
Expected: FAIL to compile (`Unresolved reference: WidgetGraphics`).

- [ ] **Step 3: Implement `WidgetGraphics.kt`**

- Clamp all pixel dimensions via `.coerceIn(1, MAX_BITMAP_PX)`.
- `renderDialBitmap`: `Paint.Cap.ROUND` background `SURFACE_2` ring + clockwise `-90f` `accentArgb` arc + centered `value`/`suffix` + optional `note`.
- `renderBodyBatterySparkline`: `dayStart = today.atStartOfDay(zone).toInstant().epochSecond`, `dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().epochSecond`, gradient fill (`35%` alpha to `0%`), stroke path, dot at latest point, dashed `SURFACE_2` remainder line.
- `renderWeekStripBitmap`: 7 equal columns separated by small gaps, each `#0E1013` rounded cell with centered `letter`, bottom `recoveryArgb(cell.recoveryZone)` bar, and small `STRAIN_BLUE` dot when `cell.hasActivity`.

- [ ] **Step 4: Run tests, lint, and formatting**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew spotlessApply :app:testDebugUnitTest --tests 'dev.baseline.widget.WidgetGraphicsTest' :app:lintDebug`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/baseline/widget/WidgetGraphics.kt app/src/test/java/dev/baseline/widget/WidgetGraphicsTest.kt
git commit -m "feat(widget): add clamped Canvas bitmap renderers for dials and charts"
```

---

### Task 3: Widget Deep-Link Routes (`WidgetRoutes`, `MainActivity`, `BaselineNavHost`)

**Files:**
- Create: `app/src/main/java/dev/baseline/widget/WidgetRoutes.kt`
- Modify: `app/src/main/AndroidManifest.xml:24-31`
- Modify: `app/src/main/java/dev/baseline/MainActivity.kt`
- Modify: `app/src/main/java/dev/baseline/ui/nav/BaselineNavHost.kt:142-237`
- Test: `app/src/test/java/dev/baseline/widget/WidgetDeepLinkTest.kt`

**Interfaces:**
- Produces in `dev.baseline.widget`:
  - `sealed interface WidgetTarget { data object Today : WidgetTarget; data object SleepToday : WidgetTarget; data object Activities : WidgetTarget; data class Activity(val id: Long) : WidgetTarget; data object Settings : WidgetTarget }`
  - `object WidgetRoutes { fun intent(context: Context, target: WidgetTarget): Intent; fun pendingIntent(context: Context, target: WidgetTarget): PendingIntent; fun parse(intent: Intent?): WidgetTarget? }`
- Modifies `BaselineNavHost`:
  - `fun BaselineNavHost(container: AppContainer, deepLink: StateFlow<WidgetTarget?> = MutableStateFlow(null), onDeepLinkConsumed: () -> Unit = {})`

- [ ] **Step 1: Write the failing tests in `app/src/test/java/dev/baseline/widget/WidgetDeepLinkTest.kt`**

```kotlin
@Test fun intentsForDifferentTargetsAreNotFilterEqualAndRoundTrip() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val targets = listOf(WidgetTarget.Today, WidgetTarget.SleepToday, WidgetTarget.Activities, WidgetTarget.Activity(1L), WidgetTarget.Activity(2L), WidgetTarget.Settings)
    for (i in targets.indices) {
        assertEquals(targets[i], WidgetRoutes.parse(WidgetRoutes.intent(ctx, targets[i])))
        for (j in i + 1 until targets.size) {
            assertFalse(WidgetRoutes.intent(ctx, targets[i]).filterEquals(WidgetRoutes.intent(ctx, targets[j])))
        }
    }
    assertNull(WidgetRoutes.parse(Intent(Intent.ACTION_MAIN)))
}

// Plus Compose nav tests (following NavShellTest.kt):
// - deepLinksNavigateAndPreserveBackStack (SleepToday -> Sleep screen -> Back -> Today; Activity(42L) -> Activity screen; Today resets viewed date to today; Settings -> Settings)
// - missingKeyStaysOnOnboarding (KeyStatus.Missing ignores SleepToday and renders Onboarding)
// - consumedDeepLinkDoesNotReplayOnRecomposition (once onDeepLinkConsumed clears flow, switching to Trends stays on Trends)
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests 'dev.baseline.widget.WidgetDeepLinkTest'`
Expected: FAIL to compile (`Unresolved reference: WidgetRoutes`).

- [ ] **Step 3: Implement `WidgetRoutes.kt`, `MainActivity.kt`, `BaselineNavHost.kt`, and `AndroidManifest.xml`**

- `WidgetRoutes`: explicit `MainActivity` component + `Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP` + distinct `Uri.parse("baseline://widget/...")` (`today`, `sleep/today`, `activities`, `activity/$id`, `settings`). `pendingIntent` uses `PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE`.
- `AndroidManifest.xml`: add `android:launchMode="singleTop"` to `.MainActivity`.
- `MainActivity`: holds `private val deepLink = MutableStateFlow<WidgetTarget?>(null)`, sets from `intent` in `onCreate` only when `savedInstanceState == null`, updates in `onNewIntent`, clears via `onDeepLinkConsumed = { deepLink.value = null }`.
- `BaselineNavHost`: `LaunchedEffect(target, start)` consumes `target` when `start != null`; if `start != Routes.ONBOARDING`, routes to `Today` (setting `SELECTED_DATE` to `container.today.value.toString()`), `SleepToday` (`popUpTo(Routes.TODAY)` then `navigate("sleep/${container.today.value}")`), `Activities`, `Activity(id)` (`navigate(Routes.ACTIVITIES)` then `navigate("activity/$id")`), or `Settings`.

- [ ] **Step 4: Run tests, lint, and formatting**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew spotlessApply :app:testDebugUnitTest --tests 'dev.baseline.widget.WidgetDeepLinkTest' --tests 'dev.baseline.ui.nav.NavShellTest' :app:lintDebug`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/dev/baseline/MainActivity.kt app/src/main/java/dev/baseline/ui/nav/BaselineNavHost.kt app/src/main/java/dev/baseline/widget/WidgetRoutes.kt app/src/test/java/dev/baseline/widget/WidgetDeepLinkTest.kt
git commit -m "feat(widget): add URI-distinct widget deep links and nav routing"
```

---

### Task 4: Shared Widget Infrastructure, `WidgetActionReceiver`, and Widget 1 (Daily Readiness & Strain 2×2 / 4×2)

**Files:**
- Create: `app/src/main/res/values/strings.xml`, `app/src/main/res/values/dimens.xml`, `app/src/main/res/values-v31/dimens.xml`
- Create: `app/src/main/res/drawable/widget_bg_card.xml`, `widget_bg_inner.xml`, `widget_bg_pill.xml`, `widget_bg_pill_error.xml`
- Create: `app/src/main/res/layout/widget_missing_key.xml`, `widget_readiness_compact.xml`, `widget_readiness_expanded.xml`
- Create: `app/src/main/res/xml/widget_readiness_info.xml`
- Create: `app/src/main/java/dev/baseline/widget/WidgetActionReceiver.kt`
- Create: `app/src/main/java/dev/baseline/widget/WidgetUpdater.kt`
- Create: `app/src/main/java/dev/baseline/widget/ReadinessWidgetProvider.kt`
- Modify: `app/src/main/java/dev/baseline/AppContainer.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/dev/baseline/widget/ReadinessWidgetTest.kt`

**Interfaces:**
- Consumes: `WidgetSnapshot`, `loadWidgetSnapshot`, `WidgetGraphics`, `WidgetRoutes`, `WidgetTarget`.
- Produces in `dev.baseline.widget`:
  - `class WidgetActionReceiver : BroadcastReceiver()` with `companion object { const val ACTION_WIDGET_SYNC = "dev.baseline.action.WIDGET_SYNC"; fun syncPendingIntent(context: Context): PendingIntent }`
  - `open class WidgetUpdater(private val context: Context, private val container: () -> AppContainer)`:
    - `open suspend fun updateAll(forceSyncing: Boolean = false)`
    - `fun hasAnyWidgets(manager: AppWidgetManager = AppWidgetManager.getInstance(context)): Boolean`
    - `internal fun buildReadinessViews(snapshot: WidgetSnapshot, compact: Boolean, options: Bundle = Bundle.EMPTY): RemoteViews`
  - `AppContainer`: `open val widgetUpdater by lazy { WidgetUpdater(appContext) { this } }`
  - `class ReadinessWidgetProvider : AppWidgetProvider()`

- [ ] **Step 1: Write the failing Robolectric tests in `app/src/test/java/dev/baseline/widget/ReadinessWidgetTest.kt`**

```kotlin
@RunWith(RobolectricTestRunner::class)
class ReadinessWidgetTest {
    @Test fun shortCircuitsWhenNoWidgetsArePlaced() = runTest {
        assertFalse(container.widgetUpdater.hasAnyWidgets())
        container.widgetUpdater.updateAll() // returns immediately without touching DB
    }

    @Test fun expandedAndCompactReadinessRenderDialsTargetsAnd48dpSyncHitbox() {
        val parent = FrameLayout(ctx)
        val expanded = container.widgetUpdater.buildReadinessViews(sampleReadySnapshot(), compact = false).apply(ctx, parent)
        assertEquals("TODAY · MON OCT 26", expanded.findViewById<TextView>(R.id.widget_header_title).text.toString())
        assertEquals("Recovery 74%", expanded.findViewById<ImageView>(R.id.dial_recovery).contentDescription.toString())
        val syncHit = expanded.findViewById<View>(R.id.widget_sync_hitbox)
        val minPx = (48 * ctx.resources.displayMetrics.density).toInt()
        assertTrue(syncHit.minimumWidth >= minPx && syncHit.minimumHeight >= minPx)

        val compact = container.widgetUpdater.buildReadinessViews(sampleReadySnapshot(), compact = true).apply(ctx, parent)
        assertEquals("74%", compact.findViewById<TextView>(R.id.compact_recovery_value).text.toString())
        assertEquals("11.4", compact.findViewById<TextView>(R.id.compact_strain_value).text.toString())
    }

    // Plus tests for:
    // - missingKeyRendersSetupPromptAndKeyErrorPillLinksToSettings
    // - zeroSizeOptionsBundleFallsBackWithoutCrashing
    // - actionWidgetSyncEnqueuesSyncNowWhenKeyPresent
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests 'dev.baseline.widget.ReadinessWidgetTest'`
Expected: FAIL to compile (`Unresolved reference: ReadinessWidgetProvider`).

- [ ] **Step 3: Create shared XML resources, `WidgetActionReceiver`, `WidgetUpdater`, and `ReadinessWidgetProvider`**

- Only define `@string` / `@drawable` / `@dimen` resources referenced in Task 4 so `lintDebug` (`UnusedResources`) stays clean.
- `dimens.xml`: `widget_outer_radius = 24dp`, `widget_inner_radius = 12dp`; `values-v31/dimens.xml`: `@android:dimen/system_app_widget_background_radius` and `@android:dimen/system_app_widget_inner_radius`.
- On `Build.VERSION.SDK_INT >= 31`, `WidgetUpdater` wraps Readiness in `RemoteViews(mapOf(SizeF(140f, 110f) to compactViews, SizeF(250f, 110f) to expandedViews))`; on API 29–30 it selects `options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250) < 250`.
- `WidgetActionReceiver`: `android:exported="false"` in `AndroidManifest.xml`, branches on `intent.action` (`ACTION_WIDGET_SYNC`, `ACTION_TIMEZONE_CHANGED`, `ACTION_TIME_CHANGED`, `ACTION_MY_PACKAGE_REPLACED`), uses `goAsync()` to refresh `dateTicker`, enqueue `syncNow()` (when `ACTION_WIDGET_SYNC` and key not missing), and call `widgetUpdater.updateAll(...)`.
- `ReadinessWidgetProvider`: `android:exported="true"`, `@xml/widget_readiness_info` (`updatePeriodMillis="1800000"`, `minWidth="250dp"`, `minHeight="110dp"`, `minResizeWidth="110dp"`, `minResizeHeight="110dp"`, `resizeMode="horizontal|vertical"`, `targetCellWidth="4"`, `targetCellHeight="2"`).

- [ ] **Step 4: Run tests, lint, and formatting**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew spotlessApply :app:testDebugUnitTest --tests 'dev.baseline.widget.ReadinessWidgetTest' :app:lintDebug`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/dev/baseline/AppContainer.kt app/src/main/java/dev/baseline/widget/ app/src/main/res/ app/src/test/java/dev/baseline/widget/ReadinessWidgetTest.kt
git commit -m "feat(widget): add widget updater, sync receiver, and readiness 2x2/4x2 widget"
```

---

### Task 5: Widgets 2 & 3 — Baseline Vitals and Live Body Battery & Stress

**Files:**
- Create: `app/src/main/res/layout/widget_vitals.xml`, `app/src/main/res/xml/widget_vitals_info.xml`, `app/src/main/java/dev/baseline/widget/VitalsWidgetProvider.kt`
- Create: `app/src/main/res/layout/widget_body_battery.xml`, `app/src/main/res/xml/widget_body_battery_info.xml`, `app/src/main/java/dev/baseline/widget/BodyBatteryWidgetProvider.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/dev/baseline/widget/WidgetUpdater.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/dev/baseline/widget/VitalsAndBodyBatteryWidgetTest.kt`

**Interfaces:**
- Consumes: `VitalsModel`, `BodyBatteryModel`, `WidgetGraphics.renderBodyBatterySparkline`, `WidgetGraphics.bodyBatteryArgb`, `WidgetRoutes`.
- Produces in `WidgetUpdater`:
  - `internal fun buildVitalsViews(snapshot: WidgetSnapshot): RemoteViews`
  - `internal fun buildBodyBatteryViews(snapshot: WidgetSnapshot, options: Bundle = Bundle.EMPTY): RemoteViews`
  - Registers `VitalsWidgetProvider` and `BodyBatteryWidgetProvider` in `WidgetUpdater` and `AndroidManifest.xml`.

- [ ] **Step 1: Write the failing Robolectric tests in `app/src/test/java/dev/baseline/widget/VitalsAndBodyBatteryWidgetTest.kt`**

```kotlin
@RunWith(RobolectricTestRunner::class)
class VitalsAndBodyBatteryWidgetTest {
    @Test fun vitalsWidgetRendersTilesDeltasHiddenFallbackAndUnreadableWarning() {
        val view = updater.buildVitalsViews(sampleReadySnapshot()).apply(ctx, FrameLayout(ctx))
        assertEquals("HRV", view.findViewById<TextView>(R.id.vital_label_1).text.toString())
        assertEquals("▲ 8%", view.findViewById<TextView>(R.id.vital_delta_1).text.toString())
        // Also assert: 2-tile snapshot hides tiles 3 & 4; allHidden shows "All baseline metrics hidden in Today"; unreadable shows "Couldn't read today's data".
    }

    @Test fun bodyBatteryWidgetRendersLevelMetaAndSparklineOrEmptyMessage() {
        val withCurve = updater.buildBodyBatteryViews(sampleReadySnapshot()).apply(ctx, FrameLayout(ctx))
        assertEquals("64", withCurve.findViewById<TextView>(R.id.bb_current_value).text.toString())
        assertEquals(View.VISIBLE, withCurve.findViewById<ImageView>(R.id.bb_sparkline).visibility)

        val emptyCurve = updater.buildBodyBatteryViews(sampleEmptyCurveSnapshot()).apply(ctx, FrameLayout(ctx))
        assertEquals("No Body Battery curve yet today", emptyCurve.findViewById<TextView>(R.id.bb_empty_note).text.toString())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests 'dev.baseline.widget.VitalsAndBodyBatteryWidgetTest'`
Expected: FAIL to compile (`Unresolved reference: buildVitalsViews`).

- [ ] **Step 3: Implement XML layouts, providers, and `WidgetUpdater` builders for Vitals and Body Battery**

- `widget_vitals.xml`: `"VS. 30-DAY BASELINE"` header + status summary + `48dp` sync button, 2×2 `LinearLayout` grid of 4 `#0E1013` tiles, all-hidden / unreadable text views, body click → `WidgetTarget.Today`.
- `widget_body_battery.xml`: `"BODY BATTERY & STRESS"` header + `48dp` sync button, current level + `"Wake · Charged · Stress"` row, `#0E1013` sparkline box with 4 axis `TextView`s + empty-curve caption, body click → `WidgetTarget.Today`.
- Register both `<receiver android:exported="true">` entries in `AndroidManifest.xml` (`4×2`, `updatePeriodMillis="1800000"`).

- [ ] **Step 4: Run tests, lint, and formatting**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew spotlessApply :app:testDebugUnitTest --tests 'dev.baseline.widget.*' :app:lintDebug`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/dev/baseline/widget/ app/src/main/res/ app/src/test/java/dev/baseline/widget/VitalsAndBodyBatteryWidgetTest.kt
git commit -m "feat(widget): add baseline vitals and live body battery widgets"
```

---

### Task 6: Widgets 4 & 5 — Sleep & Tonight's Bedtime and Weekly Summary & Latest Activity

**Files:**
- Create: `app/src/main/res/drawable/widget_bg_sport_icon.xml`, `widget_bg_strain_pill.xml`
- Create: `app/src/main/res/layout/widget_sleep.xml`, `app/src/main/res/xml/widget_sleep_info.xml`, `app/src/main/java/dev/baseline/widget/SleepWidgetProvider.kt`
- Create: `app/src/main/res/layout/widget_weekly_activity.xml`, `app/src/main/res/xml/widget_weekly_activity_info.xml`, `app/src/main/java/dev/baseline/widget/WeeklyActivityWidgetProvider.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/dev/baseline/widget/WidgetUpdater.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/dev/baseline/widget/SleepAndWeeklyWidgetTest.kt`

**Interfaces:**
- Consumes: `SleepWidgetModel`, `WeeklyActivityModel`, `sportIcon` (`dev.baseline.ui.components.sportIcon`), `WidgetGraphics.renderSleepStageBar`, `WidgetGraphics.renderWeekStripBitmap`, `WidgetRoutes`.
- Produces in `WidgetUpdater`:
  - `internal fun buildSleepViews(snapshot: WidgetSnapshot, options: Bundle = Bundle.EMPTY): RemoteViews`
  - `internal fun buildWeeklyActivityViews(snapshot: WidgetSnapshot, options: Bundle = Bundle.EMPTY): RemoteViews`
  - Registers all 5 widget providers in `WidgetUpdater` and `AndroidManifest.xml`.

- [ ] **Step 1: Write the failing Robolectric tests in `app/src/test/java/dev/baseline/widget/SleepAndWeeklyWidgetTest.kt`**

```kotlin
@RunWith(RobolectricTestRunner::class)
class SleepAndWeeklyWidgetTest {
    @Test fun sleepWidgetRendersHoursStagesTonightNeedBedtimeAndDeepLinksToSleepToday() {
        val view = updater.buildSleepViews(sampleReadySnapshot()).apply(ctx, FrameLayout(ctx))
        assertEquals("7h 42m", view.findViewById<TextView>(R.id.sleep_asleep_value).text.toString())
        assertEquals("Tonight's need: 8h 5m", view.findViewById<TextView>(R.id.sleep_tonight_need).text.toString())
        assertEquals("Bedtime 10:15 PM", view.findViewById<TextView>(R.id.sleep_bedtime).text.toString())
    }

    @Test fun weeklyActivityWidgetRoutesWorkoutCardToActivityDetailAndRootToActivities() {
        val view = updater.buildWeeklyActivityViews(sampleReadySnapshot(activityId = 99L)).apply(ctx, FrameLayout(ctx))
        assertEquals("THIS WEEK · 4 ACTIVITIES", view.findViewById<TextView>(R.id.weekly_header_title).text.toString())
        assertEquals("Morning Trail Run", view.findViewById<TextView>(R.id.weekly_activity_title).text.toString())
        // Click workout card -> shadowOf(application).nextStartedActivity.data == Uri.parse("baseline://widget/activity/99")
        // Click week header/strip -> shadowOf(application).nextStartedActivity.data == Uri.parse("baseline://widget/activities")
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests 'dev.baseline.widget.SleepAndWeeklyWidgetTest'`
Expected: FAIL to compile (`Unresolved reference: buildSleepViews`).

- [ ] **Step 3: Implement Sleep and Weekly Activity layouts, providers, and `WidgetUpdater` builders**

- `widget_sleep.xml`: `"SLEEP & TONIGHT'S NEED"` header + `48dp` sync button, asleep hours + `"/ <need>"` + `"Score <n>"`, `ImageView` stage bar (`renderSleepStageBar`), `#0E1013` tonight footer (`Tonight's need: ...` + `Bedtime ...`), body click → `WidgetTarget.SleepToday`.
- `widget_weekly_activity.xml`: `"THIS WEEK · N ACTIVITIES"` + totals + `48dp` sync button, `#0E1013` workout card (`sportIcon(latest.family)` tinted `#93C5FD` inside `#1E2A3A` `widget_bg_sport_icon`, title + meta, `widget_bg_strain_pill` strain text; or `"No activities synced yet"`), `ImageView` 7-day strip (`renderWeekStripBitmap`). Workout card click → `WidgetTarget.Activity(latest.id)`; root/strip click → `WidgetTarget.Activities`.
- Register `SleepWidgetProvider` and `WeeklyActivityWidgetProvider` (`android:exported="true"`) in `AndroidManifest.xml`.

- [ ] **Step 4: Run tests, lint, and formatting**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew spotlessApply :app:testDebugUnitTest --tests 'dev.baseline.widget.*' :app:lintDebug`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/dev/baseline/widget/ app/src/main/res/ app/src/test/java/dev/baseline/widget/SleepAndWeeklyWidgetTest.kt
git commit -m "feat(widget): add sleep bedtime and weekly activity widgets"
```

---

### Task 7: Live Sync & Preference Hooks, ProGuard/Baseline Profile, and Emulator Release Verification

**Files:**
- Modify: `app/src/main/java/dev/baseline/sync/SyncWorker.kt:30-95`
- Modify: `app/src/main/java/dev/baseline/AppContainer.kt:116-125`
- Modify: `app/src/main/java/dev/baseline/BaselineApp.kt:46-60`
- Modify: `app/proguard-rules.pro`
- Modify: `app/src/main/baseline-prof.txt`
- Test: `app/src/test/java/dev/baseline/widget/WidgetLifecycleTest.kt`

**Interfaces:**
- Modifies `SyncWorkerDeps`: adds trailing default parameter `val updateWidgets: suspend (Boolean) -> Unit = {}` (`true` when sync starts inside `syncMutex`, `false` once `afterSync` and `markSynced` complete or when returning `RATE_LIMITED`/`OFFLINE`, wrapped in `try/catch` like `markSynced()` so widget errors never fail a sync).
- Modifies `AppContainer.syncWorkerDeps`: passes `updateWidgets = { syncing -> widgetUpdater.updateAll(forceSyncing = syncing) }`.
- Modifies `BaselineApp.onCreate`: launches a background collector on `combine` of `userPrefs.baselineOrder`, `userPrefs.hiddenRows`, `repository.imperial`, `repository.firstDayOfWeek`, `apiKeyVault.status`, `historyRecalculator.running`, `dateTicker.today`, and `dateTicker.zone` (`distinctUntilChanged`) calling `container.widgetUpdater.updateAll()`.

- [ ] **Step 1: Write the failing lifecycle integration tests in `app/src/test/java/dev/baseline/widget/WidgetLifecycleTest.kt`**

```kotlin
@RunWith(RobolectricTestRunner::class)
class WidgetLifecycleTest {
    @Test fun syncWorkerNotifiesWidgetsAtStartAndAfterCompletionAndSwallowsWidgetErrors() = runTest {
        val calls = mutableListOf<Boolean>()
        // Run SyncWorker with updateWidgets = { calls += it } -> asserts calls == listOf(true, false)
        // Run SyncWorker with updateWidgets = { error("launcher dead") } -> asserts doWork() == ListenableWorker.Result.success()
    }

    @Test fun placedWidgetsRefreshOnRowReorderAndMidnightRollover() = runTest {
        // Register VitalsWidgetProvider & ReadinessWidgetProvider widget IDs in ShadowAppWidgetManager,
        // reorder baseline rows & advance clock + dateTicker.refresh(), call widgetUpdater.updateAll(),
        // and assert ShadowAppWidgetManager's updated RemoteViews reflect the new first tile and new date header.
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:testDebugUnitTest --tests 'dev.baseline.widget.WidgetLifecycleTest'`
Expected: FAIL to compile (`No parameter with name 'updateWidgets'` on `SyncWorkerDeps`).

- [ ] **Step 3: Wire `SyncWorker`, `AppContainer`, `BaselineApp`, `proguard-rules.pro`, and `baseline-prof.txt`**

- `SyncWorker`: add `private suspend fun notifyWidgets(syncing: Boolean)` catching `Exception` (re-throwing `CancellationException`) and logging via `Log.w(TAG, ...)`. Call `notifyWidgets(true)` inside `syncMutex.withLock` before running tasks, and `notifyWidgets(false)` after `markSynced()` (and on `RATE_LIMITED`/`OFFLINE` branches so `"Syncing…"` always clears).
- `BaselineApp`: launch a collector in `onCreate` observing `combine(listOf(container.userPrefs.baselineOrder, container.userPrefs.hiddenRows, container.repository.imperial, container.repository.firstDayOfWeek, container.apiKeyVault.status, container.historyRecalculator.running, container.dateTicker.today, container.dateTicker.zone)) { it.toList() }.distinctUntilChanged()` and calling `container.widgetUpdater.updateAll()`.
- `app/proguard-rules.pro`: add comment documenting that AAPT2 generates `-keep` rules for the 5 `AppWidgetProvider`s and `WidgetActionReceiver` in `AndroidManifest.xml`.
- `app/src/main/baseline-prof.txt`: add `HPLdev/baseline/widget/**;->**(**)**` and `Ldev/baseline/widget/**;`.

- [ ] **Step 4: Run full unit suite, lint, formatting, PII checks, and Pixel 10 emulator verification**

Run:
1. `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew spotlessApply :app:lintDebug :app:testDebugUnitTest`
2. Run both PII `git grep` checks listed under "No personal data in the repo" in `CLAUDE.md` / `AGENTS.md` (both must print nothing).
3. Emulator smoke test on Pixel 10 (`tools/fake_garmin.py` + `adb reverse tcp:8080 tcp:8080`, key `fake-garmin-key`, `-PgarminBaseUrl=http://127.0.0.1:8080`):
   - Verify debug install + all 5 widgets placed on launcher, 2×2 ↔ 4×2 resize on Readiness, `↻` sync pill, and deep links.
   - Verify `./gradlew :app:assembleRelease -PgarminBaseUrl=http://127.0.0.1:8080` succeeds with R8 + `isShrinkResources = true` and `compileReleaseArtProfile`.

- [ ] **Step 5: Commit**

```bash
git add app/proguard-rules.pro app/src/main/baseline-prof.txt app/src/main/java/dev/baseline/AppContainer.kt app/src/main/java/dev/baseline/BaselineApp.kt app/src/main/java/dev/baseline/sync/SyncWorker.kt app/src/test/java/dev/baseline/widget/WidgetLifecycleTest.kt
git commit -m "feat(widget): wire sync and preference widget updates and update R8 docs"
```
