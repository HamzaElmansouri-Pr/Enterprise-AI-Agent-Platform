package com.elmacore.agentplatform.agent;
import com.elmacore.agentplatform.access.application.*;
import com.elmacore.agentplatform.access.domain.*;
import com.elmacore.agentplatform.access.domain.AccessRecords.*;
import com.elmacore.agentplatform.access.api.AcceptanceProtection;
import com.elmacore.agentplatform.identity.infrastructure.*;
import com.elmacore.agentplatform.identity.application.*;
import com.elmacore.agentplatform.tool.application.*;
import com.elmacore.agentplatform.tool.domain.*;
import com.elmacore.agentplatform.tenancy.application.TenantTransactions;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static com.elmacore.agentplatform.agent.AccessTestDatabase.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@Testcontainers @SpringBootTest @AutoConfigureMockMvc @ExtendWith(OutputCaptureExtension.class)
class TenantAccessIT {
 @Container static final PostgreSQLContainer DB=container();
 @DynamicPropertySource static void properties(DynamicPropertyRegistry p){configure(DB,p);}
 @Autowired TenantAccessManagement access;@Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired JwtTokens tokens;
 @Autowired MembershipAuthorization authorization;@Autowired TenantTransactions tx;@Autowired DataSource source;@Autowired ToolGateway tools;
 // Rate guard behavior is exercised separately with its real implementation; don't let independent API fixtures exhaust one peer's minute.
 @MockitoBean AcceptanceProtection protection;
 static final String PASSWORD="synthetic-acceptance-password";
 static final String HASH=new ArgonPasswords().encode(PASSWORD);
 UUID correlation(){return UUID.randomUUID();}
 String email(UUID user){return "u"+user+"@example.test";}
 @BeforeEach void seed()throws Exception{
  try(var c=admin(DB);var s=c.createStatement()){
   s.execute("TRUNCATE platform.conversation_turns,platform.conversations,platform.tenant_access_audit,platform.tenant_invitations,platform.admissions_followup_tasks,platform.tool_approval_decisions,platform.tool_proposals,platform.agent_runs,platform.agent_run_daily_quota,platform.knowledge_chunks,platform.agent_documents,platform.knowledge_content,platform.knowledge_documents,platform.agent_configurations,platform.tenant_memberships,platform.users,platform.tenants");
   for(UUID t:List.of(A,B))try(var p=c.prepareStatement("INSERT INTO platform.tenants(id,name) VALUES(?,'Test tenant')")){p.setObject(1,t);p.executeUpdate();}
   for(UUID u:List.of(OWNER,ADMIN,MEMBER,B_OWNER,OUTSIDER))try(var p=c.prepareStatement("INSERT INTO platform.users(id,email,password_hash) VALUES(?,?,?)")){p.setObject(1,u);p.setString(2,email(u));p.setString(3,HASH);p.executeUpdate();}
   for(var row:List.of(new Object[]{A,OWNER,"OWNER"},new Object[]{A,ADMIN,"ADMIN"},new Object[]{A,MEMBER,"MEMBER"},new Object[]{B,B_OWNER,"OWNER"}))try(var p=c.prepareStatement("INSERT INTO platform.tenant_memberships(tenant_id,user_id,role) VALUES(?,?,?)")){p.setObject(1,row[0]);p.setObject(2,row[1]);p.setString(3,(String)row[2]);p.executeUpdate();}
   try(var p=c.prepareStatement("INSERT INTO platform.agent_configurations(id,tenant_id,name,system_instructions,status) VALUES(?,?,'Agent','Instructions','DRAFT')")){p.setObject(1,AGENT_A);p.setObject(2,A);p.executeUpdate();}
  }
 }
 @AfterAll static void cleanup()throws Exception{deleteKeys();}
 String root(UUID t){return "/api/v1/tenants/"+t;}
 String bearer(UUID u){return "Bearer "+tokens.issue(u);}
 Issued invite(UUID actor,String email,String role){return access.invite(actor,A,email,role,correlation());}
 String json(Object value)throws Exception{return mapper.writeValueAsString(value);}
 ResultActions postAs(UUID user,String path,Object body)throws Exception{return mvc.perform(post(path).header("Authorization",bearer(user)).contentType("application/json").content(json(body)));}
 int count(String table)throws Exception{try(var c=admin(DB);var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM platform."+table)){r.next();return r.getInt(1);}}
 void sql(String query,Object...values)throws Exception{try(var c=admin(DB);var p=c.prepareStatement(query)){for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);p.executeUpdate();}}
 void fails(Runnable work,int status){assertThatThrownBy(work::run).isInstanceOf(AccessFailure.class).extracting(e->((AccessFailure)e).status()).isEqualTo(status);}
 @Test void ownerAndAdminIssueOnlyPermittedRolesAndReturnRawTokenOnce(CapturedOutput logs)throws Exception{
  var one=invite(OWNER,"colleague@example.test","MEMBER");var two=invite(OWNER,"admin@example.test","ADMIN");var three=invite(ADMIN,"another@example.test","MEMBER");
  assertThat(Set.of(one.token(),two.token(),three.token())).hasSize(3);assertThat(one.token()).hasSize(43);
  var rows=access.invitations(OWNER,A,0,20);assertThat(rows.items()).hasSize(3);assertThat(json(rows)).doesNotContain(one.token(),"tokenHash","token_hash");
  try(var c=admin(DB);var p=c.prepareStatement("SELECT token_hash,expires_at-created_at AS lifetime FROM platform.tenant_invitations WHERE id=?")){p.setObject(1,one.invitation().id());try(var r=p.executeQuery()){r.next();assertThat(r.getBytes(1)).isEqualTo(InvitationSecret.hash(one.token()));assertThat(r.getString(2)).isEqualTo("2 days");}}
  fails(()->invite(ADMIN,"no@example.test","ADMIN"),403);fails(()->invite(MEMBER,"no@example.test","MEMBER"),403);
  assertThatThrownBy(()->invite(OWNER,"no@example.test","OWNER")).isInstanceOf(IllegalArgumentException.class);
  assertThat(logs.getAll()).doesNotContain(one.token(),two.token(),three.token(),HASH);
 }
 @Test void newUserAcceptanceCreatesOneIdentityAndMembershipThenUsesNormalLogin()throws Exception{
  var i=invite(OWNER," New.Colleague@Example.test ","MEMBER");
  var result=mvc.perform(post("/api/v1/invitations/accept-new").contentType("application/json").content(json(Map.of("token",i.token(),"email","new.colleague@example.test","password",PASSWORD)))).andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("MEMBER")).andReturn();
  UUID user=UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).path("userId").asText());
  assertThat(access.members(OWNER,A,0,20).items()).anyMatch(m->m.userId().equals(user)&&m.role().equals("MEMBER"));
  mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(json(Map.of("email","new.colleague@example.test","password",PASSWORD)))).andExpect(status().isOk()).andExpect(jsonPath("$.tokenType").value("Bearer"));
  assertThat(count("users")).isEqualTo(6);assertThat(count("tenant_access_audit")).isEqualTo(2);
  fails(()->access.acceptNew(i.token(),"new.colleague@example.test",PASSWORD,correlation()),410);
 }
 @Test void anonymousExistingAccountNeverChangesItsPasswordOrLeaksRegistration()throws Exception{
  var i=invite(OWNER,email(OUTSIDER),"MEMBER");
  fails(()->access.acceptNew(i.token(),email(OUTSIDER),PASSWORD+"-different",correlation()),410);
  assertThat(access.invitations(OWNER,A,0,20).items().getFirst().status()).isEqualTo("PENDING");
  try(var c=admin(DB);var p=c.prepareStatement("SELECT password_hash FROM platform.users WHERE id=?")){p.setObject(1,OUTSIDER);try(var r=p.executeQuery()){r.next();assertThat(r.getString(1)).isEqualTo(HASH);}}
 }
 @Test void existingEnabledUserCanJoinSecondTenantWithoutChangingGlobalIdentity()throws Exception{
  var i=invite(OWNER,email(B_OWNER),"ADMIN");var accepted=access.acceptExisting(i.token(),B_OWNER,correlation());
  assertThat(accepted.userId()).isEqualTo(B_OWNER);assertThat(accepted.role()).isEqualTo("ADMIN");assertThat(count("users")).isEqualTo(5);
  assertThat(authorization.authorize(B_OWNER,B).role().name()).isEqualTo("OWNER");assertThat(authorization.authorize(B_OWNER,A).role().name()).isEqualTo("ADMIN");
  fails(()->access.acceptExisting(i.token(),B_OWNER,correlation()),410);
 }
 @Test void wrongIdentityWrongEmailAndExistingMembershipCannotBeOverwritten()throws Exception{
  var i=invite(OWNER,email(OUTSIDER),"ADMIN");fails(()->access.acceptExisting(i.token(),MEMBER,correlation()),410);fails(()->access.acceptNew(i.token(),"other@example.test",PASSWORD,correlation()),410);
  var current=invite(OWNER,email(MEMBER),"ADMIN");fails(()->access.acceptExisting(current.token(),MEMBER,correlation()),409);assertThat(authorization.authorize(MEMBER,A).role().name()).isEqualTo("MEMBER");
 }
 @Test void expiredCancelledAndMalformedTokensHaveGenericUnavailableOutcome()throws Exception{
  var expired=invite(OWNER,"expired@example.test","MEMBER");sql("UPDATE platform.tenant_invitations SET created_at=created_at-interval '49 hours',expires_at=expires_at-interval '49 hours' WHERE id=?",expired.invitation().id());
  assertThat(access.invitations(OWNER,A,0,20).items().getFirst().expired()).isTrue();fails(()->access.acceptNew(expired.token(),"expired@example.test",PASSWORD,correlation()),410);
  var cancelled=invite(OWNER,"cancelled@example.test","MEMBER");access.cancel(OWNER,A,cancelled.invitation().id(),correlation());fails(()->access.acceptNew(cancelled.token(),"cancelled@example.test",PASSWORD,correlation()),410);fails(()->access.cancel(OWNER,A,cancelled.invitation().id(),correlation()),409);
  fails(()->access.acceptExisting("bad",MEMBER,correlation()),410);
 }
 @Test void adminsOnlyCancelTheirOwnMemberInvitations()throws Exception{
  var own=invite(ADMIN,"own@example.test","MEMBER");var other=invite(OWNER,"other@example.test","MEMBER");var privileged=invite(OWNER,"privileged@example.test","ADMIN");
  fails(()->access.cancel(ADMIN,A,other.invitation().id(),correlation()),403);fails(()->access.cancel(ADMIN,A,privileged.invitation().id(),correlation()),403);fails(()->access.cancel(MEMBER,A,own.invitation().id(),correlation()),403);
  assertThat(access.cancel(ADMIN,A,own.invitation().id(),correlation()).status()).isEqualTo("CANCELLED");
 }
 @Test void duplicateLiveEmailAndFiveLiveQuotaAreEnforcedExpiredRowsDoNotCount()throws Exception{
  var old=invite(OWNER,"expired@example.test","MEMBER");sql("UPDATE platform.tenant_invitations SET created_at=created_at-interval '49 hours',expires_at=expires_at-interval '49 hours' WHERE id=?",old.invitation().id());
  invite(OWNER,"same@example.test","MEMBER");fails(()->invite(OWNER," SAME@example.test ","MEMBER"),409);
  for(int n=0;n<4;n++)invite(OWNER,"quota"+n+"@example.test","MEMBER");fails(()->invite(OWNER,"overflow@example.test","MEMBER"),429);assertThat(count("tenant_invitations")).isEqualTo(6);
 }
 @Test void roleChangesTakeEffectWithExistingJwtAndNoopDoesNotAudit()throws Exception{
  String token=bearer(MEMBER);access.change(OWNER,A,MEMBER,"ADMIN",false,correlation());assertThat(count("tenant_access_audit")).isEqualTo(1);
  access.change(OWNER,A,MEMBER,"ADMIN",false,correlation());assertThat(count("tenant_access_audit")).isEqualTo(1);
  mvc.perform(get(root(A)+"/context").header("Authorization",token)).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"));
  access.change(OWNER,A,MEMBER,"MEMBER",false,correlation());mvc.perform(get(root(A)+"/context").header("Authorization",token)).andExpect(jsonPath("$.role").value("MEMBER"));
 }
 @Test void ownerProtectionAndAdminMutationLimitsAreIndependentOfRequestClaims(){
  fails(()->access.change(OWNER,A,OWNER,null,true,correlation()),403);fails(()->access.change(ADMIN,A,ADMIN,null,true,correlation()),403);fails(()->access.change(ADMIN,A,OWNER,null,true,correlation()),403);fails(()->access.change(ADMIN,A,MEMBER,"ADMIN",false,correlation()),403);fails(()->access.change(MEMBER,A,ADMIN,null,true,correlation()),403);
  assertThatThrownBy(()->access.change(OWNER,A,MEMBER,"OWNER",false,correlation())).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void revocationImmediatelyBlocksExistingJwtButPreservesIdentityAndToolAudit()throws Exception{
  var p=tools.submit(ADMIN,A,AGENT_A,new ToolPayload(ToolPayload.NAME,new ToolPayload.Arguments("Review","Review application",ToolPayload.Priority.NORMAL),"Staff review"),correlation());tools.decide(OWNER,A,p.id(),p.payloadDigest(),true,null,correlation());
  sql("INSERT INTO platform.agent_runs(id,tenant_id,agent_id,user_id,question,status) VALUES(?,?,?,?,?,'RUNNING')",UUID.randomUUID(),A,AGENT_A,ADMIN,"Synthetic retained history");
  String token=bearer(ADMIN);access.change(OWNER,A,ADMIN,null,true,correlation());
  mvc.perform(get(root(A)+"/context").header("Authorization",token)).andExpect(status().isForbidden());assertThat(count("users")).isEqualTo(5);assertThat(count("tool_proposals")).isEqualTo(1);assertThat(count("tool_approval_decisions")).isEqualTo(1);assertThat(count("agent_runs")).isEqualTo(1);
  assertThat(access.members(OWNER,A,0,20).items()).noneMatch(m->m.userId().equals(ADMIN));
 }
 @Test void adminCanRevokeOnlyMembersAndOldInvitationsCannotRestoreRevokedAccess()throws Exception{
  var old=invite(OWNER,email(MEMBER),"MEMBER");access.change(ADMIN,A,MEMBER,null,true,correlation());fails(()->access.acceptExisting(old.token(),MEMBER,correlation()),410);
  access.cancel(OWNER,A,old.invitation().id(),correlation());var fresh=invite(OWNER,email(MEMBER),"MEMBER");assertThat(access.acceptExisting(fresh.token(),MEMBER,correlation()).role()).isEqualTo("MEMBER");
 }
 @Test void memberDirectoryRedactsOtherEmailsAndInvitationListingIsManagerOnly()throws Exception{
  var members=access.members(MEMBER,A,0,20).items();assertThat(members).filteredOn(m->!m.userId().equals(MEMBER)).allMatch(m->m.email()==null);assertThat(members).filteredOn(m->m.userId().equals(MEMBER)).allMatch(m->m.email().equals(email(MEMBER)));
  fails(()->access.invitations(MEMBER,A,0,20),403);assertThat(access.members(OWNER,A,0,1).hasNext()).isTrue();
 }
 @Test void strictApiRejectsAuthorityFieldsWeakPasswordsAndUnauthenticatedExistingAcceptance()throws Exception{
  mvc.perform(post("/api/v1/invitations/accept-existing").contentType("application/json").content("{\"token\":\"x\"}")).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/v1/invitations/accept-new").contentType("application/json").content(json(Map.of("token",InvitationSecret.generate(),"email","new@example.test","password","short")))).andExpect(status().isBadRequest());
  postAs(OWNER,root(A)+"/invitations",Map.of("email","new@example.test","role","MEMBER","tenantId",B)).andExpect(status().isBadRequest());
  mvc.perform(get(root(A)+"/members?size=101").header("Authorization",bearer(OWNER))).andExpect(status().isBadRequest());
  mvc.perform(get(root(A)+"/members").header("Authorization",bearer(MEMBER)).header("X-Role","OWNER")).andExpect(status().isOk());
 }
 @Test void runtimeHasNoIdentityInvitationHashAuditOrDdlPrivilegesAndMissingContextFailsClosed()throws Exception{
  try(var c=DriverManager.getConnection(DB.getJdbcUrl(),"eaap_runtime",RUNTIME);var s=c.createStatement()){
   for(String q:List.of("SELECT token_hash FROM platform.tenant_invitations","SELECT * FROM platform.tenant_access_audit","UPDATE platform.users SET enabled=false","DELETE FROM platform.tenant_memberships","INSERT INTO platform.tenant_memberships VALUES(gen_random_uuid(),gen_random_uuid(),'OWNER',now())","ALTER TABLE platform.tenant_invitations DISABLE ROW LEVEL SECURITY","SET ROLE eaap_migration","SELECT platform.access_actor(gen_random_uuid(),gen_random_uuid())"))assertThatThrownBy(()->s.execute(q)).isInstanceOf(SQLException.class);
   try(var p=c.prepareStatement("SELECT * FROM platform.access_members(?,?,20,0)")){p.setObject(1,A);p.setObject(2,OWNER);assertThatThrownBy(p::executeQuery).isInstanceOf(SQLException.class).hasMessageContaining("access_forbidden");}
  }
 }
 @Test void crossTenantFunctionsRejectMismatchedContextAndLiveRoleAbuse()throws Exception{
  var jdbc=new JdbcTemplate(source);tx.execute(authorization.authorize(OWNER,A),()->{assertThatThrownBy(()->jdbc.queryForList("SELECT * FROM platform.access_members(?,?,20,0)",B,B_OWNER)).hasMessageContaining("access_forbidden");return null;});
 }
 @Test void failedMembershipInsertionRollsBackIdentityInvitationAndAudit()throws Exception{
  var i=invite(OWNER,"rollback@example.test","MEMBER");sql("CREATE FUNCTION platform.test_reject_member() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''test-only failure''; END'");sql("CREATE TRIGGER test_reject BEFORE INSERT ON platform.tenant_memberships FOR EACH ROW EXECUTE FUNCTION platform.test_reject_member()");
  try{fails(()->access.acceptNew(i.token(),"rollback@example.test",PASSWORD,correlation()),500);assertThat(count("users")).isEqualTo(5);assertThat(count("tenant_access_audit")).isEqualTo(1);assertThat(access.invitations(OWNER,A,0,20).items().getFirst().status()).isEqualTo("PENDING");}
  finally{sql("DROP TRIGGER test_reject ON platform.tenant_memberships");sql("DROP FUNCTION platform.test_reject_member()");}
 }
 @Test void concurrentAcceptanceHasOneWinnerAndOneMembership()throws Exception{
  var i=invite(OWNER,"race@example.test","MEMBER");var results=race(()->access.acceptNew(i.token(),"race@example.test",PASSWORD,correlation()),()->access.acceptNew(i.token(),"race@example.test",PASSWORD,correlation()));assertThat(results).filteredOn(x->x instanceof Accepted).hasSize(1);assertThat(count("users")).isEqualTo(6);assertThat(count("tenant_memberships")).isEqualTo(5);assertThat(count("tenant_access_audit")).isEqualTo(2);
 }
 @Test void concurrentQuotaAndDuplicateIssuanceCannotOverbook()throws Exception{
  for(int n=0;n<4;n++)invite(OWNER,"quota"+n+"@example.test","MEMBER");var results=race(()->invite(OWNER,"last-a@example.test","MEMBER"),()->invite(ADMIN,"last-b@example.test","MEMBER"));assertThat(results).filteredOn(x->x instanceof Issued).hasSize(1);assertThat(count("tenant_invitations")).isEqualTo(5);
 }
 @Test void pooledContextIsClearedAfterAnonymousAcceptanceAndTenantTransactions()throws Exception{
  var i=invite(OWNER,"pool@example.test","MEMBER");access.acceptNew(i.token(),"pool@example.test",PASSWORD,correlation());
  for(int n=0;n<5;n++)assertThat(new JdbcTemplate(source).queryForObject("SELECT platform.current_tenant_id()",UUID.class)).isNull();
 }
 @Test void runtimeCapabilitiesRecheckActorRoleAndRejectOwnerAssignment()throws Exception{
  record Attempt(UUID actor,UUID tenant,UUID target,String role,boolean revoke){}
  for(var attempt:List.of(new Attempt(ADMIN,A,MEMBER,"ADMIN",false),new Attempt(MEMBER,A,ADMIN,null,true),new Attempt(OWNER,A,MEMBER,"OWNER",false),new Attempt(OWNER,A,OWNER,null,true),new Attempt(OWNER,B,B_OWNER,null,true))) {
   try(var c=DriverManager.getConnection(DB.getJdbcUrl(),"eaap_runtime",RUNTIME)){
    c.setAutoCommit(false);com.elmacore.agentplatform.infrastructure.persistence.PostgresTenantContext.apply(c,A);
    try(var p=c.prepareStatement("SELECT platform.access_member_change(?,?,?,?,?,?)")){
     p.setObject(1,attempt.tenant());p.setObject(2,attempt.actor());p.setObject(3,attempt.target());p.setString(4,attempt.role());p.setBoolean(5,attempt.revoke());p.setObject(6,correlation());
     assertThatThrownBy(p::execute).isInstanceOf(SQLException.class);
    }finally{c.rollback();}
   }
  }
  assertThat(authorization.authorize(MEMBER,A).role().name()).isEqualTo("MEMBER");assertThat(count("tenant_access_audit")).isZero();
 }
 @Test void disabledRecipientAndRevokedIssuerCannotRedeemInvitations()throws Exception{
  var disabled=invite(OWNER,email(OUTSIDER),"MEMBER");sql("UPDATE platform.users SET enabled=false WHERE id=?",OUTSIDER);
  fails(()->access.acceptExisting(disabled.token(),OUTSIDER,correlation()),410);
  var issued=invite(ADMIN,"issuer-revoked@example.test","MEMBER");access.change(OWNER,A,ADMIN,null,true,correlation());
  fails(()->access.acceptNew(issued.token(),"issuer-revoked@example.test",PASSWORD,correlation()),410);
  assertThat(count("users")).isEqualTo(5);
 }
 @Test void concurrentDuplicateEmailProducesOnlyOneLiveInvitation()throws Exception{
  var results=race(()->invite(OWNER,"duplicate@example.test","MEMBER"),()->invite(ADMIN," DUPLICATE@example.test ","MEMBER"));
  assertThat(results).filteredOn(x->x instanceof Issued).hasSize(1);
  assertThat(results).filteredOn(x->x instanceof AccessFailure f&&f.status()==409).hasSize(1);
  assertThat(count("tenant_invitations")).isEqualTo(1);assertThat(count("tenant_access_audit")).isEqualTo(1);
 }
 @Test void acceptanceSecretsAndDatabaseFailuresStayOutOfDebugLogs(CapturedOutput logs)throws Exception{
  var i=invite(OWNER,"private-new@example.test","MEMBER");
  String hash=java.util.HexFormat.of().formatHex(InvitationSecret.hash(i.token()));
  mvc.perform(post("/api/v1/invitations/accept-new").contentType("application/json").content(json(Map.of("token",i.token(),"email","wrong@example.test","password",PASSWORD)))).andExpect(status().isGone());
  var accepted=access.acceptNew(i.token(),"private-new@example.test",PASSWORD,correlation());
  fails(()->access.cancel(OWNER,A,i.invitation().id(),correlation()),409);
  assertThat(accepted.role()).isEqualTo("MEMBER");
  assertThat(logs.getAll()).doesNotContain(i.token(),PASSWORD,HASH,hash);
 }
 @Test void concurrentRedemptionAndRevocationCannotRestoreExistingMember()throws Exception{
  var issued=invite(OWNER,email(MEMBER),"ADMIN");
  var results=race(()->access.acceptExisting(issued.token(),MEMBER,correlation()),()->{access.change(OWNER,A,MEMBER,null,true,correlation());return "revoked";});
  assertThat(results).contains("revoked");
  assertThat(results).filteredOn(x->x instanceof AccessFailure f&&(f.status()==409||f.status()==410)).hasSize(1);
  assertThat(access.members(OWNER,A,0,20).items()).noneMatch(m->m.userId().equals(MEMBER));
  fails(()->access.acceptExisting(issued.token(),MEMBER,correlation()),410);
 }
 @Test void concurrentCancellationAndAcceptanceHaveOneTerminalWinner()throws Exception{
  var issued=invite(OWNER,"cancel-race@example.test","MEMBER");
  var results=race(()->access.acceptNew(issued.token(),"cancel-race@example.test",PASSWORD,correlation()),()->access.cancel(OWNER,A,issued.invitation().id(),correlation()));
  assertThat(results).filteredOn(x->x instanceof AccessFailure).hasSize(1);
  var status=access.invitations(OWNER,A,0,20).items().getFirst().status();
  if(status.equals("ACCEPTED")){assertThat(results).filteredOn(x->x instanceof Accepted).hasSize(1);assertThat(count("users")).isEqualTo(6);}
  else{assertThat(status).isEqualTo("CANCELLED");assertThat(count("users")).isEqualTo(5);}
  assertThat(count("tenant_access_audit")).isEqualTo(2);
 }
 List<Object> race(Callable<?> one,Callable<?> two)throws Exception{var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{var a=pool.submit(()->{start.await();try{return one.call();}catch(Exception e){return e;}});var b=pool.submit(()->{start.await();try{return two.call();}catch(Exception e){return e;}});start.countDown();return List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));}finally{pool.shutdownNow();}}
}
