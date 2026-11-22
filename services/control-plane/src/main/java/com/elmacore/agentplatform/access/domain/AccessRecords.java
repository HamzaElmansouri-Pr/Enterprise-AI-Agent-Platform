package com.elmacore.agentplatform.access.domain;
import java.util.UUID;
import java.time.Instant;
import java.util.List;
public final class AccessRecords {
    private AccessRecords(){}
    public record Member(UUID userId,String email,String role,Instant createdAt){@Override public String toString(){return "Member[redacted]";}}
    public record Invitation(UUID id,UUID tenantId,String email,String role,UUID invitedBy,String status,Instant createdAt,Instant expiresAt,boolean expired,Instant acceptedAt,UUID acceptedBy,Instant cancelledAt,UUID cancelledBy){@Override public String toString(){return "Invitation[redacted]";}}
    public record Issued(Invitation invitation,String token){@Override public String toString(){return "IssuedInvitation[redacted]";}}
    public record Accepted(UUID invitationId,UUID tenantId,UUID userId,String role){@Override public String toString(){return "AcceptedInvitation[redacted]";}}
    public record Page<T>(List<T> items,int page,int size,boolean hasNext){@Override public String toString(){return "AccessPage[redacted]";}}
}
