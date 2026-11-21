package com.elmacore.agentplatform.identity.application;

public interface TenantRegistry {
    /** Starts a transaction, applies authorized context, and reads the real RLS-protected row. */
    boolean readAuthorized(AuthorizedTenant tenant);
}
