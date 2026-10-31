#!/usr/bin/env bash
# One-time setup for a Claude Code cloud container (see docs/cloud-sessions.md). Idempotent; safe to re-run.
# Installs the Android SDK pieces this project builds against, writes local.properties, routes Maven Central
# through Google's mirror (Central answers 429 through the cloud proxy), pre-fetches the Robolectric runtimes the
# tests need, and makes a throwaway signing key for R8 and update testing. With /dev/kvm it also sets up an emulator.
# Not for the owner's Mac.
#
#   tools/cloud_setup.sh            # SDK, mirror, Robolectric jars, test key (~2 min on a fresh container)
#   tools/cloud_setup.sh --warm     # also resolve and compile once, so the first test run starts at once (~4 min more)
set -euo pipefail
SDK="${ANDROID_HOME:-$HOME/android-sdk}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
MIRROR="https://maven-central.storage-download.googleapis.com/maven2"
# The bootstrap cmdline-tools zip is old and can't see platform 37, so it only installs a current sdkmanager.
BOOTSTRAP="commandlinetools-linux-9862592_latest.zip"
TOOLS="cmdline-tools;19.0"
SDKMANAGER="$SDK/cmdline-tools/19.0/bin/sdkmanager"

if [ ! -x "$SDKMANAGER" ]; then
    mkdir -p "$SDK/cmdline-tools"
    tmp="$(mktemp -d)"
    curl -sSfo "$tmp/tools.zip" "https://dl.google.com/android/repository/$BOOTSTRAP"
    unzip -q "$tmp/tools.zip" -d "$tmp"
    yes | "$tmp/cmdline-tools/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
    "$tmp/cmdline-tools/bin/sdkmanager" --sdk_root="$SDK" "$TOOLS" >/dev/null
    rm -rf "$tmp"
fi
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
# AGP also wants build-tools 36 (its default); without it every first build downloads it.
"$SDKMANAGER" "platforms;android-37.0" "build-tools;37.0.0" "build-tools;36.0.0" "platform-tools" >/dev/null

echo "sdk.dir=$SDK" > "$REPO/local.properties"

mkdir -p "$HOME/.gradle/init.d"
cat > "$HOME/.gradle/init.d/maven-central-mirror.gradle.kts" <<EOF
// Cloud containers only (tools/cloud_setup.sh): Maven Central rate-limits the proxy, so try Google's mirror first.
fun RepositoryHandler.mirrorFirst() {
    val r = maven { name = "gcsMavenCentralMirror"; url = uri("$MIRROR/") }
    remove(r); addFirst(r)
}
settingsEvaluated {
    pluginManagement.repositories.mirrorFirst()
    dependencyResolutionManagement.repositories.mirrorFirst()
}
EOF

# Robolectric downloads its Android runtimes itself, straight from Maven Central (no Gradle, so no mirror), and gets
# 429s. Put them where it looks first. sdk=36 is robolectric.properties' default; WidgetLifecycleTest pins sdk 30.
# When Robolectric is upgraded, the names change: copy them from the "Failed to fetch maven artifact" error.
for artifact in 16-robolectric-13921718-i7 11-robolectric-6757853-i7; do
    dir="$HOME/.m2/repository/org/robolectric/android-all-instrumented/$artifact"
    [ -s "$dir/android-all-instrumented-$artifact.jar" ] && continue
    mkdir -p "$dir"
    for ext in pom jar; do
        curl -sSfo "$dir/android-all-instrumented-$artifact.$ext" "$MIRROR/org/robolectric/android-all-instrumented/$artifact/android-all-instrumented-$artifact.$ext" \
            || echo "warning: couldn't pre-fetch Robolectric $artifact.$ext; the tests that need it will try Maven Central themselves" >&2
    done
done

# A throwaway key for signing test builds (R8 smoke tests, the in-app update test in CLAUDE.md). Never the release key.
if [ ! -s /tmp/test.jks ]; then
    keytool -genkeypair -keystore /tmp/test.jks -storepass testtest -keypass testtest -alias test -keyalg RSA -keysize 2048 \
        -validity 3650 -dname "CN=Baseline test" >/dev/null 2>&1
fi

# An emulator needs hardware virtualisation; without /dev/kvm it boots for half an hour and then ANRs (cloud-sessions.md).
if [ -e /dev/kvm ] && [ -r /dev/kvm ]; then
    IMAGE="system-images;android-35;google_apis;x86_64"
    "$SDKMANAGER" "emulator" "$IMAGE" >/dev/null
    if ! "$SDK/cmdline-tools/19.0/bin/avdmanager" list avd -c | grep -qx Medium_Phone_API_35; then
        echo no | "$SDK/cmdline-tools/19.0/bin/avdmanager" create avd -n Medium_Phone_API_35 -k "$IMAGE" -d medium_phone >/dev/null
    fi
    echo "KVM found: emulator and the Medium_Phone_API_35 AVD are ready (start it with: \$ANDROID_HOME/emulator/emulator -avd Medium_Phone_API_35 -no-window -no-audio &)."
else
    echo "No /dev/kvm: no emulator in this container. Hand on-device checks to a local session (docs/cloud-sessions.md)."
fi

if [ "${1:-}" = "--warm" ]; then
    (cd "$REPO" && ANDROID_HOME="$SDK" ./gradlew -q --console=plain :app:compileDebugUnitTestKotlin)
    echo "Gradle dependencies resolved and the app and tests compiled once."
fi

echo "Android SDK at $SDK; local.properties written; Maven Central mirror configured; Robolectric runtimes and /tmp/test.jks ready."
echo "Next: ./gradlew :app:testDebugUnitTest   (system JDK 21 is fine; don't set the Android Studio JAVA_HOME)"
