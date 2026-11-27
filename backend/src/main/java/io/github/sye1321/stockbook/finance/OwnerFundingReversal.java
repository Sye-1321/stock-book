package io.github.sye1321.stockbook.finance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

record OwnerFundingReversal(
        UUID id,
        UUID requestId,
        UUID contributionId,
        LocalDate effectiveDate,
        UUID initiatedBy,
        UUID approvedBy,
        UUID journalId,
        Instant postedAt) {}
