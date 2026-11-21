package com.elmacore.agentplatform.identity.application;

import com.elmacore.agentplatform.identity.domain.Email;
import com.elmacore.agentplatform.identity.domain.Role;
import java.util.Optional;
import java.util.UUID;

public interface IdentityStore {
    record Credentials(UUID id, String passwordHash, boolean enabled) {
        @Override public String toString() { return "Credentials[redacted]"; }
    }
    Optional<Credentials> credentials(Email email);
    boolean enabled(UUID userId);
    Optional<Role> membership(UUID userId, UUID tenantId);
}
