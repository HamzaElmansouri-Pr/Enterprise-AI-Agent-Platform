-- Reuse immutable run identity and existing fenced run lifecycle for each logical turn.
ALTER TABLE platform.agent_runs ADD CONSTRAINT runs_conversation_identity UNIQUE(tenant_id,agent_id,user_id,id);
CREATE TABLE platform.conversations (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL,
 agent_id uuid NOT NULL,
 created_by uuid NOT NULL REFERENCES platform.users(id),
 title varchar(120) NOT NULL CHECK(char_length(btrim(title)) BETWEEN 1 AND 120),
 status varchar(6) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','CLOSED')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp() CHECK(updated_at>=created_at),
 UNIQUE(tenant_id,agent_id,created_by,id),
 FOREIGN KEY(tenant_id,agent_id) REFERENCES platform.agent_configurations(tenant_id,id)
);
CREATE INDEX conversations_owner_page ON platform.conversations(tenant_id,created_by,agent_id,created_at DESC,id DESC);
CREATE TABLE platform.conversation_turns (
 tenant_id uuid NOT NULL,
 agent_id uuid NOT NULL,
 created_by uuid NOT NULL,
 conversation_id uuid NOT NULL,
 request_id uuid NOT NULL,
 ordinal integer NOT NULL CHECK(ordinal BETWEEN 1 AND 100),
 run_id uuid NOT NULL UNIQUE,
 user_message_id uuid NOT NULL UNIQUE,
 assistant_message_id uuid NOT NULL UNIQUE,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,conversation_id,request_id),
 UNIQUE(tenant_id,conversation_id,ordinal),
 CHECK(user_message_id<>assistant_message_id),
 FOREIGN KEY(tenant_id,agent_id,created_by,conversation_id) REFERENCES platform.conversations(tenant_id,agent_id,created_by,id),
 FOREIGN KEY(tenant_id,agent_id,created_by,run_id) REFERENCES platform.agent_runs(tenant_id,agent_id,user_id,id)
);
-- Pair projection: two stable message IDs/ordinals, content/status/citations in the linked run.
-- No copied evidence, provider payload, or independently editable message lifecycle.
ALTER TABLE platform.conversations ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.conversations FORCE ROW LEVEL SECURITY;
ALTER TABLE platform.conversation_turns ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.conversation_turns FORCE ROW LEVEL SECURITY;
CREATE POLICY conversations_read ON platform.conversations FOR SELECT TO eaap_runtime
 USING(tenant_id=platform.current_tenant_id() AND created_by::text=current_setting('app.user_id',true));
CREATE POLICY conversations_insert ON platform.conversations FOR INSERT TO eaap_runtime
 WITH CHECK(tenant_id=platform.current_tenant_id() AND created_by::text=current_setting('app.user_id',true) AND status='OPEN');
CREATE POLICY conversations_update ON platform.conversations FOR UPDATE TO eaap_runtime
 USING(tenant_id=platform.current_tenant_id() AND created_by::text=current_setting('app.user_id',true) AND status='OPEN')
 WITH CHECK(tenant_id=platform.current_tenant_id() AND created_by::text=current_setting('app.user_id',true));
CREATE POLICY turns_read ON platform.conversation_turns FOR SELECT TO eaap_runtime
 USING(tenant_id=platform.current_tenant_id() AND created_by::text=current_setting('app.user_id',true));
CREATE POLICY turns_insert ON platform.conversation_turns FOR INSERT TO eaap_runtime
 WITH CHECK(tenant_id=platform.current_tenant_id() AND created_by::text=current_setting('app.user_id',true));
GRANT SELECT,INSERT ON platform.conversations,platform.conversation_turns TO eaap_runtime;
GRANT UPDATE(title,status,updated_at) ON platform.conversations TO eaap_runtime;

CREATE FUNCTION platform.conversation_create_guard() RETURNS trigger LANGUAGE plpgsql SET search_path='' AS $$
BEGIN
 PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('conversation:'||NEW.tenant_id::text||':'||NEW.created_by::text,0));
 IF (SELECT count(*) FROM platform.conversations c WHERE c.tenant_id=NEW.tenant_id AND c.created_by=NEW.created_by)>=20 THEN RAISE EXCEPTION 'conversation_limit' USING ERRCODE='P0001'; END IF;
 IF NOT EXISTS(SELECT 1 FROM platform.agent_configurations a WHERE a.tenant_id=NEW.tenant_id AND a.id=NEW.agent_id AND a.status='DRAFT' FOR SHARE) THEN RAISE EXCEPTION 'conversation_read_only' USING ERRCODE='P0001'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER conversation_create_guard BEFORE INSERT ON platform.conversations FOR EACH ROW EXECUTE FUNCTION platform.conversation_create_guard();
CREATE FUNCTION platform.conversation_turn_guard() RETURNS trigger LANGUAGE plpgsql SET search_path='' AS $$
DECLARE parent platform.conversations%ROWTYPE; next_position integer;
BEGIN
 SELECT * INTO parent FROM platform.conversations c WHERE c.tenant_id=NEW.tenant_id AND c.id=NEW.conversation_id FOR UPDATE;
 IF parent.id IS NULL OR parent.status<>'OPEN' THEN RAISE EXCEPTION 'conversation_read_only' USING ERRCODE='P0001'; END IF;
 IF NOT EXISTS(SELECT 1 FROM platform.agent_configurations a WHERE a.tenant_id=NEW.tenant_id AND a.id=NEW.agent_id AND a.status='DRAFT' FOR SHARE) THEN RAISE EXCEPTION 'conversation_read_only' USING ERRCODE='P0001'; END IF;
 IF EXISTS(SELECT 1 FROM platform.conversation_turns t JOIN platform.agent_runs r ON r.id=t.run_id WHERE t.tenant_id=NEW.tenant_id AND t.conversation_id=NEW.conversation_id AND r.status='RUNNING') THEN RAISE EXCEPTION 'conversation_busy' USING ERRCODE='P0001'; END IF;
 SELECT coalesce(max(t.ordinal),0)+1 INTO next_position FROM platform.conversation_turns t WHERE t.tenant_id=NEW.tenant_id AND t.conversation_id=NEW.conversation_id;
 IF next_position>100 THEN RAISE EXCEPTION 'conversation_turn_limit' USING ERRCODE='P0001'; END IF;
 IF NEW.ordinal<>next_position OR NOT EXISTS(SELECT 1 FROM platform.agent_runs r WHERE r.id=NEW.run_id AND r.status='RUNNING') THEN RAISE EXCEPTION 'conversation_invalid_turn' USING ERRCODE='P0001'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER conversation_turn_guard BEFORE INSERT ON platform.conversation_turns FOR EACH ROW EXECUTE FUNCTION platform.conversation_turn_guard();
REVOKE ALL ON FUNCTION platform.conversation_create_guard(),platform.conversation_turn_guard() FROM PUBLIC;
-- Trigger functions are invoker-only; no SECURITY DEFINER or extra identity authority.
