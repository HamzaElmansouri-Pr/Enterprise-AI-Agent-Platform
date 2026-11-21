package com.elmacore.agentplatform.identity.application;

import java.util.UUID;

public interface TenantRegistry {
    /** Starts a transaction, applies authorized context, and reads the real RLS-protected row. */
    boolean readAuthorized(UUID tenantId);
}
