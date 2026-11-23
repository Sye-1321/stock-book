package io.github.sye1321.stockbook.system;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
class DatabaseIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private Flyway flyway;

    @Autowired
    private DatabaseStatusChecker databaseStatusChecker;

    @Test
    void connectsToPostgresAndAppliesTheInitialMigration() {
        assertThat(jdbcClient.sql("select version()").query(String.class).single())
                .startsWith("PostgreSQL");
        assertThat(flyway.info().all())
                .anySatisfy(migration -> {
                    assertThat(migration.getVersion()).isEqualTo(MigrationVersion.fromVersion("1"));
                    assertThat(migration.getState()).isEqualTo(MigrationState.SUCCESS);
                })
                .allMatch(migration -> !migration.getState().isFailed());
        assertThat(databaseStatusChecker.isAvailable()).isTrue();
    }
}
