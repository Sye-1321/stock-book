package io.github.sye1321.stockbook.finance;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountBalance(
        UUID accountId, String accountCode, AccountType accountType, BigDecimal debit, BigDecimal credit) {

    public BigDecimal netAmount() {
        return switch (accountType) {
            case ASSET, EXPENSE -> debit.subtract(credit);
            case LIABILITY, EQUITY, REVENUE -> credit.subtract(debit);
        };
    }
}
