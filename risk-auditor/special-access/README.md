# :special-access (M6)

*Android*

Detects the access granted through separate settings screens.

## Public API
| Type | Role |
|---|---|
| `SpecialAccessDetector` | `readDeviceState()` once, then `detect(app, perms, state)` per app |
| `SettingsSource`, `AppOpsSource`, `DeviceAdminSource`, `AccessibilitySource`, `PowerSource`, `VpnSource` | one seam per subsystem |
| `Android*Source` | the real implementations |
| `RoleResolver` | device defaults → `RoleHolders` |

## The distinction this module exists to preserve
Three answers, never two: **ENABLED** ("it can read your screen"),
**DECLARED_ONLY** ("it has asked for this"), and **UNKNOWN** ("we could not
check on this phone").

`AppOpsResult.UNREADABLE` is why the third exists. `unsafeCheckOpNoThrow` for
another package behaves inconsistently across OEM builds — some return
MODE_DEFAULT for everything, some throw. Reporting "no overlay access" when the
truth is "this phone would not tell us" is worse than saying nothing, because
the user acts on it. See `docs/LIMITATIONS.md` L-03.

Device state is read **once per scan** and reused, so every app is judged against
the same device state.

## Tests
```bash
./gradlew :special-access:testDebugUnitTest
```
