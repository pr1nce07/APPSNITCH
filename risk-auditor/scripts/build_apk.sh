#!/usr/bin/env bash
#
# Builds the debug APK and copies it to dist/risk-auditor-debug.apk.
#
# Requires an Android SDK and network access to dl.google.com and Maven Central.
# Without those this will fail, and that failure is the honest outcome: the APK
# genuinely cannot be produced in an environment that cannot fetch AGP.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]]; then
  echo "error: ANDROID_HOME / ANDROID_SDK_ROOT is not set." >&2
  echo "       Install the Android SDK (API $(grep -oP 'compileSdk = "\K[0-9]+' gradle/libs.versions.toml)) and set it." >&2
  exit 2
fi

GRADLE_CMD=./gradlew
[[ -x "$GRADLE_CMD" ]] || GRADLE_CMD=gradle

echo "Building debug APK..."
$GRADLE_CMD :app:assembleDebug

mkdir -p dist
APK="$(find app/build/outputs/apk/debug -name '*.apk' | head -1)"
if [[ -z "$APK" ]]; then
  echo "error: no APK produced" >&2
  exit 1
fi

cp "$APK" dist/risk-auditor-debug.apk
echo
echo "APK:  dist/risk-auditor-debug.apk"
echo "Size: $(du -h dist/risk-auditor-debug.apk | cut -f1)"
echo
echo "Install and run the headless scan:"
echo "  adb install -r dist/risk-auditor-debug.apk"
echo "  adb shell am start -n com.riskauditor.app/.DebugDumpActivity"
echo "  adb logcat -s RiskAuditorDump"
