package com.elmacore.agentplatform.conversation.infrastructure;
import com.elmacore.agentplatform.conversation.application.*;
import com.elmacore.agentplatform.conversation.domain.ConversationRecords.*;
import com.elmacore.agentplatform.execution.application.RunStore;
import com.elmacore.agentplatform.execution.domain.RunFailure;
import com.elmacore.agentplatform.execution.domain.RunResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
public final class JdbcConversationStore implements ConversationStore {
 private final JdbcTemplate jdbc;private final DataSource source;private final RunStore runs;private final ObjectMapper mapper;
 public JdbcConversationStore(DataSource source,RunStore runs,ObjectMapper mapper){this.source=source;this.runs=runs;this.mapper=mapper;jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(3);jdbc.setExceptionTranslator((task,sql,error)->{String m=error.getMessage();for(String code:List.of("conversation_limit","conversation_read_only","conversation_busy","conversation_turn_limit","conversation_invalid_turn"))if("P0001".equals(error.getSQLState())&&m!=null&&(m.equals("ERROR: "+code)||m.startsWith("ERROR: "+code+"\n")))throw new RunFailure(code,409);throw new RunFailure("conversation_persistence_failure",500);});}
 private void bound(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.hasResource(source))throw new IllegalStateException("Conversation requires tenant transaction");}
 private <T>List<T> query(String sql,RowMapper<T> row,Object...args){bound();return jdbc.query(c->{var p=c.prepareStatement(sql);for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;},row);}
 private int update(String sql,Object...args){bound();return jdbc.update(c->{var p=c.prepareStatement(sql);for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;});}
 private static final RowMapper<Conversation> CONVERSATION=(r,n)->new Conversation(r.getObject("id",UUID.class),r.getObject("tenant_id",UUID.class),r.getObject("agent_id",UUID.class),r.getObject("created_by",UUID.class),r.getString("title"),r.getString("status"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());
 public void context(UUID user){query("SELECT set_config('app.user_id',?,true)",(r,n)->r.getString(1),user.toString());}
 public Conversation create(UUID tenant,UUID agent,UUID user,String title){UUID id=UUID.randomUUID();query("INSERT INTO platform.conversations(id,tenant_id,agent_id,created_by,title) VALUES(?,?,?,?,?) RETURNING *",CONVERSATION,id,tenant,agent,user,title);return get(tenant,agent,user,id,false);}
 public Conversation get(UUID tenant,UUID agent,UUID user,UUID id,boolean lock){
  var rows=query("SELECT * FROM platform.conversations WHERE tenant_id=? AND agent_id=? AND created_by=? AND id=?"+(lock?" FOR UPDATE":""),CONVERSATION,tenant,agent,user,id);
  if(!rows.isEmpty())return rows.getFirst();
  // PostgreSQL applies UPDATE policy visibility to locking reads. A closed owned
  // conversation remains readable, but must not become an apparent missing row.
  if(lock&&get(tenant,agent,user,id,false).status().equals("CLOSED"))throw new RunFailure("conversation_read_only",409);
  throw new RunFailure("conversation_not_found",404);
 }

 public List<Conversation> list(UUID tenant,UUID agent,UUID user,int limit,long offset){return query("SELECT * FROM platform.conversations WHERE tenant_id=? AND agent_id=? AND created_by=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",CONVERSATION,tenant,agent,user,limit,offset);}
 private void idle(UUID tenant,UUID id){if(!query("SELECT 1 FROM platform.conversation_turns t JOIN platform.agent_runs r ON r.id=t.run_id WHERE t.tenant_id=? AND t.conversation_id=? AND r.status='RUNNING' LIMIT 1",(r,n)->1,tenant,id).isEmpty())throw new RunFailure("conversation_busy",409);}
 public Conversation change(UUID tenant,UUID agent,UUID user,UUID id,String title,boolean close){var c=get(tenant,agent,user,id,true);if(!c.status().equals("OPEN"))throw new RunFailure("conversation_read_only",409);if(close)idle(tenant,id);update("UPDATE platform.conversations SET title=?,status=?,updated_at=greatest(clock_timestamp(),updated_at+interval '1 microsecond') WHERE tenant_id=? AND id=? AND status='OPEN'",close?c.title():title,close?"CLOSED":"OPEN",tenant,id);return get(tenant,agent,user,id,false);}
 public Reservation find(UUID tenant,UUID id,UUID request){return query("SELECT t.run_id,r.question FROM platform.conversation_turns t JOIN platform.agent_runs r ON r.id=t.run_id WHERE t.tenant_id=? AND t.conversation_id=? AND t.request_id=?",(r,n)->new Reservation(request,r.getObject(1,UUID.class),r.getString(2),false),tenant,id,request).stream().findFirst().orElse(null);}
 public Reservation reserve(UUID tenant,UUID agent,UUID user,UUID id,UUID request,String question){
  var c=get(tenant,agent,user,id,true);var previous=find(tenant,id,request);if(previous!=null){if(!previous.question().equals(question))throw new RunFailure("conversation_request_conflict",409);return previous;}
  if(!c.status().equals("OPEN"))throw new RunFailure("conversation_read_only",409);idle(tenant,id);
  int ordinal=query("SELECT coalesce(max(ordinal),0)+1 FROM platform.conversation_turns WHERE tenant_id=? AND conversation_id=?",(r,n)->r.getInt(1),tenant,id).getFirst();if(ordinal>100)throw new RunFailure("conversation_turn_limit",409);
  UUID run=UUID.randomUUID();runs.admit(tenant);runs.insert(tenant,agent,user,run,question);
  update("INSERT INTO platform.conversation_turns(tenant_id,agent_id,created_by,conversation_id,request_id,ordinal,run_id,user_message_id,assistant_message_id) VALUES(?,?,?,?,?,?,?,?,?)",tenant,agent,user,id,request,ordinal,run,UUID.randomUUID(),UUID.randomUUID());
  update("UPDATE platform.conversations SET updated_at=greatest(clock_timestamp(),updated_at+interval '1 microsecond') WHERE tenant_id=? AND id=? AND status='OPEN'",tenant,id);
  return new Reservation(request,run,question,true);
 }
 public List<ConversationRuntime.History> history(UUID tenant,UUID id){
  var rows=query("SELECT r.question,r.answer FROM platform.conversation_turns t JOIN platform.agent_runs r ON r.id=t.run_id WHERE t.tenant_id=? AND t.conversation_id=? AND r.status='SUCCEEDED' ORDER BY t.ordinal DESC LIMIT 6",(r,n)->new ConversationRuntime.History(r.getString(1),r.getString(2)),tenant,id);
  var chosen=new ArrayList<ConversationRuntime.History>();int remaining=8000;
  for(var h:rows){int size=h.question().codePointCount(0,h.question().length())+h.answer().codePointCount(0,h.answer().length());if(size>remaining)break;remaining-=size;chosen.add(h);}Collections.reverse(chosen);return List.copyOf(chosen);
 }
 public List<Message> messages(UUID tenant,UUID id,int limit,long offset){
  return query("""
   SELECT CASE WHEN v.role='USER' THEN t.user_message_id ELSE t.assistant_message_id END AS message_id,t.request_id,
   t.ordinal*2-CASE WHEN v.role='USER' THEN 1 ELSE 0 END AS message_ordinal,v.role,
   CASE WHEN v.role='USER' THEN r.question ELSE r.answer END AS content,
   CASE WHEN v.role='USER' THEN 'SUCCEEDED' ELSE r.status END AS status,
   CASE WHEN v.role='USER' THEN '[]'::jsonb ELSE r.citations END AS citations,t.run_id,
   CASE WHEN v.role='USER' THEN t.created_at ELSE coalesce(r.completed_at,t.created_at) END AS message_created_at
   FROM platform.conversation_turns t JOIN platform.agent_runs r ON r.id=t.run_id
   CROSS JOIN (VALUES ('USER'),('ASSISTANT')) v(role)
   WHERE t.tenant_id=? AND t.conversation_id=? ORDER BY t.ordinal,v.role DESC LIMIT ? OFFSET ?
   """,(r,n)->{try{return new Message(r.getObject("message_id",UUID.class),r.getObject("request_id",UUID.class),r.getLong("message_ordinal"),r.getString("role"),r.getString("content"),r.getString("status"),mapper.readValue(r.getString("citations"),new TypeReference<List<RunResult.Citation>>(){}),r.getObject("run_id",UUID.class),r.getTimestamp("message_created_at").toInstant());}catch(java.io.IOException e){throw new IllegalStateException("Invalid conversation citations");}},tenant,id,limit,offset);
 }
}
