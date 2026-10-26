"""Train, evaluate and persist the Isolation Forest.   python -m app.training.train_isolation_forest"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

import joblib
import numpy as np
from sklearn.ensemble import IsolationForest
from sklearn.model_selection import train_test_split

from app.training.synthetic_data import FEATURES, generate

MODEL_PATH = Path(__file__).resolve().parent.parent / "models" / "isolation_forest.joblib"
# Trade-off, mean over 20 seeds on held-out synthetic data (docs/ML_EVALUATION.md, reproduce with
# `python -m app.training.evaluate_isolation_forest`):   0.01 -> precision 0.82 / recall 0.45
#   0.03 -> 0.74 / 0.88    0.05 -> 0.64 / 0.96
# 0.03 keeps the review queue small while still catching most fraud; a human moderator is the safety net.
CONTAMINATION = 0.03


def sha256_of(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def train(seed: int = 42) -> tuple[IsolationForest, dict]:
    x, y = generate(seed=seed)
    x_train, x_test, y_train, y_test = train_test_split(x, y, test_size=0.3, random_state=seed, stratify=y)
    # Unsupervised: fit on the normal-looking training rows only (the production reality: fraud is rare and unlabelled).
    model = IsolationForest(n_estimators=200, contamination=CONTAMINATION, random_state=seed).fit(x_train[y_train == 0])
    predicted = (model.predict(x_test) == -1).astype(int)
    tp = int(((predicted == 1) & (y_test == 1)).sum())
    fp = int(((predicted == 1) & (y_test == 0)).sum())
    fn = int(((predicted == 0) & (y_test == 1)).sum())
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    metrics = {
        "features": list(FEATURES), "seed": seed, "contamination": CONTAMINATION,
        "train_normal_rows": int((y_train == 0).sum()), "heldout_rows": int(len(y_test)),
        "heldout_fraud_rows": int((y_test == 1).sum()), "true_positives": tp, "false_positives": fp,
        "false_negatives": fn, "precision": round(precision, 4), "recall": round(recall, 4),
        "f1": round(2 * precision * recall / (precision + recall), 4) if precision + recall else 0.0,
        "note": "Synthetic data: demonstrates the method, NOT real-world fraud accuracy.",
    }
    return model, metrics


def save(model: IsolationForest, metrics: dict, path: Path = MODEL_PATH) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    joblib.dump(model, path)
    metrics = {**metrics, "model_sha256": sha256_of(path)}
    path.with_suffix(".metrics.json").write_text(json.dumps(metrics, indent=2) + "\n", encoding="utf-8")
    path.with_suffix(".sha256").write_text(metrics["model_sha256"] + "\n", encoding="utf-8")


if __name__ == "__main__":
    trained, report = train()
    save(trained, report)
    print(json.dumps(report, indent=2))
