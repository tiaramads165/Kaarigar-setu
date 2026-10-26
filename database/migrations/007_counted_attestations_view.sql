-- Migration 007: one definition of "this confirmation counts".
--
-- A confirmation counts toward the trust score, the credential, the passport and an employer's track record when it is approved and is not held out by a
-- worker's dispute (an open dispute, or one a moderator ruled REMOVED). The rule used to be assembled in Java as a SQL fragment; the project's own guard test
-- (NoStringBuiltSqlTest) forbids building SQL from anything but literals, and a fragment spliced in from a method is exactly what it forbids. A view keeps the
-- single definition, in the database, and every query reads FROM it as plain literal SQL. Changing the rule is one change here.
CREATE OR REPLACE VIEW counted_attestations AS
    SELECT * FROM attestations
    WHERE status = 'APPROVED' AND (worker_response IS DISTINCT FROM 'DISPUTED' OR dispute_decision = 'UPHELD');

-- The application role only reads the view. A database whose role has another name needs the same grant (or a re-run of setup_roles.sql, whose
-- GRANT ... ON ALL TABLES covers views that exist at that moment).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'kaarigar_app') THEN
        GRANT SELECT ON counted_attestations TO kaarigar_app;
    END IF;
END
$$;
