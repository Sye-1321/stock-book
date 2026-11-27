package io.github.sye1321.stockbook.finance;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PeriodRepository {

    private static final String SELECT = """
            select id, name, starts_on, ends_on, period_state, created_at, closed_at, closed_by
            from financial_period
            """;

    private final JdbcClient jdbcClient;

    public PeriodRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<FinancialPeriod> findAll() {
        return jdbcClient.sql(SELECT + " order by starts_on desc").query(PeriodRepository::map).list();
    }

    public Optional<FinancialPeriod> findById(UUID id) {
        return jdbcClient
                .sql(SELECT + " where id = :id")
                .param("id", id)
                .query(PeriodRepository::map)
                .optional();
    }

    public void create(UUID id, String name, LocalDate startsOn, LocalDate endsOn) {
        jdbcClient
                .sql("select finance_create_period(:id, :name, :startsOn, :endsOn)")
                .param("id", id)
                .param("name", name)
                .param("startsOn", startsOn)
                .param("endsOn", endsOn)
                .query(UUID.class)
                .single();
    }

    public void close(UUID id, UUID actor) {
        jdbcClient
                .sql("select finance_close_period(:id, :actor)")
                .param("id", id)
                .param("actor", actor)
                .query(UUID.class)
                .single();
    }

    private static FinancialPeriod map(ResultSet resultSet, int rowNumber) throws SQLException {
        OffsetDateTime closedAt = resultSet.getObject("closed_at", OffsetDateTime.class);
        return new FinancialPeriod(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("name"),
                resultSet.getObject("starts_on", LocalDate.class),
                resultSet.getObject("ends_on", LocalDate.class),
                PeriodState.valueOf(resultSet.getString("period_state")),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                closedAt == null ? null : closedAt.toInstant(),
                resultSet.getObject("closed_by", UUID.class));
    }

}
