package io.github.sye1321.stockbook.warehouses;

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
public class WarehouseRepository {

    private static final String SELECT_COLUMNS = """
            select id, code, name, location_id, active, created_at, updated_at
            from warehouse
            """;
    private static final String SELECT_SUMMARY_COLUMNS = """
            select w.id, w.code, w.name, w.location_id, l.code as location_code,
                   l.name as location_name, w.active, w.created_at, w.updated_at
            from warehouse w
            join business_location l on l.id = w.location_id
            """;

    private final JdbcClient jdbcClient;

    public WarehouseRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<WarehouseSummary> findAllSummaries() {
        return jdbcClient
                .sql(SELECT_SUMMARY_COLUMNS + " order by w.code")
                .query(WarehouseRepository::mapSummary)
                .list();
    }

    public Optional<Warehouse> findByIdForUpdate(UUID id) {
        return jdbcClient
                .sql(SELECT_COLUMNS + " where id = :id for update")
                .param("id", id)
                .query(WarehouseRepository::map)
                .optional();
    }

    public Optional<WarehouseSummary> findSummaryById(UUID id) {
        return jdbcClient
                .sql(SELECT_SUMMARY_COLUMNS + " where w.id = :id")
                .param("id", id)
                .query(WarehouseRepository::mapSummary)
                .optional();
    }

    public long countActiveByLocation(UUID locationId) {
        return jdbcClient
                .sql("select count(*) from warehouse where location_id = :locationId and active")
                .param("locationId", locationId)
                .query(Long.class)
                .single();
    }

    public void insert(Warehouse warehouse) {
        jdbcClient
                .sql("""
                        insert into warehouse (id, code, name, location_id, active, created_at, updated_at)
                        values (:id, :code, :name, :locationId, :active, :createdAt, :updatedAt)
                        """)
                .param("id", warehouse.id())
                .param("code", warehouse.code())
                .param("name", warehouse.name())
                .param("locationId", warehouse.locationId())
                .param("active", warehouse.active())
                .param("createdAt", timestamp(warehouse.createdAt()))
                .param("updatedAt", timestamp(warehouse.updatedAt()))
                .update();
    }

    public void update(UUID id, String code, String name, boolean active, Instant updatedAt) {
        int count = jdbcClient
                .sql("""
                        update warehouse
                        set code = :code, name = :name, active = :active, updated_at = :updatedAt
                        where id = :id
                        """)
                .param("id", id)
                .param("code", code)
                .param("name", name)
                .param("active", active)
                .param("updatedAt", timestamp(updatedAt))
                .update();
        if (count != 1) throw new IllegalStateException("Warehouse was not updated");
    }

    private static Warehouse map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Warehouse(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("code"),
                resultSet.getString("name"),
                resultSet.getObject("location_id", UUID.class),
                resultSet.getBoolean("active"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static WarehouseSummary mapSummary(ResultSet resultSet, int rowNumber) throws SQLException {
        return new WarehouseSummary(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("code"),
                resultSet.getString("name"),
                resultSet.getObject("location_id", UUID.class),
                resultSet.getString("location_code"),
                resultSet.getString("location_name"),
                resultSet.getBoolean("active"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
