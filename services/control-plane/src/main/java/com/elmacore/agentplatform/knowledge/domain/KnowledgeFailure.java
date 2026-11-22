package com.elmacore.agentplatform.knowledge.domain;

public final class KnowledgeFailure extends RuntimeException {
    private final String code;
    private final int status;
    public KnowledgeFailure(String code, int status) { super(code); this.code=code; this.status=status; }
    public String code() { return code; }
    public int status() { return status; }
}
