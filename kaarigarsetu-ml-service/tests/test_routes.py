from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_evaluate_scores_known_trade_with_structured_response():
    response = client.post("/evaluate", json={"trade": "Electrician", "transcript": "Safety first: check voltage and breaker."})

    assert response.status_code == 200
    assert response.json()["score"] == 60.0
    assert response.json()["matched_criteria"] == ["safety", "voltage", "breaker"]


def test_fraud_check_returns_score_and_flag():
    response = client.post("/fraud-check", json={
        "ip_frequency": 1, "device_frequency": 1, "minutes_since_previous": 1440, "reciprocity_ratio": 0.05,
    })

    assert response.status_code == 200
    assert set(response.json()) == {"anomaly_score", "flagged"}


def test_trust_scores_include_isolated_node():
    response = client.post("/trust-score", json={
        "nodes": ["worker-1", "worker-2", "isolated"],
        "edges": [{"source": "worker-1", "target": "worker-2", "weight": 0.8}],
    })

    assert response.status_code == 200
    assert set(response.json()["scores"]) == {"worker-1", "worker-2", "isolated"}


def test_transcribe_rejects_empty_audio():
    response = client.post("/transcribe", files={"audio": ("empty.wav", b"", "audio/wav")})

    assert response.status_code == 422
