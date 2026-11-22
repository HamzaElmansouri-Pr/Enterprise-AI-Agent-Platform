package com.elmacore.agentplatform.tool.domain;

import java.util.UUID;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public record Proposal(UUID id,UUID tenantId,UUID agentId,UUID createdBy,ToolPayload payload,int toolVersion,
                       String payloadDigest,Status status,Instant createdAt,Decision decision,
                       UUID executedBy,UUID taskId,String failureCode,Instant startedAt,Instant completedAt) {
    public enum Status {PENDING_APPROVAL,APPROVED,REJECTED,EXECUTING,SUCCEEDED,FAILED}
    public record Decision(UUID id,UUID approverId,String payloadDigest,String decision,String reason,Instant decidedAt) {
        @Override public String toString(){return "ApprovalDecision[redacted]";}
    }
    public static String digest(UUID id,UUID tenant,UUID agent,UUID creator,ToolPayload payload,int version) {
        // Versioned canonical encoding: fixed field order; UTF-8 byte length then exact bytes.
        // Including identities binds this digest to this proposal, tenant, agent and creator.
        try {
            var hash=MessageDigest.getInstance("SHA-256");hash.update("EAAP-007/v1\n".getBytes(StandardCharsets.UTF_8));
            for(String value:java.util.List.of(id.toString(),tenant.toString(),agent.toString(),creator.toString(),payload.toolName(),Integer.toString(version),payload.arguments().title(),payload.arguments().description(),payload.arguments().priority().name(),payload.rationale())) {
                byte[] bytes=value.getBytes(StandardCharsets.UTF_8);hash.update((bytes.length+":").getBytes(StandardCharsets.US_ASCII));hash.update(bytes);
            }
            return HexFormat.of().formatHex(hash.digest());
        }catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException("SHA-256 unavailable");}
    }
    public void verifyDigest(String expected) {
        String actual=digest(id,tenantId,agentId,createdBy,payload,toolVersion);
        if(expected==null || !expected.matches("[0-9a-f]{64}") || !payloadDigest.equals(expected) || !payloadDigest.equals(actual))throw new ToolFailure("proposal_digest_mismatch",409);
    }
    public void requirePending(){if(status!=Status.PENDING_APPROVAL)throw new ToolFailure("proposal_not_pending",409);}
    public void requireExecutable(){
        if(status!=Status.APPROVED || decision==null || !"APPROVED".equals(decision.decision()) || createdBy.equals(decision.approverId()))throw new ToolFailure("proposal_not_approved",409);
        verifyDigest(decision.payloadDigest());
    }
    @Override public String toString(){return "ToolProposal[redacted]";}
}
