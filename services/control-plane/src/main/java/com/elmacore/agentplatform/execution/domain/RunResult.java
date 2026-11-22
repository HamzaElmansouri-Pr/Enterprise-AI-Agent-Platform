package com.elmacore.agentplatform.execution.domain;

import java.util.List;
import java.util.UUID;

public record RunResult(UUID runId,UUID agentId,String status,String answer,List<Citation> citations,
                        Usage usage,Long durationMs,String failureCode,String modelId) {
    public record Citation(UUID documentId,UUID chunkId,String filename,Integer sourcePage) {
        @Override public String toString(){return "Citation[redacted]";}
    }
    public record Usage(Integer inputTokens,Integer outputTokens) {}
    public RunResult {
        java.util.Objects.requireNonNull(runId);java.util.Objects.requireNonNull(agentId);java.util.Objects.requireNonNull(usage);
        citations=List.copyOf(citations);
        if(citations.size()>6 || (durationMs!=null && durationMs<0))throw new IllegalArgumentException("Invalid run result");
        switch(status) {
            case "RUNNING" -> {
                if(answer!=null || !citations.isEmpty() || failureCode!=null || durationMs!=null || modelId!=null
                    || usage.inputTokens()!=null || usage.outputTokens()!=null)throw new IllegalArgumentException("Invalid running result");
            }
            case "SUCCEEDED" -> {
                if(answer==null || answer.isBlank() || citations.isEmpty() || failureCode!=null || durationMs==null || modelId==null)
                    throw new IllegalArgumentException("Invalid successful result");
            }
            case "FAILED" -> {
                if(answer!=null || !citations.isEmpty() || failureCode==null || durationMs==null)throw new IllegalArgumentException("Invalid failed result");
            }
            default -> throw new IllegalArgumentException("Invalid run status");
        }
    }
    @Override public String toString(){return "RunResult[redacted]";}
}
