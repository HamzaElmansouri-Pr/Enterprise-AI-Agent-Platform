CREATE FUNCTION platform.current_tenant_id()
RETURNS uuid
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = ''
AS $$
    SELECT NULLIF(pg_catalog.current_setting('app.tenant_id', true), '')::uuid
$$;

REVOKE ALL ON FUNCTION platform.current_tenant_id() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.current_tenant_id() TO eaap_runtime;

CREATE TABLE platform.tenants (
    id uuid PRIMARY KEY,
    name varchar(200) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT tenants_name_not_blank CHECK (name ~ '[^[:space:]]'),
    CONSTRAINT tenants_timestamp_order CHECK (updated_at >= created_at)
);

ALTER TABLE platform.tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.tenants FORCE ROW LEVEL SECURITY;
CREATE POLICY tenants_self_read ON platform.tenants
    FOR SELECT TO eaap_runtime
    USING (id = platform.current_tenant_id());
GRANT SELECT ON platform.tenants TO eaap_runtime;
