package io.github.sye1321.stockbook.catalog.classifications;

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
public class ClassificationRepository {
    private final JdbcClient jdbcClient;

    public ClassificationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<ProductClassification> findAll(ClassificationKind kind) {
        return jdbcClient.sql("select id, code, name, active, created_at, updated_at from "
                        + kind.table() + " order by code")
                .query(ClassificationRepository::map)
                .list();
    }

    public Optional<ProductClassification> findById(ClassificationKind kind, UUID id) {
        return jdbcClient.sql("select id, code, name, active, created_at, updated_at from "
                        + kind.table() + " where id = :id")
                .param("id", id)
                .query(ClassificationRepository::map)
                .optional();
    }

    public void insert(ClassificationKind kind, ProductClassification value) {
        jdbcClient.sql("insert into " + kind.table()
                        + " (id, code, name, active, created_at, updated_at)"
                        + " values (:id, :code, :name, :active, :createdAt, :updatedAt)")
                .param("id", value.id())
                .param("code", value.code())
                .param("name", value.name())
                .param("active", value.active())
                .param("createdAt", timestamp(value.createdAt()))
                .param("updatedAt", timestamp(value.updatedAt()))
                .update();
    }

    public int update(
            ClassificationKind kind,
            UUID id,
            String code,
            String name,
            boolean active,
            Instant expectedUpdatedAt,
            Instant updatedAt) {
        return jdbcClient.sql("update " + kind.table()
                        + " set code = :code, name = :name, active = :active, updated_at = :updatedAt"
                        + " where id = :id and updated_at = :expectedUpdatedAt")
                .param("id", id)
                .param("code", code)
                .param("name", name)
                .param("active", active)
                .param("expectedUpdatedAt", timestamp(expectedUpdatedAt))
                .param("updatedAt", timestamp(updatedAt))
                .update();
    }

    private static ProductClassification map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ProductClassification(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("code"),
                resultSet.getString("name"),
                resultSet.getBoolean("active"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime timestamp(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
