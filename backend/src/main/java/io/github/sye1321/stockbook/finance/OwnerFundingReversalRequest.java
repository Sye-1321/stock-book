package io.github.sye1321.stockbook.finance;

import java.time.LocalDate;
import java.util.UUID;

record OwnerFundingReversalRequest(
        UUID requestId,
        UUID contributionId,
        LocalDate effectiveDate,
        UUID initiatedBy,
        UUID approvalId) {}
