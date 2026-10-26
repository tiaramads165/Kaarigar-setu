-- Migration 008: named moderator accounts and two-person approval.
--
-- Until now there was one shared moderator login from the environment, so the audit trail could only say "service-account": it could not say WHICH PERSON vetted
-- an employer or ruled on a dispute, and one stolen or fooled login was enough to vet a fake employer or remove a true rating. Now:
--   * every moderator is a person with their own account (moderators);
--   * the actions that move trust (vetting an employer, ruling on a dispute) are PROPOSED by one moderator and take effect only when a DIFFERENT moderator
--     approves (moderator_actions). The rule is enforced three times: in the service, in the approving UPDATE, and by a CHECK constraint on the table.
--
-- Accounts are created, disabled and re-passworded only by an operator with database-owner rights (scripts/moderator-admin.sh), never through the web
-- application: setup_roles.sql gives the application role SELECT only on moderators, so a compromised web application cannot mint itself a moderator.
CREATE TABLE moderators (
    moderator_id    BIGSERIAL PRIMARY KEY,
    username        VARCHAR(40)  NOT NULL UNIQUE CHECK (username ~ '^[a-z][a-z0-9._-]{2,39}$'),
    display_name    VARCHAR(80)  NOT NULL,
    password_hash   TEXT         NOT NULL,
    phone_encrypted BYTEA        NOT NULL,                  -- where this person's sign-in code goes (AES-GCM, same protection as worker phones)
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(80)  NOT NULL,
    disabled_at     TIMESTAMP,
    CONSTRAINT chk_moderator_disabled CHECK (active OR disabled_at IS NOT NULL)
);

CREATE TABLE moderator_actions (
    action_id    BIGSERIAL PRIMARY KEY,
    action_type  VARCHAR(20) NOT NULL CHECK (action_type IN ('VET_EMPLOYER', 'DISPUTE_DECISION')),
    target_id    BIGINT      NOT NULL,                      -- the employer id, or the attestation id of the dispute
    verdict      VARCHAR(12) CHECK (verdict IN ('UPHELD', 'REMOVED')),
    proposed_by  BIGINT      NOT NULL REFERENCES moderators (moderator_id),
    proposed_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status       VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'EXECUTED', 'REJECTED', 'WITHDRAWN', 'EXPIRED')),
    decided_by   BIGINT      REFERENCES moderators (moderator_id),
    decided_at   TIMESTAMP,
    CONSTRAINT chk_action_verdict CHECK ((action_type = 'DISPUTE_DECISION') = (verdict IS NOT NULL)),
    CONSTRAINT chk_action_decided CHECK ((status = 'PENDING') = (decided_at IS NULL)),
    -- the four-eyes rule, at the lowest level: no row can ever be EXECUTED by the person who proposed it
    CONSTRAINT chk_four_eyes CHECK (status <> 'EXECUTED' OR (decided_by IS NOT NULL AND decided_by <> proposed_by))
);
-- one open proposal per target and kind: a second, conflicting proposal cannot be queued behind the first
CREATE UNIQUE INDEX idx_one_pending_action ON moderator_actions (action_type, target_id) WHERE status = 'PENDING';

-- Existing databases: the application role reads moderators, and reads and updates proposals. (A fresh setup gets the same from setup_roles.sql.)
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'kaarigar_app') THEN
        GRANT SELECT ON moderators TO kaarigar_app;
        GRANT SELECT, INSERT, UPDATE ON moderator_actions TO kaarigar_app;
        GRANT USAGE, SELECT ON SEQUENCE moderator_actions_action_id_seq TO kaarigar_app;
    END IF;
END
$$;
