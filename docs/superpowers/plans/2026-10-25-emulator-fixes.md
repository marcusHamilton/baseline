# Emulator review fixes — plan (2026-10-25)

Source: the emulator pass of `docs/superpowers/handoff/2026-10-25-emulator-review.md` on Pixel 9 Pro XL API 35.
Spec: `docs/superpowers/specs/2026-10-24-baseline-android-design.md`.

## Global Constraints

- Gradle: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` for every Gradle command. Unit tests: `./gradlew :app:testDebugUnitTest` (filter with `--tests` while iterating). Build: `./gradlew :app:assembleDebug`.
- Match the surrounding code: comment density, naming, idioms, Compose patterns. No new dependencies.
- Every behaviour fix gets a regression test (Robolectric/Compose UI tests already exist under `app/src/test`). Purely visual fixes get a test where one is practical.
- Commit each task on the current branch (`feat/baseline-app-ic9qem`), Conventional Commits, message ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never push.
- The API key is a secret: messages, logs and test output never include it.
- One HTTP request at a time to Garmin: everything goes through `GarminClient`'s request lock (spec §5, handoff "One HTTP request at a time").

### Task 1: Test a replacement key without writing it to the vault

**Bug (reproduced on device):** `ApiKeyVault.saveAndTest` (`garmin/KeyEntry.kt`) saves the candidate key into the vault, runs `SyncEngine.testConnection()`, and on failure restores a snapshot. While the candidate sits in the vault, a running `SyncWorker` mints a token from it, gets 401, and `TokenProvider.mint()` calls `vault.markInvalid()`; that write can land *after* the rollback, so the user's good key ends up flagged "Key rejected" with a red banner until the next successful sync. Also, if the process dies mid-test, the untested key stays saved and the previous key is lost.

**Required behaviour:**
- Testing a key (Settings replace, and onboarding) never writes the candidate to the vault and never marks the stored key invalid or valid. Only a successful test saves the key (`vault.save(key, lifetimeDays)`), and the cached JWT is cleared then so the next sync mints with the new key.
- The test request (mint + profile fetch) still goes through `GarminClient`'s single request lock, so it waits behind an in-flight sync request as today.
- A sync running during a failed test is unaffected: it keeps using the stored key and its JWT.
- User-facing messages stay exactly as they are in `saveAndTest` today (KeyInvalid, RateLimited, BadRequest, Failed, IOException, other exception). A 401/403 on the candidate's mint maps to the existing KeyInvalid message.
- Cancellation mid-test leaves the vault untouched (nothing to roll back).
- Remove code that becomes dead (snapshot/restore/rollback) unless something else still uses it.

**Tests:** a regression test that reproduces the race shape: a stored good key, a candidate rejected with 401, and assert afterwards that the vault still holds the old key and its status is not `Invalid` and expiry unchanged. A test that a successful test saves the key with the chosen lifetime. Update `TestConnectionTest`, Settings/Onboarding VM tests to the new API.

### Task 2: Today edit mode — row layout on narrow screens, and auto-scroll while dragging

**Bug A (seen at ~358dp and ~298dp wide):** in edit mode (`ui/today/TodayScreen.kt`, `EditableRow` and the stat row it wraps) the label column is squeezed so text breaks mid-word ("Sle/ep", "Baselin/e"); at ~298dp it collapses to one character per line. Normal (non-edit) rows wrap acceptably. Fix so that at 320dp width with default font scale, edit-mode labels wrap only at word boundaries and the value block never takes the label's minimum space (e.g. the label column takes the remaining weight and the value/delta block is not given priority to squeeze it to nothing). Keep the current look at normal widths (dashed border, 48dp handle, eye toggle, 45% opacity for hidden rows).

**Bug B:** dragging a row to the top/bottom edge of the viewport does not scroll the Today column (`ReorderableColumn` inside the screen's vertical scroll). Holding a dragged row near the bottom (or top) edge must scroll the page so a row can be moved past the fold in one drag. First check whether `sh.calvin.reorderable:reorderable` 3.1.0's `ReorderableColumn` offers this; otherwise implement the smallest edge auto-scroll that drives the existing scroll state. The drop index mapping to the saved order (`onSettle` → `onMoveRow`) must stay correct after scrolling.

**Tests:** a Compose test at 320dp width asserting an edit-mode label (e.g. "Respiration (sleep)") renders on at most two lines / is not broken per character; a test covering the auto-scroll behaviour where practical (e.g. dragging the handle to the bottom edge changes the scroll position), otherwise explain in the report why not and what you verified.

### Task 3: Sync status while online, and key banner wording

**Bug A:** `syncStatus(...)` in `ui/today/TodayViewModel.kt` returns "Offline, will retry" whenever a WorkInfo is FAILED or ENQUEUED with `runAttemptCount > 0`. That also happens online: a run interrupted by process death, or a RATE_LIMITED `Result.retry()`. Seen on device while online. Make the status depend on connectivity: offline → "Offline, will retry" (unchanged); online → "Retrying shortly". Connectivity comes from the existing `AppContainer.online` flow (already used by `ConnectivityLine` in `ui/nav/BaselineNavHost.kt`); pass it into `TodayViewModel` the way the other flows are passed.

**Bug B:** `keyBannerText` in `ui/components/KeyBanner.kt` shows "Sync is paused: your Garmin key expired. Paste a new key to resume." for both `Expired` and `Invalid`, but sync is not paused (the scheduler only stops when the key is Missing, which is what lets a transient 401 heal on the next 200). Use:
- `Expired`: "Your Garmin key expired, so new data can't sync. Paste a new key in Settings."
- `Invalid`: "Garmin rejected your key, so new data can't sync. Paste a new key in Settings."
Keep the red styling for both and the amber ExpiringSoon text unchanged.

**Tests:** extend the existing `syncStatus` and `keyBannerText` tests for each new branch (online vs offline retry; Expired vs Invalid wording).

### Task 4: Cosmetic fixes

1. **Settings chips** (`ui/settings/SettingsScreen.kt`): the selected chip in "How long does the new key last?" and "Background sync" has no border or fill (shows as bare green text), and "365 days" wraps onto its own row misaligned. Give the selected chip a visible selected treatment consistent with the app's theme (filled container using the existing selected-chip colour `#2A2F37` used in Trends, plus the accent text) and keep 48dp targets; the chips should flow in rows with consistent spacing.
2. **Calendar selected-day box** (`ui/calendar/CalendarScreen.kt`): the selected-day highlight is wider than its grid column and on the last (Sunday) column overflows past the page's right margin. Constrain it to the cell's column width.
3. **"Synced" time after a time-zone change** (Today header "· Synced 7:23 PM"): it stays formatted in the old zone until the viewed date changes. Format it with the current zone so it updates when the zone changes (the app already reacts to zone changes via `DateTicker`/`SystemZoneClock`; reuse that signal).
4. **White launch window:** the app's window background/splash is white before the dark Compose UI appears. Make the launch theme dark (window background = the app's `Bg` colour) via the activity/app theme in resources; no splash-screen library.

**Tests:** a test for item 3 (zone change re-formats the synced time) and item 2 where practical; items 1 and 4 are visual — note what you checked.
