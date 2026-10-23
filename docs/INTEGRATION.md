# AppSnitch + Risk Auditor: integration

The risk-auditor engine produces scores, reasons and fixes. The AppSnitch UI shows
whatever matches `appsnitch/docs/DATA-CONTRACT.md`. This change connects them.

## What changed

| File | Change |
|---|---|
| `risk-auditor/presentation/.../ContractMapper.kt` | **New.** Pure Kotlin. Converts a `DeviceScan` into the ScanResult JSON. |
| `risk-auditor/app/.../AuditorFacade.kt` | **New method** `scanForAppSnitch()`: scan, then map to JSON. |
| `appsnitch/native/android/.../AppScannerPlugin.kt` | **New.** Capacitor plugin `AppScanner`: `scan`, `uninstall`, `openAppSettings`, `openSystemSetting`. |
| `appsnitch/www/js/data/content.js` | Band thresholds moved from 25/50/75 to **30/60/80** to match the engine's `rubric.json`. |
| `appsnitch/docs/DATA-CONTRACT.md` | Band line updated to match. |
| `appsnitch/tests/band_check.js` | **New.** Checks the band thresholds and that the sample still normalizes. |

Why the band change matters: the UI derives the band from the score. The engine
used 30/60/80, so without the change a score of 27 would show as Low in the UI but
Medium in the engine. The mapper also raises a score to its band's floor when a rule
forced a higher band, so the UI never disagrees with the engine's band.

## Field mapping (engine -> contract)

| Contract | Source |
|---|---|
| `packageName`, `id`, `name`, `versionName` | `AppRecord` |
| `installedAt` | `firstInstallTime` as a UTC date, or null |
| `installSource` | `isSystem` -> `system`; `GOOGLE_PLAY` -> `play_store`; `SIDELOADED`/`ADB_OR_NONE` -> `sideloaded`; else `other` |
| `score` | `RiskResult.score`, raised to the band floor (30/60/80) if needed |
| `reasons[]` | `Reason`. `CATEGORY_*` -> `access` + category; `SPECIAL_*` -> `access` (`special`); `COMBO_*` -> `combo`; `CONTEXT_MISMATCH_*` -> `context`; all else -> `trust`. `latent` is true when every permission behind the reason is declared but not granted. |
| `access{}` | `AppDetail.categoryGroups` mapped to the 9 contract keys; `AppDetail.specialAccess` -> `special` (accessibility gets `screenReader: true`). Accounts, sensors and uncategorized are left out. |
| `fixes[]` | `AppDetail.actions`. `UNINSTALL` -> `uninstall`; `DISABLE_SYSTEM_APP` -> `info`; any other `open_*` -> `settings`. |
| `device` | `Build.MODEL` and `Build.VERSION.RELEASE` |

## How to wire it up

1. **Engine side.** Add the risk-auditor modules to the Capacitor Android project as Gradle
   includes (or build the `:app` module as an AAR and depend on it). `AuditorFacade` needs
   the permissions, special access and report modules, which are already in the repo.
2. **Capacitor.** In `appsnitch/`: `npm i @capacitor/core @capacitor/cli @capacitor/android`,
   `npx cap init AppSnitch com.yourteam.appsnitch --web-dir=www`, `npx cap add android`.
   Copy `native/android/.../AppScannerPlugin.kt` into the Android source tree and register it
   in `MainActivity` (`registerPlugin(AppScannerPlugin.class)`).
3. **Switch the UI to native.** In `www/js/config.js` set `dataSource: 'native'`. The plugin
   name must stay `AppScanner`, the default in config.
4. `npx cap sync android`, then build in Android Studio.

The ready-made `mock` source still works without any of this, so the UI can be demoed
in a browser at any time.

## Verification status (be clear about this)

| Item | Status |
|---|---|
| Band thresholds in `content.js` (29/30, 59/60, 79/80, 0, 100) | **Passed.** `node appsnitch/tests/band_check.js` |
| Sample scan still normalizes (11 apps) | **Passed.** Same script. |
| `appsnitch/tests/phone_test.py` (127 browser checks, phone widths, mock/http/native) | **Passed, 127/127**, after the band change. |
| `ContractMapper.kt`, the facade method, and `AppScannerPlugin.kt` | **NOT COMPILED.** No Kotlin compiler or Android SDK is available in this environment, and Maven/Google repos are blocked. The code was written against the real type definitions in the repo, but it has not been built. Expect to fix small compile errors on first build. |
| Mapper output against real engine output | **NOT RUN.** That needs a compiled engine or a device. The first real check is: run `scanForAppSnitch()` on a device, save the JSON, then load it through `?source=http` or the native path and confirm it renders. |

Before a demo, run `scripts/run_all_tests.sh` in `risk-auditor` on a machine with the
Android SDK and Maven access, and build the APK with `scripts/build_apk.sh`.

## Known gaps

- **Fix buttons:** the contract's `settings` action opens the app's settings page,
  not the specific page (e.g. Accessibility). The plugin has `openSystemSetting` for
  that, but the contract cannot pass the target yet.
- **Scan progress:** `scanProgress` events are not emitted. The UI shows a plain spinner.
- **Icons:** the engine does not produce icons, so the UI shows colored letters.
- **Skipped apps:** `DeviceScan.skipped` is not in the contract, so apps that failed to
  scan are not shown in the UI. The engine's headless dump still has them.
- **Uninstall:** the callback checks whether the package is gone right after the
  prompt closes. On some devices the package can take a moment to disappear, so a
  successful uninstall may be reported as cancelled.
- **Package visibility:** listing all apps needs `QUERY_ALL_PACKAGES` on Android 11+.
  Google Play requires a justification for that permission (already noted in the AppSnitch README).
