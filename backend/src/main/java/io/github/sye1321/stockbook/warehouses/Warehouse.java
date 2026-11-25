package io.github.sye1321.stockbook.warehouses;

import java.time.Instant;
import java.util.UUID;

public record Warehouse(
        UUID id,
        String code,
        String name,
        UUID locationId,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {}
