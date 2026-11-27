package io.github.sye1321.stockbook.catalog.products;

import java.time.Instant;
import java.util.UUID;

public record Product(
        UUID id,
        String sku,
        String name,
        UUID baseUnitId,
        UUID categoryId,
        UUID brandId,
        UUID familyId,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {}
