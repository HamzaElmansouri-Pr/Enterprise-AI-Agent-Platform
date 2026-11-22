package com.elmacore.agentplatform;

import com.elmacore.agentplatform.infrastructure.persistence.PostgresTenantContext;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
class PersistenceIsolationIT {
    private static final UUID TENANT_A = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("10000000-0000-0000-0000-000000000002");
    // Ephemeral test-only values, never application/development credentials.
    private static final String MIGRATION_PASSWORD = UUID.randomUUID().toString();
    private static final String RUNTIME_PASSWORD = UUID.randomUUID().toString();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg17-trixie").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("eaap_test")
            .withUsername("bootstrap_test")
            .withPassword(UUID.randomUUID().toString())
            .withEnv("POSTGRES_USER", "bootstrap_test")
            .withEnv("POSTGRES_DB", "eaap_test")
            .withEnv("MIGRATION_DB_PASSWORD", MIGRATION_PASSWORD)
            .withEnv("DB_PASSWORD", RUNTIME_PASSWORD)
            .withEnv("IDENTITY_OPERATOR_DB_PASSWORD", UUID.randomUUID().toString())
            .withCopyFileToContainer(MountableFile.forHostPath("../../infrastructure/docker/postgres/knowledge-extension.sql"), "/docker-entrypoint-initdb.d/20-knowledge-extension.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("../../infrastructure/docker/postgres/bootstrap.sh"),
                    "/docker-entrypoint-initdb.d/10-platform-roles.sh")
            .withCopyFileToContainer(MountableFile.forHostPath("../../infrastructure/docker/postgres/bootstrap.sql"),
                    "/opt/eaap/bootstrap.sql");

    private static HikariDataSource runtime;
    private static Flyway flyway;

    @BeforeAll
    static void migrateAndSeedDisposableDatabase() throws Exception {
        flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), "eaap_migration", MIGRATION_PASSWORD)
                .schemas("platform").defaultSchema("platform").createSchemas(false)
                .cleanDisabled(true).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(6);

        // Administrator only seeds deterministic fixtures; tenant access tests always use runtime.
        try (var admin = admin(); var statement = admin.createStatement()) {
            statement.execute("""
                    INSERT INTO platform.tenants(id, name) VALUES
                    ('10000000-0000-0000-0000-000000000001', 'Tenant A'),
                    ('10000000-0000-0000-0000-000000000002', 'Tenant B');
                    CREATE TABLE platform.isolation_fixture (
                        id integer PRIMARY KEY,
                        tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
                        content text NOT NULL
                    );
                    ALTER TABLE platform.isolation_fixture OWNER TO eaap_migration;
                    INSERT INTO platform.isolation_fixture VALUES
                    (1, '10000000-0000-0000-0000-000000000001', 'A'),
                    (2, '10000000-0000-0000-0000-000000000002', 'B');
                    ALTER TABLE platform.isolation_fixture ENABLE ROW LEVEL SECURITY;
                    ALTER TABLE platform.isolation_fixture FORCE ROW LEVEL SECURITY;
                    CREATE POLICY fixture_tenant_isolation ON platform.isolation_fixture TO eaap_runtime
                      USING (tenant_id = platform.current_tenant_id())
                      WITH CHECK (tenant_id = platform.current_tenant_id());
                    GRANT SELECT, INSERT, UPDATE, DELETE ON platform.isolation_fixture TO eaap_runtime;
                    """);
        }
        var pool = new HikariConfig();
        pool.setJdbcUrl(POSTGRES.getJdbcUrl());
        pool.setUsername("eaap_runtime");
        pool.setPassword(RUNTIME_PASSWORD);
        pool.setMaximumPoolSize(1);
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(2000);
        pool.addDataSourceProperty("options", "-c statement_timeout=3000 -c lock_timeout=1000");
        runtime = new HikariDataSource(pool);
    }

    @AfterAll
    static void closePool() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void migrationHistoryRemainsValidAndRepeatedMigrationIsANoOp() {
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.info().applied()).hasSize(6);
        try (var context = new SpringApplicationBuilder(ControlPlaneApplication.class)
                .profiles("migration")
                .initializers(application -> application.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("disposableMigrationDatabase", Map.of(
                                "DB_URL", POSTGRES.getJdbcUrl(),
                                "MIGRATION_DB_PASSWORD", MIGRATION_PASSWORD))))
                .run()) {
            assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(context.containsBean("entityManagerFactory")).isFalse();
            assertThat(context.getBean(Flyway.class).info().applied()).hasSize(6);
            assertThat(context.getBean(HikariDataSource.class).getUsername()).isEqualTo("eaap_migration");
        }
    }

    @Test
    void bootstrapRejectsMissingOrSharedSecretsWithoutLoggingCredentials() throws Exception {
        var shared = POSTGRES.execInContainer("sh", "-c",
                "DB_PASSWORD=\"$MIGRATION_DB_PASSWORD\" sh /docker-entrypoint-initdb.d/10-platform-roles.sh");
        assertThat(shared.getExitCode()).isNotZero();
        assertThat(shared.getStderr()).contains("passwords must differ");
        var missing = POSTGRES.execInContainer("sh", "-c",
                "DB_PASSWORD='' sh /docker-entrypoint-initdb.d/10-platform-roles.sh");
        assertThat(missing.getExitCode()).isNotZero();
        assertThat(POSTGRES.getLogs()).doesNotContain(MIGRATION_PASSWORD, RUNTIME_PASSWORD, POSTGRES.getPassword());
        assertThat(shared.getStderr() + missing.getStderr()).doesNotContain(MIGRATION_PASSWORD, RUNTIME_PASSWORD);
    }

    @Test
    void tenantIdentityConstraintsAndTimestampTypesAreEnforced() throws Exception {
        try (var connection = admin()) {
            assertSqlState(connection, "INSERT INTO platform.tenants(id, name) VALUES (NULL, 'missing id')", "23502");
            assertSqlState(connection, """
                    INSERT INTO platform.tenants(id, name)
                    VALUES ('10000000-0000-0000-0000-000000000003', NULL)
                    """, "23502");
            assertSqlState(connection, """
                    INSERT INTO platform.tenants(id, name)
                    VALUES ('10000000-0000-0000-0000-000000000003', '   ')
                    """, "23514");
            assertSqlState(connection, """
                    INSERT INTO platform.tenants(id, name)
                    VALUES ('10000000-0000-0000-0000-000000000003', E'\\t\\n')
                    """, "23514");
            assertSqlState(connection, """
                    INSERT INTO platform.tenants(id, name)
                    VALUES ('10000000-0000-0000-0000-000000000001', 'duplicate')
                    """, "23505");
            assertSqlState(connection, """
                    INSERT INTO platform.tenants(id, name, created_at, updated_at)
                    VALUES ('10000000-0000-0000-0000-000000000003', 'invalid dates',
                            '2026-01-02T00:00:00Z', '2026-01-01T00:00:00Z')
                    """, "23514");
            assertSqlState(connection, """
                    INSERT INTO platform.tenants(id, name)
                    VALUES ('10000000-0000-0000-0000-000000000003', repeat('x', 201))
                    """, "22001");
            assertThat(scalar(connection, """
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = 'platform' AND table_name = 'tenants'
                      AND column_name IN ('created_at', 'updated_at')
                      AND data_type = 'timestamp with time zone' AND is_nullable = 'NO'
                    """)).isEqualTo("2");
        }
    }

    @Test
    void runtimeRoleHasNoAdministrativeOwnershipOrMigrationPrivileges() throws Exception {
        try (var connection = runtime.getConnection()) {
            assertThat(scalar(connection, "SELECT current_user")).isEqualTo("eaap_runtime");
            assertThat(scalar(connection, """
                    SELECT rolsuper OR rolbypassrls OR rolcreatedb OR rolcreaterole OR rolreplication
                    FROM pg_roles WHERE rolname = current_user
                    """)).isEqualTo("f");
            assertThat(scalar(connection, "SELECT count(*) FROM pg_auth_members WHERE member = current_user::regrole"))
                    .isEqualTo("0");
            assertThat(scalar(connection, "SELECT has_database_privilege(current_database(), 'CREATE')"))
                    .isEqualTo("f");
            assertThat(scalar(connection, "SELECT has_database_privilege(current_database(), 'TEMP')"))
                    .isEqualTo("f");
            assertSqlState(connection, "CREATE TABLE platform.forbidden(id integer)", "42501");
            assertSqlState(connection, "CREATE TABLE public.forbidden(id integer)", "42501");
            assertSqlState(connection, "ALTER TABLE platform.isolation_fixture DISABLE ROW LEVEL SECURITY", "42501");
            assertSqlState(connection, "TRUNCATE platform.isolation_fixture", "42501");
            assertSqlState(connection, "SET ROLE eaap_migration", "42501");
            assertSqlState(connection, "SELECT * FROM platform.flyway_schema_history", "42501");
            assertSqlState(connection, "DELETE FROM platform.flyway_schema_history", "42501");
            assertSqlState(connection, "UPDATE platform.tenants SET name='forbidden'", "42501");
        }
        var runtimeFlyway = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "eaap_runtime", RUNTIME_PASSWORD)
                .schemas("platform").defaultSchema("platform").createSchemas(false).load();
        assertThrows(FlywayException.class, runtimeFlyway::migrate);
    }

    @Test
    void migrationRoleIsRestrictedAndBothProductionAndFixtureTablesForceRls() throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "eaap_migration", MIGRATION_PASSWORD)) {
            assertThat(scalar(connection, """
                    SELECT rolsuper OR rolbypassrls OR rolcreatedb OR rolcreaterole OR rolreplication
                    FROM pg_roles WHERE rolname = current_user
                    """)).isEqualTo("f");
            assertThat(scalar(connection, "SELECT count(*) FROM platform.isolation_fixture")).isEqualTo("0");
            assertThat(scalar(connection, """
                    SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'platform' AND c.relname IN ('tenants', 'isolation_fixture')
                      AND c.relrowsecurity AND c.relforcerowsecurity
                      AND c.relowner = 'eaap_migration'::regrole
                    """)).isEqualTo("2");
        }
    }

    @Test
    void missingContextFailsClosedForReadsAndWrites() throws Exception {
        try (var connection = runtime.getConnection()) {
            connection.setAutoCommit(false);
            assertThat(scalar(connection, "SELECT count(*) FROM platform.isolation_fixture")).isEqualTo("0");
            assertThat(scalar(connection, "SELECT count(*) FROM platform.tenants")).isEqualTo("0");
            assertSqlState(connection, """
                    INSERT INTO platform.isolation_fixture VALUES
                    (3, '10000000-0000-0000-0000-000000000001', 'denied')
                    """, "42501");
            connection.rollback();
        }
    }

    @Test
    void eachTenantReadsOnlyItsOwnRecordsAndTenantRegistryEntry() throws Exception {
        for (var tenant : new UUID[]{TENANT_A, TENANT_B}) {
            try (var connection = runtime.getConnection()) {
                connection.setAutoCommit(false);
                PostgresTenantContext.apply(connection, tenant);
                assertThat(scalar(connection, "SELECT tenant_id FROM platform.isolation_fixture"))
                        .isEqualTo(tenant.toString());
                assertThat(scalar(connection, "SELECT id FROM platform.tenants")).isEqualTo(tenant.toString());
                connection.commit();
            }
        }
    }

    @Test
    void tenantACannotModifyDeleteInsertOrReassignTenantBData() throws Exception {
        try (var connection = runtime.getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            PostgresTenantContext.apply(connection, TENANT_A);
            assertThat(statement.executeUpdate("UPDATE platform.isolation_fixture SET content='changed' WHERE id=2"))
                    .isZero();
            assertThat(statement.executeUpdate("DELETE FROM platform.isolation_fixture WHERE id=2")).isZero();
            connection.rollback();
            PostgresTenantContext.apply(connection, TENANT_A);
            assertSqlState(connection, """
                    INSERT INTO platform.isolation_fixture VALUES
                    (3, '10000000-0000-0000-0000-000000000002', 'denied')
                    """, "42501");
            connection.rollback();
            PostgresTenantContext.apply(connection, TENANT_A);
            assertSqlState(connection, """
                    UPDATE platform.isolation_fixture
                    SET tenant_id='10000000-0000-0000-0000-000000000002' WHERE id=1
                    """, "42501");
            connection.rollback();
        }
    }

    @Test
    void tenantCanWriteOwnFixtureRecord() throws Exception {
        try (var connection = runtime.getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            PostgresTenantContext.apply(connection, TENANT_A);
            assertThat(statement.executeUpdate("UPDATE platform.isolation_fixture SET content='own update' WHERE id=1"))
                    .isEqualTo(1);
            assertThat(scalar(connection, "SELECT content FROM platform.isolation_fixture WHERE id=1"))
                    .isEqualTo("own update");
            assertThat(statement.executeUpdate("""
                    INSERT INTO platform.isolation_fixture VALUES
                    (3, '10000000-0000-0000-0000-000000000001', 'own insert')
                    """)).isEqualTo(1);
            assertThat(statement.executeUpdate("DELETE FROM platform.isolation_fixture WHERE id=3")).isEqualTo(1);
            connection.rollback();
        }
    }

    @Test
    void pooledPhysicalConnectionDoesNotRetainContextAfterCommitOrRollback() throws Exception {
        String backendPid = null;
        for (boolean commit : new boolean[]{true, false}) {
            try (var connection = runtime.getConnection()) {
                connection.setAutoCommit(false);
                var pid = scalar(connection, "SELECT pg_backend_pid()");
                if (backendPid != null) {
                    assertThat(pid).isEqualTo(backendPid);
                }
                backendPid = pid;
                assertThat(scalar(connection, "SELECT count(*) FROM platform.isolation_fixture")).isEqualTo("0");
                PostgresTenantContext.apply(connection, TENANT_A);
                assertThat(scalar(connection, "SELECT count(*) FROM platform.isolation_fixture")).isEqualTo("1");
                if (commit) {
                    connection.commit();
                } else {
                    connection.rollback();
                }
                assertThat(scalar(connection, "SELECT count(*) FROM platform.isolation_fixture")).isEqualTo("0");
                connection.rollback();
            }
        }
    }

    @Test
    void invalidContextFailsSafelyAndCannotPoisonNextTransaction() throws Exception {
        try (var connection = runtime.getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SELECT set_config('app.tenant_id', 'invalid-uuid', true)");
            assertSqlState(connection, "SELECT * FROM platform.isolation_fixture", "22P02");
            connection.rollback();
            assertThat(scalar(connection, "SELECT count(*) FROM platform.isolation_fixture")).isEqualTo("0");
            PostgresTenantContext.apply(connection, TENANT_B);
            assertThat(scalar(connection, "SELECT content FROM platform.isolation_fixture")).isEqualTo("B");
            connection.rollback();
        }
    }

    @Test
    void contextCannotBeAppliedWithoutTransactionOrTrustedIdentity() throws Exception {
        try (var connection = runtime.getConnection()) {
            assertThrows(IllegalStateException.class, () -> PostgresTenantContext.apply(connection, TENANT_A));
            connection.setAutoCommit(false);
            assertThrows(NullPointerException.class, () -> PostgresTenantContext.apply(connection, null));
            connection.rollback();
        }
    }

    @Test
    void rowSecurityOffDoesNotLetRuntimeBypassPolicy() throws Exception {
        try (var connection = runtime.getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SET LOCAL row_security = off");
            assertSqlState(connection, "SELECT * FROM platform.isolation_fixture", "42501");
            connection.rollback();
        }
    }

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(3);
            try (var result = statement.executeQuery(sql)) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private static void assertSqlState(Connection connection, String sql, String expectedState) {
        var error = assertThrows(SQLException.class, () -> {
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(3);
                statement.execute(sql);
            }
        });
        assertThat(error.getSQLState()).isEqualTo(expectedState);
    }
}
