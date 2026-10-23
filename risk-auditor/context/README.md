# :context (M9)

*pure Kotlin*

Context mismatch: "this claims to be a torch but it reads your texts".

## Public API
| Type | Role |
|---|---|
| `Archetype` | what an app claims to be, plus its `expectedCategories` |
| `ArchetypeClassifier` | label + package + `ApplicationInfo.category` → `Classification` |
| `ContextMismatchEngine` | `analyse(snapshot)` → `ContextAnalysis` |

## Why it is conservative
A false "your banking app is suspicious" on a judging device costs far more than
a missed finding, so three guards apply:
1. Only **STRONG** classifications produce findings. One keyword is WEAK.
2. Whole-token matching only — substring matching classifies "Calculator Vault"
   as a calculator.
3. Ambiguity collapses to `UNKNOWN`, which expects everything and flags nothing.

Messengers, browsers and security apps are exempt entirely: they genuinely need
broad access.

Play Store category is unavailable offline (`docs/LIMITATIONS.md` L-04).

## Tests
```bash
./gradlew :context:test
```
Covered by `tools/offline-verify/run.sh` (9 assertions, executed), five of which
are negative cases.
