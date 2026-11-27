package io.github.sye1321.stockbook.suppliers;

import java.time.Instant;
import java.util.UUID;

public record Supplier(
        UUID id,
        UUID partyId,
        String code,
        String name,
        String phone,
        String email,
        boolean active,
        boolean partyActive,
        Instant partyUpdatedAt,
        Instant createdAt,
        Instant updatedAt) {}
