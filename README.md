# Baseline

**Your own Garmin data, as a calm daily dashboard.**

Baseline is an Android app that reads your Garmin data through the Garmin Personal API and turns it into a WHOOP-style view of how you're doing: a recovery score, sleep performance, and daily strain on a 0–21 scale. Everything is calculated and stored on your phone. There is no backend, no account and no analytics.

> **Unofficial.** Baseline is an independent project. It is not affiliated with, endorsed by or connected to Garmin Ltd. or WHOOP. Garmin and Garmin Connect are trademarks of Garmin Ltd. or its subsidiaries; WHOOP is a trademark of Whoop, Inc. They are named here only to describe compatibility and inspiration. The scores are computed locally, are not Garmin's or WHOOP's metrics, and are not medical advice.

## What it does

- **Today:** recovery, sleep and strain dials, plus a reorderable list of stats such as HRV, resting heart rate, Body Battery, steps and stress.
- **Activities:** every workout with its stats, heart-rate zones, splits and, for outdoor activities, a route map.
- **Trends:** weeks and months of recovery, sleep versus sleep need, strain and heart rate.
- **Calendar:** a month grid with a ring per day coloured by recovery and a dot for each activity.
- **Records:** all-time bests such as your fastest 5K, reached from Activities.
- **Settings:** your Garmin key and its expiry, sync interval and Sync now.

## How it works

You paste a Garmin Personal API key once (the app links to where Garmin issues it). The key is encrypted with the Android Keystore and never leaves the device except to talk to Garmin. A background sync keeps your history up to date, one request at a time to stay inside rate limits. Raw responses are stored before they are parsed, so scoring can be improved later without refetching.

## Build and run

You need Android Studio (or a JDK 17) and an emulator or an Android 10+ device.

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug          # needs a device or emulator
./gradlew :app:testDebugUnitTest     # unit and Compose UI tests
```

**No Garmin key? Try it with the fake server.** `tools/fake_garmin.py` serves anonymized sample data:

```bash
uv run --python 3.12 --no-project python -I tools/fake_garmin.py
adb reverse tcp:8080 tcp:8080
./gradlew :app:installDebug -PgarminBaseUrl=http://127.0.0.1:8080
```

Then enter `fake-garmin-key` during onboarding. Builds made with `-PgarminBaseUrl` allow cleartext traffic and are named `…-fake`; never ship one.

## Privacy

Your data stays on your device. The only network calls are to the Garmin Personal API and, for route maps, to the OpenFreeMap tile server.

## Project docs

- [Design spec](docs/superpowers/specs/2026-10-24-baseline-android-design.md): behaviour, data sources and score formulas.
- [CONTRIBUTING.md](CONTRIBUTING.md): how to help.
- [CLAUDE.md](CLAUDE.md): architecture and invariants (also read by AI coding agents).

## Licence

MIT, see [LICENSE](LICENSE). Bundled fonts and other third-party material are covered in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
