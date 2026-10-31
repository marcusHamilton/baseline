#!/bin/bash
# Baseline: cloud environment setup script. Runs before the repo is cloned, so it needs nothing from it.
# Paste this whole file into the environment's Setup script. It mirrors tools/cloud_setup.sh: keep the two in step.
set -euo pipefail

SDK="$HOME/android-sdk"
MIRROR="https://maven-central.storage-download.googleapis.com/maven2"
SDKMANAGER="$SDK/cmdline-tools/19.0/bin/sdkmanager"

# 1. Android SDK: platform 37, build-tools 37 and 36, platform-tools. The bootstrap cmdline-tools zip is too old to see
#    platform 37, so it only installs a current sdkmanager.
if [ ! -x "$SDKMANAGER" ]; then
    mkdir -p "$SDK/cmdline-tools"
    tmp="$(mktemp -d)"
    curl -sSfo "$tmp/tools.zip" https://dl.google.com/android/repository/commandlinetools-linux-9862592_latest.zip
    unzip -q "$tmp/tools.zip" -d "$tmp"
    yes | "$tmp/cmdline-tools/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
    "$tmp/cmdline-tools/bin/sdkmanager" --sdk_root="$SDK" "cmdline-tools;19.0" >/dev/null
    rm -rf "$tmp"
fi
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
# AGP also wants build-tools 36 (its default); without it every first build downloads it.
"$SDKMANAGER" "platforms;android-37.0" "build-tools;37.0.0" "build-tools;36.0.0" "platform-tools" >/dev/null

# 2. ANDROID_HOME for every shell. The repo has no local.properties yet; Gradle falls back to this.
for rc in "$HOME/.bashrc" "$HOME/.profile"; do
    grep -q 'ANDROID_HOME=' "$rc" 2>/dev/null || cat >> "$rc" <<EOF
export ANDROID_HOME="$SDK"
export PATH="\$PATH:$SDK/platform-tools:$SDK/build-tools/37.0.0"
EOF
done

# 3. Gradle tries Google's Maven Central mirror first: Central answers 429 through the cloud proxy.
mkdir -p "$HOME/.gradle/init.d"
cat > "$HOME/.gradle/init.d/maven-central-mirror.gradle.kts" <<EOF
// Cloud containers only: Maven Central rate-limits the proxy, so try Google's mirror first.
fun RepositoryHandler.mirrorFirst() {
    val r = maven { name = "gcsMavenCentralMirror"; url = uri("$MIRROR/") }
    remove(r); addFirst(r)
}
settingsEvaluated {
    pluginManagement.repositories.mirrorFirst()
    dependencyResolutionManagement.repositories.mirrorFirst()
}
EOF

# 4. Robolectric fetches its Android runtimes itself, outside Gradle (so not via the mirror), and gets 429s.
#    sdk=36 is robolectric.properties' default; WidgetLifecycleTest pins sdk 30. After a Robolectric upgrade, take the
#    new names from the "Failed to fetch maven artifact" error.
for artifact in 16-robolectric-13921718-i7 11-robolectric-6757853-i7; do
    dir="$HOME/.m2/repository/org/robolectric/android-all-instrumented/$artifact"
    [ -s "$dir/android-all-instrumented-$artifact.jar" ] && continue
    mkdir -p "$dir"
    for ext in pom jar; do
        curl -sSfo "$dir/android-all-instrumented-$artifact.$ext" \
            "$MIRROR/org/robolectric/android-all-instrumented/$artifact/android-all-instrumented-$artifact.$ext" \
            || echo "warning: couldn't pre-fetch Robolectric $artifact.$ext" >&2
    done
done

# 5. A throwaway key for signing test builds (R8 smoke tests, CLAUDE.md's update test). Never the release key.
[ -s /tmp/test.jks ] || keytool -genkeypair -keystore /tmp/test.jks -storepass testtest -keypass testtest -alias test \
    -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=Baseline test" >/dev/null 2>&1 || true

# 6. An emulator only with hardware virtualisation; without /dev/kvm it boots for half an hour and then ANRs.
if [ -r /dev/kvm ]; then
    IMAGE="system-images;android-35;google_apis;x86_64"
    "$SDKMANAGER" "emulator" "$IMAGE" >/dev/null
    "$SDK/cmdline-tools/19.0/bin/avdmanager" list avd -c | grep -qx Medium_Phone_API_35 \
        || echo no | "$SDK/cmdline-tools/19.0/bin/avdmanager" create avd -n Medium_Phone_API_35 -k "$IMAGE" -d medium_phone >/dev/null
    echo "KVM: emulator and Medium_Phone_API_35 ready."
else
    echo "No /dev/kvm: no emulator in this environment."
fi

echo "Baseline cloud setup done: SDK at $SDK, Maven mirror, Robolectric runtimes, /tmp/test.jks."
