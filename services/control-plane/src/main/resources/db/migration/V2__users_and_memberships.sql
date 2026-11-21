-- Global identity boundary: runtime can execute only parameterized lookup functions.
CREATE TABLE platform.users (
    id uuid PRIMARY KEY,
    email varchar(254) NOT NULL UNIQUE,
    password_hash varchar(512) NOT NULL CHECK (password_hash LIKE '$argon2id$%'),
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP CHECK (updated_at >= created_at),
    CHECK (length(split_part(email, '@', 1)) <= 64),
    CHECK (email = lower(btrim(email)) AND email ~ '^[a-z0-9!#$%&''*+/=?^_`{|}~-]+(\.[a-z0-9!#$%&''*+/=?^_`{|}~-]+)*@[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*\.[a-z]{2,63}$')
);
CREATE TABLE platform.tenant_memberships (
    user_id uuid NOT NULL REFERENCES platform.users(id),
    tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
    role varchar(6) NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER')),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, tenant_id)
);
-- Supports tenant FK checks without duplicating the user-first primary-key index.
CREATE INDEX tenant_memberships_tenant_idx ON platform.tenant_memberships(tenant_id);

CREATE FUNCTION platform.login_identity(login_email text)
RETURNS TABLE(id uuid, password_hash varchar, enabled boolean)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = ''
AS $$ SELECT u.id, u.password_hash, u.enabled FROM platform.users u WHERE u.email = login_email $$;
CREATE FUNCTION platform.enabled_identity(user_uuid uuid)
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path = ''
AS $$ SELECT EXISTS(SELECT 1 FROM platform.users u WHERE u.id = user_uuid AND u.enabled) $$;
CREATE FUNCTION platform.membership_role(user_uuid uuid, tenant_uuid uuid)
RETURNS varchar LANGUAGE sql STABLE SECURITY DEFINER SET search_path = ''
AS $$ SELECT m.role FROM platform.tenant_memberships m JOIN platform.users u ON u.id = m.user_id
      WHERE m.user_id = user_uuid AND m.tenant_id = tenant_uuid AND u.enabled $$;
REVOKE ALL ON FUNCTION platform.login_identity(text), platform.enabled_identity(uuid),
    platform.membership_role(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.login_identity(text), platform.enabled_identity(uuid),
    platform.membership_role(uuid, uuid) TO eaap_runtime;

-- Dedicated offline operator: no UPDATE/DELETE, no ownership, no membership in runtime/migration.
GRANT USAGE ON SCHEMA platform TO eaap_identity_operator;
GRANT EXECUTE ON FUNCTION platform.current_tenant_id() TO eaap_identity_operator;
GRANT SELECT, INSERT ON platform.tenants TO eaap_identity_operator;
CREATE POLICY tenants_operator_read ON platform.tenants FOR SELECT TO eaap_identity_operator
    USING (id = platform.current_tenant_id());
CREATE POLICY tenants_operator_insert ON platform.tenants FOR INSERT TO eaap_identity_operator
    WITH CHECK (id = platform.current_tenant_id());
GRANT SELECT (id, email, enabled), INSERT ON platform.users TO eaap_identity_operator;
GRANT SELECT, INSERT ON platform.tenant_memberships TO eaap_identity_operator;
