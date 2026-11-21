CREATE TABLE platform.agent_configurations (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
    name varchar(120) NOT NULL,
    description varchar(1000),
    system_instructions text NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'ARCHIVED')),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT agent_name_present CHECK (char_length(name) >= 1),
    CONSTRAINT agent_name_trimmed CHECK (name !~ U&'^[\0009-\000D\0020\0085\00A0\1680\2000-\200A\2028\2029\202F\205F\3000]|[\0009-\000D\0020\0085\00A0\1680\2000-\200A\2028\2029\202F\205F\3000]$'),
    CONSTRAINT agent_name_safe CHECK (name !~ '[[:cntrl:]]' AND name !~ U&'[\2028\2029\202A-\202E\2066-\2069]'),
    CONSTRAINT agent_instructions_bounded CHECK (char_length(system_instructions) BETWEEN 1 AND 10000),
    CONSTRAINT agent_instructions_present CHECK (system_instructions ~ U&'[^\0009-\000D\0020\0085\00A0\1680\2000-\200A\2028\2029\202F\205F\3000]'),
    CONSTRAINT agent_timestamp_order CHECK (updated_at >= created_at)
);
CREATE INDEX agent_configurations_tenant_created_idx
    ON platform.agent_configurations (tenant_id, created_at DESC, id DESC);
ALTER TABLE platform.agent_configurations ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.agent_configurations FORCE ROW LEVEL SECURITY;
CREATE POLICY agents_tenant_read ON platform.agent_configurations FOR SELECT TO eaap_runtime
    USING (tenant_id = platform.current_tenant_id());
CREATE POLICY agents_tenant_insert ON platform.agent_configurations FOR INSERT TO eaap_runtime
    WITH CHECK (tenant_id = platform.current_tenant_id() AND status = 'DRAFT');
CREATE POLICY agents_tenant_update ON platform.agent_configurations FOR UPDATE TO eaap_runtime
    USING (tenant_id = platform.current_tenant_id() AND status = 'DRAFT')
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT ON platform.agent_configurations TO eaap_runtime;
GRANT UPDATE (name, description, system_instructions, status, updated_at)
    ON platform.agent_configurations TO eaap_runtime;
