package io.github.sye1321.stockbook.suppliers;

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
public class SupplierRepository {

    private static final String SELECT = """
            select id, code, name, phone, email, active, created_at, updated_at
            from supplier
            """;

    private final JdbcClient jdbcClient;

    public SupplierRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Supplier> findAll() {
        return jdbcClient.sql(SELECT + " order by code").query(SupplierRepository::map).list();
    }

    public Optional<Supplier> findById(UUID id) {
        return jdbcClient
                .sql(SELECT + " where id = :id")
                .param("id", id)
                .query(SupplierRepository::map)
                .optional();
    }

    public Optional<Supplier> findByIdForUpdate(UUID id) {
        return jdbcClient
                .sql(SELECT + " where id = :id for update")
                .param("id", id)
                .query(SupplierRepository::map)
                .optional();
    }

    public void insert(Supplier supplier) {
        jdbcClient
                .sql("""
                        insert into supplier (
                            id, code, name, phone, email, active, created_at, updated_at
                        ) values (
                            :id, :code, :name, :phone, :email, :active, :createdAt, :updatedAt
                        )
                        """)
                .param("id", supplier.id())
                .param("code", supplier.code())
                .param("name", supplier.name())
                .param("phone", supplier.phone())
                .param("email", supplier.email())
                .param("active", supplier.active())
                .param("createdAt", timestamp(supplier.createdAt()))
                .param("updatedAt", timestamp(supplier.updatedAt()))
                .update();
    }

    public void update(
            UUID id,
            String code,
            String name,
            String phone,
            String email,
            boolean active,
            Instant updatedAt) {
        int count = jdbcClient
                .sql("""
                        update supplier
                        set code = :code, name = :name, phone = :phone, email = :email,
                            active = :active, updated_at = :updatedAt
                        where id = :id
                        """)
                .param("id", id)
                .param("code", code)
                .param("name", name)
                .param("phone", phone)
                .param("email", email)
                .param("active", active)
                .param("updatedAt", timestamp(updatedAt))
                .update();
        if (count != 1) throw new IllegalStateException("Supplier was not updated");
    }

    private static Supplier map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Supplier(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("code"),
                resultSet.getString("name"),
                resultSet.getString("phone"),
                resultSet.getString("email"),
                resultSet.getBoolean("active"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
