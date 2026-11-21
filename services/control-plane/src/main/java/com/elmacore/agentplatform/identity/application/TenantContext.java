package com.elmacore.agentplatform.identity.application;

import com.elmacore.agentplatform.identity.domain.TenantAccess;
import java.util.UUID;
import static com.elmacore.agentplatform.identity.application.IdentityFailure.Reason.FORBIDDEN;

public final class TenantContext {
    private final MembershipAuthorization authorization;
    private final TenantRegistry registry;
    public TenantContext(MembershipAuthorization authorization, TenantRegistry registry) {
        this.authorization = authorization; this.registry = registry;
    }
    public TenantAccess execute(UUID userId, UUID tenantId) {
        var tenant = authorization.authorize(userId, tenantId);
        if (!registry.readAuthorized(tenant)) throw new IdentityFailure(FORBIDDEN);
        return new TenantAccess(tenantId, userId, tenant.role());
    }
}
