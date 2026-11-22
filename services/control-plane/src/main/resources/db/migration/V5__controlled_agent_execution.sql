-- Attempt admission is separate from results: failed retrieval/configuration still consumes quota.
CREATE TABLE platform.agent_run_daily_quota (
    tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
    utc_day date NOT NULL,
    attempts integer NOT NULL CHECK (attempts BETWEEN 1 AND 20),
    PRIMARY KEY (tenant_id, utc_day)
);
ALTER TABLE platform.agent_run_daily_quota ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.agent_run_daily_quota FORCE ROW LEVEL SECURITY;
CREATE POLICY run_quota_read ON platform.agent_run_daily_quota FOR SELECT TO eaap_runtime
    USING (tenant_id = platform.current_tenant_id());
CREATE POLICY run_quota_insert ON platform.agent_run_daily_quota FOR INSERT TO eaap_runtime
    WITH CHECK (tenant_id = platform.current_tenant_id() AND utc_day = (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date AND attempts = 1);
CREATE POLICY run_quota_update ON platform.agent_run_daily_quota FOR UPDATE TO eaap_runtime
    USING (tenant_id = platform.current_tenant_id() AND utc_day = (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date)
    WITH CHECK (tenant_id = platform.current_tenant_id() AND utc_day = (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date);
GRANT SELECT, INSERT ON platform.agent_run_daily_quota TO eaap_runtime;
GRANT UPDATE (attempts) ON platform.agent_run_daily_quota TO eaap_runtime;

CREATE TABLE platform.agent_runs (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    agent_id uuid NOT NULL,
    user_id uuid NOT NULL REFERENCES platform.users(id),
    question text NOT NULL CHECK (char_length(question) BETWEEN 1 AND 1000),
    answer text CHECK (char_length(answer) BETWEEN 1 AND 6000),
    citations jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(citations) = 'array' AND jsonb_array_length(citations) <= 6),
    status varchar(16) NOT NULL CHECK (status IN ('RUNNING','SUCCEEDED','FAILED')),
    failure_code varchar(64),
    model_id varchar(100) CHECK (model_id IS NULL OR model_id = 'gpt-5.4-mini-2026-03-17'),
    input_tokens integer CHECK (input_tokens BETWEEN 0 AND 1000000),
    output_tokens integer CHECK (output_tokens BETWEEN 0 AND 800),
    duration_ms bigint CHECK (duration_ms >= 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at timestamptz,
    FOREIGN KEY (tenant_id,agent_id) REFERENCES platform.agent_configurations(tenant_id,id),
    CHECK (completed_at IS NULL OR completed_at >= created_at),
    CHECK (
        (status='RUNNING' AND completed_at IS NULL AND answer IS NULL AND failure_code IS NULL AND model_id IS NULL AND duration_ms IS NULL AND input_tokens IS NULL AND output_tokens IS NULL AND citations='[]'::jsonb)
        OR (status='SUCCEEDED' AND completed_at IS NOT NULL AND answer IS NOT NULL AND failure_code IS NULL AND model_id IS NOT NULL AND duration_ms IS NOT NULL AND jsonb_array_length(citations) BETWEEN 1 AND 6)
        OR (status='FAILED' AND completed_at IS NOT NULL AND answer IS NULL AND failure_code IS NOT NULL AND duration_ms IS NOT NULL AND citations='[]'::jsonb)
    )
);
CREATE INDEX agent_runs_stale_owner_idx ON platform.agent_runs (tenant_id,agent_id,user_id,created_at) WHERE status='RUNNING';
ALTER TABLE platform.agent_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.agent_runs FORCE ROW LEVEL SECURITY;
CREATE POLICY runs_read ON platform.agent_runs FOR SELECT TO eaap_runtime
    USING (tenant_id = platform.current_tenant_id());
CREATE POLICY runs_insert ON platform.agent_runs FOR INSERT TO eaap_runtime
    WITH CHECK (tenant_id = platform.current_tenant_id() AND status='RUNNING');
CREATE POLICY runs_finish ON platform.agent_runs FOR UPDATE TO eaap_runtime
    USING (tenant_id = platform.current_tenant_id() AND status='RUNNING')
    WITH CHECK (tenant_id = platform.current_tenant_id() AND status IN ('SUCCEEDED','FAILED'));
GRANT SELECT, INSERT ON platform.agent_runs TO eaap_runtime;
GRANT UPDATE (answer,citations,status,failure_code,model_id,input_tokens,output_tokens,duration_ms,completed_at)
    ON platform.agent_runs TO eaap_runtime;
-- Runtime cannot delete runs, edit their identities/questions, or overwrite a terminal row.
