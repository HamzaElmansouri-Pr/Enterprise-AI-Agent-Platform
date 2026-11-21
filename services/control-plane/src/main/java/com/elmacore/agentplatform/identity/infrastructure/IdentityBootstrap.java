package com.elmacore.agentplatform.identity.infrastructure;

import com.elmacore.agentplatform.identity.domain.Email;
import com.elmacore.agentplatform.infrastructure.persistence.PostgresTenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.UUID;

/** Offline insert-only operator use case, never installed as an HTTP endpoint. */
public final class IdentityBootstrap {
    /** Messages are fixed validation descriptions, never input values or underlying exception text. */
    public static final class InputFailure extends IllegalArgumentException {
        public InputFailure(String message) { super(message); }
    }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public IdentityBootstrap(DataSource source) {
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new JdbcTransactionManager(source));
        transaction.setTimeout(5);
    }
    public void execute(UUID tenantId, String name, UUID userId, Email email, String password) {
        if (tenantId == null) throw new InputFailure("BOOTSTRAP_TENANT_ID must be a UUID");
        if (userId == null) throw new InputFailure("BOOTSTRAP_USER_ID must be a UUID");
        if (name == null || name.isBlank() || name.length() > 200) {
            throw new InputFailure("BOOTSTRAP_TENANT_NAME must contain 1–200 nonblank characters");
        }
        if (email == null) throw new InputFailure("BOOTSTRAP_EMAIL must be a valid email");
        if (password == null || password.isBlank() || password.length() < 16 || password.length() > 128) {
            throw new InputFailure("BOOTSTRAP_PASSWORD must contain 16–128 characters and cannot be blank");
        }
        var encoder = new ArgonPasswords();
        String hash = encoder.encode(password);
        try {
            transaction.executeWithoutResult(status -> jdbc.execute((ConnectionCallback<Void>) connection -> {
                PostgresTenantContext.apply(connection, tenantId);
                var tenants = jdbc.queryForList("SELECT name FROM platform.tenants WHERE id = ?", String.class, tenantId);
                var users = jdbc.queryForList("SELECT id FROM platform.users WHERE email = ?", UUID.class, email.value());
                var memberships = jdbc.queryForList("SELECT role FROM platform.tenant_memberships WHERE user_id = ? AND tenant_id = ?",
                        String.class, userId, tenantId);
                if (!tenants.isEmpty() || !users.isEmpty() || !memberships.isEmpty()) {
                    if (tenants.equals(java.util.List.of(name)) && users.equals(java.util.List.of(userId))
                            && memberships.equals(java.util.List.of("OWNER"))) return null;
                    throw new IllegalStateException("Bootstrap conflicts with existing records");
                }
                jdbc.update("INSERT INTO platform.tenants(id, name) VALUES (?, ?)", tenantId, name);
                jdbc.update("INSERT INTO platform.users(id, email, password_hash) VALUES (?, ?, ?)", userId, email.value(), hash);
                jdbc.update("INSERT INTO platform.tenant_memberships(user_id, tenant_id, role) VALUES (?, ?, 'OWNER')", userId, tenantId);
                return null;
            }));
        } catch (RuntimeException error) {
            // JDBC exception text may contain bound hashes; never propagate it to operator logs.
            throw new IllegalStateException("Identity bootstrap failed; verify inputs and privileges with an operator");
        }
    }
}
