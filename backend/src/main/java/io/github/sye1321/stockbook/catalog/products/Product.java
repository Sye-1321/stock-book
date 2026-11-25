package io.github.sye1321.stockbook.catalog.products;

import java.time.Instant;
import java.util.UUID;

public record Product(
        UUID id,
        String sku,
        String name,
        UUID baseUnitId,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {}
