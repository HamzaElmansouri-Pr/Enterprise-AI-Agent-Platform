package com.elmacore.agentplatform.identity.application;

import java.util.Objects;
import java.util.UUID;
import static com.elmacore.agentplatform.identity.application.IdentityFailure.Reason.FORBIDDEN;

public final class MembershipAuthorization {
    private final IdentityStore identities;
    public MembershipAuthorization(IdentityStore identities) { this.identities = identities; }
    public AuthorizedTenant authorize(UUID userId, UUID tenantId) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(tenantId);
        // This existing narrow SQL function checks current membership and enabled state together.
        var role = identities.membership(userId, tenantId).orElseThrow(() -> new IdentityFailure(FORBIDDEN));
        return new AuthorizedTenant(tenantId, userId, role);
    }
}
