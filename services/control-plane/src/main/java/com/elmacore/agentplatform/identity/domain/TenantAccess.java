package com.elmacore.agentplatform.identity.domain;

import java.util.Objects;
import java.util.UUID;

public record TenantAccess(UUID tenantId, UUID userId, Role role) {
    public TenantAccess {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(userId);
        Objects.requireNonNull(role);
    }
}
