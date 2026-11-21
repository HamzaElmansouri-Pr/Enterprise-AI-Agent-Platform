package com.elmacore.agentplatform.agent.infrastructure;

import com.elmacore.agentplatform.agent.application.AgentConfigurations;
import com.elmacore.agentplatform.agent.application.AgentStore;
import com.elmacore.agentplatform.agent.infrastructure.persistence.JdbcAgentStore;
import com.elmacore.agentplatform.identity.application.MembershipAuthorization;
import com.elmacore.agentplatform.infrastructure.persistence.JdbcTenantTransactions;
import com.elmacore.agentplatform.tenancy.application.TenantTransactions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import javax.sql.DataSource;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AgentConfigurationWiring {
    @Bean TenantTransactions tenantTransactions(DataSource source) { return new JdbcTenantTransactions(source); }
    @Bean AgentStore agentStore(DataSource source) { return new JdbcAgentStore(source); }
    @Bean AgentConfigurations agents(MembershipAuthorization authorization, TenantTransactions transactions, AgentStore store) {
        return new AgentConfigurations(authorization, transactions, store, Clock.systemUTC());
    }
}
