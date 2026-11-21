package com.elmacore.agentplatform.identity.infrastructure;

import com.elmacore.agentplatform.identity.application.TenantRegistry;
import com.elmacore.agentplatform.infrastructure.persistence.PostgresTenantContext;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.UUID;

public final class JdbcTenantRegistry implements TenantRegistry {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public JdbcTenantRegistry(DataSource source) {
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new JdbcTransactionManager(source));
        transactions.setTimeout(5);
    }
    @Override public boolean readAuthorized(UUID tenantId) {
        return Boolean.TRUE.equals(transactions.execute(status -> jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            // JdbcTemplate obtains the transaction-bound connection; both operations use it directly.
            PostgresTenantContext.apply(connection, tenantId);
            try (var statement = connection.prepareStatement("SELECT id FROM platform.tenants WHERE id = ?")) {
                statement.setQueryTimeout(3);
                statement.setObject(1, tenantId);
                try (var result = statement.executeQuery()) {
                    return result.next() && tenantId.equals(result.getObject(1, UUID.class));
                }
            }
        })));
    }
}
