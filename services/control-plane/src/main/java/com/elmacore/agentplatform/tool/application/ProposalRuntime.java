package com.elmacore.agentplatform.tool.application;
import com.elmacore.agentplatform.tool.domain.ToolPayload;
import com.elmacore.agentplatform.execution.domain.RunResult.Usage;
import java.util.UUID;
public interface ProposalRuntime {
    record Request(UUID runId,UUID correlationId,String instructions,String intent){@Override public String toString(){return "ProposalGenerationRequest[redacted]";}}
    record Response(ToolPayload proposal,String modelId,Usage usage){@Override public String toString(){return "ProposalGenerationResponse[redacted]";}}
    Response generate(Request request);
}
