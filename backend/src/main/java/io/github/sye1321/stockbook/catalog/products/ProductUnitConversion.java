package io.github.sye1321.stockbook.catalog.products;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductUnitConversion(
        UUID productId,
        UUID alternateUnitId,
        String alternateUnitCode,
        String alternateUnitName,
        BigDecimal conversionFactor,
        boolean active,
        boolean packagingCompatible,
        Instant updatedAt) {}
