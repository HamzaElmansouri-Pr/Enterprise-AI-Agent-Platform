CREATE TABLE platform.tool_proposals (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    agent_id uuid NOT NULL,
    created_by uuid NOT NULL REFERENCES platform.users(id),
    tool_name varchar(64) NOT NULL CHECK (tool_name='admissions.create_followup_task'),
    tool_version integer NOT NULL CHECK (tool_version=1),
    arguments jsonb NOT NULL,
    rationale text NOT NULL CHECK (char_length(rationale) BETWEEN 1 AND 500),
    payload_digest varchar(64) NOT NULL CHECK (payload_digest ~ '^[0-9a-f]{64}$'),
    status varchar(24) NOT NULL DEFAULT 'PENDING_APPROVAL' CHECK (status IN ('PENDING_APPROVAL','APPROVED','REJECTED','EXECUTING','SUCCEEDED','FAILED')),
    correlation_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    executed_by uuid REFERENCES platform.users(id),
    execution_correlation_id uuid,
    started_at timestamptz,
    completed_at timestamptz,
    task_id uuid,
    failure_code varchar(64),
    UNIQUE (tenant_id,id),
    UNIQUE (tenant_id,id,payload_digest,created_by),
    FOREIGN KEY (tenant_id,agent_id) REFERENCES platform.agent_configurations(tenant_id,id),
    CHECK (jsonb_typeof(arguments)='object' AND octet_length(arguments::text)<=8192
        AND arguments ?& ARRAY['title','description','priority']
        AND arguments - ARRAY['title','description','priority'] = '{}'::jsonb
        AND jsonb_typeof(arguments->'title')='string' AND char_length(arguments->>'title') BETWEEN 1 AND 120
        AND arguments->>'title' !~ '[[:cntrl:]]' AND arguments->>'title' !~ U&'[\2028\2029\202A-\202E\2066-\2069]'
        AND jsonb_typeof(arguments->'description')='string' AND char_length(arguments->>'description') BETWEEN 1 AND 1000
        AND jsonb_typeof(arguments->'priority')='string' AND arguments->>'priority' IN ('LOW','NORMAL','HIGH')),
    CHECK (started_at IS NULL OR started_at>=created_at),
    CHECK (completed_at IS NULL OR completed_at>=started_at),
    CHECK (
      (status IN ('PENDING_APPROVAL','APPROVED','REJECTED') AND executed_by IS NULL AND execution_correlation_id IS NULL AND started_at IS NULL AND completed_at IS NULL AND task_id IS NULL AND failure_code IS NULL)
      OR (status='EXECUTING' AND executed_by IS NOT NULL AND execution_correlation_id IS NOT NULL AND started_at IS NOT NULL AND completed_at IS NULL AND task_id IS NULL AND failure_code IS NULL)
      OR (status='SUCCEEDED' AND executed_by IS NOT NULL AND execution_correlation_id IS NOT NULL AND started_at IS NOT NULL AND completed_at IS NOT NULL AND task_id IS NOT NULL AND failure_code IS NULL)
      OR (status='FAILED' AND executed_by IS NOT NULL AND execution_correlation_id IS NOT NULL AND started_at IS NOT NULL AND completed_at IS NOT NULL AND task_id IS NULL AND failure_code IS NOT NULL AND failure_code='TOOL_EXECUTION_FAILED')
    )
);
CREATE INDEX tool_proposals_tenant_created_idx ON platform.tool_proposals(tenant_id,created_at DESC,id DESC);
CREATE INDEX tool_proposals_creator_created_idx ON platform.tool_proposals(tenant_id,created_by,created_at DESC,id DESC);

CREATE TABLE platform.tool_approval_decisions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    proposal_id uuid NOT NULL,
    proposal_created_by uuid NOT NULL,
    approver_id uuid NOT NULL REFERENCES platform.users(id),
    payload_digest varchar(64) NOT NULL,
    decision varchar(16) NOT NULL CHECK (decision IN ('APPROVED','REJECTED')),
    reason text,
    decided_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    correlation_id uuid NOT NULL,
    UNIQUE (tenant_id,proposal_id),
    FOREIGN KEY (tenant_id,proposal_id,payload_digest,proposal_created_by)
        REFERENCES platform.tool_proposals(tenant_id,id,payload_digest,created_by),
    CHECK (decision='REJECTED' OR approver_id<>proposal_created_by),
    CHECK ((decision='APPROVED' AND reason IS NULL) OR (decision='REJECTED' AND char_length(reason) BETWEEN 1 AND 500 AND reason IS NOT NULL))
);
CREATE TABLE platform.admissions_followup_tasks (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    source_proposal_id uuid NOT NULL,
    created_by uuid NOT NULL REFERENCES platform.users(id),
    title varchar(120) NOT NULL CHECK (char_length(title)>=1),
    description varchar(1000) NOT NULL CHECK (char_length(description)>=1),
    priority varchar(8) NOT NULL CHECK (priority IN ('LOW','NORMAL','HIGH')),
    status varchar(8) NOT NULL DEFAULT 'OPEN' CHECK (status='OPEN'),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id,source_proposal_id),
    UNIQUE (tenant_id,id,source_proposal_id),
    FOREIGN KEY (tenant_id,source_proposal_id) REFERENCES platform.tool_proposals(tenant_id,id)
);
CREATE INDEX followup_tasks_tenant_created_idx ON platform.admissions_followup_tasks(tenant_id,created_at DESC,id DESC);
ALTER TABLE platform.tool_proposals ADD FOREIGN KEY (tenant_id,task_id,id)
    REFERENCES platform.admissions_followup_tasks(tenant_id,id,source_proposal_id);

ALTER TABLE platform.tool_proposals ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.tool_proposals FORCE ROW LEVEL SECURITY;
ALTER TABLE platform.tool_approval_decisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.tool_approval_decisions FORCE ROW LEVEL SECURITY;
ALTER TABLE platform.admissions_followup_tasks ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.admissions_followup_tasks FORCE ROW LEVEL SECURITY;
CREATE POLICY proposals_read ON platform.tool_proposals FOR SELECT TO eaap_runtime USING (tenant_id=platform.current_tenant_id());
CREATE POLICY proposals_insert ON platform.tool_proposals FOR INSERT TO eaap_runtime WITH CHECK (tenant_id=platform.current_tenant_id() AND status='PENDING_APPROVAL');
CREATE POLICY proposals_update ON platform.tool_proposals FOR UPDATE TO eaap_runtime
    USING (tenant_id=platform.current_tenant_id() AND status IN ('PENDING_APPROVAL','APPROVED','EXECUTING'))
    WITH CHECK (tenant_id=platform.current_tenant_id());
CREATE POLICY decisions_read ON platform.tool_approval_decisions FOR SELECT TO eaap_runtime USING (tenant_id=platform.current_tenant_id());
CREATE POLICY decisions_insert ON platform.tool_approval_decisions FOR INSERT TO eaap_runtime WITH CHECK (tenant_id=platform.current_tenant_id());
CREATE POLICY followup_read ON platform.admissions_followup_tasks FOR SELECT TO eaap_runtime USING (tenant_id=platform.current_tenant_id());
CREATE POLICY followup_insert ON platform.admissions_followup_tasks FOR INSERT TO eaap_runtime WITH CHECK (tenant_id=platform.current_tenant_id());
GRANT SELECT,INSERT ON platform.tool_proposals,platform.tool_approval_decisions,platform.admissions_followup_tasks TO eaap_runtime;
GRANT UPDATE (status,executed_by,execution_correlation_id,started_at,completed_at,task_id,failure_code) ON platform.tool_proposals TO eaap_runtime;

-- Validation triggers are SECURITY INVOKER with fixed empty search_path. No new privileged dispatcher.
CREATE FUNCTION platform.check_tool_decision() RETURNS trigger LANGUAGE plpgsql SET search_path='' AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM platform.tool_proposals p WHERE p.tenant_id=NEW.tenant_id AND p.id=NEW.proposal_id AND p.status='PENDING_APPROVAL')
       OR platform.membership_role(NEW.approver_id,NEW.tenant_id) NOT IN ('OWNER','ADMIN')
       OR platform.membership_role(NEW.approver_id,NEW.tenant_id) IS NULL THEN
        RAISE EXCEPTION 'Invalid tool decision' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER validate_tool_decision BEFORE INSERT ON platform.tool_approval_decisions FOR EACH ROW EXECUTE FUNCTION platform.check_tool_decision();

CREATE FUNCTION platform.check_tool_transition() RETURNS trigger LANGUAGE plpgsql SET search_path='' AS $$
BEGIN
    IF ROW(NEW.id,NEW.tenant_id,NEW.agent_id,NEW.created_by,NEW.tool_name,NEW.tool_version,NEW.arguments,NEW.rationale,NEW.payload_digest,NEW.created_at,NEW.correlation_id)
       IS DISTINCT FROM ROW(OLD.id,OLD.tenant_id,OLD.agent_id,OLD.created_by,OLD.tool_name,OLD.tool_version,OLD.arguments,OLD.rationale,OLD.payload_digest,OLD.created_at,OLD.correlation_id) THEN
        RAISE EXCEPTION 'Immutable tool proposal' USING ERRCODE='23514';
    END IF;
    IF NOT ((OLD.status='PENDING_APPROVAL' AND NEW.status IN ('APPROVED','REJECTED'))
        OR (OLD.status='APPROVED' AND NEW.status='EXECUTING')
        OR (OLD.status='EXECUTING' AND NEW.status IN ('SUCCEEDED','FAILED'))) THEN
        RAISE EXCEPTION 'Invalid tool transition' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM platform.tool_approval_decisions a WHERE a.tenant_id=NEW.tenant_id AND a.proposal_id=NEW.id
        AND a.payload_digest=NEW.payload_digest AND a.proposal_created_by=NEW.created_by
        AND a.decision=CASE WHEN NEW.status='REJECTED' THEN 'REJECTED' ELSE 'APPROVED' END) THEN
        RAISE EXCEPTION 'Tool decision required' USING ERRCODE='23514';
    END IF;
    IF OLD.status='EXECUTING' AND ROW(NEW.executed_by,NEW.execution_correlation_id,NEW.started_at) IS DISTINCT FROM ROW(OLD.executed_by,OLD.execution_correlation_id,OLD.started_at) THEN
        RAISE EXCEPTION 'Immutable execution identity' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER validate_tool_transition BEFORE UPDATE ON platform.tool_proposals FOR EACH ROW EXECUTE FUNCTION platform.check_tool_transition();

CREATE FUNCTION platform.check_followup_effect() RETURNS trigger LANGUAGE plpgsql SET search_path='' AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM platform.tool_proposals p JOIN platform.tool_approval_decisions a ON a.tenant_id=p.tenant_id AND a.proposal_id=p.id
        WHERE p.tenant_id=NEW.tenant_id AND p.id=NEW.source_proposal_id AND p.status='EXECUTING'
        AND p.executed_by=NEW.created_by AND a.decision='APPROVED' AND a.approver_id<>p.created_by AND a.payload_digest=p.payload_digest
        AND NEW.title=p.arguments->>'title' AND NEW.description=p.arguments->>'description' AND NEW.priority=p.arguments->>'priority') THEN
        RAISE EXCEPTION 'Approved tool payload required' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER validate_followup_effect BEFORE INSERT ON platform.admissions_followup_tasks FOR EACH ROW EXECUTE FUNCTION platform.check_followup_effect();

-- EXECUTING is transient in the atomic internal SQL tool transaction. A crash rolls back to APPROVED.
CREATE FUNCTION platform.check_tool_commit() RETURNS trigger LANGUAGE plpgsql SET search_path='' AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM platform.tool_proposals WHERE tenant_id=NEW.tenant_id AND id=NEW.id AND status='EXECUTING') THEN
        RAISE EXCEPTION 'Incomplete internal tool transaction' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER validate_tool_commit AFTER UPDATE ON platform.tool_proposals
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.check_tool_commit();
