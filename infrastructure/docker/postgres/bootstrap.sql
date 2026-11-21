-- Administrator-only provisioning, separate from versioned schema migrations.
-- Avoid logging password-bearing statements during this provisioning session.
SET log_statement = 'none';
SET log_min_error_statement = 'panic';
\getenv migration_password MIGRATION_DB_PASSWORD
\getenv runtime_password DB_PASSWORD
\getenv operator_password IDENTITY_OPERATOR_DB_PASSWORD

BEGIN;
SELECT 'CREATE ROLE eaap_migration'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'eaap_migration') \gexec
SELECT 'CREATE ROLE eaap_runtime'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'eaap_runtime') \gexec

SELECT 'CREATE ROLE eaap_identity_operator'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'eaap_identity_operator') \gexec
ALTER ROLE eaap_identity_operator WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD :'operator_password';

ALTER ROLE eaap_migration WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD :'migration_password';
ALTER ROLE eaap_runtime WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD :'runtime_password';

-- NOINHERIT alone does not prevent SET ROLE; remove memberships from dedicated roles.
SELECT format('REVOKE %I FROM %I', parent.rolname, member.rolname)
FROM pg_auth_members membership
JOIN pg_roles parent ON parent.oid = membership.roleid
JOIN pg_roles member ON member.oid = membership.member
WHERE member.rolname IN ('eaap_runtime', 'eaap_migration', 'eaap_identity_operator') \gexec

SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database()) \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO eaap_migration, eaap_runtime, eaap_identity_operator', current_database()) \gexec
REVOKE ALL ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA IF NOT EXISTS platform AUTHORIZATION eaap_migration;
DO $$
BEGIN
    IF (SELECT nspowner <> 'eaap_migration'::regrole FROM pg_namespace WHERE nspname = 'platform') THEN
        RAISE EXCEPTION 'Existing platform schema has an unexpected owner; manual review required';
    END IF;
END
$$;
REVOKE ALL ON SCHEMA platform FROM PUBLIC;
GRANT USAGE ON SCHEMA platform TO eaap_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE eaap_migration IN SCHEMA platform
    REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
SELECT format('ALTER ROLE eaap_runtime IN DATABASE %I SET search_path = pg_catalog, platform', current_database()) \gexec
SELECT format('ALTER ROLE eaap_migration IN DATABASE %I SET search_path = pg_catalog, platform', current_database()) \gexec
-- Identity hashes and agent instructions are sensitive. Keep SQL/failed-row dumps out of logs.
ALTER ROLE eaap_runtime SET log_error_verbosity = 'terse';
ALTER ROLE eaap_runtime SET log_min_error_statement = 'panic';
ALTER ROLE eaap_runtime SET log_statement = 'none';
ALTER ROLE eaap_runtime SET log_parameter_max_length = 0;
ALTER ROLE eaap_runtime SET log_parameter_max_length_on_error = 0;
ALTER ROLE eaap_identity_operator SET log_error_verbosity = 'terse';
ALTER ROLE eaap_identity_operator SET log_min_error_statement = 'panic';
ALTER ROLE eaap_identity_operator SET log_statement = 'none';
ALTER ROLE eaap_identity_operator SET log_parameter_max_length = 0;
ALTER ROLE eaap_identity_operator SET log_parameter_max_length_on_error = 0;
COMMIT;
