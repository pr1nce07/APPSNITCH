#!/usr/bin/env bash
#
# Offline verification harness.
#
# Compiles the pure-Kotlin production sources of :core-model, :catalog, :trust,
# :context and :scoring with the Kotlin compiler bundled inside Gradle, then runs
# a zero-dependency assertion suite against them.
#
# WHY THIS EXISTS: in an environment with no access to Maven Central or
# dl.google.com, `./gradlew test` cannot resolve JUnit, Truth, MockK, Robolectric
# or kotlinx-serialization. Rather than ship scoring logic that has never been
# executed, this harness compiles the real sources against annotation stubs and
# exercises them against the real rubric.json and permission_catalog.json.
#
# It does NOT replace the JUnit suites. Serialization, Android APIs and anything
# needing a device are out of scope here and are marked NOT VERIFIED in
# docs/PROGRESS.md.
#
# Usage:  tools/offline-verify/run.sh
# Exit:   0 if every assertion passed, 1 otherwise.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HARNESS="$ROOT/tools/offline-verify"
OUT="${TMPDIR:-/tmp}/risk-auditor-verify"

# Locate the Kotlin compiler bundled with Gradle.
GRADLE_BIN="$(command -v gradle || true)"
if [[ -z "$GRADLE_BIN" ]]; then
  echo "error: gradle not found on PATH; this harness borrows its bundled Kotlin compiler" >&2
  exit 2
fi
KTLIB="$(dirname "$(dirname "$(readlink -f "$GRADLE_BIN")")")/lib"
STDLIB="$(ls "$KTLIB"/kotlin-stdlib-*.jar | head -1)"

if [[ ! -f "$STDLIB" ]]; then
  echo "error: no kotlin-stdlib jar found under $KTLIB" >&2
  exit 2
fi

echo "Kotlin stdlib: $STDLIB"
rm -rf "$OUT"
mkdir -p "$OUT"

# Production sources, minus the four files that need kotlinx-serialization at
# compile time. The harness supplies API-identical replacements for the three
# loaders; ModelJson is not referenced by any of the logic under test.
SOURCES=()
while IFS= read -r f; do SOURCES+=("$f"); done < <(
  find "$ROOT/core-model/src/main/kotlin" \
       "$ROOT/catalog/src/main/kotlin" \
       "$ROOT/trust/src/main/kotlin" \
       "$ROOT/context/src/main/kotlin" \
       "$ROOT/scoring/src/main/kotlin" \
       "$ROOT/ml/src/main/kotlin" \
       "$ROOT/store/src/main/kotlin" \
       "$ROOT/diff/src/main/kotlin" \
       "$ROOT/presentation/src/main/kotlin" \
       "$ROOT/report/src/main/kotlin" \
       -name '*.kt' \
       ! -name 'ModelJson.kt' \
       ! -name 'CatalogLoader.kt' \
       ! -name 'TrustLoader.kt' \
       ! -name 'RubricLoader.kt' \
       ! -name 'ModelLoader.kt' \
       ! -name 'JsonFileSnapshotStore.kt' \
       ! -name 'PdfReportWriter.kt' \
  | sort
)

while IFS= read -r f; do SOURCES+=("$f"); done < <(
  find "$HARNESS/stubs" "$HARNESS/src" -name '*.kt' | sort
)

echo "Compiling ${#SOURCES[@]} Kotlin files..."
java -cp "$KTLIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  "${SOURCES[@]}" \
  -d "$OUT/classes" \
  -no-stdlib -no-reflect -nowarn \
  -cp "$STDLIB" 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true

if [[ ! -d "$OUT/classes" ]]; then
  echo "error: compilation produced no output" >&2
  exit 1
fi

# Data files are loaded off the classpath exactly as they are in production.
RESOURCES="$ROOT/catalog/src/main/resources:$ROOT/trust/src/main/resources:$ROOT/scoring/src/main/resources:$ROOT/ml/src/main/resources"

echo "Running harness..."
echo
java -Dfile.encoding=UTF-8 \
  -Dgolden.vectors="$ROOT/ml/src/test/resources/ml/golden_vectors.json" \
  -cp "$OUT/classes:$STDLIB:$RESOURCES" verify.MainKt
