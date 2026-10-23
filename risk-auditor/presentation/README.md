# :presentation (M13)

*pure Kotlin*

UI-ready models. **Contains no UI.**

## Public API
| Type | Answers |
|---|---|
| `DeviceReport` | Q1 — ranked apps, band counts, category rollups, special-access roster |
| `AppDetail` | Q2 — "why flagged"; Q3 — ordered actions |
| `ReportBuilder` | builds both from a `DeviceScan` |
| `RemediationAdvisor` | the ordered remediation steps |

## Why the ordering in RemediationAdvisor is load-bearing
Android enforces real dependencies between these steps. Device administrator
must be deactivated before uninstall is even possible; an app with accessibility
access can tap "cancel" on its own removal; an app that can draw overlays can
cover the confirm button. Advice in the wrong order leaves a non-technical
person stuck in a loop blaming themselves.

`ReportBuilder` keeps ranking **stable** between scans — a list that reshuffles
on every rescan looks broken.

ML output is carried beside the rule score and `mlDisagreesWithRules` is set when
they differ, so the UI can say so rather than silently preferring one.

## Tests
```bash
./gradlew :presentation:test
```
