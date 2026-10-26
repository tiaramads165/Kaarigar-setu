"""Held-out evaluation of the Isolation Forest across many random seeds and several thresholds.

    python -m app.training.evaluate_isolation_forest [--seeds 20] [--json out.json]

For every contamination value (the model's expected share of anomalies, i.e. how aggressive flagging is) and every
seed: generate a fresh synthetic dataset, hold out 30% stratified, fit on the NORMAL training rows only (the model never
sees a label), then score the held-out rows. Reports mean and standard deviation of precision, recall and false-positive
rate, plus recall per fraud pattern. Synthetic data demonstrates the method; it says nothing about real-world accuracy.
"""
from __future__ import annotations

import argparse
import json
from statistics import mean, pstdev

import numpy as np
from sklearn.ensemble import IsolationForest
from sklearn.model_selection import train_test_split

from app.training.synthetic_data import FRAUD_KINDS, generate_with_kinds

CONTAMINATIONS = (0.005, 0.01, 0.02, 0.03, 0.05, 0.08)


def evaluate_once(contamination: float, seed: int) -> dict:
    x, y, kinds = generate_with_kinds(seed=seed)
    x_train, x_test, y_train, y_test, _, k_test = train_test_split(x, y, kinds, test_size=0.3, random_state=seed, stratify=y)
    model = IsolationForest(n_estimators=200, contamination=contamination, random_state=seed).fit(x_train[y_train == 0])
    flagged = model.predict(x_test) == -1
    tp = int((flagged & (y_test == 1)).sum())
    fp = int((flagged & (y_test == 0)).sum())
    fn = int((~flagged & (y_test == 1)).sum())
    tn = int((~flagged & (y_test == 0)).sum())
    result = {
        "precision": tp / (tp + fp) if tp + fp else 0.0,
        "recall": tp / (tp + fn) if tp + fn else 0.0,
        "false_positive_rate": fp / (fp + tn) if fp + tn else 0.0,
    }
    for index, name in enumerate(FRAUD_KINDS):
        mask = k_test == index
        result[f"recall_{name}"] = float(flagged[mask].mean()) if mask.any() else 0.0
    return result


def evaluate(seeds: int = 20, contaminations: tuple[float, ...] = CONTAMINATIONS) -> list[dict]:
    rows = []
    for contamination in contaminations:
        runs = [evaluate_once(contamination, seed) for seed in range(seeds)]
        row = {"contamination": contamination, "seeds": seeds}
        for metric in runs[0]:
            values = [run[metric] for run in runs]
            row[metric] = round(mean(values), 4)
            row[metric + "_std"] = round(pstdev(values), 4)
        rows.append(row)
    return rows


def markdown(rows: list[dict]) -> str:
    lines = ["| contamination | precision | recall | false-positive rate | " + " | ".join(f"recall: {k}" for k in FRAUD_KINDS) + " |",
             "|---|---|---|---|" + "---|" * len(FRAUD_KINDS)]
    for r in rows:
        cells = [f"{r['contamination']}", f"{r['precision']:.2f} ± {r['precision_std']:.2f}", f"{r['recall']:.2f} ± {r['recall_std']:.2f}",
                 f"{r['false_positive_rate']:.3f}"] + [f"{r['recall_' + k]:.2f}" for k in FRAUD_KINDS]
        lines.append("| " + " | ".join(cells) + " |")
    return "\n".join(lines)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--seeds", type=int, default=20)
    parser.add_argument("--json", help="also write the raw numbers to this file")
    args = parser.parse_args()
    table = evaluate(args.seeds)
    print(markdown(table))
    if args.json:
        with open(args.json, "w", encoding="utf-8") as handle:
            json.dump(table, handle, indent=2)
