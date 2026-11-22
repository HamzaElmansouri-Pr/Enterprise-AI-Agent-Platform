package com.elmacore.agentplatform.knowledge.infrastructure;

import com.elmacore.agentplatform.knowledge.application.*;
import com.elmacore.agentplatform.identity.application.MembershipAuthorization;
import com.elmacore.agentplatform.tenancy.application.TenantTransactions;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import javax.sql.DataSource;

@Configuration(proxyBeanMethods=false)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class KnowledgeWiring {
    @Bean KnowledgeStore knowledgeStore(DataSource source) {return new JdbcKnowledgeStore(source);}
    @Bean KnowledgeJobs knowledgeJobs(DataSource source) {return new JdbcKnowledgeJobs(source);}
    @Bean DocumentExtraction documentExtraction() {return new PdfBoxExtraction();}
    @Bean @ConditionalOnMissingBean(Embeddings.class) Embeddings embeddings(@Value("${EMBEDDING_URL:http://agent-runtime:8000}")String url,@Value("${EMBEDDING_SECRET:}")String secret,ObjectMapper mapper) {return new HttpEmbeddings(url,secret,mapper);}
    @Bean Knowledge knowledge(MembershipAuthorization auth,TenantTransactions transactions,KnowledgeStore store,Embeddings embeddings) {return new Knowledge(auth,transactions,store,embeddings);}
    @Bean KnowledgeProcessor processor(KnowledgeJobs jobs,KnowledgeStore store,DocumentExtraction extraction,Embeddings embeddings) {return new KnowledgeProcessor(jobs,store,extraction,embeddings);}
    @Bean(destroyMethod="close")
    @ConditionalOnProperty(name="knowledge.worker.enabled",havingValue="true")
    KnowledgeWorker knowledgeWorker(KnowledgeProcessor processor) {return new KnowledgeWorker(processor);}
}
