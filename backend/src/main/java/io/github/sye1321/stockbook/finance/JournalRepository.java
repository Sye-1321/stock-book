package io.github.sye1321.stockbook.finance;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JournalRepository {

    private static final String SELECT = """
            select id, period_id, source_type, source_id, posting_purpose, effective_date,
                   recorded_at, posted_by, reverses_journal_id
            from journal_entry
            """;

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public JournalRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public void post(UUID id, JournalPostingRequest request) {
        String lines = objectMapper.writeValueAsString(request.lines());
        jdbcClient
                .sql("""
                        select finance_post_journal(
                            :id, :sourceType, :sourceId, :purpose, :effectiveDate,
                            :actor, :reversesJournalId, cast(:lines as jsonb)
                        )
                        """)
                .param("id", id)
                .param("sourceType", request.sourceType().name())
                .param("sourceId", request.sourceId())
                .param("purpose", request.postingPurpose().name())
                .param("effectiveDate", request.effectiveDate())
                .param("actor", request.actorId())
                .param("reversesJournalId", request.reversesJournalId(), Types.OTHER)
                .param("lines", lines)
                .query(UUID.class)
                .single();
    }

    public Optional<JournalEntry> findById(UUID id) {
        return jdbcClient
                .sql(SELECT + " where id = :id")
                .param("id", id)
                .query((resultSet, rowNumber) -> map(resultSet))
                .optional();
    }

    public Optional<JournalEntry> findBySource(
            JournalSourceType sourceType, UUID sourceId, PostingPurpose purpose) {
        return jdbcClient
                .sql(SELECT + " where source_type = :sourceType and source_id = :sourceId"
                        + " and posting_purpose = :purpose")
                .param("sourceType", sourceType.name())
                .param("sourceId", sourceId)
                .param("purpose", purpose.name())
                .query((resultSet, rowNumber) -> map(resultSet))
                .optional();
    }

    public List<AccountBalance> balances() {
        return jdbcClient
                .sql("""
                        select a.id, a.code, a.account_type,
                               coalesce(sum(l.debit), 0) as debit,
                               coalesce(sum(l.credit), 0) as credit
                        from finance_account a
                        left join journal_entry_line l on l.account_id = a.id
                        group by a.id, a.code, a.account_type
                        order by a.code
                        """)
                .query((resultSet, rowNumber) -> new AccountBalance(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("code"),
                        AccountType.valueOf(resultSet.getString("account_type")),
                        resultSet.getBigDecimal("debit"),
                        resultSet.getBigDecimal("credit")))
                .list();
    }

    private JournalEntry map(ResultSet resultSet) throws SQLException {
        UUID journalId = resultSet.getObject("id", UUID.class);
        List<JournalLine> lines = jdbcClient
                .sql("""
                        select l.line_number, l.account_id, a.code, l.debit, l.credit
                        from journal_entry_line l
                        join finance_account a on a.id = l.account_id
                        where l.journal_id = :journalId order by l.line_number
                        """)
                .param("journalId", journalId)
                .query(JournalRepository::mapLine)
                .list();
        return new JournalEntry(
                journalId,
                resultSet.getObject("period_id", UUID.class),
                JournalSourceType.valueOf(resultSet.getString("source_type")),
                resultSet.getObject("source_id", UUID.class),
                PostingPurpose.valueOf(resultSet.getString("posting_purpose")),
                resultSet.getObject("effective_date", LocalDate.class),
                resultSet.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("posted_by", UUID.class),
                resultSet.getObject("reverses_journal_id", UUID.class),
                lines);
    }

    private static JournalLine mapLine(ResultSet resultSet, int rowNumber) throws SQLException {
        return new JournalLine(
                resultSet.getInt("line_number"),
                resultSet.getObject("account_id", UUID.class),
                resultSet.getString("code"),
                resultSet.getBigDecimal("debit"),
                resultSet.getBigDecimal("credit"));
    }

}
