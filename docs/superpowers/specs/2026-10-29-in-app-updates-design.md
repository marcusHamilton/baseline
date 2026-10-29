# Baseline: in-app updates from GitHub releases (design spec)

Date: 2026-10-29
Status: implemented (code in `app/src/main/java/dev/baseline/update/`); the on-device update test in §8 is still owed
Changes: main spec §9 (Settings and Today) and §12 (release build, and the hosts the app talks to: Baseline now also
calls `api.github.com`). These edits land with the implementation.

## 1. Intent

Releases are published as GitHub releases (`vX.Y`, one signed `Baseline-X.Y.apk` asset), and the phone is updated
by hand: open the release page, download the APK and tap it. This spec makes Baseline find new releases by itself
and install one with a single tap.

Decisions made with the owner:

- **One tap, in the app.** A background check finds the release. The owner taps Update, and the app downloads and
  verifies the APK and hands it to the system installer. This is not a silent background install, and not
  "notify and open the browser".
- **Shown in Settings and on Today.** Settings gets a version row with release notes, and Today gets a
  dismissable banner. There is no system notification for "update available".
- **The phone runs Android 14 or later.** The design still works down to minSdk 29. The API 31+ and 34+ calls are
  used only where they exist.

### Non-goals

- Silent or scheduled installs (`PackageInstaller.InstallConstraints`, API 34). They can come later on top of
  this, but they're the hardest part to test, and one tap is what was asked for.
- Pre-release channels, rollback and delta updates.
- Any change to how releases are signed. The release key stays on the owner's Mac.

## 2. What we're building on

| Fact | Consequence |
|---|---|
| The repo `marcusHamilton/baseline` is public. | `GET https://api.github.com/repos/marcusHamilton/baseline/releases/latest` works without a token, so no secret ships in the APK. The limit is 60 requests an hour per IP, and a daily check uses one. |
| `releases/latest` skips drafts and pre-releases. | Marking a GitHub release as a pre-release hides it from the app, which gives a safe way to stage a build. |
| Each asset carries `digest: "sha256:…"` and `size`. All three current releases have a digest. v0.02 and v0.03 named their asset `app-release.apk`, and v0.04 named it `Baseline-0.04.apk`. | The download can be checked against the exact bytes that were uploaded. The app picks the release's one `*.apk` asset rather than relying on a name. |
| Android installs an update only when its signing certificate matches the installed one. | Even a compromised download path can't install anyone else's code. The app's own checks (§5) only give clearer errors and catch mistakes such as a `-fake` build or a lower `versionCode`. |
| The installed app's installer of record is whatever sideloaded it (Files, a browser or adb). | The **first** in-app update always shows the system's "Update this app?" dialog. After that, Baseline is its own installer of record, and on API 31+ later updates can skip the dialog (§6). The platform keeps the right to ask anyway, so the dialog path is always handled. |
| `SyncWorker` already runs as an expedited worker that promotes itself to a `dataSync` foreground service, and the manifest already declares that service type. | The APK download reuses that pattern instead of relying on expedited work alone (§5). |
| Android restarts the app after an update, and any broadcast (`MY_PACKAGE_REPLACED` included) creates the `Application` first. | The post-update cleanup in §6 runs from `BaselineApp.onCreate`; no receiver is needed for it. |
| OkHttp, WorkManager, DataStore and kotlinx.serialization are already dependencies. | **No new dependencies.** |

## 3. User experience

**Settings → new "App" card** (at the bottom):

| State | Row |
|---|---|
| Up to date | `Version 0.04 · Up to date` · "Checked 2 h ago" · `Check now` |
| Update available | "Baseline 0.05 is available." · release notes (first 6 lines, then "Release notes ↗" opens the release page) · `Update (55 MB)` (decimal MB, as Android's storage screens count) |
| Downloading | `Downloading 0.05… 42 %` with a progress bar and `Cancel` |
| Waiting for permission | "Allow Baseline to install updates" with an `Allow` button that opens the system's *Install unknown apps* screen for Baseline |
| Ready / installing | `Installing 0.05…` while the system installer has it |
| Failed | A one-line reason ("Download didn't match the release checksum", "Not signed with the Baseline key", "No network") and `Retry` |
| Check failed | Same as the current state, plus "Couldn't check for updates (rate-limited / offline)". The app works normally and never shows a red error for this. |

It also gets a **"Check for updates daily"** switch, on by default. Turning it off stops the daily check,
while `Check now` still works.

**Today banner:** when an update is available and the owner hasn't dismissed *that version*, a quiet banner sits
under the key banner (same slot and style as `KeyBanner`, with neutral colours rather than red): "Baseline 0.05 is
available. Update · ✕". Update starts the same flow as Settings, and ✕ hides it until a newer tag appears. The
touch targets are at least 48dp; TalkBack reads the banner's line (a polite live region, so progress is announced)
and calls ✕ "Hide this update".

**The tap:** `Update` → (the first time only) the *Install unknown apps* permission → the download (with progress) →
verification → the system installer. The permission is checked **before** the download, so the owner isn't asked
after waiting for 55 MB. Granting it can restart the app process, so the "update requested" step is saved in
DataStore and the flow picks up again in `MainActivity.onResume`.

- The first update shows the system dialog once.
- Later updates install straight away while Baseline is open.
- The process restarts into the new version. Android does this for any update, and Room migrations run on next
  open as they always do.

## 4. Checking for a release

`update/UpdateCheckWorker`, a periodic WorkManager job:

- Runs every 24 h with `NetworkType.CONNECTED`. It's unique work (`KEEP`), enqueued from `BaselineApp` next to
  the sync schedule.
- Is cancelled when the switch is off.
- Never runs in debug builds unless the build sets `-PupdatesUrl` (§8).
- `Check now` enqueues a one-off run of the same worker. The UI never calls the network (main spec §3).

Each run:

1. `GET {BuildConfig.UPDATES_URL}`, which defaults to
   `https://api.github.com/repos/marcusHamilton/baseline/releases/latest`.
   - Headers: `Accept: application/vnd.github+json`, a `User-Agent: Baseline/<versionName>` (GitHub rejects
     requests without one), and `If-None-Match: <stored ETag>`, which keeps an unchanged answer to an empty `304`.
   - The client is its own plain `OkHttpClient` built from the container's base builder, with **no**
     `GarminAuthenticator` and no Garmin lock: this isn't Garmin traffic, and no Garmin credential may ever be
     attached to it.
2. `304` → only record `lastCheckedAt`.
3. `200` → parse with kotlinx.serialization into `Release(tag, name, body, htmlUrl, asset)`.
   - `asset` is the single `*.apk` asset (`name`, `size`, `digest`, `browser_download_url`).
   - With zero or several APKs, the release is ignored and the reason logged.
4. Compare versions: strip the `v` from the tag and compare it with `BuildConfig.VERSION_NAME` numerically by dot
   segment, so `0.10 > 0.09` and a suffix such as `-fake` is ignored. A newer tag becomes `available`; an equal or
   older one clears it. A tag that doesn't parse is ignored. The tag is only a hint: the APK's own `versionCode`
   decides (§5). Segment comparison treats `0.4` and `0.04` as equal, so tags keep the current two-digit form.
   The release body is Markdown and is shown as plain text, with headings and list markers stripped.
5. `403`/`429`, network errors and parse errors are recorded as `checkError`, with no retry storm (the next
   periodic run tries again). WorkManager's backoff is used only for network loss.

State lives in `update/UpdatePrefs`, on the app's DataStore next to `UserPrefs`, so no Room schema change is
needed: the daily-check switch (default on), the ETag, when it last checked and the last check error, the offered
release (tag, notes, page, APK URL, size, SHA-256), its `Phase` (`AVAILABLE`, `NEEDS_PERMISSION`, `DOWNLOADING`,
`READY`, `INSTALLING`, `FAILED`) with a failure message and the installer session id, and the dismissed tag.

## 5. Downloading and verifying

`update/UpdateDownloadWorker`:

- A one-off job (unique work `update-download`, replaced by a new start), on any connected network. The owner tapped Update, so a metered network is fine, and the button
  shows the size.
- It's enqueued expedited (`OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST`) so it starts at once, and then,
  like `SyncWorker`, it calls `setForeground` with a `FOREGROUND_SERVICE_TYPE_DATA_SYNC` "Downloading update"
  progress notification on its own low-importance "Updates" channel. Expedited work alone has a short time and quota budget,
  which a 55 MB download on a slow network can exceed. The foreground service starts while the owner is in the app,
  so background-start limits don't apply.
- There must be room for about twice the APK, because the file is copied into the installer session. Without it,
  the job fails with "Not enough free space".

Steps:

1. Stream `browser_download_url` (which redirects to GitHub's asset CDN; OkHttp follows it) into
   `noBackupFilesDir/updates/Baseline-X.Y.apk.part`, hashing as it goes. Progress goes out through `setProgress`.
2. **Check the size and SHA-256** against the asset's `size` and `digest`. On a mismatch, delete the file and fail
   with "Download didn't match the release checksum". If a future asset has no `digest`, the size, certificate and
   system checks still apply, and the missing digest is logged.
3. **Inspect the APK:** `PackageManager.getPackageArchiveInfo(path, GET_SIGNING_CERTIFICATES)`. It must have:
   - `packageName == "dev.baseline"`;
   - `longVersionCode > ` the installed `longVersionCode`;
   - a `versionName` without `-fake`, **unless the installed build is itself `-fake`** (§8). A real build never
     installs a fake one, and fake test builds can still update each other;
   - signing certificates equal to the installed package's (`signingInfo.apkContentsSigners`). If the archive
     reports no signing info on an old OS, skip this check and let the system's own update check decide.
4. Rename `.part` to `.apk` (only a verified file ever has that name) and set the phase to `READY`.

The SHA-256 check is the integrity check. The certificate check gives clear errors (and the system enforces it
anyway). The `versionCode` check rules out side-grades, which Android would refuse with a less useful error.

## 6. Installing

`update/SelfInstaller` wraps `PackageInstaller`:

1. (When `Update` is tapped, before the download.) Check `packageManager.canRequestPackageInstalls()`. If it's
   false, show the "Allow" state. `Allow` launches `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` with
   `package:dev.baseline`, and the flow resumes in `MainActivity.onResume` (§3).
2. Create a session with `SessionParams(MODE_FULL_INSTALL)`:
   - `setAppPackageName("dev.baseline")`;
   - `setRequireUserAction(USER_ACTION_NOT_REQUIRED)` (API 31+). This is a request, not a guarantee: the system
     still asks when Baseline isn't the installer of record (the first update) or when its own rules say so;
   - `setInstallReason(INSTALL_REASON_USER)`.

   It deliberately does **not** call `setRequestUpdateOwnership(true)` (API 34). That would need another permission,
   and it would make Android challenge the manual path (downloading the APK from the release page in a browser).
   That manual path is how the phone recovers if a release ships with a broken updater.
3. Copy the verified file into `session.openWrite`, then `commit` with an `IntentSender` for
   `update/InstallResultReceiver`. That's a non-exported receiver, with a `FLAG_MUTABLE` `PendingIntent` because
   the installer fills in its extras.
4. The receiver handles:
   - `STATUS_PENDING_USER_ACTION` (the first update, or when the OS wants a dialog): the receiver doesn't start an
     activity itself. It hands `EXTRA_INTENT` to `Updater.userAction`, and the visible `MainActivity` launches it.
     Commit only runs while Baseline is in the foreground (`Update` was tapped there, or the flow resumed in
     `onResume`), so background-start limits don't apply. A download that finishes while Baseline is in the
     background waits as `READY` ("Baseline 0.05 is ready to install" · Install) and installs when it comes back.
   - `STATUS_SUCCESS`: on a self-update this usually never arrives, because the process is killed.
   - Any `STATUS_FAILURE*`: map it to the Failed state with the installer's message.
5. **After the update,** `Updater.start()` in the new process's `BaselineApp.onCreate`: if `BuildConfig.VERSION_NAME`
   is at or past the offered tag, it deletes `updates/` and then clears the offer and the dismissed tag. If the
   process died while `INSTALLING` (no answer will come), it abandons the session and shows "The install didn't
   finish. Try again." A cancelled or failed install keeps the verified APK, so Retry doesn't download again.

Required manifest additions:

- `REQUEST_INSTALL_PACKAGES`. It does nothing until the owner turns on *Install unknown apps* for Baseline.
- `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, a normal (install-time) permission on API 31+, which
  `USER_ACTION_NOT_REQUIRED` requires.
- The receiver.

## 7. Code layout

New package `dev.baseline.update/`, wired in `AppContainer` like everything else:

| File | Role |
|---|---|
| `Release.kt` | `Release`, `parseRelease` (the `JsonElement` tree, like the Garmin parsers: no serialization plugin), `isNewer(tag, installed)` and `plainNotes`. |
| `ReleaseSource.kt` | A thin GET with an ETag that returns `NotModified` / `Found(release, etag)` / `Failed(message)`. It takes an `OkHttpClient` and a URL, so tests use MockWebServer. |
| `UpdatePrefs.kt` | The DataStore state in §4. |
| `Updater.kt` | The state machine: puts `UpdatePrefs` and the download's `WorkInfo` together into `StateFlow<UpdateUi>`, and exposes `primary()` (Update / Allow / Cancel / Install / Retry), `checkNow()`, `dismiss(tag)`, `setAutoCheck()`, `check()` and `download()` for the workers, and `onInstallResult()` for the receiver. Android sits behind `UpdatePlatform`, so the flow is unit-tested with a fake. |
| `AndroidUpdatePlatform.kt` | `UpdatePlatform` on a device: WorkManager, `PackageManager`, the `PackageInstaller` session and the *Install unknown apps* screen. |
| `UpdateWorkers.kt` | `UpdateCheckWorker` and `UpdateDownloadWorker` (§4 and §5). |
| `ApkVerifier.kt` | Size, hash, package, versionCode, `-fake` and certificate checks, as a pure function over `ApkFacts`. |
| `InstallResultReceiver.kt` | The steps in §6. |

UI:

- `ui/settings` gets `AppCard`.
- `ui/components` gets `UpdateBanner`.
- `TodayScreen` places it under `KeyBanner`.

R8: both worker classes need the same `-keepnames` and constructor rule as `SyncWorker` in `app/proguard-rules.pro`,
because WorkManager stores their class names. The receiver is kept by AAPT's manifest rules, and there are no
`@Serializable` classes.

## 8. Build, release process and testing

**Build:**

- A new `buildConfigField UPDATES_URL`, overridable with `-PupdatesUrl=…`, works like `-PgarminBaseUrl`.
- A build made with either property has `-fake` in its `versionName` and allows cleartext, because the fake
  server is plain `http`. Today the `cleartext` placeholder and the suffix only follow `garminBaseUrl`.
- A new `-PversionOverride=0.05`, honoured only together with `-PupdatesUrl`, sets `versionName` to `0.05` and
  `versionCode` to 5 (the releases' own mapping), so the update test can build successive versions without editing
  the real ones.
- The verifier refuses a `-fake` APK when the installed build is real, so a fake build that slipped into a release
  could never reach the phone through the updater.

**Release process (CLAUDE.md "Release signing"):** this now matters more, because the phone acts on it within a
day.

- `versionCode` **must** go up. The updater refuses a release whose code isn't higher, and the release would be
  silently stuck.
- Exactly one `.apk` asset, named `Baseline-X.Y.apk`.
- The release notes are what the app shows: keep the first lines readable on their own.
- To stage a build without offering it to the phone, publish it as a **pre-release**.
- The emulator smoke test before tagging (main spec §12) adds one step: install the previous release, then update
  to the new one through the app.

**Fake server:** `tools/fake_garmin.py --release-apk path/to/Baseline-test.apk --release-tag v9.99` also serves
`GET /repos/marcusHamilton/baseline/releases/latest` and the APK download, with a real size and SHA-256. To test the
whole flow on an emulator without GitHub:

1. Build **three** release APKs, N, N+1 and N+2 (with `-PversionOverride`). Build all of them with
   `-PupdatesUrl=http://127.0.0.1:8080/repos/marcusHamilton/baseline/releases/latest`, so each one keeps checking
   the fake server, and sign all three with the same throwaway key.
2. Install N from adb, then run `adb reverse tcp:8080 tcp:8080`.
3. Serve N+1 and update through Settings. This one shows the system dialog, because adb was the installer of
   record.
4. Restart the server with N+2 and update again. This one should install without a dialog; that's the check for
   §6.
5. Also cover: a release whose APK has a wrong digest, one signed with a different key, and one whose versionCode
   wasn't bumped. Each must stop with its own message and leave the installed app untouched.

**Unit tests (Robolectric, `app/src/test`):**

- Version comparison (`0.10` vs `0.09`, the `v` prefix, `-fake`, a malformed tag).
- Parsing a real `releases/latest` response saved as a fixture (zero, one or two APK assets).
- `ReleaseSource` against MockWebServer: 200, 304 with the ETag sent back, 403 rate-limited, no network.
- `ApkVerifier` cases: wrong hash, wrong size, wrong package, a lower or equal versionCode, `-fake`, a different
  signer.
- `Updater` state transitions against a fake `UpdatePlatform` and MockWebServer (`UpdaterTest`).
- The Settings card and the Today banner (Compose tests: states, 48dp targets, dismiss per tag).
- The post-update cleanup.

**Guards:**

- A `UpdatesUrlTest`, next to `GarminBaseUrlTest`, asserts that the default URL is the real repo.
- A test that the update `OkHttpClient` has no authenticator, so no Garmin credential can leak to GitHub.

## 9. Risks and open points

- **A bad release reaches the phone faster.** It's still one tap, never automatic, so the owner chooses when.
  There's no rollback short of uninstalling, which wipes data. That's why the emulator smoke test before tagging
  stays mandatory.
- **The first update prompts.** This is expected, because the installer of record changes once.
- **The API rate limit is shared per IP** (60/h, unauthenticated). One request a day plus manual checks is far
  below it. A `403` shows as "couldn't check", never as an error.
- **An updater bug can strand the phone on an old version.** The manual path (browser → APK) always remains,
  which is why update ownership isn't claimed (§6). An updater change gets the same emulator update test as a
  release.
- **Network footprint:** the app now talks to `api.github.com` and GitHub's asset CDN as well as Garmin and
  OpenFreeMap. That reveals the phone's IP and that it runs Baseline. The settings switch turns the check off.
- **Not verified yet:** the no-dialog second update on a real Android 14+ phone, which needs two real releases.
  The first real release that carries the updater still has to be installed by hand.
