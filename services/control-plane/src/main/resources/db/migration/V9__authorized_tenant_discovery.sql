-- Discovery is a separate, parameterized identity capability, not a tenant directory.
-- FORCE RLS still applies to the definer. Only the migration role can use this
-- policy, and it exposes names solely for the enabled identity bound by the function.
CREATE POLICY tenants_discovery_read ON platform.tenants FOR SELECT TO eaap_migration
USING (EXISTS (
    SELECT 1 FROM platform.tenant_memberships m
    JOIN platform.users u ON u.id=m.user_id
    WHERE m.tenant_id=tenants.id AND u.enabled
      AND u.id=NULLIF(pg_catalog.current_setting('app.discovery_user_id',true),'')::uuid
));

CREATE FUNCTION platform.discover_tenants(actor uuid, n integer, o bigint)
RETURNS TABLE(tenant_id uuid, name text, role text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $$
DECLARE previous_actor text;
BEGIN
    IF actor IS NULL OR n IS NULL OR n<1 OR n>101 OR o IS NULL OR o<0 OR o>10000 THEN
        RAISE EXCEPTION 'invalid_discovery_request' USING ERRCODE='22023';
    END IF;
    previous_actor:=pg_catalog.current_setting('app.discovery_user_id',true);
    PERFORM pg_catalog.set_config('app.discovery_user_id',actor::text,true);
    RETURN QUERY SELECT t.id,t.name::text,m.role::text
      FROM platform.tenant_memberships m JOIN platform.users u ON u.id=m.user_id
      JOIN platform.tenants t ON t.id=m.tenant_id
      WHERE m.user_id=actor AND u.enabled
      ORDER BY t.id LIMIT n OFFSET o;
    -- Restore the assertion before returning rows. Any statement failure rolls
    -- back its transaction-local setting; no parameter/role privilege is added.
    PERFORM pg_catalog.set_config('app.discovery_user_id',COALESCE(previous_actor,''),true);
END $$;
REVOKE ALL ON FUNCTION platform.discover_tenants(uuid,integer,bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.discover_tenants(uuid,integer,bigint) TO eaap_runtime;
