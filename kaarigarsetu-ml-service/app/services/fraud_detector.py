from __future__ import annotations

import os
from pathlib import Path

import joblib
import numpy as np
from sklearn.ensemble import IsolationForest

from app.config import is_production
from app.training.synthetic_data import FEATURES


class FraudDetector:
    FEATURES = FEATURES

    def __init__(self, model_path: Path | None = None) -> None:
        configured_path = os.getenv("FRAUD_MODEL_PATH")
        self.model_path = model_path or Path(configured_path or "app/models/isolation_forest.joblib")
        self._model: IsolationForest | None = None

    def check(self, features: dict[str, float]) -> dict[str, float | bool]:
        values = np.array([[float(features[feature]) for feature in self.FEATURES]])
        model = self._load_model()
        # score_samples = -s(x) with s in (0, 1); about 0.5 is borderline, higher is more anomalous.
        anomaly_score = round(float(np.clip(-model.score_samples(values)[0], 0.0, 1.0)), 4)
        return {"anomaly_score": anomaly_score, "flagged": bool(model.predict(values)[0] == -1)}

    def _load_model(self) -> IsolationForest:
        if self._model is not None:
            return self._model
        if self.model_path.exists():
            self._verify_integrity()
            self._model = joblib.load(self.model_path)
            return self._model
        if is_production():
            raise RuntimeError("fraud model file is missing; train it at build time (python -m app.training.train_isolation_forest)")
        from app.training.train_isolation_forest import save, train  # dev convenience: reproducible synthetic model
        model, metrics = train()
        save(model, metrics, self.model_path)
        self._model = model
        return model

    def _verify_integrity(self) -> None:
        """Pickled models can execute code when loaded, so a model whose digest does not match the recorded
        one is refused (blueprint OWASP 'insecure deserialization')."""
        digest_file = self.model_path.with_suffix(".sha256")
        if not digest_file.exists():
            if is_production():
                raise RuntimeError("fraud model digest file is missing")
            return
        import hashlib
        actual = hashlib.sha256(self.model_path.read_bytes()).hexdigest()
        if actual != digest_file.read_text().strip():
            raise RuntimeError("fraud model integrity check failed (digest mismatch)")
