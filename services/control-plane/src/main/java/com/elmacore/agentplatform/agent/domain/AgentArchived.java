package com.elmacore.agentplatform.agent.domain;

public final class AgentArchived extends RuntimeException {
    public AgentArchived() { super("Archived agent cannot be modified"); }
}
