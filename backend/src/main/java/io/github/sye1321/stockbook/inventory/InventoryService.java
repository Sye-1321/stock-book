package io.github.sye1321.stockbook.inventory;

import io.github.sye1321.stockbook.catalog.products.Product;
import io.github.sye1321.stockbook.catalog.products.ProductRepository;
import io.github.sye1321.stockbook.catalog.products.ProductUnitConversion;
import io.github.sye1321.stockbook.suppliers.Supplier;
import io.github.sye1321.stockbook.suppliers.SupplierRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_PAGE = 10_000;

    private final InventoryRepository inventoryRepository;
    private final ProductRepository productRepository;
    private final SupplierRepository supplierRepository;
    private final Clock clock;

    public InventoryService(
            InventoryRepository inventoryRepository,
            ProductRepository productRepository,
            SupplierRepository supplierRepository,
            Clock clock) {
        this.inventoryRepository = inventoryRepository;
        this.productRepository = productRepository;
        this.supplierRepository = supplierRepository;
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
        if (type == MovementType.TRANSFER_IN || type == MovementType.TRANSFER_OUT) {
            throw new InvalidInventoryException("Transfer movements must be posted through the transfers endpoint");
        }
        if (type == MovementType.RECEIPT_IN) {
            throw new InvalidInventoryException("Receipt movements must be posted through the receipts endpoint");
        }
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
                null,
                null,
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

    @Transactional
    public TransferResult transfer(
            UUID requestId,
            UUID sourceWarehouseId,
            UUID destinationWarehouseId,
            UUID productId,
            BigDecimal enteredQuantity,
            UUID enteredUnitId,
            String reason,
            UUID postedBy) {
        validateTransferRequired(
                requestId,
                sourceWarehouseId,
                destinationWarehouseId,
                productId,
                enteredQuantity,
                enteredUnitId,
                postedBy);
        String normalizedReason = normalizeTransferReason(reason);

        inventoryRepository.lockRequest(requestId);
        InventoryTransfer existing = inventoryRepository.findTransferByRequestId(requestId).orElse(null);
        if (existing != null) {
            if (sameTransferRequest(
                    existing,
                    sourceWarehouseId,
                    destinationWarehouseId,
                    productId,
                    enteredQuantity,
                    enteredUnitId,
                    normalizedReason)) {
                return new TransferResult(existing, false);
            }
            throw new InventoryConflictException("Request ID has already been used with different transfer data");
        }

        UUID firstWarehouseId = sourceWarehouseId.compareTo(destinationWarehouseId) < 0
                ? sourceWarehouseId
                : destinationWarehouseId;
        UUID secondWarehouseId = firstWarehouseId.equals(sourceWarehouseId)
                ? destinationWarehouseId
                : sourceWarehouseId;
        InventoryRepository.WarehouseState first = requireActiveWarehouse(firstWarehouseId);
        InventoryRepository.WarehouseState second = requireActiveWarehouse(secondWarehouseId);
        if (!first.active() || !first.locationActive() || !second.active() || !second.locationActive()) {
            throw new InvalidInventoryException("Both warehouses and their locations must be active");
        }

        Product product = productRepository
                .findById(productId)
                .orElseThrow(() -> new InvalidInventoryException("Product was not found"));
        if (!product.active()) throw new InvalidInventoryException("Product must be active");
        BigDecimal factor = conversionFactor(product, enteredUnitId);
        BigDecimal baseQuantity = enteredQuantity.multiply(factor);

        BigDecimal firstBalance = inventoryRepository
                .lockBalance(firstWarehouseId, productId)
                .orElse(BigDecimal.ZERO);
        BigDecimal secondBalance = inventoryRepository
                .lockBalance(secondWarehouseId, productId)
                .orElse(BigDecimal.ZERO);
        BigDecimal sourceBalance = sourceWarehouseId.equals(firstWarehouseId)
                ? firstBalance
                : secondBalance;
        BigDecimal destinationBalance = destinationWarehouseId.equals(firstWarehouseId)
                ? firstBalance
                : secondBalance;
        BigDecimal newSourceBalance = sourceBalance.subtract(baseQuantity);
        if (newSourceBalance.signum() < 0) {
            throw new InventoryConflictException("Insufficient stock for this transfer");
        }
        BigDecimal newDestinationBalance = destinationBalance.add(baseQuantity);

        Instant postedAt = clock.instant();
        UUID transferId = UUID.randomUUID();
        inventoryRepository.insertTransfer(
                transferId,
                requestId,
                sourceWarehouseId,
                destinationWarehouseId,
                productId,
                enteredQuantity,
                enteredUnitId,
                factor,
                baseQuantity,
                normalizedReason,
                postedBy,
                postedAt);
        inventoryRepository.insertMovement(
                UUID.randomUUID(),
                null,
                transferId,
                null,
                sourceWarehouseId,
                productId,
                MovementType.TRANSFER_OUT,
                enteredQuantity,
                enteredUnitId,
                factor,
                baseQuantity.negate(),
                normalizedReason,
                postedBy,
                postedAt);
        inventoryRepository.insertMovement(
                UUID.randomUUID(),
                null,
                transferId,
                null,
                destinationWarehouseId,
                productId,
                MovementType.TRANSFER_IN,
                enteredQuantity,
                enteredUnitId,
                factor,
                baseQuantity,
                normalizedReason,
                postedBy,
                postedAt);
        inventoryRepository.upsertBalance(
                sourceWarehouseId, productId, newSourceBalance, postedAt);
        inventoryRepository.upsertBalance(
                destinationWarehouseId, productId, newDestinationBalance, postedAt);
        return new TransferResult(
                inventoryRepository
                        .findTransferByRequestId(requestId)
                        .orElseThrow(() -> new IllegalStateException("Posted transfer was not found")),
                true);
    }

    @Transactional
    public ReceiptResult receive(
            UUID requestId,
            UUID supplierId,
            UUID warehouseId,
            String supplierDocumentReference,
            List<ReceiptLineRequest> lines,
            UUID postedBy) {
        if (requestId == null || supplierId == null || warehouseId == null || postedBy == null) {
            throw new InvalidInventoryException("Receipt, supplier and warehouse identifiers are required");
        }
        if (lines == null || lines.isEmpty()) {
            throw new InvalidInventoryException("A receipt must contain at least one line");
        }
        String reference = normalizeReference(supplierDocumentReference);
        List<ReceiptLineRequest> canonicalLines = canonicalizeReceiptLines(lines);

        inventoryRepository.lockRequest(requestId);
        GoodsReceipt existing = inventoryRepository.findReceiptByRequestId(requestId).orElse(null);
        if (existing != null) {
            if (sameReceiptRequest(
                    existing, supplierId, warehouseId, reference, canonicalLines)) {
                return new ReceiptResult(existing, false);
            }
            throw new InventoryConflictException("Request ID has already been used with different receipt data");
        }

        Supplier supplier = supplierRepository
                .findByIdForUpdate(supplierId)
                .orElseThrow(() -> new InvalidInventoryException("Supplier was not found"));
        if (!supplier.active() || !supplier.partyActive()) {
            throw new InvalidInventoryException("Supplier and its party must be active");
        }
        InventoryRepository.WarehouseState warehouse = requireActiveWarehouse(warehouseId);
        if (!warehouse.active() || !warehouse.locationActive()) {
            throw new InvalidInventoryException("Warehouse and its location must be active");
        }

        List<PreparedReceiptLine> prepared = new ArrayList<>();
        for (ReceiptLineRequest line : canonicalLines) {
            Product product = productRepository
                    .findById(line.productId())
                    .orElseThrow(() -> new InvalidInventoryException("Product was not found"));
            if (!product.active()) throw new InvalidInventoryException("All receipt products must be active");
            BigDecimal factor = conversionFactor(product, line.unitId());
            prepared.add(new PreparedReceiptLine(
                    line, factor, line.quantity().multiply(factor)));
        }

        List<BigDecimal> currentBalances = new ArrayList<>();
        for (PreparedReceiptLine line : prepared) {
            currentBalances.add(inventoryRepository
                    .lockBalance(warehouseId, line.request().productId())
                    .orElse(BigDecimal.ZERO));
        }

        Instant postedAt = clock.instant();
        UUID receiptId = UUID.randomUUID();
        inventoryRepository.insertReceipt(
                receiptId,
                requestId,
                supplierId,
                warehouseId,
                reference,
                postedBy,
                postedAt);
        for (int index = 0; index < prepared.size(); index++) {
            PreparedReceiptLine line = prepared.get(index);
            UUID receiptLineId = UUID.randomUUID();
            inventoryRepository.insertReceiptLine(
                    receiptLineId,
                    receiptId,
                    index + 1,
                    line.request().productId(),
                    line.request().quantity(),
                    line.request().unitId(),
                    line.factor(),
                    line.baseQuantity());
            inventoryRepository.insertMovement(
                    UUID.randomUUID(),
                    null,
                    null,
                    receiptLineId,
                    warehouseId,
                    line.request().productId(),
                    MovementType.RECEIPT_IN,
                    line.request().quantity(),
                    line.request().unitId(),
                    line.factor(),
                    line.baseQuantity(),
                    "Goods receipt",
                    postedBy,
                    postedAt);
            inventoryRepository.upsertBalance(
                    warehouseId,
                    line.request().productId(),
                    currentBalances.get(index).add(line.baseQuantity()),
                    postedAt);
        }
        return new ReceiptResult(
                inventoryRepository
                        .findReceiptByRequestId(requestId)
                        .orElseThrow(() -> new IllegalStateException("Posted receipt was not found")),
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

    private InventoryRepository.WarehouseState requireActiveWarehouse(UUID warehouseId) {
        return inventoryRepository
                .lockWarehouse(warehouseId)
                .orElseThrow(() -> new InvalidInventoryException("Warehouse was not found"));
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

    private static void validateTransferRequired(
            UUID requestId,
            UUID sourceWarehouseId,
            UUID destinationWarehouseId,
            UUID productId,
            BigDecimal enteredQuantity,
            UUID enteredUnitId,
            UUID postedBy) {
        if (requestId == null
                || sourceWarehouseId == null
                || destinationWarehouseId == null
                || productId == null
                || enteredUnitId == null
                || postedBy == null) {
            throw new InvalidInventoryException("All transfer identifiers are required");
        }
        if (sourceWarehouseId.equals(destinationWarehouseId)) {
            throw new InvalidInventoryException("Source and destination warehouses must be different");
        }
        if (enteredQuantity == null || enteredQuantity.signum() <= 0) {
            throw new InvalidInventoryException("Quantity must be greater than zero");
        }
    }

    private static String normalizeTransferReason(String reason) {
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isEmpty() || normalized.length() > 500) {
            throw new InvalidInventoryException("Reason is required and must not exceed 500 characters");
        }
        return normalized;
    }

    private static String normalizeReference(String reference) {
        String normalized = reference == null ? "" : reference.trim();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > 100) {
            throw new InvalidInventoryException("Supplier document reference must not exceed 100 characters");
        }
        return normalized;
    }

    private static List<ReceiptLineRequest> canonicalizeReceiptLines(
            List<ReceiptLineRequest> lines) {
        Set<UUID> products = new HashSet<>();
        List<ReceiptLineRequest> canonical = new ArrayList<>();
        for (ReceiptLineRequest line : lines) {
            if (line == null || line.productId() == null || line.unitId() == null) {
                throw new InvalidInventoryException("Every receipt line requires a product and unit");
            }
            if (line.quantity() == null || line.quantity().signum() <= 0) {
                throw new InvalidInventoryException("Every receipt quantity must be greater than zero");
            }
            if (!products.add(line.productId())) {
                throw new InvalidInventoryException("A product may appear only once in a receipt");
            }
            canonical.add(line);
        }
        canonical.sort(Comparator.comparing(ReceiptLineRequest::productId));
        return List.copyOf(canonical);
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

    private static boolean sameTransferRequest(
            InventoryTransfer existing,
            UUID sourceWarehouseId,
            UUID destinationWarehouseId,
            UUID productId,
            BigDecimal enteredQuantity,
            UUID enteredUnitId,
            String reason) {
        return existing.sourceWarehouseId().equals(sourceWarehouseId)
                && existing.destinationWarehouseId().equals(destinationWarehouseId)
                && existing.productId().equals(productId)
                && existing.enteredQuantity().compareTo(enteredQuantity) == 0
                && existing.enteredUnitId().equals(enteredUnitId)
                && existing.reason().equals(reason);
    }

    private static boolean sameReceiptRequest(
            GoodsReceipt existing,
            UUID supplierId,
            UUID warehouseId,
            String reference,
            List<ReceiptLineRequest> lines) {
        if (!existing.supplierId().equals(supplierId)
                || !existing.warehouseId().equals(warehouseId)
                || !Objects.equals(existing.supplierDocumentReference(), reference)
                || existing.lines().size() != lines.size()) {
            return false;
        }
        for (int index = 0; index < lines.size(); index++) {
            GoodsReceiptLine existingLine = existing.lines().get(index);
            ReceiptLineRequest requestedLine = lines.get(index);
            if (!existingLine.productId().equals(requestedLine.productId())
                    || !existingLine.enteredUnitId().equals(requestedLine.unitId())
                    || existingLine.enteredQuantity().compareTo(requestedLine.quantity()) != 0) {
                return false;
            }
        }
        return true;
    }

    public record PostResult(InventoryMovement movement, boolean created) {}

    public record TransferResult(InventoryTransfer transfer, boolean created) {}

    public record ReceiptLineRequest(UUID productId, BigDecimal quantity, UUID unitId) {}

    public record ReceiptResult(GoodsReceipt receipt, boolean created) {}

    private record PreparedReceiptLine(
            ReceiptLineRequest request, BigDecimal factor, BigDecimal baseQuantity) {}

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
