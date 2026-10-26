-- Least-privilege application role (blueprint Phase 0/1/4). Run AFTER schema.sql and every migration,
-- as the database owner:   psql -d kaarigar_setu -v app_password="$DB_PASSWORD" -f database/setup_roles.sql
-- The application connects as kaarigar_app: DML only, no DDL, and the audit trail is append-only.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'kaarigar_app') THEN
        CREATE ROLE kaarigar_app LOGIN;
    END IF;
END
$$;
ALTER ROLE kaarigar_app WITH PASSWORD :'app_password' NOSUPERUSER NOCREATEDB NOCREATEROLE;

REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO kaarigar_app;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO kaarigar_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO kaarigar_app;
-- DPDP erasure (ErasureService) needs DELETE on exactly these tables. Rows of assessments, attestations, fraud_flags and
-- credentials are removed by ON DELETE CASCADE from workers, which runs with the table owner's rights, so they need no grant.
GRANT DELETE ON workers, trust_edges, otp_challenges, trusted_devices TO kaarigar_app;
-- audit_log is append-only for the application.
REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM kaarigar_app;
-- Moderator accounts are created, disabled and re-passworded only by an operator (scripts/moderator-admin.sh, as the database owner), never by the web
-- application: if the application could write this table, a compromised application could mint itself a moderator.
REVOKE INSERT, UPDATE ON moderators FROM kaarigar_app;
-- Replay-detection fingerprints (migration 011) are insert-only for the application: a compromised web application must not be able to edit what later answers are compared with.
-- (They are removed by ON DELETE CASCADE from assessments, with the table owner's rights.)
REVOKE UPDATE ON answer_fingerprints, answer_shingles FROM kaarigar_app;
