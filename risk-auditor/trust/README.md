# :trust (M2)

*pure Kotlin*

Installer classification, the security/MDM allowlist, and device roles.

## Public API
| Type | Role |
|---|---|
| `InstallerClassifier` | raw installer package → `InstallerKind`; `displayName` for the UI |
| `TrustedTool`, `TrustRegistry`, `TrustVerdict` | is this a legitimate security or management app? |
| `DeviceRole`, `RoleHolders` | default SMS app, dialer, assistant, browser, home |
| `TrustLoader` | reads `trusted_tools.json` |

## The central rule
**A package-name match alone is never trusted.** Package names are trivially
spoofable, and a sideloaded app impersonating an MDM agent is exactly the shape
a real attack takes — it must score *higher*, not lower. Trust requires either a
pinned signing certificate or arrival from Google Play / an OEM store.

`initiatingPackage` beats `installingPackage` when they disagree: an app can set
itself as the installer, but the system records the initiator.

## Tests
```bash
./gradlew :trust:test
```
Also covered by `tools/offline-verify/run.sh` (9 assertions, executed),
including the spoof case.
