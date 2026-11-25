package io.github.sye1321.stockbook.warehouses;

import io.github.sye1321.stockbook.warehouses.WarehouseService.InvalidWarehouseException;
import io.github.sye1321.stockbook.warehouses.WarehouseService.WarehouseConflictException;
import io.github.sye1321.stockbook.warehouses.WarehouseService.WarehouseNotFoundException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/warehouses")
public class WarehouseController {

    private final WarehouseService warehouseService;

    public WarehouseController(WarehouseService warehouseService) {
        this.warehouseService = warehouseService;
    }

    @GetMapping
    public List<WarehouseSummary> list() {
        return warehouseService.list();
    }

    @PostMapping
    public ResponseEntity<WarehouseSummary> create(@RequestBody CreateWarehouseRequest request) {
        WarehouseSummary created = warehouseService.create(
                request == null ? null : request.code(),
                request == null ? null : request.name(),
                request == null ? null : request.locationId());
        return ResponseEntity.created(URI.create("/api/v1/warehouses/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public WarehouseSummary update(@PathVariable UUID id, @RequestBody UpdateWarehouseRequest request) {
        return warehouseService.update(
                id,
                request == null ? null : request.code(),
                request == null ? null : request.name(),
                request == null ? null : request.active());
    }

    @ExceptionHandler(InvalidWarehouseException.class)
    ResponseEntity<ErrorResponse> invalid(InvalidWarehouseException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("invalid_request", exception.getMessage()));
    }

    @ExceptionHandler(WarehouseNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(WarehouseNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("not_found", "Warehouse was not found"));
    }

    @ExceptionHandler(WarehouseConflictException.class)
    ResponseEntity<ErrorResponse> conflict(WarehouseConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("conflict", exception.getMessage()));
    }

    public record CreateWarehouseRequest(String code, String name, UUID locationId) {}

    public record UpdateWarehouseRequest(String code, String name, Boolean active) {}

    public record ErrorResponse(String code, String message) {}
}
