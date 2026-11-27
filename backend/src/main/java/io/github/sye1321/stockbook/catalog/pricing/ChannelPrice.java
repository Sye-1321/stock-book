package io.github.sye1321.stockbook.catalog.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ChannelPrice(
        UUID id,
        UUID productId,
        UUID unitId,
        String unitCode,
        SalesChannel channel,
        BigDecimal amount,
        BigDecimal conversionFactor,
        Instant validFrom,
        Instant validTo,
        UUID recordedBy) {}
