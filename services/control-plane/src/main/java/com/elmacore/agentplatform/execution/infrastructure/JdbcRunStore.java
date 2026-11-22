package com.elmacore.agentplatform.execution.infrastructure;

import com.elmacore.agentplatform.execution.application.RunStore;
import com.elmacore.agentplatform.execution.domain.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.util.*;

public final class JdbcRunStore implements RunStore {
    private final JdbcTemplate jdbc;
    private final DataSource source;
    private final ObjectMapper mapper;
    public JdbcRunStore(DataSource source,ObjectMapper mapper){this.source=source;this.mapper=mapper;jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(3);}
    private void transaction(){
        if(!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.hasResource(source))
            throw new IllegalStateException("Runs require tenant transaction");
    }
    @Override public void admit(UUID tenant) {
        transaction();
        var rows=jdbc.queryForList("""
            INSERT INTO platform.agent_run_daily_quota(tenant_id,utc_day,attempts)
            VALUES(?,(CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date,1)
            ON CONFLICT(tenant_id,utc_day) DO UPDATE
            SET attempts=agent_run_daily_quota.attempts+1 WHERE agent_run_daily_quota.attempts<20
            RETURNING attempts
            """,Integer.class,tenant);
        if(rows.isEmpty()) throw new RunFailure("DAILY_EXECUTION_LIMIT",429);
    }
    @Override public void insert(UUID tenant,UUID agent,UUID user,UUID run,String question) {
        transaction();jdbc.update("INSERT INTO platform.agent_runs(id,tenant_id,agent_id,user_id,question,status) VALUES(?,?,?,?,?,'RUNNING')",run,tenant,agent,user,question);
    }
    @Override public RunResult get(UUID tenant,UUID agent,UUID user,UUID run) {
        transaction();
        var rows=jdbc.query("SELECT * FROM platform.agent_runs WHERE tenant_id=? AND agent_id=? AND user_id=? AND id=?",(r,n)-> {
            try {
                List<RunResult.Citation> citations=mapper.readValue(r.getString("citations"),new TypeReference<List<RunResult.Citation>>(){});
                return new RunResult(r.getObject("id",UUID.class),r.getObject("agent_id",UUID.class),r.getString("status"),r.getString("answer"),citations,
                    new RunResult.Usage((Integer)r.getObject("input_tokens"),(Integer)r.getObject("output_tokens")),(Long)r.getObject("duration_ms"),r.getString("failure_code"),r.getString("model_id"));
            } catch(java.io.IOException error){throw new IllegalStateException("Invalid persisted run");}
        },tenant,agent,user,run);
        if(rows.isEmpty())throw new RunFailure("RUN_NOT_FOUND",404);
        return rows.getFirst();
    }
    @Override public boolean finish(UUID tenant,UUID agent,UUID user,RunResult result) {
        transaction();String citations;
        try {citations=mapper.writeValueAsString(result.citations());}catch(java.io.IOException error){throw new IllegalStateException("Invalid citations");}
        boolean updated=jdbc.update("""
            UPDATE platform.agent_runs SET status=?,answer=?,citations=?::jsonb,failure_code=?,model_id=?,
            input_tokens=?,output_tokens=?,duration_ms=?,completed_at=GREATEST(clock_timestamp(),created_at)
            WHERE tenant_id=? AND agent_id=? AND user_id=? AND id=? AND status='RUNNING'
              AND created_at>clock_timestamp()-interval '180 seconds'
            """,result.status(),result.answer(),citations,result.failureCode(),result.modelId(),result.usage().inputTokens(),result.usage().outputTokens(),result.durationMs(),tenant,agent,user,result.runId())==1;
        if(updated) org.slf4j.LoggerFactory.getLogger(JdbcRunStore.class).info(
            "execution_terminal run_id={} correlation_id={} status={} model={} duration_ms={} input_tokens={} output_tokens={} failure_code={}",
            result.runId(),org.slf4j.MDC.get("correlation_id"),result.status(),result.modelId(),result.durationMs(),result.usage().inputTokens(),result.usage().outputTokens(),result.failureCode());
        return updated;
    }
    @Override public void recover(UUID tenant,UUID agent,UUID user) {
        transaction();
        jdbc.update("""
            UPDATE platform.agent_runs SET status='FAILED',failure_code='EXECUTION_INTERRUPTED',
              duration_ms=GREATEST(0,(EXTRACT(EPOCH FROM (clock_timestamp()-created_at))*1000)::bigint),
              completed_at=GREATEST(clock_timestamp(),created_at)
            WHERE tenant_id=? AND agent_id=? AND user_id=? AND status='RUNNING'
              AND created_at<=clock_timestamp()-interval '180 seconds'
            """,tenant,agent,user);
    }
}
