# Working in a Claude Code cloud session

Cloud sessions run in a fresh Linux container. The CLAUDE.md commands assume the owner's Mac, so a few
things are different here. These notes come from the session that turned on R8 and wrote the fake Garmin
server (2026-10-26).

## Setup: run this first

```bash
tools/cloud_setup.sh            # add --warm to also resolve dependencies and compile once
```

It's idempotent: about 2 minutes on a fresh container, a few seconds after that. If a session starts without the SDK
(no `~/android-sdk`), run it first.

The owner can make every session start ready instead: the cloud environment's **Setup script** (environment menu in the
session's title bar → Edit) holds the whole of `tools/cloud_env_setup.sh`, pasted in. It has to be self-contained,
because it runs before the repo is cloned. So it sets `ANDROID_HOME` in `~/.bashrc` and `~/.profile` rather than writing
`local.properties`; Gradle falls back to that. It does everything below except `--warm`. When you change one of the
two scripts, change the other, and tell the owner to re-paste.

The scripts:

- Installs the Android SDK into `~/android-sdk` (platform 37.0, build-tools 37.0.0, platform-tools) and writes `local.properties`.
  The container has no SDK. The bootstrap `cmdline-tools` zip is too old to see `platforms;android-37.0`, so the script uses it
  only to install `cmdline-tools;19.0`. The platform package is `android-37.0`, not `android-37`.
- Adds a Gradle init script (`~/.gradle/init.d/`) that tries Google's Maven Central mirror first. Through the
  cloud proxy, `repo.maven.apache.org` answers **429 Too Many Requests** on a cold cache, which fails the first build.
- Pre-fetches Robolectric's Android runtimes (API 36, and API 30 for `WidgetLifecycleTest`) from the same mirror into
  `~/.m2`. Robolectric downloads them itself, outside Gradle, so the mirror doesn't cover it and the tests fail with
  "Failed to fetch maven artifact org.robolectric:android-all-instrumented:…". After a Robolectric upgrade, put the
  new names from that error into the script.
- Makes `/tmp/test.jks` (password `testtest`), a throwaway key for signing test builds (CLAUDE.md's update test and
  R8 smoke tests). It's never the release key.
- Installs the emulator and a `Medium_Phone_API_35` AVD **only if `/dev/kvm` exists**. No container has had it so far.

Don't export the Android Studio `JAVA_HOME` from CLAUDE.md, because that path doesn't exist here. The system JDK 21 works.

## What works and what doesn't

| Task | Cloud | Notes |
|---|---|---|
| `:app:testDebugUnitTest`, `:app:lintDebug`, `assembleDebug`/`assembleRelease` | Yes | First build ~4 min (downloads); tests ~1–2 min. |
| `tools/fake_garmin.py` | Yes | Check it with curl; see CLAUDE.md for the fake key and endpoints. |
| Signed release | No | The keystore is only on the owner's Mac. `assembleRelease` gives an unsigned APK. |
| Android emulator | **No** | No `/dev/kvm`. A software-emulated API 35 image takes ~35 min to boot, then system apps and the app itself ANR ("failed to complete startup"). Don't try it; hand on-device checks to a local session. |
| Real Garmin API | No | The owner's key lives in `~/.config/garmin/.env` on the Mac. Use the fake server. |

## Checking R8 and the baseline profile without a device

You can't run the minified APK here, but you can check what R8 did:

- `app/build/outputs/mapping/release/mapping.txt`: classes that must keep their names do so
  (`grep '^dev.baseline.sync.SyncWorker '`, `AppDatabase_Impl`, `BaselineApp`, `MainActivity`).
- `seeds.txt` and `usage.txt` in the same folder show what was kept and what was removed.
- Strings persisted by name (enum names such as `HRV_DAY` and `PENDING`) are still in the dex:
  `unzip -p app-release-unsigned.apk classes.dex | strings | grep -x HRV_DAY`.
- Profile: `app/build/intermediates/r8_art_profile/release/expandReleaseArtProfileWildcards/baseline-prof.txt` lists
  the expanded rules; `assets/dexopt/baseline.prof` must be in the APK.
- Then say plainly that the on-device smoke test is still owed, and give the local commands
  (CLAUDE.md, "Fake Garmin server").

## Pitfalls

- **Don't run the unit tests while something else is using the CPU** (a build, an emulator). Some tests wait on
  real-time timeouts (for example `TrendsViewModelTest.rangeEndFollowsTheTodayFlow`, 10 s) and flake under load.
  If a test fails, re-run it on an idle machine before you call it a regression.
- **A Robolectric runtime the script doesn't know** (after an upgrade, or a new `@Config(sdk = …)`) fails with 429
  ("Failed to fetch maven artifact org.robolectric:android-all-instrumented:…"). Add its name to `tools/cloud_setup.sh`
  and re-run the script.
- **`pkill -f <pattern>` kills your own shell** when the pattern is also in the command line you're running.
  Kill by PID instead.
- Long Gradle runs can go over the 10-minute tool timeout. Run them in the background, or use `-q` and filter the output.
- `app/build/` and `~/.gradle` are about 2 GB after one release build. The container's disk allowance is fixed, so delete
  `app/build` if writes start failing.
