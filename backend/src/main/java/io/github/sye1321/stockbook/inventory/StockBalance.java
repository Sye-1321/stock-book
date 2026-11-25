package io.github.sye1321.stockbook.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record StockBalance(
        UUID warehouseId,
        String warehouseCode,
        String warehouseName,
        UUID productId,
        String productSku,
        String productName,
        UUID baseUnitId,
        String baseUnitCode,
        BigDecimal baseQuantity,
        Instant updatedAt) {}
