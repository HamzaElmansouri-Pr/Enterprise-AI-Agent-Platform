package com.elmacore.agentplatform.execution.application;

import com.elmacore.agentplatform.agent.application.*;
import com.elmacore.agentplatform.agent.domain.*;
import com.elmacore.agentplatform.identity.application.*;
import com.elmacore.agentplatform.knowledge.application.Knowledge;
import com.elmacore.agentplatform.tenancy.application.TenantTransactions;
import com.elmacore.agentplatform.execution.domain.*;
import java.util.*;
import java.util.concurrent.Semaphore;

public final class AgentRuns {
    private final MembershipAuthorization authorization;
    private final TenantTransactions transactions;
    private final AgentStore agents;
    private final Knowledge knowledge;
    private final RunStore store;
    private final ExecutionRuntime runtime;
    private final Semaphore active=new Semaphore(2);
    public AgentRuns(MembershipAuthorization authorization,TenantTransactions transactions,AgentStore agents,
                     Knowledge knowledge,RunStore store,ExecutionRuntime runtime) {
        this.authorization=authorization;this.transactions=transactions;this.agents=agents;
        this.knowledge=knowledge;this.store=store;this.runtime=runtime;
    }
    private AgentConfiguration agent(UUID tenant,UUID id,boolean draft) {
        var agent=agents.find(tenant,id).orElseThrow(AgentNotFound::new);
        if(draft && agent.status()!=AgentStatus.DRAFT) throw new AgentArchived();
        return agent;
    }
    public RunResult get(UUID user,UUID tenant,UUID agent,UUID run) {
        var scope=authorization.authorize(user,tenant);
        return transactions.execute(scope,()-> {
            agent(tenant,agent,false);store.recover(tenant,agent,user);return store.get(tenant,agent,user,run);
        });
    }
    public RunResult start(UUID user,UUID tenant,UUID agent,RunInput input,UUID correlation) {
        var scope=authorization.authorize(user,tenant);
        transactions.execute(scope,()->agent(tenant,agent,true));
        if(!active.tryAcquire()) throw new RunFailure("EXECUTION_BUSY",429);
        UUID run=null;long started=System.nanoTime();
        try {
            transactions.execute(scope,()-> {agent(tenant,agent,true);store.recover(tenant,agent,user);store.admit(tenant);return null;});
            // EAAP-005 owns inference and the final authorized READY/association query.
            var matches=knowledge.search(user,tenant,agent,input.message(),input.topK());
            var current=authorization.authorize(user,tenant);
            var config=transactions.execute(current,()->agent(tenant,agent,true));
            UUID id=UUID.randomUUID();
            transactions.execute(current,()-> {agent(tenant,agent,true);store.insert(tenant,agent,user,id,input.message());return null;});
            run=id;
            if(matches.isEmpty()) throw new RunFailure("INSUFFICIENT_KNOWLEDGE",422);
            var passages=new ArrayList<ExecutionRuntime.Passage>();
            var sources=new LinkedHashMap<String,RunResult.Citation>();int remaining=8000;
            for(var match:matches) {
                if(passages.size()==6 || remaining==0) break;
                String text=match.text();int count=Math.min(remaining,text.codePointCount(0,text.length()));
                text=text.substring(0,text.offsetByCodePoints(0,count));remaining-=count;
                String source="S"+(passages.size()+1);
                passages.add(new ExecutionRuntime.Passage(source,text));
                sources.put(source,new RunResult.Citation(match.documentId(),match.chunkId(),match.filename(),match.sourcePage()));
            }
            var output=runtime.execute(new ExecutionRuntime.Request(id,correlation,config.details().systemInstructions(),input.message(),List.copyOf(passages)));
            try {validate(output,sources.keySet());}
            catch(RunFailure invalid) {
                if(output!=null && ExecutionRuntime.MODEL.equals(output.modelId()) && output.usage()!=null
                    && !invalidUsage(output.usage().inputTokens(),1000000) && !invalidUsage(output.usage().outputTokens(),800))
                    throw new RunFailure(invalid.code(),invalid.status(),id,output.modelId(),output.usage());
                throw invalid;
            }
            var citations=output.sourceIds().stream().distinct().map(sources::get).toList();
            var result=new RunResult(id,agent,"SUCCEEDED",output.answer(),citations,output.usage(),elapsed(started),null,output.modelId());
            terminal(user,tenant,agent,result);
            return result;
        } catch(RunFailure failure) {
            if(run!=null) {
                terminal(user,tenant,agent,new RunResult(run,agent,"FAILED",null,List.of(),failure.usage(),elapsed(started),failure.code(),failure.modelId()));
            }
            throw new RunFailure(failure.code(),failure.status(),run);
        } catch(RuntimeException failure) {
            if(run!=null) {
                // Best effort only; never claim success if persistence or reauthorization fails.
                try {terminal(user,tenant,agent,new RunResult(run,agent,"FAILED",null,List.of(),new RunResult.Usage(null,null),elapsed(started),"EXECUTION_FAILED",null));}
                catch(RuntimeException ignored) { safePersistenceFailure(run); }
            }
            throw failure;
        } finally {active.release();}
    }
    private static long elapsed(long started){return (System.nanoTime()-started)/1_000_000;}
    private void terminal(UUID user,UUID tenant,UUID agent,RunResult result) {
        try {
            var scope=authorization.authorize(user,tenant);
            transactions.execute(scope,()-> {
                agent(tenant,agent,false);
                if(!store.finish(tenant,agent,user,result)) throw new RunFailure("RUN_ALREADY_TERMINAL",409,result.runId());
                return null;
            });
        } catch(RuntimeException error) {safePersistenceFailure(result.runId());throw error;}
    }
    private static void safePersistenceFailure(UUID run) {
        org.slf4j.LoggerFactory.getLogger(AgentRuns.class).error("execution_persistence_failed run_id={} correlation_id={}",run,org.slf4j.MDC.get("correlation_id"));
    }
    static void validate(ExecutionRuntime.Response output,Set<String> allowed) {
        if(output==null || output.answer()==null || output.answer().isBlank() || output.answer().codePointCount(0,output.answer().length())>6000
                || output.answer().indexOf(0)>=0 || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(output.answer())
                || output.sourceIds()==null || output.sourceIds().isEmpty() || output.sourceIds().size()>6
                || output.sourceIds().stream().anyMatch(s->!allowed.contains(s)) || !ExecutionRuntime.MODEL.equals(output.modelId())
                || output.usage()==null || invalidUsage(output.usage().inputTokens(),1000000) || invalidUsage(output.usage().outputTokens(),800))
            throw new RunFailure("INVALID_MODEL_OUTPUT",502);
    }
    private static boolean invalidUsage(Integer n,int max){return n!=null && (n<0 || n>max);}
}
