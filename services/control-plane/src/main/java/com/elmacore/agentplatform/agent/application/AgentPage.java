package com.elmacore.agentplatform.agent.application;

import com.elmacore.agentplatform.agent.domain.AgentConfiguration;
import java.util.List;

public record AgentPage(List<AgentConfiguration> items, int page, int size, boolean hasNext) {
    public AgentPage { items = List.copyOf(items); }
    @Override public String toString() { return "AgentPage[redacted]"; }
}
