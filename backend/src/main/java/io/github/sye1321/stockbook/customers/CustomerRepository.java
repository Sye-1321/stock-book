package io.github.sye1321.stockbook.customers;

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
public class CustomerRepository {

    private static final String SELECT = """
            select c.id, c.party_id, c.code, p.name, p.phone, p.email,
                   c.payment_terms_days, c.active, p.active as party_active,
                   p.updated_at as party_updated_at,
                   c.created_at, c.updated_at
            from customer c
            join party p on p.id = c.party_id
            """;

    private final JdbcClient jdbcClient;

    public CustomerRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Customer> findAll() {
        return jdbcClient.sql(SELECT + " order by c.code").query(CustomerRepository::map).list();
    }

    public Optional<Customer> findById(UUID id) {
        return jdbcClient
                .sql(SELECT + " where c.id = :id")
                .param("id", id)
                .query(CustomerRepository::map)
                .optional();
    }

    public Optional<Customer> findByIdForUpdate(UUID id) {
        return jdbcClient
                .sql(SELECT + " where c.id = :id for update of c, p")
                .param("id", id)
                .query(CustomerRepository::map)
                .optional();
    }

    public void insert(Customer customer) {
        jdbcClient
                .sql("""
                        insert into customer (
                            id, party_id, code, payment_terms_days, active, created_at, updated_at
                        ) values (
                            :id, :partyId, :code, :paymentTermsDays, :active, :createdAt, :updatedAt
                        )
                        """)
                .param("id", customer.id())
                .param("partyId", customer.partyId())
                .param("code", customer.code())
                .param("paymentTermsDays", customer.paymentTermsDays())
                .param("active", customer.active())
                .param("createdAt", timestamp(customer.createdAt()))
                .param("updatedAt", timestamp(customer.updatedAt()))
                .update();
    }

    public void update(
            UUID id, String code, int paymentTermsDays, boolean active, Instant updatedAt) {
        int count = jdbcClient
                .sql("""
                        update customer
                        set code = :code, payment_terms_days = :paymentTermsDays,
                            active = :active, updated_at = :updatedAt
                        where id = :id
                        """)
                .param("id", id)
                .param("code", code)
                .param("paymentTermsDays", paymentTermsDays)
                .param("active", active)
                .param("updatedAt", timestamp(updatedAt))
                .update();
        if (count != 1) throw new IllegalStateException("Customer was not updated");
    }

    private static Customer map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Customer(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("party_id", UUID.class),
                resultSet.getString("code"),
                resultSet.getString("name"),
                resultSet.getString("phone"),
                resultSet.getString("email"),
                resultSet.getInt("payment_terms_days"),
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
