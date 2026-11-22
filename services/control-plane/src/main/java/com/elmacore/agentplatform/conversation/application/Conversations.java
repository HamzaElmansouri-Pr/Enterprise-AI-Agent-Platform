package com.elmacore.agentplatform.conversation.application;
import com.elmacore.agentplatform.conversation.domain.ConversationRecords;
import com.elmacore.agentplatform.conversation.domain.ConversationRecords.*;
import com.elmacore.agentplatform.agent.application.AgentStore;
import com.elmacore.agentplatform.agent.application.AgentNotFound;
import com.elmacore.agentplatform.agent.domain.*;
import com.elmacore.agentplatform.execution.application.*;
import com.elmacore.agentplatform.execution.domain.*;
import com.elmacore.agentplatform.identity.application.MembershipAuthorization;
import com.elmacore.agentplatform.knowledge.application.Knowledge;
import com.elmacore.agentplatform.tenancy.application.TenantTransactions;
import java.util.*;
import java.util.function.Supplier;
public final class Conversations {
 private final MembershipAuthorization auth;private final TenantTransactions tx;private final AgentStore agents;private final ConversationStore store;private final RunStore runs;private final Knowledge knowledge;private final ConversationRuntime runtime;private final ExecutionAdmission admission;
 public Conversations(MembershipAuthorization auth,TenantTransactions tx,AgentStore agents,ConversationStore store,RunStore runs,Knowledge knowledge,ConversationRuntime runtime,ExecutionAdmission admission){this.auth=auth;this.tx=tx;this.agents=agents;this.store=store;this.runs=runs;this.knowledge=knowledge;this.runtime=runtime;this.admission=admission;}
 private AgentConfiguration agent(UUID tenant,UUID agent,boolean draft){var a=agents.find(tenant,agent).orElseThrow(AgentNotFound::new);if(draft&&a.status()!=AgentStatus.DRAFT)throw new AgentArchived();return a;}
 private <T>T in(UUID user,UUID tenant,UUID agent,Supplier<T> work){var scope=auth.authorize(user,tenant);return tx.execute(scope,()->{store.context(user);agent(tenant,agent,false);return work.get();});}
 private static long offset(int page,int size){if(page<0||size<1||size>100||(long)page*size>10000)throw new IllegalArgumentException("Invalid pagination");return (long)page*size;}
 private static <T>Page<T> page(List<T> rows,int page,int size){return new Page<>(List.copyOf(rows.subList(0,Math.min(size,rows.size()))),page,size,rows.size()>size);}
 public Conversation create(UUID u,UUID t,UUID a,String title){String name=ConversationRecords.title(title);return in(u,t,a,()->{agent(t,a,true);return store.create(t,a,u,name);});}
 public Page<Conversation> list(UUID u,UUID t,UUID a,int p,int n){long o=offset(p,n);return in(u,t,a,()->page(store.list(t,a,u,n+1,o),p,n));}
 public Conversation get(UUID u,UUID t,UUID a,UUID c){return in(u,t,a,()->store.get(t,a,u,c,false));}
 public Conversation change(UUID u,UUID t,UUID a,UUID c,String title,boolean close){String name=close?null:ConversationRecords.title(Objects.requireNonNull(title));return in(u,t,a,()->{store.get(t,a,u,c,false);runs.recover(t,a,u);return store.change(t,a,u,c,name,close);});}
 public Page<Message> messages(UUID u,UUID t,UUID a,UUID c,int p,int n){long o=offset(p,n);return in(u,t,a,()->{store.get(t,a,u,c,false);runs.recover(t,a,u);return page(store.messages(t,c,n+1,o),p,n);});}
 public Turn outcome(UUID u,UUID t,UUID a,UUID c,UUID request){return in(u,t,a,()->{store.get(t,a,u,c,false);runs.recover(t,a,u);var r=store.find(t,c,request);if(r==null)throw new RunFailure("conversation_request_not_found",404);var result=runs.get(t,a,u,r.runId());return new Turn(request,r.runId(),result.status(),result);});}
 private void terminal(UUID u,UUID t,UUID a,RunResult result){in(u,t,a,()->{if(!runs.finish(t,a,u,result))runs.recover(t,a,u);return null;});}
 private static long elapsed(long start){return (System.nanoTime()-start)/1_000_000;}
 public Turn send(UUID u,UUID t,UUID a,UUID c,UUID request,String question,UUID correlation){
  Objects.requireNonNull(request);var input=new RunInput(question,6);
  var previous=in(u,t,a,()->{store.get(t,a,u,c,false);runs.recover(t,a,u);return store.find(t,c,request);});
  if(previous!=null){if(!previous.question().equals(question))throw new RunFailure("conversation_request_conflict",409);return outcome(u,t,a,c,request);}
  admission.acquire();
  try{
   var reservation=in(u,t,a,()->{store.get(t,a,u,c,false);runs.recover(t,a,u);var existing=store.find(t,c,request);if(existing==null)agent(t,a,true);return store.reserve(t,a,u,c,request,input.message());});
   if(!reservation.fresh())return outcome(u,t,a,c,request);
   long started=System.nanoTime();UUID run=reservation.runId();
   try{
    var matches=knowledge.search(u,t,a,input.message(),6);
    if(matches.isEmpty())throw new RunFailure("INSUFFICIENT_KNOWLEDGE",422);
    // Reauthorize after retrieval. Never hold a DB transaction over either inference call.
    var config=in(u,t,a,()->agent(t,a,true));
    var history=in(u,t,a,()->{store.get(t,a,u,c,false);return store.history(t,c);});
    var evidence=GroundedAnswer.prepare(matches);
    var output=runtime.converse(new ConversationRuntime.Request(run,correlation,config.details().systemInstructions(),question,evidence.passages(),history));
    terminal(u,t,a,GroundedAnswer.result(run,a,elapsed(started),output,evidence));
   }catch(RunFailure failure){
    terminal(u,t,a,new RunResult(run,a,"FAILED",null,List.of(),failure.usage(),elapsed(started),failure.code(),failure.modelId()));
   }catch(RuntimeException failure){
    try{terminal(u,t,a,new RunResult(run,a,"FAILED",null,List.of(),new RunResult.Usage(null,null),elapsed(started),"EXECUTION_FAILED",null));}
    catch(RuntimeException ignored){org.slf4j.LoggerFactory.getLogger(Conversations.class).error("conversation_persistence_failed run_id={} correlation_id={}",run,correlation);}
    throw failure;
   }
   return outcome(u,t,a,c,request);
  }finally{admission.release();}
 }
}
