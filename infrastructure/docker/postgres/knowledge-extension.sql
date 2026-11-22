\set ON_ERROR_STOP on
BEGIN;
CREATE SCHEMA IF NOT EXISTS extensions;
REVOKE ALL ON SCHEMA extensions FROM PUBLIC;
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA extensions;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension e JOIN pg_namespace n ON n.oid=e.extnamespace
                   WHERE e.extname='vector' AND n.nspname='extensions') THEN
        RAISE EXCEPTION 'vector must be provisioned in extensions schema by an operator';
    END IF;
END $$;
GRANT USAGE ON SCHEMA extensions TO eaap_migration, eaap_runtime;
COMMIT;
