package com.elmacore.agentplatform.identity.infrastructure;

import com.elmacore.agentplatform.identity.application.TenantDirectory;
import com.elmacore.agentplatform.identity.domain.Role;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.UUID;

public final class JdbcTenantDirectory implements TenantDirectory {
    private final JdbcTemplate jdbc;
    public JdbcTenantDirectory(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public List<Workspace> list(UUID userId, int limit, long offset) {
        return jdbc.query(connection -> {
            var statement = connection.prepareStatement("SELECT * FROM platform.discover_tenants(?, ?, ?)");
            statement.setQueryTimeout(3);
            statement.setObject(1, userId); statement.setInt(2, limit); statement.setLong(3, offset);
            return statement;
        }, (rs, row) -> new Workspace(rs.getObject("tenant_id", UUID.class), rs.getString("name"),
                Role.valueOf(rs.getString("role"))));
    }
}
