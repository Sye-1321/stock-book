package io.github.sye1321.stockbook.locations;

import java.time.Instant;
import java.util.UUID;

public record Location(
        UUID id, String code, String name, boolean active, Instant createdAt, Instant updatedAt) {}
