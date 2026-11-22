package com.elmacore.agentplatform.conversation.infrastructure;
import com.elmacore.agentplatform.conversation.application.*;
import com.elmacore.agentplatform.agent.application.AgentStore;
import com.elmacore.agentplatform.execution.application.*;
import com.elmacore.agentplatform.identity.application.MembershipAuthorization;
import com.elmacore.agentplatform.knowledge.application.Knowledge;
import com.elmacore.agentplatform.tenancy.application.TenantTransactions;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
@Configuration(proxyBeanMethods=false)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class ConversationConfiguration {
 @Bean ConversationStore conversationStore(DataSource source,RunStore runs,ObjectMapper mapper){return new JdbcConversationStore(source,runs,mapper);}
 @Bean Conversations conversations(MembershipAuthorization auth,TenantTransactions tx,AgentStore agents,ConversationStore store,RunStore runs,Knowledge knowledge,ConversationRuntime runtime,ExecutionAdmission admission){return new Conversations(auth,tx,agents,store,runs,knowledge,runtime,admission);}
}
