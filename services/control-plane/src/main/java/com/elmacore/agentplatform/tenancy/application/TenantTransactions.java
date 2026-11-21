package com.elmacore.agentplatform.tenancy.application;

import com.elmacore.agentplatform.identity.application.AuthorizedTenant;
import java.util.function.Supplier;

/** Executes one application operation after membership/permission authorization. */
public interface TenantTransactions {
    <T> T execute(AuthorizedTenant tenant, Supplier<T> work);
}
