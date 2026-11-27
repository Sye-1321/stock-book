package io.github.sye1321.stockbook.catalog.products;

import java.time.Instant;
import java.util.UUID;

public record ProductSummary(
        UUID id,
        String sku,
        String name,
        UUID baseUnitId,
        String baseUnitCode,
        String baseUnitName,
        UUID categoryId,
        String categoryName,
        UUID brandId,
        String brandName,
        UUID familyId,
        String familyName,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {}
