package com.elmacore.agentplatform.identity.application;

import com.elmacore.agentplatform.identity.domain.TenantAccess;
import java.util.UUID;
import static com.elmacore.agentplatform.identity.application.IdentityFailure.Reason.FORBIDDEN;

public final class TenantContext {
    private final IdentityStore identities;
    private final TenantRegistry registry;
    public TenantContext(IdentityStore identities, TenantRegistry registry) {
        this.identities = identities; this.registry = registry;
    }
    public TenantAccess execute(UUID userId, UUID tenantId) {
        // The bearer authentication boundary resolved the enabled user; membership checks it again atomically.
        var role = identities.membership(userId, tenantId).orElseThrow(() -> new IdentityFailure(FORBIDDEN));
        if (!registry.readAuthorized(tenantId)) throw new IdentityFailure(FORBIDDEN);
        return new TenantAccess(tenantId, userId, role);
    }
}
