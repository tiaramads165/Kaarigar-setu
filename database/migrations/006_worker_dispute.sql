-- Migration 006: the worker's say in what employers write about them.
--
-- An employer's confirmation is authorised with a code sent to the EMPLOYER's phone, so until now a worker was never asked and could not object: an employer
-- could post a rating, including a bad one, about any registered worker. A worker may now ACCEPT a confirmation or DISPUTE it. Acceptance is optional and
-- changes nothing; it is a record. A dispute holds the confirmation out of every score until a moderator rules: UPHELD (it counts again) or REMOVED (it
-- never counts). A worker cannot simply refuse ratings they dislike: refusing is not an option, only disputing, and a moderator decides.
-- Why not make acceptance mandatory: a worker could then refuse every bad rating and the trust score would be a number the worker controls.
ALTER TABLE attestations
    ADD COLUMN worker_response      VARCHAR(12)  CHECK (worker_response IS NULL OR worker_response IN ('ACCEPTED', 'DISPUTED')),
    ADD COLUMN responded_at         TIMESTAMP,
    ADD COLUMN dispute_reason       VARCHAR(300),
    ADD COLUMN dispute_decision     VARCHAR(12)  CHECK (dispute_decision IS NULL OR dispute_decision IN ('UPHELD', 'REMOVED')),
    ADD COLUMN dispute_decided_at   TIMESTAMP,
    ADD COLUMN dispute_decided_by   VARCHAR(120),
    ADD CONSTRAINT chk_dispute_consistency CHECK (
        (worker_response = 'DISPUTED' AND dispute_reason IS NOT NULL) OR (worker_response IS DISTINCT FROM 'DISPUTED' AND dispute_reason IS NULL AND dispute_decision IS NULL));

CREATE INDEX idx_attestations_open_dispute ON attestations (worker_id) WHERE worker_response = 'DISPUTED' AND dispute_decision IS NULL;
