package io.github.sye1321.stockbook.finance;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class OwnerFundingRepository {

    private final JdbcClient jdbcClient;

    OwnerFundingRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    UUID post(UUID id, UUID journalId, OwnerFundingRequest request) {
        return jdbcClient
                .sql("""
                        select finance_post_owner_funding(
                            :id, :requestId, :cashAccountId, :amount, :effectiveDate,
                            :initiatedBy, :approvalId, :journalId
                        )
                        """)
                .param("id", id)
                .param("requestId", request.requestId())
                .param("cashAccountId", request.cashAccountId())
                .param("amount", request.amount())
                .param("effectiveDate", request.effectiveDate())
                .param("initiatedBy", request.initiatedBy())
                .param("approvalId", request.approvalId())
                .param("journalId", journalId)
                .query(UUID.class)
                .single();
    }

    UUID reverse(UUID id, UUID journalId, OwnerFundingReversalRequest request) {
        return jdbcClient
                .sql("""
                        select finance_reverse_owner_funding(
                            :id, :requestId, :contributionId, :effectiveDate,
                            :initiatedBy, :approvalId, :journalId
                        )
                        """)
                .param("id", id)
                .param("requestId", request.requestId())
                .param("contributionId", request.contributionId())
                .param("effectiveDate", request.effectiveDate())
                .param("initiatedBy", request.initiatedBy())
                .param("approvalId", request.approvalId())
                .param("journalId", journalId)
                .query(UUID.class)
                .single();
    }

    Optional<OwnerFundingContribution> findContributionByJournalId(UUID journalId) {
        return jdbcClient
                .sql("""
                        select id, request_id, cash_account_id, amount, effective_date,
                               initiated_by, approved_by, journal_id, posted_at
                          from owner_funding_contribution where journal_id = :journalId
                        """)
                .param("journalId", journalId)
                .query(OwnerFundingRepository::mapContribution)
                .optional();
    }

    Optional<OwnerFundingReversal> findReversalByJournalId(UUID journalId) {
        return jdbcClient
                .sql("""
                        select id, request_id, contribution_id, effective_date,
                               initiated_by, approved_by, journal_id, posted_at
                          from owner_funding_reversal where journal_id = :journalId
                        """)
                .param("journalId", journalId)
                .query(OwnerFundingRepository::mapReversal)
                .optional();
    }

    private static OwnerFundingContribution mapContribution(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new OwnerFundingContribution(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("request_id", UUID.class),
                resultSet.getObject("cash_account_id", UUID.class),
                resultSet.getBigDecimal("amount"),
                resultSet.getObject("effective_date", LocalDate.class),
                resultSet.getObject("initiated_by", UUID.class),
                resultSet.getObject("approved_by", UUID.class),
                resultSet.getObject("journal_id", UUID.class),
                resultSet.getObject("posted_at", OffsetDateTime.class).toInstant());
    }

    private static OwnerFundingReversal mapReversal(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new OwnerFundingReversal(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("request_id", UUID.class),
                resultSet.getObject("contribution_id", UUID.class),
                resultSet.getObject("effective_date", LocalDate.class),
                resultSet.getObject("initiated_by", UUID.class),
                resultSet.getObject("approved_by", UUID.class),
                resultSet.getObject("journal_id", UUID.class),
                resultSet.getObject("posted_at", OffsetDateTime.class).toInstant());
    }
}
