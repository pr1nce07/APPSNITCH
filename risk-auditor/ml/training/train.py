#!/usr/bin/env python3
"""
Train the on-device permission model for the App Risk Auditor (PS-01 stretch 13).

WHAT THIS DOES
--------------
Trains an L2-regularised logistic regression on binary permission features and
exports it to ``ml/src/main/resources/ml/model.json`` for the Kotlin inference
code in ``:ml`` to load. Also exports golden feature vectors with their Python
predictions so the Kotlin implementation can be proven to agree to 1e-6.

DATASET
-------
Pass ``--dataset <path.csv>`` to train on a real public permission dataset
(for example UCI TUANDROMD, or a Kaggle Android-permission dataset). The file
must be a CSV with one row per app, one binary column per permission, and a
label column.

If no dataset is given, the script generates a SYNTHETIC dataset and marks the
exported model ``"synthetic": true``. That flag is load-bearing: the Android UI
refuses to present a synthetic model's output as meaningful, and the reported
metrics describe only the generator, not real malware. Synthetic mode exists so
the pipeline, the export format and the Kotlin/Python parity test can all be
exercised end to end without a network connection. A real model MUST be trained
locally before any demo that shows the ML column.

LICENCE NOTE
------------
No APKs are downloaded or distributed. Permission-vector datasets only. Check
the licence of whichever dataset you point this at before redistributing the
resulting model.

USAGE
-----
    pip install -r requirements.txt
    python3 train.py                      # synthetic, for pipeline testing
    python3 train.py --dataset tuandromd.csv --label-column Label
"""

from __future__ import annotations

import argparse
import datetime as _dt
import json
import pathlib
import sys

import numpy as np

try:
    from sklearn.ensemble import RandomForestClassifier
    from sklearn.linear_model import LogisticRegression
    from sklearn.metrics import (
        f1_score,
        precision_score,
        recall_score,
        roc_auc_score,
    )
    from sklearn.model_selection import StratifiedKFold
except ImportError:  # pragma: no cover
    print("error: scikit-learn is required. Run: pip install -r requirements.txt", file=sys.stderr)
    raise

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent.parent
MODEL_OUT = REPO / "ml" / "src" / "main" / "resources" / "ml" / "model.json"
GOLDEN_OUT = REPO / "ml" / "src" / "test" / "resources" / "ml" / "golden_vectors.json"

# Permissions the synthetic generator uses. These are the same constants the
# Kotlin catalog knows about, so the exported vocab lines up with what the app
# actually observes on a device.
SYNTHETIC_VOCAB = [
    "android.permission.READ_SMS",
    "android.permission.RECEIVE_SMS",
    "android.permission.SEND_SMS",
    "android.permission.READ_CONTACTS",
    "android.permission.READ_CALL_LOG",
    "android.permission.CAMERA",
    "android.permission.RECORD_AUDIO",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_BACKGROUND_LOCATION",
    "android.permission.READ_PHONE_STATE",
    "android.permission.CALL_PHONE",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.READ_MEDIA_IMAGES",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    "android.permission.SYSTEM_ALERT_WINDOW",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.PACKAGE_USAGE_STATS",
    "android.permission.BIND_ACCESSIBILITY_SERVICE",
    "android.permission.BIND_DEVICE_ADMIN",
    "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.WAKE_LOCK",
    "android.permission.VIBRATE",
    "android.permission.POST_NOTIFICATIONS",
]

# Probability each permission appears, for benign and for flagged apps.
# These encode the same intuition as the rule engine; they are NOT learned from
# real malware and must never be described as if they were.
SYNTHETIC_P_BENIGN = np.array([
    0.02, 0.02, 0.02, 0.18, 0.03, 0.22, 0.14, 0.30, 0.04, 0.20,
    0.06, 0.35, 0.30, 0.34, 0.02, 0.30, 0.40, 0.08, 0.03, 0.02,
    0.02, 0.01, 0.00, 0.01, 0.95, 0.90, 0.35, 0.55, 0.70, 0.60,
])
SYNTHETIC_P_FLAGGED = np.array([
    0.72, 0.70, 0.45, 0.62, 0.55, 0.40, 0.42, 0.48, 0.38, 0.65,
    0.30, 0.55, 0.52, 0.30, 0.25, 0.80, 0.60, 0.45, 0.55, 0.45,
    0.35, 0.30, 0.22, 0.34, 0.99, 0.92, 0.50, 0.75, 0.55, 0.40,
])


def normalise_permission(column: str) -> str:
    """Map a dataset column name onto a standard Android permission constant."""
    name = column.strip()
    if name.startswith("android.permission."):
        return name
    if name.startswith("permission."):
        return "android.permission." + name.split(".", 1)[1]
    # Many datasets use the bare constant, e.g. "READ_SMS".
    bare = name.replace("-", "_").replace(" ", "_").upper()
    if bare.isidentifier():
        return "android.permission." + bare
    return name


def load_real_dataset(path: pathlib.Path, label_column: str):
    import csv

    with path.open(newline="", encoding="utf-8") as fh:
        reader = csv.DictReader(fh)
        rows = list(reader)

    if not rows:
        raise SystemExit(f"error: {path} contains no rows")
    if label_column not in rows[0]:
        raise SystemExit(
            f"error: label column '{label_column}' not found. "
            f"Available columns: {sorted(rows[0])[:20]}..."
        )

    raw_columns = [c for c in rows[0] if c != label_column]
    vocab, unmappable = [], []
    for c in raw_columns:
        mapped = normalise_permission(c)
        if mapped.startswith("android.permission."):
            vocab.append(mapped)
        else:
            unmappable.append(c)

    if unmappable:
        print(f"  note: {len(unmappable)} column(s) could not be mapped to a permission "
              f"constant and were dropped: {unmappable[:8]}")

    keep = [c for c in raw_columns if normalise_permission(c).startswith("android.permission.")]

    def to_binary(v: str) -> float:
        return 1.0 if str(v).strip().lower() in {"1", "true", "yes", "y"} else 0.0

    def to_label(v: str) -> int:
        s = str(v).strip().lower()
        if s in {"1", "malware", "malicious", "true", "yes"}:
            return 1
        return 0

    X = np.array([[to_binary(r[c]) for c in keep] for r in rows], dtype=float)
    y = np.array([to_label(r[label_column]) for r in rows], dtype=int)
    return X, y, vocab


def make_synthetic(n: int, seed: int):
    rng = np.random.default_rng(seed)
    n_flagged = n // 2
    n_benign = n - n_flagged

    benign = (rng.random((n_benign, len(SYNTHETIC_VOCAB))) < SYNTHETIC_P_BENIGN).astype(float)
    flagged = (rng.random((n_flagged, len(SYNTHETIC_VOCAB))) < SYNTHETIC_P_FLAGGED).astype(float)

    X = np.vstack([benign, flagged])
    y = np.concatenate([np.zeros(n_benign, dtype=int), np.ones(n_flagged, dtype=int)])

    order = rng.permutation(len(y))
    return X[order], y[order], list(SYNTHETIC_VOCAB)


def cross_validate(model_factory, X, y, folds: int, seed: int) -> dict:
    skf = StratifiedKFold(n_splits=folds, shuffle=True, random_state=seed)
    precisions, recalls, f1s, aucs = [], [], [], []

    for train_idx, test_idx in skf.split(X, y):
        model = model_factory()
        model.fit(X[train_idx], y[train_idx])
        pred = model.predict(X[test_idx])
        proba = model.predict_proba(X[test_idx])[:, 1]

        precisions.append(precision_score(y[test_idx], pred, zero_division=0))
        recalls.append(recall_score(y[test_idx], pred, zero_division=0))
        f1s.append(f1_score(y[test_idx], pred, zero_division=0))
        aucs.append(roc_auc_score(y[test_idx], proba))

    return {
        "precision": float(np.mean(precisions)),
        "recall": float(np.mean(recalls)),
        "f1": float(np.mean(f1s)),
        "roc_auc": float(np.mean(aucs)),
        "folds": folds,
    }


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dataset", type=pathlib.Path, help="CSV of permission vectors. Omit for synthetic.")
    ap.add_argument("--label-column", default="Label")
    ap.add_argument("--samples", type=int, default=4000, help="synthetic sample count")
    ap.add_argument("--seed", type=int, default=20261016)
    ap.add_argument("--folds", type=int, default=5)
    ap.add_argument("--threshold", type=float, default=0.5)
    ap.add_argument("--golden", type=int, default=40, help="golden vectors to export for the parity test")
    args = ap.parse_args()

    if args.dataset:
        print(f"Loading dataset: {args.dataset}")
        X, y, vocab = load_real_dataset(args.dataset, args.label_column)
        dataset_name = args.dataset.name
        synthetic = False
    else:
        print("No --dataset given: generating SYNTHETIC data.")
        print("  The exported model will be marked synthetic:true and its metrics")
        print("  describe the generator, NOT real malware. Train on a real dataset")
        print("  before any demo that shows the ML column.")
        X, y, vocab = make_synthetic(args.samples, args.seed)
        dataset_name = "synthetic-generator-v1"
        synthetic = True

    print(f"  samples={len(y)}  features={X.shape[1]}  positives={int(y.sum())}")

    print("\nCross-validating L2 logistic regression...")
    # L2 is scikit-learn's default penalty; passing penalty="l2" explicitly is
    # deprecated from 1.8 onwards, so it is left at the default here.
    lr_metrics = cross_validate(
        lambda: LogisticRegression(C=1.0, max_iter=2000, solver="lbfgs"),
        X, y, args.folds, args.seed,
    )
    for k, v in lr_metrics.items():
        print(f"  {k:>10}: {v:.4f}" if isinstance(v, float) else f"  {k:>10}: {v}")

    print("\nCross-validating random forest (comparison only)...")
    rf_metrics = cross_validate(
        lambda: RandomForestClassifier(n_estimators=200, random_state=args.seed, n_jobs=-1),
        X, y, args.folds, args.seed,
    )
    for k, v in rf_metrics.items():
        print(f"  {k:>10}: {v:.4f}" if isinstance(v, float) else f"  {k:>10}: {v}")

    delta = rf_metrics["f1"] - lr_metrics["f1"]
    print(f"\n  F1 difference (forest - logistic): {delta:+.4f}")
    if delta > 0.05:
        print("  The forest is clearly better here. Logistic regression is still exported,")
        print("  because per-feature contributions are what make the score explainable,")
        print("  but this gap is worth recording in docs/LIMITATIONS.md.")
    else:
        print("  No meaningful gap: the interpretable model is the right choice.")

    print("\nFitting final logistic regression on all data...")
    final = LogisticRegression(C=1.0, max_iter=2000, solver="lbfgs")
    final.fit(X, y)

    weights = final.coef_[0].astype(float).tolist()
    bias = float(final.intercept_[0])

    model = {
        "model_version": f"lr-{'syn' if synthetic else 'real'}-{_dt.date.today().isoformat()}",
        "dataset_name": dataset_name,
        "trained_on": _dt.datetime.now(_dt.timezone.utc).isoformat(timespec="seconds"),
        "synthetic": synthetic,
        "threshold": args.threshold,
        "bias": bias,
        "vocab": vocab,
        "weights": weights,
        "metrics": {
            "cv_precision": lr_metrics["precision"],
            "cv_recall": lr_metrics["recall"],
            "cv_f1": lr_metrics["f1"],
            "cv_roc_auc": lr_metrics["roc_auc"],
            "cv_folds": float(lr_metrics["folds"]),
            "comparison_rf_f1": rf_metrics["f1"],
        },
    }

    MODEL_OUT.parent.mkdir(parents=True, exist_ok=True)
    MODEL_OUT.write_text(json.dumps(model, indent=2) + "\n", encoding="utf-8")
    print(f"  wrote {MODEL_OUT.relative_to(REPO)}")

    # Golden vectors: the exact inputs and Python outputs the Kotlin scorer must
    # reproduce to 1e-6. Without these, "the Kotlin port is correct" is an
    # assertion rather than a test.
    rng = np.random.default_rng(args.seed + 1)
    idx = rng.choice(len(X), size=min(args.golden, len(X)), replace=False)
    probabilities = final.predict_proba(X[idx])[:, 1]

    golden = {
        "model_version": model["model_version"],
        "note": "Generated by train.py. The Kotlin MlScorer must reproduce every "
                "probability below to within 1e-6.",
        "cases": [
            {
                "features": [int(v) for v in X[i]],
                "expected_probability": float(p),
            }
            for i, p in zip(idx.tolist(), probabilities.tolist())
        ],
    }
    GOLDEN_OUT.parent.mkdir(parents=True, exist_ok=True)
    GOLDEN_OUT.write_text(json.dumps(golden, indent=2) + "\n", encoding="utf-8")
    print(f"  wrote {GOLDEN_OUT.relative_to(REPO)} ({len(golden['cases'])} cases)")

    print("\nDone.")
    if synthetic:
        print("REMINDER: this model is synthetic. Do not present its output as a real verdict.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
