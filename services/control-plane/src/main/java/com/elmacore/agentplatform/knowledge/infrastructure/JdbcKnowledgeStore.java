package com.elmacore.agentplatform.knowledge.infrastructure;

import com.elmacore.agentplatform.knowledge.application.KnowledgeStore;
import com.elmacore.agentplatform.knowledge.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

public final class JdbcKnowledgeStore implements KnowledgeStore {
    private final DataSource source; private final JdbcTemplate jdbc;
    private static final String METADATA="""
        SELECT d.id,d.tenant_id,d.filename,d.content_type,d.byte_size,d.sha256,d.status,
        d.embedding_model,d.chunk_count,d.failure_code,d.created_at,d.updated_at,
        ARRAY(SELECT a.agent_id FROM platform.agent_documents a WHERE a.tenant_id=d.tenant_id AND a.document_id=d.id ORDER BY a.agent_id LIMIT 101) AS agents
        FROM platform.knowledge_documents d WHERE d.tenant_id=?
        """;
    private static final RowMapper<KnowledgeDocument> MAP=(r,n)-> {
        var agents=Arrays.asList((UUID[])r.getArray("agents").getArray());
        return new KnowledgeDocument(
            r.getObject("id",UUID.class),r.getObject("tenant_id",UUID.class),r.getString("filename"),r.getString("content_type"),
            r.getInt("byte_size"),r.getString("sha256"),DocumentStatus.valueOf(r.getString("status")),r.getString("embedding_model"),r.getInt("chunk_count"),
            r.getString("failure_code"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),
            agents.subList(0,Math.min(100,agents.size())),agents.size()>100);
    };
    public JdbcKnowledgeStore(DataSource source) { this.source=source; jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(3); }
    private void transaction() {
        if(!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.hasResource(source)) throw new IllegalStateException("Knowledge requires tenant transaction");
    }
    @Override public void insert(UUID tenant,UUID id,Upload upload) {
        transaction();
        try {
            jdbc.update("INSERT INTO platform.knowledge_documents(id,tenant_id,filename,content_type,byte_size,sha256,embedding_model) VALUES(?,?,?,?,?,?,?)",id,tenant,upload.filename(),upload.contentType(),upload.size(),upload.sha256(),EmbeddingVector.MODEL);
            jdbc.update("INSERT INTO platform.knowledge_content(tenant_id,document_id,source) VALUES(?,?,?)",tenant,id,upload.bytes());
        } catch(DataAccessException failure) {
            for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
                if(cause instanceof SQLException sql && "P0020".equals(sql.getSQLState())) throw new KnowledgeFailure("document_limit_reached",409);
            }
            throw failure;
        }
    }
    @Override public KnowledgeDocument get(UUID tenant,UUID id) {
        transaction();return jdbc.query(METADATA+" AND d.id=?",MAP,tenant,id).stream().findFirst().orElseThrow(()->new KnowledgeFailure("document_not_found",404));
    }
    @Override public List<KnowledgeDocument> list(UUID tenant,int size,long offset) {
        transaction();return jdbc.query(METADATA+" ORDER BY d.created_at DESC,d.id DESC LIMIT ? OFFSET ?",MAP,tenant,size,offset);
    }
    @Override public void draftAgent(UUID tenant,UUID agent) {
        transaction();
        var states=jdbc.queryForList("SELECT status FROM platform.agent_configurations WHERE tenant_id=? AND id=?",String.class,tenant,agent);
        if(states.isEmpty()) throw new KnowledgeFailure("agent_not_found",404);
        if(!states.getFirst().equals("DRAFT")) throw new KnowledgeFailure("agent_archived",409);
        // Share-lock the lifecycle while establishing an association or executing retrieval.
        if(jdbc.queryForList("SELECT id FROM platform.agent_configurations WHERE tenant_id=? AND id=? AND status='DRAFT' FOR SHARE",UUID.class,tenant,agent).isEmpty()) throw new KnowledgeFailure("agent_archived",409);
    }
    @Override public boolean associated(UUID tenant,UUID document,UUID agent) {
        transaction();return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.agent_documents WHERE tenant_id=? AND document_id=? AND agent_id=?)",Boolean.class,tenant,document,agent));
    }
    @Override public void associate(UUID tenant,UUID document,UUID agent) {
        transaction();jdbc.update("INSERT INTO platform.agent_documents(tenant_id,document_id,agent_id) VALUES(?,?,?) ON CONFLICT DO NOTHING",tenant,document,agent);
    }
    @Override public void remove(UUID tenant,UUID document,UUID agent) {
        transaction();jdbc.update("DELETE FROM platform.agent_documents WHERE tenant_id=? AND document_id=? AND agent_id=?",tenant,document,agent);
    }
    @Override public boolean retry(UUID tenant,UUID document) {
        transaction();return jdbc.update("UPDATE platform.knowledge_documents SET status='PENDING',failure_code=NULL,updated_at=clock_timestamp() WHERE tenant_id=? AND id=? AND status='FAILED'",tenant,document)==1;
    }
    @Override public byte[] source(UUID tenant,UUID document) {
        transaction();return jdbc.queryForObject("SELECT source FROM platform.knowledge_content WHERE tenant_id=? AND document_id=?",byte[].class,tenant,document);
    }
    @Override public void ready(UUID tenant,UUID document,List<TextChunking.Chunk> chunks,List<EmbeddingVector> vectors) {
        transaction();
        jdbc.batchUpdate("INSERT INTO platform.knowledge_chunks(id,tenant_id,document_id,ordinal,text,source_page,embedding,embedding_model) VALUES(?,?,?,?,?,?,?::extensions.vector,?)",new BatchPreparedStatementSetter() {
            public int getBatchSize(){return chunks.size();}
            public void setValues(PreparedStatement p,int i)throws SQLException {
                var chunk=chunks.get(i);p.setObject(1,UUID.randomUUID());p.setObject(2,tenant);p.setObject(3,document);p.setInt(4,chunk.ordinal());p.setString(5,chunk.text());p.setObject(6,chunk.page());p.setString(7,vectors.get(i).sql());p.setString(8,EmbeddingVector.MODEL);
            }
        });
        int changed=jdbc.update("UPDATE platform.knowledge_documents SET status='READY',chunk_count=?,claim_token=NULL,lease_until=NULL,failure_code=NULL,updated_at=clock_timestamp() WHERE tenant_id=? AND id=? AND status='PROCESSING'",chunks.size(),tenant,document);
        if(changed!=1) throw new KnowledgeFailure("job_lease_lost",409);
    }
    @Override public void failed(UUID tenant,UUID document,String code) {
        transaction();int changed=jdbc.update("UPDATE platform.knowledge_documents SET status='FAILED',chunk_count=0,claim_token=NULL,lease_until=NULL,failure_code=?,updated_at=clock_timestamp() WHERE tenant_id=? AND id=? AND status='PROCESSING'",code,tenant,document);
        com.elmacore.agentplatform.operations.OperationalSignals.committed(
            com.elmacore.agentplatform.operations.OperationalSignals.Event.KNOWLEDGE_FAILED,changed);
    }
    @Override public List<Match> search(UUID tenant,UUID agent,EmbeddingVector vector,int topK) {
        transaction();return jdbc.query("""
            SELECT d.id,d.filename,c.id AS chunk_id,c.text,c.source_page,
                1-(c.embedding OPERATOR(extensions.<=>) ?::extensions.vector) AS score
            FROM platform.knowledge_chunks c
            JOIN platform.knowledge_documents d ON d.tenant_id=c.tenant_id AND d.id=c.document_id
            JOIN platform.agent_documents a ON a.tenant_id=d.tenant_id AND a.document_id=d.id
            WHERE c.tenant_id=? AND a.agent_id=? AND d.status='READY'
                AND c.embedding_model=? AND d.embedding_model=?
            ORDER BY score DESC,d.id,c.ordinal,c.id LIMIT ?
            """,(r,n)->new Match(r.getObject("id",UUID.class),r.getString("filename"),r.getObject("chunk_id",UUID.class),r.getString("text"),Math.clamp(r.getDouble("score"),-1,1),r.getObject("source_page",Integer.class)),vector.sql(),tenant,agent,EmbeddingVector.MODEL,EmbeddingVector.MODEL,topK);
    }
}
