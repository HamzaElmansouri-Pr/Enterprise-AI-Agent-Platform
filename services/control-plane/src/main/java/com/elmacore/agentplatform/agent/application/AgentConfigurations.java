package com.elmacore.agentplatform.agent.application;

import com.elmacore.agentplatform.agent.domain.AgentConfiguration;
import com.elmacore.agentplatform.agent.domain.AgentDetails;
import com.elmacore.agentplatform.agent.domain.AgentArchived;
import com.elmacore.agentplatform.identity.application.AuthorizedTenant;
import com.elmacore.agentplatform.identity.application.MembershipAuthorization;
import com.elmacore.agentplatform.identity.application.IdentityFailure;
import com.elmacore.agentplatform.identity.domain.Role;
import com.elmacore.agentplatform.tenancy.application.TenantTransactions;
import java.time.Clock;
import java.util.UUID;

public final class AgentConfigurations {
    private final MembershipAuthorization authorization;
    private final TenantTransactions transactions;
    private final AgentStore store;
    private final Clock clock;
    public AgentConfigurations(MembershipAuthorization authorization, TenantTransactions transactions,
                               AgentStore store, Clock clock) {
        this.authorization = authorization; this.transactions = transactions; this.store = store; this.clock = clock;
    }
    private AuthorizedTenant authorize(UUID userId, UUID tenantId, boolean write) {
        var tenant = authorization.authorize(userId, tenantId);
        if (write && tenant.role() != Role.OWNER && tenant.role() != Role.ADMIN) {
            throw new IdentityFailure(IdentityFailure.Reason.FORBIDDEN);
        }
        return tenant;
    }
    public AgentConfiguration create(UUID userId, UUID tenantId, AgentDetails details) {
        var tenant = authorize(userId, tenantId, true);
        return transactions.execute(tenant, () -> store.insert(
                AgentConfiguration.draft(UUID.randomUUID(), tenantId, details, clock.instant())));
    }
    public AgentPage list(UUID userId, UUID tenantId, AgentPageRequest request) {
        var tenant = authorize(userId, tenantId, false);
        return transactions.execute(tenant, () -> {
            var rows = store.list(tenantId, request);
            boolean more = rows.size() > request.size();
            return new AgentPage(rows.subList(0, Math.min(rows.size(), request.size())), request.page(), request.size(), more);
        });
    }
    public AgentConfiguration get(UUID userId, UUID tenantId, UUID id) {
        var tenant = authorize(userId, tenantId, false);
        return transactions.execute(tenant, () -> required(tenantId, id));
    }
    public AgentConfiguration update(UUID userId, UUID tenantId, UUID id, AgentDetails details) {
        var tenant = authorize(userId, tenantId, true);
        return transactions.execute(tenant, () -> {
            var replacement = required(tenantId, id).update(details, clock.instant());
            return store.updateDraft(replacement).orElseGet(() -> {
                // A concurrent archive changes the conditional-write outcome, never its terminal state.
                required(tenantId, id);
                throw new AgentArchived();
            });
        });
    }
    public AgentConfiguration archive(UUID userId, UUID tenantId, UUID id) {
        var tenant = authorize(userId, tenantId, true);
        return transactions.execute(tenant, () -> {
            var current = required(tenantId, id);
            var archived = current.archive(clock.instant());
            if (archived == current) return current;
            return store.archiveDraft(archived).orElseGet(() -> required(tenantId, id));
        });
    }
    private AgentConfiguration required(UUID tenantId, UUID id) {
        return store.find(tenantId, id).orElseThrow(AgentNotFound::new);
    }
}
