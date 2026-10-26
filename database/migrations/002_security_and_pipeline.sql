-- Migration 002: what Phases 4-7 need on top of the Phase 1 schema (database/schema.sql stays untouched).
-- Additive only: every existing column, constraint and DAO signature keeps working.
-- Apply as the database owner, after schema.sql:   psql -d kaarigar_setu -f database/migrations/002_security_and_pipeline.sql

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid() on older servers; harmless on PG 13+

-- ---------------------------------------------------------------------------------------------------------
-- Personal data protection (DPDP Act). workers.phone / employers.phone now hold an opaque keyed-HMAC lookup
-- key (never the number itself); the number is recoverable only from phone_enc (AES-GCM, key from env).
-- ---------------------------------------------------------------------------------------------------------
-- The phone column is opaque now (a keyed HMAC, not a number), so it must be wider than VARCHAR(30).
ALTER TABLE workers   ALTER COLUMN phone TYPE VARCHAR(100);
ALTER TABLE employers ALTER COLUMN phone TYPE VARCHAR(100);

ALTER TABLE workers
    ADD COLUMN phone_enc       BYTEA,
    ADD COLUMN subject_id      UUID NOT NULL DEFAULT gen_random_uuid(),   -- pseudonymous id used in credentials
    ADD COLUMN consent_at      TIMESTAMP,
    ADD COLUMN consent_version VARCHAR(40),
    ADD COLUMN erased_at       TIMESTAMP;
CREATE UNIQUE INDEX uq_workers_subject ON workers (subject_id);

ALTER TABLE employers
    ADD COLUMN phone_enc       BYTEA,
    ADD COLUMN subject_id      UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN consent_at      TIMESTAMP,
    ADD COLUMN consent_version VARCHAR(40),
    ADD COLUMN erased_at       TIMESTAMP;
CREATE UNIQUE INDEX uq_employers_subject ON employers (subject_id);

-- ---------------------------------------------------------------------------------------------------------
-- One-time passwords: persistent, so lockout and expiry survive a restart and apply across nodes.
-- Only a salted (and optionally peppered) PBKDF2 hash is stored, never the code.
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE otp_challenges (
    challenge_id  BIGSERIAL PRIMARY KEY,
    challenge_key VARCHAR(200) NOT NULL,        -- 'login:WORKER:<hmac>' | 'attestation:<id>' | 'moderator:<user>'
    otp_hash      VARCHAR(255) NOT NULL,        -- base64(salt) '$' base64(pbkdf2)
    expires_at    TIMESTAMP    NOT NULL,
    attempts      SMALLINT     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    consumed_at   TIMESTAMP,
    ip_hash       VARCHAR(64),
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_otp_key_created ON otp_challenges (challenge_key, created_at DESC);

-- ---------------------------------------------------------------------------------------------------------
-- Assessments: each row is one answered question inside an attempt (5 rows = a full assessment).
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE assessments
    ADD COLUMN attempt_id    UUID,
    ADD COLUMN question_no   SMALLINT CHECK (question_no IS NULL OR question_no BETWEEN 1 AND 20),
    ADD COLUMN language      VARCHAR(10),
    ADD COLUMN needs_review  BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN review_reason TEXT,
    ADD COLUMN provider      VARCHAR(60);
CREATE INDEX idx_assessments_attempt ON assessments (worker_id, attempt_id);
CREATE UNIQUE INDEX uq_assessment_attempt_question ON assessments (attempt_id, question_no) WHERE attempt_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------------------
-- Attestations: OTP-pending and review states, the work-quality feedback form, and the network facts the
-- fraud model needs. One live attestation per worker + employer pair.
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE attestations DROP CONSTRAINT chk_attestation_status;
ALTER TABLE attestations ADD CONSTRAINT chk_attestation_status
    CHECK (status IN ('OTP_PENDING', 'PENDING', 'PENDING_REVIEW', 'APPROVED', 'REJECTED', 'EXPIRED'));
ALTER TABLE attestations
    ADD COLUMN job_role         VARCHAR(80),
    ADD COLUMN duration_days    INT CHECK (duration_days IS NULL OR duration_days BETWEEN 0 AND 3650),
    ADD COLUMN would_hire_again BOOLEAN,
    ADD COLUMN work_date        DATE,
    ADD COLUMN source_ip_hash   VARCHAR(64),
    ADD COLUMN device_hash      VARCHAR(64),
    ADD COLUMN decided_at       TIMESTAMP;
CREATE UNIQUE INDEX uq_attestation_pair ON attestations (worker_id, employer_id) WHERE status NOT IN ('REJECTED', 'EXPIRED');
CREATE INDEX idx_attestations_ip     ON attestations (source_ip_hash, created_at);
CREATE INDEX idx_attestations_device ON attestations (device_hash, created_at);
CREATE INDEX idx_attestations_employer ON attestations (employer_id, created_at);

-- ---------------------------------------------------------------------------------------------------------
-- Trust graph: edges run employer -> worker (an attestation), so the source is no longer always a worker.
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE trust_edges DROP CONSTRAINT trust_edges_source_id_fkey;
ALTER TABLE trust_edges DROP CONSTRAINT chk_trust_edge_not_self;
ALTER TABLE trust_edges
    ADD COLUMN source_kind    VARCHAR(10) NOT NULL DEFAULT 'WORKER' CHECK (source_kind IN ('WORKER', 'EMPLOYER')),
    ADD COLUMN attestation_id BIGINT REFERENCES attestations (attestation_id) ON DELETE CASCADE,
    ADD COLUMN created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE trust_edges ADD CONSTRAINT chk_trust_edge_not_self CHECK (source_kind <> 'WORKER' OR source_id <> target_id);
CREATE UNIQUE INDEX uq_trust_edge_attestation ON trust_edges (attestation_id) WHERE attestation_id IS NOT NULL;
CREATE INDEX idx_trust_edges_target ON trust_edges (target_id);

-- ---------------------------------------------------------------------------------------------------------
-- Credentials: a non-guessable public reference for QR links, expiry, and revocation.
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE credentials
    ADD COLUMN credential_ref     UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN expires_at         TIMESTAMP,
    ADD COLUMN revoked_at         TIMESTAMP,
    ADD COLUMN revocation_reason  VARCHAR(200),
    ADD COLUMN key_id             VARCHAR(80);
CREATE UNIQUE INDEX uq_credentials_ref ON credentials (credential_ref);
CREATE INDEX idx_credentials_worker ON credentials (worker_id, issued_at DESC);

-- ---------------------------------------------------------------------------------------------------------
-- Fraud flags: keep the features the model saw so a moderator can see WHY, and one flag per attestation.
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE fraud_flags
    ADD COLUMN features   TEXT,
    ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE fraud_flags ADD CONSTRAINT uq_fraud_flag_attestation UNIQUE (attestation_id);

-- ---------------------------------------------------------------------------------------------------------
-- Audit log: append-only AND hash-chained. Each row stores the hash of the previous row, so any edit,
-- deletion or reordering of history is detectable with verify_audit_chain(). UPDATE/DELETE/TRUNCATE are blocked
-- by triggers even for the application role (the owner can still disable triggers, which is why the
-- application never connects as the owner).
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE audit_log
    ADD COLUMN chain_seq  BIGINT,
    ADD COLUMN prev_hash  CHAR(64),
    ADD COLUMN entry_hash CHAR(64);
CREATE UNIQUE INDEX uq_audit_chain_seq ON audit_log (chain_seq);

CREATE FUNCTION audit_entry_hash(prev CHAR, etype TEXT, who TEXT, info TEXT, ts TIMESTAMP) RETURNS CHAR(64) AS $$
    SELECT encode(sha256(convert_to(prev || '|' || etype || '|' || who || '|' || info || '|'
                                    || to_char(ts, 'YYYY-MM-DD"T"HH24:MI:SS.US'), 'UTF8')), 'hex')::CHAR(64)
$$ LANGUAGE sql IMMUTABLE;

CREATE FUNCTION audit_log_chain() RETURNS trigger AS $$
DECLARE
    last_row RECORD;
BEGIN
    PERFORM pg_advisory_xact_lock(727001);    -- serialise appends so the chain order is well defined
    SELECT chain_seq, entry_hash INTO last_row FROM audit_log ORDER BY chain_seq DESC LIMIT 1;
    NEW.chain_seq  := COALESCE(last_row.chain_seq, 0) + 1;
    NEW.prev_hash  := COALESCE(last_row.entry_hash, repeat('0', 64));
    NEW.entry_hash := audit_entry_hash(NEW.prev_hash, NEW.event_type, NEW.actor, NEW.details, NEW."timestamp");
    RETURN NEW;
END
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_audit_log_chain BEFORE INSERT ON audit_log FOR EACH ROW EXECUTE FUNCTION audit_log_chain();

CREATE FUNCTION audit_log_block_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only';
END
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_audit_log_no_update BEFORE UPDATE OR DELETE ON audit_log FOR EACH ROW EXECUTE FUNCTION audit_log_block_mutation();
CREATE TRIGGER trg_audit_log_no_truncate BEFORE TRUNCATE ON audit_log FOR EACH STATEMENT EXECUTE FUNCTION audit_log_block_mutation();

CREATE FUNCTION verify_audit_chain() RETURNS TABLE (valid BOOLEAN, first_bad_seq BIGINT, checked BIGINT) AS $$
DECLARE
    r        RECORD;
    prev     CHAR(64) := repeat('0', 64);
    n        BIGINT := 0;
BEGIN
    FOR r IN SELECT * FROM audit_log ORDER BY chain_seq LOOP
        n := n + 1;
        IF r.chain_seq <> n OR r.prev_hash <> prev
           OR r.entry_hash <> audit_entry_hash(prev, r.event_type, r.actor, r.details, r."timestamp") THEN
            RETURN QUERY SELECT FALSE, r.chain_seq, n;
            RETURN;
        END IF;
        prev := r.entry_hash;
    END LOOP;
    RETURN QUERY SELECT TRUE, 0::BIGINT, n;
END
$$ LANGUAGE plpgsql STABLE;
