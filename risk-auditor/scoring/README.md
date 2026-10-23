# :scoring (M10)

*pure Kotlin*

The risk engine. The heart of the submission.

## Public API
| Type | Role |
|---|---|
| `RiskEngine` | `score(ScoringInput)` → `RiskResult` |
| `Rubric` and friends | the whole numeric model, loaded from `rubric.json` |
| `CombinationRules` | the predicate half of each combination rule |
| `RubricLoader` | reads `rubric.json` |

## rubric.json is the single source of truth
Both the engine and `tools/render_rubric.py` read it, so the one-page rubric
deliverable cannot drift from the code. Numbers, band floors and user-facing
text live in JSON; **predicates** live in Kotlin and are joined by rule id. A
test fails if either side has an id the other lacks.

## The decision that matters
Ordinary permissions are capped at **45 points in total**. Reaching High or
Critical requires special access, a combination rule, poor provenance, a context
mismatch or a threat-intel hit. Without that cap a legitimate messenger
outscores a torch app holding an accessibility service and the ranking becomes
noise. See `docs/ASSUMPTIONS.md` A-02.

Special access is scored from the detected access state only, never from the
permission list, so one capability is never counted twice.

Every rule emits its `Reason` at the moment it moves the score. There is no
second "explain" pass that could drift from the arithmetic.

## Tests
```bash
./gradlew :scoring:test
```
Covered by `tools/offline-verify/run.sh` (29 assertions, executed): 2,000-profile
fuzz for range and reason count, 400-profile monotonicity, a banned-jargon
linter, and a positive and negative case for all 14 combination rules.
