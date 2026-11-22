package com.elmacore.agentplatform.knowledge.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record KnowledgeDocument(UUID id, UUID tenantId, String filename, String contentType,
        int byteSize, String sha256, DocumentStatus status, String embeddingModel, int chunkCount,
        String failureCode, Instant createdAt, Instant updatedAt, List<UUID> agentIds, boolean hasMoreAgentIds) {
    public KnowledgeDocument {
        java.util.Objects.requireNonNull(id);java.util.Objects.requireNonNull(tenantId);
        java.util.Objects.requireNonNull(status);java.util.Objects.requireNonNull(createdAt);java.util.Objects.requireNonNull(updatedAt);
        if(!EmbeddingVector.MODEL.equals(embeddingModel) || chunkCount<0 || chunkCount>256 || updatedAt.isBefore(createdAt)
                || (status==DocumentStatus.READY)!=(chunkCount>0) || (status==DocumentStatus.FAILED)!=(failureCode!=null)) throw new IllegalArgumentException("Invalid document state");
        agentIds=List.copyOf(agentIds);
    }
    @Override public String toString() { return "KnowledgeDocument[redacted]"; }
}
