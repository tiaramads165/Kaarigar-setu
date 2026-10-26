-- Migration 003: the skill band (Pass / Borderline / Needs review) and the per-area breakdown behind a worker's skill score.
-- Additive only. Existing rows are back-filled with a band from their current score (thresholds 70 and 50, the defaults of PASS_THRESHOLD and
-- BORDERLINE_THRESHOLD); they have no breakdown until the worker takes the assessment again.
ALTER TABLE workers
    ADD COLUMN skill_band         VARCHAR(16) CHECK (skill_band IS NULL OR skill_band IN ('PASS', 'BORDERLINE', 'NEEDS_REVIEW')),
    ADD COLUMN skill_breakdown    TEXT,                                  -- JSON object: area -> score (0-100)
    ADD COLUMN band_near_boundary BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE workers SET skill_band = CASE WHEN skill_score >= 70 THEN 'PASS' WHEN skill_score >= 50 THEN 'BORDERLINE' ELSE 'NEEDS_REVIEW' END
 WHERE skill_score > 0;
