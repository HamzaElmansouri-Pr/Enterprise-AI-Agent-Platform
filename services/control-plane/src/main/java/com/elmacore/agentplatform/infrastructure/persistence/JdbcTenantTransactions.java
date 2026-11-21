package com.elmacore.agentplatform.infrastructure.persistence;

import com.elmacore.agentplatform.identity.application.AuthorizedTenant;
import com.elmacore.agentplatform.tenancy.application.TenantTransactions;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.Objects;
import java.util.function.Supplier;

public final class JdbcTenantTransactions implements TenantTransactions {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public JdbcTenantTransactions(DataSource source) {
        this.source = source;
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new JdbcTransactionManager(source));
        transactions.setTimeout(5);
    }
    @Override public <T> T execute(AuthorizedTenant tenant, Supplier<T> work) {
        Objects.requireNonNull(tenant, "Authorized tenant is required");
        Objects.requireNonNull(work);
        // No nested operations may replace an outer tenant or commit a partial use case.
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.hasResource(source)) {
            throw new IllegalStateException("Tenant transactions cannot be nested");
        }
        return transactions.execute(status -> jdbc.execute((ConnectionCallback<T>) connection -> {
            PostgresTenantContext.apply(connection, tenant.tenantId());
            // All JdbcTemplates for this datasource reuse this exact transaction-bound connection.
            return work.get();
        }));
    }
}
