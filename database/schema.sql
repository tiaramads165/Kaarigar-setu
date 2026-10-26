CREATE TABLE workers (
    worker_id BIGSERIAL PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    phone VARCHAR(30) NOT NULL UNIQUE,
    trade VARCHAR(80) NOT NULL,
    language VARCHAR(60) NOT NULL,
    skill_score NUMERIC(5,2) NOT NULL DEFAULT 0 CHECK (skill_score >= 0 AND skill_score <= 100),
    trust_score NUMERIC(5,2) NOT NULL DEFAULT 0 CHECK (trust_score >= 0 AND trust_score <= 100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE employers (
    employer_id BIGSERIAL PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    phone VARCHAR(30) NOT NULL UNIQUE,
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE assessments (
    assessment_id BIGSERIAL PRIMARY KEY,
    worker_id BIGINT NOT NULL REFERENCES workers(worker_id) ON DELETE CASCADE,
    trade VARCHAR(80) NOT NULL,
    transcript TEXT NOT NULL,
    score NUMERIC(5,2) NOT NULL CHECK (score >= 0 AND score <= 100),
    feedback TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE attestations (
    attestation_id BIGSERIAL PRIMARY KEY,
    worker_id BIGINT NOT NULL REFERENCES workers(worker_id) ON DELETE CASCADE,
    employer_id BIGINT NOT NULL REFERENCES employers(employer_id) ON DELETE CASCADE,
    otp_hash VARCHAR(255) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    anomaly_score NUMERIC(6,4) NOT NULL DEFAULT 0 CHECK (anomaly_score >= 0 AND anomaly_score <= 1),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_attestation_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);

CREATE TABLE trust_edges (
    edge_id BIGSERIAL PRIMARY KEY,
    source_id BIGINT NOT NULL REFERENCES workers(worker_id) ON DELETE CASCADE,
    target_id BIGINT NOT NULL REFERENCES workers(worker_id) ON DELETE CASCADE,
    weight NUMERIC(6,4) NOT NULL CHECK (weight >= 0 AND weight <= 1),
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT chk_trust_edge_not_self CHECK (source_id <> target_id)
);

CREATE TABLE credentials (
    credential_id BIGSERIAL PRIMARY KEY,
    worker_id BIGINT NOT NULL REFERENCES workers(worker_id) ON DELETE CASCADE,
    signed_payload TEXT NOT NULL,
    signature TEXT NOT NULL,
    issued_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE fraud_flags (
    flag_id BIGSERIAL PRIMARY KEY,
    attestation_id BIGINT NOT NULL REFERENCES attestations(attestation_id) ON DELETE CASCADE,
    anomaly_score NUMERIC(6,4) NOT NULL CHECK (anomaly_score >= 0 AND anomaly_score <= 1),
    reviewer_decision VARCHAR(30),
    reviewed_by VARCHAR(120),
    reviewed_at TIMESTAMP,
    CONSTRAINT chk_reviewer_decision CHECK (reviewer_decision IS NULL OR reviewer_decision IN ('PENDING', 'VALID', 'FRAUD'))
);

CREATE TABLE audit_log (
    log_id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(80) NOT NULL,
    actor VARCHAR(120) NOT NULL,
    details TEXT NOT NULL,
    timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
