package com.elmacore.agentplatform.knowledge.infrastructure;

import com.elmacore.agentplatform.knowledge.application.KnowledgeJobs;
import com.elmacore.agentplatform.knowledge.domain.KnowledgeFailure;
import com.elmacore.agentplatform.infrastructure.persistence.PostgresTenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.util.*;
import java.util.function.Supplier;

public final class JdbcKnowledgeJobs implements KnowledgeJobs {
    private final JdbcTemplate jdbc; private final TransactionTemplate transactions;private final DataSource source;
    public JdbcKnowledgeJobs(DataSource source) {
        this.source=source;jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(3);
        transactions=new TransactionTemplate(new JdbcTransactionManager(source));transactions.setTimeout(5);
    }
    @Override public Optional<Lease> claim() {
        if(TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.hasResource(source)) throw new IllegalStateException("Job claim cannot be nested");
        return jdbc.query("SELECT * FROM platform.claim_knowledge_job()",(r,n)->new Lease(r.getObject("tenant_id",UUID.class),r.getObject("document_id",UUID.class),r.getObject("token",UUID.class))).stream().findFirst();
    }
    @Override public <T>T execute(Lease job,Supplier<T> work) {
        Objects.requireNonNull(job);Objects.requireNonNull(work);
        if(TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.hasResource(source)) throw new IllegalStateException("Job transactions cannot be nested");
        return transactions.execute(status->jdbc.execute((ConnectionCallback<T>)connection->{
            boolean valid=Boolean.TRUE.equals(jdbc.queryForObject("SELECT platform.open_knowledge_job(?,?,?)",Boolean.class,job.tenantId(),job.documentId(),job.token()));
            if(!valid) throw new KnowledgeFailure("job_lease_lost",409);
            PostgresTenantContext.apply(connection,job.tenantId());
            return work.get();
        }));
    }
}
