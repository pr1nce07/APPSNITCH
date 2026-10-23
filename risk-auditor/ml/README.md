# :ml (M11)

*pure Kotlin + Python*

On-device inference, plus the training pipeline that produces the model.

## Public API
| Type | Role |
|---|---|
| `ModelSpec` | vocab, weights, bias, threshold, metrics, `synthetic` flag |
| `MlScorer` | `score(snapshot)` → `MlResult` |
| `ModelLoader` | reads `ml/model.json`; returns null when none is bundled |

## Training
```bash
pip install -r ml/training/requirements.txt
cd ml/training
python3 train.py                                    # synthetic, pipeline testing only
python3 train.py --dataset tuandromd.csv --label-column Label
```
Exports `ml/src/main/resources/ml/model.json` and the golden vectors the parity
test uses.

## Honesty constraints
- The bundled model is **synthetic** and flagged as such. Its 0.99 metrics
  describe the generator, not malware (`docs/LIMITATIONS.md` L-07).
- The model **never** overrides the rule score — this class has no access to
  `RiskResult` at all — and rule reasons remain the source of every explanation.
- Logistic regression over a tree ensemble, because `weight x feature` can be
  named honestly (`docs/ASSUMPTIONS.md` A-14).
- The sigmoid is the numerically stable form; the naive one produces NaN on
  sparse high-dimensional input.

## Tests
```bash
./gradlew :ml:test
```
Covered by `tools/offline-verify/run.sh` (11 assertions, executed), including
Kotlin/Python parity to **1.2e-17**.
