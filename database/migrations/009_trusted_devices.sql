-- Migration 009: trusted devices, the fix for the targeted sign-in lockout (attack harness AUTH-08).
--
-- A phone number's code limits used to be shared by everyone who typed that number, so a stranger could burn a victim's request quota (3 per 10 minutes)
-- or lock the victim's pending code with five wrong guesses. Now a browser that has already signed in as an account is remembered as a TRUSTED DEVICE:
-- it has its own request budget and its own code, and nothing a stranger does with that phone number can consume or lock them.
--
-- The server stores only a keyed hash of a random token that the browser keeps in an HttpOnly cookie. A trusted device is NOT a way to sign in (the code is
-- still required): it only decides which rate-limit bucket a code request counts against. At most 5 devices per account; unused ones expire.
CREATE TABLE trusted_devices (
    device_id    BIGSERIAL PRIMARY KEY,
    role         VARCHAR(10) NOT NULL CHECK (role IN ('WORKER', 'EMPLOYER')),
    account_id   BIGINT      NOT NULL,                  -- no foreign key: the id belongs to workers or employers by role; erasure deletes these rows explicitly
    token_hash   VARCHAR(64) NOT NULL UNIQUE,
    created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_trusted_devices_account ON trusted_devices (role, account_id, last_used_at DESC);

-- Existing databases: the application role manages these rows (a fresh setup gets the same from setup_roles.sql).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'kaarigar_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON trusted_devices TO kaarigar_app;
        GRANT USAGE, SELECT ON SEQUENCE trusted_devices_device_id_seq TO kaarigar_app;
    END IF;
END
$$;
