#!/usr/bin/env bash
# One-time setup for a Claude Code cloud container (see docs/cloud-sessions.md). Idempotent; safe to re-run.
# Installs the Android SDK pieces this project builds against, writes local.properties, and routes Maven Central
# through Google's mirror (Central answers 429 through the cloud proxy). Not for the owner's Mac.
set -euo pipefail
SDK="${ANDROID_HOME:-$HOME/android-sdk}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
# The bootstrap cmdline-tools zip is old and can't see platform 37, so it only installs a current sdkmanager.
BOOTSTRAP="commandlinetools-linux-9862592_latest.zip"
TOOLS="cmdline-tools;19.0"

if [ ! -x "$SDK/cmdline-tools/19.0/bin/sdkmanager" ]; then
    mkdir -p "$SDK/cmdline-tools"
    tmp="$(mktemp -d)"
    curl -sSfo "$tmp/tools.zip" "https://dl.google.com/android/repository/$BOOTSTRAP"
    unzip -q "$tmp/tools.zip" -d "$tmp"
    yes | "$tmp/cmdline-tools/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
    "$tmp/cmdline-tools/bin/sdkmanager" --sdk_root="$SDK" "$TOOLS" >/dev/null
    rm -rf "$tmp"
fi
yes | "$SDK/cmdline-tools/19.0/bin/sdkmanager" --licenses >/dev/null 2>&1 || true
"$SDK/cmdline-tools/19.0/bin/sdkmanager" "platforms;android-37.0" "build-tools;37.0.0" "platform-tools" >/dev/null

echo "sdk.dir=$SDK" > "$REPO/local.properties"

mkdir -p "$HOME/.gradle/init.d"
cat > "$HOME/.gradle/init.d/maven-central-mirror.gradle.kts" <<'EOF'
// Cloud containers only (tools/cloud_setup.sh): Maven Central rate-limits the proxy, so try Google's mirror first.
fun RepositoryHandler.mirrorFirst() {
    val r = maven { name = "gcsMavenCentralMirror"; url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
    remove(r); addFirst(r)
}
settingsEvaluated {
    pluginManagement.repositories.mirrorFirst()
    dependencyResolutionManagement.repositories.mirrorFirst()
}
EOF

echo "Android SDK at $SDK; local.properties written; Maven Central mirror configured."
echo "Next: ./gradlew :app:testDebugUnitTest   (system JDK 21 is fine; don't set the Android Studio JAVA_HOME)"
