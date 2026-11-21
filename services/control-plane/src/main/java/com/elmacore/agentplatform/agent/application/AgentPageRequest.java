package com.elmacore.agentplatform.agent.application;

import com.elmacore.agentplatform.agent.domain.AgentStatus;

public record AgentPageRequest(int page, int size, AgentStatus status) {
    public static final long MAX_OFFSET = 10000;
    public AgentPageRequest {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > MAX_OFFSET) {
            throw new IllegalArgumentException("Invalid agent pagination");
        }
    }
    public long offset() { return (long) page * size; }
}
