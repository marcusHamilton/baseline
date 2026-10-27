# Baseline

A personal Android app that turns your own Garmin data (Garmin Personal API) into a WHOOP-style dashboard: recovery, sleep and strain scores, Today, Activities, Trends, Calendar, Records and Settings. It's a single-user, on-device app with no backend.

- **Spec (the authority):** `docs/superpowers/specs/2026-10-24-baseline-android-design.md`. Read the relevant section before changing behaviour; when code and spec disagree, fix one of them in the same change.
- **Plans:** `docs/superpowers/plans/` (the original 21-task build plan, plus later fix plans).
- **Handoffs:** `docs/superpowers/handoff/` (what the last session did, and where it had doubts).

## Build and test

> **Cloud session (Linux container, not the owner's Mac)?** Read `docs/cloud-sessions.md` first and run `tools/cloud_setup.sh`. There's no emulator, keystore or Garmin key there, and the commands below need that setup.

On the owner's Mac there's no system JDK; use Android Studio's:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest            # Robolectric + Compose UI tests, ~425 tests
./gradlew :app:testDebugUnitTest --tests 'dev.baseline.sync.*'
./gradlew :app:assembleDebug
./gradlew :app:installDebug                 # needs a device/emulator
./gradlew :app:lintDebug
./gradlew spotlessApply                     # format; spotlessCheck is the CI gate
```

- `lintDebug` is clean and CI runs it with `warningsAsErrors`, so any new finding fails the build. Fix it, or disable the check in the `lint {}` block of `app/build.gradle.kts` with a reason. Run it before finishing a change.
- Formatting is ktlint through Spotless (config in the root `build.gradle.kts` and `.editorconfig`). Run `./gradlew spotlessApply` before committing; CI runs `spotlessCheck`. Don't hand-format. The formatting commit is listed in `.git-blame-ignore-revs`.

- Kotlin 2.4, AGP 9.4, Compose BOM 2026.09, Room 3 (`androidx.room3`, KSP), WorkManager, DataStore, OkHttp 5, Garmin FIT SDK, Vico charts, MapLibre. Versions live in `gradle/libs.versions.toml`.
- minSdk 29, target/compile 37, JVM 17. Release is minified with R8 (`app/proguard-rules.pro`) and ships a hand-written baseline profile (`app/src/main/baseline-prof.txt`); smoke-test a release build on the emulator after touching either, since R8 breakage only shows at runtime.
- Instrumented tests live in `app/src/androidTest` (DB smoke and Keystore tests) and need a device.
- The unit suite must pass on any host time zone. Tests that format times inject a `Clock` and must not depend on the system default zone.

## Release signing

- `app/build.gradle.kts` signs release builds only when `keystore.properties` exists in the repo root. That file is git-ignored and holds `storeFile`, `storePassword`, `keyAlias` (`baseline`) and `keyPassword`. Without it, `assembleRelease` produces an unsigned APK.
- On the owner's Mac the keystore is `~/.config/baseline/baseline-release.jks`, and a copy of the properties file is at `~/.config/baseline/keystore.properties`. If the repo-root copy is missing, copy that one in.
- To make a release:
  1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
  2. Run `./gradlew :app:assembleRelease`.
  3. Check the signature: `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`.
  4. Tag `vX.Y` and attach the APK to a GitHub release (`gh release create`).
- **Never commit the keystore or `keystore.properties`, and never print or log their passwords.** Every update must be signed with this same key, or it won't install over the previous release. If the key is lost, users have to uninstall, which wipes their data.
- Debug builds use the machine's debug key, so they won't install over a release build (or the other way round) without an uninstall.

## Architecture (`app/src/main/java/dev/baseline/`)

- `AppContainer` is manual DI: every singleton is wired here. There is no Hilt. `BaselineApp` owns it, and `MainActivity` hosts `ui/nav/BaselineNavHost`, which builds each screen's ViewModel from the container.
- `garmin/`: the Garmin Personal API.
  - `ApiKeyVault` keeps the API key AES-encrypted via `KeystoreAead`, along with its expiry and an invalid flag.
  - `TokenProvider` mints and caches the short-lived JWT from the key.
  - `GarminClient` sends every request through one `requestLock`, so only one Garmin request is ever in flight (rate limits).
  - `KeyEntry.saveAndTest` tests a candidate key via `GarminClient.testKey` without writing it to the vault, and saves it only after it passes, while still holding the lock.
- `sync/`:
  - `SyncWorker` is the only thing that talks to Garmin.
  - `SyncEngine` runs a task table: first-run backfill planned by `BackfillPlanner`, then incremental windows.
  - `RawIngest` stores raw responses before parsing, so data can be re-parsed.
  - `SyncScheduler` wraps WorkManager.
- `data/`: `Repository` plus Room `data/db/AppDatabase`, at schema version 2. Schemas are exported to `app/schemas/`; any schema change needs a migration and a new exported schema.
- `scores/ScorePipeline` holds recovery, sleep performance and strain (0–21). The strain scale is calibrated so your 90th-percentile day is about 17 (spec §7). `records/RecordsRefresher` maintains all-time records.
- `fit/` parses FIT files with the Garmin FIT SDK and builds activity streams.
- `prefs/UserPrefs` is DataStore: baseline row order and hidden rows, sync interval, coefficient set, cached JWT and last sync.
- `ui/<screen>/`: one Compose screen and one ViewModel per feature. `ui/components` holds shared pieces (KeyBanner, OfflineBar, StatRow), `ui/theme` holds the colours (`Bg` is `#0E1013` and `ChipActive` is `#2A2F37`), and `ui/format/Formatters` does display formatting.
- `DateTicker` and `SystemZoneClock` make "today" and the current zone follow midnight and time-zone changes, through a broadcast receiver and `MainActivity.onResume`. Screens take `container.today` or `dateTicker.zone`; never cache `LocalDate.now()` or `ZoneId.systemDefault()` in a ViewModel.

## Invariants: don't break these

- **The API key is a secret.** Never log it, put it in a message or exception, or print it in test output. The OkHttp logger redacts `Authorization`.
- **One Garmin request at a time.** Everything goes through `GarminClient`; don't create a second client path.
- **A key under test never touches the vault.** Only a passing test saves it. Don't reintroduce save-then-rollback: a running sync can mark the good key invalid.
- **Sync is not paused on an Invalid or Expired key.** It keeps trying, so a one-off 401 heals on the next 200. The scheduler only stops when the key is Missing. The red banner text in `ui/components/KeyBanner.kt` says "…so new data can't sync. Paste a new key in Settings."
- **Room schema changes** need a version bump, a migration and an exported schema.
- **Touch targets are at least 48dp** (spec §9, accessibility). TalkBack descriptions exist for dials, charts, calendar cells and the drag handles ("Move up" / "Move down").
- **Today edit mode** uses the custom drag-reorder in `ui/today/RowReorder.kt` and `TodayScreen.kt`, with edge auto-scroll, not a library. Drop indices are in the all-rows space, hidden rows included, which is what `TodayViewModel.moveRow` expects.

## Conventions

- Match the surrounding code: comment density, naming and Compose patterns. Every behaviour fix gets a regression test (Robolectric/Compose tests live under `app/src/test`). Add no new dependencies without a reason in the spec.
- Use Conventional Commits (`feat:`, `fix:`, `test:`, `docs:`).
- Test fixtures in `app/src/test/resources/fixtures/` are anonymized, never raw (see below). `tools/fetch_sample.py` pulls samples from the user's own account into the git-ignored `tools/out/`; `tools/make_fixtures.py` (JSON, via `tools/anonymize.py`) and `tools/anonymize_fit.py` (each `.fit`) turn them into fixtures. Run them with `uv run --python 3.12 --no-project python -I …`; they read the key from `~/.config/garmin/.env` and never print it.

## No personal data in the repo

This repo is public. Never commit anything from a real person's account, body or location: no real Garmin responses or `.fit` files, user/device IDs, names, emails, serials, GPS or place names, home paths (`/Users/<name>`), or secrets. Fixtures go through the anonymizers above; test routes and maps use made-up places. Dates and physiological values stay in fixtures because tests pin them.

Before committing anything that touches fixtures, docs or tools, both of these must print nothing (`LICENSE` carries the owner's name on purpose). The second reads the owner's own identifiers from a file outside the repo, so never write real identifiers into the repo, even as examples.

```bash
git grep -nIE '/Users/[A-Za-z]+|/home/[a-z]+|BEGIN (RSA|PRIVATE)|storePassword=|eyJ[A-Za-z0-9_-]{20,}\.' -- . ':!CLAUDE.md'
git grep -nIiEf ~/.config/baseline/pii-patterns -- . ':!LICENSE'
```

## Testing on an emulator

AVDs on this machine: `Pixel_9_Pro_XL_API_35` (used for review; it already has the app and data), `Medium_Phone_API_35`, and `Pixel_9` (API 36, but `/data` is nearly full, so installs fail; ask before wiping it).

Useful adb recipes:

```bash
adb shell cmd package compile -m speed -f dev.baseline        # AOT the debug APK; debug cold starts otherwise look broken (empty first frame, missing insets)
adb shell cmd alarm set-timezone Asia/Tokyo                   # zone change with broadcast (Los Angeles -> Tokyo crosses midnight)
adb shell settings put global auto_time 0                     # then: adb shell cmd alarm set-time <epoch-millis>; restore with auto_time 1
adb shell wm density 600                                      # ~358dp-wide screen for narrow-layout checks; 'wm density reset' afterwards
adb shell run-as dev.baseline cat no_backup/androidx.work.workdb > wm.db   # WorkManager state (also copy -wal)
adb shell cmd jobscheduler run -f -n androidx.work.systemjobscheduler dev.baseline <jobId>
```

- `adb shell input tap` calls sent in parallel get merged, so send them one after another.
- The Calendar and Today show an empty placeholder for a moment on cold start before Room's first emission. That's expected.

### Fake Garmin server (no real key needed)

`tools/fake_garmin.py` is a stdlib-only fake of the Garmin Personal API. It serves the anonymized fixtures in `app/src/test/resources/fixtures/` for every endpoint in `GarminEndpoint.kt`, including zipped FIT files for run, ride, swim and strength activities, so key entry, a full backfill, scores, the activity map, Records, Trends and Calendar all work without the owner's key. Use it to smoke-test anything that touches sync, parsing or R8.

**Use this by default for on-device testing. Don't ask the owner for their key.** The real key is only needed to pull new fixtures (`tools/fetch_sample.py`) or to check a change against live Garmin behaviour the fixtures don't cover.

```bash
uv run --python 3.12 --no-project python -I tools/fake_garmin.py            # :8080; --port, --key, --today to change
adb reverse tcp:8080 tcp:8080                                                 # the emulator's 127.0.0.1:8080 now reaches the host
./gradlew :app:installDebug -PgarminBaseUrl=http://127.0.0.1:8080
```

- Use `adb reverse` + `127.0.0.1`, not `10.0.2.2`: on API 37 (`Pixel_10_Pro`) the app couldn't reach 10.0.2.2 (probably Android's local-network restriction; a debug build did the same, so it isn't R8), so key entry just hangs and then says "No connection" while the server sees nothing. `adb reverse` works on every API level and on physical devices. The adb reverse mapping is lost when the emulator restarts.
- The fake API key is `fake-garmin-key`. Paste it in onboarding (or Settings → replace key). Any other key gets a 401, which is how to test the invalid-key banner.
- `-PgarminBaseUrl` sets `BuildConfig.GARMIN_BASE_URL`, turns on cleartext traffic and appends `-fake` to `versionName`. Without it, builds talk to `https://personal.connectapi.garmin.com` (`GarminBaseUrlTest` guards this). **Never release a build made with it.** Uninstall or clear data before going back to the real API: the stored key and synced data belong to the fake.
- A minified release can be pointed at it too: `./gradlew :app:assembleRelease -PgarminBaseUrl=…`. Without `keystore.properties` the APK is unsigned; sign a throwaway copy with `apksigner sign --ks <any test keystore>` to install it. This is the way to smoke-test R8 and the baseline profile.
- **Dates follow today.** The server shifts the fixtures' dates in memory (the files are never modified, and the Robolectric tests still read them with their original dates), so the newest data lands on today and a fresh sync fills today and yesterday.
  - "Today" is fixed when the server starts: the host's date, or `--today YYYY-MM-DD`. **Restart the server** after midnight, and pass `--today` whenever the emulator's date or zone differs from the host's (a pinned clock, or after a `set-timezone` test).
  - Data already synced into the app keeps the dates it was fetched with. After changing `--today`, clear the app's data (`adb shell pm clear dev.baseline`) and re-enter the key, or the old and new days will mix.
  - History moves by whole weeks, so weekdays are preserved. The trailing days up to today repeat the same weekday of the week before, and nothing is dated after today.
  - Ranged endpoints return only the requested window; per-day endpoints (sleep, heart rate, HRV, SpO2) return the one-day fixture re-dated to the requested date. Activities move with the history and the trailing days are filled from the week before the same way, so an activity's date can repeat a week later (with a new id).
  - FIT files keep their original internal timestamps, so an activity's map and charts show the fixture's dates. That's expected.
  - If you add a fixture, give it a `calendarDate` (or `date`) and add it to `RANGED_ROUTES` or `DAY_ROUTES` in `tools/fake_garmin.py`; then run `uv run --python 3.12 --no-project python -I tools/test_fake_garmin.py`.
- Activity types without a FIT fixture get a 404 and show up as failed tasks; that's expected.
- The server logs each request (method, path and status) to stderr, which shows what a sync asked for.

## Known follow-ups

- A long re-score after a sync can exceed WorkManager's ~10-minute limit. The work reruns.
- `stat-label`, `day-highlight` and `day-card` test tags in production composables are deliberate: they mark layout-only nodes with no semantics, and the tests need their bounds.
- Not yet verified on a device: replacing the key with a valid key in Settings, and API 36.
