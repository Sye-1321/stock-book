package io.github.sye1321.stockbook.finance;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class JournalService {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final JournalRepository journalRepository;
    private final AccountRepository accountRepository;
    private final Clock clock;

    JournalService(
            JournalRepository journalRepository, AccountRepository accountRepository, Clock clock) {
        this.journalRepository = journalRepository;
        this.accountRepository = accountRepository;
        this.clock = clock;
    }

    @Transactional
    JournalEntry post(JournalPostingRequest request) {
        validate(request);
        Instant recordedAt = clock.instant();
        LocalDate businessDate = recordedAt.atZone(accountRepository.settings().timezone()).toLocalDate();
        if (request.effectiveDate().isAfter(businessDate)) {
            throw new InvalidJournalException("Future-effective journals are not permitted");
        }

        UUID id = UUID.randomUUID();
        try {
            journalRepository.post(id, request);
        } catch (DataAccessException exception) {
            if (hasSqlState(exception, "23505")) {
                throw new DuplicateJournalException(
                        "A journal already exists for this source and posting purpose");
            }
            throw new InvalidJournalException("Journal posting was rejected by the financial ledger");
        }
        return journalRepository.findById(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    List<AccountBalance> balances() {
        return journalRepository.balances();
    }

    private static void validate(JournalPostingRequest request) {
        if (request == null
                || request.sourceType() == null
                || request.sourceId() == null
                || request.postingPurpose() == null
                || request.effectiveDate() == null
                || request.actorId() == null
                || request.lines() == null
                || request.lines().size() < 2) {
            throw new InvalidJournalException("Complete source, date, actor and at least two lines are required");
        }
        BigDecimal debits = ZERO;
        BigDecimal credits = ZERO;
        for (JournalLineRequest line : request.lines()) {
            if (line == null || line.accountId() == null) {
                throw new InvalidJournalException("Every journal line requires an account");
            }
            BigDecimal debit = money(line.debit());
            BigDecimal credit = money(line.credit());
            boolean valid = debit.signum() > 0 && credit.signum() == 0
                    || credit.signum() > 0 && debit.signum() == 0;
            if (!valid) {
                throw new InvalidJournalException("Each line must contain one positive debit or credit");
            }
            debits = debits.add(debit);
            credits = credits.add(credit);
        }
        if (debits.compareTo(credits) != 0) {
            throw new InvalidJournalException("Journal debits and credits must balance");
        }
    }

    private static BigDecimal money(BigDecimal amount) {
        BigDecimal value = amount == null ? ZERO : amount;
        if (value.stripTrailingZeros().scale() > 2 || value.precision() - value.scale() > 18) {
            throw new InvalidJournalException("Money amounts must fit NUMERIC(20,2) without rounding");
        }
        return value;
    }

    private static boolean hasSqlState(Throwable exception, String sqlState) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException sqlException && sqlState.equals(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public static class InvalidJournalException extends RuntimeException {
        public InvalidJournalException(String message) {
            super(message);
        }
    }

    public static class DuplicateJournalException extends RuntimeException {
        public DuplicateJournalException(String message) {
            super(message);
        }
    }
}
