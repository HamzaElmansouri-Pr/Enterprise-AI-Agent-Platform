package com.elmacore.agentplatform.tool.domain;
import java.util.UUID;
import java.time.Instant;
public record FollowupTask(UUID id,UUID tenantId,UUID sourceProposalId,UUID createdBy,String title,String description,ToolPayload.Priority priority,String status,Instant createdAt) {
    @Override public String toString(){return "FollowupTask[redacted]";}
}
