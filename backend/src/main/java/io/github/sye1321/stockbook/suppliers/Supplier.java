package io.github.sye1321.stockbook.suppliers;

import java.time.Instant;
import java.util.UUID;

public record Supplier(
        UUID id,
        String code,
        String name,
        String phone,
        String email,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {}
