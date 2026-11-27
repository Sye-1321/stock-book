package io.github.sye1321.stockbook.parties;

import java.time.Instant;
import java.util.UUID;

public record Party(
        UUID id,
        String name,
        String phone,
        String email,
        boolean active,
        boolean supplier,
        boolean customer,
        Instant createdAt,
        Instant updatedAt) {}
