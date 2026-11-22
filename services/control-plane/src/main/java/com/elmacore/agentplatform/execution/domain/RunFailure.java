package com.elmacore.agentplatform.execution.domain;

import java.util.UUID;

public final class RunFailure extends RuntimeException {
    private final String code;
    private final int status;
    private final UUID runId;
    private final String modelId;
    private final RunResult.Usage usage;
    public RunFailure(String code,int status) { this(code,status,null); }
    public RunFailure(String code,int status,UUID runId) { this(code,status,runId,null,new RunResult.Usage(null,null)); }
    public RunFailure(String code,int status,UUID runId,String modelId,RunResult.Usage usage) {super(code);this.code=code;this.status=status;this.runId=runId;this.modelId=modelId;this.usage=usage;}
    public String modelId(){return modelId;}
    public RunResult.Usage usage(){return usage;}
    public String code(){return code;}
    public int status(){return status;}
    public UUID runId(){return runId;}
}
