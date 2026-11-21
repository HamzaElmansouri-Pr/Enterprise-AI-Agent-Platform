package com.elmacore.agentplatform;

import com.elmacore.agentplatform.identity.api.LoginProtection;
import com.elmacore.agentplatform.identity.domain.Email;
import com.elmacore.agentplatform.identity.infrastructure.*;
import com.elmacore.agentplatform.identity.application.MembershipAuthorization;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jwt.*;
import com.zaxxer.hikari.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.interfaces.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthenticationIT.LimiterConfiguration.class)
class AuthenticationIT {
    private static final UUID A = UUID.fromString("10000000-0000-0000-0000-000000000011");
    private static final UUID B = UUID.fromString("10000000-0000-0000-0000-000000000012");
    private static final UUID USER = UUID.fromString("20000000-0000-0000-0000-000000000011");
    private static final UUID DISABLED = UUID.fromString("20000000-0000-0000-0000-000000000012");
    private static final String PASSWORD = "test-only-strong-password";
    private static final String MIGRATION = UUID.randomUUID().toString();
    private static final String RUNTIME = UUID.randomUUID().toString();
    private static final String OPERATOR = UUID.randomUUID().toString();
    private static final KeyPair KEY = rsa();
    private static final Path PRIVATE = pem("PRIVATE", KEY.getPrivate().getEncoded());
    private static final Path PUBLIC = pem("PUBLIC", KEY.getPublic().getEncoded());
    private static final String ISSUER = "https://test.example/identity";
    private static final String AUDIENCE = "test-control-plane";
    private static final MutableClock CLOCK = new MutableClock();
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg17-trixie").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("eaap_auth_test").withUsername("bootstrap_test").withPassword(UUID.randomUUID().toString())
            .withEnv("POSTGRES_USER", "bootstrap_test").withEnv("POSTGRES_DB", "eaap_auth_test")
            .withEnv("MIGRATION_DB_PASSWORD", MIGRATION).withEnv("DB_PASSWORD", RUNTIME)
            .withEnv("IDENTITY_OPERATOR_DB_PASSWORD", OPERATOR)
            .withCopyFileToContainer(MountableFile.forHostPath("../../infrastructure/docker/postgres/bootstrap.sh"),
                    "/docker-entrypoint-initdb.d/10-platform-roles.sh")
            .withCopyFileToContainer(MountableFile.forHostPath("../../infrastructure/docker/postgres/bootstrap.sql"), "/opt/eaap/bootstrap.sql");

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        var previous = Flyway.configure().dataSource(DB.getJdbcUrl(), "eaap_migration", MIGRATION)
                .schemas("platform").defaultSchema("platform").createSchemas(false).target("1").load();
        assertThat(previous.migrate().migrationsExecuted).isEqualTo(1);
        // Explicit upgrade from the accepted V1 state, preserving its checksum/history.
        var latest = Flyway.configure().dataSource(DB.getJdbcUrl(), "eaap_migration", MIGRATION)
                .schemas("platform").defaultSchema("platform").createSchemas(false).load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(2);
        latest.validate();
        assertThat(latest.info().applied()).hasSize(3);
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.password", () -> RUNTIME);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
        registry.add("AUTH_PRIVATE_KEY", () -> PRIVATE.toUri().toString());
        registry.add("AUTH_PUBLIC_KEY", () -> PUBLIC.toUri().toString());
        registry.add("AUTH_ISSUER", () -> ISSUER);
        registry.add("AUTH_AUDIENCE", () -> AUDIENCE);
        registry.add("AUTH_KEY_ID", () -> "test-only");
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired HikariDataSource source;
    @Autowired JwtTokens tokens;
    private static final ArgonPasswords PASSWORDS = new ArgonPasswords();
    private static final String HASH = PASSWORDS.encode(PASSWORD);
    @BeforeEach void seed() throws Exception {
        CLOCK.advance();
        try (var admin = admin(); var statement = admin.createStatement()) {
            statement.execute("DELETE FROM platform.tenant_memberships; DELETE FROM platform.users; DELETE FROM platform.tenants");
            try (var tenants = admin.prepareStatement("INSERT INTO platform.tenants(id, name) VALUES (?, 'A'), (?, 'B')")) {
                tenants.setObject(1, A); tenants.setObject(2, B); tenants.executeUpdate();
            }
            try (var users = admin.prepareStatement("INSERT INTO platform.users(id, email, password_hash, enabled) VALUES (?, 'user@example.com', ?, true), (?, 'disabled@example.com', ?, false)")) {
                users.setObject(1, USER); users.setString(2, HASH); users.setObject(3, DISABLED); users.setString(4, HASH); users.executeUpdate();
            }
            try (var memberships = admin.prepareStatement("INSERT INTO platform.tenant_memberships(user_id, tenant_id, role) VALUES (?, ?, 'MEMBER'), (?, ?, 'ADMIN'), (?, ?, 'OWNER')")) {
                memberships.setObject(1, USER); memberships.setObject(2, A); memberships.setObject(3, USER);
                memberships.setObject(4, B); memberships.setObject(5, DISABLED); memberships.setObject(6, A); memberships.executeUpdate();
            }
        }
    }
    @AfterAll static void cleanupKeys() throws Exception { Files.deleteIfExists(PRIVATE); Files.deleteIfExists(PUBLIC); }

    @Test void correctCredentialsIssueMinimalSignedFifteenMinuteToken() throws Exception {
        var result = login(" USER@EXAMPLE.COM ", PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer")).andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(HASH, PASSWORD, "password_hash");
        var tree = mapper.readTree(body);
        assertThat(tree.size()).isEqualTo(3);
        var jwt = tokens.decode(tree.get("accessToken").asText());
        assertThat(jwt.getHeaders().get("alg")).isEqualTo("RS256");
        assertThat(jwt.getSubject()).isEqualTo(USER.toString());
        assertThat(jwt.getClaims().keySet()).containsExactlyInAnyOrder("iss", "sub", "aud", "iat", "exp", "jti");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).toSeconds()).isEqualTo(900);
        assertThat(HASH).startsWith("$argon2id$v=19$m=19456,t=2,p=1$").doesNotContain(PASSWORD);
        assertThat(PASSWORDS.encode(PASSWORD)).isNotEqualTo(HASH);
    }
    @Test void failuresAreGenericForWrongUnknownAndDisabledAccounts() throws Exception {
        String wrong = login("user@example.com", "wrong").andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String unknown = login("unknown@example.com", PASSWORD).andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String disabled = login("disabled@example.com", PASSWORD).andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertThat(wrong).isEqualTo(unknown).isEqualTo(disabled).isEqualTo("{\"code\":\"invalid_credentials\"}");
    }
    @Test void invalidAndOversizedInputsRejectedBeforeAuthentication() throws Exception {
        login("invalid", PASSWORD).andExpect(status().isBadRequest());
        login("user@example.com", "x".repeat(129)).andExpect(status().isBadRequest());
        login("x".repeat(255), PASSWORD).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("x".repeat(4097))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/login").contentType("text/plain").content("credentials")).andExpect(status().isUnsupportedMediaType());
    }
    @Test void strictJsonAndQueryCredentialsRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/login").queryParam("password", "forbidden").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        for (String body : List.of("{}", "null", "{\"email\":\"a@b.com\",\"password\":\"x\",\"role\":\"OWNER\"}",
                "{\"email\":\"a@b.com\",\"email\":\"c@d.com\",\"password\":\"x\"}", "{} {}", "{\"email\":\"user@example.com\",\"password\":1234567890123456}")) {
            CLOCK.advance();
            mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
    }
    @Test void limiterBoundsAttemptsAndExpiresItsWindow() throws Exception {
        for (int i = 0; i < 5; i++) login("unknown@example.com", PASSWORD).andExpect(status().isUnauthorized());
        login("user@example.com", PASSWORD).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"));
        CLOCK.advance(); login("user@example.com", PASSWORD).andExpect(status().isOk());
    }
    @Test void authenticMemberAccessesBothTenantsAndRolesComeFromDatabase() throws Exception {
        String token = tokens.issue(USER);
        context(A, alteredToken("role")).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("MEMBER"));
        context(A, token).andExpect(status().isOk()).andExpect(jsonPath("$.tenantId").value(A.toString()))
                .andExpect(jsonPath("$.userId").value(USER.toString())).andExpect(jsonPath("$.role").value("MEMBER"));
        context(B, token).andExpect(status().isOk()).andExpect(jsonPath("$.tenantId").value(B.toString())).andExpect(jsonPath("$.role").value("ADMIN"));
        mvc.perform(get(path(A)).header("Authorization", "Bearer " + token).header("X-Tenant-ID", B).header("X-Role", "OWNER")
                .queryParam("role", "OWNER")).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("MEMBER"));
        // Force the production SELECT policy to hide the row while membership remains valid.
        try (var admin = admin(); var statement = admin.createStatement()) {
            statement.execute("CREATE POLICY test_hide_registry ON platform.tenants AS RESTRICTIVE FOR SELECT TO eaap_runtime USING(false)");
            try { context(A, token).andExpect(status().isForbidden()); }
            finally { statement.execute("DROP POLICY test_hide_registry ON platform.tenants"); }
        }
    }
    @Test void memberRemovalAndDisabledUsersRevokeExistingTokens() throws Exception {
        String token = tokens.issue(USER);
        context(A, token).andExpect(status().isOk());
        try (var admin = admin(); var statement = admin.prepareStatement("DELETE FROM platform.tenant_memberships WHERE user_id = ? AND tenant_id = ?")) {
            statement.setObject(1, USER); statement.setObject(2, A); statement.executeUpdate();
        }
        context(A, token).andExpect(status().isForbidden());
        context(UUID.randomUUID(), token).andExpect(status().isForbidden());
        context(B, token).andExpect(status().isOk());
        try (var admin = admin(); var statement = admin.prepareStatement("UPDATE platform.users SET enabled = false WHERE id = ?")) {
            statement.setObject(1, USER); statement.executeUpdate();
        }
        context(B, token).andExpect(status().isUnauthorized());
        context(A, tokens.issue(DISABLED)).andExpect(status().isUnauthorized());
        context(A, tokens.issue(UUID.randomUUID())).andExpect(status().isUnauthorized());
    }
    @ParameterizedTest @ValueSource(strings = {"expired", "signature", "issuer", "audience", "algorithm", "rsa-algorithm", "unsigned", "malformed", "kid", "subject", "iat", "exp", "jti", "future", "lifetime"})
    void invalidTokensAreRejected(String variant) throws Exception {
        context(A, alteredToken(variant)).andExpect(status().isUnauthorized()).andExpect(content().json("{\"code\":\"unauthorized\"}"));
    }
    @Test void missingAuthenticationInvalidUuidAndUnspecifiedRoutesFailSafely() throws Exception {
        mvc.perform(get(path(A))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/tenants/invalid/context").header("Authorization", "Bearer " + tokens.issue(USER))).andExpect(status().isBadRequest());
        for (String route : List.of("/api/v1/auth/bootstrap", "/api/v1/users", "/api/v1/tenants", "/actuator/env", "/unrelated")) {
            mvc.perform(get(route).header("Authorization", "Bearer " + tokens.issue(USER))).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/v1/auth/bootstrap")).andExpect(status().isForbidden());
    }
    @Test void tenantConnectionIsTransactionBoundAndNoContextLeaksBetweenHttpRequests() throws Exception {
        String pid = scalar(source.getConnection(), "SELECT pg_backend_pid()");
        context(A, tokens.issue(USER)).andExpect(status().isOk());
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT pg_backend_pid(), platform.current_tenant_id(), (SELECT count(*) FROM platform.tenants)")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo(pid);
                assertThat(rows.getObject(2)).isNull(); assertThat(rows.getInt(3)).isZero();
            }
        }
        context(B, tokens.issue(USER)).andExpect(status().isOk());
        try (var connection = source.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT platform.current_tenant_id()")) {
            assertThat(rows.next()).isTrue(); assertThat(rows.getObject(1)).isNull();
        }
    }
    @Test void contextAndRealReadUseTheSameSpringTransactionConnectionAndRollbackCleansIt() throws Exception {
        var events = new ArrayList<String>();
        var wrapped = new org.springframework.jdbc.datasource.DelegatingDataSource(source) {
            @Override public Connection getConnection() throws SQLException {
                Connection actual = super.getConnection();
                return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
                        new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                            if (method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
                                if (sql.startsWith("SELECT set_config")) {
                                    assertThat(actual.getAutoCommit()).isFalse();
                                    events.add("context:" + scalar(actual, "SELECT pg_backend_pid()", false));
                                }
                                if (sql.startsWith("SELECT id FROM platform.tenants")) {
                                    events.add("read:" + scalar(actual, "SELECT pg_backend_pid()", false));
                                    assertThat(scalar(actual, "SELECT platform.current_tenant_id()", false)).isEqualTo(A.toString());
                                }
                            }
                            try { return method.invoke(actual, args); }
                            catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
                        });
            }
        };
        var authorized = new MembershipAuthorization(new JdbcIdentityStore(new JdbcTemplate(source))).authorize(USER, A);
        assertThat(new JdbcTenantRegistry(wrapped).readAuthorized(authorized)).isTrue();
        assertThat(events).hasSize(2);
        assertThat(events.get(0).substring(8)).isEqualTo(events.get(1).substring(5));
        // Raise a real PostgreSQL error after applying context; TransactionTemplate must roll back.
        try (var admin = admin(); var statement = admin.createStatement()) {
            statement.execute("CREATE FUNCTION platform.test_rls_error() RETURNS boolean LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test-only failure'; END $$");
            statement.execute("GRANT EXECUTE ON FUNCTION platform.test_rls_error() TO eaap_runtime");
            statement.execute("CREATE POLICY test_registry_error ON platform.tenants AS RESTRICTIVE FOR SELECT TO eaap_runtime USING(platform.test_rls_error())");
            try {
                assertThrows(org.springframework.dao.DataAccessException.class, () -> new JdbcTenantRegistry(source).readAuthorized(authorized));
                context(A, tokens.issue(USER)).andExpect(status().isInternalServerError())
                        .andExpect(content().json("{\"code\":\"internal_error\"}"));
            } finally {
                statement.execute("DROP POLICY test_registry_error ON platform.tenants; DROP FUNCTION platform.test_rls_error()");
            }
        }
        try (var connection = source.getConnection()) {
            assertThat(scalar(connection, "SELECT platform.current_tenant_id()", false)).isNull();
            assertThat(scalar(connection, "SELECT count(*) FROM platform.tenants", false)).isEqualTo("0");
        }
        context(B, tokens.issue(USER)).andExpect(status().isOk());
    }

    @Test void databaseFailureDuringBearerAuthenticationIsSanitized() throws Exception {
        try (var admin = admin(); var statement = admin.createStatement()) {
            statement.execute("REVOKE EXECUTE ON FUNCTION platform.enabled_identity(uuid) FROM eaap_runtime");
            try {
                context(A, tokens.issue(USER)).andExpect(status().isInternalServerError())
                        .andExpect(content().json("{\"code\":\"internal_error\"}"))
                        .andExpect(header().exists("X-Request-ID"));
            } finally {
                statement.execute("GRANT EXECUTE ON FUNCTION platform.enabled_identity(uuid) TO eaap_runtime");
            }
        }
        context(A, tokens.issue(USER)).andExpect(status().isOk());
    }

    @Test void runtimeIdentityAccessIsNarrowAndConstraintsAreEnforced() throws Exception {
        try (var connection = source.getConnection()) {
            for (String sql : List.of("SELECT * FROM platform.users", "SELECT * FROM platform.tenant_memberships",
                    "INSERT INTO platform.tenants(id,name) VALUES (gen_random_uuid(),'forbidden')", "SET ROLE eaap_identity_operator")) {
                var error = assertThrows(SQLException.class, () -> { try (var statement = connection.createStatement()) { statement.execute(sql); } });
                assertThat(error.getSQLState()).isEqualTo("42501");
            }
        }
        try (var admin = admin(); var statement = admin.prepareStatement("INSERT INTO platform.users(id,email,password_hash) VALUES (?, ?, ?)")) {
            statement.setObject(1, UUID.randomUUID()); statement.setString(2, "USER@example.com"); statement.setString(3, HASH);
            assertThat(assertThrows(SQLException.class, statement::executeUpdate).getSQLState()).isEqualTo("23514");
            statement.setString(2, "user@example.com");
            assertThat(assertThrows(SQLException.class, statement::executeUpdate).getSQLState()).isEqualTo("23505");
            statement.setString(2, "another@example.com"); statement.setString(3, "plaintext");
            assertThat(assertThrows(SQLException.class, statement::executeUpdate).getSQLState()).isEqualTo("23514");
        }
        assertThat(DB.getLogs()).doesNotContain(PASSWORD, HASH, MIGRATION, RUNTIME, OPERATOR);
    }
    @Test void offlineBootstrapIsAtomicIdempotentInsertOnlyAndRejectsMissingSecrets() throws Exception {
        UUID tenant = UUID.fromString("10000000-0000-0000-0000-000000000099");
        UUID user = UUID.fromString("20000000-0000-0000-0000-000000000099");
        var config = new HikariConfig(); config.setJdbcUrl(DB.getJdbcUrl()); config.setUsername("eaap_identity_operator");
        config.setPassword(OPERATOR); config.setMaximumPoolSize(1);
        try (var operator = new HikariDataSource(config)) {
            var bootstrap = new IdentityBootstrap(operator);
            assertThrows(IllegalArgumentException.class, () -> bootstrap.execute(tenant, "Bootstrap", user, new Email("owner@example.com"), null));
            bootstrap.execute(tenant, "Bootstrap", user, new Email("OWNER@example.com"), PASSWORD);
            bootstrap.execute(tenant, "Bootstrap", user, new Email("owner@example.com"), "different-test-only-password");
            assertThrows(IllegalStateException.class, () -> bootstrap.execute(tenant, "Bootstrap", UUID.randomUUID(), new Email("other@example.com"), PASSWORD));
            try (var connection = operator.getConnection(); var statement = connection.createStatement()) {
                assertThat(scalar(connection, "SELECT rolsuper OR rolbypassrls OR rolcreaterole FROM pg_roles WHERE rolname = current_user", false)).isEqualTo("f");
                assertThat(assertThrows(SQLException.class, () -> statement.execute("UPDATE platform.users SET enabled = true")).getSQLState()).isEqualTo("42501");
                assertThat(assertThrows(SQLException.class, () -> statement.execute("SELECT password_hash FROM platform.users")).getSQLState()).isEqualTo("42501");
            }
        }
        context(tenant, tokens.issue(user)).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("OWNER"));
        login("owner@example.com", PASSWORD).andExpect(status().isOk());
        try (var admin = admin(); var statement = admin.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM platform.users WHERE email = 'owner@example.com'")) {
            assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(1);
        }
        // The real one-shot profile starts no HTTP server and receives only operator credentials.
        var inputs = new HashMap<String, Object>();
        inputs.put("DB_URL", DB.getJdbcUrl()); inputs.put("IDENTITY_OPERATOR_DB_PASSWORD", OPERATOR);
        inputs.put("BOOTSTRAP_TENANT_ID", tenant.toString()); inputs.put("BOOTSTRAP_TENANT_NAME", "Bootstrap");
        inputs.put("BOOTSTRAP_USER_ID", user.toString()); inputs.put("BOOTSTRAP_EMAIL", "owner@example.com"); inputs.put("BOOTSTRAP_PASSWORD", PASSWORD);
        try (var application = new SpringApplicationBuilder(ControlPlaneApplication.class).profiles("identity-bootstrap")
                .initializers(app -> app.getEnvironment().getPropertySources().addFirst(new MapPropertySource("testOperator", inputs))).run()) {
            assertThat(application).isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(application.containsBean("entityManagerFactory")).isFalse();
            assertThat(application.getBean(HikariDataSource.class).getUsername()).isEqualTo("eaap_identity_operator");
        }
    }
    @Test void bootstrapProfileRejectsMissingPasswordWithoutStartingHttp() {
        var inputs = new HashMap<String, Object>();
        inputs.put("DB_URL", DB.getJdbcUrl()); inputs.put("IDENTITY_OPERATOR_DB_PASSWORD", OPERATOR);
        inputs.put("BOOTSTRAP_TENANT_ID", A.toString()); inputs.put("BOOTSTRAP_TENANT_NAME", "A");
        inputs.put("BOOTSTRAP_USER_ID", USER.toString()); inputs.put("BOOTSTRAP_EMAIL", "user@example.com");
        inputs.put("BOOTSTRAP_PASSWORD", "");
        var failure = assertThrows(IllegalStateException.class, () ->
                new SpringApplicationBuilder(ControlPlaneApplication.class).profiles("identity-bootstrap")
                        .initializers(app -> app.getEnvironment().getPropertySources().addFirst(new MapPropertySource("missingOperatorInput", inputs))).run());
        assertThat(failure).hasMessageContaining("Identity bootstrap failed");
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(mapper.writeValueAsBytes(Map.of("email", email, "password", password))));
    }
    private ResultActions context(UUID tenant, String token) throws Exception { return mvc.perform(get(path(tenant)).header("Authorization", "Bearer " + token)); }
    private static String path(UUID tenant) { return "/api/v1/tenants/" + tenant + "/context"; }
    private static Connection admin() throws SQLException { var properties = new java.util.Properties(); properties.setProperty("user", DB.getUsername());
        properties.setProperty("password", DB.getPassword());
        // Negative constraint fixtures intentionally include hashes; suppress administrator error row dumps.
        properties.setProperty("options", "-c log_error_verbosity=terse -c log_min_error_statement=panic");
        return DriverManager.getConnection(DB.getJdbcUrl(), properties); }
    private static String scalar(Connection c, String sql) throws Exception { return scalar(c, sql, true); }
    private static String scalar(Connection c, String sql, boolean close) throws Exception {
        try (var statement = c.createStatement(); var rows = statement.executeQuery(sql)) { rows.next(); return rows.getString(1); }
        finally { if (close) c.close(); }
    }
    private String alteredToken(String variant) throws Exception {
        if (variant.equals("malformed")) return "invalid.token";
        Instant now = Instant.now().minusSeconds(1);
        var claims = new JWTClaimsSet.Builder().subject(USER.toString()).issuer(ISSUER).audience(AUDIENCE)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(900))).jwtID(UUID.randomUUID().toString());
        switch (variant) {
            case "expired" -> claims.issueTime(Date.from(now.minusSeconds(1800))).expirationTime(Date.from(now.minusSeconds(900)));
            case "issuer" -> claims.issuer("https://wrong.example");
            case "audience" -> claims.audience("wrong-api");
            case "subject" -> claims.subject("not-a-uuid");
            case "iat" -> claims.issueTime(null);
            case "exp" -> claims.expirationTime(null);
            case "jti" -> claims.jwtID(null);
            case "future" -> claims.issueTime(Date.from(now.plusSeconds(100)));
            case "lifetime" -> claims.expirationTime(Date.from(now.plusSeconds(3600)));
            case "role" -> claims.claim("role", "OWNER").claim("tenantId", B.toString());
        }
        if (variant.equals("unsigned")) return new PlainJWT(claims.build()).serialize();
        var alg = variant.equals("algorithm") ? JWSAlgorithm.HS256 : variant.equals("rsa-algorithm") ? JWSAlgorithm.RS512 : JWSAlgorithm.RS256;
        var jwt = new SignedJWT(new JWSHeader.Builder(alg).keyID(variant.equals("kid") ? "unknown" : "test-only").build(), claims.build());
        if (variant.equals("algorithm")) jwt.sign(new MACSigner(new byte[32]));
        else jwt.sign(new RSASSASigner(variant.equals("signature") ? rsa().getPrivate() : KEY.getPrivate()));
        return jwt.serialize();
    }
    private static KeyPair rsa() {
        try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch (GeneralSecurityException error) { throw new IllegalStateException(error); }
    }
    private static Path pem(String type, byte[] bytes) {
        try {
            Path file = Files.createTempFile("eaap-test-only-", ".pem", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(file, "-----BEGIN " + type + " KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{10}).encodeToString(bytes) + "\n-----END " + type + " KEY-----\n");
            return file;
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
    static class MutableClock extends Clock {
        private final AtomicLong millis = new AtomicLong(System.currentTimeMillis());
        void advance() { millis.addAndGet(60000); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
    }
    @TestConfiguration(proxyBeanMethods = false) static class LimiterConfiguration {
        @Bean @Primary LoginProtection testLoginProtection() { return new LoginProtection(CLOCK); }
    }
}
