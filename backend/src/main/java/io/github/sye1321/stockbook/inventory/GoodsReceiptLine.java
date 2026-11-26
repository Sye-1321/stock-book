package io.github.sye1321.stockbook.inventory;

import java.math.BigDecimal;
import java.util.UUID;

public record GoodsReceiptLine(
        UUID id,
        int lineNumber,
        UUID productId,
        String productSku,
        BigDecimal enteredQuantity,
        UUID enteredUnitId,
        String enteredUnitCode,
        BigDecimal conversionFactor,
        BigDecimal baseQuantity) {}
