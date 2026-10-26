# Baseline Android App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A sideloaded Android app that shows the user's Garmin data in a Whoop-style layout: Recovery, Sleep and Strain dials, activities, records, a calendar and trends. Data comes from the Garmin Connect Personal API and is cached on the phone.

**Architecture:**
- One Gradle `app` module, with packages split by responsibility (`garmin`, `data`, `sync`, `fit`, `scores`, `records`, `prefs`, `ui`).
- Every API response is stored raw in Room. Typed rows (`DayEntity`, `ActivityEntity`, streams) are parsed from the raw rows, and derived rows (scores, records) are computed from the typed rows by pure-Kotlin modules.
- Compose screens read only from the `Repository` (`Flow`s), never the network. Background sync runs through WorkManager.

**Tech Stack:** Kotlin, Jetpack Compose with Material 3, Navigation Compose, Room (KSP), DataStore, WorkManager, OkHttp (used directly; the spec's Retrofit is dropped because every call is a raw GET stored as bytes), kotlinx.serialization, Garmin FIT SDK (`com.garmin:fit`), Vico (`com.patrykandpatrick.vico:compose-m3`), MapLibre (`org.maplibre.gl:android-sdk`), `sh.calvin.reorderable:reorderable`. Tests use JUnit 4, MockWebServer, kotlinx-coroutines-test and Robolectric.

**Spec:** `docs/superpowers/specs/2026-10-24-baseline-android-design.md`. Section numbers in this plan (§n) refer to the spec.

## Global Constraints

**Build and project setup**
- `applicationId` and root package: `dev.baseline`. Single module `:app`. compileSdk 37, targetSdk 37, minSdk 29. Java/Kotlin bytecode target 17. (SDK platforms `android-37.0` and `android-37.2` and build-tools 37.0.0 are installed.)
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` for every Gradle command. No system JDK is installed.
- Dependency versions: for each library, use the **latest stable** release (no alpha, beta or rc), with no age gate. Pin it in `gradle/libs.versions.toml`. Later tasks that add a library use its latest stable at that time.
  - Gradle is the newest version supported by the chosen AGP.
  - The Compose compiler plugin is `org.jetbrains.kotlin.plugin.compose` at the Kotlin version.
  - If the chosen AGP has built-in Kotlin (AGP 9+), do not apply `org.jetbrains.kotlin.android`.
- No other libraries beyond the Tech Stack list without asking.
- No dependency-injection framework. Wiring lives in `dev.baseline.AppContainer`, owned by `BaselineApp : Application`.

**Network and the API key**
- Base URL `https://personal.connectapi.garmin.com`.
- Token mint: `POST /personalapitoken-service/personal-api/personal-api-key/token` with headers `Authorization: ApiKey <key>` and `Accept: */*`. The response body is the JWT.
- Data calls send `Authorization: Bearer <jwt>`.
- FIT download sends `Accept: application/zip, application/x-zip-compressed, application/octet-stream` exactly.
- At most 1 request per second, serial.
- Never log the API key or the JWT. OkHttp logging is debug-only and must redact `Authorization`.

**Time handling (§2.1)**
- Interval maths uses GMT instants.
- `*Local` epoch values are parsed as `LocalDateTime` at UTC, and are used only for display and choosing a calendar date.
- An activity's day = the date part of `startTimeLocal`. A sleep's day = its `calendarDate`.

**Look**
- Dark theme only: background `#0E1013`, card `#171A1F`.
- Recovery colours: green `#34D399` (≥67), amber `#FBBF24` (34–66), red `#F87171` (<34). Strain `#60A5FA`. Sleep `#A78BFA`.
- Fonts: Barlow Condensed (numbers and headings), Inter (body text).
- Touch targets ≥ 48 dp. Every dial and chart has a `contentDescription`.

**Data and testing rules**
- Test fixtures live under `app/src/test/resources/fixtures/`. They are produced by `tools/make_fixtures.py`, with names and profile image URLs scrubbed. The repo stays private.
- Missing data is shown as missing, never as a placeholder number. No `NaN` may reach the UI or the DB.

## Review Focus

1. **DST and travel days.** On 2026-11-01, US clocks fall back. An activity at local 01:30 on that date must land on Nov 1. A sleep is filed under its own `calendarDate`, never one derived from timestamps. Pinned in Tasks 2 and 6.
2. **Key expires mid-backfill.** A 401 after re-mint pauses sync without losing any `done` task. After a new key is saved, sync resumes from the first `pending` task, and completed tasks are not re-fetched. Pinned in Task 9.
3. **Nights or days with no data.** The watch wasn't worn, there's no HRV (the fixture has 130 HRV nights in 180 days), or the HR array has `null` values. Recovery becomes partial or absent, strain becomes 0, and there is never a `NaN`. Pinned in Tasks 11–13.
4. **Activities edited or deleted in Garmin Connect after a sync.** Incremental sync re-reads the last 7 days of activities by date. It updates changed rows and deletes local activities in that window that Garmin no longer returns. Pinned in Task 9.
5. **Process killed or app updated mid-backfill.** Tasks persist. Raw rows upsert by `(endpoint, key)`, so re-running a task never duplicates data. Pinned in Tasks 5 and 9.

---

## Phase 1 — Skeleton

### Task 1: Project scaffold, theme and navigation shell

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, `.gitignore`, `local.properties` (gitignored, `sdk.dir=/Users/<you>/Library/Android/sdk`), `gradlew`, `gradlew.bat`, `gradle/wrapper/*`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/dev/baseline/BaselineApp.kt`, `AppContainer.kt`, `MainActivity.kt`
- Create: `app/src/main/java/dev/baseline/ui/theme/{Color.kt,Type.kt,Theme.kt}`, `ui/nav/BaselineNavHost.kt`
- Create: `app/src/main/res/font/` (Barlow Condensed 500/600/700, Inter 400/500/600, TTFs from github.com/google/fonts under the OFL)
- Test: `app/src/test/java/dev/baseline/ui/nav/NavShellTest.kt` (Robolectric with compose-ui-test)

**Interfaces:**
- Produces:
  - `object Routes { const val TODAY="today"; const val ACTIVITIES="activities"; const val TRENDS="trends"; const val SETTINGS="settings"; const val CALENDAR="calendar"; const val SLEEP="sleep/{date}"; const val ACTIVITY="activity/{id}"; const val RECORDS="records"; const val ONBOARDING="onboarding" }`
  - `BaselineTheme { }`, plus colour constants `RecoveryGreen`, `RecoveryAmber`, `RecoveryRed`, `StrainBlue`, `SleepPurple`, `Bg`, `Card`
  - `AppContainer(context: Context)` (empty for now)

- [ ] **Step 1: Bootstrap the Gradle wrapper**

  Pick versions using the Global Constraints rule. Download `gradle-<ver>-bin.zip` from `https://services.gradle.org/distributions/` into the session scratchpad (not the repo). Unzip it and run `<unzipped>/bin/gradle wrapper --gradle-version <ver>` in the repo root with `JAVA_HOME` set.

  Expected: `./gradlew --version` prints `<ver>`.

- [ ] **Step 2: Write `.gitignore`** with these lines: `.gradle/`, `build/`, `local.properties`, `.idea/`, `*.iml`, `.DS_Store`, `/tools/out/`, `captures/`, `.kotlin/`.

- [ ] **Step 3: Write the failing test `NavShellTest.bottomBarHasFourTabs`.** It launches `MainActivity` content with Robolectric and asserts:
  - nodes with text `Today`, `Activities`, `Trends` and `Settings` exist
  - clicking `Trends` shows a node with the test tag `screen-trends`

- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest --tests dev.baseline.ui.nav.NavShellTest`. Expected: FAIL (it doesn't compile).

- [ ] **Step 5: Implement the shell.**
  - `MainActivity` sets `BaselineTheme { BaselineNavHost() }`.
  - `BaselineNavHost` is a `Scaffold` with a 4-item `NavigationBar` and a placeholder screen for each route. Each placeholder is tagged `screen-<route>`.
  - Fonts are a `FontFamily` built from the res/font files.

- [ ] **Step 6: Run** the test, then `./gradlew :app:assembleDebug`. Expected: PASS and `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit:** `git add -A && git commit -m "chore: scaffold Android app with theme and nav shell"`

### Task 2: Time base and sport families

**Files:**
- Create: `app/src/main/java/dev/baseline/garmin/GarminTime.kt`, `app/src/main/java/dev/baseline/data/SportFamily.kt`
- Test: `app/src/test/java/dev/baseline/garmin/GarminTimeTest.kt`, `app/src/test/java/dev/baseline/data/SportFamilyTest.kt`

**Interfaces:**
- Produces:
  - `object GarminTime`, with:
    - `fun gmtEpochMs(ms: Long): Instant`
    - `fun localEpochMs(ms: Long): LocalDateTime` (interprets the value as UTC fields)
    - `fun parseLocal(s: String): LocalDateTime`, which accepts `"2025-08-30 10:24:33"` and `"2026-03-26T17:23:53.0"`
    - `fun parseGmt(s: String): Instant`, same accepted formats, read as UTC
    - `fun activityDay(startTimeLocal: String): LocalDate`
  - `enum class SportFamily { RUN, RIDE, SWIM, STRENGTH, OTHER; companion object { fun of(typeKey: String): SportFamily } }`
  - `fun SportFamily.countsForDistanceRecords(typeKey: String): Boolean`. It returns false for `treadmill_running`, `virtual_ride`, `indoor_cycling` and any `open_water_swimming`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun localEpochIsWallClock() {
  // sleepStartTimestampLocal from fixture sleep_day.json; GMT is 1792734761000
  assertEquals(LocalDateTime.of(2026,10,22,22,52,41), GarminTime.localEpochMs(1792709561000))
  assertEquals(Instant.parse("2026-10-23T05:52:41Z"), GarminTime.gmtEpochMs(1792734761000))
}
@Test fun dstFallBackActivityStaysOnItsLocalDate() {
  assertEquals(LocalDate.of(2026,11,1), GarminTime.activityDay("2026-11-01 01:30:00"))
}
@Test fun familiesCoverUserTypes() {
  mapOf("running" to RUN, "trail_running" to RUN, "track_running" to RUN, "treadmill_running" to RUN,
        "road_biking" to RIDE, "virtual_ride" to RIDE, "indoor_cycling" to RIDE,
        "lap_swimming" to SWIM, "strength_training" to STRENGTH, "pickleball" to OTHER,
        "walking" to OTHER, "never_seen_before" to OTHER)
    .forEach { (k, f) -> assertEquals(k, f, SportFamily.of(k)) }
  assertFalse(RUN.countsForDistanceRecords("treadmill_running"))
  assertTrue(RIDE.countsForDistanceRecords("road_biking"))
}
```

- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests 'dev.baseline.garmin.GarminTimeTest' --tests 'dev.baseline.data.SportFamilyTest'`. Expected: FAIL.

- [ ] **Step 3: Implement.** Use the full typeKey table from §2.2. `java.time` only.

- [ ] **Step 4: Run** the tests. Expected: PASS.

- [ ] **Step 5: Commit:** `git commit -am "feat: Garmin time base and sport families"` (use `git add` for new files first)

### Task 3: API key vault and user prefs

**Files:**
- Create: `app/src/main/java/dev/baseline/garmin/ApiKeyVault.kt`, `app/src/main/java/dev/baseline/garmin/KeystoreAead.kt`, `app/src/main/java/dev/baseline/prefs/UserPrefs.kt`
- Test: `app/src/test/java/dev/baseline/garmin/ApiKeyVaultTest.kt`, `app/src/androidTest/java/dev/baseline/garmin/KeystoreAeadTest.kt`

**Interfaces:**
- Produces:
  - `class KeystoreAead(alias: String = "baseline-key")` with `fun encrypt(plain: ByteArray): ByteArray` and `fun decrypt(blob: ByteArray): ByteArray`. It uses AES-256-GCM from the Android Keystore; the blob is IV (12 bytes) followed by the ciphertext.
  - `class ApiKeyVault(store: DataStore<Preferences>, encrypt: (ByteArray) -> ByteArray, decrypt: (ByteArray) -> ByteArray, clock: Clock)`, with:
    - `suspend fun save(key: String, lifetimeDays: Int)`, where lifetimeDays ∈ {30, 90, 180, 365} and expiry = today + lifetimeDays
    - `suspend fun key(): String?`
    - `suspend fun clear()`
    - `val status: Flow<KeyStatus>`
  - `sealed interface KeyStatus { data object Missing; data class Ok(val expires: LocalDate, val daysLeft: Int); data class ExpiringSoon(val expires: LocalDate, val daysLeft: Int); data class Expired(val expires: LocalDate); data object Invalid }`
    - `ExpiringSoon` applies when `daysLeft <= 7`. `Expired` applies when today > expires.
    - `Invalid` is set by `suspend fun markInvalid()` and cleared by `save`.
  - `class UserPrefs(store: DataStore<Preferences>)` with flows and setters:
    - `baselineOrder: Flow<List<String>>`, default `listOf("hrv","rhr","sleep","resp","bb","stress","steps")`
    - `hiddenRows: Flow<Set<String>>`
    - `syncInterval: Flow<SyncInterval>`, where `enum SyncInterval { HOURLY, THREE_HOURS, MANUAL }`, default `HOURLY`
    - `banister: Flow<BanisterSet>`, where `enum BanisterSet(val a: Double, val b: Double) { MALE_FITTED(0.64, 1.92), FEMALE_FITTED(0.86, 1.67) }`, default `MALE_FITTED`
    - `strainTau: Flow<StrainCalibration?>`
    - `cachedJwt: Flow<ByteArray?>`, encrypted

- [ ] **Step 1: Write the failing JVM test `ApiKeyVaultTest`.** It uses an in-memory `PreferenceDataStoreFactory` on a temp file, identity encrypt/decrypt, and a fixed clock at 2026-10-24. It covers:
  - `save("k", 365)` → status `Ok(2027-10-24, 365)`
  - with the clock moved to 2027-10-18 → `ExpiringSoon(…, 6)`
  - with the clock at 2027-10-25 → `Expired`
  - `markInvalid()` → `Invalid`
  - a second `save` → `Ok`
  - `key()` round-trips
  - the stored preference bytes do not contain the plaintext key when using a XOR stub cipher

- [ ] **Step 2: Run** the test. Expected: FAIL.

- [ ] **Step 3: Implement** `ApiKeyVault`, `UserPrefs` and `KeystoreAead`. Use `KeyGenParameterSpec` with `PURPOSE_ENCRYPT|PURPOSE_DECRYPT`, `BLOCK_MODE_GCM`, `ENCRYPTION_PADDING_NONE`, 256 bits.

- [ ] **Step 4: Write `KeystoreAeadTest`** (instrumented). It checks that encrypting then decrypting returns the input, and that two encryptions of the same input differ.

- [ ] **Step 5: Run** `./gradlew :app:testDebugUnitTest --tests 'dev.baseline.garmin.ApiKeyVaultTest'`. Expected: PASS. Run `./gradlew :app:connectedDebugAndroidTest` with the phone attached (or the emulator). Expected: PASS.

- [ ] **Step 6: Commit:** `"feat: encrypted API key vault and user prefs"`

### Task 4: Garmin HTTP client (token, auth, endpoints)

**Files:**
- Create: `app/src/main/java/dev/baseline/garmin/{TokenProvider.kt,GarminAuth.kt,GarminEndpoint.kt,GarminClient.kt}`
- Test: `app/src/test/java/dev/baseline/garmin/GarminClientTest.kt`

**Interfaces:**
- Consumes: `ApiKeyVault.key()`, `ApiKeyVault.markInvalid()`, `UserPrefs.cachedJwt`
- Produces:
  - `enum class GarminEndpoint(val maxDays: Int)`. Values:
    - `PROFILE(0)`, `HR_ZONES(0)`
    - `DAILY_SUMMARIES(180)`, `HRV_RANGE(180)`
    - `SLEEP_SUMMARIES(28)`, `BODY_BATTERY(28)`
    - `STRESS(7)`, `STEPS(7)`
    - `SLEEP_DAY(1)`, `HEART_RATE_DAY(1)`, `HRV_DAY(1)`, `SPO2_DAY(1)`
    - `ACTIVITY_PAGE(0)`, `ACTIVITY_RANGE(7)`, `ACTIVITY_DETAIL(0)`, `FIT(0)`

    It has `fun request(key: String): GarminRequest`, where `key` is `"yyyy-MM-dd"`, `"start..end"`, a page index, or an activity id. Paths and query parameters exactly as in the §2 table. `ACTIVITY_RANGE` = activity search with `startDate`, `endDate`, `limit=50`.
  - `data class GarminRequest(val path: String, val query: Map<String,String>, val accept: String)`
  - `sealed interface GarminResult { data class Ok(val body: ByteArray, val contentType: String?); data object Empty; data class RateLimited(val code: Int); data class BadRequest(val body: String); data object KeyInvalid; data class Failed(val code: Int?, val message: String) }`
    - `Empty` = 204, or a 200 whose body is empty or `{"data":[]}`.
    - `RateLimited` covers 429 and 5xx.
  - `class GarminClient(baseUrl: HttpUrl, http: OkHttpClient, tokens: TokenProvider)` with `suspend fun fetch(endpoint: GarminEndpoint, key: String): GarminResult`. It uses OkHttp directly; no Retrofit interface is needed, because every call is a raw GET (Retrofit is dropped from the stack).
  - `class TokenProvider(baseUrl: HttpUrl, mintHttp: OkHttpClient, vault: ApiKeyVault, prefs: UserPrefs, clock: Clock)` with:
    - `suspend fun token(): String` — mints when there's no token, or fewer than 60 minutes remain on the JWT `exp`
    - `suspend fun forceRefresh(): String`
    - both throw `KeyInvalidException` on a 401 or 403 during mint, after calling `vault.markInvalid()`
  - `class GarminAuthenticator(tokens)`: an OkHttp `Authenticator` that re-mints once per request (it checks `response.priorResponse == null`) and otherwise returns `null`.

- [ ] **Step 1: Write the failing tests** (MockWebServer):

```kotlin
@Test fun mintsWithApiKeyAndSendsBearer()      // 1st request POST token path, header "ApiKey k"; 2nd GET has "Bearer <jwt>"
@Test fun reusesTokenUntil60MinutesBeforeExp() // JWT exp = now+2h -> one mint for two calls; exp = now+59m -> re-mint
@Test fun reMintsOnceOn401ThenSucceeds()       // GET 401, mint, GET 200 -> Ok; exactly 2 mints
@Test fun secondConsecutive401IsKeyInvalid()   // GET 401, mint ok, GET 401 -> KeyInvalid and vault status Invalid
@Test fun emptyDataAnd204AreEmpty()            // 204 -> Empty; 200 {"data":[]} -> Empty
@Test fun rateLimitAnd5xxAreRateLimited()      // 429 -> RateLimited(429); 503 -> RateLimited(503)
@Test fun fitUsesExactAcceptHeader()           // request header Accept == "application/zip, application/x-zip-compressed, application/octet-stream"
@Test fun endpointPathsMatchSpec()             // e.g. HRV_RANGE.request("2026-04-27..2026-10-23").path == "/hrv-service/hrv/daily/2026-04-27/2026-10-23"
                                               // SLEEP_DAY.request("2026-10-23") == path "/sleep-service/sleep/dailySleep", query {date=2026-10-23}
                                               // ACTIVITY_PAGE.request("2").query == {start=100, limit=50}
@Test fun authorizationNeverLogged()           // HttpLoggingInterceptor configured via GarminClient.loggingInterceptor() redacts "Authorization"
```

  Test JWTs are built with a helper that base64url-encodes `{"exp":<sec>}`.

- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests 'dev.baseline.garmin.GarminClientTest'`. Expected: FAIL.

- [ ] **Step 3: Implement.** Decode the JWT `exp` with `java.util.Base64.getUrlDecoder()` on the second segment. A `Mutex` serialises minting.

- [ ] **Step 4: Run** the tests. Expected: PASS.

- [ ] **Step 5: Commit:** `"feat: Garmin Personal API client with token handling"`

### Task 5: Room database and raw storage

**Files:**
- Create: `app/src/main/java/dev/baseline/data/db/{AppDatabase.kt,Entities.kt,Daos.kt,Converters.kt}`
- Test: `app/src/test/java/dev/baseline/data/db/RawAndTaskDaoTest.kt` (Robolectric, in-memory Room)

**Interfaces:**
- Produces these entities (all in `Entities.kt`):
  - `RawResponse(@PK endpoint: String, @PK key: String, json: ByteArray, fetchedAt: Instant)`
  - `SyncTaskEntity(@PK endpoint: String, @PK key: String, priority: Int, status: TaskStatus, attempts: Int, lastError: String?)`, where `enum TaskStatus { PENDING, DONE, EMPTY, FAILED }`
  - `DayEntity`, with the §6 `day` columns as nullable fields. `date: LocalDate` is the PK.
    - `sleepNeedMinutes` = that night's `sleepNeed.actual`.
    - Also has `nextSleepNeedBaselineMin: Int?`, `nextSleepNeedActualMin: Int?`, `nextSleepNeedReasons: String?` (comma-joined `key=VALUE` for the adjustment fields whose value ≠ `NO_CHANGE`) and `recommendedBedtimeMin: Int?`. All four come from `nextSleepNeed`.
  - `HrDayEntity(@PK date: LocalDate, packed: ByteArray)` — pairs of (epochSec Int delta from midnight GMT of the first sample, bpm Byte). Null samples are skipped.
  - `ActivityEntity`, with the §6 columns. It also has `typeKey`, `fastestSplitsJson`, `fitPath: String?`, `hrCoverage: Double?` and `poolLengthM: Double?`.
  - `ActivityStreamEntity(@PK activityId: Long, packed: ByteArray)`
  - `LapEntity`, `SwimLengthEntity`, `StrengthSetEntity` (FK `activityId`, plus an index)
  - `ScoreEntity(@PK date, recovery: Int?, recoveryPartial: Boolean, sleepPerformance: Double?, dayLoad: Double, strain: Double, strainTargetLow: Double?, strainTargetHigh: Double?, calibrationVersion: Int)`
  - `ActivityScoreEntity(@PK activityId, load: Double, loadSource: LoadSource, strain: Double)`, where `enum LoadSource { HR, GARMIN_SCALED }`
  - `BestEffortEntity(@PK sport: SportFamily, @PK effort: String, valueSec: Double, activityId: Long, achievedOn: LocalDate)`, where `effort` is e.g. `"5000m"` or `"power_1200s"`
- Produces these DAOs:
  - `RawDao.upsert(r)`, `RawDao.get(endpoint, key): RawResponse?`, `RawDao.keys(endpoint): List<String>`
  - `TaskDao.insertIgnore(tasks)`, `TaskDao.nextPending(): SyncTaskEntity?` (lowest priority, then key DESC), `TaskDao.update(t)`, `TaskDao.counts(): Flow<TaskCounts(done, total)>`, `TaskDao.resetFailed()`
  - `DayDao.upsert`, `DayDao.range(from, to): Flow<List<DayEntity>>`, `DayDao.get(date)`
  - `ActivityDao.upsert`, `ActivityDao.byDay(date)`, `ActivityDao.all(): Flow<List<ActivityEntity>>`, `ActivityDao.idsBetween(from, to)`, `ActivityDao.delete(ids)`, `ActivityDao.maxId()`
  - Matching simple DAOs for the stream, lap, length, set, score, activity-score and best-effort tables.
- `AppDatabase.create(context)`, version 1, with `exportSchema = true` to `app/schemas`.

- [ ] **Step 1: Write the failing tests:**
  - `upsertRawTwiceKeepsOneRow`
  - `insertIgnoreDoesNotResetDoneTask` — insert a PENDING task, mark it DONE, insertIgnore the same task → still DONE
  - `nextPendingOrdersByPriorityThenNewestKey` — keys `2026-10-22` and `2026-10-23` at the same priority → `2026-10-23` comes first
  - `hrPackingRoundTrips` — 720 samples from `fixtures/heartrate_day.json`, with 1 null → 719 back

- [ ] **Step 2: Run.** Expected: FAIL.

- [ ] **Step 3: Implement** the entities, DAOs, converters (LocalDate, Instant and enums as strings or epoch) and `HrPacking.pack/unpack` in `Converters.kt`.

- [ ] **Step 4: Run.** Expected: PASS.

- [ ] **Step 5: Commit:** `"feat: Room schema for raw, typed and derived data"`

### Task 6: Fixtures and raw→typed parsers

**Files:**
- Create: `tools/fetch_sample.py`, `tools/make_fixtures.py`
- Create: `app/src/test/resources/fixtures/` (generated)
- Create: `app/src/main/java/dev/baseline/data/parse/{DayParser.kt,ActivityParser.kt}`
- Test: `app/src/test/java/dev/baseline/data/parse/{DayParserTest.kt,ActivityParserTest.kt}`

**Interfaces:**
- Consumes: `GarminTime`, `SportFamily`, entities from Task 5
- Produces:
  - `object DayParser`, with merge functions that return updated copies (missing fields stay null):
    - `fun mergeDailySummary(e: DayEntity?, item: JsonObject): DayEntity`
    - `fun mergeSleepDay(e: DayEntity?, json: JsonObject): DayEntity`
    - `fun mergeSleepSummary(e: DayEntity?, item: JsonObject): DayEntity`
    - `fun mergeHrv(e: DayEntity?, summary: JsonObject): DayEntity`
    - `fun mergeBodyBattery(e: DayEntity?, item: JsonObject, sleepEndGmt: Instant?): DayEntity`
    - `fun mergeStress(e: DayEntity?, item: JsonObject): DayEntity`
    - `fun mergeSteps(e: DayEntity?, item: JsonObject): DayEntity`
    - `fun parseHeartRateDay(json: JsonObject): Pair<LocalDate, List<HrPoint>>`
  - `object ActivityParser` with `fun parseListItem(o: JsonObject): ActivityEntity`
  - `data class HrPoint(val epochSec: Long, val bpm: Int)`, in `dev.baseline.scores`

- [ ] **Step 1: Write `tools/fetch_sample.py`.** Port the phase-0 script at the phase-0 `dump.py` script. If that file is gone, rewrite it from §2.
  - It reads `API_KEY` from `~/.config/garmin/.env` and never prints it. It writes to `tools/out/sample/`.
  - It fetches:
    - all range endpoints for the whole history, from 2025-08-30 to yesterday, in max-size windows
    - one day each of the per-day endpoints (2026-10-23)
    - all activity pages
    - FITs for one activity each of: running, road_biking, virtual_ride, indoor_cycling, lap_swimming and strength_training (the strength one is activity `22311621604`)
  - It sleeps 1 s between requests.
  - Run it with: `~/.local/bin/uv run --python 3.12 --no-project python -I tools/fetch_sample.py`

- [ ] **Step 2: Write `tools/make_fixtures.py <sampleDir>`.**
  - It merges range chunks into `dailysummaries_history.json` (`{"data":[…]}`), `hrv_history.json` (`{"hrvSummaries":[…]}`) and `sleep_summaries_history.json` (`[…]`).
  - It copies the single-day files, `activities_all.json` and the FITs as `fit/{run,road_ride,virtual_ride,indoor_ride,swim,strength}.fit`.
  - It deletes the keys `ownerFullName`, `ownerDisplayName`, `displayName` and `fullName`, and every key starting with `ownerProfileImageUrl`, recursively.
  - Run it.

  Expected: the fixtures directory exists, and `grep -rl ownerFullName app/src/test/resources/fixtures` prints nothing.

- [ ] **Step 3: Write the failing tests** with exact values from the 2026-10-23 fixtures:

```kotlin
@Test fun sleepDay() { val d = DayParser.mergeSleepDay(null, fixture("sleep_day.json"))
  assertEquals(LocalDate.of(2026,10,23), d.date); assertEquals(31740, d.sleepSeconds)
  assertEquals(3780, d.deepSeconds); assertEquals(20640, d.lightSeconds); assertEquals(7320, d.remSeconds); assertEquals(0, d.awakeSeconds)
  assertEquals(87, d.sleepScore); assertEquals(490, d.sleepNeedMinutes); assertEquals(12.0, d.respirationAvg!!, 0.0)
  assertEquals(97.0, d.spo2Avg!!, 0.0); assertEquals(Instant.ofEpochMilli(1792766501000), d.sleepEnd) }
@Test fun hrvDay() { val d = DayParser.mergeHrv(null, fixture("hrv_day.json")["hrvSummary"]!!.jsonObject)
  assertEquals(96, d.hrvLastNight); assertEquals(83, d.hrvWeeklyAvg); assertEquals(89, d.hrvBalancedLow); assertEquals(102, d.hrvBalancedUpper) }
@Test fun dailySummary() { val item = fixture("dailysummaries_history.json")["data"]!!.jsonArray.map{it.jsonObject}.first{ it["calendarDate"]!!.jsonPrimitive.content=="2026-10-23" }
  val d = DayParser.mergeDailySummary(null, item); assertEquals(49, d.restingHr); assertEquals(32, d.stressAvg) }
@Test fun heartRateDaySkipsNulls() { val (day, pts) = DayParser.parseHeartRateDay(fixture("heartrate_day.json"))
  assertEquals(LocalDate.of(2026,10,23), day); assertEquals(719, pts.size); assertEquals(120, pts[1].epochSec - pts[0].epochSec) }
@Test fun nextSleepNeed() { val d = DayParser.mergeSleepDay(null, fixture("sleep_day.json"))
  assertEquals(460, d.nextSleepNeedBaselineMin); assertEquals(460, d.nextSleepNeedActualMin); assertEquals(1420, d.recommendedBedtimeMin)
  assertEquals("sleepHistoryAdjustment=DECREASING,hrvAdjustment=INCREASING", d.nextSleepNeedReasons) }
@Test fun sleepFiledUnderCalendarDate() { /* sleep_day with sleepStart on 2026-10-22 local -> date 2026-10-23 (its calendarDate) */ }
@Test fun mergeKeepsExistingFields() { /* sleep then summary for same date: both sleepSeconds and restingHr non-null */ }
@Test fun activityListItem() { val a = fixture("activities_all.json").jsonArray.map{ActivityParser.parseListItem(it.jsonObject)}
  assertEquals(175, a.size); assertEquals(90, a.count{it.sportFamily==RUN}); assertEquals(36, a.count{it.sportFamily==RIDE})
  val s = a.first{it.id==22311621604}; assertEquals(STRENGTH, s.sportFamily); assertEquals(2.88, s.garminLoad!!, 0.01)
  assertEquals(LocalDate.of(2025,8,30), a.minOf{ it.day }) }
```

  Notes on the expected values:
  - `restingHr` from the daily summary wins over the heart-rate-day `restingHeartRate`; both are 49 on this date.
  - `bodyBatteryWake` = the level of the `bodyBatteryValuesArray` sample nearest `sleepEnd`, with null levels skipped.

- [ ] **Step 4: Run** the parser tests. Expected: FAIL.

- [ ] **Step 5: Implement** with `kotlinx.serialization.json` (`Json { ignoreUnknownKeys = true }`, read through the `JsonObject` tree). Every field is optional.

- [ ] **Step 6: Run.** Expected: PASS.

- [ ] **Step 7: Commit** the fixtures, tools and parsers: `"feat: fixtures and Garmin JSON parsers"`

### Task 7: Repository, onboarding and raw Today (phase 1 deliverable)

**Files:**
- Create: `app/src/main/java/dev/baseline/data/Repository.kt`, `app/src/main/java/dev/baseline/sync/SyncEngine.kt` (only `syncDay` for now)
- Create: `app/src/main/java/dev/baseline/ui/onboarding/{OnboardingScreen.kt,OnboardingViewModel.kt}`, `ui/today/{TodayScreen.kt,TodayViewModel.kt}` (raw values version)
- Modify: `AppContainer.kt` (wire db, vault, prefs, client, repository), `ui/nav/BaselineNavHost.kt` (start at ONBOARDING when `KeyStatus.Missing`)
- Test: `app/src/test/java/dev/baseline/ui/onboarding/OnboardingViewModelTest.kt`

**Interfaces:**
- Consumes: `GarminClient.fetch`, `RawDao`, `DayParser`, `ApiKeyVault`
- Produces:
  - `class Repository(db, prefs, vault)` with:
    - `fun day(date): Flow<DayEntity?>`
    - `fun activitiesOn(date): Flow<List<ActivityEntity>>`
    - `fun score(date): Flow<ScoreEntity?>`
    - `val keyStatus: Flow<KeyStatus>`
    - `val syncProgress: Flow<TaskCounts>`
  - `class SyncEngine(client, db, clock, pause: suspend (Long) -> Unit)` with:
    - `suspend fun testConnection(): GarminResult` (calls PROFILE)
    - `suspend fun syncDay(date: LocalDate)` (SLEEP_DAY, HRV_DAY, HEART_RATE_DAY, DAILY_SUMMARIES `date..date`; stores raw, then parses into `DayEntity`)
  - `OnboardingViewModel(vault, engine)` with `fun submit(key: String, lifetimeDays: Int)` and `state: StateFlow<OnboardingState { Idle, Testing, Error(msg), Done }>`.
    - Saving happens only after `testConnection()` returns `Ok`.
    - On failure the vault is cleared.

- [ ] **Step 1: Write the failing test** `submitSavesOnlyAfterSuccessfulTest`. It uses a fake engine returning `KeyInvalid` → state `Error`, `vault.key()==null`; then `Ok` → state `Done`, `vault.key()=="k"`.

- [ ] **Step 2: Run.** Expected: FAIL.

- [ ] **Step 3: Implement.**
  - Onboarding UI: a password field labelled "Personal API key"; a lifetime picker with 30/90/180/365 chips (default 365); a link to `https://connect.garmin.com/app/settings/personalApiToken`; a "Save and test" button.
  - Raw Today: a list of `DayEntity` fields for today and yesterday.

- [ ] **Step 4: Run** the tests and `./gradlew :app:installDebug` with the phone connected.

  Expected: PASS. On the phone, entering the key shows real numbers: yesterday's sleep in hours, HRV and resting HR.

- [ ] **Step 5: Commit:** `"feat: onboarding and first live sync"`. Then ask the user to review phase 1 on the phone before continuing.

## Phase 2 — Sync

### Task 8: Backfill planner

**Files:**
- Create: `app/src/main/java/dev/baseline/sync/BackfillPlanner.kt`
- Test: `app/src/test/java/dev/baseline/sync/BackfillPlannerTest.kt`

**Interfaces:**
- Consumes: `GarminEndpoint`, `SyncTaskEntity`
- Produces:
  - `object BackfillPlanner` with `fun plan(start: LocalDate, end: LocalDate): List<SyncTaskEntity>`
  - Priorities follow §5.1 order:
    - 1 = PROFILE, HR_ZONES
    - 2 = DAILY_SUMMARIES, HRV_RANGE
    - 3 = SLEEP_SUMMARIES, BODY_BATTERY
    - 4 = STRESS, STEPS
    - 5 = ACTIVITY_PAGE `"0"`
    - 6 = SLEEP_DAY and HEART_RATE_DAY for each date
    - 7 = FIT (added later by the engine)
  - Range keys are `"start..end"` windows tiled backwards from `end`, each ≤ `maxDays` days inclusive.
  - `val HISTORY_START = LocalDate.of(2025,8,30)` (the earliest data; the engine stops paging older when windows return Empty).

- [ ] **Step 1: Write the failing tests** for start 2025-08-30 and end 2026-10-23 (420 days):
  - `windowsNeverExceedMax` — for each range task, `days(key) <= endpoint.maxDays`
  - `windowsTileWithoutGapsOrOverlap` — the union of each endpoint's windows equals the full span exactly
  - `taskCountsMatchSpec` — DAILY_SUMMARIES 3, HRV_RANGE 3, SLEEP_SUMMARIES 15, BODY_BATTERY 15, STRESS 60, STEPS 60, SLEEP_DAY 420, HEART_RATE_DAY 420, ACTIVITY_PAGE 1, PROFILE 1, HR_ZONES 1
  - `perDayTasksAreNewestFirstWithinPriority` — the first `nextPending` at priority 6 is `2026-10-23`

- [ ] **Step 2: Run.** Expected: FAIL.

- [ ] **Step 3: Implement.**

- [ ] **Step 4: Run.** Expected: PASS.

- [ ] **Step 5: Commit:** `"feat: backfill planner"`

### Task 9: Sync engine (backfill and incremental)

**Files:**
- Modify: `app/src/main/java/dev/baseline/sync/SyncEngine.kt`
- Create: `app/src/main/java/dev/baseline/sync/RawIngest.kt` (raw row → typed tables, for each endpoint)
- Test: `app/src/test/java/dev/baseline/sync/SyncEngineTest.kt` (Robolectric Room, with a fake `GarminClient` returning scripted results)

**Interfaces:**
- Consumes: `BackfillPlanner`, `GarminClient`, DAOs, `DayParser`, `ActivityParser`, `FitReader` (Task 10; it uses a `fitIngest: suspend (Long, ByteArray) -> Unit` lambda so it can be tested before Task 10)
- Produces:
  - `suspend fun SyncEngine.ensureBackfillPlanned(today: LocalDate)` — calls `insertIgnore` on the plan the first time only. A pref flag records this.
  - `suspend fun SyncEngine.runPending(onProgress: (TaskCounts) -> Unit): SyncOutcome`, where `enum SyncOutcome { COMPLETED, RATE_LIMITED, KEY_INVALID, OFFLINE }`.
  - `suspend fun SyncEngine.planIncremental(today: LocalDate)` — inserts or replaces PENDING tasks with priority 0 for:
    - range endpoints over `today-6..today`
    - SLEEP_DAY, HEART_RATE_DAY and HRV_DAY for today and yesterday
    - ACTIVITY_RANGE `today-6..today`
  - `RawIngest.apply(endpoint, key, json)` — dispatches to parsers and upserts typed rows. A parse exception leaves the raw row stored and marks the task FAILED, with `lastError` set to the exception message. For an ACTIVITY_PAGE that returns 50 items, it adds `ACTIVITY_PAGE (n+1)`. For each new activity id it adds a `FIT` task at priority 7. For ACTIVITY_RANGE it deletes local activities in the window that are absent from the response.

**Rules from §5:**
- Wait 1000 ms between requests (`pause(1000)`).
- On `RateLimited`, wait 60 000 ms, then 120 000 ms, then 240 000 ms. On the 4th consecutive `RateLimited`, return `RATE_LIMITED` with the task still PENDING.
- `Empty` → status EMPTY.
- `BadRequest` on a range → replace it with two halves once (`attempts` records that a split happened). On a non-range task → FAILED.
- `KeyInvalid` → return `KEY_INVALID` immediately; the task stays PENDING.
- A network `IOException` → return `OFFLINE`.

- [ ] **Step 1: Write the failing tests:**
  - `backoffSequenceThenStop` — four 429s → `pause` called with `[60000, 120000, 240000]` (plus the 1000 ms spacers), outcome RATE_LIMITED, task PENDING
  - `keyExpiresMidBackfillResumesWithoutRefetch` — 5 tasks; the 3rd returns KeyInvalid → outcome KEY_INVALID, 2 DONE. On a second run with the client OK, only 3 fetches happen.
  - `rerunningDoneTaskDoesNotDuplicate` — apply the same raw twice → one `RawResponse`, one `DayEntity`
  - `fullActivityPageQueuesNextPageAndFits` — page 0 with 50 items → `ACTIVITY_PAGE "1"` pending, plus 50 FIT tasks
  - `badRequestSplitsRangeOnce`
  - `malformedJsonMarksFailedKeepsRaw` — the client returns `Ok("{not json")` → `RawResponse` stored, task FAILED, and the engine continues to the next task
  - `activityRangeDeletesMissingLocalActivities` — local ids {1,2,3} dated in the window; response has {1,3} → id 2 deleted, ids outside the window untouched
  - `incrementalRefetchesTodayAndYesterday` — after `planIncremental(2026-10-24)` the pending set includes `SLEEP_DAY 2026-10-24` and `SLEEP_DAY 2026-10-23`, at priority 0

- [ ] **Step 2: Run.** Expected: FAIL.

- [ ] **Step 3: Implement.**

- [ ] **Step 4: Run.** Expected: PASS.

- [ ] **Step 5: Commit:** `"feat: resumable sync engine"`

### Task 10: FIT reader

**Files:**
- Create: `app/src/main/java/dev/baseline/fit/FitReader.kt`
- Test: `app/src/test/java/dev/baseline/fit/FitReaderTest.kt`

**Interfaces:**
- Produces:
  - `object FitReader` with:
    - `fun readZip(zip: ByteArray): ByteArray` (returns the first `.fit` entry)
    - `fun read(fit: ByteArray): FitActivity`
  - `data class FitActivity(val streams: ActivityStreams, val laps: List<Lap>, val lengths: List<SwimLength>, val sets: List<StrengthSet>, val poolLengthM: Double?, val startGmt: Instant, val durationSec: Double)`
  - `data class ActivityStreams(val epochSec: LongArray, val hr: IntArray /* -1 = missing */, val distanceM: FloatArray?, val speedMs: FloatArray?, val powerW: IntArray?, val altitudeM: FloatArray?, val lat: DoubleArray?, val lon: DoubleArray?, val cadence: IntArray?)`, plus `fun pack(): ByteArray` and `companion fun unpack(b: ByteArray)`
  - `data class Lap(val startSec: Double, val durationSec: Double, val distanceM: Double?, val avgHr: Int?)`
  - `data class SwimLength(val startSec: Double, val durationSec: Double, val strokes: Int?, val stroke: String?, val active: Boolean)`
  - `data class StrengthSet(val startSec: Double, val durationSec: Double, val reps: Int?, val weightKg: Double?, val category: Int?, val active: Boolean)`

  Uses `com.garmin.fit.Decode` and `MesgBroadcaster` listeners for Record, Lap, Length, Set and Session. Positions are converted from semicircles with `× 180 / 2^31`.

- [ ] **Step 1: Write the failing tests** against the fixture FITs:
  - `runHasOneSecondHrPowerGps` — 1655 samples; every gap is 1 s; `powerW != null`; `lat != null`; 6 laps
  - `roadRideHasNoPower` — `powerW == null`, and `hr` has ≥ 3800 valid samples
  - `virtualRideHasPower` — `powerW != null`
  - `swimHasLengths` — 36 lengths; the first is `freestyle` with 15 strokes and 30.937 s; `poolLengthM != null`
  - `strengthHasSmartRecordedHr` — 413 samples, gaps in {1,2,4,…}, max gap 18 s; 1 set with 7 reps
  - `packRoundTrips`

- [ ] **Step 2: Run.** Expected: FAIL.

- [ ] **Step 3: Implement.** Then wire `fitIngest` in `AppContainer`: unzip into `filesDir/fit/{id}.fit`, read, and upsert the stream, laps, lengths and sets. Set `ActivityEntity.fitPath` and `poolLengthM`.

- [ ] **Step 4: Run.** Expected: PASS.

- [ ] **Step 5: Commit:** `"feat: FIT reader for streams, laps, lengths and sets"`

### Task 10b: All-day Body Battery curve and cache-safe schema

The user asked for this addition on 2026-10-24.

**Files:**
- Modify: `garmin/GarminEndpoint.kt` (BODY_BATTERY query adds `chunks=288`), `data/db/{Entities.kt,Daos.kt,AppDatabase.kt}`, `sync/RawIngest.kt`, `data/parse/DayParser.kt`, `tools/fetch_sample.py`
- Create: fixture `app/src/test/resources/fixtures/bodybattery_chunks_day.json` (2026-10-23, `chunks=288`)
- Test: `DayParserTest`, `SyncEngineTest` (new cases), `RawAndTaskDaoTest` (new entity)

**Interfaces:**
- Produces:
  - `BodyBatteryDayEntity(@PK date: LocalDate, packed: ByteArray)`, which stores `HrPacking` with `HrPoint(epochSec, level)`.
  - `BodyBatteryDayDao.upsert`, `BodyBatteryDayDao.get(date)`, `BodyBatteryDayDao.flow(date): Flow<BodyBatteryDayEntity?>`.
  - `DayParser.parseBodyBatteryCurve(item: JsonObject): List<HrPoint>`. It reads levels through the descriptor list, skips null levels, dedupes by timestamp (keeping the last value) and sorts the result.
  - `mergeBodyBattery` changes: `bodyBatteryWake` is the curve sample nearest `sleepEnd`, but only within ±15 min. Otherwise it is null.
  - `AppDatabase.create` uses Room 3's destructive fallback on schema mismatch. The DB is a re-downloadable cache, so with an empty `sync_task` table the backfill replans itself.

- [ ] **Step 1: Verify the range behaviour.**
  - Fetch one 28-day BODY_BATTERY window with `chunks=288` (extend `tools/fetch_sample.py`) and confirm every day carries about 576 samples at 3-minute spacing.
  - If it doesn't, use the fallback: add per-day `BODY_BATTERY_DAY` tasks at priority 6 and say so in the report.
  - Save the 2026-10-23 item as the fixture.
- [ ] **Step 2: Write the failing tests.**
  - `curveFromFixtureIsDedupedAndSorted`: about 288 to 576 points, strictly increasing timestamps, levels in 0..100.
  - `wakeUsesNearestWithin15Min`: on the fixture, `sleepEnd` gives the expected level.
  - `wakeNullWhenNoSampleNearby`.
  - `ingestStoresCurve`: a BODY_BATTERY range creates one `BodyBatteryDayEntity` per day.
  - `schemaMismatchRebuildsCache`: a smoke check that `create()` has the fallback. Assert it through the builder config, or an instrumented test on the emulator.
- [ ] **Step 3: Run.** Expected: FAIL.
- [ ] **Step 4: Implement.**
- [ ] **Step 5: Run.** Expected: PASS.
- [ ] **Step 6: Commit:** `"feat: all-day Body Battery curve"`

### Task 11: SyncWorker and scheduling (phase 2 deliverable)

**Files:**
- Create: `app/src/main/java/dev/baseline/sync/{SyncWorker.kt,SyncScheduler.kt}`
- Modify: `AndroidManifest.xml` (foreground service type `dataSync`, `POST_NOTIFICATIONS`), `AppContainer.kt`, `TodayScreen.kt` (pull-to-refresh calls `SyncScheduler.syncNow()`)
- Test: `app/src/test/java/dev/baseline/sync/SyncSchedulerTest.kt` (work-testing)

**Interfaces:**
- Produces:
  - `class SyncWorker : CoroutineWorker`. It:
    1. calls `ensureBackfillPlanned`
    2. calls `planIncremental`
    3. calls `runPending`, with `setForeground` and a notification `"Syncing history {done} / {total}"` while more than 20 tasks remain
    4. runs `ScorePipeline.recompute(changedDays)` (Task 14; it's a no-op until then)

    It maps the outcomes to results: KEY_INVALID → `Result.success()` (sync paused by key status); RATE_LIMITED or OFFLINE → `Result.retry()`.
  - `class SyncScheduler(workManager, prefs)` with:
    - `fun apply(interval: SyncInterval)` — unique periodic work `"sync-periodic"`, 1 h or 3 h; MANUAL cancels it. The constraint is `NetworkType.UNMETERED` while backfill tasks remain, otherwise `CONNECTED`.
    - `fun syncNow()` — unique one-time expedited work `"sync-now"`, `NetworkType.CONNECTED`

- [ ] **Step 1: Write the failing tests:**
  - `hourlySchedulesPeriodicOneHour`
  - `manualCancelsPeriodic`
  - `syncNowUsesConnectedNotUnmetered`
  - `backfillPendingUsesUnmetered`

- [ ] **Step 2: Run.** Expected: FAIL.

- [ ] **Step 3: Implement.**

- [ ] **Step 4: Run** the tests, then install. Expected: PASS. On the phone, the first launch shows the backfill notification counting up to about 1,200 and finishing. Spot-check one day in raw Today against Garmin Connect.

- [ ] **Step 5: Commit:** `"feat: background sync worker and scheduler"`. Then ask the user to review phase 2.

## Phase 3 — Scores and daily screens

### Task 12: Baselines and TRIMP/strain

**Files:**
- Create: `app/src/main/java/dev/baseline/scores/{Baselines.kt,Trimp.kt,Strain.kt}`
- Test: `app/src/test/java/dev/baseline/scores/{BaselinesTest.kt,TrimpTest.kt,StrainTest.kt}`

**Interfaces:**
- Produces:
  - `data class Baseline(val lnHrvMean: Double, val lnHrvSd: Double, val rhrMean: Double, val rhrSd: Double, val respMean: Double?, val respSd: Double?, val nights: Int)`
  - `object Baselines` with `fun at(day: LocalDate, history: List<DayEntity>): Baseline?`
    - The window is `day-30 .. day-1`.
    - It needs ≥ 7 days with both HRV and RHR, otherwise it returns null.
    - The SD is floored to `0.05` (lnHRV), `1.5` (RHR) and `0.3` (respiration).
  - `object Trimp` with:
    - `fun load(points: List<HrPoint>, hrRest: Int, hrMax: Int, set: BanisterSet, maxGapSec: Int = 10, floorBpm: Int? = null, fixedDtSec: Int? = null): Double`
    - `fun coverage(points: List<HrPoint>, durationSec: Double, maxGapSec: Int = 10): Double`
  - `object NonActivityLoad` with `fun compute(day: List<HrPoint>, activityWindows: List<LongRange>, zone1FloorBpm: Int, hrRest: Int, hrMax: Int, set: BanisterSet): Double`. Samples inside any window, or below the floor, are excluded. `dt` is a fixed 120 s.
  - `data class ResolvedLoad(val load: Double, val source: LoadSource)`
  - `object ActivityLoad` with:
    - `fun resolve(trimp: Double?, coverage: Double?, garminLoad: Double?, k: Double?): ResolvedLoad?` — uses HR if `coverage >= 0.6`, otherwise `garminLoad * k`. Returns null if neither is possible.
    - `fun scalingFactors(samples: List<LoadSample>): Map<SportFamily, Double>` — the median of `trimp/garminLoad` per family over samples with coverage ≥ 0.6 and garminLoad > 10. Families with no samples get the global median.
  - `data class LoadSample(val family: SportFamily, val trimp: Double, val coverage: Double, val garminLoad: Double?)`
  - `data class StrainCalibration(val tau: Double, val version: Int)`
  - `object Strain` with:
    - `fun calibrate(dayLoads: List<Double>, version: Int): StrainCalibration` — `tau = p90 / ln(21.0/4.0)`; p90 uses nearest-rank on the sorted list
    - `fun of(load: Double, cal: StrainCalibration): Double` — `21*(1-exp(-load/tau))`, rounded to 0.1

TRIMP for each consecutive pair (a, b) of valid samples:
- `dt = min(b.t - a.t, maxGapSec) / 60.0`, or `fixedDtSec / 60.0` when it's set
- `r = clamp((a.bpm - hrRest) / (hrMax - hrRest), 0, 1)`
- add `dt * r * set.a * exp(set.b * r)`

`floorBpm` drops samples whose bpm is below it.

- [ ] **Step 1: Write the failing tests:**

```kotlin
@Test fun trimpOfConstantHr() { // 61 points 1 s apart = 1 min at bpm 124, rest 44, max 205
  val pts = (0 until 61).map { HrPoint(it.toLong(), 124) }
  val r = 80.0/161; assertEquals(1.0 * r * 0.64 * exp(1.92*r), Trimp.load(pts, 44, 205, MALE_FITTED), 1e-6) }
@Test fun gapsCappedAt10s() { /* two points 30 s apart -> dt counted as 10 s */ }
@Test fun strengthFixtureCoverageIs098AndTakesHrPath() {
  val fit = FitReader.read(bytes("fit/strength.fit"))  // fixtures store unzipped .fit
  val pts = fit.streams.toHrPoints(); val cov = Trimp.coverage(pts, 983.355)
  assertEquals(0.98, cov, 0.01)
  assertEquals(LoadSource.HR, ActivityLoad.resolve(Trimp.load(pts,44,205,MALE_FITTED), cov, 2.88, 1.0)!!.source) }
@Test fun lowCoverageFallsBackToScaledGarmin() { assertEquals(ResolvedLoad(140.0, GARMIN_SCALED), ActivityLoad.resolve(5.0, 0.3, 14.0, 10.0)) }
@Test fun scalingIgnoresTinyGarminLoads() { /* strength samples with garminLoad 3 excluded; RUN median computed */ }
@Test fun nonActivityExcludesWindowsAndBelowFloor() { /* fixture heartrate_day + a window -> only samples >=103 outside window, dt 120 s */ }
@Test fun calibrationMapsP90To17() { val cal = Strain.calibrate((1..100).map{it.toDouble()}, 1)
  assertEquals(17.0, Strain.of(90.0, cal), 0.05); assertEquals(0.0, Strain.of(0.0, cal), 0.0); assertTrue(Strain.of(1e6, cal) <= 21.0) }
@Test fun baselineNeedsSevenNightsAndFloorsSd() { /* 6 nights -> null; 7 identical nights -> sd == floors */ }
@Test fun baselineExcludesTheDayItself() { }
@Test fun noNaNOnEmptyInputs() { assertEquals(0.0, Trimp.load(emptyList(),44,205,MALE_FITTED), 0.0); assertEquals(0.0, Trimp.coverage(emptyList(), 0.0), 0.0) }
```

  `ActivityStreams.toHrPoints()` is an extension defined here in `Trimp.kt`; it skips `hr == -1`.

- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests 'dev.baseline.scores.*'`. Expected: FAIL.

- [ ] **Step 3: Implement.**

- [ ] **Step 4: Run.** Expected: PASS.

- [ ] **Step 5: Commit:** `"feat: baselines, TRIMP and hybrid strain"`

### Task 13: Sleep performance, Recovery and strain target

**Files:**
- Create: `app/src/main/java/dev/baseline/scores/{SleepPerformance.kt,Recovery.kt}`
- Test: `app/src/test/java/dev/baseline/scores/RecoveryTest.kt`

**Interfaces:**
- Consumes: `Baseline`, `DayEntity`
- Produces:
  - `object SleepPerformance` with `fun of(sleepSeconds: Int?, needMinutes: Int?): Double?` = `min(1, s/(need*60))`. Null if either input is missing or need ≤ 0.
  - `enum class RecoveryZone { GREEN, AMBER, RED; companion object { fun of(score: Int): RecoveryZone } }` (≥ 67 / 34–66 / < 34)
  - `data class Contributor(val metric: String /* "hrv","rhr","sleep","resp" */, val z: Double, val delta: Double)`
  - `data class RecoveryResult(val score: Int, val partial: Boolean, val zone: RecoveryZone, val top: List<Contributor> /* 2 largest |weight*z| */)`
  - `object Recovery` with `fun compute(day: DayEntity, b: Baseline?, sleepPerf: Double?): RecoveryResult?`
    - Weights: hrv 0.50, rhr 0.25, sleep 0.20, resp 0.05.
    - `z_sleep = clamp((sp-0.85)/0.10, -3.0, 2.0)`
    - `z_resp = -max(0, (resp-mean)/sd)`
    - `score = round(100/(1+exp(-(1.2*c+0.4))))`
    - Missing HRV drops that term, renormalises the remaining weights and sets `partial = true`.
    - Returns null if `sleepPerf == null` or `b == null`.
  - `object StrainTarget` with `fun forRecovery(score: Int): ClosedFloatingPointRange<Double>` (GREEN 14.0..18.0, AMBER 10.0..14.0, RED 0.0..10.0)

- [ ] **Step 1: Write the failing tests:**
  - `averageDayIsAbout60` — all z = 0 and sp = 0.85 → `round(100/(1+e^-0.4))` = 60
  - `zonesAtBoundaries` — 67 GREEN, 66 AMBER, 34 AMBER, 33 RED
  - `missingHrvIsPartialAndRenormalised` — z_rhr = 1, sp = 0.85, no HRV → c = 0.25/0.5 → score = round(100/(1+e^-(1.2*0.5+0.4)))
  - `respirationOnlyHurtsWhenHigher`
  - `noSleepNoRecovery`
  - `topContributorsAreTwoLargest`
  - `sleepPerformanceCapsAt1AndNullSafe` — `of(31740, 490)` = 1.0 because 31740 > 29400; `of(null, 490)` = null; `of(100, 0)` = null
  - `strainTargets`

- [ ] **Step 2: Run.** Expected: FAIL.

- [ ] **Step 3: Implement.**

- [ ] **Step 4: Run.** Expected: PASS.

- [ ] **Step 5: Commit:** `"feat: sleep performance, recovery and strain target"`

### Task 14: Score pipeline and history sanity check

**Files:**
- Create: `app/src/main/java/dev/baseline/scores/ScorePipeline.kt`
- Modify: `sync/SyncWorker.kt` (call the pipeline), `prefs/UserPrefs.kt` (`strainTau` read/write)
- Test: `app/src/test/java/dev/baseline/scores/ScorePipelineTest.kt`, `app/src/test/java/dev/baseline/scores/HistorySanityTest.kt`

**Interfaces:**
- Consumes: everything in `scores/`, the DAOs, `UserPrefs.banister`, and the HR zones parsed from raw `HR_ZONES` (`maxHeartRateUsed`, `restingHeartRateUsed`, `zone1Floor` of the `DEFAULT` sport)
- Produces: `class ScorePipeline(db, prefs, clock)` with:
  - `suspend fun recompute(fromDay: LocalDate)` — recomputes activity loads, day loads, strain and recovery for `fromDay..today`, and writes `ScoreEntity` and `ActivityScoreEntity`
  - `suspend fun recalibrateIfDue(today: LocalDate)` — runs on the 1st of the month, or when there's no calibration yet; increments `version`
  - `suspend fun recalculateHistory()` — re-scores all days with the current calibration (used by Settings)

  Day load = Σ activity loads for that day (by `activityDay`) + `NonActivityLoad` over the `HrDayEntity`. Activity windows = `[startGmt, startGmt+duration]`. Past `ScoreEntity` rows keep their `calibrationVersion` unless `recalculateHistory` is called.

- [ ] **Step 1: Write the failing tests:**
  - `ScorePipelineTest.dayWithNoDataScoresZeroStrainNoRecovery` — a DayEntity with all nulls → `strain == 0.0`, `recovery == null`, no NaN in any column
  - `ScorePipelineTest.pastDaysKeepCalibrationVersion`
  - `HistorySanityTest.fullHistoryIsSane`:
    - Load `dailysummaries_history`, `hrv_history`, `sleep_summaries_history` and `activities_all` into in-memory Room through `RawIngest`.
    - Run `recompute(2025-08-30)`. With no per-day HR or FIT for most days, the activity path is `GARMIN_SCALED` with global k = 1.0.
    - Assert:
      - strain p90 over days with score rows is within 16.5..17.5
      - every recovery is in 5..98
      - no `NaN`
      - at least 100 days have a recovery value

- [ ] **Step 2: Run.** Expected: FAIL.

- [ ] **Step 3: Implement.** When there are no `LoadSample`s at all, `k` falls back to 1.0.

- [ ] **Step 4: Run.** Expected: PASS. If the sanity bounds fail, report the distribution to the user rather than changing the spec constants.

- [ ] **Step 5: Commit:** `"feat: score pipeline with calibration"`

### Task 15: Today screen (dials, activities, baseline rows)

**Files:**
- Modify: `ui/today/{TodayScreen.kt,TodayViewModel.kt}`
- Create: `ui/components/{Dial.kt,StatRow.kt,ActivityCard.kt,BodyBatteryCard.kt}`, `ui/charts/LineChart.kt` (a thin Vico wrapper, created here and reused by Tasks 16, 18 and 20)
- Test: `app/src/test/java/dev/baseline/ui/today/{TodayViewModelTest.kt,TodayScreenTest.kt}`

**Interfaces:**
- Consumes: `Repository.day/score/activitiesOn`, `Baselines`, `UserPrefs.baselineOrder/hiddenRows`
- Produces:
  - `data class TodayUiState(val date: LocalDate, val sleepPct: Int?, val recovery: RecoveryResult?, val strain: Double, val strainTarget: ClosedFloatingPointRange<Double>?, val activities: List<ActivityCardUi>, val rows: List<StatRowUi>, val lastSynced: Instant?)`
  - `data class StatRowUi(val id: String, val label: String, val value: String, val unit: String, val baseline: String?, val delta: String?, val better: Boolean?)`
  - `TodayViewModel` with `fun setDate(d: LocalDate)`, `fun prevDay()` and `fun nextDay()` (disabled beyond today)

  Row labels, in default order:
  - HRV (overnight)
  - Resting heart rate
  - Sleep
  - Respiration (sleep)
  - Body Battery at wake
  - Avg stress
  - Steps

  The baseline for each row is the 30-day mean. Steps and Body Battery use their own 30-day means.

  - `TodayUiState` also has `bodyBattery: BodyBatteryUi`, where `BodyBatteryUi(latest: Int?, curve: List<Pair<Instant,Int>>, charged: Int?, drained: Int?, expanded: Boolean)`.
    - `latest` is the last sample of that day's `BodyBatteryDayEntity` curve, for both today and past days.
    - **Bar** (`BodyBatteryCard.kt`):
      - It sits full width directly under the Sleep / Recovery / Strain dials and is always shown.
      - It shows the title "Body Battery", the number, and a track filled to `latest`/100.
      - Fill colour by band: 76–100 `#34D399`, 51–75 `#A3D94C`, 26–50 `#FBBF24`, 0–25 `#F87171`.
      - With no reading it shows an empty track and "—".
    - **Expanded:** tapping the bar toggles `expanded` (`TodayViewModel.toggleBodyBattery()`) to reveal the day's 3-min curve (`LineChart`) with charged and drained totals.
    - The "Body Battery at wake" baseline row stays.

- [ ] **Step 1: Write the failing tests:**
  - `TodayViewModelTest.rowsFollowSavedOrderAndHideHidden` — order `["steps","hrv"]` + hidden `{"steps"}` → rows begin `hrv`, and `steps` is absent; ids not in the saved order are appended in default order
  - `TodayViewModelTest.cannotGoPastToday`
  - `TodayScreenTest.activitiesHeaderHiddenWhenNone` — state with an empty `activities` list → no node with text `TODAY'S ACTIVITIES`; with one activity → the header exists and appears above `VS. 30-DAY BASELINE`
  - `TodayScreenTest.dialsHaveContentDescriptions` — `"Recovery 72 percent"`, `"Sleep performance 86 percent"`, `"Strain 11.4 of 21"`
  - `TodayViewModelTest.bodyBatteryLatestIsLastCurveSample`: a day with a curve gives the last level. A day without one gives `latest == null`.
  - `TodayScreenTest.bodyBatteryBarBandsAndExpand`:
    - level 80 uses `#34D399`, 60 uses `#A3D94C`, 40 uses `#FBBF24`, 10 uses `#F87171`;
    - `null` shows "—";
    - clicking the bar shows the curve chart and the charged/drained text, and clicking again hides it.

- [ ] **Step 2: Run.** Expected: FAIL.

- [ ] **Step 3: Implement** to match the `Main.dc.html` mockup.
  - Tapping the header date → `Routes.CALENDAR`. The Sleep dial → `Routes.SLEEP`. An activity card → `Routes.ACTIVITY`.
  - Recovery dial: zone colour, with "Partial" under it when `partial`. The insight card text comes from `top` contributors, e.g. `"HRV is 6% above your 30-day baseline"`.

- [ ] **Step 4: Run.** Expected: PASS.

- [ ] **Step 5: Commit:** `"feat: Today screen"`

### Task 15b: Score during backfill, and treat no-sleep nights as empty

Controller-added on 2026-10-25 after the device check of Task 15. On the emulator, with real data:
- 68 `SLEEP_DAY` tasks were FAILED with "missing calendarDate", because Garmin returns a placeholder for nights with no sleep.
- The `score` table stayed empty during backfill. `afterSync` only runs once `runPending` has drained the whole queue, and when foreground is refused, WorkManager stops a run after about 10 minutes.

**Files:**
- Modify: `sync/RawIngest.kt`, `sync/SyncEngine.kt`, `sync/SyncWorker.kt`, `sync/SyncScheduler.kt`, `data/db/Daos.kt`, `AppContainer.kt`
- Test: `SyncEngineTest`, `SyncWorkerTest`, `SyncSchedulerTest`

**Interfaces:**
- `SyncOutcome` gains `PARTIAL`.
- `SyncEngine.runPending(onProgress, budget: Duration = 8.minutes)` returns `PARTIAL` once the budget has elapsed and tasks remain PENDING. The budget is checked between tasks, with time from the injected clock.
- On `PARTIAL`, `SyncWorker` does the following, then returns `Result.success()`:
  1. Runs `afterSync(changedFrom)`.
  2. Sets `lastSyncedAt`.
  3. Calls `SyncScheduler.continueSoon()`. This enqueues unique one-time work named `"sync-continue"` with policy KEEP and the backfill network constraint (UNMETERED while `backfillPending()`, otherwise CONNECTED).
- `SyncScheduler.continueSoon()`.
- `TaskDao.repairSleepPlaceholders()`. It sets status = EMPTY on every FAILED task where endpoint = 'SLEEP_DAY' and lastError = 'missing calendarDate'. The worker calls it once at the start of each run; it is idempotent and cheap.

- [ ] **Step 1: Write the failing tests**
  - `sleepDayPlaceholderIsEmptyNotFailed`. A SLEEP_DAY body with no `calendarDate` → the task is EMPTY, the raw response is kept, and no day row is written.
  - `runPendingStopsAtBudgetWithPartial`. Use a fake clock that advances 1 minute per task and a budget of 3 minutes → PARTIAL, with the remaining tasks PENDING.
  - `partialRunScoresAndContinues` (worker) → `afterSync` is called with `changedFrom`, `markSynced` is called, and `continueSoon` is called once.
  - `continueSoonUsesBackfillConstraint`.
  - `repairSleepPlaceholdersMarksThemEmpty`.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.** In `RawIngest`, SLEEP_DAY passes `skipUndated = true`. A placeholder writes no day row, and the engine marks the task EMPTY.
- [ ] **Step 4: Run** the full JVM suite, then install on the emulator. Expected: PASS. Within one run the `score` table gets rows, and the 68 placeholder tasks become EMPTY.
- [ ] **Step 5: Commit** with the message `"fix: score during backfill, no-sleep nights are empty"`.

### Task 16: Calendar and Sleep detail (phase 3 deliverable)

**Files:**
- Create: `ui/calendar/{CalendarScreen.kt,CalendarViewModel.kt}`, `ui/sleep/{SleepScreen.kt,SleepViewModel.kt}`
- Reuse: `ui/charts/LineChart.kt` (created in Task 15)
- Test: `app/src/test/java/dev/baseline/ui/calendar/CalendarViewModelTest.kt`, `app/src/test/java/dev/baseline/ui/sleep/SleepViewModelTest.kt`

**Interfaces:**
- Produces:
  - `CalendarUiState(month: YearMonth, firstDayOfWeek: DayOfWeek, cells: List<CalendarCell?>, selected: LocalDate, summary: DaySummary)`
  - `CalendarCell(date, zone: RecoveryZone?, activityCount: Int /* capped at 3 for dots */, isToday: Boolean, isFuture: Boolean)`
  - `SleepUiState(date, sleepPct, asleep: Duration, need: Duration, garminScore: Int?, bed: LocalTime?, wake: LocalTime?, hrvSeries: List<Pair<Instant,Int>>, hrSeries: List<Pair<Instant,Int>>, stages: List<StageUi>, needCard: SleepNeedUi?, tiles: SleepTiles)`
  - `SleepNeedUi(baseline: Duration, adjustment: Duration, reasons: List<String>, bedtimeStart: LocalTime?)`
    - It comes from `DayEntity.nextSleepNeed*`.
    - Reason labels: `trainingFeedback` → "Recent training", `hrvAdjustment` → "HRV", `sleepHistoryAdjustment` → "Sleep history", `napAdjustment` → "Naps".
    - Each label gets " ▲" for `INCREASING` or " ▼" for `DECREASING`.

  Sleep detail fetches `HRV_DAY` and `SPO2_DAY` on demand (a priority 0 task plus `syncNow`) when the raw rows are missing. The HR series is the `HrDayEntity` samples inside the `[sleepStart, sleepEnd]` window.

- [ ] **Step 1: Write the failing tests:**
  - `CalendarViewModelTest.october2026MondayStart` — the first cell is null ×3 (Oct 1 is a Thursday) and there are 31 dated cells
  - `CalendarViewModelTest.futureDaysNotSelectable`
  - `CalendarViewModelTest.firstDayFollowsProfile` — profile `firstDayOfWeek` = `"sunday"` → Sunday start
  - `SleepViewModelTest.needCardFromNextSleepNeed` — fixture sleep_day → baseline 460 min, adjustment 0 min, reasons `["Sleep history ▼", "HRV ▲"]`, bedtime 23:40
  - `SleepViewModelTest.hrSeriesClippedToSleepWindow`

- [ ] **Step 2: Run.** Expected: FAIL.

- [ ] **Step 3: Implement** to match `Calendar.dc.html` and `Sleep.dc.html`. There is no hypnogram.

- [ ] **Step 4: Run** the tests and install. Expected: PASS. The phone shows real dials, the calendar and sleep detail.

- [ ] **Step 5: Commit:** `"feat: calendar and sleep detail"`. Then ask the user to review phase 3, including whether the scores feel right.

## Phase 4 — Activities

### Task 17: Activities list

**Files:**
- Create: `ui/activities/{ActivitiesScreen.kt,ActivitiesViewModel.kt}`
- Test: `app/src/test/java/dev/baseline/ui/activities/ActivitiesViewModelTest.kt`

**Interfaces:**
- Produces: `ActivitiesUiState(filter: SportFamily?, week: WeekSummary(count, duration, distanceKm, avgStrain), days: List<DayGroup(date, items: List<ActivityCardUi>)>)`.
  - `ActivityCardUi(id, name, family, typeKey, meta: String, strain: Double?)` is shared with Today.
  - Filter chips: All, Run, Ride, Strength, Swim, Other.
  - The week is Monday to Sunday (or follows the profile `firstDayOfWeek`).

- [ ] **Step 1: Write the failing tests:**
  - `groupsByLocalDayNewestFirst`
  - `filterBySport`
  - `metaFormatsByFamily` — run: `"10.21 km · 52:14 · 5:07 /km"`; ride: km/h; swim: `/100m`; strength: `"46 min · 18 sets"`
  - `imperialFollowsProfile` — profile `measurementSystem` = `"statute_us"` → run meta `"6.34 mi · 52:14 · 8:14 /mi"`. Formatting lives in `ui/format/Formatters.kt`, as `class Formatters(val imperial: Boolean)`, and is reused by every later screen.

- [ ] **Step 2: Run.** Expected: FAIL.

- [ ] **Step 3: Implement** to match `Activities.dc.html`, including the Records button → `Routes.RECORDS`. The list is a `LazyColumn` (175+ items).

- [ ] **Step 4: Run.** Expected: PASS.

- [ ] **Step 5: Commit:** `"feat: activities list"`

### Task 18: Activity detail by sport, with map (phase 4 deliverable)

**Files:**
- Create: `ui/activity/{ActivityScreen.kt,ActivityViewModel.kt,sections/{SummaryGrid.kt,RouteMap.kt,HrSection.kt,ZonesSection.kt,SplitsSection.kt,RideSection.kt,SwimSection.kt,StrengthSection.kt}}`
- Test: `app/src/test/java/dev/baseline/ui/activity/ActivityViewModelTest.kt`

**Interfaces:**
- Consumes: `ActivityEntity`, `ActivityStreamEntity`, laps, lengths and sets; `ActivityScoreEntity`; `GarminEndpoint.ACTIVITY_DETAIL` (fetched on open for `splitSummaries`)
- Produces:
  - `ActivityUiState(header, summary: List<Tile>, sections: List<Section>)`
  - `sealed interface Section { Map(latLon), Hr(series, avg, max), Zones(secondsPerZone), Splits(rows), Ride(speed, power?, elevation), Swim(lengths, pacePer100), Strength(exercises), Running(cadence, power, stride) }`
  - `fun sectionsFor(family: SportFamily, hasGps: Boolean, hasPower: Boolean): List<SectionKind>`:
    - RUN: Map?, Hr, Zones, Splits, Running
    - RIDE: Map?, Ride, Hr, Zones
    - SWIM: Swim, Hr, Zones
    - STRENGTH: Strength, Hr
    - OTHER: Map?, Hr, Zones

  Each summary also shows Garmin's training load next to Baseline strain. The map is MapLibre with style `https://tiles.openfreemap.org/styles/dark`; if that style 404s, use `https://tiles.openfreemap.org/styles/positron`. The route is drawn as a line layer, with a start marker.

- [ ] **Step 1: Write the failing tests:**
  - `sectionsPerFamily` (every row of the mapping above)
  - `splitsPerKmFromStream` — from the run fixture, the per-km split count = floor(distance km) plus a remainder row
  - `swimPacePer100FromLengths`
  - `strengthGroupsSetsByCategory`

- [ ] **Step 2: Run.** Expected: FAIL.

- [ ] **Step 3: Implement** to match `Run.dc.html` and `Strength.dc.html`.

- [ ] **Step 4: Run** the tests and install. Expected: PASS. Open a run, a road ride, a swim and a strength session on the phone.

- [ ] **Step 5: Commit:** `"feat: activity detail by sport"`. Then ask the user to review phase 4.

## Phase 5 — Records and Trends

### Task 19: Best efforts and the Records screen

**Files:**
- Create: `app/src/main/java/dev/baseline/records/BestEfforts.kt`, `ui/records/{RecordsScreen.kt,RecordsViewModel.kt}`
- Modify: `ScorePipeline.recompute` (also refreshes records for new activities)
- Test: `app/src/test/java/dev/baseline/records/BestEffortsTest.kt`

**Interfaces:**
- Produces:
  - `object BestEfforts` with:
    - `fun runFromSplits(activities: List<ActivityEntity>): List<BestEffortEntity>` — keys `1000m, 1609m, 5000m, 10000m, 21098m, 42195m`; only types where `countsForDistanceRecords` is true
    - `fun fastestDistance(s: ActivityStreams, meters: Double): Double?` — the minimum time over any window covering ≥ meters; two-pointer over cumulative distance, interpolating the boundary sample
    - `fun bestPower(s: ActivityStreams, windowSec: Int): Int?` — the maximum rolling mean over valid power samples, using a 1 s grid
    - `fun fastestSwim(lengths: List<SwimLength>, poolLengthM: Double, targetM: Int): Double?` — consecutive active lengths only; a rest length breaks the run
  - Ride efforts: `10000m, 20000m, 40000m, 100000m` (outdoor only) and `power_5s, power_60s, power_300s, power_1200s, power_3600s` (any ride with power).
  - Swim efforts: `100m, 400m, 1500m` (lap_swimming only).
  - When an activity's FIT isn't downloaded yet, ride 40 km and swim 100 m fall back to Garmin's `fastestSplit_40000` / `fastestSplit_100`.
  - `RecordsUiState(tab, rows: List<RecordRow(label, time, meta, isNew /* achievedOn >= today-30 */, activityId)>, power: List<PowerRow>?, powerLabel: String /* "Indoor" when no outdoor ride has power */)`

- [ ] **Step 1: Write the failing tests:**
  - `runRecordsMatchFixture` — 5K = 1476.757 s from activity 20642238318; treadmill activities excluded
  - `fastestDistanceOnSyntheticStream` — constant 5 m/s for 600 s then 10 m/s → fastest 1000 m = 100 s
  - `bestPowerOnSyntheticStream` — 300 s at 200 W containing 60 s at 400 W → power_60s = 400, power_300s = 240
  - `swimRestBreaksSequence`
  - `virtualRideCountsForPowerNotDistance`
  - `newBadgeWithin30Days`

- [ ] **Step 2: Run.** Expected: FAIL.

- [ ] **Step 3: Implement** to match `Records.dc.html`.

- [ ] **Step 4: Run.** Expected: PASS.

- [ ] **Step 5: Commit:** `"feat: all-time records"`

### Task 20: Trends screen (phase 5 deliverable)

**Files:**
- Create: `ui/trends/{TrendsScreen.kt,TrendsViewModel.kt}`
- Test: `app/src/test/java/dev/baseline/ui/trends/TrendsViewModelTest.kt`

**Interfaces:**
- Produces: `TrendsUiState(range: TrendRange { D7, D30, D90 }, recovery: List<Pair<LocalDate,Int?>>, hrv: List<Pair<LocalDate,Int?>>, hrvBand: List<Pair<LocalDate, ClosedRange<Int>>>, rhr, sleepVsNeed: List<Triple<LocalDate, Duration?, Duration?>>, averages)`. Missing days are null, so they show as gaps rather than zeros. The HRV band comes from `hrvBalancedLow..hrvBalancedUpper`.

- [ ] **Step 1: Write the failing tests:**
  - `rangeSelectsDays` (7/30/90 entries ending today)
  - `missingDaysAreNullNotZero`
  - `averagesIgnoreNulls`

- [ ] **Step 2: Run.** Expected: FAIL.

- [ ] **Step 3: Implement** to match `Trends.dc.html`.

- [ ] **Step 4: Run** the tests and install. Expected: PASS.

- [ ] **Step 5: Commit:** `"feat: trends"`. Then ask the user to review phase 5.

## Phase 6 — Polish

### Task 21: Baseline reorder/hide, Settings, banners and error states

**Files:**
- Modify: `ui/today/TodayScreen.kt` (Edit mode with `sh.calvin.reorderable`), `prefs/UserPrefs.kt`
- Create: `ui/settings/{SettingsScreen.kt,SettingsViewModel.kt}`, `ui/components/KeyBanner.kt`
- Test: `app/src/test/java/dev/baseline/ui/settings/SettingsViewModelTest.kt`, `app/src/test/java/dev/baseline/ui/today/EditModeTest.kt`

**Interfaces:**
- Consumes: `KeyStatus`, `SyncScheduler`, `ScorePipeline.recalculateHistory`, `TaskDao.counts`
- Produces:
  - `TodayViewModel.moveRow(from: Int, to: Int)` and `TodayViewModel.toggleHidden(id: String)`, which persist to `UserPrefs`
  - `SettingsViewModel` with:
    - `replaceKey(key, lifetimeDays)` — the same test-then-save flow as onboarding, then `syncNow()`
    - `setInterval(SyncInterval)` → `SyncScheduler.apply`
    - `setBanister(BanisterSet)` → `recalculateHistory()`
    - `recalculateHistory()`
    - `syncNow()`
  - `KeyBanner(status)`:
    - amber: `"Your Garmin key expires in {n} days. Create a new one in Garmin Connect and paste it in Settings."`
    - red (Expired or Invalid): `"Sync is paused: your Garmin key expired. Paste a new key to resume."`

    It's shown on Today and Settings. Every screen shows `"Last synced {relative time}"` when offline. Unparseable days show `"Couldn't read this data"`.

- [ ] **Step 1: Write the failing tests:**
  - `EditModeTest.moveRowPersistsOrder` — move `steps` from 6 to 0 → prefs order starts with `steps`
  - `EditModeTest.hiddenRowStillVisibleInEditMode`
  - `SettingsViewModelTest.changingBanisterTriggersRecalc`
  - `SettingsViewModelTest.replaceKeyResumesSync` — status Invalid → `replaceKey` OK → status Ok, `syncNow` called once
  - `KeyBannerTest.amberAt7DaysRedWhenExpired`

- [ ] **Step 2: Run.** Expected: FAIL.

- [ ] **Step 3: Implement** to match `Main.dc.html` (Edit mode) and `Settings.dc.html`. Settings also shows history coverage, from the `DayEntity` min date and the activity min date.

- [ ] **Step 4: Run** the full suite `./gradlew :app:testDebugUnitTest` and install. Expected: all PASS.

- [ ] **Step 5: Commit:** `"feat: settings, baseline editing and key banners"`. Then ask the user for the final review.
