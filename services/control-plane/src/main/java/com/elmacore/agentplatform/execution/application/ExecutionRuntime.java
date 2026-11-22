package com.elmacore.agentplatform.execution.application;

import com.elmacore.agentplatform.execution.domain.RunResult.Usage;
import java.util.List;
import java.util.UUID;

public interface ExecutionRuntime {
    String MODEL="gpt-5.4-mini-2026-03-17";
    record Passage(String sourceId,String text) {
        @Override public String toString(){return "Passage[redacted]";}
    }
    record Request(UUID runId,UUID correlationId,String instructions,String message,List<Passage> passages) {
        @Override public String toString(){return "ExecutionRequest[redacted]";}
    }
    record Response(String answer,List<String> sourceIds,String modelId,Usage usage) {
        @Override public String toString(){return "ExecutionResponse[redacted]";}
    }
    Response execute(Request request);
}
