package com.elmacore.agentplatform.execution.infrastructure;

import com.elmacore.agentplatform.execution.application.*;
import com.elmacore.agentplatform.agent.application.AgentStore;
import com.elmacore.agentplatform.identity.application.MembershipAuthorization;
import com.elmacore.agentplatform.knowledge.application.Knowledge;
import com.elmacore.agentplatform.tenancy.application.TenantTransactions;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods=false)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class ExecutionWiring {
    @Bean ExecutionAdmission executionAdmission(){return new ExecutionAdmission();}
    @Bean RunStore runStore(DataSource source,ObjectMapper mapper){return new JdbcRunStore(source,mapper);}
    @Bean HttpExecutionRuntime executionRuntime(Environment env,ObjectMapper mapper) {
        String secret=env.getProperty("EXECUTION_INTERNAL_SECRET","");
        if(!secret.isEmpty() && secret.equals(env.getProperty("EMBEDDING_SECRET")))throw new IllegalArgumentException("Dedicated execution credential required");
        return new HttpExecutionRuntime(env.getProperty("EXECUTION_RUNTIME_URL","http://agent-runtime:8000"),secret,mapper);
    }
    @Bean AgentRuns agentRuns(MembershipAuthorization auth,TenantTransactions tx,AgentStore agents,Knowledge knowledge,RunStore store,ExecutionRuntime runtime,ExecutionAdmission admission) {
        return new AgentRuns(auth,tx,agents,knowledge,store,runtime,admission);
    }
}
