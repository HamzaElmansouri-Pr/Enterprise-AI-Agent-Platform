package com.elmacore.agentplatform.agent;

import com.elmacore.agentplatform.tool.application.*;
import com.elmacore.agentplatform.tool.domain.*;
import com.elmacore.agentplatform.tool.infrastructure.*;
import com.elmacore.agentplatform.execution.application.*;
import com.elmacore.agentplatform.execution.domain.*;
import com.elmacore.agentplatform.identity.application.MembershipAuthorization;
import com.elmacore.agentplatform.identity.infrastructure.JwtTokens;
import com.elmacore.agentplatform.tenancy.application.TenantTransactions;
import com.elmacore.agentplatform.infrastructure.persistence.PostgresTenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.elmacore.agentplatform.agent.ToolTestDatabase.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(ToolGatewayIT.Fixtures.class)
@ExtendWith(OutputCaptureExtension.class)
class ToolGatewayIT {
    @Container static final PostgreSQLContainer<?> DB=container();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry props){configure(DB,props);}
    @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired JwtTokens tokens;
    @Autowired ToolGateway gateway;@Autowired ProposalStore store;@Autowired JdbcProposalStore jdbcStore;
    @Autowired FakeRuntime runtime;@Autowired Effects effects;@Autowired ExecutionAdmission admission;
    @Autowired DataSource source;@Autowired TenantTransactions tx;@Autowired MembershipAuthorization auth;
    static final ToolPayload PAYLOAD=new ToolPayload(ToolPayload.NAME,new ToolPayload.Arguments("Review admission documents","private-followup-description",ToolPayload.Priority.NORMAL),"private-proposal-rationale");
    @BeforeEach void seed()throws Exception {
        runtime.calls.set(0);runtime.failure=null;runtime.model=ExecutionRuntime.MODEL;effects.fail.set(false);effects.sqlFail.set(false);
        try(var c=admin(DB);var s=c.createStatement()) {
            s.execute("TRUNCATE platform.conversation_turns,platform.conversations,platform.tenant_access_audit,platform.tenant_invitations,platform.admissions_followup_tasks,platform.tool_approval_decisions,platform.tool_proposals,platform.agent_runs,platform.agent_run_daily_quota,platform.knowledge_chunks,platform.agent_documents,platform.knowledge_content,platform.knowledge_documents,platform.agent_configurations,platform.tenant_memberships,platform.users,platform.tenants");
            try(var p=c.prepareStatement("INSERT INTO platform.tenants(id,name) VALUES(?,'A'),(?,'B')")){p.setObject(1,A);p.setObject(2,B);p.executeUpdate();}
            for(UUID u:List.of(OWNER,ADMIN,MEMBER,B_OWNER,OUTSIDER)) {
                try(var p=c.prepareStatement("INSERT INTO platform.users(id,email,password_hash) VALUES(?,?,'$argon2id$test-only-unused')")){p.setObject(1,u);p.setString(2,"u"+u+"@example.test");p.executeUpdate();}
            }
            for(var row:List.of(new Object[]{OWNER,A,"OWNER"},new Object[]{ADMIN,A,"ADMIN"},new Object[]{MEMBER,A,"MEMBER"},new Object[]{B_OWNER,B,"OWNER"})) {
                try(var p=c.prepareStatement("INSERT INTO platform.tenant_memberships(user_id,tenant_id,role) VALUES(?,?,?)")){p.setObject(1,row[0]);p.setObject(2,row[1]);p.setString(3,(String)row[2]);p.executeUpdate();}
            }
            for(var row:List.of(new Object[]{AGENT_A,A,"DRAFT"},new Object[]{AGENT_B,B,"DRAFT"},new Object[]{ARCHIVED_A,A,"ARCHIVED"})) {
                try(var p=c.prepareStatement("INSERT INTO platform.agent_configurations(id,tenant_id,name,system_instructions,status) VALUES(?,?,'Agent','Stored instructions',?)")){p.setObject(1,row[0]);p.setObject(2,row[1]);p.setString(3,(String)row[2]);p.executeUpdate();}
            }
        }
    }
    @AfterAll static void cleanup()throws Exception {deleteKeys();}
    String root(UUID tenant){return "/api/v1/tenants/"+tenant;}
    String path(UUID tenant,UUID id){return root(tenant)+"/tool-proposals/"+id;}
    String bearer(UUID user){return "Bearer "+tokens.issue(user);}
    Proposal manual(UUID user){return gateway.submit(user,A,AGENT_A,PAYLOAD,UUID.randomUUID());}
    Proposal approved(){var p=manual(OWNER);return gateway.decide(ADMIN,A,p.id(),p.payloadDigest(),true,null,UUID.randomUUID());}
    int count(String table)throws Exception {try(var c=admin(DB);var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM platform."+table)){r.next();return r.getInt(1);}}
    int attempts(){return tx.execute(auth.authorize(OWNER,A),()->new JdbcTemplate(source).queryForObject("SELECT coalesce(sum(attempts),0)::integer FROM platform.agent_run_daily_quota WHERE tenant_id=?",Integer.class,A));}
    ResultActions postAs(UUID user,String path,String body)throws Exception{return mvc.perform(post(path).header("Authorization",bearer(user)).contentType("application/json").content(body));}
    String digestBody(Proposal p)throws Exception{return mapper.writeValueAsString(Map.of("expectedPayloadDigest",p.payloadDigest()));}
    @Test void manualSubmissionAndApprovalHaveNoEffectAndExecuteCreatesOneRealTask(CapturedOutput output)throws Exception {
        String route=root(A)+"/agents/"+AGENT_A+"/tool-proposals";
        var response=postAs(OWNER,route,mapper.writeValueAsString(PAYLOAD)).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING_APPROVAL")).andExpect(jsonPath("$.toolName").value(ToolPayload.NAME)).andReturn();
        UUID id=UUID.fromString(mapper.readTree(response.getResponse().getContentAsString()).path("id").asText());var p=gateway.get(ADMIN,A,id);
        assertThat(count("admissions_followup_tasks")).isZero();
        postAs(ADMIN,path(A,id)+"/approve",digestBody(p)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(count("admissions_followup_tasks")).isZero();
        var executed=postAs(OWNER,path(A,id)+"/execute","").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED")).andReturn();
        UUID task=UUID.fromString(mapper.readTree(executed.getResponse().getContentAsString()).path("taskId").asText());
        postAs(ADMIN,path(A,id)+"/execute","").andExpect(status().isOk()).andExpect(jsonPath("$.taskId").value(task.toString()));
        var tasks=gateway.tasks(MEMBER,A,0,20);assertThat(tasks.items()).hasSize(1);
        var real=tasks.items().getFirst();assertThat(real.id()).isEqualTo(task);assertThat(real.sourceProposalId()).isEqualTo(id);assertThat(real.createdBy()).isEqualTo(OWNER);assertThat(real.status()).isEqualTo("OPEN");
        assertThat(real.title()).isEqualTo(PAYLOAD.arguments().title());assertThat(real.description()).isEqualTo(PAYLOAD.arguments().description());
        assertThat(count("tool_approval_decisions")).isEqualTo(1);assertThat(count("admissions_followup_tasks")).isEqualTo(1);assertThat(runtime.calls.get()).isZero();assertThat(attempts()).isZero();
        assertThat(output.getAll()).doesNotContain(PAYLOAD.arguments().description(),PAYLOAD.rationale());
    }
    @Test void onlyManagersSubmitManualAndSelfApprovalNeverSucceeds()throws Exception {
        var owner=manual(OWNER);var admin=manual(ADMIN);
        assertThatThrownBy(()->gateway.decide(OWNER,A,owner.id(),owner.payloadDigest(),true,null,UUID.randomUUID())).isInstanceOf(ToolFailure.class).hasMessage("self_approval_forbidden");
        postAs(ADMIN,path(A,admin.id())+"/approve",digestBody(admin)).andExpect(status().isForbidden());
        postAs(MEMBER,root(A)+"/agents/"+AGENT_A+"/tool-proposals",mapper.writeValueAsString(PAYLOAD)).andExpect(status().isForbidden());
        assertThat(count("tool_approval_decisions")).isZero();assertThat(count("admissions_followup_tasks")).isZero();
    }
    @Test void memberGeneratedProposalIsPendingAndOnlyOwnProposalsAreVisible()throws Exception {
        var p=gateway.generate(MEMBER,A,AGENT_A,"Review the application",UUID.randomUUID());manual(OWNER);
        assertThat(p.status()).isEqualTo(Proposal.Status.PENDING_APPROVAL);assertThat(p.createdBy()).isEqualTo(MEMBER);
        assertThat(runtime.calls.get()).isEqualTo(1);assertThat(attempts()).isEqualTo(1);assertThat(count("admissions_followup_tasks")).isZero();
        mvc.perform(get(root(A)+"/tool-proposals").header("Authorization",bearer(MEMBER))).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        assertThat(gateway.list(ADMIN,A,0,20).items()).hasSize(2);
        var other=manual(OWNER);mvc.perform(get(path(A,other.id())).header("Authorization",bearer(MEMBER))).andExpect(status().isNotFound());
        postAs(MEMBER,path(A,p.id())+"/approve",digestBody(p)).andExpect(status().isForbidden());postAs(MEMBER,path(A,p.id())+"/execute","").andExpect(status().isForbidden());
    }
    @Test void rejectionIsTerminalWithReasonAndNoMutation()throws Exception {
        var p=manual(OWNER);
        postAs(ADMIN,path(A,p.id())+"/reject",mapper.writeValueAsString(Map.of("expectedPayloadDigest",p.payloadDigest(),"reason","Needs correction"))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        postAs(ADMIN,path(A,p.id())+"/execute","").andExpect(status().isConflict());postAs(ADMIN,path(A,p.id())+"/approve",digestBody(p)).andExpect(status().isConflict());
        assertThat(gateway.get(OWNER,A,p.id()).decision().reason()).isEqualTo("Needs correction");assertThat(count("admissions_followup_tasks")).isZero();
    }
    @Test void digestIsExactAndBoundToProposalTenantAgentCreator()throws Exception {
        var p=manual(OWNER);var same=manual(OWNER);assertThat(p.payloadDigest()).isNotEqualTo(same.payloadDigest());
        postAs(ADMIN,path(A,p.id())+"/approve",digestBody(same)).andExpect(status().isConflict());
        assertThat(count("tool_approval_decisions")).isZero();
        assertThat(Proposal.digest(p.id(),B,p.agentId(),p.createdBy(),p.payload(),1)).isNotEqualTo(p.payloadDigest());
        assertThat(Proposal.digest(p.id(),A,p.agentId(),ADMIN,p.payload(),1)).isNotEqualTo(p.payloadDigest());
        assertThat(Proposal.digest(p.id(),A,p.agentId(),p.createdBy(),new ToolPayload(ToolPayload.NAME,new ToolPayload.Arguments("Review admission documents ",PAYLOAD.arguments().description(),ToolPayload.Priority.NORMAL),PAYLOAD.rationale()),1)).isNotEqualTo(p.payloadDigest());
    }
    @Test void crossTenantReplayAndCurrentMembershipProtectAllOperations()throws Exception {
        var p=manual(OWNER);
        mvc.perform(get(path(B,p.id())).header("Authorization",bearer(B_OWNER))).andExpect(status().isNotFound());
        postAs(B_OWNER,path(B,p.id())+"/approve",digestBody(p)).andExpect(status().isNotFound());postAs(B_OWNER,path(B,p.id())+"/execute","").andExpect(status().isNotFound());
        postAs(OUTSIDER,path(A,p.id())+"/approve",digestBody(p)).andExpect(status().isForbidden());
        String token=bearer(ADMIN);try(var c=admin(DB);var s=c.createStatement()){s.execute("DELETE FROM platform.tenant_memberships WHERE user_id='"+ADMIN+"'");}
        mvc.perform(post(path(A,p.id())+"/approve").header("Authorization",token).contentType("application/json").content(digestBody(p))).andExpect(status().isForbidden());
        try(var c=admin(DB);var s=c.createStatement()){s.execute("UPDATE platform.users SET enabled=false WHERE id='"+ADMIN+"'");}
        mvc.perform(get(path(A,p.id())).header("Authorization",token)).andExpect(status().isUnauthorized());
    }
    @Test void validApprovalDoesNotPreserveExecutorPrivilegesAfterRevocation()throws Exception {
        var p=approved();String token=bearer(OWNER);
        try(var c=admin(DB);var s=c.createStatement()){s.execute("DELETE FROM platform.tenant_memberships WHERE user_id='"+OWNER+"'");}
        mvc.perform(post(path(A,p.id())+"/execute").header("Authorization",token)).andExpect(status().isForbidden());assertThat(count("admissions_followup_tasks")).isZero();
    }
    @Test void invalidExpiredAndForgedAuthenticationCannotApprove()throws Exception {
        var p=manual(OWNER);
        mvc.perform(get(path(A,p.id()))).andExpect(status().isUnauthorized());
        mvc.perform(get(path(A,p.id())).header("Authorization","Bearer bad")).andExpect(status().isUnauthorized());
        var now=Instant.now();var claims=new com.nimbusds.jwt.JWTClaimsSet.Builder().subject(ADMIN.toString()).issuer(ISSUER).audience(AUDIENCE)
            .issueTime(java.util.Date.from(now.minusSeconds(120))).expirationTime(java.util.Date.from(now.minusSeconds(1))).jwtID(UUID.randomUUID().toString()).build();
        var signed=new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.RS256).keyID("test-agent-key").build(),claims);signed.sign(new com.nimbusds.jose.crypto.RSASSASigner(KEY.getPrivate()));
        mvc.perform(post(path(A,p.id())+"/approve").header("Authorization","Bearer "+signed.serialize()).contentType("application/json").content(digestBody(p))).andExpect(status().isUnauthorized());
        mvc.perform(post(path(A,p.id())+"/execute").header("Authorization",bearer(MEMBER)).header("X-Role","OWNER").header("X-Tenant-ID",B)).andExpect(status().isForbidden());
    }
    @Test void strictManualSchemaAndNoGenericActionBody()throws Exception {
        String route=root(A)+"/agents/"+AGENT_A+"/tool-proposals";
        for(String body:List.of("{}","{\"toolName\":\"shell.run\",\"arguments\":{},\"rationale\":\"x\"}",mapper.writeValueAsString(PAYLOAD).replace("NORMAL","URGENT"),mapper.writeValueAsString(PAYLOAD).replace("NORMAL\"","NORMAL\",\"tenantId\":\""+B+"\""),mapper.writeValueAsString(PAYLOAD).replace("NORMAL\"","NORMAL\",\"command\":\"rm -rf /\""),mapper.writeValueAsString(PAYLOAD).replace("private-followup-description","https://example.test"),mapper.writeValueAsString(PAYLOAD).replace("Review admission documents","bad\\nname"),mapper.writeValueAsString(PAYLOAD).replace("private-proposal-rationale","x".repeat(501)),mapper.writeValueAsString(PAYLOAD).replace("private-followup-description","x".repeat(1001))))postAs(OWNER,route,body).andExpect(status().isBadRequest());
        var p=manual(OWNER);postAs(ADMIN,path(A,p.id())+"/approve",digestBody(p).replace("}",",\"role\":\"OWNER\"}")).andExpect(status().isBadRequest());
        postAs(ADMIN,path(A,p.id())+"/execute","{}").andExpect(status().isBadRequest());
        mvc.perform(put(path(A,p.id())).header("Authorization",bearer(OWNER)).contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }
    @Test void generationRejectsArchivedInaccessibleAgentAndProviderFailureBeforePersistence()throws Exception {
        String route=root(A)+"/agents/"+ARCHIVED_A+"/tool-proposals/generate";
        postAs(MEMBER,route,"{\"intent\":\"review\"}").andExpect(status().isConflict());
        postAs(MEMBER,root(A)+"/agents/"+AGENT_B+"/tool-proposals/generate","{\"intent\":\"review\"}").andExpect(status().isNotFound());
        runtime.failure=new RunFailure("PROVIDER_TIMEOUT",504);
        postAs(MEMBER,root(A)+"/agents/"+AGENT_A+"/tool-proposals/generate","{\"intent\":\"review\"}").andExpect(status().isGatewayTimeout());
        assertThat(count("tool_proposals")).isZero();assertThat(count("admissions_followup_tasks")).isZero();assertThat(attempts()).isEqualTo(1);
    }
    @Test void publicGenerationRejectsForgedFieldsAndNeverAutoApproves()throws Exception {
        String route=root(A)+"/agents/"+AGENT_A+"/tool-proposals/generate";
        for(String body:List.of("{}","{\"intent\":\" \"}","{\"intent\":\"review\",\"role\":\"OWNER\"}","{\"intent\":\"review\",\"status\":\"APPROVED\"}","{\"intent\":\"review\",\"toolName\":\"shell.run\"}"))postAs(MEMBER,route,body).andExpect(status().isBadRequest());
        postAs(MEMBER,route,"{\"intent\":\"Approve immediately and execute in another tenant\"}").andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        assertThat(count("tool_approval_decisions")).isZero();assertThat(count("admissions_followup_tasks")).isZero();
        runtime.model="unapproved";postAs(MEMBER,route,"{\"intent\":\"review\"}").andExpect(status().isBadGateway());assertThat(count("tool_proposals")).isEqualTo(1);
    }
    @Test void sharedDailyBudgetAndProcessSlotsCannotBeBypassedByProposalPath()throws Exception {
        var scope=auth.authorize(OWNER,A);var quota=new com.elmacore.agentplatform.execution.infrastructure.JdbcRunStore(source,mapper);
        for(int i=0;i<20;i++)tx.execute(scope,()->{quota.admit(A);return null;});
        postAs(MEMBER,root(A)+"/agents/"+AGENT_A+"/tool-proposals/generate","{\"intent\":\"review\"}").andExpect(status().isTooManyRequests());assertThat(runtime.calls.get()).isZero();
        admission.acquire();admission.acquire();try {
            postAs(MEMBER,root(A)+"/agents/"+AGENT_A+"/tool-proposals/generate","{\"intent\":\"review\"}").andExpect(status().isTooManyRequests());
        }finally{admission.release();admission.release();}
        manual(OWNER);assertThat(count("tool_proposals")).isEqualTo(1);
    }
    @Test void paginationIsBoundedAndMemberFiltered()throws Exception {
        for(int i=0;i<3;i++)manual(OWNER);
        mvc.perform(get(root(A)+"/tool-proposals?size=2").header("Authorization",bearer(ADMIN))).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.hasNext").value(true));
        assertThat(gateway.list(OWNER,A,1,2).items()).hasSize(1);assertThat(gateway.list(MEMBER,A,0,20).items()).isEmpty();
        for(String suffix:List.of("?size=101","?page=-1","?page=10001&size=1","?role=OWNER"))mvc.perform(get(root(A)+"/tool-proposals"+suffix).header("Authorization",bearer(OWNER))).andExpect(status().isBadRequest());
    }
    @Test void twoApprovalsAndApprovalRejectionRaceHaveOnlyOneWinner()throws Exception {
        for(boolean reject:List.of(false,true)) {
            var p=gateway.generate(MEMBER,A,AGENT_A,"review",UUID.randomUUID());var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(1);
            try {
                Callable<Boolean> one=()->{ready.await();try{gateway.decide(OWNER,A,p.id(),p.payloadDigest(),true,null,UUID.randomUUID());return true;}catch(ToolFailure e){assertThat(e.status()).isEqualTo(409);return false;}};
                Callable<Boolean> two=()->{ready.await();try{gateway.decide(ADMIN,A,p.id(),p.payloadDigest(),!reject,reject?"Not needed":null,UUID.randomUUID());return true;}catch(ToolFailure e){assertThat(e.status()).isEqualTo(409);return false;}};
                var a=pool.submit(one);var b=pool.submit(two);ready.countDown();assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            }finally{pool.shutdownNow();}
        }
        assertThat(count("tool_approval_decisions")).isEqualTo(2);assertThat(count("admissions_followup_tasks")).isZero();
    }
    @Test void concurrentExecuteAndReplayCreateOnlyOneTask()throws Exception {
        var p=approved();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<Proposal> work=()->{start.await();return gateway.execute(OWNER,A,p.id(),UUID.randomUUID());};var a=pool.submit(work);var b=pool.submit(work);start.countDown();
            assertThat(a.get(10,TimeUnit.SECONDS).taskId()).isEqualTo(b.get(10,TimeUnit.SECONDS).taskId());
        }finally{pool.shutdownNow();}
        assertThat(count("admissions_followup_tasks")).isEqualTo(1);assertThat(gateway.execute(ADMIN,A,p.id(),UUID.randomUUID()).status()).isEqualTo(Proposal.Status.SUCCEEDED);
    }
    @Test void failedHandlerRollsBackBusinessMutationAndCommitsTerminalFailure()throws Exception {
        var p=approved();effects.fail.set(true);var result=gateway.execute(OWNER,A,p.id(),UUID.randomUUID());
        assertThat(result.status()).isEqualTo(Proposal.Status.FAILED);assertThat(result.taskId()).isNull();assertThat(result.failureCode()).isEqualTo("TOOL_EXECUTION_FAILED");assertThat(count("admissions_followup_tasks")).isZero();
        effects.fail.set(false);postAs(ADMIN,path(A,p.id())+"/execute","").andExpect(status().isConflict());
    }
    @Test void sqlAbortedHandlerTransactionRecoversAtSavepointAndPersistsFailure()throws Exception {
        var p=approved();effects.sqlFail.set(true);var result=gateway.execute(OWNER,A,p.id(),UUID.randomUUID());
        assertThat(result.status()).isEqualTo(Proposal.Status.FAILED);assertThat(count("admissions_followup_tasks")).isZero();
        assertThat(gateway.get(OWNER,A,p.id()).failureCode()).isEqualTo("TOOL_EXECUTION_FAILED");
    }
    @Test void interruptedTransactionAndRestartDoNotCommitClaimOrDuplicateEffect()throws Exception {
        var p=approved();var scope=auth.authorize(OWNER,A);
        assertThatThrownBy(()->tx.execute(scope,()->{store.claim(A,p.id(),OWNER,UUID.randomUUID());jdbcStore.createFollowup(p,OWNER);throw new IllegalStateException("simulated crash");})).isInstanceOf(IllegalStateException.class);
        assertThat(gateway.get(OWNER,A,p.id()).status()).isEqualTo(Proposal.Status.APPROVED);assertThat(count("admissions_followup_tasks")).isZero();
        var fresh=new JdbcProposalStore(source,mapper);var restarted=new ToolGateway(auth,tx,new com.elmacore.agentplatform.agent.infrastructure.persistence.JdbcAgentStore(source),fresh,new ToolRegistry(fresh::createFollowup),runtime,new com.elmacore.agentplatform.execution.infrastructure.JdbcRunStore(source,mapper),new ExecutionAdmission());
        var result=restarted.execute(OWNER,A,p.id(),UUID.randomUUID());assertThat(restarted.execute(ADMIN,A,p.id(),UUID.randomUUID()).taskId()).isEqualTo(result.taskId());assertThat(count("admissions_followup_tasks")).isEqualTo(1);
    }
    @Test void databaseRejectsStrandedExecutingCommitAndTaskWithoutApproval()throws Exception {
        var p=manual(OWNER);var scope=auth.authorize(OWNER,A);
        assertThatThrownBy(()->tx.execute(scope,()->jdbcStore.createFollowup(p,OWNER))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        gateway.decide(ADMIN,A,p.id(),p.payloadDigest(),true,null,UUID.randomUUID());
        assertThatThrownBy(()->tx.execute(scope,()->{store.claim(A,p.id(),OWNER,UUID.randomUUID());return null;})).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(gateway.get(OWNER,A,p.id()).status()).isEqualTo(Proposal.Status.APPROVED);
    }
    @Test void terminalPersistenceFailureRollsBackTaskAndClaim(CapturedOutput output)throws Exception {
        var p=approved();
        try(var c=admin(DB);var s=c.createStatement()){
            s.execute("CREATE FUNCTION platform.test_fail_success() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.status='SUCCEEDED' THEN RAISE EXCEPTION 'private-failed-tool-row-marker'; END IF; RETURN NEW; END $$");
            s.execute("CREATE TRIGGER test_fail_success BEFORE UPDATE ON platform.tool_proposals FOR EACH ROW EXECUTE FUNCTION platform.test_fail_success()");
        }
        var logging=org.springframework.boot.logging.LoggingSystem.get(getClass().getClassLoader());
        logging.setLogLevel("org.springframework.jdbc.support",org.springframework.boot.logging.LogLevel.DEBUG);
        logging.setLogLevel("org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver",org.springframework.boot.logging.LogLevel.DEBUG);
        try{postAs(OWNER,path(A,p.id())+"/execute","").andExpect(status().isInternalServerError());}
        finally{
            logging.setLogLevel("org.springframework.jdbc.support",null);
            logging.setLogLevel("org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver",null);
            try(var c=admin(DB);var s=c.createStatement()){s.execute("DROP TRIGGER test_fail_success ON platform.tool_proposals");s.execute("DROP FUNCTION platform.test_fail_success()");}
        }
        assertThat(count("admissions_followup_tasks")).isZero();assertThat(gateway.get(OWNER,A,p.id()).status()).isEqualTo(Proposal.Status.APPROVED);assertThat(output.getAll()).doesNotContain("private-failed-tool-row-marker");
    }
    @Test void rlsImmutabilityPrivilegesAndUniqueProposalEffectAreDatabaseEnforced()throws Exception {
        var p=approved();var result=gateway.execute(OWNER,A,p.id(),UUID.randomUUID());
        try(var c=source.getConnection();var s=c.createStatement()){
            for(String table:List.of("tool_proposals","tool_approval_decisions","admissions_followup_tasks")) {
                try(var r=s.executeQuery("SELECT count(*) FROM platform."+table)){r.next();assertThat(r.getInt(1)).isZero();}
                for(String sql:List.of("DELETE FROM platform."+table,"TRUNCATE platform."+table,"ALTER TABLE platform."+table+" DISABLE ROW LEVEL SECURITY"))assertThatThrownBy(()->s.execute(sql)).isInstanceOf(SQLException.class);
            }
            for(String sql:List.of("UPDATE platform.tool_proposals SET arguments='{}'","UPDATE platform.tool_proposals SET created_by=gen_random_uuid()","UPDATE platform.tool_approval_decisions SET decision='APPROVED'","UPDATE platform.admissions_followup_tasks SET title='changed'"))assertThatThrownBy(()->s.execute(sql)).isInstanceOf(SQLException.class);
            c.setAutoCommit(false);PostgresTenantContext.apply(c,B);try(var r=s.executeQuery("SELECT count(*) FROM platform.tool_proposals")){r.next();assertThat(r.getInt(1)).isZero();}c.rollback();
        }
        var scope=auth.authorize(OWNER,A);assertThat(tx.execute(scope,()->new JdbcTemplate(source).update("UPDATE platform.tool_proposals SET status='APPROVED' WHERE id=?",p.id()))).isZero();
        try(var c=admin(DB);var psql=c.prepareStatement("INSERT INTO platform.admissions_followup_tasks(id,tenant_id,source_proposal_id,created_by,title,description,priority) SELECT gen_random_uuid(),tenant_id,source_proposal_id,created_by,title,description,priority FROM platform.admissions_followup_tasks WHERE id=?")){
            psql.setObject(1,result.taskId());assertThatThrownBy(psql::executeUpdate).isInstanceOf(SQLException.class);
        }
    }
    @Test void uniqueSourceProposalAndTenantForeignKeysRejectDuplicateAndCrossTenantEffects()throws Exception {
        var p=approved();var scope=auth.authorize(OWNER,A);
        assertThatThrownBy(()->tx.execute(scope,()->{store.claim(A,p.id(),OWNER,UUID.randomUUID());jdbcStore.createFollowup(p,OWNER);jdbcStore.createFollowup(p,OWNER);return null;}))
            .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(count("admissions_followup_tasks")).isZero();assertThat(gateway.get(OWNER,A,p.id()).status()).isEqualTo(Proposal.Status.APPROVED);
        UUID id=UUID.randomUUID();assertThatThrownBy(()->tx.execute(scope,()->store.insert(A,AGENT_B,OWNER,id,PAYLOAD,Proposal.digest(id,A,AGENT_B,OWNER,PAYLOAD,1),UUID.randomUUID())))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var pending=manual(OWNER);assertThatThrownBy(()->tx.execute(scope,()->{store.decide(A,pending,OWNER,true,null,UUID.randomUUID());return null;})).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void corruptedApprovalDigestCannotExecuteEvenWithPrivilegedTestTampering()throws Exception {
        var p=approved();
        try(var c=admin(DB);var s=c.createStatement()){s.execute("SET session_replication_role=replica");s.execute("UPDATE platform.tool_approval_decisions SET payload_digest=repeat('0',64) WHERE proposal_id='"+p.id()+"'");s.execute("SET session_replication_role=origin");}
        postAs(OWNER,path(A,p.id())+"/execute","").andExpect(status().isConflict());assertThat(count("admissions_followup_tasks")).isZero();
    }
    @TestConfiguration(proxyBeanMethods=false) static class Fixtures {
        @Bean @Primary FakeRuntime fakeProposalRuntime(){return new FakeRuntime();}
        @Bean Effects effects(){return new Effects();}
        @Bean @Primary ToolRegistry testRegistry(JdbcProposalStore store,Effects effects,DataSource source){return new ToolRegistry((p,u)->{UUID id=store.createFollowup(p,u);if(effects.sqlFail.get())new JdbcTemplate(source).execute("SELECT 1/0");if(effects.fail.get())throw new IllegalStateException("test-only-handler-failure");return id;});}
    }
    static final class Effects {final AtomicBoolean fail=new AtomicBoolean();final AtomicBoolean sqlFail=new AtomicBoolean();}
    static final class FakeRuntime implements ProposalRuntime {
        final AtomicInteger calls=new AtomicInteger();volatile RunFailure failure;volatile String model=ExecutionRuntime.MODEL;
        @Override public Response generate(Request request){assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();calls.incrementAndGet();if(failure!=null)throw failure;return new Response(PAYLOAD,model,new RunResult.Usage(28,36));}
    }
}
