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
    void upgradesAnExistingAuthenticationSchemaWithoutEditingAppliedMigrations() throws SQLException {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .target("3")
                .load()
                .migrate();
        assertThat(principalNameLength()).isEqualTo(100);
        insertExistingAdministrator();

        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();
        assertThat(principalNameLength()).isEqualTo(190);
        assertThat(existingAdministratorRole()).isEqualTo("ADMIN");
    }

    private void insertExistingAdministrator() throws SQLException {
        try (var connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = connection.prepareStatement("""
                        insert into app_user (
                            id, login_identifier, display_name, password_hash, active,
                            session_version, created_at, updated_at, password_changed_at
                        ) values (
                            '00000000-0000-0000-0000-000000000001', 'admin@example.com',
                            'Initial Administrator', '{noop}password', true, 0, now(), now(), now()
                        )
                        """)) {
            statement.executeUpdate();
        }
    }

    private String existingAdministratorRole() throws SQLException {
        try (var connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = connection.prepareStatement(
                        "select role from app_user where login_identifier = 'admin@example.com'");
                var result = statement.executeQuery()) {
            if (!result.next()) {
                throw new AssertionError("Existing administrator was not preserved");
            }
            return result.getString(1);
        }
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
