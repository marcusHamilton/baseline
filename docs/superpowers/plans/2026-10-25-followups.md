# Follow-ups — plan (2026-10-25)

Source: "Known follow-ups" in `CLAUDE.md`. Spec: `docs/superpowers/specs/2026-10-24-baseline-android-design.md`. Read `CLAUDE.md` for architecture, invariants and conventions.

## Global Constraints

- Gradle: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`. Unit tests: `./gradlew :app:testDebugUnitTest` (filter with `--tests` while iterating); it must stay fully green on any host time zone. Build: `./gradlew :app:assembleDebug`.
- Match the surrounding code: comment density, naming, idioms, Compose patterns. No new dependencies.
- Every behaviour change gets a regression test (Robolectric/Compose tests under `app/src/test`).
- Touch targets ≥ 48dp; TalkBack labels on new controls.
- The API key never appears in logs, messages or test output.
- Room schema stays at version 2: no schema change in this plan (queries only).
- Commit on the current branch (`feat/baseline-app-ic9qem`), Conventional Commits, with a Co-Authored-By trailer naming the model that wrote the change. Never push.

### Task 1: Small cleanups

1. **Deprecated test form:** `TestConnectionTest`'s cancellation test (`cancellationDuringOnValidStillSavesAndClearsTheToken`) uses `launch(job + Dispatchers.Default)`. That is deprecated and detaches the child from `runBlocking`, so an unexpected exception would be swallowed. Rewrite without the external Job: cancel from inside the test key callback (e.g. `currentCoroutineContext().cancel()` before calling `onValid()`) and `launch(Dispatchers.Default) { … }.join()`. The test must still fail if `NonCancellable` is removed from `KeyEntry.saveAndTest` (check once, then restore). No deprecation warning from that file.
2. **Settings "Last sync" follows zone changes while open:** Settings formats "Last sync" with `clock.zone`, read once per composition, so it doesn't re-format when the time zone changes while Settings is open. Today already does this right via `container.dateTicker.zone` (a `StateFlow<ZoneId>` passed into `TodayScreen` from `ui/nav/BaselineNavHost.kt`). Do the same for Settings: take the zone as state, collected with lifecycle, and format "Last sync" with it. Keep the existing `SettingsScreenTest.lastSyncUsesTheClocksZoneNotTheHosts` passing. Add a test that changing the zone state re-formats the text.
3. **Test-only tags in production composables:** `testTag("stat-label")` (`ui/components/StatRow.kt`), `testTag("day-highlight")` and `testTag("day-card")` (`ui/calendar/CalendarScreen.kt`) exist only for tests. Remove each one where the tests can find the same node another way: text, content description, `hasAnyAncestor`, unmerged-tree matchers, or `onChildren()`. The tests must keep asserting the same thing, with the same bounds checks. If one tag has no reasonable alternative, keep it with a one-line comment saying why, and say so in the report.

### Task 2: "Retry failed" in Settings

**Problem:** sync tasks that end FAILED (`TaskStatus.FAILED` in `sync_task`, set by `SyncEngine.fail` and the bad-request path) are never retried, except the narrow automatic re-queue of unreadable range windows (`TaskDao.requeueFailedOverlapping`). Today the only way to clear them is to reinstall.

**Required behaviour:**
- Settings → Sync card shows a line with the number of failed items (e.g. "3 items couldn't sync") and a **Retry failed** button, only when that count is > 0. The count comes from a Room `Flow` (a new `TaskDao` query; no schema change), so it updates live.
- Tapping Retry failed sets every FAILED task back to `PENDING` with `lastError = NULL`, keeping `attempts` as it is (so a range already split once is not split again), then asks for a sync now through the existing `SyncScheduler.syncNow()`. The DB update happens off the main thread, in one statement or transaction.
- While the retry is being queued the button is disabled; afterwards the line disappears once the count drops to 0 (live, from the flow). If tasks fail again, the line comes back.
- The button is ≥ 48dp, has a clear TalkBack label, and matches the existing Settings button/text styles.
- Don't touch the automatic `requeueFailedOverlapping` / `repairSleepPlaceholders` behaviour.

**Tests:**
- A DAO test (real in-memory Room, like the existing DAO tests): the failed-count flow counts only FAILED, and the retry query re-queues only FAILED rows, clears `lastError`, and keeps `attempts`.
- A ViewModel test: retry calls the DAO re-queue and then `syncNow()`, in that order.
- A Compose test: the line and button show only when the count is > 0, and tapping the button invokes the retry.
