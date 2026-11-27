package io.github.sye1321.stockbook.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

record OwnerFundingContribution(
        UUID id,
        UUID requestId,
        UUID cashAccountId,
        BigDecimal amount,
        LocalDate effectiveDate,
        UUID initiatedBy,
        UUID approvedBy,
        UUID journalId,
        Instant postedAt) {}
