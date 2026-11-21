package com.elmacore.agentplatform.knowledge.application;

import com.elmacore.agentplatform.knowledge.domain.*;
import java.util.ArrayList;

public final class KnowledgeProcessor {
    private final KnowledgeJobs jobs; private final KnowledgeStore store;
    private final DocumentExtraction extraction; private final Embeddings embeddings;
    public KnowledgeProcessor(KnowledgeJobs jobs,KnowledgeStore store,DocumentExtraction extraction,Embeddings embeddings) {
        this.jobs=jobs;this.store=store;this.extraction=extraction;this.embeddings=embeddings;
    }
    public boolean processNext() {
        var next=jobs.claim(); if(next.isEmpty()) return false;
        var job=next.get();
        try {
            var document=jobs.execute(job,()->store.get(job.tenantId(),job.documentId()));
            var source=jobs.execute(job,()->store.source(job.tenantId(),job.documentId()));
            var chunks=new TextChunking().split(extraction.extract(document.contentType(),source));
            var vectors=new ArrayList<EmbeddingVector>();
            for(int start=0;start<chunks.size();start+=8) {
                jobs.execute(job,()->null); // Renew the fenced lease before each bounded inference batch.
                vectors.addAll(embeddings.embed("passage",chunks.subList(start,Math.min(start+8,chunks.size())).stream().map(TextChunking.Chunk::text).toList()));
            }
            if(vectors.size()!=chunks.size()) throw new KnowledgeFailure("EMBEDDING_UNAVAILABLE",503);
            jobs.execute(job,()-> {store.ready(job.tenantId(),job.documentId(),chunks,vectors);return null;});
        } catch(KnowledgeFailure failure) {
            if(Thread.currentThread().isInterrupted()) return true; // Preserve the durable lease for restart recovery.
            if(!failure.code().equals("job_lease_lost")) jobs.execute(job,()-> {store.failed(job.tenantId(),job.documentId(),safeCode(failure.code()));return null;});
        } catch(RuntimeException failure) {
            if(Thread.currentThread().isInterrupted()) return true;
            jobs.execute(job,()-> {store.failed(job.tenantId(),job.documentId(),"PROCESSING_FAILED");return null;});
        }
        return true;
    }
    private String safeCode(String value) {
        return switch(value) {
            case "INVALID_PDF","ENCRYPTED_PDF","PAGE_LIMIT","TEXT_LIMIT","NO_EXTRACTABLE_TEXT","CHUNK_LIMIT","EMBEDDING_UNAVAILABLE" -> value;
            default -> "PROCESSING_FAILED";
        };
    }
}
