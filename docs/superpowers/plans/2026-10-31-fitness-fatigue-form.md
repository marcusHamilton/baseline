# Fitness, Fatigue and Form Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a plain-language training-load state (Overreaching … Detraining) and a weekly strain budget on Today, and a fitness/fatigue chart with a state strip on Trends.

**Architecture:** A pure `scores/TrainingLoad` object turns the stored `ScoreEntity.dayLoad` values into daily fitness/fatigue/form and a week budget. `TodayViewModel` and `TrendsViewModel` read a wider score window (126 warm-up days) and call it; nothing is stored, so recalculations flow through on the next Room emission. Copy and colours live in one `ui/components/TrainingLoadCard.kt` shared by both screens.

**Tech Stack:** Kotlin 2.4, Jetpack Compose, Room 3 (read only), JUnit4 + Robolectric + Compose UI tests.

**Spec:** `docs/superpowers/specs/2026-10-31-fitness-fatigue-form-design.md` (read it first; this plan argues from it).

## Global Constraints

- No Room schema change, no migration, no new dependency, no R8 or baseline-profile change.
- Fitness constant 42 days, fatigue 7 days, warm-up `WARM_UP_DAYS = 126`, safe band `1.3` (130 %), hard day `tau × ln 3`.
- Form for day D uses `F(D−1)` and `A(D−1)`; `formPct = round((F − A) / F × 100)`.
- State bands: `< −30` Overreaching, `−30 to < −10` Productive, `−10 to ≤ +5` Maintaining, `> +5 to ≤ +20` Fresh, `> +20` Detraining.
- Calibrating while the earliest score date is later than `D − 42`; No recent training when `tau != null && F(D−1) < 0.01 × tau`.
- Colours: Overreaching `RecoveryRed`, Productive `RecoveryGreen`, Maintaining `TextMuted`, Fresh `StrainBlue`, Detraining `RecoveryAmber`, No recent training `RecoveryAmber`, Calibrating `TextMuted`; bar track `Surface2`, fill `StrainBlue`, over-band fill `RecoveryAmber`.
- Negative form is written with U+2212 ("−18%"), positive with "+", zero as "0%".
- Touch targets ≥ 48 dp; the state is always written out (colour never the only signal).
- ViewModels take `today` from `todayFlow` (`container.today`); never `LocalDate.now()` or `ZoneId.systemDefault()`.
- Tests that build databases use `testutil.inMemoryDatabase()`; no test depends on the host time zone.
- Before each commit: `./gradlew spotlessApply`. Before the last commit: `./gradlew :app:testDebugUnitTest :app:lintDebug spotlessCheck` all green. In a cloud container, read `docs/cloud-sessions.md` and run `tools/cloud_setup.sh` first.
- Conventional Commits; end each commit message with the session's attribution lines.

## Review Focus

Inputs the spec implies but its test list doesn't pin; each has a test in the named task.

1. **Zero fitness with no strain calibration** (fresh backfill, or 42 days of zero load before the first calibration): `F(D−1) == 0` must give No recent training, never a division by zero or `NaN%`. → Task 1.
2. **A non-finite or negative `dayLoad`** in a score row: counted as 0, so one bad row can't poison every later day's EWMA. → Task 1.
3. **Paging Today back to a date before the first score**: no Training load bar at all (not "Calibrating · 60 days to go"); Trends shows gaps, not a flat zero line, for those dates. → Tasks 3 and 5.
4. **A week that starts before there is any fitness** (`F(W−1) == 0`, e.g. the first week after calibrating ends during a layoff): no budget line, no `Infinity%`. → Task 2.
5. **Large font scale on a narrow phone**: the budget line wraps instead of being cut off ("…room for ~2 hard days" stays readable at font scale 2.0 on a 320 dp width). → Task 4.

---

### Task 1: `TrainingLoad.series` — fitness, fatigue, form and state

**Files:**
- Create: `app/src/main/java/dev/baseline/scores/TrainingLoad.kt`
- Test: `app/src/test/java/dev/baseline/scores/TrainingLoadTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class FormState { CALIBRATING, NO_RECENT_TRAINING, OVERREACHING, PRODUCTIVE, MAINTAINING, FRESH, DETRAINING }

  data class TrainingLoadDay(
      val date: LocalDate,
      val fitness: Double,          // F(date − 1)
      val fatigue: Double,          // A(date − 1)
      val formPct: Int?,            // null for CALIBRATING and NO_RECENT_TRAINING
      val state: FormState,
      val calibratingDaysLeft: Int?, // non-null only for CALIBRATING
  )

  object TrainingLoad {
      const val WARM_UP_DAYS = 126L
      fun stateOf(formPct: Int): FormState
      fun series(loads: Map<LocalDate, Double>, from: LocalDate, to: LocalDate, earliestScore: LocalDate?, tau: Double?): List<TrainingLoadDay>
  }
  ```
  Callers build `loads` as `scores.associate { it.date to it.dayLoad }`.

- [ ] **Step 1: Write the failing tests** in `TrainingLoadTest` (pure JUnit, no Robolectric; `val d0 = LocalDate.of(2026, 1, 1)`, a helper `flat(n, load)` that maps `d0 .. d0+n−1` to `load`):
  - `constantLoadConverges`: 400 days of load 100 → last day's `fitness` and `fatigue` within 0.01 of 100, `formPct == 0`, `state == MAINTAINING`.
  - `spikeDecaysByTheTimeConstants`: 200 days of 0 then one day of 420 on `s`; for date `s+1`: `fitness == 10.0` and `fatigue == 60.0` (±1e-9); for `s+2`: `fitness == 10.0 * (1 − 1/42.0)` and `fatigue == 60.0 * (1 − 1/7.0)`.
  - `formUsesYesterday`: same loads twice except today's load differs (0 vs 500) → today's `TrainingLoadDay` is identical in both.
  - `stateBoundaries`: `stateOf(-31) == OVERREACHING`, `stateOf(-30) == PRODUCTIVE`, `stateOf(-11) == PRODUCTIVE`, `stateOf(-10) == MAINTAINING`, `stateOf(5) == MAINTAINING`, `stateOf(6) == FRESH`, `stateOf(20) == FRESH`, `stateOf(21) == DETRAINING`.
  - `calibratingCountsDown`: `earliestScore = d0`; day `d0+41` → `CALIBRATING`, `calibratingDaysLeft == 1`, `formPct == null`; day `d0+42` → not `CALIBRATING`, `calibratingDaysLeft == null`.
  - `noEarliestScoreIsCalibrating`: empty `loads`, `earliestScore = null` → every day `CALIBRATING` with `calibratingDaysLeft == 42`.
  - `noRecentTrainingBelowOnePercentOfTau`: `earliestScore = d0`, 200 days of 100 then 300 days of 0, `tau = 100.0` → last day `NO_RECENT_TRAINING`, `formPct == null`; same loads with `tau = null` → a real state (DETRAINING) as long as `fitness > 0`.
  - `zeroFitnessWithoutTauIsNoRecentTraining` (Review Focus 1): 60 days of 0, `earliestScore = d0`, `tau = null` → last day `NO_RECENT_TRAINING`, `formPct == null`.
  - `missingDaysCountAsZero`: loads on alternate days only equal a map with explicit zeros on the other days (`series` results equal).
  - `badLoadsCountAsZero` (Review Focus 2): one day with `Double.NaN`, one with `-50.0`, one with `Double.POSITIVE_INFINITY` → same result as zeros on those days; every `fitness`/`fatigue` is finite.

- [ ] **Step 2: Run them and see them fail**
  Run: `./gradlew :app:testDebugUnitTest --tests 'dev.baseline.scores.TrainingLoadTest'`
  Expected: compilation failure (`TrainingLoad` unresolved).

- [ ] **Step 3: Implement `TrainingLoad.kt`**
  - `series` iterates day by day from `minOf(loads.keys.minOrNull() ?: from, from)` to `to`, holding `F` and `A` (both start at 0). For each date D it first emits the `TrainingLoadDay` from the current `F`/`A` (that is `F(D−1)`, `A(D−1)`) when `D in from..to`, then folds in `L(D)` (`loads[D]`, with null / non-finite / negative → 0).
  - State order: CALIBRATING (`earliestScore == null` → days left 42; `earliestScore > D − 42` → `earliestScore + 42 − D`), then NO_RECENT_TRAINING (`F <= 0`, or `tau != null && F < 0.01 * tau`), then `stateOf(formPct)`.
  - KDoc on the object cites spec §3; keep comment density like `Strain.kt`.

- [ ] **Step 4: Run the tests and see them pass** (same command). Expected: all green.

- [ ] **Step 5: Commit**
  ```bash
  ./gradlew spotlessApply
  git add app/src/main/java/dev/baseline/scores/TrainingLoad.kt app/src/test/java/dev/baseline/scores/TrainingLoadTest.kt
  git commit -m "feat(scores): fitness, fatigue and form from stored day loads"
  ```

### Task 2: `TrainingLoad.weekBudget`

**Files:**
- Modify: `app/src/main/java/dev/baseline/scores/TrainingLoad.kt`
- Test: `app/src/test/java/dev/baseline/scores/TrainingLoadTest.kt`

**Interfaces:**
- Consumes: Task 1's `series` (for `F(W−1)` and the state check).
- Produces:
  ```kotlin
  /** [daysLeft] = days after the date within its week (W + 6 − date); 0 on the week's last day. */
  data class WeekBudget(val weekStart: LocalDate, val pct: Int, val overBand: Boolean, val hardDaysLeft: Int?, val daysLeft: Int)

  // in object TrainingLoad
  fun weekBudget(loads: Map<LocalDate, Double>, date: LocalDate, firstDayOfWeek: DayOfWeek, earliestScore: LocalDate?, tau: Double?): WeekBudget?
  ```
  Callers pass loads from `date − 7 − WARM_UP_DAYS` to `date`. `WeekBudget.daysLeft` is an addition to the spec's §3.4 type; Today's copy (§4 "past weeks") needs it.

- [ ] **Step 1: Write the failing tests** (`2026-03-04` is a Wednesday):
  - `budgetTakesNormalFromTheDayBeforeTheWeek`: 300 days of 100 ending `2026-03-01` (Sunday), then Mon 200, Tue 0, Wed 100; Monday-start, `tau = 100.0` → `weekStart == 2026-03-02`, `pct == round(300 / (7 × F(03-01)) × 100)` where `F(03-01)` is taken from `series` for `2026-03-02`, `daysLeft == 4`.
  - `sundayStartWeek`: same loads with `DayOfWeek.SUNDAY` → `weekStart == 2026-03-01`, `daysLeft == 3`.
  - `todaysPartialLoadCounts`: adding 50 to Wednesday raises `pct` accordingly.
  - `hardDaysLeftIsCappedToDaysLeftAndZero`: low week load, `tau = 10.0` (tiny hard day) → `hardDaysLeft == daysLeft`; week load already at 129 % with `tau = 1000.0` → `hardDaysLeft == 0`, `overBand == false`.
  - `hardDayIsAStrain14Day`: pick loads so `1.3 × normal − weekLoad == 2.5 × tau × ln 3` with `daysLeft ≥ 3` → `hardDaysLeft == 2`.
  - `overBand`: week load 131 % → `overBand == true`, `hardDaysLeft == 0`.
  - `pastWeekLastDay`: the Sunday of a Monday-start week → `daysLeft == 0`, `hardDaysLeft == 0`.
  - `noCalibrationNoHardDays`: `tau = null` → `hardDaysLeft == null`, `pct` still set.
  - `noBudgetWhileCalibratingOrNoTraining`: `earliestScore` 10 days before `date` → `null`; NO_RECENT_TRAINING state → `null`.
  - `zeroNormalGivesNoBudget` (Review Focus 4): calibration period over, but `F(W−1) == 0` while `F(date−1) > 0` (all load since the week started), `tau = null` → `null`, never `Infinity`.

- [ ] **Step 2: Run them and see them fail** — `./gradlew :app:testDebugUnitTest --tests 'dev.baseline.scores.TrainingLoadTest'`. Expected: `weekBudget` unresolved.

- [ ] **Step 3: Implement `weekBudget`** per spec §3.4: `W = date.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))`; get `F(W−1)` as `series(loads, W, W, …).single().fitness`; return null when `date`'s state is CALIBRATING or NO_RECENT_TRAINING, or `normal <= 0`. `hardDaysLeft = floor((1.3 × normal − weekLoad) / (tau × ln 3)).coerceIn(0, daysLeft)`. Loads are sanitised the same way as in `series` (share one private helper).

- [ ] **Step 4: Run and pass** (same command).

- [ ] **Step 5: Commit** — `git commit -m "feat(scores): weekly strain budget from fitness"` (after `spotlessApply`; add both files).

### Task 3: Today state — `TrainingLoadUi` in `TodayViewModel`

**Files:**
- Modify: `app/src/main/java/dev/baseline/ui/today/TodayViewModel.kt`
- Test: `app/src/test/java/dev/baseline/ui/today/TodayViewModelTest.kt`

**Interfaces:**
- Consumes: `TrainingLoad.series`, `TrainingLoad.weekBudget`, `TrainingLoad.WARM_UP_DAYS`; `repo.scores(from, to)`, `repo.historyStart` (`.first` is the earliest health day, which is also the earliest score date), `repo.firstDayOfWeek`, `prefs.strainCalibration` (`?.tau`).
- Produces:
  ```kotlin
  data class TrainingLoadUi(val day: TrainingLoadDay, val budget: WeekBudget?, val expanded: Boolean)

  // TodayUiState gains (last parameter, default null so existing callers compile):
  val trainingLoad: TrainingLoadUi? = null

  // TodayViewModel:
  fun toggleTrainingLoad()

  internal fun buildTrainingLoadUi(
      date: LocalDate, scores: List<ScoreEntity>, earliestScore: LocalDate?, firstDayOfWeek: DayOfWeek, tau: Double?, expanded: Boolean,
  ): TrainingLoadUi?
  ```
  `buildTodayState` gains a trailing `trainingLoad: TrainingLoadUi? = null` parameter passed straight into the state.

- [ ] **Step 1: Write the failing tests** (pure calls to `buildTrainingLoadUi`, plus one ViewModel test in the file's existing style):
  - `trainingLoadForAProductiveDay`: 200 days of 100 then 10 days of 140 (the last on `date`), `earliestScore` = the first of them, Monday start, `tau = 100.0` → `state == PRODUCTIVE`, `budget != null`.
  - `noBarBeforeTheFirstScore` (Review Focus 3): `earliestScore = date.plusDays(5)` → `null`.
  - `calibratingHasNoBudget`: `earliestScore = date.minusDays(10)` → `day.state == CALIBRATING`, `calibratingDaysLeft == 32`, `budget == null`.
  - `pastDayUsesThatDatesWeek`: a past Wednesday → `budget.weekStart` is that week's Monday and `budget.daysLeft == 4`.
  - ViewModel: `toggleTrainingLoadFlipsExpanded` — with a seeded in-memory database (`testutil.inMemoryDatabase()`), `state.trainingLoad?.expanded` goes `false → true` after `toggleTrainingLoad()`; and changing the date with `prevDay()` changes `state.trainingLoad.day.date`.

- [ ] **Step 2: Run and fail** — `./gradlew :app:testDebugUnitTest --tests 'dev.baseline.ui.today.TodayViewModelTest'`.

- [ ] **Step 3: Implement**
  - `buildTrainingLoadUi` returns null when `earliestScore != null && date < earliestScore`; otherwise builds `loads` from `scores`, calls `series(loads, date, date, …).single()` and `weekBudget(...)`.
  - In `state`'s `flatMapLatest`, add a fourth input group:
    `combine(repo.scores(d.minusDays(7 + TrainingLoad.WARM_UP_DAYS), d), repo.historyStart.map { it.first }, repo.firstDayOfWeek, prefs.strainCalibration.map { it?.tau }, trainingLoadExpanded, ::LoadInputs)`
    with `private class LoadInputs(...)` next to the other input classes, then call `buildTrainingLoadUi` inside the outer combine and pass it to `buildTodayState`.
  - `trainingLoadExpanded` is a `MutableStateFlow(false)` like `bodyBatteryExpanded`.

- [ ] **Step 4: Run and pass**, then also run the whole `dev.baseline.ui.today.*` package to confirm existing Today tests are unchanged.

- [ ] **Step 5: Commit** — `git commit -m "feat(today): training-load state and week budget in Today's state"`.

### Task 4: Today UI — `TrainingLoadCard`

**Files:**
- Create: `app/src/main/java/dev/baseline/ui/components/TrainingLoadCard.kt`
- Modify: `app/src/main/java/dev/baseline/ui/today/TodayScreen.kt` (`TodayScreen` wiring and `TodayContent`, placed directly after the `BodyBatteryCard` call at line ~197 with the same modifier)
- Test: `app/src/test/java/dev/baseline/ui/today/TodayScreenTest.kt`, plus `app/src/test/java/dev/baseline/ui/components/TrainingLoadCopyTest.kt` for the pure copy

**Interfaces:**
- Consumes: `TrainingLoadUi`, `FormState`, `WeekBudget`.
- Produces (also used by Task 5):
  ```kotlin
  fun formStateLabel(s: FormState): String     // "Overreaching", "Productive", "Maintaining", "Fresh", "Detraining", "Calibrating", "No recent training"
  fun formStateColor(s: FormState): Color      // per Global Constraints
  fun formPctText(pct: Int): String            // "−18%", "+5%", "0%"
  fun formStateMeaning(s: FormState): String?  // spec §3.3 one-liners; null for CALIBRATING / NO_RECENT_TRAINING
  fun budgetLine(b: WeekBudget): String
  fun trainingLoadDescription(ui: TrainingLoadUi): String
  @Composable fun TrainingLoadCard(ui: TrainingLoadUi, onToggle: () -> Unit, modifier: Modifier = Modifier)
  ```
  `TodayContent` gains `onToggleTrainingLoad: () -> Unit = {}` as its last parameter; `TodayScreen` passes `vm::toggleTrainingLoad` by name.

- [ ] **Step 1: Write the failing copy tests** (`TrainingLoadCopyTest`, pure JUnit), exact strings from spec §4:
  - `budgetLine(WeekBudget(w, 62, false, 2, 4)) == "62% of a normal week · room for ~2 hard days"`; with `hardDaysLeft = 1` → "room for ~1 hard day".
  - `hardDaysLeft = 0`, not over → `"62% of a normal week · easy days from here"`.
  - over the band → `"141% of a normal week · past your safe range"`.
  - `daysLeft = 0` → `"104% of a normal week"`; `hardDaysLeft = null` → `"62% of a normal week"`.
  - `formPctText(-18) == "−18%"`, `formPctText(5) == "+5%"`, `formPctText(0) == "0%"`.
  - `trainingLoadDescription` for Productive −18 %, 62 %, 2 hard days, collapsed == `"Training load: Productive, form minus 18 percent. 62 percent of a normal week, room for about 2 hard days. Double-tap to expand."`; Calibrating 12 days == `"Training load: Calibrating, 12 days to go."`.

- [ ] **Step 2: Write the failing Compose tests** in `TodayScreenTest` (same `show(...)`/`rule.setContent` pattern as the Body Battery tests):
  - `trainingLoadCollapsedShowsStateAndBudget`: "Training load", "Productive" and the budget line are displayed; "Fitness" is not.
  - `trainingLoadExpandsOnTap`: click the node with the description → "Form −18%: You're building fitness at a pace you can absorb.", "Fitness 64 load · your 6-week average" and "Fatigue 76 load · your last-week average" appear.
  - `trainingLoadCalibrating`: "Calibrating · 12 days to go" displayed; no budget text.
  - `trainingLoadHiddenWhenNull`: `trainingLoad = null` → no "Training load" node.
  - `trainingLoadTouchTargetAndDescription`: the clickable node's height ≥ 48 dp and its content description equals `trainingLoadDescription(ui)`.
  - `budgetLineWrapsAtLargeFont` (Review Focus 5): `Density(density, fontScale = 2f)` in a 320 dp-wide box → the full budget text node is displayed and its `TextLayoutResult` has no ellipsis (`!hasVisualOverflow`).

- [ ] **Step 3: Run and fail** — `./gradlew :app:testDebugUnitTest --tests 'dev.baseline.ui.components.TrainingLoadCopyTest' --tests 'dev.baseline.ui.today.TodayScreenTest'`.

- [ ] **Step 4: Implement `TrainingLoadCard.kt`** following `BodyBatteryCard`: same container shape/colour/padding, `clickable(role = Role.Button)`, `heightIn(min = 48.dp)`, `semantics(mergeDescendants = true) { contentDescription = …; stateDescription = Expanded/Collapsed }`. Title left, state label right in `formStateColor`; budget `Text` with no `maxLines`; bar = `Surface2` track, fill to `min(pct, 130) / 130f` in `StrainBlue` (or `RecoveryAmber` when `overBand`), 1 dp tick at `100 / 130f`. Expanded lines use `formStateMeaning`, `fitness.roundToInt()`, `fatigue.roundToInt()`. Wire into `TodayContent` and `TodayScreen`.

- [ ] **Step 5: Run and pass** (same command), then the whole `dev.baseline.ui.today.*` package.

- [ ] **Step 6: Commit** — `git commit -m "feat(today): Training load bar under Body Battery"`.

### Task 5: Trends — Training load card

**Files:**
- Modify: `app/src/main/java/dev/baseline/ui/trends/TrendsViewModel.kt`, `TrendCharts.kt`, `TrendsScreen.kt`
- Test: `app/src/test/java/dev/baseline/ui/trends/TrendsViewModelTest.kt`, `TrendsScreenTest.kt`

**Interfaces:**
- Consumes: `TrainingLoad.series`, `WARM_UP_DAYS`; `formStateLabel`, `formStateColor`, `formPctText` from Task 4; `repo.historyStart`, `prefs.strainCalibration` — `TrendsViewModel` gains a `prefs: UserPrefs` constructor parameter (update both constructors and the call in `ui/nav/BaselineNavHost.kt`).
- Produces:
  ```kotlin
  // TrendsUiState gains (default so existing test builders compile):
  val trainingLoad: List<TrainingLoadDay?> = emptyList()   // one per range day; null before the earliest score

  internal fun buildTrendsState(range, today, days, scores, earliestScore: LocalDate? = null, tau: Double? = null): TrendsUiState
  internal fun TrendsUiState.trainingLoadHeader(): String       // "Productive · form −18%", "Calibrating", "—" when empty
  internal fun TrendsUiState.trainingLoadDescription(): String

  // TrendCharts.kt
  TrendLine(..., secondary: List<Int?> = emptyList(), secondaryColor: Color = Color.Unspecified)
  @Composable internal fun StateStrip(states: List<FormState?>, modifier: Modifier = Modifier)
  ```

- [ ] **Step 1: Write the failing ViewModel tests**:
  - `warmUpDoesNotLeakIntoExistingSeries`: scores for the range plus 126 days before it → `recovery.size == range.days` and its first date is `today − (days − 1)`; averages are unchanged versus passing only the range's scores.
  - `trainingLoadCoversTheRange`: `trainingLoad.size == range.days`, last entry's date == `today`.
  - `daysBeforeTheFirstScoreAreGaps` (Review Focus 3): `earliestScore = today − 10` on a 30-day range → the first 20 entries are `null`.
  - `trainingLoadHeaderAndDescription`: for a fixed Productive −18 % series rising from 52 to 64 (fitness) and 40 to 76 (fatigue) → header `"Productive · form −18%"`, description `"Training load over the last 30 days. Fitness rose from 52 to 64, fatigue from 40 to 76. Now Productive, form minus 18 percent."`. Direction words: "rose" / "fell" / "held at 64" when equal after rounding.
  - The existing `setRangeReWiresTheFlowAndWidensTheWindow` keeps passing with the read starting `WARM_UP_DAYS` earlier (adjust its expected window if it asserts the query bounds).

- [ ] **Step 2: Write the failing Compose tests** in `TrendsScreenTest`: the "TRAINING LOAD" heading and header text are displayed; the legend shows "Fitness" and "Fatigue"; the chart node's description equals `trainingLoadDescription()`; with `trainingLoad` empty the card shows the same no-data placeholder as other `ChartBox`es.

- [ ] **Step 3: Run and fail** — `./gradlew :app:testDebugUnitTest --tests 'dev.baseline.ui.trends.*'`.

- [ ] **Step 4: Implement**
  - ViewModel reads `repo.scores(from.minusDays(TrainingLoad.WARM_UP_DAYS), today)` and combines in `repo.historyStart.map { it.first }` and `prefs.strainCalibration.map { it?.tau }`. `buildTrendsState` filters scores to the range for the existing series and passes all of them to `series` for `trainingLoad`, nulling dates before `earliestScore`.
  - `TrendLine`: the secondary series draws first with a 1.25 dp stroke in `secondaryColor`, and both series share one `lo..hi` scale; with `secondary` empty the drawing is unchanged.
  - `StateStrip`: a 6 dp `Row` of equal-weight boxes, `formStateColor(state)` or `Surface2` for null / CALIBRATING / NO_RECENT_TRAINING, with `clearAndSetSemantics {}` (the chart's description covers it).
  - `TrendsScreen`: a `TrendCard("TRAINING LOAD", state.trainingLoadHeader())` after "SLEEP vs NEED", containing the `TrendLine` (fitness `StrainBlue` primary, fatigue `StrainBlueLight` secondary), the `StateStrip`, the existing axis labels and a `FlowRow` legend.

- [ ] **Step 5: Run and pass** (same command).

- [ ] **Step 6: Commit** — `git commit -m "feat(trends): Training load card with fitness, fatigue and a form-state strip"`.

### Task 6: Docs and final verification

**Files:**
- Modify: `docs/superpowers/specs/2026-10-24-baseline-android-design.md` (§9 Today and Trends rows)
- Modify: `CLAUDE.md` (Architecture, the `scores/` bullet)

- [ ] **Step 1: Update the main spec §9**: Today row — add "A **Training load** bar under the Body Battery bar (state and weekly budget; tap to expand; `2026-10-31-fitness-fatigue-form-design.md`)." Trends row — add "Training load: fitness and fatigue lines with a form-state strip."
- [ ] **Step 2: Update `CLAUDE.md`**: after the `ScorePipeline` sentence add "`scores/TrainingLoad` derives fitness, fatigue, form and the weekly budget from stored day loads at read time (nothing persisted)."
- [ ] **Step 3: Full verification**
  Run: `./gradlew spotlessApply && ./gradlew :app:testDebugUnitTest :app:lintDebug spotlessCheck`
  Expected: BUILD SUCCESSFUL, no lint findings.
  Then both PII greps from `CLAUDE.md` print nothing (the second only where `~/.config/baseline/pii-patterns` exists).
- [ ] **Step 4: On-device check (owner's Mac or any emulator; skip in a cloud container and say so in the handoff)**: with `tools/fake_garmin.py` and `-PgarminBaseUrl=http://127.0.0.1:8080`, after the backfill Today shows the bar under Body Battery, it expands on tap, ‹ back a week changes the week, and Trends shows the card on 7 / 30 / 90.
- [ ] **Step 5: Commit** — `git commit -m "docs: Training load in the main spec and CLAUDE.md"`.
