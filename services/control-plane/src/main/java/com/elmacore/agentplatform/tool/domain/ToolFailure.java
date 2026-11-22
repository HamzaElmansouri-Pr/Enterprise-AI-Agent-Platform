package com.elmacore.agentplatform.tool.domain;
public final class ToolFailure extends RuntimeException {
    private final String code;private final int status;
    public ToolFailure(String code,int status){super(code);this.code=code;this.status=status;}
    public String code(){return code;}public int status(){return status;}
}
