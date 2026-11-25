package io.github.sye1321.stockbook.locations;

import io.github.sye1321.stockbook.locations.LocationService.InvalidLocationException;
import io.github.sye1321.stockbook.locations.LocationService.LocationConflictException;
import io.github.sye1321.stockbook.locations.LocationService.LocationNotFoundException;
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
@RequestMapping("/api/v1/locations")
public class LocationController {

    private final LocationService locationService;

    public LocationController(LocationService locationService) {
        this.locationService = locationService;
    }

    @GetMapping
    public List<Location> list() {
        return locationService.list();
    }

    @PostMapping
    public ResponseEntity<Location> create(@RequestBody CreateLocationRequest request) {
        Location created = locationService.create(
                request == null ? null : request.code(), request == null ? null : request.name());
        return ResponseEntity.created(URI.create("/api/v1/locations/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public Location update(@PathVariable UUID id, @RequestBody UpdateLocationRequest request) {
        return locationService.update(
                id,
                request == null ? null : request.code(),
                request == null ? null : request.name(),
                request == null ? null : request.active());
    }

    @ExceptionHandler(InvalidLocationException.class)
    ResponseEntity<ErrorResponse> invalid(InvalidLocationException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("invalid_request", exception.getMessage()));
    }

    @ExceptionHandler(LocationNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(LocationNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("not_found", "Location was not found"));
    }

    @ExceptionHandler(LocationConflictException.class)
    ResponseEntity<ErrorResponse> conflict(LocationConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("conflict", exception.getMessage()));
    }

    public record CreateLocationRequest(String code, String name) {}

    public record UpdateLocationRequest(String code, String name, Boolean active) {}

    public record ErrorResponse(String code, String message) {}
}
