package io.github.sye1321.stockbook.customers;

import java.time.Instant;
import java.util.UUID;

public record Customer(
        UUID id,
        UUID partyId,
        String code,
        String name,
        String phone,
        String email,
        int paymentTermsDays,
        boolean active,
        boolean partyActive,
        Instant partyUpdatedAt,
        Instant createdAt,
        Instant updatedAt) {}
