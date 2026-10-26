# Contributing to Baseline

Thanks for helping. Baseline is a small, single-user Android app, so the bar is simple: keep it correct, keep it private, keep it tidy.

## Before you start

- For anything bigger than a bug fix, open an issue first so we can agree on the approach.
- Read the relevant section of the [design spec](docs/superpowers/specs/2026-10-24-baseline-android-design.md). If your change makes the code and the spec disagree, update one of them in the same pull request.
- [CLAUDE.md](CLAUDE.md) describes the architecture and the rules that must not break. Skim it first.

## Set up

Android Studio's bundled JDK is enough; there is no other tooling to install.

```bash
./gradlew :app:testDebugUnitTest      # the full unit suite
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
./gradlew spotlessApply              # format the code (ktlint); CI runs spotlessCheck
```

You need Android Studio (or a JDK 17) and an emulator or an Android 10+ device. `./gradlew :app:installDebug` installs on whatever is connected.

### Try it without a Garmin key

You don't need one. `tools/fake_garmin.py` serves anonymized sample data:

```bash
uv run --python 3.12 --no-project python -I tools/fake_garmin.py
adb reverse tcp:8080 tcp:8080
./gradlew :app:installDebug -PgarminBaseUrl=http://127.0.0.1:8080
```

Then enter `fake-garmin-key` during onboarding. Builds made with `-PgarminBaseUrl` allow cleartext traffic and are named `…-fake`; never ship one. Uninstall or clear data before going back to the real API. More recipes (time-zone changes, R8 smoke tests) are in [CLAUDE.md](CLAUDE.md#testing-on-an-emulator).

## Pull requests

- Branch from `main`, keep the change focused, and explain the why in the description.
- Use [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `test:`, `docs:`, `build:`.
- Every behaviour fix gets a regression test. New logic gets tests too. The suite must pass on any host time zone, so inject a `Clock` instead of reading the system zone.
- Run `./gradlew spotlessApply` before you commit; CI fails on unformatted code.
- Match the surrounding code: naming, comment density and Compose patterns. Don't add a dependency without a reason.
- Touch targets stay at least 48dp, and anything interactive keeps its TalkBack description.
- A change to the Room schema needs a version bump, a migration and a new exported schema in `app/schemas/`.

## Rules that protect users

- **The API key is a secret.** Never log it or put it in a message, exception or test output.
- **One Garmin request at a time.** Everything goes through `GarminClient`.
- **A key being tested never touches the vault** until it passes.

The reasons for these are in [CLAUDE.md](CLAUDE.md#invariants-dont-break-these).

## Never include personal data

This repository is public. Do not commit, attach to an issue, or paste into a pull request any real Garmin response or `.fit` file, user or device IDs, names, email addresses, GPS tracks, place names, home paths or keys. That includes your own data.

- Test fixtures are anonymized. Use `tools/anonymize.py` for JSON and `tools/anonymize_fit.py` for `.fit` files, and use made-up places for any route or map in a test.
- When you report a bug, redact IDs, coordinates and locations from logs and screenshots.
- Before committing changes to fixtures, docs or tools, run the two scans in [CLAUDE.md](CLAUDE.md#no-personal-data-in-the-repo). Both must print nothing.

## Reporting security problems

If you find a way to leak the API key or someone's data, please don't open a public issue. Use GitHub's private vulnerability reporting on this repository.

## Licence

By contributing you agree that your work is released under the [MIT licence](LICENSE).
