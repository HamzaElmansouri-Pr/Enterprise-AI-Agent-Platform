package com.elmacore.agentplatform.tool.infrastructure;

import com.elmacore.agentplatform.tool.application.*;
import com.elmacore.agentplatform.execution.application.*;
import com.elmacore.agentplatform.agent.application.AgentStore;
import com.elmacore.agentplatform.identity.application.MembershipAuthorization;
import com.elmacore.agentplatform.tenancy.application.TenantTransactions;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods=false)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class ToolWiring {
    @Bean JdbcProposalStore proposalStore(DataSource source,ObjectMapper mapper){return new JdbcProposalStore(source,mapper);}
    @Bean ToolRegistry toolRegistry(JdbcProposalStore store){return new ToolRegistry(store::createFollowup);}
    @Bean ProposalRuntime proposalRuntime(Environment env,ObjectMapper mapper){return new HttpProposalRuntime(env.getProperty("EXECUTION_RUNTIME_URL","http://agent-runtime:8000"),env.getProperty("EXECUTION_INTERNAL_SECRET",""),mapper);}
    @Bean ToolGateway toolGateway(MembershipAuthorization auth,TenantTransactions tx,AgentStore agents,ProposalStore store,ToolRegistry registry,ProposalRuntime runtime,RunStore quota,ExecutionAdmission admission){return new ToolGateway(auth,tx,agents,store,registry,runtime,quota,admission);}
}
