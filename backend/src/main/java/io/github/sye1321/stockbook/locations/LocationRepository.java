package io.github.sye1321.stockbook.locations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LocationRepository {

    private static final String SELECT_COLUMNS = """
            select id, code, name, active, created_at, updated_at
            from business_location
            """;

    private final JdbcClient jdbcClient;

    public LocationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Location> findAll() {
        return jdbcClient.sql(SELECT_COLUMNS + " order by code").query(LocationRepository::map).list();
    }

    public Optional<Location> findById(UUID id) {
        return jdbcClient
                .sql(SELECT_COLUMNS + " where id = :id")
                .param("id", id)
                .query(LocationRepository::map)
                .optional();
    }

    public Optional<Location> findByIdForUpdate(UUID id) {
        return jdbcClient
                .sql(SELECT_COLUMNS + " where id = :id for update")
                .param("id", id)
                .query(LocationRepository::map)
                .optional();
    }

    public void insert(Location location) {
        jdbcClient
                .sql("""
                        insert into business_location (id, code, name, active, created_at, updated_at)
                        values (:id, :code, :name, :active, :createdAt, :updatedAt)
                        """)
                .param("id", location.id())
                .param("code", location.code())
                .param("name", location.name())
                .param("active", location.active())
                .param("createdAt", timestamp(location.createdAt()))
                .param("updatedAt", timestamp(location.updatedAt()))
                .update();
    }

    public void update(UUID id, String code, String name, boolean active, Instant updatedAt) {
        int count = jdbcClient
                .sql("""
                        update business_location
                        set code = :code, name = :name, active = :active, updated_at = :updatedAt
                        where id = :id
                        """)
                .param("id", id)
                .param("code", code)
                .param("name", name)
                .param("active", active)
                .param("updatedAt", timestamp(updatedAt))
                .update();
        if (count != 1) throw new IllegalStateException("Location was not updated");
    }

    private static Location map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Location(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("code"),
                resultSet.getString("name"),
                resultSet.getBoolean("active"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
