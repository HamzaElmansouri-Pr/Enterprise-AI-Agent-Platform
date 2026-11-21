package com.elmacore.agentplatform.agent.application;

import com.elmacore.agentplatform.agent.domain.AgentConfiguration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentStore {
    AgentConfiguration insert(AgentConfiguration agent);
    Optional<AgentConfiguration> find(UUID tenantId, UUID id);
    List<AgentConfiguration> list(UUID tenantId, AgentPageRequest request);
    Optional<AgentConfiguration> updateDraft(AgentConfiguration agent);
    Optional<AgentConfiguration> archiveDraft(AgentConfiguration agent);
}
