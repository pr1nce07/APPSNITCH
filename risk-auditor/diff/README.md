# :diff (M12)

*pure Kotlin*

What changed between two scans, and which changes deserve a notification.

## Public API
| Type | Role |
|---|---|
| `AppDiff` | newly declared, newly granted, removed, new special access, version and installer changes |
| `DiffAlert` | a change worth interrupting the user about |
| `DiffEngine` | `diff`, `alertsFor`, `diffAll` |

## Two behaviours that matter
- **The first run never alerts.** On a fresh install every app looks like it just
  gained everything it holds. `diffAll` detects the empty store, writes the
  baseline and returns nothing.
- **Not every diff is an alert.** An app gaining `ACCESS_NETWORK_STATE` is a
  diff, not something to interrupt someone about. Alerting on everything trains
  people to dismiss the notification, which costs more than the few real alerts
  it surfaces.

## Tests
```bash
./gradlew :diff:test
```
