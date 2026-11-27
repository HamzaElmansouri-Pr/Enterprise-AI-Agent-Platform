package com.elmacore.agentplatform.identity.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class TenantDiscovery {
    private final IdentityStore identities;
    private final TenantDirectory directory;
    public TenantDiscovery(IdentityStore identities, TenantDirectory directory) {
        this.identities = identities; this.directory = directory;
    }
    public record Page(List<TenantDirectory.Workspace> items, int page, int size, boolean hasNext) {
        public Page { items = List.copyOf(items); }
        @Override public String toString() { return "WorkspacePage[redacted]"; }
    }
    public Page execute(UUID userId, int page, int size) {
        Objects.requireNonNull(userId);
        long offset = (long)page * size;
        if (page < 0 || size < 1 || size > 100 || offset > 10000) {
            throw new IllegalArgumentException("Invalid discovery page");
        }
        if (!identities.enabled(userId)) throw new IdentityFailure(IdentityFailure.Reason.UNAUTHENTICATED);
        var rows = directory.list(userId, size + 1, offset);
        return new Page(rows.stream().limit(size).toList(), page, size, rows.size() > size);
    }
}
