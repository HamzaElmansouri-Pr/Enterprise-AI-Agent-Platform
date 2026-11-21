package com.elmacore.agentplatform.infrastructure.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/** Internal adapter primitive; callers must supply an already authorized tenant identity. */
public final class PostgresTenantContext {
    private PostgresTenantContext() {
    }

    public static void apply(Connection connection, UUID trustedTenantId) throws SQLException {
        Objects.requireNonNull(trustedTenantId, "A trusted tenant identity is required");
        if (connection.getAutoCommit()) {
            throw new IllegalStateException("Tenant context requires an explicit transaction");
        }
        try (var statement = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            statement.setQueryTimeout(3);
            statement.setString(1, trustedTenantId.toString());
            statement.execute();
        }
    }
}
