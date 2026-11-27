package io.github.sye1321.stockbook.finance;

import java.math.BigDecimal;
import java.util.UUID;

public record JournalLineRequest(UUID accountId, BigDecimal debit, BigDecimal credit) {}
