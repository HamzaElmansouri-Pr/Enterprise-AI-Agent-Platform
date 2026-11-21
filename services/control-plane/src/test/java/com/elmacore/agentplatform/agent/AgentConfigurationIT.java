package com.elmacore.agentplatform.agent;

import com.elmacore.agentplatform.agent.application.AgentConfigurations;
import com.elmacore.agentplatform.agent.domain.AgentConfiguration;
import com.elmacore.agentplatform.agent.domain.AgentDetails;
import com.elmacore.agentplatform.agent.infrastructure.persistence.JdbcAgentStore;
import com.elmacore.agentplatform.identity.application.MembershipAuthorization;
import com.elmacore.agentplatform.identity.infrastructure.ArgonPasswords;
import com.elmacore.agentplatform.identity.infrastructure.JwtTokens;
import com.elmacore.agentplatform.infrastructure.persistence.PostgresTenantContext;
import com.elmacore.agentplatform.tenancy.application.TenantTransactions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static com.elmacore.agentplatform.agent.AgentTestDatabase.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(AgentSqlProbe.Configuration.class)
class AgentConfigurationIT {
    @Container static final PostgreSQLContainer<?> DB = AgentTestDatabase.container();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) { configure(DB, properties); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtTokens tokens;
    @Autowired DataSource source;
    @Autowired MembershipAuthorization authorization;
    @Autowired TenantTransactions transactions;
    @Autowired AgentConfigurations agents;
    private static final String HASH = new ArgonPasswords().encode("agent-test-only-password");
    private static final String INSTRUCTIONS_A = "private-tenant-A-instructions";
    private static final String INSTRUCTIONS_B = "private-tenant-B-instructions";

    @BeforeEach void seed() throws Exception {
        AgentSqlProbe.hook = null;
        try (var connection = admin(DB); var statement = connection.createStatement()) {
            statement.execute("DELETE FROM platform.agent_configurations; DELETE FROM platform.tenant_memberships; DELETE FROM platform.users; DELETE FROM platform.tenants");
            try (var insert = connection.prepareStatement("INSERT INTO platform.tenants(id,name) VALUES (?, 'A'), (?, 'B')")) {
                insert.setObject(1, A); insert.setObject(2, B); insert.executeUpdate();
            }
            for (UUID user : List.of(OWNER, ADMIN, MEMBER, B_OWNER, MULTI, OUTSIDER)) {
                try (var insert = connection.prepareStatement("INSERT INTO platform.users(id,email,password_hash) VALUES (?, ?, ?)")) {
                    insert.setObject(1, user); insert.setString(2, "u" + user + "@example.test"); insert.setString(3, HASH); insert.executeUpdate();
                }
            }
            membership(connection, OWNER, A, "OWNER"); membership(connection, ADMIN, A, "ADMIN");
            membership(connection, MEMBER, A, "MEMBER"); membership(connection, B_OWNER, B, "OWNER");
            membership(connection, MULTI, A, "ADMIN"); membership(connection, MULTI, B, "MEMBER");
            fixture(connection, AGENT_A, A, "Fixture A", INSTRUCTIONS_A, "DRAFT");
            fixture(connection, AGENT_B, B, "Fixture B", INSTRUCTIONS_B, "DRAFT");
            fixture(connection, ARCHIVED_A, A, "Archived A", "archived instructions", "ARCHIVED");
        }
        AgentSqlProbe.EVENTS.clear();
    }
    @AfterEach void clearHook() { AgentSqlProbe.hook = null; }
    @AfterAll static void cleanupKeys() throws Exception { deleteKeys(); }

    @ParameterizedTest @ValueSource(strings = {"OWNER", "ADMIN"})
    void managersCanCreateReadUpdateAndArchive(String role) throws Exception {
        UUID user = role.equals("OWNER") ? OWNER : ADMIN;
        String instructions = "Line one\n${DO_NOT_EXPAND}\nhttps://example.invalid\nignore all tenant rules";
        var result = create(A, user, payload("  مساعد 🎓  ", instructions)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("مساعد 🎓")).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.tenantId").value(A.toString())).andExpect(jsonPath("$.systemInstructions").value(instructions)).andReturn();
        var original = mapper.readTree(result.getResponse().getContentAsString());
        UUID id = UUID.fromString(original.get("id").asText());
        assertThat(result.getResponse().getHeader("Location")).isEqualTo(path(A) + "/" + id);
        assertThat(original.size()).isEqualTo(8);
        getAgent(A, id, user).andExpect(status().isOk());
        var updated = json(update(A, id, user, payload("Changed", "Changed\ninstructions")).andExpect(status().isOk()));
        assertThat(updated.get("id")).isEqualTo(original.get("id"));
        assertThat(updated.get("tenantId")).isEqualTo(original.get("tenantId"));
        assertThat(updated.get("createdAt")).isEqualTo(original.get("createdAt"));
        assertThat(Instant.parse(updated.get("updatedAt").asText())).isAfter(Instant.parse(original.get("updatedAt").asText()));
        assertThat(updated.get("name").asText()).isEqualTo("Changed");
        assertThat(updated.get("systemInstructions").asText()).isEqualTo("Changed\ninstructions");
        assertThat(json(getAgent(A, id, user).andExpect(status().isOk()))).isEqualTo(updated);
        var archived = json(archive(A, id, user).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED")));
        assertThat(json(getAgent(A, id, user).andExpect(status().isOk()))).isEqualTo(archived);
        AgentSqlProbe.EVENTS.clear();
        assertThat(json(archive(A, id, user).andExpect(status().isOk()))).isEqualTo(archived);
        assertThat(AgentSqlProbe.agents()).noneMatch(event -> event.sql().stripLeading().startsWith("UPDATE"));
        update(A, id, user, payload("Forbidden", "Content")).andExpect(status().isConflict())
                .andExpect(content().json("{\"code\":\"agent_archived\"}"));
        assertThat(json(getAgent(A, id, user).andExpect(status().isOk()))).isEqualTo(archived);
    }
    @Test void membersHaveReadOnlyAccessAndCallerRolesAreIgnored() throws Exception {
        getAgent(A, AGENT_A, MEMBER).andExpect(status().isOk());
        list(A, MEMBER, "").andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
        create(A, MEMBER, payload("Denied", "Data")).andExpect(status().isForbidden());
        update(A, AGENT_A, MEMBER, payload("Denied", "Data")).andExpect(status().isForbidden());
        archive(A, AGENT_A, MEMBER).andExpect(status().isForbidden());
        mvc.perform(post(path(A)).header("Authorization", "Bearer " + signedMemberRole()).header("X-Role", "OWNER")
                .header("X-Tenant-ID", B).queryParam("role", "OWNER").contentType("application/json")
                .content(payload("Denied", "Data"))).andExpect(status().isForbidden());
        assertThat(AgentSqlProbe.agents()).noneMatch(event -> event.sql().stripLeading().startsWith("INSERT") || event.sql().stripLeading().startsWith("UPDATE"));
    }
    @Test void nonexistentAndCrossTenantAgentsHaveTheSame404AndNoDataLeaks() throws Exception {
        var hidden = getAgent(A, AGENT_B, OWNER).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        var absent = getAgent(A, UUID.randomUUID(), OWNER).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(hidden).isEqualTo(absent).doesNotContain(INSTRUCTIONS_B);
        update(A, AGENT_B, OWNER, payload("Forbidden", "Data")).andExpect(status().isNotFound());
        archive(A, AGENT_B, OWNER).andExpect(status().isNotFound());
        var listing = list(A, OWNER, "").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(listing).contains(INSTRUCTIONS_A).doesNotContain(INSTRUCTIONS_B, AGENT_B.toString());
        AgentSqlProbe.EVENTS.clear();
        list(B, OWNER, "").andExpect(status().isForbidden());
        create(B, OWNER, payload("Forbidden", "Data")).andExpect(status().isForbidden());
        assertThat(AgentSqlProbe.EVENTS).noneMatch(event -> event.sql().contains("set_config") || event.sql().contains("platform.agent_configurations"));
    }
    @Test void multiTenantUserGetsTheCorrectScopeAndRoleForEachRequest() throws Exception {
        list(A, MULTI, "").andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
        list(B, MULTI, "").andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(AGENT_B.toString()));
        create(A, MULTI, payload("Allowed", "Data")).andExpect(status().isCreated());
        create(B, MULTI, payload("Denied", "Data")).andExpect(status().isForbidden());
        list(A, OUTSIDER, "").andExpect(status().isForbidden());
    }
    @Test void removedMembershipImmediatelyBlocksAllAgentOperations() throws Exception {
        String token = tokens.issue(OWNER);
        mvc.perform(get(path(A)).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        try (var connection = admin(DB); var statement = connection.prepareStatement("DELETE FROM platform.tenant_memberships WHERE user_id = ? AND tenant_id = ?")) {
            statement.setObject(1, OWNER); statement.setObject(2, A); statement.executeUpdate();
        }
        for (var request : List.of(get(path(A)), get(path(A) + "/" + AGENT_A),
                post(path(A)).contentType("application/json").content(payload("Denied", "Data")),
                put(path(A) + "/" + AGENT_A).contentType("application/json").content(payload("Denied", "Data")),
                post(path(A) + "/" + AGENT_A + "/archive"))) {
            mvc.perform(request.header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        }
    }
    @Test void disabledUserAndMissingOrInvalidTokensCannotUseAnyApprovedRoute() throws Exception {
        for (var request : List.of(get(path(A)), get(path(A) + "/" + AGENT_A), post(path(A)),
                put(path(A) + "/" + AGENT_A), post(path(A) + "/" + AGENT_A + "/archive"))) {
            mvc.perform(request).andExpect(status().isUnauthorized());
            mvc.perform(request.header("Authorization", "Bearer invalid.token")).andExpect(status().isUnauthorized());
        }
        String token = tokens.issue(OWNER);
        try (var connection = admin(DB); var statement = connection.prepareStatement("UPDATE platform.users SET enabled = false WHERE id = ?")) {
            statement.setObject(1, OWNER); statement.executeUpdate();
        }
        mvc.perform(get(path(A)).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }
    @ParameterizedTest @ValueSource(strings = {"id", "tenantId", "status", "createdAt", "updatedAt", "role", "model", "tools", "unknown"})
    void clientControlledOrUnapprovedPropertiesAreRejected(String property) throws Exception {
        String body = "{\"name\":\"Valid\",\"systemInstructions\":\"Valid\",\"" + property + "\":\"untrusted\"}";
        create(A, OWNER, body).andExpect(status().isBadRequest());
        update(A, AGENT_A, OWNER, body).andExpect(status().isBadRequest());
    }
    @Test void invalidPayloadTypesUnicodeAndBodySizeAreRejected() throws Exception {
        for (String body : List.of("{}", "[]", "null", "{", "{\"name\":1,\"systemInstructions\":\"Valid\"}",
                "{\"name\":\"Valid\",\"systemInstructions\":123}", "{\"name\":\"Valid\",\"description\":4,\"systemInstructions\":\"Valid\"}",
                "{\"name\":\"One\",\"name\":\"Two\",\"systemInstructions\":\"Valid\"}", "{} {}")) {
            create(A, OWNER, body).andExpect(status().isBadRequest());
        }
        for (String name : List.of("", " \t ", "x".repeat(121), "unsafe\nname", "unsafe\u202ename")) {
            create(A, OWNER, payload(name, "Valid")).andExpect(status().isBadRequest());
        }
        for (String instructions : List.of("", " \n\t", "\u00a0\u3000", "x".repeat(10001), "private\0content")) {
            create(A, OWNER, payload("Valid", instructions)).andExpect(status().isBadRequest());
        }
        create(A, OWNER, mapper.writeValueAsString(Map.of("name", "Valid", "description", "x".repeat(1001), "systemInstructions", "Valid")))
                .andExpect(status().isBadRequest());
        create(A, OWNER, "x".repeat(160 * 1024 + 1)).andExpect(status().isBadRequest());
        mvc.perform(post(path(A)).header("Authorization", "Bearer " + tokens.issue(OWNER)).contentType("text/plain").content("not JSON"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post(path(A) + "/" + AGENT_A + "/archive").header("Authorization", "Bearer " + tokens.issue(OWNER))
                .contentType("application/json").content("{\"role\":\"OWNER\"}")).andExpect(status().isBadRequest());
        create(A, OWNER, payload("🎓".repeat(120), "🎓".repeat(10000))).andExpect(status().isCreated());
    }
    @ParameterizedTest @ValueSource(strings = {"?page=-1", "?size=0", "?size=101", "?page=2147483647&size=100", "?page=50000000000", "?page=abc", "?status=ACTIVE", "?status=draft", "?status="})
    void invalidPaginationAndFiltersReturn400(String query) throws Exception { list(A, OWNER, query).andExpect(status().isBadRequest()); }
    @Test void invalidUuidsAndUnapprovedRoutesAreDenied() throws Exception {
        mvc.perform(get("/api/v1/tenants/invalid/agents").header("Authorization", "Bearer " + tokens.issue(OWNER))).andExpect(status().isBadRequest());
        mvc.perform(get(path(A) + "/invalid").header("Authorization", "Bearer " + tokens.issue(OWNER))).andExpect(status().isBadRequest());
        for (var request : List.of(delete(path(A) + "/" + AGENT_A), post(path(A) + "/" + AGENT_A + "/restore"),
                post(path(A) + "/" + AGENT_A + "/execute"), get(path(A) + "/" + AGENT_A + "/archive"), get("/api/v1/unrelated"))) {
            mvc.perform(request.header("Authorization", "Bearer " + tokens.issue(OWNER))).andExpect(status().isForbidden());
        }
    }
    @Test void paginationHasDeterministicOrderAndSizePlusOneWithoutCountOrRepeatedMembershipLookup() throws Exception {
        try (var connection = admin(DB); var statement = connection.createStatement()) {
            statement.execute("DELETE FROM platform.agent_configurations WHERE tenant_id = '" + A + "'");
            for (int i = 10; i < 15; i++) fixture(connection, new UUID(3, i), A, "Agent " + i, "Data", i % 2 == 0 ? "DRAFT" : "ARCHIVED");
        }
        AgentSqlProbe.EVENTS.clear();
        var first = json(list(A, MEMBER, "?size=2").andExpect(status().isOk()).andExpect(jsonPath("$.hasNext").value(true)));
        assertThat(first.get("items").get(0).get("id").asText()).isEqualTo(new UUID(3, 14).toString());
        assertThat(first.get("items").get(1).get("id").asText()).isEqualTo(new UUID(3, 13).toString());
        assertThat(first.get("page").asInt()).isZero(); assertThat(first.get("size").asInt()).isEqualTo(2);
        assertThat(AgentSqlProbe.EVENTS.stream().filter(event -> event.sql().contains("membership_role")).count()).isEqualTo(1);
        assertThat(AgentSqlProbe.agents()).hasSize(1).allMatch(event -> !event.sql().toUpperCase().contains("COUNT("));
        list(A, MEMBER, "?page=1&size=2").andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.hasNext").value(true));
        list(A, MEMBER, "?page=2&size=2").andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.hasNext").value(false));
        list(A, MEMBER, "?page=0&size=2&status=DRAFT").andExpect(status().isOk()).andExpect(jsonPath("$.items[1].id").value(new UUID(3, 12).toString())).andExpect(jsonPath("$.hasNext").value(true));
        list(A, MEMBER, "?page=1&size=2&status=DRAFT").andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.hasNext").value(false));
        list(A, MEMBER, "?status=ARCHIVED").andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
        list(A, MEMBER, "?page=500&size=20").andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
    }
    @Test void httpOperationsUseTheExactContextConnectionAndPooledContextsDoNotLeak() throws Exception {
        AgentSqlProbe.EVENTS.clear();
        create(A, OWNER, payload("Scoped", "Data")).andExpect(status().isCreated());
        var events = List.copyOf(AgentSqlProbe.EVENTS);
        var context = events.stream().filter(event -> event.sql().contains("set_config")).findFirst().orElseThrow();
        assertThat(AgentSqlProbe.agents()).isNotEmpty().allMatch(event -> event.backend() == context.backend() && A.toString().equals(event.tenant()));
        list(B, B_OWNER, "").andExpect(status().isOk());
        try (var one = source.getConnection(); var two = source.getConnection()) {
            for (Connection connection : List.of(one, two)) {
                assertThat(scalar(connection, "SELECT platform.current_tenant_id()")).isNull();
                assertThat(scalar(connection, "SELECT count(*) FROM platform.agent_configurations")).isEqualTo("0");
            }
        }
    }
    @Test void sharedTransactionsRejectMissingOrNestedContextAndRollbackAllWrites() throws Exception {
        var tenant = authorization.authorize(OWNER, A);
        assertThrows(NullPointerException.class, () -> transactions.execute(null, () -> { throw new AssertionError("must not execute"); }));
        var outer = new TransactionTemplate(new JdbcTransactionManager(source));
        assertThrows(IllegalStateException.class, () -> outer.execute(status -> transactions.execute(tenant, () -> "denied")));
        var store = new JdbcAgentStore(source);
        assertThrows(IllegalStateException.class, () -> store.find(A, AGENT_A));
        UUID id = UUID.randomUUID();
        assertThrows(DataAccessException.class, () -> transactions.execute(tenant, () -> {
            store.insert(AgentConfiguration.draft(id, A, new AgentDetails("Rollback", null, "private-rollback-data"), Instant.now()));
            new JdbcTemplate(source).update("UPDATE platform.agent_configurations SET name = '' WHERE tenant_id = ? AND id = ?", A, id);
            return null;
        }));
        assertThrows(com.elmacore.agentplatform.agent.application.AgentNotFound.class, () -> agents.get(OWNER, A, id));
        try (var connection = source.getConnection()) { assertThat(scalar(connection, "SELECT platform.current_tenant_id()")).isNull(); }
        assertThat(DB.getLogs()).doesNotContain("private-rollback-data", INSTRUCTIONS_A, INSTRUCTIONS_B);
    }
    @Test @ExtendWith(OutputCaptureExtension.class)
    void internalDatabaseErrorsAreSanitizedAndInstructionsAreNeverLogged(CapturedOutput output) throws Exception {
        String secret = "private-request-instructions-never-log";
        try (var connection = admin(DB); var statement = connection.createStatement()) {
            statement.execute("CREATE FUNCTION platform.test_agent_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test-only failure'; END $$");
            statement.execute("CREATE TRIGGER test_agent_failure BEFORE INSERT ON platform.agent_configurations FOR EACH ROW EXECUTE FUNCTION platform.test_agent_failure()");
            try {
                create(A, OWNER, payload("Private", secret)).andExpect(status().isInternalServerError()).andExpect(content().json("{\"code\":\"internal_error\"}"));
            } finally { statement.execute("DROP TRIGGER test_agent_failure ON platform.agent_configurations; DROP FUNCTION platform.test_agent_failure()"); }
        }
        assertThat(output.getAll()).doesNotContain(secret);
        assertThat(DB.getLogs()).doesNotContain(secret, INSTRUCTIONS_A, INSTRUCTIONS_B, HASH, RUNTIME, MIGRATION, OPERATOR);
    }
    @Test void runtimeRlsCannotReadInsertReassignUpdateArchiveDeleteOrAdministerOtherTenant() throws Exception {
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false); PostgresTenantContext.apply(connection, A);
            assertThat(scalar(connection, "SELECT count(*) FROM platform.agent_configurations")).isEqualTo("2");
            assertThat(scalar(connection, "SELECT count(*) FROM platform.agent_configurations WHERE id = '" + AGENT_B + "'")).isEqualTo("0");
            try (var statement = connection.createStatement()) {
                assertThat(statement.executeUpdate("UPDATE platform.agent_configurations SET name = 'denied' WHERE id = '" + AGENT_B + "'")).isZero();
                assertThat(statement.executeUpdate("UPDATE platform.agent_configurations SET status = 'ARCHIVED' WHERE id = '" + AGENT_B + "'")).isZero();
                assertThat(statement.executeUpdate("UPDATE platform.agent_configurations SET status = 'DRAFT' WHERE id = '" + ARCHIVED_A + "'")).isZero();
            }
            connection.rollback();
        }
        denied("INSERT INTO platform.agent_configurations(id,tenant_id,name,system_instructions) VALUES (gen_random_uuid(), '" + B + "','Denied','Data')", "42501");
        denied("UPDATE platform.agent_configurations SET tenant_id = '" + B + "' WHERE id = '" + AGENT_A + "'", "42501");
        denied("UPDATE platform.agent_configurations SET id = gen_random_uuid()", "42501");
        denied("UPDATE platform.agent_configurations SET created_at = CURRENT_TIMESTAMP", "42501");
        for (String sql : List.of("DELETE FROM platform.agent_configurations", "TRUNCATE platform.agent_configurations",
                "ALTER TABLE platform.agent_configurations DISABLE ROW LEVEL SECURITY", "DROP POLICY agents_tenant_read ON platform.agent_configurations",
                "CREATE TABLE platform.forbidden_agent_table(id integer)", "SET ROLE eaap_migration")) denied(sql, "42501");
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false); statement.execute("SET LOCAL row_security = off");
            assertThat(assertThrows(SQLException.class, () -> statement.executeQuery("SELECT * FROM platform.agent_configurations")).getSQLState()).isEqualTo("42501");
            connection.rollback();
        }
        try (var connection = source.getConnection()) {
            assertThat(scalar(connection, "SELECT count(*) FROM platform.agent_configurations")).isEqualTo("0");
            assertThat(scalar(connection, "SELECT rolsuper OR rolbypassrls OR rolcreaterole FROM pg_roles WHERE rolname=current_user")).isEqualTo("f");
            assertThat(scalar(connection, "SELECT count(*) FROM pg_class WHERE oid = 'platform.agent_configurations'::regclass AND relrowsecurity AND relforcerowsecurity AND relowner='eaap_migration'::regrole")).isEqualTo("1");
            try (var statement = connection.createStatement()) {
                assertThat(assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO platform.agent_configurations(id,tenant_id,name,system_instructions) VALUES (gen_random_uuid(), '" + A + "','Denied','Data')")).getSQLState()).isEqualTo("42501");
            }
        }
    }
    @Test void databaseConstraintsMirrorDomainBoundsAndUnicodeRules() throws Exception {
        for (String name : List.of("", " ", " Leading", "Trailing ", "\u00a0Name", "bad\nname", "bad\u202ename")) invalidRow(name, "Valid", "23514");
        invalidRow("x".repeat(121), "Valid", "22001");
        for (String instructions : List.of("", " \n\t", "\u00a0\u3000", "x".repeat(10001))) invalidRow("Valid", instructions, "23514");
        denied("INSERT INTO platform.agent_configurations(id,tenant_id,name,system_instructions,status) VALUES(gen_random_uuid(),'" + A + "','Name','Data','ACTIVE')", "42501");
        denied("INSERT INTO platform.agent_configurations(id,tenant_id,name,system_instructions,status) VALUES(gen_random_uuid(),'" + A + "','Name','Data','ARCHIVED')", "42501");
        denied("INSERT INTO platform.agent_configurations(id,tenant_id,name,description,system_instructions) VALUES(gen_random_uuid(),'" + A + "','Name',repeat('x',1001),'Data')", "22001");
        denied("INSERT INTO platform.agent_configurations(id,tenant_id,name,system_instructions,created_at,updated_at) VALUES(gen_random_uuid(),'" + A + "','Name','Data','2026-02-01','2026-01-01')", "23514");
        // Runtime INSERT policy rejects every non-DRAFT status before CHECK evaluation.
        // A trusted setup connection separately proves the schema's closed status set.
        try (var connection = admin(DB); var statement = connection.prepareStatement(
                "INSERT INTO platform.agent_configurations(id,tenant_id,name,system_instructions,status) VALUES(?,?,'Name','Data','ACTIVE')")) {
            statement.setObject(1, UUID.randomUUID()); statement.setObject(2, A);
            assertThat(assertThrows(SQLException.class, statement::executeUpdate).getSQLState()).isEqualTo("23514");
        }
        var token = authorization.authorize(OWNER, A);
        transactions.execute(token, () -> new JdbcAgentStore(source).insert(AgentConfiguration.draft(UUID.randomUUID(), A,
                new AgentDetails("🎓".repeat(120), "🎓".repeat(1000), "🎓".repeat(10000)), Instant.now())));
    }
    @Test void aCompletedArchiveWinsAgainstAnUpdateThatReadAnEarlierDraft() throws Exception {
        var prepared = new CountDownLatch(1); var release = new CountDownLatch(1);
        AgentSqlProbe.hook = sql -> { if (sql.contains("UPDATE platform.agent_configurations") && sql.contains("SET name")) { prepared.countDown(); await(release); } };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var update = executor.submit(() -> update(A, AGENT_A, OWNER, payload("Must not overwrite", "Changed")).andReturn().getResponse().getStatus());
            try {
                assertThat(prepared.await(5, TimeUnit.SECONDS)).isTrue();
                archive(A, AGENT_A, ADMIN).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"));
            } finally { release.countDown(); }
            assertThat(update.get(5, TimeUnit.SECONDS)).isEqualTo(409);
        }
        getAgent(A, AGENT_A, MEMBER).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Fixture A")).andExpect(jsonPath("$.status").value("ARCHIVED"));
    }
    @Test void archiveThatReadAnEarlierDraftPreservesAConcurrentCompletedUpdate() throws Exception {
        var prepared = new CountDownLatch(1); var release = new CountDownLatch(1);
        AgentSqlProbe.hook = sql -> { if (sql.contains("UPDATE platform.agent_configurations") && sql.contains("SET status")) { prepared.countDown(); await(release); } };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var archive = executor.submit(() -> json(archive(A, AGENT_A, OWNER).andExpect(status().isOk())));
            try {
                assertThat(prepared.await(5, TimeUnit.SECONDS)).isTrue();
                update(A, AGENT_A, ADMIN, payload("Concurrent update", "Updated content")).andExpect(status().isOk());
            } finally { release.countDown(); }
            var result = archive.get(5, TimeUnit.SECONDS);
            assertThat(result.get("status").asText()).isEqualTo("ARCHIVED");
            assertThat(result.get("name").asText()).isEqualTo("Concurrent update");
        }
    }
    @Test void concurrentArchivalReturnsTheSameTerminalRepresentation() throws Exception {
        var prepared = new CountDownLatch(2); var release = new CountDownLatch(1);
        AgentSqlProbe.hook = sql -> { if (sql.contains("UPDATE platform.agent_configurations") && sql.contains("SET status")) { prepared.countDown(); await(release); } };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> json(archive(A, AGENT_A, OWNER).andExpect(status().isOk())));
            var second = executor.submit(() -> json(archive(A, AGENT_A, ADMIN).andExpect(status().isOk())));
            try { assertThat(prepared.await(5, TimeUnit.SECONDS)).isTrue(); } finally { release.countDown(); }
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(second.get(5, TimeUnit.SECONDS));
        }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test latch timed out"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
    private void invalidRow(String name, String instructions, String state) throws Exception {
        try (var connection = source.getConnection(); var statement = connection.prepareStatement("INSERT INTO platform.agent_configurations(id,tenant_id,name,system_instructions) VALUES (?, ?, ?, ?)")) {
            connection.setAutoCommit(false); PostgresTenantContext.apply(connection, A);
            statement.setObject(1, UUID.randomUUID()); statement.setObject(2, A); statement.setString(3, name); statement.setString(4, instructions);
            assertThat(assertThrows(SQLException.class, statement::executeUpdate).getSQLState()).isEqualTo(state);
            connection.rollback();
        }
    }
    private void denied(String sql, String state) throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false); PostgresTenantContext.apply(connection, A);
            assertThat(assertThrows(SQLException.class, () -> statement.execute(sql)).getSQLState()).isEqualTo(state);
            connection.rollback();
        }
    }
    private static void membership(Connection connection, UUID user, UUID tenant, String role) throws Exception {
        try (var statement = connection.prepareStatement("INSERT INTO platform.tenant_memberships(user_id,tenant_id,role) VALUES(?,?,?)")) {
            statement.setObject(1, user); statement.setObject(2, tenant); statement.setString(3, role); statement.executeUpdate();
        }
    }
    private static void fixture(Connection connection, UUID id, UUID tenant, String name, String instructions, String status) throws Exception {
        try (var statement = connection.prepareStatement("INSERT INTO platform.agent_configurations(id,tenant_id,name,system_instructions,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?)")) {
            statement.setObject(1, id); statement.setObject(2, tenant); statement.setString(3, name); statement.setString(4, instructions); statement.setString(5, status);
            statement.setTimestamp(6, Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"))); statement.setTimestamp(7, Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"))); statement.executeUpdate();
        }
    }
    private static String scalar(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) { assertThat(result.next()).isTrue(); return result.getString(1); }
    }
    private String signedMemberRole() throws Exception {
        Instant now = Instant.now().minusSeconds(1);
        var claims = new JWTClaimsSet.Builder().subject(MEMBER.toString()).issuer(ISSUER).audience(AUDIENCE).issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(900))).jwtID(UUID.randomUUID().toString()).claim("role", "OWNER").claim("tenantId", B.toString()).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-agent-key").build(), claims);
        jwt.sign(new RSASSASigner(KEY.getPrivate())); return jwt.serialize();
    }
    private String payload(String name, String instructions) throws Exception { return mapper.writeValueAsString(Map.of("name", name, "systemInstructions", instructions)); }
    private static String path(UUID tenant) { return "/api/v1/tenants/" + tenant + "/agents"; }
    private ResultActions create(UUID tenant, UUID user, String body) throws Exception { return mvc.perform(post(path(tenant)).header("Authorization", "Bearer " + tokens.issue(user)).contentType("application/json").content(body)); }
    private ResultActions update(UUID tenant, UUID id, UUID user, String body) throws Exception { return mvc.perform(put(path(tenant) + "/" + id).header("Authorization", "Bearer " + tokens.issue(user)).contentType("application/json").content(body)); }
    private ResultActions archive(UUID tenant, UUID id, UUID user) throws Exception { return mvc.perform(post(path(tenant) + "/" + id + "/archive").header("Authorization", "Bearer " + tokens.issue(user))); }
    private ResultActions getAgent(UUID tenant, UUID id, UUID user) throws Exception { return mvc.perform(get(path(tenant) + "/" + id).header("Authorization", "Bearer " + tokens.issue(user))); }
    private ResultActions list(UUID tenant, UUID user, String query) throws Exception { return mvc.perform(get(path(tenant) + query).header("Authorization", "Bearer " + tokens.issue(user))); }
    private JsonNode json(ResultActions result) throws Exception { return mapper.readTree(result.andReturn().getResponse().getContentAsString()); }
}
