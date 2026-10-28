# Handoff: home-screen widgets → emulator QA (2026-10-26)

Branch: `initial-widgets`. All 7 tasks in `docs/superpowers/plans/2026-10-26-home-screen-widgets.md` are implemented, formatted (`spotlessCheck`), lint-clean (`:app:lintDebug`), unit-tested (`:app:testDebugUnitTest`, 462 tests), and verified to compile and shrink cleanly under R8 (`:app:assembleRelease`).

- **Design spec:** `docs/superpowers/specs/2026-10-26-home-screen-widgets-design.md`
- **Implementation plan:** `docs/superpowers/plans/2026-10-26-home-screen-widgets.md`

## What this branch adds

Five native `AppWidgetProvider` + `RemoteViews` home-screen widgets under `dev.baseline.widget` (zero new Gradle dependencies):

1. **Daily Readiness & Strain (`ReadinessWidgetProvider`):**
   - Responsive `RemoteViews(mapOf(SizeF...))` on API 31+ (with `OPTION_APPWIDGET_MIN_WIDTH < 250` fallback on API 29–30).
   - `4×2` expanded layout: **SLEEP** (left, `50dp`) — **RECOVERY** (center hero, `60dp`, matching `TodayScreen.kt` order and proportions) — **STRAIN** (right, `50dp`), plus target/reason footer and live Body Battery badge.
   - `2×2` compact layout: Recovery %, Strain + target range, and horizontal Body Battery fill bar.
   - Body tap opens **Today** (resetting `SELECTED_DATE` to `today`).
2. **Baseline Vitals (`VitalsWidgetProvider`, `4×2`):**
   - Top 4 unhidden rows from `UserPrefs.baselineOrder` minus `UserPrefs.hiddenRows`, 30-day delta badges, and header summary (`"N worse"` in amber or `"N in range"`). Never resurfaces hidden rows (`"All baseline metrics hidden in Today"` when all 7 are hidden).
   - Body tap opens **Today**.
3. **Body Battery & Stress (`BodyBatteryWidgetProvider`, `4×2`):**
   - Latest Body Battery level colored by Garmin band, `"Wake · Charged · Stress"` metadata, and a 3-minute daytime `Canvas` sparkline across `[00:00..24:00]` in the current `ZoneId` (`12a/6a/12p/6p` or `00/06/12/18` axis).
   - Body tap opens **Today**.
4. **Sleep & Tonight's Need (`SleepWidgetProvider`, `4×2`):**
   - Last night's asleep duration vs. sleep need, sleep %, Garmin sleep score, proportional Deep/REM/Light/Awake stage bar, and tonight's sleep need + recommended bedtime (`12h`/`24h`).
   - Body tap opens **Sleep detail** (`sleep/<today>`) with **Today** on the back stack.
5. **Weekly Activity (`WeeklyActivityWidgetProvider`, `4×2`):**
   - Week-to-date count, duration, and metric/imperial distance honoring `Repository.firstDayOfWeek`; most recent workout card (across all history so Monday mornings aren't empty) with `sportIcon(family)` and strain pill; 7-day recovery/activity strip.
   - Workout card tap opens **Activity detail** (`activity/<id>`); week header, 7-day strip, or empty-state card tap opens **Activities** (`activities`).

### Shared widget behaviour & lifecycle

- **Widget picker previews (`android:previewLayout`):** Dedicated static `@layout/widget_preview_*` XMLs backed by `<vector>` drawables (`@drawable/ic_preview_*`) so Android 12+ widget pickers display populated rings, sparklines, stage bars, and sample numbers without putting fake sample text into `android:initialLayout`.
- **Sync control (`≥ 48dp × 48dp` hit target):** Shows `"↻ <time>"` (or `"↻ <MMM d>"` / `"↻ Sync"`), `"Syncing…"` while `SyncWorker` runs, or `"Key error"` when the key is `Invalid` or `Expired` (tapping `"Key error"` routes straight to **Settings**; otherwise tapping triggers `SyncScheduler.syncNow()` via `WidgetActionReceiver`).
- **Missing key (`KeyStatus.Missing`):** Renders `"Connect Garmin API key to start"` and routes taps to Onboarding.
- **Zero-widget short-circuit & concurrency:** `WidgetUpdater.updateAll` serializes updates with a `Mutex` and returns immediately before touching Room or rendering bitmaps when zero Baseline widgets are placed.

## Setup for emulator testing (Fake Garmin server)

Do not pass `-PgarminBaseUrl` when running `:app:testDebugUnitTest` (`GarminBaseUrlTest` asserts the default real URL). Use `-PgarminBaseUrl` only when building/installing for the emulator:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
uv run --python 3.12 --no-project python -I tools/fake_garmin.py            # listens on :8080
adb reverse tcp:8080 tcp:8080
./gradlew :app:installDebug -PgarminBaseUrl=http://127.0.0.1:8080
```

- If an older release-signed APK is already installed on the test AVD (e.g. `Pixel_10_Pro` booted with `-no-snapshot-save`), either uninstall `dev.baseline` before `installDebug` or build and install a signed release APK (`./gradlew :app:assembleRelease -PgarminBaseUrl=http://127.0.0.1:8080 && adb install -r app/build/outputs/apk/release/app-release.apk`) to smoke-test R8 at the same time.
- Fake Garmin key for onboarding: `fake-garmin-key` (any other key returns HTTP 401 for testing the `Key error` state).
- Note on fixture dates: `tools/fake_garmin.py` serves fixtures around October 2026 (`2026-10-24` / `2026-10-25`); set the emulator date or inspect both pre-sync `"—"` and synced day states accordingly.

## Emulator verification checklist

1. **Android Widget Picker (`Baseline` section):**
   - Long-press home screen → **Widgets** → **Baseline**.
   - Confirm all 5 previews render crisp graphics and sample metrics (no empty boxes or clipped text).
   - Confirm **Daily Readiness & Strain** preview shows **SLEEP** (left) — **RECOVERY** (center hero, larger ring) — **STRAIN** (right), matching `TodayScreen`.
2. **Placement & `initialLayout`:**
   - Drag each of the 5 widgets onto the home screen.
   - Confirm dropping a widget never flashes the picker's fake preview numbers (`78%`, `Morning Run`, etc.) before real Room data binds.
3. **Responsive resize on Daily Readiness & Strain:**
   - Long-press the placed Readiness widget and drag resize handles down to `2×2` and back to `4×2`.
   - Confirm `2×2` switches to the compact numeric layout (Recovery %, Strain + Target, horizontal Body Battery bar, compact `↻` button) and `4×2` restores the three `SLEEP · RECOVERY · STRAIN` dials + footer.
4. **One-tap sync (`↻` pill) & error states:**
   - Tap the top-right `↻` pill on a placed widget: confirm it flips to `"Syncing…"`, `fake_garmin.py` logs the incoming requests, and the pill updates to `"↻ <time>"` when `SyncWorker` finishes.
   - Test `KeyStatus.Missing` (fresh install / cleared storage before onboarding): confirm placed widgets show `"Connect Garmin API key to start"` and tapping opens Onboarding.
   - Test `Key error` (401 key): confirm the pill turns red (`"Key error"`, or `"!"` in `2×2` compact) and tapping it opens **Settings**.
5. **In-app preference & clock reactivity:**
   - In **Today → Edit**, move `Steps` to the top and hide `HRV`: confirm **Baseline Vitals** on the home screen updates its 4 tiles without manual widget re-add, and never shows hidden rows. Hide all 7 rows and confirm `"All baseline metrics hidden in Today"` appears; restore rows afterwards.
   - Toggle 24-hour time in Android system settings: confirm sync timestamps, Sleep bedtime, and Body Battery axis labels (`00 / 06 / 12 / 18` vs `12a / 6a / 12p / 6p`) update.
   - Advance timezone/clock across midnight (`adb shell cmd alarm set-timezone Asia/Tokyo`): confirm day-based widgets roll over to the new date (`"—"` until synced, never leaking yesterday's scores).
6. **Deep-link navigation (cold start & warm `singleTop`):**
   - Tap **Readiness**, **Vitals**, and **Body Battery** widgets (both after force-stopping `dev.baseline` and while the app is open on another tab or past date on Today): confirm all land on **Today** reset to today's date.
   - Tap **Sleep & Tonight's Need**: confirm it opens **Sleep detail** for today, and pressing Back returns to **Today**.
   - Tap the workout card on **Weekly Activity**: confirm it opens that **Activity detail** screen, and pressing Back returns to **Activities**. Tap the week header or 7-day strip: confirm it opens the **Activities** tab.
7. **Cleanup:**
   - Restore emulator timezone/auto-time if changed, and don't save `-fake` builds/data onto persistent review snapshots.
