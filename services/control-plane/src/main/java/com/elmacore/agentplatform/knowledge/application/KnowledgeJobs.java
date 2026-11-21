package com.elmacore.agentplatform.knowledge.application;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public interface KnowledgeJobs {
    record Lease(UUID tenantId,UUID documentId,UUID token) {
        @Override public String toString() { return "KnowledgeJobLease[redacted]"; }
    }
    Optional<Lease> claim();
    <T> T execute(Lease lease,Supplier<T> work);
}
