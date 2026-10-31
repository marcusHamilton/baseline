# Baseline — Fitness, Fatigue and Form: design spec

Date: 2026-10-31
Status: draft for review
Extends: §7 (Scores) and §9 (Screens: Today, Trends) of `docs/superpowers/specs/2026-10-24-baseline-android-design.md`

## 1. Intent

Baseline tells you how recovered you are today and how much strain to aim for today. It says nothing about the
weeks: whether the last fortnight has been building fitness, digging a hole, or letting fitness slip. Training-load
tools (TrainingPeaks' Performance Management Chart, intervals.icu, Garmin's Acute Load, Athlytic) answer that with
fitness, fatigue and form.

This feature is a **guardrail**, not a training planner:

- Today answers in words: *Overreaching*, *Productive*, *Maintaining*, *Fresh* or *Detraining*, plus how much of a
  normal week you have done and how many hard days are left before you overreach.
- Numbers (fitness, fatigue, form %) are there, but secondary.
- Trends shows how the state has moved over 7 / 30 / 90 days.

Success: on any day the user can see, without interpreting a number, whether the recent load is sustainable, and
roughly how much more training this week fits.

### Decisions made with the owner

- Purpose: guardrail (plain-language state first, numbers second).
- Weekly budget reads as "% of a normal week · room for ~N hard days", with a safe band up to 130 %.

### Non-goals

- Notifications or alerts about the state.
- Per-sport load, race planning, future-load projection, or a "target form on race day".
- Any change to Recovery, Strain, the strain target or calibration (§7.2–7.4 are unchanged).
- A home-screen widget for it (can come later on top of `TrainingLoad`).

## 2. Inputs

The only input is `ScoreEntity.dayLoad`: the day load `L` from §7.2 (activity TRIMP or scaled Garmin load, plus
non-activity load). It is already stored for every day with a `DayEntity`, in the user's own load units, and is
rewritten by every recompute, recalibration and "Recalculate history".

Also used:

- `StrainCalibration.tau` (`UserPrefs.strainCalibration`): to size a "hard day" and to guard against near-zero
  fitness. When there is no calibration yet, the hard-day count and the near-zero guard (§3.3) are skipped.
- `Repository.firstDayOfWeek`: the Garmin profile's week start, as Activities and Calendar use.
- The earliest score date: for the calibrating state.

**Missing days.** A date with no `ScoreEntity` row inside the read window counts as load 0 (a rest day). Before the
earliest score date nothing is counted (see §3.3).

## 3. Model (`scores/TrainingLoad.kt`)

A pure Kotlin object with no Android or Room dependencies, unit-tested on its own.

### 3.1 Fitness and fatigue

Exponentially weighted averages of day load, the standard Banister/Coggan constants:

- Fitness (chronic load): `F(d) = F(d−1) + (L(d) − F(d−1)) / 42`
- Fatigue (acute load): `A(d) = A(d−1) + (L(d) − A(d−1)) / 7`
- Both start at 0 on the first day of the read window.

**Warm-up.** Callers read loads from `126` days (3 × 42) before the first date they display. Starting at 0 that far
back leaves about 5 % of the starting error in fitness and none to speak of in fatigue. This is a constant
`WARM_UP_DAYS = 126` in `TrainingLoad`.

### 3.2 Form

Form for day D is taken from the values **through D − 1**, so it describes the readiness going into the day and does
not drift as today's load arrives:

- `form(D) = F(D−1) − A(D−1)`
- `formPct(D) = form(D) / F(D−1) × 100`, rounded to a whole percent.

Form is shown as a percentage of fitness because raw load units mean nothing outside Baseline (they depend on the
Banister set and the user's HR zones). Fitness and fatigue themselves are shown rounded to whole numbers, labelled
"load", in the expanded views only.

### 3.3 State

| formPct | State | Colour | One-line meaning (expanded Today card) |
|---|---|---|---|
| < −30 | Overreaching | `RecoveryRed` | Fatigue is well above your fitness. Back off for a few days. |
| −30 to < −10 | Productive | `RecoveryGreen` | You're building fitness at a pace you can absorb. |
| −10 to ≤ +5 | Maintaining | `TextMuted` | Load matches your fitness. |
| > +5 to ≤ +20 | Fresh | `StrainBlue` | You're rested. A good time for a hard session or a race. |
| > +20 | Detraining | `RecoveryAmber` | You've trained well under your norm for a while; fitness is slipping. |

The bands follow intervals.icu's form zones. As a check, −30 % form is an acute:chronic ratio of about 1.43, near the
top of the commonly cited 0.8–1.3 "sweet spot".

Two states come before the table:

1. **Calibrating** when the earliest score date is later than `D − 42`. Text: "Calibrating · N days to go", with
   `N = earliest + 42 − D` in days. No percentage, no colour (`TextMuted`).
2. **No recent training** when a calibration exists and `F(D−1) < 0.01 × tau`. This stops form % blowing up after a
   long break. No percentage, `RecoveryAmber`.

The result type:

```kotlin
enum class FormState { CALIBRATING, NO_RECENT_TRAINING, OVERREACHING, PRODUCTIVE, MAINTAINING, FRESH, DETRAINING }

data class TrainingLoadDay(
    val date: LocalDate,
    val fitness: Double,      // F(D−1)
    val fatigue: Double,      // A(D−1)
    val formPct: Int?,        // null for CALIBRATING and NO_RECENT_TRAINING
    val state: FormState,
    val calibratingDaysLeft: Int?,
)
```

`TrainingLoad.series(loads: Map<LocalDate, Double>, from: LocalDate, to: LocalDate, earliestScore: LocalDate?, tau: Double?)`
returns one `TrainingLoadDay` per date in `from..to`, computing the EWMAs from the first date in `loads` (the caller
passes the warm-up window).

### 3.4 Weekly budget

For a day D in the week starting `W = D.with(previousOrSame(firstDayOfWeek))`:

- `normal = 7 × F(W − 1)`: a normal week at the fitness you had when the week started, so the target does not move
  mid-week.
- `weekLoad = Σ L(d)` for `d` in `W..D`, today's partial load included.
- `pct = weekLoad / normal × 100`, rounded.
- Safe band top: `1.3 × normal` (130 %).
- Hard-day load: `tau × ln 3`, the load of a strain-14 day (the bottom of the green strain target, §7.2:
  `21 × (1 − e^(−L/τ)) = 14`).
- `hardDaysLeft = floor((1.3 × normal − weekLoad) / (tau × ln 3))`, clamped to `0..daysLeft`, where `daysLeft` is
  the days after D in the week (`W + 6 − D`). On past weeks `daysLeft` is 0.

```kotlin
data class WeekBudget(val weekStart: LocalDate, val pct: Int, val overBand: Boolean, val hardDaysLeft: Int?, val daysLeft: Int)
```

- `overBand` is `weekLoad > 1.3 × normal`.
- `hardDaysLeft` is null when there is no calibration.
- No budget (`null`) while the state is Calibrating or No recent training, or when `normal` is 0.

`TrainingLoad.weekBudget(loads, date, firstDayOfWeek, tau)` computes it; it needs loads from `W − 1 − WARM_UP_DAYS`
to D.

## 4. Today

A **Training load** bar under the Body Battery bar, built like it (full width, collapsed by default, tap to expand;
the expanded state is held in `TodayViewModel` like `toggleBodyBattery`).

**Collapsed** (one row plus a bar):

- Left: the title "Training load". Right: the state label in its colour, e.g. "Productive".
- Under it, the budget line: "62% of a normal week · room for ~2 hard days". Variants:
  - `hardDaysLeft == 0` and not over the band: "62% of a normal week · easy days from here".
  - Over the band: "141% of a normal week · past your safe range", in `RecoveryAmber`.
  - Past weeks (`daysLeft == 0`): just "104% of a normal week".
  - No calibration: just the percentage.
- The bar: a `Surface2` track from 0 to 130 %, filled in `StrainBlue` to `min(pct, 130)`, with a 1 dp tick at 100 %.
  Over the band the fill is `RecoveryAmber`.
- Calibrating: "Calibrating · 12 days to go" instead of the state, and no budget line or bar.
- No recent training: the state text only.

**Expanded** adds three lines:

- "Form −18%: You're building fitness at a pace you can absorb." (the §3.3 one-liner)
- "Fitness 64 load · your 6-week average"
- "Fatigue 76 load · your last-week average"

**Past days.** Today's ‹ › navigation shows the state as of that date and that date's week budget (with `daysLeft`
from that date, so a past Wednesday still says how many hard days were left then). Future days aren't reachable.

**Accessibility.**

- The bar is one TalkBack node: "Training load: Productive, form minus 18 percent. 62 percent of a normal week, room
  for about 2 hard days." The expand action and state come from its click label ("Show training load details") and
  Expanded/Collapsed state description, as on the Body Battery bar.
- The whole bar is the click target and is at least 48 dp tall.
- The state is always written out, so colour is never the only signal.

**Placement and edit mode.** The bar is fixed under Body Battery and is not one of the reorderable baseline rows (it
has no 30-day-baseline delta, which is what those rows show). `DEFAULT_BASELINE_ORDER` is unchanged.

**ViewModel.** `TodayViewModel` reads `repo.scores(date − 7 − WARM_UP_DAYS, date)` alongside the existing flows and
builds a `TrainingLoadUi` (state, formPct, fitness, fatigue, budget) in `buildTodayState` through `TrainingLoad`. The
extra rows are a few hundred `ScoreEntity`s; no new DAO query is needed.

## 5. Trends

A **Training load** card after the existing charts, following the current card style.

- **Header:** the latest state and form, e.g. "Productive · form −18%"; "Calibrating" when that applies.
- **Chart:** fitness as a 2 dp `StrainBlue` line and fatigue as a thinner `StrainBlueLight` line on one scale,
  covering the range (7 / 30 / 90 days). `TrendLine` gains an optional second series
  (`secondary: List<Int?> = emptyList()`, `secondaryColor`) drawn under the primary; existing callers are unchanged.
- **State strip:** under the chart, a 6 dp strip with one segment per day coloured by that day's state (Calibrating
  and No recent training in `Surface2`).
- **Axis:** the same start-date / "Today" labels as the other charts.
- **Legend:** "Fitness" and "Fatigue" with their colours, in a `FlowRow` like the Calendar legend.
- **TalkBack:** "Training load over the last 30 days. Fitness rose from 52 to 64, fatigue from 40 to 76. Now
  Productive, form minus 18 percent."

`TrendsViewModel` reads `repo.scores(from − WARM_UP_DAYS, today)` instead of `repo.scores(from, today)`, and
`buildTrendsState` filters the existing score-based series back to the range. `TrendsUiState` gains
`trainingLoad: List<TrainingLoadDay>`.

## 6. Error handling and edge cases

| Case | Behaviour |
|---|---|
| Fewer than 42 days of scores (first sync, partial backfill) | Calibrating with days to go; no budget; Trends strip in `Surface2`. |
| Gap days with no score row | Load 0. A watch left off for a week reads as rest, which is what the user did as far as Baseline knows. |
| History recalculated or strain recalibrated | Loads are re-read from `ScoreEntity`; nothing is cached, so the state follows on the next emission. |
| Banister set changed | Same as above. Load units change, but form % and the budget are ratios, so the state barely moves. |
| No strain calibration | No hard-day count and no near-zero guard; everything else works. |
| Time-zone or midnight change | Screens take `container.today` / `dateTicker`, as everywhere else; no `LocalDate.now()` in the ViewModels. |
| Week start changes in the Garmin profile | Picked up from `repo.firstDayOfWeek` on its next emission. |

## 7. Testing

All under `app/src/test`, Robolectric where Compose is involved. Times come from an injected `Clock`; nothing depends
on the host time zone. Databases come from `testutil.inMemoryDatabase()`.

`TrainingLoadTest` (pure JVM):

- EWMAs against hand-computed values: a constant load converges to it, a single spike decays by `(1 − 1/42)` and
  `(1 − 1/7)` per day.
- Form uses D − 1: today's load changes today's fatigue but not today's form.
- Every state boundary (−30, −10, +5, +20) on both sides.
- Calibrating: 41 days of history → 1 day to go; 42 → a real state.
- No recent training: fitness below `0.01 × tau`, and no guard when tau is null.
- Missing days count as zero.
- Week budget: Monday- and Sunday-start weeks; `normal` taken from the day before the week; today's partial load
  included; `hardDaysLeft` clamped to the days left and to 0; over the band; past week has `daysLeft == 0`.

`TodayViewModelTest` / `TodayScreenTest`:

- Collapsed and expanded text for each variant in §4, including Calibrating and over the band.
- Past-day navigation shows that date's state and week.
- The TalkBack description, and the bar's touch target ≥ 48 dp.

`TrendsViewModelTest` / `TrendsScreenTest`:

- The warm-up read doesn't leak into the existing series (Recovery etc. still cover only the range).
- Header, legend and TalkBack text for a 30-day range.
- `TrendLine` with and without the secondary series draws the same primary path.

## 8. Spec and doc updates in the same change

- Main spec §9: add the Training load bar to Today's row and the Training load card to Trends' row; reference this
  spec.
- `CLAUDE.md` Architecture: mention `scores/TrainingLoad` next to `ScorePipeline`.
- No Room schema change, no migration, no new dependency, no R8 or baseline-profile change.

## 9. Open items

- The thresholds (−30 / −10 / +5 / +20 %, the 130 % band, a strain-14 hard day) are starting values, like §7's. Check
  them against the backfilled history once it ships: the owner should be in Productive or Maintaining most of a
  normal training block, and Overreaching only after a genuinely big week.
- Load is HR-based and per-sport-blind: a long easy ride and a short hard run can carry the same load. That is the
  same limit Strain already has.
