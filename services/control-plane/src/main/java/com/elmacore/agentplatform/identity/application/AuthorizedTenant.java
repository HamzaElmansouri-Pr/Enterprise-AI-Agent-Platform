package com.elmacore.agentplatform.identity.application;

import com.elmacore.agentplatform.identity.domain.Role;
import java.util.UUID;

/** Produced only by the trusted live membership check, never deserialized from HTTP input. */
public final class AuthorizedTenant {
    private final UUID tenantId;
    private final UUID userId;
    private final Role role;
    AuthorizedTenant(UUID tenantId, UUID userId, Role role) {
        this.tenantId = java.util.Objects.requireNonNull(tenantId);
        this.userId = java.util.Objects.requireNonNull(userId);
        this.role = java.util.Objects.requireNonNull(role);
    }
    public UUID tenantId() { return tenantId; }
    public UUID userId() { return userId; }
    public Role role() { return role; }
}
