package io.github.sye1321.stockbook.finance;

import java.util.UUID;

public record FinanceAccount(
        UUID id,
        String code,
        String name,
        AccountRole role,
        AccountType type,
        boolean active,
        boolean postingAllowed) {}
