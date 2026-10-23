#!/usr/bin/env bash
#
# DEV ONLY. Builds and installs the inert test fixtures.
#
# These APKs declare permissions and components but contain no behaviour at all
# (see fixture-apps/build.gradle.kts). They exist so detection can be tested
# without using real malware, which PS-01 forbids and which would be reckless
# on a shared judging device.
#
# Never run this on the organizer's judging device: the demo must run on THEIR
# test kit, not on apps we chose.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if ! command -v adb >/dev/null 2>&1; then
  echo "error: adb not found on PATH" >&2
  exit 2
fi

if [[ -z "$(adb devices | sed -n '2p')" ]]; then
  echo "error: no device or emulator attached" >&2
  exit 2
fi

GRADLE_CMD=./gradlew
[[ -x "$GRADLE_CMD" ]] || GRADLE_CMD=gradle

echo "Building all fixture flavors..."
$GRADLE_CMD :fixture-apps:assembleDebug

count=0
while IFS= read -r apk; do
  echo "  installing $(basename "$apk")"
  adb install -r -t "$apk" >/dev/null
  count=$((count + 1))
done < <(find fixture-apps/build/outputs/apk -name '*-debug.apk' | sort)

echo
echo "Installed $count fixture APKs."
echo
echo "Several fixtures need special access turned on BY HAND before they are"
echo "detectable as ENABLED rather than DECLARED_ONLY. That is the point: the"
echo "scanner must distinguish the two."
echo
echo "  Accessibility : Settings > Accessibility > Fixture Accessibility"
echo "  Device admin  : Settings > Security > Device admin apps"
echo "  Notifications : Settings > Notifications > Device & app notifications"
echo "  Overlay       : Settings > Apps > Special app access > Display over other apps"
echo
echo "Uninstall them all afterwards:"
echo "  adb shell pm list packages com.riskauditor.fixtures | sed 's/package://' | xargs -n1 adb uninstall"
