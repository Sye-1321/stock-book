package io.github.sye1321.stockbook.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record InventoryMovement(
        UUID id,
        UUID requestId,
        UUID transferId,
        UUID receiptLineId,
        UUID receiptId,
        UUID warehouseId,
        String warehouseCode,
        UUID productId,
        String productSku,
        MovementType type,
        BigDecimal enteredQuantity,
        UUID enteredUnitId,
        String enteredUnitCode,
        BigDecimal conversionFactor,
        BigDecimal baseQuantity,
        String reason,
        UUID postedBy,
        String postedByName,
        Instant postedAt) {}
