import json
import shutil
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.services.fraud_detector import FraudDetector
from app.training.train_isolation_forest import MODEL_PATH, sha256_of, train

client = TestClient(app)
NORMAL = {"ip_frequency": 1, "device_frequency": 1, "minutes_since_previous": 1440, "reciprocity_ratio": 0.05}


def test_normal_attestation_is_not_flagged_and_score_is_in_range():
    body = client.post("/fraud-check", json=NORMAL).json()
    assert body["flagged"] is False
    assert 0.0 <= body["anomaly_score"] < 0.55


@pytest.mark.parametrize("name,features", [
    ("rapid burst from one ip", {"ip_frequency": 12, "device_frequency": 8, "minutes_since_previous": 1, "reciprocity_ratio": 0.1}),
    ("reciprocal vouching ring", {"ip_frequency": 1, "device_frequency": 1, "minutes_since_previous": 900, "reciprocity_ratio": 0.95}),
    ("one device vouching for many", {"ip_frequency": 2, "device_frequency": 15, "minutes_since_previous": 600, "reciprocity_ratio": 0.5}),
])
def test_fraud_patterns_are_flagged_with_a_higher_score(name, features):
    body = client.post("/fraud-check", json=features).json()
    normal = client.post("/fraud-check", json=NORMAL).json()
    assert body["flagged"] is True, name
    assert body["anomaly_score"] > normal["anomaly_score"]


def test_fraud_input_validation():
    assert client.post("/fraud-check", json={**NORMAL, "reciprocity_ratio": 1.5}).status_code == 422
    assert client.post("/fraud-check", json={**NORMAL, "ip_frequency": -1}).status_code == 422
    assert client.post("/fraud-check", json={"ip_frequency": 1}).status_code == 422


def test_heldout_precision_and_recall_meet_the_documented_floor():
    _, metrics = train()
    assert metrics["recall"] >= 0.7 and metrics["precision"] >= 0.65
    recorded = json.loads(MODEL_PATH.with_suffix(".metrics.json").read_text())
    assert recorded["model_sha256"] == sha256_of(MODEL_PATH)


def test_a_tampered_model_file_is_refused(tmp_path: Path):
    copy = tmp_path / "isolation_forest.joblib"
    shutil.copy(MODEL_PATH, copy)
    shutil.copy(MODEL_PATH.with_suffix(".sha256"), copy.with_suffix(".sha256"))
    copy.write_bytes(copy.read_bytes() + b"tampered")
    with pytest.raises(RuntimeError, match="integrity"):
        FraudDetector(copy).check(NORMAL)


# ---- trust graph -------------------------------------------------------------------------------------------

def scores(nodes, edges):
    return client.post("/trust-score", json={"nodes": nodes, "edges": edges}).json()


def test_scores_sum_to_one_and_include_isolated_nodes():
    body = scores(["a", "b", "c", "lonely"], [{"source": "a", "target": "b", "weight": 0.8}, {"source": "b", "target": "c", "weight": 0.6}])
    assert set(body["scores"]) == {"a", "b", "c", "lonely"}
    assert abs(sum(body["scores"].values()) - 1.0) < 1e-4
    assert max(body["scaled_scores"].values()) == 100.0


def test_a_vouch_from_a_high_trust_node_moves_the_score_more_than_one_from_a_low_trust_node():
    # "hub" is vouched for by five nodes; "nobody" by none. Each then vouches for one worker.
    nodes = ["hub", "nobody", "w_from_hub", "w_from_nobody"] + [f"s{i}" for i in range(5)]
    edges = [{"source": f"s{i}", "target": "hub", "weight": 1.0} for i in range(5)]
    edges += [{"source": "hub", "target": "w_from_hub", "weight": 1.0}, {"source": "nobody", "target": "w_from_nobody", "weight": 1.0}]
    result = scores(nodes, edges)["scores"]
    assert result["w_from_hub"] > result["w_from_nobody"]


def test_empty_graph_and_bad_weights():
    assert scores([], [])["scores"] == {}
    assert client.post("/trust-score", json={"edges": [{"source": "a", "target": "b", "weight": 0}]}).status_code == 422
    assert client.post("/trust-score", json={"edges": [{"source": "a", "target": "b", "weight": 1.5}]}).status_code == 422


def test_evaluation_script_reports_per_pattern_recall_and_trade_off():
    from app.training.evaluate_isolation_forest import evaluate, markdown
    rows = evaluate(seeds=2, contaminations=(0.01, 0.05))
    assert [r["contamination"] for r in rows] == [0.01, 0.05]
    for row in rows:
        assert 0 <= row["precision"] <= 1 and 0 <= row["recall"] <= 1 and 0 <= row["false_positive_rate"] <= 1
        assert {"recall_burst", "recall_vouching_ring", "recall_same_ip", "recall_shared_device"} <= set(row)
    # a more aggressive threshold flags more, so it can only catch more fraud
    assert rows[1]["recall"] >= rows[0]["recall"]
    assert markdown(rows).count("\n") == 3


def test_fraud_kinds_do_not_change_the_committed_dataset():
    import hashlib
    from app.training.synthetic_data import generate
    x, y = generate()
    assert hashlib.sha256(x.tobytes() + y.tobytes()).hexdigest() == "5e2869003f62628fb617366824ce76d915e22a984c8bc86924210b60dbcd1467"
