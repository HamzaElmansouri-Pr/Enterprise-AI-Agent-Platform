package com.elmacore.agentplatform.agent;

import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.Properties;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

final class RunTestDatabase {
    static final UUID A = UUID.fromString("10000000-0000-0000-0000-000000000001");
    static final UUID B = UUID.fromString("10000000-0000-0000-0000-000000000002");
    static final UUID OWNER = UUID.fromString("20000000-0000-0000-0000-000000000001");
    static final UUID ADMIN = UUID.fromString("20000000-0000-0000-0000-000000000002");
    static final UUID MEMBER = UUID.fromString("20000000-0000-0000-0000-000000000003");
    static final UUID B_OWNER = UUID.fromString("20000000-0000-0000-0000-000000000004");
    static final UUID MULTI = UUID.fromString("20000000-0000-0000-0000-000000000005");
    static final UUID OUTSIDER = UUID.fromString("20000000-0000-0000-0000-000000000006");
    static final UUID AGENT_A = UUID.fromString("30000000-0000-0000-0000-000000000001");
    static final UUID AGENT_B = UUID.fromString("30000000-0000-0000-0000-000000000002");
    static final UUID ARCHIVED_A = UUID.fromString("30000000-0000-0000-0000-000000000003");
    static final String MIGRATION = UUID.randomUUID().toString();
    static final String RUNTIME = UUID.randomUUID().toString();
    static final String OPERATOR = UUID.randomUUID().toString();
    static final String ISSUER = "https://agents.test.example";
    static final String AUDIENCE = "agent-api-test";
    static final KeyPair KEY = rsa();
    static final Path PRIVATE = pem("PRIVATE", KEY.getPrivate().getEncoded());
    static final Path PUBLIC = pem("PUBLIC", KEY.getPublic().getEncoded());
    static PostgreSQLContainer container() {
        return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.7-pg17-trixie").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("agent_test").withUsername("bootstrap_test").withPassword(UUID.randomUUID().toString())
                .withEnv("POSTGRES_USER", "bootstrap_test").withEnv("POSTGRES_DB", "agent_test")
                .withEnv("MIGRATION_DB_PASSWORD", MIGRATION).withEnv("DB_PASSWORD", RUNTIME)
                .withEnv("IDENTITY_OPERATOR_DB_PASSWORD", OPERATOR)
                .withCopyFileToContainer(MountableFile.forHostPath("../../infrastructure/docker/postgres/knowledge-extension.sql"), "/docker-entrypoint-initdb.d/20-knowledge-extension.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("../../infrastructure/docker/postgres/bootstrap.sh"), "/docker-entrypoint-initdb.d/10-platform-roles.sh")
                .withCopyFileToContainer(MountableFile.forHostPath("../../infrastructure/docker/postgres/bootstrap.sql"), "/opt/eaap/bootstrap.sql");
    }
    static void configure(PostgreSQLContainer database, DynamicPropertyRegistry properties) {
        var v2 = Flyway.configure().dataSource(database.getJdbcUrl(), "eaap_migration", MIGRATION)
                .schemas("platform").defaultSchema("platform").createSchemas(false).target("4").load();
        assertThat(v2.migrate().migrationsExecuted).isEqualTo(4);
        var latest = Flyway.configure().dataSource(database.getJdbcUrl(), "eaap_migration", MIGRATION)
                .schemas("platform").defaultSchema("platform").createSchemas(false).load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(5);
        latest.validate();
        assertThat(latest.migrate().migrationsExecuted).isZero();
        assertThat(latest.info().applied()).hasSize(9);
        properties.add("spring.datasource.url", database::getJdbcUrl);
        properties.add("spring.datasource.password", () -> RUNTIME);
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        properties.add("AUTH_PRIVATE_KEY", () -> PRIVATE.toUri().toString());
        properties.add("AUTH_PUBLIC_KEY", () -> PUBLIC.toUri().toString());
        properties.add("AUTH_ISSUER", () -> ISSUER);
        properties.add("AUTH_AUDIENCE", () -> AUDIENCE);
        properties.add("AUTH_KEY_ID", () -> "test-agent-key");
    }
    static Connection admin(PostgreSQLContainer database) throws Exception {
        var settings = new Properties();
        settings.setProperty("user", database.getUsername()); settings.setProperty("password", database.getPassword());
        settings.setProperty("options", "-c log_error_verbosity=terse -c log_min_error_statement=panic");
        return DriverManager.getConnection(database.getJdbcUrl(), settings);
    }
    static void deleteKeys() throws Exception { Files.deleteIfExists(PRIVATE); Files.deleteIfExists(PUBLIC); }
    private static KeyPair rsa() {
        try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch (Exception error) { throw new IllegalStateException("Cannot generate test-only signing key", error); }
    }
    private static Path pem(String type, byte[] encoded) {
        try {
            Path path = Files.createTempFile("execution-test-only-", ".pem", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(path, "-----BEGIN " + type + " KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{10}).encodeToString(encoded) + "\n-----END " + type + " KEY-----\n");
            return path;
        } catch (Exception error) { throw new IllegalStateException("Cannot write test-only signing key", error); }
    }
}
