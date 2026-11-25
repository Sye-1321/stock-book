package io.github.sye1321.stockbook.inventory;

import java.util.List;

public record MovementPage(List<InventoryMovement> items, int page, int size, boolean hasMore) {}
