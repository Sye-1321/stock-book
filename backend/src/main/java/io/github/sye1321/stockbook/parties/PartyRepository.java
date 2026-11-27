package io.github.sye1321.stockbook.parties;

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
public class PartyRepository {

    private static final String SELECT = """
            select p.id, p.name, p.phone, p.email, p.active, p.created_at, p.updated_at,
                   exists (select 1 from supplier s where s.party_id = p.id) as supplier,
                   exists (select 1 from customer c where c.party_id = p.id) as customer
            from party p
            """;

    private final JdbcClient jdbcClient;

    public PartyRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Party> findForLookup(String query) {
        String pattern = "%" + query.toLowerCase() + "%";
        return jdbcClient
                .sql(SELECT + """
                         where lower(p.name) like :pattern
                            or lower(coalesce(p.email, '')) like :pattern
                         order by p.name, p.id
                         limit 50
                         """)
                .param("pattern", pattern)
                .query(PartyRepository::map)
                .list();
    }

    public Optional<Party> findByIdForUpdate(UUID id) {
        return jdbcClient
                .sql("""
                        select p.id, p.name, p.phone, p.email, p.active,
                               p.created_at, p.updated_at,
                               exists (select 1 from supplier s where s.party_id = p.id) as supplier,
                               exists (select 1 from customer c where c.party_id = p.id) as customer
                        from party p
                        where p.id = :id
                        for update of p
                        """)
                .param("id", id)
                .query(PartyRepository::map)
                .optional();
    }

    public void insert(Party party) {
        jdbcClient
                .sql("""
                        insert into party (id, name, phone, email, active, created_at, updated_at)
                        values (:id, :name, :phone, :email, :active, :createdAt, :updatedAt)
                        """)
                .param("id", party.id())
                .param("name", party.name())
                .param("phone", party.phone())
                .param("email", party.email())
                .param("active", party.active())
                .param("createdAt", timestamp(party.createdAt()))
                .param("updatedAt", timestamp(party.updatedAt()))
                .update();
    }

    public boolean update(
            UUID id,
            String name,
            String phone,
            String email,
            boolean active,
            Instant expectedUpdatedAt,
            Instant updatedAt) {
        int count = jdbcClient
                .sql("""
                        update party
                        set name = :name, phone = :phone, email = :email,
                            active = :active, updated_at = :updatedAt
                        where id = :id and updated_at = :expectedUpdatedAt
                        """)
                .param("id", id)
                .param("name", name)
                .param("phone", phone)
                .param("email", email)
                .param("active", active)
                .param("expectedUpdatedAt", timestamp(expectedUpdatedAt))
                .param("updatedAt", timestamp(updatedAt))
                .update();
        return count == 1;
    }

    private static Party map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Party(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("name"),
                resultSet.getString("phone"),
                resultSet.getString("email"),
                resultSet.getBoolean("active"),
                resultSet.getBoolean("supplier"),
                resultSet.getBoolean("customer"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
