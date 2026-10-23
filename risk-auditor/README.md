# On-Device App Risk Auditor

> Part of [AppSnitch](../README.md). This folder is the scoring engine; the phone UI lives in [`../appsnitch`](../appsnitch).

Hackathon PS-01 · Mobile Security.

Scans every installed app on an Android device, reads what each one is actually
allowed to do, and turns that into a risk score with reasons a non-technical
person can act on. Everything runs on the device; nothing is uploaded.

The three questions PS-01 asks are the acceptance test:

| Question | Where it is answered |
|---|---|
| **Q1** Which of my apps can do the most harm? | `DeviceReport.ranked` — every app, worst first |
| **Q2** Why is each one flagged? | `AppDetail.whyFlagged` — ranked plain-language reasons |
| **Q3** What can I do about it right now? | `AppDetail.actions` — ordered remediation steps |

> **Status:** this run is **engine only — no UI**, as instructed. Every model a
> screen would bind to exists and is serializable; the screens do not.
> **The APK was not built**: the authoring environment cannot reach
> `dl.google.com` or Maven Central. See `docs/LIMITATIONS.md` L-00 and
> `docs/PROGRESS.md` for exactly what ran and what did not.

---

## Architecture

Data flows one way. Layers 1-4 contain no Android imports at all, which is what
makes the scoring engine testable against hand-built fixtures with no device.

```
 ┌──────────────────────────────────────────────────────────────┐
 │  :app          AuditorFacade · DebugDumpActivity             │  M17
 ├──────────────────────────────────────────────────────────────┤
 │  :presentation DeviceReport · AppDetail · RemediationAdvisor │  M13
 │  :actions      IntentFactory          :report  PDF export    │  M14 M16
 │  :monitor      install watch · notifications                 │  M15
 ├──────────────────────────────────────────────────────────────┤
 │  :scoring      RiskEngine · rubric.json · CombinationRules   │  M10   ← pure
 │  :context      ArchetypeClassifier · ContextMismatchEngine   │  M9    ← pure
 │  :diff         DiffEngine             :store  snapshots      │  M12   ← pure
 │  :ml           MlScorer (inference)                          │  M11   ← pure
 ├──────────────────────────────────────────────────────────────┤
 │  :inventory    :permissions    :special-access               │  M4-M6
 │  :manifest-analysis            :hash-intel (ONLY networking) │  M7 M8
 ├──────────────────────────────────────────────────────────────┤
 │  :catalog      permission → category        :trust  installer│  M1 M2
 │  :core-model   the shared contract                           │  M0    ← pure
 └──────────────────────────────────────────────────────────────┘
      :fixture-apps  — dev-only inert test APKs                    M3
```

A module may only depend on modules above it in that list.

### Module map

| Module | Kind | Purpose |
|---|---|---|
| `core-model` | pure Kotlin | Serializable, versioned shared types |
| `catalog` | pure Kotlin | Permission → category, sensitivity, plain-language text |
| `trust` | pure Kotlin | Installer classification, MDM allowlist, device roles |
| `context` | pure Kotlin | "A torch app should not read your texts" |
| `scoring` | pure Kotlin | The risk engine and `rubric.json` |
| `store` | pure Kotlin | Snapshot persistence (JSON file) |
| `diff` | pure Kotlin | What changed since the last scan |
| `presentation` | pure Kotlin | UI-ready models. No UI. |
| `ml` | pure Kotlin | On-device inference + Python training pipeline |
| `hash-intel` | pure Kotlin | SHA-256 + threat intel. **The only module with network code.** |
| `inventory` | Android | Enumerate installed apps |
| `permissions` | Android | Declared vs granted, protection levels |
| `special-access` | Android | Accessibility, device admin, listeners, AppOps |
| `manifest-analysis` | Android | Exported components, cleartext, debuggable |
| `actions` | Android | Settings deep links and uninstall intents |
| `monitor` | Android | Install watching, notifications, periodic sweep |
| `report` | Android | PDF export |
| `app` | Android | Wiring + headless debug entry point |
| `fixture-apps` | Android | Dev-only inert fixtures. Never shipped. |

---

## The one design decision worth knowing

**Ordinary permissions are capped at 45 points in total.**

Without that cap the model is additive, and any feature-rich-but-legitimate app
— a messenger, a camera app — outscores a torch app holding one accessibility
service. Every app turns red, the ranking becomes noise, and Q1 goes unanswered.

With the cap, reaching High or Critical requires something qualitatively
different: special access, a known-dangerous combination, an unverified install
source, a context mismatch, or a threat-intelligence hit.

Verified: granting **every** non-special permission in the catalog, from Google
Play, scores 55 (Medium). A torch app holding SMS and arriving from outside a
store scores 90 (Critical).

---

## Build and test

### Requirements

JDK 17+, Android SDK (API 36), and network access to Maven Central and
`dl.google.com`.

### Everything

```bash
scripts/run_all_tests.sh      # harness + ML + Gradle + traceability
```

Steps whose prerequisites are missing are skipped with the command to run them,
rather than failing the whole run.

### Individually

```bash
tools/offline-verify/run.sh                  # pure-Kotlin logic, needs NO network
./gradlew test                               # JVM unit tests
./gradlew connectedDebugAndroidTest          # instrumentation, needs a device
python3 scripts/check_traceability.py        # every requirement → a test
python3 tools/render_rubric.py --check       # regenerate the rubric deliverable
```

### The APK

```bash
scripts/build_apk.sh          # → dist/risk-auditor-debug.apk
```

### Run it on a device

There is no UI yet, so the entry point is headless. It scans, writes JSON, and
prints a summary to logcat.

```bash
adb install -r dist/risk-auditor-debug.apk
adb shell am start -n com.riskauditor.app/.DebugDumpActivity
adb logcat -s RiskAuditorDump

# include system apps and compute APK fingerprints
adb shell am start -n com.riskauditor.app/.DebugDumpActivity \
    --ez include_system true --ez compute_hashes true

# pull the full scan
adb shell run-as com.riskauditor.app cat files/scan.json > scan.json
```

### Test fixtures

```bash
scripts/install_fixtures.sh
```

Installs 13 inert APKs that declare permissions and components but contain no
behaviour whatsoever. Several need their special access switched on by hand —
that is the point, since the scanner must distinguish "enabled" from "declared".

**Never run this on the judging device.** The demo runs on the organizer's test
kit, not on apps we chose.

---

## The offline verification harness

`tools/offline-verify/` compiles the real pure-Kotlin sources with the Kotlin
compiler bundled inside Gradle and runs 91 assertions against the real data
files, with **no third-party dependencies at all**.

It exists because this project was written in an environment that could not
download JUnit. Rather than ship a scoring engine that had never been executed,
the harness proves the logic works; it cannot test serialization or anything
touching Android, and `docs/PROGRESS.md` says so explicitly.

It is not a replacement for `./gradlew test`. Run both.

---

## Privacy

- Scanning is entirely on-device. No root, no adb needed by the user.
- The **only** network code in the project is in `:hash-intel`, and it is gated
  on explicit, revocable, default-off consent. `scripts/check_traceability.py`
  fails the build if a networking library appears in any other module.
- What a lookup sends: a 64-character SHA-256 fingerprint. Nothing else — no
  package name, no device identifier, no APK bytes. Asserted against a
  MockWebServer by inspecting the outgoing request.
- Every other feature works in airplane mode.
- No analytics, crash-reporting or advertising SDK is present.
- Cloud backup and device transfer are disabled: the snapshot store is an
  inventory of every app you have and what each can reach.

---

## Documentation

| File | What it is |
|---|---|
| `docs/SCORING_RUBRIC.md` / `.pdf` | PS-01 deliverable 02. Generated from `rubric.json` — do not hand-edit. |
| `docs/DEMO.md` | The 3-minute demo script, written for an unknown test kit |
| `docs/TRACEABILITY.md` | Every requirement → module → class → test |
| `docs/PROGRESS.md` | **What actually ran and what did not.** Read this first. |
| `docs/LIMITATIONS.md` | What this tool cannot do, and why |
| `docs/ASSUMPTIONS.md` | Decisions taken without asking |

---

## What this tool does not claim

It measures **capability, not behaviour**: how much harm an app *could* do, never
whether it *is* doing harm. An app can hold every permission in the catalog and
behave impeccably. Say this out loud during the demo — a tool that implies
otherwise teaches people to uninstall things they need and to trust things they
should not.
