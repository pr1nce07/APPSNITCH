# :app (M17)

*Android*

Integration. Wires every module together.

## Public API
| Type | Role |
|---|---|
| `AuditorFacade` | `scan(options)`, `scanOne`, `appDetail`, `exportPdf`, `consent` |
| `DebugDumpActivity` | headless entry point — **no layout** |
| `RiskAuditorApplication` | supplies the callbacks `:monitor` leaves open |
| `SharedPrefsConsentStore` | persists the hash-lookup consent decision |

## Scan order
inventory → permissions → special access (device state read once) → manifest →
hashing and intel (optional, consent-gated, slowest) → score → diff.

Dependencies are wired by hand. There are about fifteen singletons and the
wiring is one readable block; a DI framework would add a build dependency and
indirection to save nothing.

## Running it
```bash
adb shell am start -n com.riskauditor.app/.DebugDumpActivity
adb shell am start -n com.riskauditor.app/.DebugDumpActivity --ez include_system true
adb logcat -s RiskAuditorDump
adb shell run-as com.riskauditor.app cat files/scan.json > scan.json
```

> `DebugDumpActivity` is exported and is currently the launcher entry, because
> there is no UI. **Unexport it and drop the LAUNCHER filter once real screens
> exist** — an exported activity that triggers a full device scan is not
> something to ship. See `docs/ASSUMPTIONS.md` A-18.

## Tests
```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest   # needs a device
```
Neither has been run; `:app` has never been compiled.
