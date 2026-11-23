package io.github.sye1321.stockbook.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class SessionPrincipalMigrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void expandsAnExistingSpringSessionSchemaWithoutEditingAppliedMigrations() throws SQLException {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .target("3")
                .load()
                .migrate();
        assertThat(principalNameLength()).isEqualTo(100);

        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();
        assertThat(principalNameLength()).isEqualTo(190);
    }

    private int principalNameLength() throws SQLException {
        try (var connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = connection.prepareStatement("""
                        select character_maximum_length
                        from information_schema.columns
                        where table_schema = 'public'
                          and table_name = 'spring_session'
                          and column_name = 'principal_name'
                        """);
                var result = statement.executeQuery()) {
            if (!result.next()) {
                throw new AssertionError("spring_session.principal_name was not created");
            }
            return result.getInt(1);
        }
    }
}
