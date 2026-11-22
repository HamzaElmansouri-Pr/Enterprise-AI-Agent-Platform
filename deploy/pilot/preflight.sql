SET statement_timeout='5s';
SELECT to_regclass('platform.flyway_schema_history') IS NOT NULL AS has_history \gset
\if :has_history
SELECT COALESCE(max(version),'') AS migration FROM platform.flyway_schema_history WHERE success \gset
\else
\set migration ''
\endif
SELECT json_build_object(
 'postgresMajor',current_setting('server_version_num')::int / 10000,
 'clusterId',(SELECT system_identifier::text FROM pg_control_system()),
 'vectorVersion',(SELECT extversion FROM pg_extension WHERE extname='vector' AND extnamespace='extensions'::regnamespace),
 'rolesSafe',(SELECT count(*)=3 AND bool_and(NOT rolsuper AND NOT rolbypassrls AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication) FROM pg_roles WHERE rolname IN ('eaap_runtime','eaap_migration','eaap_identity_operator'))
   AND NOT EXISTS(SELECT FROM pg_auth_members a JOIN pg_roles r ON r.oid=a.member WHERE r.rolname IN ('eaap_runtime','eaap_migration','eaap_identity_operator')),
 'objects',
   (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema'
      AND NOT EXISTS(SELECT FROM pg_depend d WHERE d.classid='pg_class'::regclass AND d.objid=c.oid AND d.deptype='e'))
   + (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
      WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema'
        AND NOT EXISTS(SELECT FROM pg_depend d WHERE d.classid='pg_proc'::regclass AND d.objid=p.oid AND d.deptype='e'))
   + (SELECT count(*) FROM pg_namespace WHERE nspname !~ '^pg_' AND nspname NOT IN ('information_schema','public','platform','extensions')),
 'forcedRls',(SELECT count(*)=15 AND bool_and(c.relrowsecurity AND c.relforcerowsecurity AND c.relowner='eaap_migration'::regrole) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='platform' AND c.relkind='r' AND c.relname NOT IN ('flyway_schema_history','users','tenant_memberships')),
 'migration',:'migration'
);
