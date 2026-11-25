package io.github.sye1321.stockbook.inventory;

import io.github.sye1321.stockbook.identity.StockBookUserDetails;
import io.github.sye1321.stockbook.inventory.InventoryService.InvalidInventoryException;
import io.github.sye1321.stockbook.inventory.InventoryService.InventoryConflictException;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {

    private final InventoryService service;

    public InventoryController(InventoryService service) {
        this.service = service;
    }

    @PostMapping("/movements")
    public ResponseEntity<MovementResponse> post(
            @RequestBody PostMovementRequest request,
            @AuthenticationPrincipal StockBookUserDetails user) {
        InventoryService.PostResult result = service.post(
                request == null ? null : request.requestId(),
                request == null ? null : request.warehouseId(),
                request == null ? null : request.productId(),
                request == null ? null : request.type(),
                request == null ? null : request.quantity(),
                request == null ? null : request.unitId(),
                request == null ? null : request.reason(),
                user.id());
        MovementResponse response = MovementResponse.from(result.movement());
        if (!result.created()) return ResponseEntity.ok(response);
        return ResponseEntity.created(URI.create("/api/v1/inventory/movements/" + response.id()))
                .body(response);
    }

    @GetMapping("/balances")
    public List<BalanceResponse> balances(
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) UUID productId) {
        return service.balances(warehouseId, productId).stream().map(BalanceResponse::from).toList();
    }

    @GetMapping("/movements")
    public MovementPageResponse movements(
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        MovementPage result = service.movements(warehouseId, productId, page, size);
        return new MovementPageResponse(
                result.items().stream().map(MovementResponse::from).toList(),
                result.page(),
                result.size(),
                result.hasMore());
    }

    @ExceptionHandler(InvalidInventoryException.class)
    ResponseEntity<ErrorResponse> invalid(InvalidInventoryException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("invalid_request", exception.getMessage()));
    }

    @ExceptionHandler(InventoryConflictException.class)
    ResponseEntity<ErrorResponse> conflict(InventoryConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("conflict", exception.getMessage()));
    }

    public record PostMovementRequest(
            UUID requestId,
            UUID warehouseId,
            UUID productId,
            MovementType type,
            BigDecimal quantity,
            UUID unitId,
            String reason) {}

    public record MovementResponse(
            UUID id,
            UUID requestId,
            UUID warehouseId,
            String warehouseCode,
            UUID productId,
            String productSku,
            MovementType type,
            String enteredQuantity,
            UUID enteredUnitId,
            String enteredUnitCode,
            String conversionFactor,
            String baseQuantity,
            String reason,
            UUID postedBy,
            String postedByName,
            java.time.Instant postedAt) {
        static MovementResponse from(InventoryMovement movement) {
            return new MovementResponse(
                    movement.id(),
                    movement.requestId(),
                    movement.warehouseId(),
                    movement.warehouseCode(),
                    movement.productId(),
                    movement.productSku(),
                    movement.type(),
                    movement.enteredQuantity().toPlainString(),
                    movement.enteredUnitId(),
                    movement.enteredUnitCode(),
                    movement.conversionFactor().toPlainString(),
                    movement.baseQuantity().toPlainString(),
                    movement.reason(),
                    movement.postedBy(),
                    movement.postedByName(),
                    movement.postedAt());
        }
    }

    public record BalanceResponse(
            UUID warehouseId,
            String warehouseCode,
            String warehouseName,
            UUID productId,
            String productSku,
            String productName,
            UUID baseUnitId,
            String baseUnitCode,
            String baseQuantity,
            java.time.Instant updatedAt) {
        static BalanceResponse from(StockBalance balance) {
            return new BalanceResponse(
                    balance.warehouseId(),
                    balance.warehouseCode(),
                    balance.warehouseName(),
                    balance.productId(),
                    balance.productSku(),
                    balance.productName(),
                    balance.baseUnitId(),
                    balance.baseUnitCode(),
                    balance.baseQuantity().toPlainString(),
                    balance.updatedAt());
        }
    }

    public record MovementPageResponse(
            List<MovementResponse> items, int page, int size, boolean hasMore) {}

    public record ErrorResponse(String code, String message) {}
}
