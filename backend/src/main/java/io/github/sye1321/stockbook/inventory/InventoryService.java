package io.github.sye1321.stockbook.inventory;

import io.github.sye1321.stockbook.catalog.products.Product;
import io.github.sye1321.stockbook.catalog.products.ProductRepository;
import io.github.sye1321.stockbook.catalog.products.ProductUnitConversion;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_PAGE = 10_000;

    private final InventoryRepository inventoryRepository;
    private final ProductRepository productRepository;
    private final Clock clock;

    public InventoryService(
            InventoryRepository inventoryRepository, ProductRepository productRepository, Clock clock) {
        this.inventoryRepository = inventoryRepository;
        this.productRepository = productRepository;
        this.clock = clock;
    }

    @Transactional
    public PostResult post(
            UUID requestId,
            UUID warehouseId,
            UUID productId,
            MovementType type,
            BigDecimal enteredQuantity,
            UUID enteredUnitId,
            String reason,
            UUID postedBy) {
        validateRequired(requestId, warehouseId, productId, type, enteredQuantity, enteredUnitId, postedBy);
        String normalizedReason = normalizeReason(type, reason);

        inventoryRepository.lockRequest(requestId);
        InventoryMovement existing = inventoryRepository.findMovementByRequestId(requestId).orElse(null);
        if (existing != null) {
            if (sameRequest(
                    existing,
                    warehouseId,
                    productId,
                    type,
                    enteredQuantity,
                    enteredUnitId,
                    normalizedReason)) {
                return new PostResult(existing, false);
            }
            throw new InventoryConflictException("Request ID has already been used with different movement data");
        }

        InventoryRepository.WarehouseState warehouse = inventoryRepository
                .lockWarehouse(warehouseId)
                .orElseThrow(() -> new InvalidInventoryException("Warehouse was not found"));
        if (!warehouse.active() || !warehouse.locationActive()) {
            throw new InvalidInventoryException("Warehouse and its location must be active");
        }

        Product product = productRepository
                .findById(productId)
                .orElseThrow(() -> new InvalidInventoryException("Product was not found"));
        if (!product.active()) throw new InvalidInventoryException("Product must be active");

        BigDecimal factor = conversionFactor(product, enteredUnitId);
        BigDecimal unsignedBaseQuantity = enteredQuantity.multiply(factor);
        BigDecimal signedBaseQuantity = type == MovementType.ADJUSTMENT_OUT
                ? unsignedBaseQuantity.negate()
                : unsignedBaseQuantity;

        BigDecimal currentBalance = inventoryRepository
                .lockBalance(warehouseId, productId)
                .orElse(BigDecimal.ZERO);
        if (type == MovementType.OPENING && inventoryRepository.hasMovements(warehouseId, productId)) {
            throw new InventoryConflictException(
                    "An opening balance is allowed only before other movements for this warehouse and product");
        }
        BigDecimal newBalance = currentBalance.add(signedBaseQuantity);
        if (newBalance.signum() < 0) {
            throw new InventoryConflictException("Insufficient stock for this adjustment");
        }

        Instant postedAt = clock.instant();
        UUID movementId = UUID.randomUUID();
        inventoryRepository.insertMovement(
                movementId,
                requestId,
                warehouseId,
                productId,
                type,
                enteredQuantity,
                enteredUnitId,
                factor,
                signedBaseQuantity,
                normalizedReason,
                postedBy,
                postedAt);
        inventoryRepository.upsertBalance(warehouseId, productId, newBalance, postedAt);
        return new PostResult(
                inventoryRepository
                        .findMovementByRequestId(requestId)
                        .orElseThrow(() -> new IllegalStateException("Posted movement was not found")),
                true);
    }

    @Transactional(readOnly = true)
    public List<StockBalance> balances(UUID warehouseId, UUID productId) {
        return inventoryRepository.findBalances(warehouseId, productId);
    }

    @Transactional(readOnly = true)
    public MovementPage movements(UUID warehouseId, UUID productId, int page, int size) {
        if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidInventoryException(
                    "Page must be between 0 and 10000 and size must be between 1 and 100");
        }
        List<InventoryMovement> results =
                inventoryRepository.findMovements(warehouseId, productId, page * size, size + 1);
        boolean hasMore = results.size() > size;
        List<InventoryMovement> items = hasMore ? results.subList(0, size) : results;
        return new MovementPage(List.copyOf(items), page, size, hasMore);
    }

    private BigDecimal conversionFactor(Product product, UUID enteredUnitId) {
        if (product.baseUnitId().equals(enteredUnitId)) return BigDecimal.ONE;
        return productRepository
                .findConversion(product.id(), enteredUnitId)
                .map(ProductUnitConversion::conversionFactor)
                .orElseThrow(() -> new InvalidInventoryException(
                        "Entered unit must be the product's base unit or a configured conversion"));
    }

    private static void validateRequired(
            UUID requestId,
            UUID warehouseId,
            UUID productId,
            MovementType type,
            BigDecimal enteredQuantity,
            UUID enteredUnitId,
            UUID postedBy) {
        if (requestId == null
                || warehouseId == null
                || productId == null
                || type == null
                || enteredUnitId == null
                || postedBy == null) {
            throw new InvalidInventoryException("All movement identifiers and type are required");
        }
        if (enteredQuantity == null || enteredQuantity.signum() <= 0) {
            throw new InvalidInventoryException("Quantity must be greater than zero");
        }
    }

    private static String normalizeReason(MovementType type, String reason) {
        String normalized = reason == null ? null : reason.trim();
        if (normalized != null && normalized.length() > 500) {
            throw new InvalidInventoryException("Reason must not exceed 500 characters");
        }
        if (type != MovementType.OPENING && (normalized == null || normalized.isEmpty())) {
            throw new InvalidInventoryException("A reason is required for adjustments");
        }
        return normalized == null || normalized.isEmpty() ? null : normalized;
    }

    private static boolean sameRequest(
            InventoryMovement existing,
            UUID warehouseId,
            UUID productId,
            MovementType type,
            BigDecimal enteredQuantity,
            UUID enteredUnitId,
            String reason) {
        return existing.warehouseId().equals(warehouseId)
                && existing.productId().equals(productId)
                && existing.type() == type
                && existing.enteredQuantity().compareTo(enteredQuantity) == 0
                && existing.enteredUnitId().equals(enteredUnitId)
                && Objects.equals(existing.reason(), reason);
    }

    public record PostResult(InventoryMovement movement, boolean created) {}

    public static class InvalidInventoryException extends RuntimeException {
        public InvalidInventoryException(String message) {
            super(message);
        }
    }

    public static class InventoryConflictException extends RuntimeException {
        public InventoryConflictException(String message) {
            super(message);
        }
    }
}
