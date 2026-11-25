package io.github.sye1321.stockbook.warehouses;

import java.time.Instant;
import java.util.UUID;

public record WarehouseSummary(
        UUID id,
        String code,
        String name,
        UUID locationId,
        String locationCode,
        String locationName,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {}
