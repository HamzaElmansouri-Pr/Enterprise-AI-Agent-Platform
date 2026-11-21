package com.elmacore.agentplatform.identity.infrastructure;

import com.elmacore.agentplatform.identity.application.IdentityStore;
import com.elmacore.agentplatform.identity.domain.Email;
import com.elmacore.agentplatform.identity.domain.Role;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Optional;
import java.util.UUID;

public final class JdbcIdentityStore implements IdentityStore {
    private final JdbcTemplate jdbc;
    public JdbcIdentityStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public Optional<Credentials> credentials(Email email) {
        return jdbc.query("SELECT * FROM platform.login_identity(?)", (rs, row) ->
                new Credentials(rs.getObject("id", UUID.class), rs.getString("password_hash"),
                        rs.getBoolean("enabled")), email.value()).stream().findFirst();
    }
    @Override public boolean enabled(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT platform.enabled_identity(?)", Boolean.class, userId));
    }
    @Override public Optional<Role> membership(UUID userId, UUID tenantId) {
        return Optional.ofNullable(jdbc.queryForObject("SELECT platform.membership_role(?, ?)",
                String.class, userId, tenantId)).map(Role::valueOf);
    }
}
