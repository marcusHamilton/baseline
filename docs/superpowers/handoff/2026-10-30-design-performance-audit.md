# Handoff: design and performance audit (2026-10-30)

A cloud session audited the whole app for design, accessibility and performance problems and fixed what it found.
Branch `claude/design-performance-audit-1d2c0l`. No emulator in the container, so the on-device checks below are still owed.

## Found on the base branch

- `lintDebug` failed on an unchanged tree: AGP 9.5.0 shipped and `AndroidGradlePluginVersion` is an error under
  `warningsAsErrors`. The three version-currency checks are now disabled in `app/build.gradle.kts`, with the reason.

## Design and accessibility

| Area | Problem | Fix |
|---|---|---|
| Today dials | 96 + 132 + 96 dp plus padding is 364 dp: on a 360 dp phone Strain was squeezed, at 320 dp to about half size | `dialScale` scales all three together when there is no room; unchanged at full width |
| `Dial` | Fixed-sp number in a fixed-dp ring clipped at very large font scales (about 3.0) | `TextAutoSize.StepBased`, identical at the default scale |
| Today insight | "Strain target today" on past days | "Strain target" when not today |
| Today header | Read `LocalDate.now()` during composition | `TodayUiState.today` from the ViewModel |
| Sleep | Bed and wake used the zone captured when the screen opened (`ZoneId.systemDefault()`) | `SleepViewModel` takes `dateTicker.zone` |
| Trends | Charts gave no hint of the span | Start date and "Today" under every chart |
| Calendar | Legend clipped when narrow or at large font scale | `FlowRow` |
| Onboarding | Stock Material styling, unlike the same form in Settings | Shared `ui/settings/KeyFormStyle.kt` |
| Onboarding, Settings | The keyboard could cover "Save and test" | `imePadding()`; the Scaffold content consumes its insets |
| `MainActivity` | API 29–34 weren't edge-to-edge like 35+ | `enableEdgeToEdge` with dark bar styles |
| All screens | No TalkBack headings | `heading()` on screen, section and card titles |

## Performance

- `Repository` flows now drop repeated Room emissions (`distinctUntilChanged`; blobs are compared by content before
  any unpack or parse). During a backfill every write used to rebuild every open screen's state, and every raw
  response re-parsed the profile JSON once per subscriber.
- Widget snapshots read this week's activities and the latest workout instead of the whole history.
- `ScorePipeline` computes each day's load once per run and reuses the calibration's `Loads` for the re-score
  (previously every stream was unpacked twice on `recalibrateAndRescore`), and caches per-stream TRIMP and coverage in
  memory. Rough JVM timing on the fixtures with synthetic streams: `recalibrateAndRescore` about 2.2× faster; a plain
  `recompute` barely changed (it is dominated by scoring and the write). Scores are bit-for-bit unchanged.

## Not verified (needs a device)

- Keyboard over the key field in Onboarding and Settings; system bars on an API 29–34 device.
- The Today dials on a 360 dp phone and with Display size raised; TalkBack heading navigation on each screen (the Today
  day title is inside a clickable column, so it probably reads as heading plus button).
- A minified release: no R8 or baseline-profile change was made, but `enableEdgeToEdge` and the new text auto-size
  are new code paths at startup.
