package com.elmacore.agentplatform.knowledge.infrastructure;

import com.elmacore.agentplatform.knowledge.application.KnowledgeProcessor;
import java.util.concurrent.*;

/** One polling/processing thread, zero in-memory backlog. PostgreSQL is the durable queue. */
public final class KnowledgeWorker implements AutoCloseable {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(KnowledgeWorker.class);
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"knowledge-worker");t.setDaemon(true);return t;});
    private long lastFailure;
    public KnowledgeWorker(KnowledgeProcessor processor) {
        executor.scheduleWithFixedDelay(()-> {
            long started=System.nanoTime();
            try(var correlation=org.slf4j.MDC.putCloseable("correlation_id",java.util.UUID.randomUUID().toString())) {
                if(processor.processNext()) LOG.info("knowledge_job_attempt_completed correlation_id={} duration_ms={}",
                    org.slf4j.MDC.get("correlation_id"),(System.nanoTime()-started)/1_000_000);
            }
            catch(RuntimeException error) {
                long now=System.nanoTime();if(now-lastFailure>TimeUnit.MINUTES.toNanos(1)) {LOG.error("knowledge_worker_failed");lastFailure=now;}
            }
        },3,3,TimeUnit.SECONDS);
    }
    @Override public void close() {executor.shutdownNow();}
}
