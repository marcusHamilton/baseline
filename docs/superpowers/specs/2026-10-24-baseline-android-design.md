# Baseline — Android Garmin dashboard: design spec

Date: 2026-10-24
Status: draft for review

## 1. Summary

Baseline is a personal Android app that replaces *viewing* Garmin Connect. It shows daily health in a
Whoop-style layout (Recovery / Sleep / Strain), plus a full activity history, all-time records, a
calendar and trends. Data comes from Garmin's **Connect Personal API** (read-only, pull-only), cached
on the phone.

Garmin Connect stays installed: the watch still uploads through the Connect phone
app. Baseline never writes to Garmin.

### What the user asked for
- Replace Garmin Connect for looking at activities and health stats; Whoop-style daily view.
- Whoop-style computed scores (Recovery %, Strain 0–21), not Garmin's metrics in a new skin.
- Activities: running, cycling, strength, swimming and others.
- Android only. Just the user, sideloaded. Claude builds, the user reviews on the phone.
- Pull and keep all history.
- All-time records for common run, ride and swim distances.
- Today screen: today's activities above the baseline list, with the heading only when there are any.
- Tapping the date opens a calendar.
- Baseline rows can be reordered (and hidden) in the app.
- Strain: keep 0–21, computed with the hybrid approach in §7.2.

### Assumptions (correct these if wrong)
- The user is fine with the phone holding the API key and all data. There is no backend.
- Metric units by default. The Garmin profile's `measurementSystem` is read and the units follow it.
- The user re-creates the Garmin key at most once a year and pastes it into the app.

### Non-goals (v1)
- iOS or web, a backend, multiple users or Play Store distribution.
- Writing to Garmin (workouts, courses, renaming activities).
- Notifications other than sync progress, Health Connect export. (Home-screen widgets were added later; see `2026-10-26-home-screen-widgets-design.md`.)
- Nutrition, hydration, weight, women's health screens. The endpoints exist; they're left out until asked for.

## 2. Verified data sources

Everything below was checked against the user's account on 2026-10-24.

- **Base URL:** `https://personal.connectapi.garmin.com`
- **Token:** `POST /personalapitoken-service/personal-api/personal-api-key/token` with header
  `Authorization: ApiKey <key>`. The response body is a JWT. That JWT is sent as
  `Authorization: Bearer <jwt>`.
- **History:** data starts **2025-08-30**. There are 175 activities. Ranges before that date return
  empty `{"data":[]}` or 204.

| Data | Endpoint | Max range / call | Confirmed fields used |
|---|---|---|---|
| Daily summary | `GET /usersummary-service/v2/dailysummaries?startDate&endDate` | 180 d | `heartRate.restingValue`, `respiration.avgValue`, `stress.avgLevel`, `bodyBattery`, `calories`, `movement` |
| Sleep (one night) | `GET /sleep-service/sleep/dailySleep?date` | 1 d | stage seconds (deep/light/rem/awake), `sleepTimeSeconds`, `sleepScores.overall.value`, `sleepNeed{baseline,actual,*Adjustment}`, `averageRespirationValue`, `averageSpO2Value`, `avgHeartRate`, `avgSleepStress`, start/end timestamps |
| Sleep summaries | `GET /sleep-service/sleep/dailySleepSummariesByDate?startDate&endDate` | 28 d | stage seconds, `sleepNeed`, timestamps (no score, respiration or SpO₂) |
| HRV (one night) | `GET /hrv-service/hrv/{date}` | 1 d | `hrvSummary{lastNightAvg, weeklyAvg, baseline{balancedLow,balancedUpper}, status}`, `hrvReadings[]` every 5 min |
| HRV range | `GET /hrv-service/hrv/daily/{start}/{end}` | 180 d | `hrvSummaries[]` (same summary shape) |
| All-day HR | `GET /wellness-service/wellness/dailyHeartRate?date` | 1 d | `heartRateValues[[ms, bpm]]` every **120 s**, `restingHeartRate` |
| Body Battery | `GET /wellness-service/wellness/bodyBattery/reports/daily?startDate&endDate&chunks=288` | 28 d | `bodyBatteryValuesArray` (with `chunks=288`: a sample every 3 min, 576 per day; without it: only 6 per day), `charged`, `drained` |
| Stress | `GET /usersummary-service/stats/stress/daily/{start}/{end}` | 7 d | `values` |
| Steps | `GET /usersummary-service/stats/steps/daily/{start}/{end}` | 7 d | `totalSteps`, `stepGoal`, `totalDistance` |
| SpO₂ | `GET /wellness-service/wellness/daily/spo2acclimation/{date}` | 1 d | `avgSleepSpO2`, `lowestSpO2` (the sleep payload already has these; fetched only lazily) |
| HR zones | `GET /biometric-service/heartRateZones/v2` | — | `maxHeartRateUsed`, `restingHeartRateUsed`, `zone1Floor..zone5Floor` |
| Profile | `GET /userprofile-service/userprofile/settings` | — | `measurementSystem`, `timeZone`, `firstDayOfWeek` |
| Activity list | `GET /activitylist-service/activities/search/activities?start&limit` | 50 / page | id, type, times, distance, duration, avg/max HR, `hrTimeInZone_1..5`, `activityTrainingLoad`, `fastestSplit_{1000,1609,5000,10000,21098,42195,40000,100}`, `pr`, `summarizedExerciseSets`, `avgPower`, `normPower`, `elevationGain` |
| Activity detail | `GET /activity-service/activity/{id}` | — | `summaryDTO`, `splitSummaries` (fetched on open only) |
| Original file | `GET /download-service/files/activity/{id}` with `Accept: application/zip, application/x-zip-compressed, application/octet-stream` | — | ZIP holding one `.fit`. Any other Accept value returns **406**. |

**What the FIT files contain:**
- **Run:** 1 s records with `heart_rate`, `power`, `cadence`, `step_length`, `vertical_oscillation`,
  position, `distance`, `enhanced_altitude`; plus laps.
- **Road ride:** 1 s HR, GPS and distance; **no power** (no power meter).
- **Virtual ride:** 1 s HR and **power** (from the trainer).
- **Swim:** 1 s `heart_rate`; `length` messages (time, strokes, stroke type).
- **Strength:** `set` messages (reps, weight, category). HR uses smart recording, so samples are
  1–4 s apart (413 samples over 983 s).

### 2.1 Time base
- **GMT epoch fields** (`*GMT` epoch ms, the HR sample timestamps, FIT timestamps) are real instants.
  **All interval maths uses these:** excluding activity windows, sleep windows, sample gaps.
- **`*Local` epoch fields** are local wall-clock time encoded as if it were UTC. In the sample,
  `sleepStartTimestampLocal` = GMT − 7 h. They are parsed as `LocalDateTime` at UTC and used only for
  display and choosing the calendar day.
- **An activity's day** is the date part of its `startTimeLocal` string.
- **A sleep's day** is its `calendarDate`, which is the morning it ended.

### 2.2 Sport families
Activity `typeKey` maps to a family. The family picks the detail layout and the records it feeds.

| Family | typeKeys (✓ = in the user's data) | Records |
|---|---|---|
| run | `running` ✓, `trail_running` ✓, `track_running` ✓, `treadmill_running` ✓ | yes (treadmill excluded from records) |
| ride | `road_biking` ✓, `virtual_ride` ✓, `indoor_cycling` ✓, `cycling`, `mountain_biking`, `gravel_cycling` | distance: outdoor only; power: any ride with power |
| swim | `lap_swimming` ✓, `open_water_swimming` | pool records: `lap_swimming` only |
| strength | `strength_training` ✓ | — |
| other | everything else (`walking` ✓, `hiking` ✓, `pickleball` ✓, `resort_skiing` ✓, `yoga` ✓, `indoor_rowing` ✓, `horseback_riding` ✓, `mobility` ✓, …) | — |

Unknown typeKeys fall into **other**. The icon follows the family, with a few per-type overrides
(walking, hiking).

**Not available:**
- Sleep stage timeline (hypnogram).
- Training Readiness, Training Status, acute/chronic load.
- Garmin's own personal-records endpoint (404).

## 3. Architecture

A single-module Android app. Manual dependency wiring through an `AppContainer` owned by the
`Application`.

```
app/src/main/java/dev/baseline/
  garmin/    GarminApi (Retrofit), TokenProvider, ApiKeyVault, DTOs
  data/      Room AppDatabase, entities, DAOs, Repository (the only thing UI reads)
  sync/      SyncWorker, BackfillPlanner, SyncCursor
  fit/       FitReader (Garmin FIT SDK) -> ActivityStreams
  scores/    Baselines, Recovery, Strain, SleepPerformance, StrainCalibration  (pure Kotlin)
  records/   BestEfforts (run/ride/swim)                                         (pure Kotlin)
  prefs/     DataStore: baseline row order + hidden set, units, sync interval, key expiry
  ui/        theme, nav, today, calendar, sleep, activities, activity, records, trends, settings
```

**Data flow:**
1. `SyncWorker` → `GarminApi` → raw JSON saved in Room.
2. Raw rows are parsed into typed rows.
3. `scores/` and `records/` compute derived rows.
4. The Repository exposes `Flow`s, and Compose screens collect them.

The UI never calls the network. Pull-to-refresh enqueues the same `SyncWorker` (expedited).

**Raw-plus-derived storage.** Every API response is stored as raw JSON, keyed by
`(endpoint, key)` (key = date or activity id). Typed and derived tables are rebuilt from the raw
rows. When a formula changes, scores can be recomputed without re-downloading.

## 4. Auth and key handling

- **Entering the key:**
  - The key is entered on Settings, and on a first-run screen when no key is saved.
  - The user also picks how long the key lasts (30 / 90 / 180 / 365 days, the same choices Garmin
    offers). The app stores the computed expiry date, because Garmin doesn't expose it.
  - "Save and test" mints a token and calls the profile endpoint. On failure the key is not saved and
    the error is shown.
- **Storage:**
  - An AES-GCM key generated in the Android Keystore encrypts the API key and the cached JWT.
  - Both are kept in DataStore.
  - The plaintext key is never logged. OkHttp logging is off in release builds and redacts
    `Authorization` in debug builds.
- **Tokens:**
  - `TokenProvider` holds the JWT and its `exp` claim, and mints a new one when fewer than 60 minutes
    are left.
  - An OkHttp `Authenticator` handles a 401 by minting once and retrying once.
  - If the second attempt also returns 401, or the mint itself returns 401, the key state becomes
    *invalid/expired*: Settings shows the red banner ("Garmin rejected your key, so new data can't sync. Paste a new key in Settings." / "Your Garmin key expired, so new data can't sync. Paste a new key in Settings."). Syncing keeps trying, so a transient 401 heals on the next 200.
- **Expiry warnings:** an amber banner from 7 days before the stored expiry, a red banner after it.
  Cached data stays readable.

## 5. Sync

### 5.1 First-run backfill (everything since 2025-08-30, about 1,200 requests)
`BackfillPlanner` turns the date span into a list of request tasks, stored in a `sync_task` table.
Each task is `(endpoint, key, status)`. The list survives app restarts, which is what makes backfill
resumable.

| Order | Tasks | Calls for 14 months |
|---|---|---|
| 1 | Profile, HR zones | 2 |
| 2 | Daily summaries, HRV range (180 d chunks) | 6 |
| 3 | Sleep summaries, Body Battery (28 d chunks) | 30 |
| 4 | Stress, steps (7 d chunks) | 120 |
| 5 | Activity list (50 / page until short page) | 4 |
| 6 | Per-day sleep + all-day HR, **newest first** | ~840 |
| 7 | FIT per activity, newest first | 175 |
| — | Per-day HRV readings, SpO₂ | lazy: when Sleep detail opens, plus the last 7 days during sync |

After steps 1–5 (about a minute), Today, Calendar, Trends and the Activities list already work.
Steps 6–7 fill in sleep detail, all-day strain and records in the background.

**Pacing:**
- Requests run one at a time, at most 1 per second.
- On a 429 or 5xx the worker backs off for 60 s, then 120 s, then 240 s. After three failures it stops
  and resumes on the next scheduled run.
- An empty `{"data":[]}` or a 204 marks the task done with no data.

**How it runs:**
- Backfill runs as a long-running WorkManager job with a foreground notification
  ("Syncing history 340 / 1,180").
- It needs network, and only runs on unmetered networks unless the user taps "Sync now".

### 5.2 Incremental sync
Runs every hour by default (or every 3 hours, or manual only), plus pull-to-refresh.

**Each run:**
1. Re-fetch the range endpoints for the last 7 days.
2. Re-fetch per-day sleep, all-day HR and HRV for **today and yesterday**.
3. Page the activity list from 0 until it reaches a known `activityId`.
4. Download FIT files for new activities.

That's about 15 requests. Days older than yesterday are treated as final.

### 5.3 Derived recompute
After any sync that changed raw rows for day D, recompute:
- baselines and Recovery for D..today
- Strain for D
- records for any new activities

## 6. Storage (Room)

| Table | Contents |
|---|---|
| `raw_response` | endpoint, key, json, fetchedAt. Primary key `(endpoint, key)`. |
| `day` | date, restingHr, respirationAvg, stressAvg, steps, stepGoal, bodyBatteryWake, bodyBatteryCharged, sleepSeconds, deep/light/rem/awake seconds, sleepScore, sleepNeedMinutes, sleepStart, sleepEnd, spo2Avg, hrvLastNight, hrvWeeklyAvg, hrvBalancedLow, hrvBalancedUpper |
| `hr_sample` | date, epochSec, bpm (all-day 2-min samples). Stored packed as one blob per day to keep row counts low. |
| `body_battery_day` | date, packed (all-day 3-min Body Battery levels 0–100, same packing as HR, deduplicated by timestamp). |
| `activity` | id, type, sportFamily, start, duration, distance, avgHr, maxHr, zoneSeconds[5], garminLoad, elevationGain, avgPower, normPower, name, fastestSplits (JSON), fitPath, hrCoverage |
| `activity_stream` | One packed blob per activity: t, hr, dist, speed, power, alt, lat, lon. Parsed from FIT. |
| `activity_lap`, `swim_length`, `strength_set` | Parsed from FIT. |
| `score` | date, recovery, sleepPerformance, dayLoad, strain, strainTarget, calibrationVersion |
| `activity_score` | activityId, load, loadSource (`HR` \| `GARMIN_SCALED`), strain |
| `best_effort` | sport, distanceOrDuration, value, activityId, achievedAt |
| `sync_task` | endpoint, key, status, attempts, lastError |

FIT ZIPs are unpacked into `filesDir/fit/{id}.fit` and kept, so they can be re-parsed later.

## 7. Scores

All constants below are **starting values**. They're tuned after 2–4 weeks of use; the 14 months of
backfilled history can be used to check them immediately.

### 7.1 Baselines
- A rolling 30-day window ending the day *before* D.
- Inputs: ln(HRV last-night average), resting HR, sleep respiration. Sleep performance is scored
  against fixed targets (§7.4), not a personal baseline.
- Mean and standard deviation of each.
- Needs at least 7 valid nights, otherwise the UI shows "Calibrating".
- The standard deviation is floored to avoid divide-by-tiny: HRV 0.05 (log scale), RHR 1.5 bpm.

### 7.2 Strain (0–21): the hybrid approach
Strain measures cardiovascular load from heart rate.

**Heart-rate effort per sample (Banister TRIMP):**
- Heart-rate reserve: `r = clamp((HR − HRrest) / (HRmax − HRrest), 0, 1)`.
- HRmax and HRrest come from Garmin's HR zones (205 and 44 today).
- Each sample adds `dt_min × r × a × e^(b·r)`.
- `dt` is the **actual gap** to the next valid sample, capped at 10 s. It's never assumed to be 1 s,
  because strength uses smart recording.
- `(a, b)` is one of Banister's two published coefficient sets:
  - (0.64, 1.92), fitted on male subjects — the default
  - (0.86, 1.67), fitted on female subjects

  Settings shows them with those labels.

**Activity load:**
1. Compute TRIMP from the FIT HR stream.
2. `hrCoverage` = (sum of gaps between consecutive valid HR samples, each gap capped at 10 s) ÷
   activity duration. The strength sample measures 0.98, well above the 0.6 threshold.
3. If `hrCoverage ≥ 0.6`, use the TRIMP value; `loadSource = HR`.
4. Otherwise (e.g. a swim with poor wrist HR, or a missing FIT): `load = garminLoad × k_sport`;
   `loadSource = GARMIN_SCALED`.
   - `k_sport` = the median of `TRIMP / garminLoad` over that sport's activities that have coverage
     ≥ 0.6 and `garminLoad > 10`.
   - If no such activities exist, use the median across all sports.
5. Strength sessions almost always take the HR path. Garmin's load for them is tiny (median 3), which
   is exactly why the formula doesn't use it directly.

**Non-activity load:**
- Comes from the 2-min all-day HR.
- Samples inside activity windows are excluded.
- Only samples at or above Garmin's `zone1Floor` (103 bpm) count, so a resting day doesn't pile up
  phantom load.
- Each sample counts as `dt = 2 min`.

**Converting load to strain:**
- Day load `L = Σ activity loads + non-activity load`.
- `strain = 21 × (1 − e^(−L/τ))`, rounded to 0.1.
- Each activity's own strain uses the same curve on its own load. Activity strains therefore don't add
  up to the day's strain; the curve flattens the total.

**Calibration:**
- `τ` is set so the 90th-percentile day load maps to 17: `τ = p90 / ln(21/4)`.
- p90 is taken over **all days with complete data**, including rest days. Today's history has
  p90 ≈ 184 (from Garmin's load; it gets re-derived from TRIMP after backfill).
- It's computed after backfill and recomputed on the 1st of each month.
- Each value is stored with a `calibrationVersion`. Past days keep the τ they were computed with, unless
  the user taps "Recalculate history".

**Strain target from Recovery:**

| Recovery | Target strain |
|---|---|
| ≥ 67 | 14–18 |
| 34–66 | 10–14 |
| < 34 | 0–10 |

In an Overreaching week (`2026-10-31-fitness-fatigue-form-design.md` §4), Today and the Readiness widget show a target
above 10–14 as 10–14, marked "(eased for this week)" on Today. The stored target is unchanged.

**Accuracy:**
- Consistent day to day and personal to the user, but not equal to Whoop's numbers.
- Strongest for runs and rides, decent for strength, weakest for swims (the fallback path covers those).

### 7.3 Sleep performance
- `sleepPerformance = min(1, sleepSeconds / (sleepNeed.actual × 60))`, using **that night's**
  `sleepNeed`. `sleepNeed.actual` already includes Garmin's HRV, training and sleep-debt adjustments.
- Garmin's sleep score is shown as a secondary number.
- **"Tonight's sleep need" card:**
  - Uses `nextSleepNeed`: Garmin's baseline in minutes, the total adjustment (`actual − baseline`),
    and the adjustment reasons Garmin names (training, HRV, sleep history, nap).
  - Garmin gives **labels only, no minutes per reason.**
  - Shows the recommended bedtime from `recommendedBedtimeStartMins`.

### 7.4 Recovery (0–100 %)
**Component scores (z-scores against the 30-day baseline):**
- `z_hrv = (ln HRV − mean) / sd`
- `z_rhr = −(RHR − mean) / sd`
- `z_sleep = clamp((sleepPerformance − 0.85) / 0.10, −3, 2)`
- `z_resp = −max(0, (resp − mean) / sd)` (only an increase hurts)

**Combining them:**
- `c = 0.50·z_hrv + 0.25·z_rhr + 0.20·z_sleep + 0.05·z_resp`
- `recovery = round(100 / (1 + e^(−(1.2·c + 0.4))))` → an average day is about 60 %.

**Zones:** ≥ 67 green, 34–66 amber, < 34 red.

**Fallbacks:**
- No HRV that night: the HRV term is dropped, the other weights are renormalised, and the dial shows
  "Partial".
- No sleep at all: no Recovery that day.

**The text card** explains the two biggest contributors, in words, with their deltas against baseline.

## 8. Records

- **Run** (1 km, 1 mile, 5K, 10K, Half, Marathon): the all-time minimum of Garmin's `fastestSplit_*`
  across running-family activities (running, trail, track, treadmill). No FIT parsing needed.
- **Ride** (10 / 20 / 40 / 100 km):
  - Fastest time from a two-pointer sliding window over the FIT cumulative distance and time.
    Outdoor rides only.
  - Garmin's `fastestSplit_40000` is used to cross-check.
  - **Best power** (5 s, 1 min, 5 min, 20 min, 60 min) uses a rolling mean over the 1 s power stream.
    Today that means virtual rides only, since road rides have no power. The row is labelled
    "Indoor" until an outdoor ride with power exists.
- **Swim** (100 / 400 / 1,500 m, pool):
  - The best run of consecutive active `length` messages whose distance (lengths × pool length)
    reaches the target.
  - Garmin's `fastestSplit_100` is used to cross-check.
- **Updating:** records are recomputed when new activities arrive. A record set in the last 30 days
  shows a NEW badge. Tapping a record opens its activity.

## 9. Screens

All screens follow the mockups.

| Screen | Notes |
|---|---|
| Today | Date header (tap → Calendar; ‹ › change day). Sleep / Recovery / Strain dials. A slim **Body Battery strip** sits under the dials (no card): "BODY BATTERY" styled like the dial labels, the overnight charge, the latest level and a thin track filled to that level out of 100, both in Garmin's band colours: 76–100 `#34D399`, 51–75 `#A3D94C`, 26–50 `#FBBF24`, 0–25 `#F87171`. With no reading it shows an empty track and "—". Tapping it shows the day's 3-min curve and the drained total. The **Recovery card** follows, with the strain target and the week's training load: a tag ("Productive week"), a callout only when the week needs attention, and "This week: 67% of normal · ~3 hard days left"; tapping it opens Trends at the Training load card (`2026-10-31-fitness-fatigue-form-design.md` §4). **Today's activities** section above the baseline list, shown only if there are activities. **Vs. 30-day baseline** rows: HRV, RHR, sleep, respiration, Body Battery at wake, stress, steps. An **Edit** mode adds drag handles (reorder) and an eye toggle (hide). Order and hidden set are saved in DataStore. |
| Calendar | Month grid, Monday or Sunday start following the Garmin profile. Each day has a ring coloured by Recovery and up to 3 activity dots. Tap a day → summary card; "Open day" → Today for that date. Swipe or arrows change the month. |
| Sleep detail | Sleep % and hours vs need, Garmin score, bed and wake times. Overnight HRV (5 min) and HR (2 min) chart *instead of a hypnogram*. Stage totals with bars. Sleep-need card. HRV, lowest HR, Body Battery charged, SpO₂ tiles. |
| Activities | Sport filter chips, week summary, list grouped by day. A **Records** button opens Records. |
| Activity detail | Built from sections that depend on the sport: **Run**: map, summary, HR chart, zones, splits, cadence/power/stride. **Ride**: map, speed/power/elevation, zones, laps. **Swim**: lengths, pace per 100 m, strokes, zones. **Strength**: sets by exercise, HR chart. **Other**: summary, HR, zones. Garmin's training load is shown next to Baseline strain. |
| Records | Run / Ride / Swim tabs, plus a best-power row for rides. |
| Trends | 7 / 30 / 90-day range. Recovery bars, HRV line with Garmin's balanced band, RHR line, sleep vs need, and Training load: fitness and fatigue lines with a form-state strip (`2026-10-31-fitness-fatigue-form-design.md`). |
| Settings | Key status, expiry and replace; sync interval and "Sync now"; "N items couldn't sync" + "Retry failed" when any sync task is FAILED (re-queues them and syncs now); history coverage; strain coefficient set; recalculate history; units. An **App** card: version, the update on offer with its notes and one button (Update / Allow / Cancel / Install / Retry), "Check now" and a daily-check switch (`2026-10-29-in-app-updates-design.md`). Today shows the same offer as a dismissable banner under the key banner. |

**Navigation:**
- Four tabs: Today, Activities, Trends, Settings. Tapping a tab always opens that tab's own page, never a page left open under it (Activities → Records, then Activities, shows Activities). Tapping Today also returns it to the current day. A page opened from a tab keeps that tab highlighted. Back from any tab lands on Today.
- Transitions are quick: tabs swap with a ~120 ms fade; pages opened from a tab slide in over ~220 ms. The activity map is created once its page has finished sliding in.

**Look:**
- Dark only for v1, background `#0E1013`.
- Barlow Condensed for numbers and headings, Inter for body text.
- Recovery colours `#34D399` / `#FBBF24` / `#F87171`, strain `#60A5FA`, sleep `#A78BFA`.

**Accessibility:**
- Touch targets ≥ 48 dp.
- Content descriptions on dials and charts.
- Colour never the only signal: zones also have labels, and deltas have arrows.

## 10. Error handling

| Situation | Behaviour |
|---|---|
| Offline | Show cached data with a "Last synced …" line. Sync waits for the network constraint. |
| 401 after re-mint | Key invalid/expired: red banner (sync keeps trying and heals on the next 200), cached data still readable. |
| 429 / 5xx | Back off as in §5.1. The task keeps its status, so nothing is lost. |
| 400 on a range | Means the window is too large. The planner's window sizes are fixed per endpoint, so this is logged as a bug and the task is split in half once. |
| Malformed JSON or FIT | Raw row kept, parse error stored on the task. That day or activity shows "Couldn't read this data". |
| Missing data | Missing HRV, sleep or HR data: the scores degrade as in §7 and the UI says what's missing. Never a fake value. |
| Schema changes | Garmin can change fields. DTOs ignore unknown keys; missing optional fields become null. |

## 11. Testing

**Unit tests (JVM):**
- `scores/`, `records/` and `BackfillPlanner`, using fixtures trimmed from the 2026-10-24 dump.
- Fixtures are scrubbed of name, profile image URLs and display name.

**API client:**
- MockWebServer covers token mint, Bearer header, the 401 → re-mint → retry flow, 429 backoff,
  204/empty handling and the exact FIT `Accept` header.

**FIT parsing:**
- Tests against the real sample files: run, road ride, virtual ride, swim, strength, short indoor ride.
- A strain test asserts the strength fixture takes the `HR` path (coverage ≥ 0.6), and that its `dt`
  values follow the real 1–4 s gaps.
- A time-base test asserts sleep and activity windows line up with the GMT HR samples (§2.1).

**Formula checks:**
- A test runs the score pipeline over all 14 months of fixture data.
- It asserts the result is sane: strain p90 is about 17, Recovery stays within 5–98, and there are no
  NaN values.

**On device:** the user installs debug builds through Android Studio and reviews each phase.
`tools/fake_garmin.py` serves the fixtures as a fake Personal API (fake key `fake-garmin-key`); a
build made with `-PgarminBaseUrl` talks to it, so emulator runs, including a minified release, need
no real key. Normal builds always use the real API.

**Fixtures and privacy:** fixtures contain personal health data, and one run includes GPS. The repo
stays private (local, or a private GitHub repo).

## 12. Tech stack

| Area | Choice |
|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3, Navigation Compose. One `app` module, minSdk 29, targetSdk latest. |
| Storage | Room, DataStore |
| Background | WorkManager |
| Network | Retrofit, OkHttp, kotlinx.serialization |
| FIT files | Garmin FIT SDK for Java. The Maven coordinates are confirmed at scaffold time. |
| Charts | Vico |
| Maps | MapLibre Android with OpenFreeMap vector tiles (no API key) |
| Updates | GitHub's public releases API and `PackageInstaller`, with the libraries above; no new dependency (`2026-10-29-in-app-updates-design.md`) |
| Reordering | A small custom drag-reorder (`RowReorder.kt`, with edge auto-scroll); no library |
| Tests | JUnit, MockWebServer, kotlinx-coroutines-test |
| Start-up speed | `androidx.profileinstaller` with a hand-written baseline profile (`app/src/main/baseline-prof.txt`) |

Versions are pinned in `gradle/libs.versions.toml` at scaffold time.

**Hosts the app talks to:** Garmin (`personal.connectapi.garmin.com`), OpenFreeMap for map tiles, and, for
in-app updates, `api.github.com` once a day plus GitHub's download CDN when the owner taps Update. Only Garmin ever
sees the key; the update client has no authenticator (`UpdatesUrlTest`). The daily check can be turned off in Settings.

**Release build:** R8 (`isMinifyEnabled`) and resource shrinking are on for `release`; the keep rules are
in `app/proguard-rules.pro`, which also records why each library needs none of its own. `SyncWorker`'s
class name is kept because WorkManager stores it with queued work. R8 problems only show at runtime, so
every release is smoke-tested on an emulator before it's tagged: key entry, a sync, Today, an activity
with a map, Records, Trends, Calendar and Settings.

**Why profileinstaller:** without a baseline profile, Compose code runs interpreted and JIT-compiled for
the first launches after every install or update, which shows as janky start-up and slow first tab
switches. The APK is sideloaded from GitHub releases, not installed by Play, so nothing compiles the
profile unless the app installs it itself; `profileinstaller` does that on first launch (it already
arrives transitively through Compose, and is declared directly so it isn't dropped by accident). The
profile is hand-written rather than generated by a `:baselineprofile` Macrobenchmark module, which would
add a test module, UI Automator and a device run to every release. It covers start-up (application,
DI, data layer, navigation, Today) and the screens reached from it (Calendar, Sleep, Activities, an
activity, Records, Trends, Settings, plus Vico and MapLibre); AGP merges it with the profiles the
AndroidX libraries ship and rewrites it for R8's renaming.

**Build prerequisite:** JDK 17+ and the Android SDK on this Mac. They're checked before phase 1; if
missing, the user installs Android Studio.

## 13. Build phases

Each phase ends with an installable build the user reviews.

1. **Skeleton:** theme, navigation, key entry and vault, token provider, Retrofit client, raw storage.
   One-day sync fills a Today screen showing raw values.
2. **Sync:** the backfill planner, a resumable worker with a notification, incremental sync and
   pull-to-refresh.
3. **Scores:** baselines, Recovery, Sleep performance, Strain with calibration. Today dials and the
   Calendar.
4. **Activities:** list, FIT parsing, per-sport detail screens, map.
5. **Records and Trends.**
6. **Polish:** baseline reorder/hide, expiry banners, error states, Settings extras.

## 14. Open items and risks

- **Rate limits** are unpublished. 1 request per second has been fine so far (about 35 calls), but a
  full backfill is untested.
- **Ride power:** road rides have no power, and virtual rides do. Outdoor best-power records need a
  power meter.
- **Strength weight** is only present when logged on the watch. Without it, sets show reps only.
- **Strain coefficients:** the default is Banister's male-fitted set; Settings offers the female-fitted
  set. The user picks whichever fits. Switching sets re-fits the strain scale before re-scoring history.
- **API stability:** the Personal API is about a week old, and field names may change. The raw JSON
  storage plus lenient DTOs keep that recoverable.
- **Key expiry date** comes from the user's choice at entry, not from Garmin.
