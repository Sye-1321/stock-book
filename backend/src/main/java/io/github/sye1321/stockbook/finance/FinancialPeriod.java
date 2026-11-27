package io.github.sye1321.stockbook.finance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record FinancialPeriod(
        UUID id,
        String name,
        LocalDate startsOn,
        LocalDate endsOn,
        PeriodState state,
        Instant createdAt,
        Instant closedAt,
        UUID closedBy) {}
