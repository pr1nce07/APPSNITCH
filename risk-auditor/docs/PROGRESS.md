# Progress and execution report

**Date:** 2026-10-16
**Scope of this run:** engine only, no UI (as instructed).

This file separates **what was actually executed** from **what was written but
not run**. Nothing below is claimed to pass unless it ran and its output was
seen.

---

## Environment

| Tool | Available | Version |
|---|---|---|
| JDK | yes | OpenJDK 21.0.12.1 |
| Gradle | yes | 8.14.3 (bundled Kotlin compiler 2.0.21 borrowed by the harness) |
| Kotlin compiler | yes | 2.0.21, via Gradle's `kotlin-compiler-embeddable` |
| Python | yes | 3.13.16 with scikit-learn 1.9.1, numpy 2.5.3, reportlab 5.0.1 |
| Android SDK | **no** | cannot be installed — `dl.google.com` is refused |
| Maven Central | **no** | `repo.maven.apache.org` refused (HTTP 403, policy denial) |
| Google Maven | **no** | `dl.google.com` refused |
| Gradle distributions / plugin portal | **no** | refused |
| adb / emulator | **no** | not present and not installable |

PyPI and npm **are** reachable, which is why the Python pipeline could be run.

---

## What was executed, and passed

### 1. Offline verification harness — 91 assertions, 0 failures

```bash
tools/offline-verify/run.sh
```

Compiles the real production sources of `:core-model`, `:catalog`, `:trust`,
`:context`, `:scoring`, `:ml`, `:store`, `:diff`, `:presentation` and
`:report` (layout only) with Gradle's bundled Kotlin compiler, against
annotation stubs in place of kotlinx-serialization, and runs a dependency-free
assertion suite over the **real** `rubric.json`, `permission_catalog.json`,
`trusted_tools.json` and `model.json`.

| Suite | Assertions | Result |
|---|---:|---|
| Enum serial names / rubric coverage | 2 | pass |
| Catalog (M1) | 8 | pass |
| Trust (M2) | 9 | pass |
| Context mismatch (M9) | 9 | pass |
| Rubric configuration (M10) | 7 | pass |
| Risk engine — calibration profiles (M10) | 10 | pass |
| Risk engine — invariants (M10) | 12 | pass |
| Store, diff, presentation, report layout (M12, M13, M16) | 23 | pass |
| On-device ML inference (M11) | 11 | pass |
| **Total** | **91** | **0 failures** |

Notable results:

- Score stayed within 0..100 across **2,000 randomised profiles**.
- Every HIGH/CRITICAL result carried **≥3 reasons** across 2,000 profiles.
- Monotonicity held across 400 profiles: adding a granted dangerous permission
  never lowered a score.
- The banned-jargon linter passed across 600 profiles × every reason emitted.
- Each of the 14 combination rules fired on its positive case and not on its
  negative case.
- **Q1, Q2 and Q3 are covered end to end** by the pipeline suite: a three-app
  device scan produces a correct ranking, a flagged app's detail carries three
  jargon-free reasons, and remediation steps come back in an order Android will
  actually accept.
- The remediation ordering is verified: device admin, accessibility and overlay
  all precede uninstall.

### 2. ML training pipeline

```bash
cd ml/training && python3 train.py
```

Ran end to end on synthetic data. 4,000 samples, 30 features. 5-fold stratified
CV: logistic regression precision 0.9955, recall 0.9930, F1 0.9942, ROC-AUC
0.9995; random forest F1 0.9920 for comparison.

**These metrics are meaningless as malware-detection numbers.** The synthetic
generator is trivially separable. The model is flagged `"synthetic": true` and
must be retrained on a real dataset before any demo that shows it. See
`docs/LIMITATIONS.md` L-07.

### 3. Kotlin/Python parity

Maximum probability delta across 40 golden vectors: **1.2e-17** (tolerance
1e-6). The Kotlin port of the model reproduces scikit-learn exactly.

### 4. Rubric deliverable

```bash
python3 tools/render_rubric.py --check
```

Generated `docs/SCORING_RUBRIC.md` and `docs/SCORING_RUBRIC.pdf`, verified as
**exactly one page** and visually checked for overflow and column alignment.

### 5. Traceability

```bash
python3 scripts/check_traceability.py
```

78 requirement rows, 74 mappings checked. **43 VERIFIED, 34 NOT-RUN, 1 BLOCKED.**
Structural constraints all pass: network code confined to `:hash-intel`,
`QUERY_ALL_PACKAGES` declared, no fixture package names in scoring logic, no
real malware hashes shipped, rubric generated rather than hand-written, all 14
combination rules paired between JSON and Kotlin.

---

## Calibration table

Produced by the real engine over the real rubric:

| Profile | Score | Band | Reasons |
|---|---:|---|---:|
| Clean app (Play, internet only) | 0 | LOW | 2 |
| Game (Play, storage + network + boot) | 9 | LOW | 5 |
| Camera app (Play, camera + mic + location + storage) | 39 | MEDIUM | 5 |
| Messenger (Play, broad access, no role) | 40 | MEDIUM | 10 |
| Messenger holding the default-SMS role | 29 | LOW | 5 |
| Every non-special permission granted (Play) | 55 | MEDIUM | 11 |
| Verified MDM with device admin (Play) | 10 | LOW | 4 |
| Unverified app with device admin (sideloaded) | 60 | HIGH | 4 |
| Torch holding SMS + contacts + call log (sideloaded) | 90 | CRITICAL | 6 |
| Banking trojan (accessibility + overlay, sideloaded, hidden) | 100 | CRITICAL | 10 |
| Stalkerware (hidden, camera/mic, background location, boot) | 100 | CRITICAL | 8 |
| Clean app with a MALICIOUS hash verdict | 80 | CRITICAL | 3 |

This is the shape PS-01 needs: legitimate apps spread across Low and Medium,
genuinely dangerous profiles isolated in Critical. Granting *every* non-special
permission still only reaches 55, which is the permission cap doing its job.

---

## What was written but NOT run

Every item below has complete source. None of it was executed, because the
dependencies could not be downloaded. Run them on a networked machine with an
Android SDK.

| Module | Test class | Command |
|---|---|---|
| `:core-model` | `ModelSerializationTest` | `./gradlew :core-model:test` |
| `:catalog` | `PermissionCatalogTest` | `./gradlew :catalog:test` |
| `:scoring` | `RiskEngineTest`, `RubricTest` | `./gradlew :scoring:test` |
| `:store` | `SnapshotStoreTest` | `./gradlew :store:test` (core logic also covered by the harness) |
| `:diff` | `DiffEngineTest` | `./gradlew :diff:test` (also covered by the harness) |
| `:presentation` | `ReportBuilderTest`, `RemediationAdvisorTest` | `./gradlew :presentation:test` (also covered by the harness) |
| `:hash-intel` | `ThreatIntelServiceTest` | `./gradlew :hash-intel:test` |
| `:inventory` | `AppInventoryTest`, `IconLoaderTest` | `./gradlew :inventory:testDebugUnitTest` |
| `:permissions` | `PermissionExtractorTest` | `./gradlew :permissions:testDebugUnitTest` |
| `:special-access` | `SpecialAccessDetectorTest`, `RoleResolverTest` | `./gradlew :special-access:testDebugUnitTest` |
| `:manifest-analysis` | `ManifestAnalyzerTest` | `./gradlew :manifest-analysis:testDebugUnitTest` |
| `:actions` | `IntentFactoryTest` | `./gradlew :actions:testDebugUnitTest` |
| `:monitor` | `InstallMonitorTest`, `PackageEventReceiverTest`, `PeriodicSweepWorkerTest` | `./gradlew :monitor:testDebugUnitTest` |
| `:report` | `ReportLayoutTest`, `PdfReportWriterTest` | `./gradlew :report:testDebugUnitTest` |

Also unbuilt and unrun:

- **`:app`** — `AuditorFacade`, `DebugDumpActivity`, `RiskAuditorApplication`.
  Never compiled. Expect to fix compile errors on first build.
- **`:fixture-apps`** — 13 flavors, never built. Expect a manifest merge issue
  or two.
- **Instrumentation tests** — none written; `androidTest` directories are empty.
  This is a real gap against the brief and is listed below.
- **`dist/risk-auditor-debug.apk`** — not produced. See `docs/LIMITATIONS.md` L-00.

---

## Module status

| # | Module | Built | Logic verified here | Notes |
|---|---|---|---|---|
| M0 | `:core-model` | source only | partial | types compile in the harness; serialization untested |
| M1 | `:catalog` | source only | **yes** | 8 assertions, 88 permissions, 10 categories |
| M2 | `:trust` | source only | **yes** | 9 assertions including the spoof case |
| M3 | `:fixture-apps` | source only | no | 13 flavors, never built |
| M4 | `:inventory` | source only | no | Android API; fake + tests written |
| M5 | `:permissions` | source only | no | Android API; fake + tests written |
| M6 | `:special-access` | source only | no | Android API; fakes + tests written |
| M7 | `:manifest-analysis` | source only | no | Android API; tests written |
| M8 | `:hash-intel` | source only | no | MockWebServer tests written, unrun |
| M9 | `:context` | source only | **yes** | 9 assertions, positives and negatives |
| M10 | `:scoring` | source only | **yes** | 29 assertions incl. fuzz and monotonicity |
| M11 | `:ml` | source only | **yes** | 11 assertions, parity to 1.2e-17 |
| M12 | `:store` + `:diff` | source only | **yes** | in-memory store and diff engine executed; `JsonFileSnapshotStore` not (needs serialization) |
| M13 | `:presentation` | source only | **yes** | `ReportBuilder` and `RemediationAdvisor` executed, Q1/Q2/Q3 covered |
| M14 | `:actions` | source only | no | Robolectric tests written, unrun |
| M15 | `:monitor` | source only | no | tests written, unrun |
| M16 | `:report` | source only | partial | `ReportLayout` pagination executed; `PdfReportWriter` not (Android) |
| M17 | `:app` | source only | no | never compiled |

`:store`, `:diff`, `:presentation` and `:report` are pure Kotlin and *could*
have been added to the offline harness. They were not, for time; adding them is
the cheapest next win and would raise genuinely-verified coverage substantially.

---

## Known gaps against the brief

1. **No APK.** Blocking, environmental. `docs/LIMITATIONS.md` L-00.
2. **No instrumentation tests.** The brief asks for `androidTest` coverage
   against the fixtures. Directories exist; no tests written. Writing them
   without any ability to run them seemed lower value than making the unit
   tests good, but it is a real gap.
3. **No real ML model.** `docs/LIMITATIONS.md` L-07.
4. **No certificate pins in the trust allowlist.** `docs/LIMITATIONS.md` L-05.
5. **The local blocklist is empty.** `docs/LIMITATIONS.md` L-11.

---

## Next steps, in order of value

1. Build on a machine with an Android SDK; fix the compile errors that a
   never-compiled `:app` and `:fixture-apps` certainly contain.
2. Run the full JUnit suite and record real results here.
3. Write the instrumentation tests against the fixtures.
4. Build the UI on `DeviceReport` and `AppDetail` — the data is all there.
5. Train the ML model on a real permission dataset.
6. Populate certificate pins and the fixture blocklist.
