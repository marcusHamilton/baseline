# Baseline: in-app updates from GitHub releases (design spec)

Date: 2026-10-29
Status: draft for review
Changes: main spec §1 (the network footprint: Baseline now also calls `api.github.com`), §9 (Settings and Today) and
§12 (release build). These edits land with the implementation.

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
| Each asset carries `digest: "sha256:…"` and `size`. | The download can be checked against the exact bytes that were uploaded. |
| Android installs an update only when its signing certificate matches the installed one. | Even a compromised download path can't install anyone else's code. The app's own checks (§5) only give clearer errors and catch mistakes such as a `-fake` build or a lower `versionCode`. |
| The installed app's installer of record is whatever sideloaded it (Files, a browser or adb). | The **first** in-app update always shows the system's "Update this app?" dialog. After that, Baseline is its own installer of record, and on API 31+ later updates skip the dialog while Baseline is in the foreground (§6). |
| `WidgetActionReceiver` already handles `MY_PACKAGE_REPLACED`. | The post-update hook exists, and the cleanup in §6 is added there or next to it. |
| OkHttp, WorkManager, DataStore and kotlinx.serialization are already dependencies. | **No new dependencies.** |

## 3. User experience

**Settings → new "App" card** (at the bottom):

| State | Row |
|---|---|
| Up to date | `Version 0.04 · Up to date` · "Checked 2 h ago" · `Check now` |
| Update available | `Version 0.05 available` · release notes (first ~6 lines, then "More" opens the release page) · `Update (55 MB)` |
| Downloading | `Downloading 0.05… 42 %` with a progress bar and `Cancel` |
| Waiting for permission | "Allow Baseline to install updates" with an `Allow` button that opens the system's *Install unknown apps* screen for Baseline |
| Ready / installing | `Installing 0.05…` while the system installer has it |
| Failed | A one-line reason ("Download didn't match the release checksum", "Not signed with the Baseline key", "No network") and `Retry` |
| Check failed | Same as the current state, plus "Couldn't check for updates (rate-limited / offline)". The app works normally and never shows a red error for this. |

It also gets a **"Check for updates automatically"** switch, on by default. Turning it off stops the daily check,
while `Check now` still works.

**Today banner:** when an update is available and the owner hasn't dismissed *that version*, a quiet banner sits
under the key banner (same slot and style as `KeyBanner`, with neutral colours rather than red): "Baseline 0.05 is
available. Update · ✕". Update starts the same flow as Settings, and ✕ hides it until a newer tag appears. The
touch targets are at least 48dp, and TalkBack reads "Update available, Baseline 0.05".

**The tap:** `Update` → the download (with progress) → verification → the system installer.

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
   - Headers: `Accept: application/vnd.github+json`, a `User-Agent: Baseline/<versionName>` (GitHub requires one),
     and `If-None-Match: <stored ETag>`.
   - The client is its own plain `OkHttpClient` built from the container's base builder, with **no**
     `GarminAuthenticator` and no Garmin lock: this isn't Garmin traffic, and no Garmin credential may ever be
     attached to it.
2. `304` → only record `lastCheckedAt`.
3. `200` → parse with kotlinx.serialization into `Release(tag, name, body, htmlUrl, asset)`.
   - `asset` is the single `*.apk` asset (`name`, `size`, `digest`, `browser_download_url`).
   - With zero or several APKs, the release is ignored and the reason logged.
4. Compare versions: strip the `v` from the tag and compare it with `BuildConfig.VERSION_NAME` numerically by dot
   segment, so `0.10 > 0.09` and a suffix such as `-fake` is ignored. A newer tag becomes `available`; an equal or
   older one clears it.
5. `403`/`429`, network errors and parse errors are recorded as `checkError`, with no retry storm (the next
   periodic run tries again). WorkManager's backoff is used only for network loss.

State lives in `prefs/UserPrefs` (DataStore), so no Room schema change is needed:

- `updatesEnabled` (default on).
- `updateEtag`.
- `updateLastCheckedAt`.
- `updateAvailable`: the serialized `Release` above, or null.
- `updateDismissedTag`.
- `updateCheckError`.

## 5. Downloading and verifying

`update/UpdateDownloadWorker`:

- An **expedited** one-off job (`OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST`), unique per tag, any network.
  The owner tapped Update, so a metered network is fine, and the button shows the size.
- `getForegroundInfo` reuses the existing sync notification channel with a "Downloading update" progress
  notification. Only API < 31 ever needs it.

Steps:

1. Stream `browser_download_url` (which redirects to `objects.githubusercontent.com`; OkHttp follows it) into
   `noBackupFilesDir/updates/Baseline-X.Y.apk.part`, hashing as it goes. Progress goes out through `setProgress`.
2. **Check the size and SHA-256** against the asset's `size` and `digest`. On a mismatch, delete the file and fail
   with "Download didn't match the release checksum".
3. **Inspect the APK:** `PackageManager.getPackageArchiveInfo(path, GET_SIGNING_CERTIFICATES)`. It must have:
   - `packageName == "dev.baseline"`;
   - `longVersionCode > ` the installed `longVersionCode`;
   - a `versionName` without `-fake` (§8);
   - signing certificates equal to the installed package's (`signingInfo.apkContentsSigners`). If the archive
     reports no signing info on an old OS, skip this check and let the system's own update check decide.
4. Rename `.part` to `.apk` and mark the state `ReadyToInstall(tag, path)`.

The SHA-256 check is the integrity check. The certificate check gives clear errors (and the system enforces it
anyway). The `versionCode` check rules out side-grades, which Android would refuse with a less useful error.

## 6. Installing

`update/SelfInstaller` wraps `PackageInstaller`:

1. Check `packageManager.canRequestPackageInstalls()`. If it's false, show the "Allow" state. `Allow` launches
   `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` with `package:dev.baseline`, and the flow resumes in
   `MainActivity.onResume`.
2. Create a session with `SessionParams(MODE_FULL_INSTALL)`:
   - `setAppPackageName("dev.baseline")`;
   - `setRequireUserAction(USER_ACTION_NOT_REQUIRED)` (API 31+);
   - `setRequestUpdateOwnership(true)` (API 34+), so other installers can't update Baseline without the owner
     confirming;
   - `setInstallReason(INSTALL_REASON_USER)`.
3. Copy the verified file into `session.openWrite`, then `commit` with an `IntentSender` for
   `update/InstallResultReceiver`. That's a non-exported receiver, with a `FLAG_MUTABLE` `PendingIntent` because
   the installer fills in its extras.
4. The receiver handles:
   - `STATUS_PENDING_USER_ACTION` (the first update, or when the OS wants a dialog): launch `EXTRA_INTENT`.
     Commit only runs while Baseline is in the foreground (`Update` was tapped there, or the flow resumed in
     `onResume`), so the launch isn't blocked by background-start limits. If the activity has gone, keep
     `ReadyToInstall` and the banner says "Ready to install · Install".
   - `STATUS_SUCCESS`: on a self-update this usually never arrives, because the process is killed.
   - Any `STATUS_FAILURE*`: map it to the Failed state with the installer's message.
5. **After the update,** the new process's `MY_PACKAGE_REPLACED` handler (and a cold-start check in
   `BaselineApp`, as a backstop) does the following: if `BuildConfig.VERSION_NAME` is at or past `updateAvailable`,
   it clears `updateAvailable` and `updateDismissedTag` and deletes `updates/`.

Required manifest additions:

- `REQUEST_INSTALL_PACKAGES`.
- `UPDATE_PACKAGES_WITHOUT_USER_ACTION` (a normal permission, API 31+), which allows the no-dialog path.
- The receiver.

Neither permission can be granted at runtime without the owner's action, and both only matter for this package.

## 7. Code layout

New package `dev.baseline.update/`, wired in `AppContainer` like everything else:

| File | Role |
|---|---|
| `Release.kt` | `@Serializable` DTOs for the API response, `Release`/`ReleaseAsset`, and `isNewer(tag, installedName)`. |
| `ReleaseSource.kt` | A thin GET with an ETag that returns `NotModified` / `Found(Release)` / `Error`. It takes an `OkHttpClient` and a URL, so tests use MockWebServer. |
| `UpdateRepository.kt` | Puts DataStore state and WorkManager `WorkInfo` together into a `StateFlow<UpdateState>`, and exposes `checkNow()`, `download()`, `cancel()`, `install()` and `dismiss(tag)`. |
| `UpdateCheckWorker.kt` / `UpdateDownloadWorker.kt` | The workers in §4 and §5. |
| `ApkVerifier.kt` | Hash, size, package, versionCode, `-fake` and certificate checks. Pure functions over bytes and `PackageInfo`, so they can be tested. |
| `SelfInstaller.kt` / `InstallResultReceiver.kt` | The steps in §6. |

UI:

- `ui/settings` gets `AppCard`.
- `ui/components` gets `UpdateBanner`.
- `TodayScreen` places it under `KeyBanner`.

R8: both worker classes need the same `-keepnames` and constructor rule as `SyncWorker` in `app/proguard-rules.pro`,
because WorkManager stores their class names. The `@Serializable` DTOs are covered by the serialization plugin's
rules, as the Garmin ones are.

## 8. Build, release process and testing

**Build:**

- A new `buildConfigField UPDATES_URL`, overridable with `-PupdatesUrl=…`, works like `-PgarminBaseUrl`.
- A build made with either property has `-fake` in its `versionName`.
- The verifier refuses to install any APK whose `versionName` contains `-fake`, so a fake build that slipped into a
  release could never reach the phone through the updater.

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

1. Build two release APKs, N and N+1, and sign both with the same throwaway key.
2. Install N built with `-PupdatesUrl=http://127.0.0.1:8080/repos/marcusHamilton/baseline/releases/latest`.
3. Run `adb reverse tcp:8080 tcp:8080`.
4. Update to N+1 through Settings, and repeat to check that the second update skips the dialog.

**Unit tests (Robolectric, `app/src/test`):**

- Version comparison (`0.10` vs `0.09`, the `v` prefix, `-fake`, a malformed tag).
- Parsing a real `releases/latest` response saved as a fixture (zero, one or two APK assets).
- `ReleaseSource` against MockWebServer: 200, 304 with the ETag sent back, 403 rate-limited, a timeout.
- `ApkVerifier` cases: wrong hash, wrong size, wrong package, a lower or equal versionCode, `-fake`, a different
  signer.
- `UpdateRepository` state transitions.
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
  below it, and `304`s are cheap. A `403` shows as "couldn't check", never as an error.
- **Network footprint:** the app now talks to `api.github.com` and GitHub's asset CDN as well as Garmin and
  OpenFreeMap. That reveals the phone's IP and that it runs Baseline. The settings switch turns the check off.
- **Not verified yet:** the no-dialog second update on a real Android 14+ phone, which needs two real releases.
  The first real release that carries the updater still has to be installed by hand.
