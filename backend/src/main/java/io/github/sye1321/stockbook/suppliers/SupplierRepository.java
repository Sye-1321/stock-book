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
            select s.id, s.party_id, s.code, p.name, p.phone, p.email,
                   s.active, p.active as party_active, p.updated_at as party_updated_at,
                   s.created_at, s.updated_at
            from supplier s
            join party p on p.id = s.party_id
            """;

    private final JdbcClient jdbcClient;

    public SupplierRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Supplier> findAll() {
        return jdbcClient.sql(SELECT + " order by s.code").query(SupplierRepository::map).list();
    }

    public Optional<Supplier> findById(UUID id) {
        return jdbcClient
                .sql(SELECT + " where s.id = :id")
                .param("id", id)
                .query(SupplierRepository::map)
                .optional();
    }

    public Optional<Supplier> findByIdForUpdate(UUID id) {
        return jdbcClient
                .sql(SELECT + " where s.id = :id for update of s, p")
                .param("id", id)
                .query(SupplierRepository::map)
                .optional();
    }

    public void insert(Supplier supplier) {
        jdbcClient
                .sql("""
                        insert into supplier (
                            id, party_id, code, active, created_at, updated_at
                        ) values (
                            :id, :partyId, :code, :active, :createdAt, :updatedAt
                        )
                        """)
                .param("id", supplier.id())
                .param("partyId", supplier.partyId())
                .param("code", supplier.code())
                .param("active", supplier.active())
                .param("createdAt", timestamp(supplier.createdAt()))
                .param("updatedAt", timestamp(supplier.updatedAt()))
                .update();
    }

    public void update(
            UUID id,
            String code,
            boolean active,
            Instant updatedAt) {
        int count = jdbcClient
                .sql("""
                        update supplier
                        set code = :code, active = :active, updated_at = :updatedAt
                        where id = :id
                        """)
                .param("id", id)
                .param("code", code)
                .param("active", active)
                .param("updatedAt", timestamp(updatedAt))
                .update();
        if (count != 1) throw new IllegalStateException("Supplier was not updated");
    }

    private static Supplier map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Supplier(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("party_id", UUID.class),
                resultSet.getString("code"),
                resultSet.getString("name"),
                resultSet.getString("phone"),
                resultSet.getString("email"),
                resultSet.getBoolean("active"),
                resultSet.getBoolean("party_active"),
                resultSet.getObject("party_updated_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
