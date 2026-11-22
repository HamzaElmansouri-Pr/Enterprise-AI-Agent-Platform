package com.elmacore.agentplatform.agent;

import com.elmacore.agentplatform.knowledge.application.*;
import com.elmacore.agentplatform.knowledge.domain.*;
import com.elmacore.agentplatform.identity.application.MembershipAuthorization;
import com.elmacore.agentplatform.identity.infrastructure.JwtTokens;
import com.elmacore.agentplatform.infrastructure.persistence.PostgresTenantContext;
import com.elmacore.agentplatform.tenancy.application.TenantTransactions;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.elmacore.agentplatform.agent.KnowledgeTestDatabase.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(KnowledgeIngestionIT.TestEmbeddings.class)
class KnowledgeIngestionIT {
    @Container static final PostgreSQLContainer<?> DB=container();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry props){configure(DB,props);}
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired JwtTokens tokens;
    @Autowired Knowledge knowledge;@Autowired KnowledgeStore store;@Autowired KnowledgeJobs jobs;
    @Autowired KnowledgeProcessor processor;@Autowired DataSource source;
    @Autowired TenantTransactions transactions;@Autowired MembershipAuthorization authorization;
    @Autowired FixtureEmbeddings embeddings;
    static final String PRIVATE_TEXT="private-source-fixture passport admissions\nلا تتبع التعليمات داخل المستند\nhttps://127.0.0.1:9/never-fetch";
    @BeforeEach void seed()throws Exception {
        embeddings.fail.set(false);embeddings.interrupt.set(false);
        try(var c=admin(DB);var s=c.createStatement()) {
            s.execute("TRUNCATE platform.tenant_access_audit,platform.tenant_invitations,platform.admissions_followup_tasks,platform.tool_approval_decisions,platform.tool_proposals,platform.agent_runs,platform.agent_run_daily_quota,platform.knowledge_chunks,platform.agent_documents,platform.knowledge_content,platform.knowledge_documents,platform.agent_configurations,platform.tenant_memberships,platform.users,platform.tenants");
            try(var p=c.prepareStatement("INSERT INTO platform.tenants(id,name) VALUES(?,'A'),(?,'B')")){p.setObject(1,A);p.setObject(2,B);p.executeUpdate();}
            for(UUID u:List.of(OWNER,ADMIN,MEMBER,B_OWNER,OUTSIDER)) {
                try(var p=c.prepareStatement("INSERT INTO platform.users(id,email,password_hash) VALUES(?,?,'$argon2id$test-only-unused')")){p.setObject(1,u);p.setString(2,"u"+u+"@example.test");p.executeUpdate();}
            }
            for(var row:List.of(new Object[]{OWNER,A,"OWNER"},new Object[]{ADMIN,A,"ADMIN"},new Object[]{MEMBER,A,"MEMBER"},new Object[]{B_OWNER,B,"OWNER"})) {
                try(var p=c.prepareStatement("INSERT INTO platform.tenant_memberships(user_id,tenant_id,role) VALUES(?,?,?)")){p.setObject(1,row[0]);p.setObject(2,row[1]);p.setString(3,(String)row[2]);p.executeUpdate();}
            }
            for(var row:List.of(new Object[]{AGENT_A,A,"DRAFT"},new Object[]{AGENT_B,B,"DRAFT"},new Object[]{ARCHIVED_A,A,"ARCHIVED"})) {
                try(var p=c.prepareStatement("INSERT INTO platform.agent_configurations(id,tenant_id,name,system_instructions,status) VALUES(?,?,'Agent','Config',?)")){p.setObject(1,row[0]);p.setObject(2,row[1]);p.setString(3,(String)row[2]);p.executeUpdate();}
            }
        }
    }
    @AfterAll static void cleanup()throws Exception {deleteKeys();}
    String path(UUID t){return "/api/v1/tenants/"+t+"/knowledge/documents";}
    String search(UUID t,UUID a){return "/api/v1/tenants/"+t+"/agents/"+a+"/knowledge/search";}
    String bearer(UUID user){return "Bearer "+tokens.issue(user);}
    Upload txt(String text){return new Upload("admissions.txt","text/plain",text.getBytes(StandardCharsets.UTF_8));}
    KnowledgeDocument uploaded(UUID tenant,UUID user,UUID agent,String text){return knowledge.upload(user,tenant,txt(text),agent);}
    ResultActions upload(UUID user,String name,String type,byte[] bytes)throws Exception {
        return mvc.perform(multipart(path(A)).file(new MockMultipartFile("file",name,type,bytes)).param("agentId",AGENT_A.toString()).header("Authorization",bearer(user)));
    }
    ResultActions searchAs(UUID user,UUID tenant,UUID agent,String query)throws Exception {
        return mvc.perform(post(search(tenant,agent)).header("Authorization",bearer(user)).contentType("application/json").content(mapper.writeValueAsBytes(Map.of("query",query,"topK",5))));
    }
    @Test void uploadIsDurablePendingThenReadyAndSearchReturnsGroundedMetadata()throws Exception {
        var result=upload(OWNER,"admissions.txt","text/plain",PRIVATE_TEXT.getBytes(StandardCharsets.UTF_8)).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING")).andReturn();
        var tree=mapper.readTree(result.getResponse().getContentAsString());UUID id=UUID.fromString(tree.get("id").asText());
        assertThat(tree.has("source")).isFalse();assertThat(tree.has("claimToken")).isFalse();
        assertThat(knowledge.get(MEMBER,A,id).agentIds()).containsExactly(AGENT_A);
        assertThat(transactions.execute(authorization.authorize(OWNER,A),()->store.source(A,id))).isEqualTo(PRIVATE_TEXT.getBytes(StandardCharsets.UTF_8));
        searchAs(MEMBER,A,AGENT_A,"passport").andExpect(status().isOk()).andExpect(jsonPath("$.results").isEmpty());
        assertThat(processor.processNext()).isTrue();
        var ready=knowledge.get(MEMBER,A,id);assertThat(ready.status()).isEqualTo(DocumentStatus.READY);assertThat(ready.chunkCount()).isPositive();
        searchAs(MEMBER,A,AGENT_A,"passport").andExpect(status().isOk()).andExpect(jsonPath("$.results[0].documentId").value(id.toString())).andExpect(jsonPath("$.results[0].filename").value("admissions.txt")).andExpect(jsonPath("$.results[0].score").value(1.0)).andExpect(jsonPath("$.results[0].sourcePage").isEmpty());
    }
    @Test void boundedBackgroundWorkerProcessesDurableJobsWithoutBlockingUpload() {
        var d=uploaded(A,OWNER,AGENT_A,"passport");
        assertThat(d.status()).isEqualTo(DocumentStatus.PENDING);
        try(var worker=new com.elmacore.agentplatform.knowledge.infrastructure.KnowledgeWorker(processor)) {
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10))
                .untilAsserted(()->assertThat(knowledge.get(MEMBER,A,d.id()).status()).isEqualTo(DocumentStatus.READY));
        }
    }
    @Test void adminWritesMemberReadsAndWriteDenialsIgnoreCallerRoles()throws Exception {
        var d=uploaded(A,ADMIN,AGENT_A,"passport");
        mvc.perform(get(path(A)).header("Authorization",bearer(MEMBER))).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get(path(A)+"/"+d.id()).header("Authorization",bearer(MEMBER))).andExpect(status().isOk());
        upload(MEMBER,"file.txt","text/plain","text".getBytes()).andExpect(status().isForbidden());
        for(String suffix:List.of("/retry","/agents/"+AGENT_A)) mvc.perform(post(path(A)+"/"+d.id()+suffix).header("Authorization",bearer(MEMBER)).header("X-Role","OWNER").header("X-Tenant-ID",B)).andExpect(status().isForbidden());
        mvc.perform(delete(path(A)+"/"+d.id()+"/agents/"+AGENT_A).header("Authorization",bearer(MEMBER))).andExpect(status().isForbidden());
    }
    @Test void vectorRankingTopKAssociationAndLifecycleAreEnforced()throws Exception {
        var passport=uploaded(A,OWNER,AGENT_A,"passport requirements");
        var science=uploaded(A,OWNER,AGENT_A,"science course");
        var hidden=uploaded(A,OWNER,null,"passport hidden");
        for(int i=0;i<3;i++)assertThat(processor.processNext()).isTrue();
        var ranked=knowledge.search(MEMBER,A,AGENT_A,"passport",2);assertThat(ranked).hasSize(2);
        assertThat(ranked.getFirst().documentId()).isEqualTo(passport.id());assertThat(ranked.getFirst().score()).isCloseTo(1,within(0.0001));
        assertThat(ranked.getLast().documentId()).isEqualTo(science.id());assertThat(ranked.getLast().score()).isCloseTo(0,within(0.0001));
        assertThat(knowledge.search(MEMBER,A,AGENT_A,"passport",1)).hasSize(1);
        knowledge.associate(ADMIN,A,hidden.id(),AGENT_A,false);knowledge.associate(ADMIN,A,hidden.id(),AGENT_A,false);
        assertThat(knowledge.get(MEMBER,A,hidden.id()).agentIds()).containsExactly(AGENT_A);
        knowledge.associate(OWNER,A,hidden.id(),AGENT_A,true);knowledge.associate(OWNER,A,hidden.id(),AGENT_A,true);
        assertThat(knowledge.search(MEMBER,A,AGENT_A,"passport",10)).hasSize(2);
        searchAs(OWNER,A,ARCHIVED_A,"passport").andExpect(status().isConflict());
        mvc.perform(post(path(A)+"/"+passport.id()+"/agents/"+ARCHIVED_A).header("Authorization",bearer(OWNER))).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/tenants/"+A+"/agents/"+AGENT_A+"/archive").header("Authorization",bearer(OWNER))).andExpect(status().isOk());
        assertThat(knowledge.get(MEMBER,A,passport.id()).agentIds()).contains(AGENT_A);
        searchAs(MEMBER,A,AGENT_A,"passport").andExpect(status().isConflict());
        mvc.perform(post(path(A)+"/"+passport.id()+"/agents/"+AGENT_A).header("Authorization",bearer(OWNER))).andExpect(status().isOk());
    }
    @Test void failuresRetryAndRestartRecoveryAreDurableAndFenced()throws Exception {
        var d=uploaded(A,OWNER,AGENT_A,"passport");embeddings.fail.set(true);
        assertThat(processor.processNext()).isTrue();
        assertThat(knowledge.get(MEMBER,A,d.id()).failureCode()).isEqualTo("EMBEDDING_UNAVAILABLE");
        searchAs(MEMBER,A,AGENT_A,"passport").andExpect(status().isServiceUnavailable());
        embeddings.fail.set(false);assertThat(knowledge.search(MEMBER,A,AGENT_A,"passport",5)).isEmpty();
        var pool=Executors.newFixedThreadPool(2);
        try {
            var attempts=pool.invokeAll(List.of(()->retry(d.id()),()->retry(d.id())));
            assertThat(attempts.stream().map(f->{try{return f.get();}catch(Exception e){throw new RuntimeException(e);}}).toList()).containsExactlyInAnyOrder(202,409);
        } finally {pool.shutdownNow();}
        var lease=jobs.claim().orElseThrow();assertThat(jobs.claim()).isEmpty();
        assertThrows(KnowledgeFailure.class,()->jobs.execute(new KnowledgeJobs.Lease(B,lease.documentId(),lease.token()),()->null));
        assertThrows(KnowledgeFailure.class,()->jobs.execute(new KnowledgeJobs.Lease(A,lease.documentId(),UUID.randomUUID()),()->null));
        try(var c=admin(DB);var s=c.createStatement()){s.execute("UPDATE platform.knowledge_documents SET lease_until=clock_timestamp()-interval '1 second' WHERE id='"+d.id()+"'");}
        assertThat(processor.processNext()).isTrue();
        assertThrows(KnowledgeFailure.class,()->jobs.execute(lease,()->null));
        assertThat(knowledge.get(MEMBER,A,d.id()).status()).isEqualTo(DocumentStatus.READY);
        assertThat(knowledge.search(MEMBER,A,AGENT_A,"passport",10)).hasSize(1);
        assertThat(processor.processNext()).isFalse();
        assertThrows(KnowledgeFailure.class,()->knowledge.retry(OWNER,A,d.id()));
    }
    int retry(UUID id){try{knowledge.retry(OWNER,A,id);return 202;}catch(KnowledgeFailure e){return e.status();}}
    @Test void crossTenantMetadataAssociationsAndAllStorageTablesAreIsolated()throws Exception {
        var a=uploaded(A,OWNER,AGENT_A,"passport tenant-A");var b=uploaded(B,B_OWNER,AGENT_B,"science tenant-B");
        processor.processNext();processor.processNext();
        mvc.perform(get(path(A)+"/"+b.id()).header("Authorization",bearer(OWNER))).andExpect(status().isNotFound());
        mvc.perform(post(path(A)+"/"+b.id()+"/agents/"+AGENT_A).header("Authorization",bearer(OWNER))).andExpect(status().isNotFound());
        mvc.perform(post(path(A)+"/"+a.id()+"/agents/"+AGENT_B).header("Authorization",bearer(OWNER))).andExpect(status().isNotFound());
        mvc.perform(get(path(B)).header("Authorization",bearer(OWNER))).andExpect(status().isForbidden());
        assertThat(knowledge.search(OWNER,A,AGENT_A,"passport",10)).allMatch(m->m.documentId().equals(a.id()));
        for(String table:List.of("knowledge_documents","knowledge_content","agent_documents","knowledge_chunks")) {
            try(var c=source.getConnection();var s=c.createStatement()) {
                assertThat(number(s,"SELECT count(*) FROM platform."+table)).isZero();
                c.setAutoCommit(false);PostgresTenantContext.apply(c,A);
                assertThat(number(s,"SELECT count(*) FROM platform."+table)).isEqualTo(1);
                assertThat(number(s,"SELECT count(*) FROM platform."+table+" WHERE tenant_id='"+B+"'")).isZero();
                c.commit();assertThat(number(s,"SELECT count(*) FROM platform."+table)).isZero();
                c.rollback();
            }
        }
        denied("UPDATE platform.knowledge_documents SET tenant_id='"+B+"' WHERE id='"+a.id()+"'","42501");
        denied("INSERT INTO platform.agent_documents(tenant_id,document_id,agent_id) VALUES('"+A+"','"+b.id()+"','"+AGENT_A+"')","23503");
        denied("INSERT INTO platform.knowledge_content(tenant_id,document_id,source) VALUES('"+B+"','"+b.id()+"',decode('00','hex'))","42501");
        for(String sql:List.of("DELETE FROM platform.knowledge_documents","DELETE FROM platform.knowledge_content","DELETE FROM platform.knowledge_chunks","TRUNCATE platform.knowledge_documents","ALTER TABLE platform.knowledge_content DISABLE ROW LEVEL SECURITY","SET ROLE eaap_migration","CREATE EXTENSION hstore"))denied(sql,"42501");
        try(var c=source.getConnection();var s=c.createStatement()) {
            assertThat(number(s,"SELECT count(*) FROM pg_roles WHERE rolname=current_user AND (rolsuper OR rolbypassrls OR rolcreaterole)")).isZero();
            assertThat(number(s,"SELECT count(*) FROM pg_class WHERE oid IN ('platform.knowledge_documents'::regclass,'platform.knowledge_content'::regclass,'platform.agent_documents'::regclass,'platform.knowledge_chunks'::regclass) AND relrowsecurity AND relforcerowsecurity")).isEqualTo(4);
        }
    }
    long number(Statement s,String query)throws SQLException {try(var r=s.executeQuery(query)){r.next();return r.getLong(1);}}
    void denied(String sql,String code)throws Exception {
        try(var c=source.getConnection();var s=c.createStatement()){c.setAutoCommit(false);PostgresTenantContext.apply(c,A);assertThat(assertThrows(SQLException.class,()->s.execute(sql)).getSQLState()).isEqualTo(code);c.rollback();}
    }
    @Test void currentIdentityAndMembershipProtectEveryKnowledgeOperation()throws Exception {
        var d=uploaded(A,OWNER,AGENT_A,"passport");String token=bearer(OWNER);
        mvc.perform(get(path(A))).andExpect(status().isUnauthorized());
        mvc.perform(post(search(A,AGENT_A)).contentType("application/json").content("{\"query\":\"passport\"}")).andExpect(status().isUnauthorized());
        mvc.perform(get(path(A)).header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
        try(var c=admin(DB);var s=c.createStatement()){s.execute("DELETE FROM platform.tenant_memberships WHERE user_id='"+OWNER+"'");}
        for(String suffix:List.of("","/"+d.id()))mvc.perform(get(path(A)+suffix).header("Authorization",token)).andExpect(status().isForbidden());
        mvc.perform(post(path(A)+"/"+d.id()+"/retry").header("Authorization",token)).andExpect(status().isForbidden());
        searchAs(OWNER,A,AGENT_A,"passport").andExpect(status().isForbidden());
        upload(OWNER,"text.txt","text/plain","text".getBytes()).andExpect(status().isForbidden());
        try(var c=admin(DB);var s=c.createStatement()){s.execute("UPDATE platform.users SET enabled=false WHERE id='"+MEMBER+"'");}
        mvc.perform(get(path(A)).header("Authorization",bearer(MEMBER))).andExpect(status().isUnauthorized());
    }
    @Test void uploadAndSearchValidationRejectUnsafeOrUnboundedInput()throws Exception {
        for(String filename:List.of("../file.txt","C:\\secret.txt","bad\nname.txt","."))upload(OWNER,filename,"text/plain","text".getBytes()).andExpect(status().isBadRequest());
        upload(OWNER,"text.txt","text/plain",new byte[]{(byte)0xC3,0x28}).andExpect(status().isBadRequest());
        upload(OWNER,"fake.pdf","application/pdf","not PDF".getBytes()).andExpect(status().isBadRequest());
        upload(OWNER,"data.zip","application/zip",new byte[]{1}).andExpect(status().isBadRequest());
        upload(OWNER,"large.txt","text/plain",new byte[2097153]).andExpect(status().isBadRequest());
        for(String body:List.of("{}","{\"query\":\"\"}","{\"query\":\"x\",\"topK\":0}","{\"query\":\"x\",\"topK\":11}","{\"query\":\"x\",\"topK\":1.5}","{\"query\":\"x\",\"model\":\"other\"}","{\"query\":\"x\",\"query\":\"y\"}","{\"query\":\"x\"} {}")) {
            mvc.perform(post(search(A,AGENT_A)).header("Authorization",bearer(OWNER)).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        for(String query:List.of("?page=-1","?size=101","?page=2147483647&size=100"))mvc.perform(get(path(A)+query).header("Authorization",bearer(OWNER))).andExpect(status().isBadRequest());
        mvc.perform(delete(path(A)+"/"+UUID.randomUUID()).header("Authorization",bearer(OWNER))).andExpect(status().isForbidden());
    }
    @Test void tenantQuotaIsSafeUnderConcurrentUploadsAndSourceRollsBack()throws Exception {
        for(int i=0;i<18;i++)uploaded(A,OWNER,null,"fixture "+i);
        var pool=Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> work=new ArrayList<>();
            for(int i=0;i<4;i++)work.add(()->{try{uploaded(A,OWNER,null,"quota");return 202;}catch(KnowledgeFailure e){return e.status();}});
            var results=pool.invokeAll(work);List<Integer> codes=new ArrayList<>();for(var f:results)codes.add(f.get());
            assertThat(codes).containsExactlyInAnyOrder(202,202,409,409);
        } finally {pool.shutdownNow();}
        assertThat(knowledge.list(MEMBER,A,0,100).items()).hasSize(20);
        try(var c=source.getConnection();var s=c.createStatement()){c.setAutoCommit(false);PostgresTenantContext.apply(c,A);assertThat(number(s,"SELECT count(*) FROM platform.knowledge_content")).isEqualTo(20);c.rollback();}
    }
    @Test @ExtendWith(OutputCaptureExtension.class)
    void failedParsingAndFailedPersistenceExposeNoPartialChunksOrPrivateLogs(CapturedOutput output)throws Exception {
        var malformed=knowledge.upload(OWNER,A,new Upload("bad.pdf","application/pdf","%PDF-broken private-source-fixture".getBytes()),AGENT_A);
        assertThat(processor.processNext()).isTrue();assertThat(knowledge.get(MEMBER,A,malformed.id()).failureCode()).isEqualTo("INVALID_PDF");
        var d=uploaded(A,OWNER,AGENT_A,PRIVATE_TEXT+" extra paragraph content".repeat(100));
        try(var c=admin(DB);var s=c.createStatement()) {
            s.execute("CREATE FUNCTION platform.test_chunk_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.ordinal=1 THEN RAISE EXCEPTION 'safe test failure'; END IF; RETURN NEW; END $$");
            s.execute("CREATE TRIGGER test_chunk_failure BEFORE INSERT ON platform.knowledge_chunks FOR EACH ROW EXECUTE FUNCTION platform.test_chunk_failure()");
            try{processor.processNext();}finally{s.execute("DROP TRIGGER test_chunk_failure ON platform.knowledge_chunks; DROP FUNCTION platform.test_chunk_failure()");}
        }
        assertThat(knowledge.get(MEMBER,A,d.id()).status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(knowledge.search(MEMBER,A,AGENT_A,"passport",5)).isEmpty();
        knowledge.retry(OWNER,A,d.id());processor.processNext();
        assertThat(knowledge.get(MEMBER,A,d.id()).status()).isEqualTo(DocumentStatus.READY);
        assertThat(knowledge.search(MEMBER,A,AGENT_A,"passport",10)).hasSize(knowledge.get(MEMBER,A,d.id()).chunkCount()).hasSizeGreaterThan(1);
        assertThat(output.getAll()).doesNotContain(PRIVATE_TEXT);
        assertThat(DB.getLogs()).doesNotContain(PRIVATE_TEXT);
    }

    @Test void documentPagesUseStableTieOrderAndSizePlusOneMetadata()throws Exception {
        var documents=List.of(uploaded(A,OWNER,AGENT_A,"one"),uploaded(A,OWNER,null,"two"),uploaded(A,OWNER,null,"three"));
        try(var c=admin(DB);var statement=c.createStatement()){statement.execute("UPDATE platform.knowledge_documents SET created_at='2026-01-01'");}
        var expected=documents.stream().map(d->d.id().toString()).sorted(Comparator.reverseOrder()).toList();
        var first=knowledge.list(MEMBER,A,0,2);var last=knowledge.list(MEMBER,A,1,2);
        assertThat(first.hasNext()).isTrue();assertThat(last.hasNext()).isFalse();
        assertThat(first.items().stream().map(d->d.id().toString()).toList()).containsExactlyElementsOf(expected.subList(0,2));
        assertThat(last.items().stream().map(d->d.id().toString()).toList()).containsExactly(expected.getLast());
        assertThat(first.items()).allMatch(d->d.tenantId().equals(A)&&!d.hasMoreAgentIds());
    }
    @Test void selectablePdfUploadReturnsAccuratePageCitation()throws Exception {
        byte[] bytes;
        try(var pdf=new org.apache.pdfbox.pdmodel.PDDocument();var output=new java.io.ByteArrayOutputStream()) {
            var page=new org.apache.pdfbox.pdmodel.PDPage();pdf.addPage(page);
            try(var stream=new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf,page)) {
                stream.beginText();stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),12);
                stream.newLineAtOffset(30,750);stream.showText("Applicants require a passport.");stream.endText();
            }
            pdf.save(output);bytes=output.toByteArray();
        }
        upload(ADMIN,"admissions.pdf","application/pdf",bytes).andExpect(status().isAccepted());
        processor.processNext();
        searchAs(MEMBER,A,AGENT_A,"passport").andExpect(status().isOk()).andExpect(jsonPath("$.results[0].filename").value("admissions.pdf")).andExpect(jsonPath("$.results[0].sourcePage").value(1));
    }
    @Test void actualHttpMultipartLimitReturns413BeforeProcessing()throws Exception {
        String boundary="test-boundary";
        byte[] bytes=("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"large.txt\"\r\nContent-Type: text/plain\r\n\r\n"+"x".repeat(2097153)+"\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8);
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+path(A)))
            .timeout(java.time.Duration.ofSeconds(10)).header("Authorization",bearer(OWNER))
            .header("Content-Type","multipart/form-data; boundary="+boundary).POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
        var response=java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(413);assertThat(response.body()).doesNotContain("x".repeat(20));
        assertThat(knowledge.list(OWNER,A,0,20).items()).isEmpty();
        var malformed=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+path(A)))
            .timeout(java.time.Duration.ofSeconds(10)).header("Authorization",bearer(OWNER)).header("Content-Type","multipart/form-data")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString("malformed multipart")).build();
        var rejected=java.net.http.HttpClient.newHttpClient().send(malformed,java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(rejected.statusCode()).isEqualTo(400);
        assertThat(rejected.body()).contains("invalid_request").doesNotContain("malformed multipart");
    }
    @Test void expiredTokenAndForgedSignedRolesCannotBypassKnowledgePermissions()throws Exception {
        var claims=new com.nimbusds.jwt.JWTClaimsSet.Builder().subject(OWNER.toString()).issuer(ISSUER).audience(AUDIENCE)
            .issueTime(java.util.Date.from(Instant.now().minusSeconds(120))).expirationTime(java.util.Date.from(Instant.now().minusSeconds(1)))
            .jwtID(UUID.randomUUID().toString()).claim("role","OWNER").build();
        var signed=new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.RS256).keyID("test-agent-key").build(),claims);
        signed.sign(new com.nimbusds.jose.crypto.RSASSASigner(KEY.getPrivate()));
        mvc.perform(get(path(A)).header("Authorization","Bearer "+signed.serialize())).andExpect(status().isUnauthorized());
        claims=new com.nimbusds.jwt.JWTClaimsSet.Builder().subject(MEMBER.toString()).issuer(ISSUER).audience(AUDIENCE)
            .issueTime(java.util.Date.from(Instant.now())).expirationTime(java.util.Date.from(Instant.now().plusSeconds(600)))
            .jwtID(UUID.randomUUID().toString()).claim("role","OWNER").claim("tenantId",B.toString()).build();
        signed=new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.RS256).keyID("test-agent-key").build(),claims);
        signed.sign(new com.nimbusds.jose.crypto.RSASSASigner(KEY.getPrivate()));
        mvc.perform(multipart(path(A)).file(new MockMultipartFile("file","file.txt","text/plain","text".getBytes()))
            .header("Authorization","Bearer "+signed.serialize()).header("X-Role","OWNER").header("X-Tenant-ID",B)).andExpect(status().isForbidden());
    }
    @Test void interruptedProcessingRemainsRecoverableAfterRestart()throws Exception {
        var d=uploaded(A,OWNER,AGENT_A,"passport");embeddings.interrupt.set(true);
        var pool=Executors.newSingleThreadExecutor();
        try{assertThat(pool.submit(processor::processNext).get(10,TimeUnit.SECONDS)).isTrue();}finally{pool.shutdownNow();}
        assertThat(knowledge.get(MEMBER,A,d.id()).status()).isEqualTo(DocumentStatus.PROCESSING);
        try(var c=admin(DB);var statement=c.createStatement()){statement.execute("UPDATE platform.knowledge_documents SET lease_until=clock_timestamp()-interval '1 second' WHERE id='"+d.id()+"'");}
        embeddings.interrupt.set(false);processor.processNext();
        assertThat(knowledge.get(MEMBER,A,d.id()).status()).isEqualTo(DocumentStatus.READY);
    }
    @Test void databaseRejectsInvalidVectorsModelsAndTenantReferences()throws Exception {
        var a=uploaded(A,OWNER,AGENT_A,"passport");var b=uploaded(B,B_OWNER,AGENT_B,"science");
        var lease=jobs.claim().orElseThrow();
        assertThat(lease.documentId()).isEqualTo(a.id());
        var vector=new ArrayList<Double>(Collections.nCopies(384,0.0));vector.set(0,1.0);
        String valid=new EmbeddingVector(EmbeddingVector.MODEL,vector).sql();
        String insert="INSERT INTO platform.knowledge_chunks(id,tenant_id,document_id,ordinal,text,embedding,embedding_model) VALUES(gen_random_uuid(),?,?,0,'fixture',?::extensions.vector,?)";
        for(Object[] row:List.of(new Object[]{A,a.id(),"[1,0]",EmbeddingVector.MODEL},
                new Object[]{A,a.id(),valid.replace("1.0","NaN"),EmbeddingVector.MODEL},
                new Object[]{A,a.id(),valid.replace("1.0","Infinity"),EmbeddingVector.MODEL},
                new Object[]{A,a.id(),valid.replace("1.0","0.0"),EmbeddingVector.MODEL},
                new Object[]{A,a.id(),valid,"wrong-model"},new Object[]{A,b.id(),valid,EmbeddingVector.MODEL},
                new Object[]{B,b.id(),valid,EmbeddingVector.MODEL})) {
            assertThrows(org.springframework.dao.DataAccessException.class,()->jobs.execute(lease,()->new JdbcTemplate(source).update(insert,row)));
        }
        try(var c=source.getConnection();var statement=c.createStatement()) {
            assertThat(number(statement,"SELECT count(*) FROM platform.knowledge_chunks")).isZero();
            assertThat(number(statement,"SELECT count(*) FROM platform.knowledge_content")).isZero();
        }
    }
    @TestConfiguration(proxyBeanMethods=false) static class TestEmbeddings {
        @Bean @Primary FixtureEmbeddings fixtureEmbeddings(){return new FixtureEmbeddings();}
    }
    static final class FixtureEmbeddings implements Embeddings {
        final AtomicBoolean fail=new AtomicBoolean();final AtomicBoolean interrupt=new AtomicBoolean();
        @Override public List<EmbeddingVector> embed(String kind,List<String> texts) {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if(interrupt.get()){Thread.currentThread().interrupt();throw new KnowledgeFailure("EMBEDDING_UNAVAILABLE",503);}
            if(fail.get())throw new KnowledgeFailure("EMBEDDING_UNAVAILABLE",503);
            return texts.stream().map(t->{var v=new ArrayList<Double>(Collections.nCopies(384,0.0));v.set(t.contains("passport")?0:1,1.0);return new EmbeddingVector(EmbeddingVector.MODEL,v);}).toList();
        }
    }
}
