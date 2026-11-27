package io.github.sye1321.stockbook.finance;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OwnerFundingService {

    private final OwnerFundingRepository repository;

    OwnerFundingService(OwnerFundingRepository repository) {
        this.repository = repository;
    }

    @Transactional
    OwnerFundingContribution post(OwnerFundingRequest request) {
        validate(request);
        try {
            UUID journalId = repository.post(UUID.randomUUID(), UUID.randomUUID(), request);
            return repository.findContributionByJournalId(journalId).orElseThrow();
        } catch (DataAccessException exception) {
            throw translate(exception, "Owner funding was rejected");
        }
    }

    @Transactional
    OwnerFundingReversal reverse(OwnerFundingReversalRequest request) {
        validate(request);
        try {
            UUID journalId = repository.reverse(UUID.randomUUID(), UUID.randomUUID(), request);
            return repository.findReversalByJournalId(journalId).orElseThrow();
        } catch (DataAccessException exception) {
            throw translate(exception, "Owner funding reversal was rejected");
        }
    }

    private static void validate(OwnerFundingRequest request) {
        if (request == null
                || request.requestId() == null
                || request.cashAccountId() == null
                || request.effectiveDate() == null
                || request.initiatedBy() == null
                || request.approvalId() == null) {
            throw new InvalidOwnerFundingException("Complete funding and persisted approval are required");
        }
        money(request.amount());
    }

    private static void validate(OwnerFundingReversalRequest request) {
        if (request == null
                || request.requestId() == null
                || request.contributionId() == null
                || request.effectiveDate() == null
                || request.initiatedBy() == null
                || request.approvalId() == null) {
            throw new InvalidOwnerFundingException(
                    "Complete funding reversal and persisted approval are required");
        }
    }

    private static void money(BigDecimal amount) {
        if (amount == null
                || amount.signum() <= 0
                || amount.stripTrailingZeros().scale() > 2
                || amount.precision() - amount.scale() > 18) {
            throw new InvalidOwnerFundingException(
                    "Funding must be positive and fit NUMERIC(20,2) without rounding");
        }
    }

    private static RuntimeException translate(DataAccessException exception, String message) {
        String sqlState = sqlState(exception);
        if ("23505".equals(sqlState)) {
            return new DuplicateOwnerFundingException(
                    message + " because its request ID is already used", exception);
        }
        if ("42501".equals(sqlState)) {
            return new OwnerFundingAuthorizationException(
                    message + " because approval is not authorized", exception);
        }
        return new InvalidOwnerFundingException(message, exception);
    }

    private static String sqlState(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }

    static class InvalidOwnerFundingException extends RuntimeException {
        InvalidOwnerFundingException(String message) {
            super(message);
        }

        InvalidOwnerFundingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static class DuplicateOwnerFundingException extends RuntimeException {
        DuplicateOwnerFundingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static class OwnerFundingAuthorizationException extends RuntimeException {
        OwnerFundingAuthorizationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
