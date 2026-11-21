package com.elmacore.agentplatform.agent.infrastructure.persistence;

import com.elmacore.agentplatform.agent.application.AgentPageRequest;
import com.elmacore.agentplatform.agent.application.AgentStore;
import com.elmacore.agentplatform.agent.domain.AgentConfiguration;
import com.elmacore.agentplatform.agent.domain.AgentDetails;
import com.elmacore.agentplatform.agent.domain.AgentStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class JdbcAgentStore implements AgentStore {
    private static final String COLUMNS = "id, tenant_id, name, description, system_instructions, status, created_at, updated_at";
    private static final RowMapper<AgentConfiguration> MAP = (rs, row) -> new AgentConfiguration(
            rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
            new AgentDetails(rs.getString("name"), rs.getString("description"), rs.getString("system_instructions")),
            AgentStatus.valueOf(rs.getString("status")), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    private final DataSource source;
    private final JdbcTemplate jdbc;
    public JdbcAgentStore(DataSource source) {
        this.source = source;
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(3);
    }
    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(source)) {
            throw new IllegalStateException("Agent persistence requires a tenant transaction");
        }
    }
    @Override public AgentConfiguration insert(AgentConfiguration agent) {
        requireTransaction();
        return jdbc.queryForObject("INSERT INTO platform.agent_configurations (" + COLUMNS
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING " + COLUMNS, MAP,
                agent.id(), agent.tenantId(), agent.details().name(), agent.details().description(),
                agent.details().systemInstructions(), agent.status().name(), Timestamp.from(agent.createdAt()), Timestamp.from(agent.updatedAt()));
    }
    @Override public Optional<AgentConfiguration> find(UUID tenantId, UUID id) {
        requireTransaction();
        return jdbc.query("SELECT " + COLUMNS + " FROM platform.agent_configurations WHERE tenant_id = ? AND id = ?",
                MAP, tenantId, id).stream().findFirst();
    }
    @Override public List<AgentConfiguration> list(UUID tenantId, AgentPageRequest request) {
        requireTransaction();
        String sql = "SELECT " + COLUMNS + " FROM platform.agent_configurations WHERE tenant_id = ?";
        String order = " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?";
        if (request.status() == null) {
            return jdbc.query(sql + order, MAP, tenantId, request.size() + 1, request.offset());
        }
        return jdbc.query(sql + " AND status = ?" + order, MAP, tenantId, request.status().name(), request.size() + 1, request.offset());
    }
    @Override public Optional<AgentConfiguration> updateDraft(AgentConfiguration agent) {
        requireTransaction();
        return jdbc.query("""
                UPDATE platform.agent_configurations
                SET name = ?, description = ?, system_instructions = ?,
                    updated_at = GREATEST(?, updated_at + INTERVAL '1 microsecond')
                WHERE tenant_id = ? AND id = ? AND status = 'DRAFT'
                RETURNING
                """ + COLUMNS, MAP, agent.details().name(), agent.details().description(), agent.details().systemInstructions(),
                Timestamp.from(agent.updatedAt()), agent.tenantId(), agent.id()).stream().findFirst();
    }
    @Override public Optional<AgentConfiguration> archiveDraft(AgentConfiguration agent) {
        requireTransaction();
        return jdbc.query("""
                UPDATE platform.agent_configurations
                SET status = 'ARCHIVED', updated_at = GREATEST(?, updated_at + INTERVAL '1 microsecond')
                WHERE tenant_id = ? AND id = ? AND status = 'DRAFT'
                RETURNING
                """ + COLUMNS, MAP, Timestamp.from(agent.updatedAt()), agent.tenantId(), agent.id()).stream().findFirst();
    }
}
