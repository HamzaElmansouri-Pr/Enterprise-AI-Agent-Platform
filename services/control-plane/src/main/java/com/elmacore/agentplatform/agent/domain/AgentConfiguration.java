package com.elmacore.agentplatform.agent.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

public record AgentConfiguration(UUID id, UUID tenantId, AgentDetails details, AgentStatus status,
                                 Instant createdAt, Instant updatedAt) {
    public AgentConfiguration {
        Objects.requireNonNull(id); Objects.requireNonNull(tenantId); Objects.requireNonNull(details);
        Objects.requireNonNull(status); Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("Invalid agent timestamps");
    }
    public static AgentConfiguration draft(UUID id, UUID tenantId, AgentDetails details, Instant now) {
        now = now.truncatedTo(ChronoUnit.MICROS);
        return new AgentConfiguration(id, tenantId, details, AgentStatus.DRAFT, now, now);
    }
    public AgentConfiguration update(AgentDetails replacement, Instant now) {
        if (status == AgentStatus.ARCHIVED) throw new AgentArchived();
        return new AgentConfiguration(id, tenantId, replacement, status, createdAt, nextTime(now));
    }
    public AgentConfiguration archive(Instant now) {
        if (status == AgentStatus.ARCHIVED) return this;
        return new AgentConfiguration(id, tenantId, details, AgentStatus.ARCHIVED, createdAt, nextTime(now));
    }
    private Instant nextTime(Instant now) {
        now = now.truncatedTo(ChronoUnit.MICROS);
        return now.isAfter(updatedAt) ? now : updatedAt.plus(1, ChronoUnit.MICROS);
    }
    @Override public String toString() { return "AgentConfiguration[redacted]"; }
}
