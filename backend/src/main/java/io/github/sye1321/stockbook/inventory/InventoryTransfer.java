package io.github.sye1321.stockbook.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record InventoryTransfer(
        UUID id,
        UUID requestId,
        UUID sourceWarehouseId,
        String sourceWarehouseCode,
        UUID destinationWarehouseId,
        String destinationWarehouseCode,
        UUID productId,
        String productSku,
        BigDecimal enteredQuantity,
        UUID enteredUnitId,
        String enteredUnitCode,
        BigDecimal conversionFactor,
        BigDecimal baseQuantity,
        String reason,
        UUID postedBy,
        String postedByName,
        Instant postedAt) {}
