-- Migration 005: employer vetting, a trust anchor that registering cannot buy.
--
-- Until now an employer was marked "verified" automatically after its first approved confirmation, so a throwaway account got the flag (and the credibility
-- bonus) for free, and three such accounts were enough to give a worker the top trust band (see docs/FRAUD_ATTACK_SIMULATION.md). From now on
-- employers.verified means "a moderator vetted this employer" (an identity or business check done outside the application); who and when are recorded here
-- and in the audit trail. The Excellent band needs at least one vetted confirming employer.
ALTER TABLE employers
    ADD COLUMN verified_by VARCHAR(120),
    ADD COLUMN verified_at TIMESTAMP;

-- Rows verified automatically by earlier versions were never vetted: reset them. (Local demo data is re-vetted by the demo seed.)
UPDATE employers SET verified = FALSE WHERE verified_by IS NULL;
