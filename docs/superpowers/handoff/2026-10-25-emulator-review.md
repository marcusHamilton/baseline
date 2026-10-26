# Handoff: cloud session → local emulator review (2026-10-25)

Branch: `feat/baseline-app-ic9qem`. Its code is the same as the desktop's `feat/baseline-app` plus everything listed below. Every task in the plan (1–21) is now implemented. Each one had an Opus review and its fixes were re-reviewed, and the whole branch also had two review rounds.

## What this session did

| Commit | What |
|---|---|
| `9c88e5a` fix: trends review round 1 | The HRV and RHR headers show the latest value plus a baseline or average. Adds a VM flow test. The selected chip is `#2A2F37`. |
| `711cf7b` feat: settings, baseline editing and key banners | Task 21: Today edit mode (reorder and hide), Settings screen, KeyBanner, offline "Last synced" bar, "Couldn't read this data" notes. |
| `6e11d93`, `ade447c` fix: settings review rounds 1–2 | The re-score runs off the main thread and is serialised with the sync worker. It runs in an app-level `HistoryRecalculator` and has a persisted `recalcPending` flag. A rejected key's JWT is cleared. Atomic coefficient taps. 48dp drag handle. The stale "couldn't read" rules are fixed. |
| `6c6b764` test: cancel ViewModel and DataStore scopes… | Fixes the flaky full-suite runs: ViewModel and DataStore scopes are now cancel-and-joined before the test DB closes (`testutil/ScopeCleanup.kt`). |
| `5f32355`, `0638059` fix: whole-branch review rounds 1–2 | Whole-branch review fixes: "today" follows midnight and timezone changes (`DateTicker`, `SystemZoneClock`). Failed parse windows are retried once per day. The key-invalid flag clears on a 200. `AfterSync` is extracted and tested. One HTTP request at a time (`GarminClient` lock + callTimeout). Keystore work runs on IO. Key expiry status follows the date. |

| `a9502b3` + follow-up | Switching the strain coefficient set (and "Recalculate history") now re-fits the strain scale, then re-scores, so your 90th-percentile day stays at about 17. A final calibration is never downgraded to provisional. |

The unit suite has 410 tests and was green on repeated `--rerun` runs. `assembleDebug` and `lintDebug` pass (0 errors).

## Building locally

Nothing about the build changed: use the plan's `JAVA_HOME` and `./gradlew :app:testDebugUnitTest`, then `./gradlew :app:installDebug`. One new dependency was added: `sh.calvin.reorderable:reorderable` 3.1.0, which is in the plan's tech stack.

## Where I have doubts: test these on the emulator first

1. **Drag-to-reorder on Today (Edit).**
   - Check that drag works inside the scrolling Today column and that auto-scroll behaves.
   - The dropped position must match where the row lands. The indices come from the reorderable list and are mapped to the saved order.
   - Hide `bb`, drag `steps` to the top, kill the app and reopen. The order and hidden set should persist, and hidden rows should be absent outside edit mode.
   - The drag handle also has TalkBack "Move up / Move down" actions.
2. **Midnight / timezone rollover.**
   - Open Today, background the app, then move the emulator clock past midnight (or change the timezone) and reopen.
   - If you were on the old today, Today should jump to the new day. A past day should stay put, but › should reach the new today.
   - Calendar, Activities ("this week"), Records (New badges) and Trends (range end) should all advance.
   - Calendar: select an earlier day in the month, then roll over into the next month. The selected day should become the new today.
   - Confirm the date-change broadcast receiver registers without a crash on your API level.
3. **Calendar month switching.** A test showed a race where switching month while Room's observers were still registering never emitted. It was fixed on the test side only, and **it may also happen in the app**. Tap ‹ / › quickly across months and watch for a month that stays blank.
4. **Offline bar.** In airplane mode, "Last synced …" should appear on every screen except onboarding and update each minute. Cached data stays readable, and pull-to-refresh must not spin forever.
5. **Replacing the key in Settings.**
   - A garbage key should give an error, keep the old key, and the status should stay Connected.
   - A valid key should show Saved and start a sync.
   - Replace the key while a backfill is running: "Testing…" should clear within about 1–2 minutes, because it waits behind one in-flight request.
6. **Key banners.** Amber from 7 days before expiry; red when expired or invalid, with sync paused. To fake it, save a key with a short lifetime or move the clock forward. After a one-off 401, the red banner should clear on the next successful request.
7. **Strain coefficient set / Recalculate history.**
   - The busy state should show, and leaving Settings must not cancel the run.
   - Past days' strain should change.
   - Force-stop during a re-score, then sync: the re-score should rerun after the sync.
   - Decided: switching sets re-fits the scale (tau = p90 under the new set) before re-scoring. Expect past strains to move only by a few tenths: the set changes how easy and hard minutes are weighted, not the overall level.
8. **"Couldn't read this data".** This is hard to trigger with real data. The logic is unit-tested, but the on-screen note on Today and Sleep hasn't been seen on a device.
9. **Upgrade install over the phase-4 build on the phone** (`adb install -r`). Data should be kept, with no onboarding (the schema is still v2). Records fill in after the first sync.
10. **Edge-to-edge and TalkBack.** Check status and gesture bar insets on targetSdk 37. Dials, charts and the map should be read out with their descriptions.
11. **Visual match with mockups.** Today edit mode: dashed border, rows at 45% opacity, Edit/Done pill. Settings: key bar, chip groups instead of `<select>` (chosen for 48dp targets), banner colours.

## Deviations from plan and mockups, for your review

- Settings uses chip groups instead of dropdowns for sync interval and coefficient set.
- The key-life bar is days left divided by the smallest of 30/90/180/365 that fits, because the chosen lifetime isn't stored.
- The banner says "paste it in Settings" (the plan's wording) rather than the mockup's "below". For 0 or 1 days left it reads "expires today" / "in 1 day".
- Activity detail only shows "Couldn't read" for a failed FIT task, not for ACTIVITY_DETAIL failures.
- There is no launcher icon yet, so the default Android icon shows.
- Settings → Units shows the profile's `measurementSystem` (Metric when unknown). It is display-only.

## Known minor follow-ups (not done)

- Lint warnings: LogNotTimber ×5, MissingApplicationIcon, DataExtractionRules (harmless, since `allowBackup=false`).
- A WorkManager run doing a very long re-score after a sync may exceed the roughly 10-minute worker limit. The work then reruns.

## Suggested next steps after the emulator pass

1. Launcher icon (currently the default robot).
2. A "Retry failed" control in Settings for permanently FAILED sync tasks (today only reinstalling clears them).
3. Optional: a morning notification with recovery and strain target once last night's sleep syncs.
