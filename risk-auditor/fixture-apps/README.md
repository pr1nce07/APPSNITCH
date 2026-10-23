# :fixture-apps (M3)

*Android, dev-only*

Inert test fixtures. **Never shipped.**

13 product flavors, each a tiny APK that **declares** permissions and components
but **does nothing**. Every component is an empty no-op.

This is how PS-01's "no live malware" constraint is honoured while still being
able to test detection of a torch app holding SMS, an accessibility+overlay
pair, a hidden-icon app and so on. Downloading real malware to test a scanner
would break the rules and be genuinely reckless on a shared device.

## Flavors
| Flavor | What it exercises |
|---|---|
| `torchSms` | context mismatch: utility with SMS + contacts + call log |
| `calculatorContacts` | context mismatch: calculator with contacts |
| `wallpaperCameraMic` | context mismatch: wallpaper with camera + mic |
| `accessibility` | accessibility service detection |
| `overlay` | overlay declaration and AppOps state |
| `deviceAdmin` | device admin detection |
| `notificationListener` | notification listener detection |
| `installPackagesBoot` | install-packages + boot persistence |
| `noLauncherIcon` | hidden icon + background location + camera/mic |
| `debuggable` | static manifest check: debuggable |
| `exportedCleartext` | unguarded exported provider + cleartext |
| `legitMessenger` | **negative**: must NOT be flagged for SMS |
| `clean` | **negative**: must stay Low |

## Install
```bash
scripts/install_fixtures.sh
```
Several need special access switched on **by hand** — that is the point, since
the scanner must distinguish ENABLED from DECLARED_ONLY.

**Never install these on the judging device.** The demo runs on the organizer's
test kit, not on apps we chose.

> Never built or tested — see `docs/LIMITATIONS.md` L-12.
