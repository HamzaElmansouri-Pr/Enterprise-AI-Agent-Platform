package com.elmacore.agentplatform.tool.application;

import com.elmacore.agentplatform.tool.domain.*;
import com.elmacore.agentplatform.agent.application.*;
import com.elmacore.agentplatform.agent.domain.*;
import com.elmacore.agentplatform.identity.application.*;
import com.elmacore.agentplatform.identity.domain.Role;
import com.elmacore.agentplatform.execution.application.*;
import com.elmacore.agentplatform.execution.domain.RunFailure;
import com.elmacore.agentplatform.tenancy.application.TenantTransactions;
import java.util.*;

public final class ToolGateway {
    private final MembershipAuthorization auth;private final TenantTransactions tx;private final AgentStore agents;
    private final ProposalStore store;private final ToolRegistry registry;private final ProposalRuntime runtime;
    private final RunStore quota;private final ExecutionAdmission admission;
    public ToolGateway(MembershipAuthorization auth,TenantTransactions tx,AgentStore agents,ProposalStore store,ToolRegistry registry,ProposalRuntime runtime,RunStore quota,ExecutionAdmission admission) {
        this.auth=auth;this.tx=tx;this.agents=agents;this.store=store;this.registry=registry;this.runtime=runtime;this.quota=quota;this.admission=admission;
    }
    private AuthorizedTenant scope(UUID user,UUID tenant,boolean manage){
        var scope=auth.authorize(user,tenant);if(manage && scope.role()==Role.MEMBER)throw new IdentityFailure(IdentityFailure.Reason.FORBIDDEN);return scope;
    }
    private AgentConfiguration draft(UUID tenant,UUID agent){
        var value=agents.find(tenant,agent).orElseThrow(AgentNotFound::new);
        if(value.status()!=AgentStatus.DRAFT)throw new AgentArchived();return value;
    }
    private Proposal create(AuthorizedTenant scope,UUID agent,UUID id,ToolPayload payload,UUID correlation){
        registry.required(payload.toolName(),ToolPayload.VERSION);
        return tx.execute(scope,()->{draft(scope.tenantId(),agent);return store.insert(scope.tenantId(),agent,scope.userId(),id,payload,Proposal.digest(id,scope.tenantId(),agent,scope.userId(),payload,1),correlation);});
    }
    public Proposal submit(UUID user,UUID tenant,UUID agent,ToolPayload payload,UUID correlation){return create(scope(user,tenant,true),agent,UUID.randomUUID(),payload,correlation);}
    public Proposal generate(UUID user,UUID tenant,UUID agent,String intent,UUID correlation){
        ToolPayload.text(intent,1000);var scope=scope(user,tenant,false);
        tx.execute(scope,()->draft(tenant,agent));admission.acquire();
        UUID requestId=UUID.randomUUID();long start=System.nanoTime();
        try {
            var config=tx.execute(scope,()->{var current=draft(tenant,agent);quota.admit(tenant);return current;});
            var response=runtime.generate(new ProposalRuntime.Request(requestId,correlation,config.details().systemInstructions(),intent));
            if(response==null || response.proposal()==null || !ExecutionRuntime.MODEL.equals(response.modelId()) || response.usage()==null
                || bad(response.usage().inputTokens(),1000000) || bad(response.usage().outputTokens(),800))throw new RunFailure("INVALID_MODEL_OUTPUT",502);
            var result=create(scope(user,tenant,false),agent,requestId,response.proposal(),correlation);
            org.slf4j.LoggerFactory.getLogger(ToolGateway.class).info("proposal_generated proposal_id={} correlation_id={} model={} duration_ms={} input_tokens={} output_tokens={}",result.id(),correlation,response.modelId(),(System.nanoTime()-start)/1_000_000,response.usage().inputTokens(),response.usage().outputTokens());
            return result;
        }finally{admission.release();}
    }
    private static boolean bad(Integer n,int max){return n!=null && (n<0 || n>max);}
    private UUID visible(AuthorizedTenant scope){return scope.role()==Role.MEMBER?scope.userId():null;}
    public Proposal get(UUID user,UUID tenant,UUID id){var scope=scope(user,tenant,false);return tx.execute(scope,()->store.get(tenant,id,visible(scope),false));}
    public record Page<T>(List<T> items,int page,int size,boolean hasNext){@Override public String toString(){return "ToolPage[redacted]";}}
    private static void page(int page,int size){if(page<0 || size<1 || size>100 || (long)page*size>10000)throw new IllegalArgumentException("Invalid pagination");}
    public Page<Proposal> list(UUID user,UUID tenant,int page,int size){page(page,size);var scope=scope(user,tenant,false);return tx.execute(scope,()->{var rows=store.list(tenant,visible(scope),size+1,(long)page*size);return new Page<>(List.copyOf(rows.subList(0,Math.min(size,rows.size()))),page,size,rows.size()>size);});}
    public Page<FollowupTask> tasks(UUID user,UUID tenant,int page,int size){page(page,size);var scope=scope(user,tenant,false);return tx.execute(scope,()->{var rows=store.tasks(tenant,size+1,(long)page*size);return new Page<>(List.copyOf(rows.subList(0,Math.min(size,rows.size()))),page,size,rows.size()>size);});}
    public Proposal decide(UUID user,UUID tenant,UUID id,String digest,boolean approve,String reason,UUID correlation){
        var scope=scope(user,tenant,true);
        if(approve && reason!=null)throw new IllegalArgumentException("Approval accepts no reason");
        if(!approve)ToolPayload.text(reason,500);
        var result=tx.execute(scope,()->{
            var proposal=store.get(tenant,id,null,true);proposal.requirePending();proposal.verifyDigest(digest);
            if(approve && user.equals(proposal.createdBy()))throw new ToolFailure("self_approval_forbidden",403);
            store.decide(tenant,proposal,user,approve,reason,correlation);return store.get(tenant,id,null,false);
        });audit(result,user,correlation);return result;
    }
    public Proposal execute(UUID user,UUID tenant,UUID id,UUID correlation){
        var scope=scope(user,tenant,true);
        var result=tx.execute(scope,()->{
            var proposal=store.get(tenant,id,null,true);proposal.verifyDigest(proposal.payloadDigest());
            if(proposal.status()==Proposal.Status.SUCCEEDED)return proposal;
            proposal.requireExecutable();var tool=registry.required(proposal.payload().toolName(),proposal.toolVersion());
            store.claim(tenant,id,user,correlation);
            var effect=store.attempt(()->tool.handler().execute(proposal,user));
            store.finish(tenant,id,effect);return store.get(tenant,id,null,false);
        });audit(result,user,correlation);return result;
    }
    private static void audit(Proposal result,UUID requester,UUID correlation){org.slf4j.LoggerFactory.getLogger(ToolGateway.class).info("tool_proposal_result proposal_id={} correlation_id={} status={} requested_by={} executed_by={} task_id={} failure_code={}",result.id(),correlation,result.status(),requester,result.executedBy(),result.taskId(),result.failureCode());}
}
