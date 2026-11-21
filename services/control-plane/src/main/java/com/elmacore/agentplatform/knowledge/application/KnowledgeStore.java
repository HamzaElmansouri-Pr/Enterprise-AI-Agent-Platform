package com.elmacore.agentplatform.knowledge.application;

import com.elmacore.agentplatform.knowledge.domain.*;
import java.util.List;
import java.util.UUID;

public interface KnowledgeStore {
    record Match(UUID documentId,String filename,UUID chunkId,String text,double score,Integer sourcePage) {
        @Override public String toString() { return "KnowledgeMatch[redacted]"; }
    }
    void insert(UUID tenant,UUID id,Upload source);
    KnowledgeDocument get(UUID tenant,UUID id);
    List<KnowledgeDocument> list(UUID tenant,int size,long offset);
    void draftAgent(UUID tenant,UUID agent);
    boolean associated(UUID tenant,UUID document,UUID agent);
    void associate(UUID tenant,UUID document,UUID agent);
    void remove(UUID tenant,UUID document,UUID agent);
    boolean retry(UUID tenant,UUID document);
    byte[] source(UUID tenant,UUID document);
    void ready(UUID tenant,UUID document,List<TextChunking.Chunk> chunks,List<EmbeddingVector> vectors);
    void failed(UUID tenant,UUID document,String code);
    List<Match> search(UUID tenant,UUID agent,EmbeddingVector query,int topK);
}
