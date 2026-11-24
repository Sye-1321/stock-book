package io.github.sye1321.stockbook.identity;

import java.time.Instant;
import java.util.UUID;

public record UserAccount(
        UUID id,
        String loginIdentifier,
        String displayName,
        String passwordHash,
        boolean active,
        AccountRole role,
        long sessionVersion,
        Instant createdAt,
        Instant updatedAt,
        Instant passwordChangedAt) {}
