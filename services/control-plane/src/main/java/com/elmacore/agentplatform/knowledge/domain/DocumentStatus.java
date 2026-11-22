package com.elmacore.agentplatform.knowledge.domain;

public enum DocumentStatus {
    PENDING, PROCESSING, READY, FAILED;

    public void requireRetryable() {
        if(this!=FAILED) throw new KnowledgeFailure("document_not_failed",409);
    }
}
