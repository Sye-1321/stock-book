package io.github.sye1321.stockbook.catalog.classifications;

import java.time.Instant;
import java.util.UUID;

public record ProductClassification(
        UUID id, String code, String name, boolean active, Instant createdAt, Instant updatedAt) {}
