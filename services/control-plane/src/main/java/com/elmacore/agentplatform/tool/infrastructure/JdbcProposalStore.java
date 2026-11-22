package com.elmacore.agentplatform.tool.infrastructure;

import com.elmacore.agentplatform.tool.application.*;
import com.elmacore.agentplatform.tool.domain.*;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;

public final class JdbcProposalStore implements ProposalStore {
    private final DataSource source;private final JdbcTemplate jdbc;private final ObjectMapper mapper;
    private static final String SELECT="""
        SELECT p.*,a.id AS decision_id,a.approver_id,a.payload_digest AS approved_digest,a.decision,a.reason,a.decided_at
        FROM platform.tool_proposals p LEFT JOIN platform.tool_approval_decisions a ON a.tenant_id=p.tenant_id AND a.proposal_id=p.id
        WHERE p.tenant_id=?
        """;
    public JdbcProposalStore(DataSource source,ObjectMapper mapper){this.source=source;this.mapper=mapper;jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(3);}
    private void transaction(){if(!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.hasResource(source))throw new IllegalStateException("Tool gateway requires tenant transaction");}
    private static java.time.Instant instant(ResultSet r,String column)throws SQLException {var value=r.getTimestamp(column);return value==null?null:value.toInstant();}
    private Proposal row(ResultSet r,int n)throws SQLException {
        try {
            var args=mapper.readValue(r.getString("arguments"),ToolPayload.Arguments.class);
            UUID decision=r.getObject("decision_id",UUID.class);
            return new Proposal(r.getObject("id",UUID.class),r.getObject("tenant_id",UUID.class),r.getObject("agent_id",UUID.class),r.getObject("created_by",UUID.class),
                new ToolPayload(r.getString("tool_name"),args,r.getString("rationale")),r.getInt("tool_version"),r.getString("payload_digest"),Proposal.Status.valueOf(r.getString("status")),instant(r,"created_at"),
                decision==null?null:new Proposal.Decision(decision,r.getObject("approver_id",UUID.class),r.getString("approved_digest"),r.getString("decision"),r.getString("reason"),instant(r,"decided_at")),
                r.getObject("executed_by",UUID.class),r.getObject("task_id",UUID.class),r.getString("failure_code"),instant(r,"started_at"),instant(r,"completed_at"));
        }catch(tools.jackson.core.JacksonException error){throw new IllegalStateException("Invalid stored tool payload");}
    }
    @Override public Proposal insert(UUID tenant,UUID agent,UUID creator,UUID id,ToolPayload payload,String digest,UUID correlation) {
        transaction();String args;
        try{args=mapper.writeValueAsString(payload.arguments());}catch(tools.jackson.core.JacksonException error){throw new IllegalArgumentException("Invalid tool arguments");}
        jdbc.update("INSERT INTO platform.tool_proposals(id,tenant_id,agent_id,created_by,tool_name,tool_version,arguments,rationale,payload_digest,correlation_id) VALUES(?,?,?,?,?,1,?::jsonb,?,?,?)",id,tenant,agent,creator,payload.toolName(),args,payload.rationale(),digest,correlation);
        return get(tenant,id,null,false);
    }
    @Override public Proposal get(UUID tenant,UUID id,UUID creator,boolean lock){
        transaction();var args=new ArrayList<Object>(List.of(tenant,id));String sql=SELECT+" AND p.id=?";
        if(creator!=null){sql+=" AND p.created_by=?";args.add(creator);}if(lock)sql+=" FOR UPDATE OF p";
        var rows=jdbc.query(sql,this::row,args.toArray());
        if(rows.isEmpty() && lock) {
            // UPDATE RLS deliberately hides terminal rows from FOR UPDATE. They are
            // immutable, so replay/status checks may safely read them without a lock.
            var terminal=get(tenant,id,creator,false);
            if(java.util.Set.of(Proposal.Status.REJECTED,Proposal.Status.SUCCEEDED,Proposal.Status.FAILED).contains(terminal.status()))return terminal;
            throw new ToolFailure("proposal_busy",409);
        }
        if(rows.isEmpty())throw new ToolFailure("proposal_not_found",404);return rows.getFirst();
    }
    @Override public List<Proposal> list(UUID tenant,UUID creator,int limit,long offset){
        transaction();var args=new ArrayList<Object>(List.of(tenant));String sql=SELECT;
        if(creator!=null){sql+=" AND p.created_by=?";args.add(creator);}args.add(limit);args.add(offset);
        return jdbc.query(sql+" ORDER BY p.created_at DESC,p.id DESC LIMIT ? OFFSET ?",this::row,args.toArray());
    }
    @Override public void decide(UUID tenant,Proposal proposal,UUID actor,boolean approve,String reason,UUID correlation){
        transaction();String decision=approve?"APPROVED":"REJECTED";
        jdbc.update("INSERT INTO platform.tool_approval_decisions(id,tenant_id,proposal_id,proposal_created_by,approver_id,payload_digest,decision,reason,correlation_id) VALUES(?,?,?,?,?,?,?,?,?)",UUID.randomUUID(),tenant,proposal.id(),proposal.createdBy(),actor,proposal.payloadDigest(),decision,reason,correlation);
        if(jdbc.update("UPDATE platform.tool_proposals SET status=? WHERE tenant_id=? AND id=? AND status='PENDING_APPROVAL'",decision,tenant,proposal.id())!=1)throw new ToolFailure("proposal_not_pending",409);
    }
    @Override public void claim(UUID tenant,UUID id,UUID actor,UUID correlation){
        transaction();if(jdbc.update("UPDATE platform.tool_proposals p SET status='EXECUTING',executed_by=?,execution_correlation_id=?,started_at=GREATEST(clock_timestamp(),created_at) WHERE p.tenant_id=? AND p.id=? AND p.status='APPROVED' AND NOT EXISTS (SELECT 1 FROM platform.admissions_followup_tasks t WHERE t.tenant_id=p.tenant_id AND t.source_proposal_id=p.id)",actor,correlation,tenant,id)!=1)throw new ToolFailure("proposal_not_approved",409);
    }
    @Override public Effect attempt(Supplier<UUID> handler){
        transaction();return jdbc.execute((ConnectionCallback<Effect>)c->{
            Savepoint savepoint=c.setSavepoint();
            try {UUID task=handler.get();c.releaseSavepoint(savepoint);return new Effect(task,null);}
            catch(RuntimeException error){c.rollback(savepoint);c.releaseSavepoint(savepoint);return new Effect(null,"TOOL_EXECUTION_FAILED");}
        });
    }
    @Override public void finish(UUID tenant,UUID id,Effect effect){
        transaction();if(jdbc.update("UPDATE platform.tool_proposals SET status=?,task_id=?,failure_code=?,completed_at=GREATEST(clock_timestamp(),started_at) WHERE tenant_id=? AND id=? AND status='EXECUTING'",effect.failureCode()==null?"SUCCEEDED":"FAILED",effect.taskId(),effect.failureCode(),tenant,id)!=1)throw new ToolFailure("proposal_not_executing",409);
    }
    @Override public List<FollowupTask> tasks(UUID tenant,int limit,long offset){
        transaction();return jdbc.query("SELECT * FROM platform.admissions_followup_tasks WHERE tenant_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",(r,n)->new FollowupTask(r.getObject("id",UUID.class),r.getObject("tenant_id",UUID.class),r.getObject("source_proposal_id",UUID.class),r.getObject("created_by",UUID.class),r.getString("title"),r.getString("description"),ToolPayload.Priority.valueOf(r.getString("priority")),r.getString("status"),instant(r,"created_at")),tenant,limit,offset);
    }
    public UUID createFollowup(Proposal proposal,UUID actor){
        transaction();UUID id=UUID.randomUUID();var args=proposal.payload().arguments();
        jdbc.update("INSERT INTO platform.admissions_followup_tasks(id,tenant_id,source_proposal_id,created_by,title,description,priority) VALUES(?,?,?,?,?,?,?)",id,proposal.tenantId(),proposal.id(),actor,args.title(),args.description(),args.priority().name());return id;
    }
}
