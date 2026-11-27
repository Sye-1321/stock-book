package io.github.sye1321.stockbook.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

record OwnerFundingRequest(
        UUID requestId,
        UUID cashAccountId,
        BigDecimal amount,
        LocalDate effectiveDate,
        UUID initiatedBy,
        UUID approvalId) {}
