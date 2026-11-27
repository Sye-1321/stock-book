package io.github.sye1321.stockbook.catalog.classifications;

import io.github.sye1321.stockbook.catalog.classifications.ClassificationService.ClassificationConflictException;
import io.github.sye1321.stockbook.catalog.classifications.ClassificationService.ClassificationNotFoundException;
import io.github.sye1321.stockbook.catalog.classifications.ClassificationService.InvalidClassificationException;
import java.net.URI;
import java.time.Instant;
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
@RequestMapping("/api/v1/catalog")
public class ClassificationController {
    private final ClassificationService service;

    public ClassificationController(ClassificationService service) { this.service = service; }

    @GetMapping("/{kind:categories|brands|families}")
    public List<ProductClassification> list(@PathVariable String kind) {
        return service.list(kind(kind));
    }

    @PostMapping("/{kind:categories|brands|families}")
    public ResponseEntity<ProductClassification> create(
            @PathVariable String kind, @RequestBody ClassificationRequest request) {
        ProductClassification created = service.create(kind(kind),
                request == null ? null : request.code(), request == null ? null : request.name());
        return ResponseEntity.created(URI.create("/api/v1/catalog/" + kind + "/" + created.id())).body(created);
    }

    @PutMapping("/{kind:categories|brands|families}/{id}")
    public ProductClassification update(
            @PathVariable String kind, @PathVariable UUID id, @RequestBody ClassificationRequest request) {
        return service.update(kind(kind), id,
                request == null ? null : request.code(), request == null ? null : request.name(),
                request == null ? null : request.active(), request == null ? null : request.expectedUpdatedAt());
    }

    @ExceptionHandler(InvalidClassificationException.class)
    ResponseEntity<ErrorResponse> invalid(InvalidClassificationException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("invalid_request", exception.getMessage()));
    }
    @ExceptionHandler(ClassificationNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("not_found", "Classification was not found"));
    }
    @ExceptionHandler(ClassificationConflictException.class)
    ResponseEntity<ErrorResponse> conflict(ClassificationConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("conflict", exception.getMessage()));
    }

    private static ClassificationKind kind(String value) {
        return switch (value) {
            case "categories" -> ClassificationKind.CATEGORY;
            case "brands" -> ClassificationKind.BRAND;
            case "families" -> ClassificationKind.FAMILY;
            default -> throw new InvalidClassificationException("Unknown classification");
        };
    }

    public record ClassificationRequest(String code, String name, Boolean active, Instant expectedUpdatedAt) {}
    public record ErrorResponse(String code, String message) {}
}
