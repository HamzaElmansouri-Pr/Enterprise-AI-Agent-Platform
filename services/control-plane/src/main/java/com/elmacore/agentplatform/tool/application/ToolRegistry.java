package com.elmacore.agentplatform.tool.application;

import com.elmacore.agentplatform.tool.domain.*;
import java.util.UUID;
import java.util.List;

/** One explicit registration. No reflection, runtime registration, or executable names. */
public final class ToolRegistry {
    @FunctionalInterface public interface Handler {UUID execute(Proposal proposal,UUID actor);}
    public record Definition(String name,int version,List<String> argumentFields,String requiredPermission,boolean approvalRequired,Handler handler) {}
    private final Definition followup;
    public ToolRegistry(Handler followupHandler){followup=new Definition(ToolPayload.NAME,1,List.of("title","description","priority"),"TENANT_MANAGE",true,followupHandler);}
    public Definition required(String name,int version) {
        if(!followup.name().equals(name) || followup.version()!=version)throw new ToolFailure("unsupported_tool",409);
        return followup;
    }
}
