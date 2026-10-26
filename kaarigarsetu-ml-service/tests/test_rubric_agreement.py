"""Tests of the agreement MATH using made-up numbers. They say nothing about the real scorer's agreement with real graders."""
import csv

import pytest

from app.training.rubric_agreement import band, cohen_kappa, compare, ranks, run


def test_bands_follow_the_agreed_thresholds():
    assert [band(v) for v in (100, 70, 69.99, 50, 49.99, 0)] == ["pass", "pass", "borderline", "borderline", "review", "review"]


def test_perfect_agreement():
    scores = [10, 40, 55, 65, 72, 90, 100]
    result = compare(scores, scores)
    assert result["pearson_r"] == 1 and result["spearman_rho"] == 1 and result["mean_absolute_error"] == 0
    assert result["band_agreement"] == 1 and result["band_kappa"] == 1


def test_reversed_scores_disagree():
    human = [10, 30, 50, 70, 90]
    result = compare(human, list(reversed(human)))
    assert result["pearson_r"] == -1 and result["spearman_rho"] == -1
    assert result["band_kappa"] < 0


def test_bias_sign_is_machine_minus_human():
    assert compare([50, 60], [60, 70])["mean_bias_machine_minus_human"] == 10


def test_tied_ranks_are_averaged():
    assert ranks([1, 2, 2, 3]) == [1, 2.5, 2.5, 4]


def test_kappa_is_zero_for_agreement_no_better_than_chance():
    assert cohen_kappa(["pass", "pass", "review", "review"], ["pass", "review", "pass", "review"]) == 0


def test_empty_or_mismatched_inputs_are_refused():
    with pytest.raises(ValueError):
        compare([], [])
    with pytest.raises(ValueError):
        compare([1], [1, 2])


def test_too_small_a_sample_is_refused(tmp_path):
    path = tmp_path / "graded.csv"
    with open(path, "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=["trade", "question_id", "language", "transcript", "human_score"])
        writer.writeheader()
        writer.writerow({"trade": "electrician", "question_id": 1, "language": "en", "transcript": "x", "human_score": 50})
    with pytest.raises(SystemExit):
        run(str(path))
