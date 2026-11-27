package io.github.sye1321.stockbook.finance;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PeriodService {

    private final PeriodRepository periodRepository;
    PeriodService(PeriodRepository periodRepository) {
        this.periodRepository = periodRepository;
    }

    @Transactional(readOnly = true)
    List<FinancialPeriod> list() {
        return periodRepository.findAll();
    }

    @Transactional
    FinancialPeriod create(String name, LocalDate startsOn, LocalDate endsOn) {
        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.isBlank()
                || normalizedName.length() > 100
                || startsOn == null
                || endsOn == null
                || startsOn.isAfter(endsOn)) {
            throw new InvalidPeriodException("A name and valid date range are required");
        }
        UUID id = UUID.randomUUID();
        try {
            periodRepository.create(id, normalizedName, startsOn, endsOn);
        } catch (DataAccessException exception) {
            throw new InvalidPeriodException("The financial period overlaps or conflicts with an existing period");
        }
        return periodRepository.findById(id).orElseThrow();
    }

    @Transactional
    FinancialPeriod close(UUID id, UUID actor) {
        if (id == null || actor == null) {
            throw new InvalidPeriodException("Period and actor are required");
        }
        try {
            periodRepository.close(id, actor);
        } catch (DataAccessException exception) {
            throw new InvalidPeriodException("The financial period is missing or already closed");
        }
        return periodRepository.findById(id).orElseThrow();
    }

    public static class InvalidPeriodException extends RuntimeException {
        public InvalidPeriodException(String message) {
            super(message);
        }
    }
}
