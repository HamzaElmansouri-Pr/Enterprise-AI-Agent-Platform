package com.elmacore.agentplatform.identity.application;

import com.elmacore.agentplatform.identity.domain.Role;
import java.util.List;
import java.util.UUID;

/** Read-only global identity capability; never grants access to a workspace. */
public interface TenantDirectory {
    record Workspace(UUID tenantId, String name, Role role) {
        @Override public String toString() { return "Workspace[redacted]"; }
    }
    List<Workspace> list(UUID userId, int limit, long offset);
}
