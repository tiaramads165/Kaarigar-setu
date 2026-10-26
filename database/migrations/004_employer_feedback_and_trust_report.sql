-- Migration 004: structured employer feedback and the explainable trust report.
-- Additive only. Existing attestations keep NULL ratings (they count as "unrated confirmations", see docs/TRUST_SCORE.md).
ALTER TABLE attestations
    ADD COLUMN rating_quality     SMALLINT CHECK (rating_quality     IS NULL OR rating_quality     BETWEEN 1 AND 5),   -- quality of the work
    ADD COLUMN rating_punctuality SMALLINT CHECK (rating_punctuality IS NULL OR rating_punctuality BETWEEN 1 AND 5),   -- punctuality and reliability
    ADD COLUMN rating_conduct     SMALLINT CHECK (rating_conduct     IS NULL OR rating_conduct     BETWEEN 1 AND 5);   -- conduct and honesty

ALTER TABLE workers
    ADD COLUMN trust_band   VARCHAR(16) CHECK (trust_band IS NULL OR trust_band IN ('EXCELLENT', 'GOOD', 'FAIR', 'BUILDING', 'NO_HISTORY')),
    ADD COLUMN trust_report TEXT;                                   -- JSON: the factors behind the score and what would improve it

CREATE INDEX idx_attestations_approved ON attestations (worker_id) WHERE status = 'APPROVED';
