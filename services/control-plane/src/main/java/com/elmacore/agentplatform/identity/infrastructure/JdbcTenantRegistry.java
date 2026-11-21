package com.elmacore.agentplatform.identity.infrastructure;

import com.elmacore.agentplatform.identity.application.AuthorizedTenant;
import com.elmacore.agentplatform.identity.application.TenantRegistry;
import com.elmacore.agentplatform.infrastructure.persistence.JdbcTenantTransactions;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

public final class JdbcTenantRegistry implements TenantRegistry {
    private final JdbcTemplate jdbc;
    private final JdbcTenantTransactions transactions;
    public JdbcTenantRegistry(DataSource source) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(3);
        transactions = new JdbcTenantTransactions(source);
    }
    @Override public boolean readAuthorized(AuthorizedTenant tenant) {
        return transactions.execute(tenant, () -> !jdbc.queryForList(
                "SELECT id FROM platform.tenants WHERE id = ?", java.util.UUID.class, tenant.tenantId()).isEmpty());
    }
}
