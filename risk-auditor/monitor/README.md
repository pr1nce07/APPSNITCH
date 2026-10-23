# :monitor (M15)

*Android*

Install monitoring and notifications (PS-01 should-have 09 and 10).

## Public API
| Type | Role |
|---|---|
| `InstallMonitor` | pure: should this notify, and what should it say |
| `PackageEventReceiver` | runtime-registered install/update receiver |
| `MonitorService` | opt-in foreground service that keeps the receiver alive |
| `PeriodicSweepWorker` | six-hourly WorkManager diff — the safety net |
| `BootReceiver` | restarts monitoring after reboot or self-update |
| `Notifier` | channel creation and POST_NOTIFICATIONS handling |

## Why delivery is threefold
From Android 8, manifest-declared receivers no longer get most implicit
broadcasts, and OEM power management makes package broadcasts less reliable
still. A manifest-only monitor works on the developer's phone and silently does
nothing on the judging device. See `docs/LIMITATIONS.md` L-08.

The scanning stack is **injected** (`MonitorService.eventHandler`,
`PeriodicSweepWorker.sweepAction`) so this module depends on neither
`:inventory` nor `:scoring`, which keeps the graph acyclic and lets the
notification logic be tested with no scanning stack at all.

Threshold is High. Notifying on Medium would fire for a large share of ordinary
apps, and a channel people mute protects nobody.

## Tests
```bash
./gradlew :monitor:testDebugUnitTest
```
