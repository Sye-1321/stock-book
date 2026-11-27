package io.github.sye1321.stockbook.suppliers;

import io.github.sye1321.stockbook.suppliers.SupplierService.InvalidSupplierException;
import io.github.sye1321.stockbook.suppliers.SupplierService.SupplierConflictException;
import io.github.sye1321.stockbook.suppliers.SupplierService.SupplierNotFoundException;
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
@RequestMapping("/api/v1/suppliers")
public class SupplierController {

    private final SupplierService service;

    public SupplierController(SupplierService service) {
        this.service = service;
    }

    @GetMapping
    public List<Supplier> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<Supplier> create(@RequestBody SupplierRequest request) {
        Supplier supplier = service.create(
                request == null ? null : request.code(),
                request == null ? null : request.name(),
                request == null ? null : request.phone(),
                request == null ? null : request.email());
        return ResponseEntity.created(URI.create("/api/v1/suppliers/" + supplier.id()))
                .body(supplier);
    }

    @PutMapping("/{id}")
    public Supplier update(@PathVariable UUID id, @RequestBody SupplierRequest request) {
        return service.update(
                id,
                request == null ? null : request.code(),
                request == null ? null : request.name(),
                request == null ? null : request.phone(),
                request == null ? null : request.email(),
                request == null ? null : request.active(),
                request == null ? null : request.partyActive(),
                request == null ? null : request.updateParty(),
                request == null ? null : request.partyUpdatedAt());
    }

    @ExceptionHandler(InvalidSupplierException.class)
    ResponseEntity<ErrorResponse> invalid(InvalidSupplierException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("invalid_request", exception.getMessage()));
    }

    @ExceptionHandler(SupplierNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(SupplierNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("not_found", "Supplier was not found"));
    }

    @ExceptionHandler(SupplierConflictException.class)
    ResponseEntity<ErrorResponse> conflict(SupplierConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("conflict", exception.getMessage()));
    }

    public record SupplierRequest(
            String code,
            String name,
            String phone,
            String email,
            Boolean active,
            Boolean partyActive,
            Boolean updateParty,
            java.time.Instant partyUpdatedAt) {}

    public record ErrorResponse(String code, String message) {}
}
