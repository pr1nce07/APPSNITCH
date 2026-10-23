# Limitations

What this project cannot do, and why. Written plainly, because a security tool
that overstates what it knows is worse than one that admits the gaps.

---

## L-00 · The APK could not be built in the authoring environment · **BLOCKING**

The project was written in a sandbox whose egress proxy refuses
`dl.google.com`, `repo.maven.apache.org`, `services.gradle.org` and
`plugins.gradle.org` with HTTP 403 (policy denial, confirmed via the proxy's own
status endpoint). There is no Android SDK, no `aapt2` and no emulator, and none
can be fetched.

Consequences:

- `./gradlew :app:assembleDebug` cannot run, so **PS-01 deliverable 01 (working
  APK) was not produced here**.
- The Android Gradle Plugin, Robolectric, JUnit, Truth, MockK, MockWebServer and
  kotlinx-serialization could not be resolved, so **no JUnit suite was executed
  here**.

What was done instead: the pure-Kotlin logic was compiled with the Kotlin
compiler bundled inside Gradle and exercised by a dependency-free harness
(`tools/offline-verify/run.sh`). That genuinely executes the scoring engine,
catalog, trust model, context engine and ML inference against the real data
files. It cannot test serialization or anything touching Android.

On any machine with an Android SDK and normal network access:

```bash
scripts/build_apk.sh          # produces dist/risk-auditor-debug.apk
./gradlew test                # all JVM unit tests
./gradlew connectedDebugAndroidTest   # instrumentation tests, needs a device
```

See `docs/PROGRESS.md` for exactly what ran and what did not.

---

## L-01 · Test-library versions are unverified

AGP 9.4.0, Gradle 9.6.0, Kotlin 2.4.21, kotlinx.serialization 1.11.0 and
kotlinx.coroutines 1.11.0 were checked against their upstream release pages on
2026-10-16. The JUnit, Truth, MockK, Robolectric, AndroidX-test, WorkManager and
OkHttp versions in `gradle/libs.versions.toml` are **plausible pins that could
not be confirmed**, because Maven Central was unreachable. Confirm them with
`./gradlew dependencies --refresh-dependencies` before relying on the build.

---

## L-02 · This tool reads capability, not behaviour

The score answers *"how much harm could this app do?"*, never *"is this app
doing harm?"*. Nothing here runs, instruments or decompiles an app. An app may
hold every permission in the catalog and behave impeccably; another may hold two
and abuse both.

This is stated on the PDF report's methodology page and should be said out loud
during the demo. A tool that implies otherwise teaches people to uninstall
things they need and to trust things they should not.

---

## L-03 · AppOps cannot be read reliably for other packages

Overlay, install-packages, usage-stats and all-files access are granted through
AppOps rather than the permission system. `AppOpsManager.unsafeCheckOpNoThrow`
(API 29+, `checkOpNoThrow` before) is public, but for a package *other than your
own* its behaviour varies: some OEM builds return `MODE_DEFAULT` for everything,
some throw `SecurityException`.

Handling: both outcomes become `AccessState.UNKNOWN`, and the UI says
*"we could not check this on your phone"*. They are **never** reported as denied.
Claiming "no overlay access" when the truth is "this phone would not say" is the
kind of false reassurance that makes a security tool actively harmful.

Asserted by `SpecialAccessDetectorTest.unreadable_appops_is_unknown_not_denied`.

---

## L-04 · Play Store category is not available offline

PS-01 should-have 08 mentions the Play category. That is a network lookup, and
PS-01 also requires offline-first operation. The two cannot both be satisfied.

Offline-first wins. Archetype classification uses `ApplicationInfo.category`
(set by the developer, API 26+) plus whole-token keyword matching on the label
and package name. This is weaker than a real category, so the engine compensates
by only acting on **strong** classifications and by exempting archetypes that
legitimately need broad access. A missed mismatch is far cheaper than a false
one on a judging device.

---

## L-05 · The trusted-tool allowlist ships no certificate pins

`trust/src/main/resources/trusted_tools.json` lists a handful of well-known
security and accessibility apps with **empty** `cert_sha256` arrays, so they can
only be trusted when they also arrived from Google Play or an OEM store.

Shipping guessed certificate hashes would be a fabricated security claim, and a
wrong pin silently downgrades a genuine tool. Populate them from a device you
control before a production release:

```bash
apksigner verify --print-certs <apk> | grep SHA-256
```

A package-name match alone never confers trust. That is deliberate and tested
(`trustSuite.SPOOF_same_package_name_sideloaded_wrong_certificate_NOT_trusted`):
a sideloaded app impersonating an MDM agent is exactly the shape a real attack
takes and must score *higher*, not lower.

---

## L-06 · The active VPN cannot be identified

An app declaring a `VpnService` is not necessarily the device's active VPN, and
there is no public API to ask which app holds it without being that app. VPN
access is therefore always reported as `DECLARED_ONLY`, never `ENABLED`.

---

## L-07 · The bundled ML model is synthetic

`ml/src/main/resources/ml/model.json` was trained on data generated by
`ml/training/train.py`, not on real malware. It is flagged `"synthetic": true`,
and the reported metrics (CV F1 ≈ 0.99) **describe the generator, not the real
world**. A separable synthetic dataset trivially yields near-perfect scores;
that number means nothing about malware detection.

The pipeline is complete and runs. Train on a real public permission dataset
(UCI TUANDROMD or similar — permission vectors only, no APKs, check the licence)
before any demo that shows the ML column:

```bash
cd ml/training && python3 train.py --dataset tuandromd.csv --label-column Label
```

The Kotlin/Python parity test is real and passes to 1.2e-17, so the port is
correct whichever model is loaded.

---

## L-08 · Package-added broadcasts are not reliably delivered

From Android 8.0, manifest-declared receivers no longer receive most implicit
broadcasts, and OEM power management makes package broadcasts less dependable
still. A manifest-only install monitor works on the developer's phone and
silently does nothing on the judging device.

Delivery is therefore threefold: a runtime-registered receiver inside an opt-in
foreground service, a six-hourly WorkManager sweep that diffs the stored
snapshot, and `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` restart (both of which
*are* still delivered to manifest receivers).

**Not verified on a device.** Which package broadcasts the target API actually
delivers needs confirming on real hardware; the WorkManager path is what makes
the feature work regardless.

---

## L-09 · Cleartext detection can overstate

`FLAG_USES_CLEARTEXT_TRAFFIC` is the platform's resolved value and accounts for
the targetSdk default and `android:usesCleartextTraffic`. It does **not** account
for a network-security-config that permits cleartext for a few specific domains.
An app doing the careful thing can therefore read as "permits unencrypted
traffic". It is scored at only 4 points for that reason.

---

## L-10 · No UI exists in this run

By instruction, this run is engine-only. `:presentation` produces every model a
screen would bind to, and `DebugDumpActivity` drives a scan and writes JSON, but
there are no screens. PS-01's demo flow (must-have 06, the 3-minute live demo)
needs a UI built on top of `DeviceReport` and `AppDetail`.

---

## L-11 · The local blocklist is empty

`app/src/main/assets/local_blocklist.txt` ships with no hashes. PS-01 forbids
using or distributing real malware, so the only hashes that belong there are
those of this project's own inert fixtures. Populate it after building them:

```bash
sha256sum fixture-apps/build/outputs/apk/*/debug/*.apk
```

Until then a MALICIOUS verdict can only come from a network provider, which
needs consent and an API key.

---

## L-12 · Fixture APKs were never built

`:fixture-apps` is written but, like everything else Android, was never compiled
here. The flavor manifests and the inert components are untested against a real
build. Expect to fix a manifest merge error or two on first build; nothing in
them is load-bearing for the scoring logic.

---

## L-13 · Hash caching trades correctness for speed

`Sha256Hasher` caches on `(package, lastUpdateTime, size)`. An APK whose bytes
change without either changing would return a stale hash. On a non-rooted device
that essentially cannot happen — the system sets `lastUpdateTime` on every
install — and the alternative is re-hashing every APK on every scan, which costs
seconds. The trade-off is deliberate and tested
(`ThreatIntelServiceTest.hashing_is_cached_until_the_app_is_updated`).
