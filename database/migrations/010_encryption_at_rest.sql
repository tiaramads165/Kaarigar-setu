-- Migration 010: encryption at rest beyond phone numbers.
--
-- Transcripts, scores, ratings, names, signed credential payloads and the details of audit events are stored as AES-256-GCM ciphertext written by the
-- application (security/DataCipher; the keys live in the environment, not in the database). A sealed value is text, so every column that now holds one is
-- TEXT. The numeric/boolean/date types and their CHECK ranges move into the application, which already validates every one of these values before storing
-- it (a ciphertext cannot be range-checked by the database, and that is the point of it).
--
-- This migration changes only the column types: existing values stay readable as plain text until scripts/seal-at-rest.sh seals them (the application
-- reads both). Left plain on purpose, because the application or an operator must be able to filter, count or join on them: ids, statuses, timestamps,
-- trade, language, the fraud model's anomaly score, trust-graph weights, hashes, and the event type, actor and time of audit events.
DROP VIEW counted_attestations;                    -- SELECT * over attestations: it must be rebuilt after the column types change

ALTER TABLE workers
    DROP CONSTRAINT workers_skill_score_check,
    DROP CONSTRAINT workers_trust_score_check,
    DROP CONSTRAINT workers_skill_band_check,
    DROP CONSTRAINT workers_trust_band_check,
    ALTER COLUMN skill_score DROP DEFAULT,
    ALTER COLUMN trust_score DROP DEFAULT;
ALTER TABLE workers
    ALTER COLUMN band_near_boundary DROP NOT NULL,          -- sealed 'true'/'false'; absent means false
    ALTER COLUMN band_near_boundary DROP DEFAULT;
ALTER TABLE workers
    ALTER COLUMN name        TYPE TEXT,
    ALTER COLUMN band_near_boundary TYPE TEXT USING band_near_boundary::text,
    ALTER COLUMN skill_score TYPE TEXT USING skill_score::text,
    ALTER COLUMN trust_score TYPE TEXT USING trust_score::text,
    ALTER COLUMN skill_band  TYPE TEXT,
    ALTER COLUMN trust_band  TYPE TEXT;

ALTER TABLE employers
    ALTER COLUMN name TYPE TEXT;

ALTER TABLE assessments
    DROP CONSTRAINT assessments_score_check;
ALTER TABLE assessments
    ALTER COLUMN score TYPE TEXT USING score::text;

ALTER TABLE attestations
    DROP CONSTRAINT attestations_duration_days_check,
    DROP CONSTRAINT attestations_rating_quality_check,
    DROP CONSTRAINT attestations_rating_punctuality_check,
    DROP CONSTRAINT attestations_rating_conduct_check;
ALTER TABLE attestations
    ALTER COLUMN job_role           TYPE TEXT,
    ALTER COLUMN dispute_reason     TYPE TEXT,
    ALTER COLUMN duration_days      TYPE TEXT USING duration_days::text,
    ALTER COLUMN would_hire_again   TYPE TEXT USING would_hire_again::text,
    ALTER COLUMN work_date          TYPE TEXT USING work_date::text,
    ALTER COLUMN rating_quality     TYPE TEXT USING rating_quality::text,
    ALTER COLUMN rating_punctuality TYPE TEXT USING rating_punctuality::text,
    ALTER COLUMN rating_conduct     TYPE TEXT USING rating_conduct::text;

-- The same single definition of "this confirmation counts" as migration 007 (it reads only status and the dispute columns).
CREATE VIEW counted_attestations AS
    SELECT * FROM attestations
    WHERE status = 'APPROVED' AND (worker_response IS DISTINCT FROM 'DISPUTED' OR dispute_decision = 'UPHELD');

-- Existing databases: re-grant what dropping the view removed. (A fresh setup gets the same from setup_roles.sql.)
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'kaarigar_app') THEN
        GRANT SELECT ON counted_attestations TO kaarigar_app;
    END IF;
END
$$;
