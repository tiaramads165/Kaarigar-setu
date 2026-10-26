"""Rubric-agreement check: how closely the automatic scorer matches a human grader on the same transcripts.

    python -m app.training.rubric_agreement graded.csv [--json out.json] [--min-rows 30]

Input CSV columns:  trade, question_id, language, transcript, human_score
  human_score is the human grader's percentage (0-100) for that answer against the SAME rubric the scorer used.
  A grader must mark the transcripts blind, i.e. without seeing the automatic score.

Reports: n, Pearson r, Spearman rho, mean absolute error, mean signed bias (scorer minus human), exact agreement on the
three decision bands (pass >= 70, borderline 50-69, needs review < 50) and Cohen's kappa on those bands.

The scorer used is whatever the service is configured with (EVALUATOR=llm for the real LLM, otherwise the keyword scorer),
and the provider is printed so a keyword-scorer result is never mistaken for an LLM one. This script contains no data:
the labelled sample has to come from real human grading (the real rubric is supplied by the domain expert).
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import sys
from collections import Counter

from app.services.evaluation_service import EvaluationService

PASS, BORDERLINE = 70.0, 50.0


def band(percent: float) -> str:
    return "pass" if percent >= PASS else "borderline" if percent >= BORDERLINE else "review"


def pearson(a: list[float], b: list[float]) -> float:
    ma, mb = sum(a) / len(a), sum(b) / len(b)
    num = sum((x - ma) * (y - mb) for x, y in zip(a, b))
    den = math.sqrt(sum((x - ma) ** 2 for x in a) * sum((y - mb) ** 2 for y in b))
    return num / den if den else float("nan")


def ranks(values: list[float]) -> list[float]:
    order = sorted(range(len(values)), key=lambda i: values[i])
    result = [0.0] * len(values)
    i = 0
    while i < len(order):
        j = i
        while j + 1 < len(order) and values[order[j + 1]] == values[order[i]]:
            j += 1
        for k in range(i, j + 1):
            result[order[k]] = (i + j) / 2 + 1
        i = j + 1
    return result


def cohen_kappa(a: list[str], b: list[str]) -> float:
    n = len(a)
    observed = sum(x == y for x, y in zip(a, b)) / n
    ca, cb = Counter(a), Counter(b)
    expected = sum(ca[label] * cb[label] for label in set(a) | set(b)) / (n * n)
    return (observed - expected) / (1 - expected) if expected < 1 else 1.0


def compare(human: list[float], machine: list[float]) -> dict:
    if len(human) != len(machine) or not human:
        raise ValueError("need the same, non-zero number of human and machine scores")
    hb, mb = [band(v) for v in human], [band(v) for v in machine]
    return {
        "n": len(human),
        "pearson_r": round(pearson(human, machine), 4),
        "spearman_rho": round(pearson(ranks(human), ranks(machine)), 4),
        "mean_absolute_error": round(sum(abs(h - m) for h, m in zip(human, machine)) / len(human), 3),
        "mean_bias_machine_minus_human": round(sum(m - h for h, m in zip(human, machine)) / len(human), 3),
        "band_agreement": round(sum(x == y for x, y in zip(hb, mb)) / len(human), 4),
        "band_kappa": round(cohen_kappa(hb, mb), 4),
    }


def run(path: str, min_rows: int = 30) -> dict:
    with open(path, newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    if len(rows) < min_rows:
        raise SystemExit(f"{len(rows)} graded rows is too few to say anything (need at least {min_rows}; --min-rows to override)")
    service, human, machine, providers = EvaluationService(), [], [], Counter()
    for row in rows:
        result = service.evaluate(row["trade"], row["transcript"], int(row["question_id"]), row.get("language") or None)
        human.append(float(row["human_score"]))
        machine.append(float(result["score"]))
        providers[str(result["provider"])] += 1
    return {**compare(human, machine), "providers": dict(providers)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("csv")
    parser.add_argument("--json")
    parser.add_argument("--min-rows", type=int, default=30)
    args = parser.parse_args()
    report = run(args.csv, args.min_rows)
    print(json.dumps(report, indent=2))
    if args.json:
        with open(args.json, "w", encoding="utf-8") as handle:
            json.dump(report, handle, indent=2)
    sys.exit(0)
