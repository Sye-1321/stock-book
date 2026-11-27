package io.github.sye1321.stockbook.finance;

import java.math.BigDecimal;
import java.util.UUID;

public record JournalLine(
        int lineNumber, UUID accountId, String accountCode, BigDecimal debit, BigDecimal credit) {}
