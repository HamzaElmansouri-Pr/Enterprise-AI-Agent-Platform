-- Requires the explicit administrator-owned pgvector provisioning operation first.
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension e JOIN pg_namespace n ON n.oid=e.extnamespace
                   WHERE e.extname='vector' AND n.nspname='extensions') THEN
        RAISE EXCEPTION 'Provision vector in extensions schema before V4';
    END IF;
END $$;
ALTER TABLE platform.agent_configurations ADD CONSTRAINT agents_tenant_identity UNIQUE (tenant_id,id);
CREATE TABLE platform.knowledge_documents (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
    filename varchar(160) NOT NULL CHECK (length(btrim(filename)) > 0 AND filename !~ '[[:cntrl:]/\\]'),
    content_type varchar(32) NOT NULL CHECK (content_type IN ('text/plain','application/pdf')),
    byte_size integer NOT NULL CHECK (byte_size BETWEEN 1 AND 2097152),
    sha256 varchar(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    status varchar(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PROCESSING','READY','FAILED')),
    embedding_model varchar(100) NOT NULL CHECK (embedding_model='intfloat/multilingual-e5-small@614241f622f53c4eeff9890bdc4f31cfecc418b3'),
    chunk_count integer NOT NULL DEFAULT 0 CHECK (chunk_count BETWEEN 0 AND 256),
    failure_code varchar(40) CHECK (failure_code IN ('INVALID_PDF','ENCRYPTED_PDF','PAGE_LIMIT','TEXT_LIMIT','NO_EXTRACTABLE_TEXT','CHUNK_LIMIT','EMBEDDING_UNAVAILABLE','PROCESSING_FAILED')),
    claim_token uuid,
    lease_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP CHECK(updated_at>=created_at),
    UNIQUE (tenant_id,id),
    CHECK ((status='READY' AND chunk_count>0 AND failure_code IS NULL) OR (status<>'READY' AND chunk_count=0)),
    CHECK ((status='FAILED') = (failure_code IS NOT NULL)),
    CHECK ((status='PROCESSING' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status<>'PROCESSING' AND claim_token IS NULL AND lease_until IS NULL))
);
CREATE INDEX knowledge_documents_listing ON platform.knowledge_documents(tenant_id,created_at DESC,id DESC);
CREATE INDEX knowledge_documents_pending ON platform.knowledge_documents(created_at,id) WHERE status='PENDING' OR status='PROCESSING';
CREATE TABLE platform.knowledge_content (
    tenant_id uuid NOT NULL,
    document_id uuid NOT NULL,
    source bytea NOT NULL CHECK(octet_length(source) BETWEEN 1 AND 2097152),
    PRIMARY KEY(tenant_id,document_id),
    FOREIGN KEY(tenant_id,document_id) REFERENCES platform.knowledge_documents(tenant_id,id)
);
CREATE TABLE platform.agent_documents (
    tenant_id uuid NOT NULL,
    document_id uuid NOT NULL,
    agent_id uuid NOT NULL,
    PRIMARY KEY(tenant_id,document_id,agent_id),
    FOREIGN KEY(tenant_id,document_id) REFERENCES platform.knowledge_documents(tenant_id,id),
    FOREIGN KEY(tenant_id,agent_id) REFERENCES platform.agent_configurations(tenant_id,id)
);
CREATE INDEX agent_documents_agent ON platform.agent_documents(tenant_id,agent_id,document_id);
CREATE TABLE platform.knowledge_chunks (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    document_id uuid NOT NULL,
    ordinal integer NOT NULL CHECK(ordinal BETWEEN 0 AND 255),
    text varchar(1200) NOT NULL CHECK(length(btrim(text))>0),
    source_page integer CHECK(source_page BETWEEN 1 AND 25),
    embedding extensions.vector(384) NOT NULL CHECK(extensions.vector_norm(embedding) BETWEEN sqrt(0.99) AND sqrt(1.01)),
    embedding_model varchar(100) NOT NULL CHECK(embedding_model='intfloat/multilingual-e5-small@614241f622f53c4eeff9890bdc4f31cfecc418b3'),
    UNIQUE(tenant_id,document_id,ordinal),
    FOREIGN KEY(tenant_id,document_id) REFERENCES platform.knowledge_documents(tenant_id,id)
);
-- Enforce the quota even for competing transactions. Hash collision only serializes extra tenants.
CREATE FUNCTION platform.limit_knowledge_documents() RETURNS trigger LANGUAGE plpgsql SET search_path='' AS $$
BEGIN
    IF NEW.tenant_id IS DISTINCT FROM platform.current_tenant_id() THEN RAISE EXCEPTION 'tenant context required' USING ERRCODE='42501'; END IF;
    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(NEW.tenant_id::text, 5));
    IF (SELECT count(*) FROM platform.knowledge_documents WHERE tenant_id=NEW.tenant_id)>=20 THEN
        RAISE EXCEPTION 'document quota reached' USING ERRCODE='P0020';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER knowledge_document_quota BEFORE INSERT ON platform.knowledge_documents FOR EACH ROW EXECUTE FUNCTION platform.limit_knowledge_documents();

ALTER TABLE platform.knowledge_documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.knowledge_documents FORCE ROW LEVEL SECURITY;
ALTER TABLE platform.knowledge_content ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.knowledge_content FORCE ROW LEVEL SECURITY;
ALTER TABLE platform.agent_documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.agent_documents FORCE ROW LEVEL SECURITY;
ALTER TABLE platform.knowledge_chunks ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.knowledge_chunks FORCE ROW LEVEL SECURITY;
CREATE POLICY documents_read ON platform.knowledge_documents FOR SELECT TO eaap_runtime USING(tenant_id=platform.current_tenant_id());
CREATE POLICY documents_insert ON platform.knowledge_documents FOR INSERT TO eaap_runtime WITH CHECK(tenant_id=platform.current_tenant_id() AND status='PENDING' AND claim_token IS NULL AND chunk_count=0);
CREATE POLICY documents_update ON platform.knowledge_documents FOR UPDATE TO eaap_runtime USING(tenant_id=platform.current_tenant_id()) WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY content_read ON platform.knowledge_content FOR SELECT TO eaap_runtime USING(tenant_id=platform.current_tenant_id());
CREATE POLICY content_insert ON platform.knowledge_content FOR INSERT TO eaap_runtime WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY associations_read ON platform.agent_documents FOR SELECT TO eaap_runtime USING(tenant_id=platform.current_tenant_id());
CREATE POLICY associations_insert ON platform.agent_documents FOR INSERT TO eaap_runtime WITH CHECK(tenant_id=platform.current_tenant_id() AND EXISTS(SELECT 1 FROM platform.agent_configurations a WHERE a.tenant_id=agent_documents.tenant_id AND a.id=agent_id AND a.status='DRAFT'));
CREATE POLICY associations_remove ON platform.agent_documents FOR DELETE TO eaap_runtime USING(tenant_id=platform.current_tenant_id());
CREATE POLICY chunks_read ON platform.knowledge_chunks FOR SELECT TO eaap_runtime USING(tenant_id=platform.current_tenant_id());
CREATE POLICY chunks_insert ON platform.knowledge_chunks FOR INSERT TO eaap_runtime WITH CHECK(tenant_id=platform.current_tenant_id() AND EXISTS(SELECT 1 FROM platform.knowledge_documents d WHERE d.tenant_id=knowledge_chunks.tenant_id AND d.id=document_id AND d.status='PROCESSING'));
GRANT SELECT,INSERT ON platform.knowledge_documents,platform.knowledge_content,platform.agent_documents,platform.knowledge_chunks TO eaap_runtime;
GRANT UPDATE(status,chunk_count,failure_code,updated_at,claim_token,lease_until) ON platform.knowledge_documents TO eaap_runtime;
GRANT DELETE ON platform.agent_documents TO eaap_runtime;
-- Only the migration-owned fixed dispatcher functions use these policies. Runtime cannot assume
-- that role. Policies expose pending/leased metadata only, never source/chunk/vector tables.
CREATE POLICY dispatcher_read ON platform.knowledge_documents FOR SELECT TO eaap_migration USING(status IN ('PENDING','PROCESSING'));
CREATE POLICY dispatcher_update ON platform.knowledge_documents FOR UPDATE TO eaap_migration USING(status IN ('PENDING','PROCESSING')) WITH CHECK(status='PROCESSING');
CREATE FUNCTION platform.claim_knowledge_job() RETURNS TABLE(tenant_id uuid,document_id uuid,token uuid)
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path='' AS $$
    UPDATE platform.knowledge_documents d SET status='PROCESSING',claim_token=gen_random_uuid(),
        lease_until=clock_timestamp()+interval '2 minutes',updated_at=clock_timestamp()
    WHERE d.id=(SELECT k.id FROM platform.knowledge_documents k
        WHERE k.status='PENDING' OR (k.status='PROCESSING' AND k.lease_until<clock_timestamp())
        ORDER BY k.created_at,k.id FOR UPDATE SKIP LOCKED LIMIT 1)
    RETURNING d.tenant_id,d.id,d.claim_token
$$;
CREATE FUNCTION platform.open_knowledge_job(t uuid,d uuid,c uuid) RETURNS boolean
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path='' AS $$
    WITH renewed AS (UPDATE platform.knowledge_documents SET lease_until=clock_timestamp()+interval '2 minutes'
        WHERE tenant_id=t AND id=d AND status='PROCESSING' AND claim_token=c AND lease_until>clock_timestamp()
        RETURNING id) SELECT EXISTS(SELECT 1 FROM renewed)
$$;
REVOKE ALL ON FUNCTION platform.claim_knowledge_job(),platform.open_knowledge_job(uuid,uuid,uuid),platform.limit_knowledge_documents() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.claim_knowledge_job(),platform.open_knowledge_job(uuid,uuid,uuid) TO eaap_runtime;
