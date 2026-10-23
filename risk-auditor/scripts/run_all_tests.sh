#!/usr/bin/env bash
#
# Runs every test suite in the project.
#
#   1. The offline verification harness (always runnable: no network needed)
#   2. The Kotlin/Python ML parity check (needs python3 + scikit-learn)
#   3. All JVM unit tests via Gradle (needs Maven Central access)
#   4. Instrumentation tests, if a device or emulator is attached
#
# Steps 3 and 4 are skipped with a clear message when their prerequisites are
# missing, rather than failing the whole run.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

FAILED=0
note()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
fail()  { printf '\033[31mFAIL\033[0m  %s\n' "$*"; FAILED=1; }
pass()  { printf '\033[32mOK\033[0m    %s\n' "$*"; }
skip()  { printf '\033[33mSKIP\033[0m  %s\n' "$*"; }

# ---------------------------------------------------------------- 1 ----
note "Offline verification harness (pure Kotlin logic)"
if ./tools/offline-verify/run.sh; then
  pass "offline harness"
else
  fail "offline harness"
fi

# ---------------------------------------------------------------- 2 ----
note "ML pipeline and Kotlin/Python parity"
if python3 -c "import sklearn" >/dev/null 2>&1; then
  if (cd ml/training && python3 -I train.py >/dev/null 2>&1); then
    pass "ml training pipeline (synthetic)"
    # Parity itself is asserted inside the offline harness above, which reads
    # the model.json and golden_vectors.json this step regenerates.
  else
    fail "ml training pipeline"
  fi
else
  skip "ml training (scikit-learn not installed: pip install -r ml/training/requirements.txt)"
fi

# ---------------------------------------------------------------- 3 ----
note "Gradle JVM unit tests"
if [[ -x ./gradlew ]]; then
  GRADLE_CMD=./gradlew
elif command -v gradle >/dev/null 2>&1; then
  GRADLE_CMD=gradle
else
  GRADLE_CMD=""
fi

if [[ -z "$GRADLE_CMD" ]]; then
  skip "gradle not available"
else
  if $GRADLE_CMD --offline tasks >/dev/null 2>&1 || $GRADLE_CMD tasks >/dev/null 2>&1; then
    if $GRADLE_CMD test testDebugUnitTest --continue; then
      pass "gradle unit tests"
    else
      fail "gradle unit tests"
    fi
  else
    skip "gradle cannot resolve dependencies (no Maven Central access?)"
    echo "      run this on a networked machine: $GRADLE_CMD test testDebugUnitTest"
  fi
fi

# ---------------------------------------------------------------- 4 ----
note "Instrumentation tests"
if command -v adb >/dev/null 2>&1 && [[ -n "$(adb devices | sed -n '2p')" ]]; then
  if [[ -n "$GRADLE_CMD" ]] && $GRADLE_CMD connectedDebugAndroidTest; then
    pass "instrumentation tests"
  else
    fail "instrumentation tests"
  fi
else
  skip "no device or emulator attached (adb devices is empty)"
  echo "      run on a device: ${GRADLE_CMD:-./gradlew} connectedDebugAndroidTest"
fi

# ---------------------------------------------------------------- 5 ----
note "Traceability"
if python3 scripts/check_traceability.py; then
  pass "traceability"
else
  fail "traceability"
fi

echo
if [[ $FAILED -eq 0 ]]; then
  printf '\033[32mAll runnable checks passed.\033[0m\n'
else
  printf '\033[31mSome checks failed. See above.\033[0m\n'
fi
exit $FAILED
