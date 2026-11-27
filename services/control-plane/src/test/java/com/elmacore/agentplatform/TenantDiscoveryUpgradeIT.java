package com.elmacore.agentplatform;

import com.elmacore.agentplatform.identity.infrastructure.ArgonPasswords;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import java.sql.DriverManager;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class TenantDiscoveryUpgradeIT {
    private static final String MIGRATION = UUID.randomUUID().toString();
    private static final String RUNTIME = UUID.randomUUID().toString();
    @Container static final PostgreSQLContainer DB = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg17-trixie").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("upgrade_test").withUsername("bootstrap_test").withPassword(UUID.randomUUID().toString())
            .withEnv("POSTGRES_USER","bootstrap_test").withEnv("POSTGRES_DB","upgrade_test")
            .withEnv("MIGRATION_DB_PASSWORD",MIGRATION).withEnv("DB_PASSWORD",RUNTIME)
            .withEnv("IDENTITY_OPERATOR_DB_PASSWORD",UUID.randomUUID().toString())
            .withCopyFileToContainer(MountableFile.forHostPath("../../infrastructure/docker/postgres/bootstrap.sh"),"/docker-entrypoint-initdb.d/10-platform-roles.sh")
            .withCopyFileToContainer(MountableFile.forHostPath("../../infrastructure/docker/postgres/bootstrap.sql"),"/opt/eaap/bootstrap.sql")
            .withCopyFileToContainer(MountableFile.forHostPath("../../infrastructure/docker/postgres/knowledge-extension.sql"),"/docker-entrypoint-initdb.d/20-knowledge-extension.sql");

    @Test void v8UpgradePreservesIdentityDataAndInstallsOnlyTheNarrowReadCapability() throws Exception {
        var previous = Flyway.configure().dataSource(DB.getJdbcUrl(),"eaap_migration",MIGRATION)
                .schemas("platform").defaultSchema("platform").createSchemas(false).target("8").load();
        assertThat(previous.migrate().migrationsExecuted).isEqualTo(8);
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
        String hash = new ArgonPasswords().encode("synthetic-upgrade-password");
        try (var connection = DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword())) {
            try (var statement = connection.prepareStatement("INSERT INTO platform.tenants(id,name) VALUES (?,?)")) {
                statement.setObject(1,tenant); statement.setString(2,"Existing Atlas"); statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("INSERT INTO platform.users(id,email,password_hash) VALUES (?, 'upgrade@example.test', ?)")) {
                statement.setObject(1,user); statement.setString(2,hash); statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("INSERT INTO platform.tenant_memberships(user_id,tenant_id,role) VALUES (?,?,'OWNER')")) {
                statement.setObject(1,user); statement.setObject(2,tenant); statement.executeUpdate();
            }
        }
        var latest = Flyway.configure().dataSource(DB.getJdbcUrl(),"eaap_migration",MIGRATION)
                .schemas("platform").defaultSchema("platform").createSchemas(false).load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1); latest.validate();
        assertThat(latest.migrate().migrationsExecuted).isZero();
        try (var connection = DriverManager.getConnection(DB.getJdbcUrl(),"eaap_runtime",RUNTIME);
             var statement = connection.prepareStatement("SELECT * FROM platform.discover_tenants(?,21,0)")) {
            statement.setObject(1,user);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString("name")).isEqualTo("Existing Atlas");
                assertThat(rows.getString("role")).isEqualTo("OWNER"); assertThat(rows.next()).isFalse();
            }
            try (var check = connection.createStatement(); var rows = check.executeQuery("""
                    SELECT p.prosecdef AND p.proowner='eaap_migration'::regrole,
                      NOT EXISTS(SELECT 1 FROM aclexplode(p.proacl) a WHERE a.grantee=0),
                      p.proconfig::text, has_table_privilege('platform.users','SELECT'),
                      has_table_privilege('platform.tenant_memberships','SELECT')
                    FROM pg_proc p WHERE p.oid='platform.discover_tenants(uuid,integer,bigint)'::regprocedure
                    """)) {
                rows.next(); assertThat(rows.getBoolean(1)).isTrue(); assertThat(rows.getBoolean(2)).isTrue();
                assertThat(rows.getString(3)).contains("search_path"); assertThat(rows.getBoolean(4)).isFalse(); assertThat(rows.getBoolean(5)).isFalse();
            }
        }
        try (var connection = DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());
             var statement = connection.prepareStatement("SELECT password_hash FROM platform.users WHERE id=?")) {
            statement.setObject(1,user); try (var rows = statement.executeQuery()) { rows.next(); assertThat(rows.getString(1)).isEqualTo(hash); }
        }
    }
}
