package io.github.sye1321.stockbook.catalog.units;

import io.github.sye1321.stockbook.catalog.units.UnitOfMeasureService.InvalidUnitException;
import io.github.sye1321.stockbook.catalog.units.UnitOfMeasureService.UnitConflictException;
import io.github.sye1321.stockbook.catalog.units.UnitOfMeasureService.UnitNotFoundException;
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
@RequestMapping("/api/v1/units")
public class UnitOfMeasureController {

    private final UnitOfMeasureService service;

    public UnitOfMeasureController(UnitOfMeasureService service) {
        this.service = service;
    }

    @GetMapping
    public List<UnitOfMeasure> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<UnitOfMeasure> create(@RequestBody UnitRequest request) {
        UnitOfMeasure created = service.create(
                request == null ? null : request.code(), request == null ? null : request.name());
        return ResponseEntity.created(URI.create("/api/v1/units/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public UnitOfMeasure update(@PathVariable UUID id, @RequestBody UnitRequest request) {
        return service.update(
                id, request == null ? null : request.code(), request == null ? null : request.name());
    }

    @ExceptionHandler(InvalidUnitException.class)
    ResponseEntity<ErrorResponse> invalid(InvalidUnitException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("invalid_request", exception.getMessage()));
    }

    @ExceptionHandler(UnitNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(UnitNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("not_found", "Unit of measure was not found"));
    }

    @ExceptionHandler(UnitConflictException.class)
    ResponseEntity<ErrorResponse> conflict(UnitConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("conflict", exception.getMessage()));
    }

    public record UnitRequest(String code, String name) {}

    public record ErrorResponse(String code, String message) {}
}
