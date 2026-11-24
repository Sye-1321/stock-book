package io.github.sye1321.stockbook.identity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserAccountRepository {

    private static final String SELECT_COLUMNS = """
            select id, login_identifier, display_name, password_hash, active, role, session_version,
                   created_at, updated_at, password_changed_at
            from app_user
            """;

    private final JdbcClient jdbcClient;

    public UserAccountRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<UserAccount> findByLoginIdentifier(String loginIdentifier) {
        return jdbcClient
                .sql(SELECT_COLUMNS + " where login_identifier = :loginIdentifier")
                .param("loginIdentifier", loginIdentifier)
                .query(UserAccountRepository::mapUser)
                .optional();
    }

    public Optional<UserAccount> findById(UUID id) {
        return jdbcClient
                .sql(SELECT_COLUMNS + " where id = :id")
                .param("id", id)
                .query(UserAccountRepository::mapUser)
                .optional();
    }

    public Optional<UserAccount> findByIdForUpdate(UUID id) {
        return jdbcClient
                .sql(SELECT_COLUMNS + " where id = :id for update")
                .param("id", id)
                .query(UserAccountRepository::mapUser)
                .optional();
    }

    public long count() {
        return jdbcClient.sql("select count(*) from app_user").query(Long.class).single();
    }

    public java.util.List<UserAccount> findAll() {
        return jdbcClient
                .sql(SELECT_COLUMNS + " order by display_name, login_identifier")
                .query(UserAccountRepository::mapUser)
                .list();
    }

    public long countActiveAdministrators() {
        return jdbcClient
                .sql("select count(*) from app_user where active and role = 'ADMIN'")
                .query(Long.class)
                .single();
    }

    public void insert(UserAccount user) {
        jdbcClient
                .sql("""
                        insert into app_user (
                            id, login_identifier, display_name, password_hash, active,
                            role, session_version, created_at, updated_at, password_changed_at
                        ) values (
                            :id, :loginIdentifier, :displayName, :passwordHash, :active,
                            :role, :sessionVersion, :createdAt, :updatedAt, :passwordChangedAt
                        )
                        """)
                .param("id", user.id())
                .param("loginIdentifier", user.loginIdentifier())
                .param("displayName", user.displayName())
                .param("passwordHash", user.passwordHash())
                .param("active", user.active())
                .param("role", user.role().name())
                .param("sessionVersion", user.sessionVersion())
                .param("createdAt", toDatabaseTimestamp(user.createdAt()))
                .param("updatedAt", toDatabaseTimestamp(user.updatedAt()))
                .param("passwordChangedAt", toDatabaseTimestamp(user.passwordChangedAt()))
                .update();
    }

    public void updatePassword(UUID id, String passwordHash, Instant changedAt) {
        int updated = jdbcClient
                .sql("""
                        update app_user
                        set password_hash = :passwordHash,
                            password_changed_at = :changedAt,
                            updated_at = :changedAt,
                            session_version = session_version + 1
                        where id = :id
                        """)
                .param("id", id)
                .param("passwordHash", passwordHash)
                .param("changedAt", toDatabaseTimestamp(changedAt))
                .update();
        if (updated != 1) {
            throw new IllegalStateException("User account was not updated");
        }
    }

    public void updateActive(UUID id, boolean active, Instant changedAt) {
        int updated = jdbcClient
                .sql("""
                        update app_user
                        set active = :active,
                            updated_at = :changedAt,
                            session_version = session_version + 1
                        where id = :id
                        """)
                .param("id", id)
                .param("active", active)
                .param("changedAt", toDatabaseTimestamp(changedAt))
                .update();
        if (updated != 1) {
            throw new IllegalStateException("User account was not updated");
        }
    }

    public void updateStaffAccount(UUID id, String displayName, AccountRole role, boolean active, Instant changedAt) {
        int updated = jdbcClient
                .sql("""
                        update app_user
                        set display_name = :displayName,
                            role = :role,
                            active = :active,
                            updated_at = :changedAt,
                            session_version = case
                                when role <> :role or active <> :active then session_version + 1
                                else session_version
                            end
                        where id = :id
                        """)
                .param("id", id)
                .param("displayName", displayName)
                .param("role", role.name())
                .param("active", active)
                .param("changedAt", toDatabaseTimestamp(changedAt))
                .update();
        if (updated != 1) {
            throw new IllegalStateException("User account was not updated");
        }
    }

    private static UserAccount mapUser(ResultSet resultSet, int rowNumber) throws SQLException {
        return new UserAccount(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("login_identifier"),
                resultSet.getString("display_name"),
                resultSet.getString("password_hash"),
                resultSet.getBoolean("active"),
                AccountRole.valueOf(resultSet.getString("role")),
                resultSet.getLong("session_version"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("password_changed_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime toDatabaseTimestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
