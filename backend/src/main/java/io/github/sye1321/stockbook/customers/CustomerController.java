package io.github.sye1321.stockbook.customers;

import io.github.sye1321.stockbook.customers.CustomerService.CustomerConflictException;
import io.github.sye1321.stockbook.customers.CustomerService.CustomerNotFoundException;
import io.github.sye1321.stockbook.customers.CustomerService.InvalidCustomerException;
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
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CustomerService service;

    public CustomerController(CustomerService service) {
        this.service = service;
    }

    @GetMapping
    public List<Customer> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<Customer> create(@RequestBody CustomerRequest request) {
        Customer customer = service.create(
                request == null ? null : request.partyId(),
                request == null ? null : request.code(),
                request == null ? null : request.name(),
                request == null ? null : request.phone(),
                request == null ? null : request.email(),
                request == null ? null : request.paymentTermsDays());
        return ResponseEntity.created(URI.create("/api/v1/customers/" + customer.id()))
                .body(customer);
    }

    @PutMapping("/{id}")
    public Customer update(@PathVariable UUID id, @RequestBody CustomerRequest request) {
        return service.update(
                id,
                request == null ? null : request.code(),
                request == null ? null : request.name(),
                request == null ? null : request.phone(),
                request == null ? null : request.email(),
                request == null ? null : request.paymentTermsDays(),
                request == null ? null : request.active(),
                request == null ? null : request.partyActive(),
                request == null ? null : request.updateParty(),
                request == null ? null : request.partyUpdatedAt());
    }

    @ExceptionHandler(InvalidCustomerException.class)
    ResponseEntity<ErrorResponse> invalid(InvalidCustomerException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("invalid_request", exception.getMessage()));
    }

    @ExceptionHandler(CustomerNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(CustomerNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("not_found", "Customer was not found"));
    }

    @ExceptionHandler(CustomerConflictException.class)
    ResponseEntity<ErrorResponse> conflict(CustomerConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("conflict", exception.getMessage()));
    }

    public record CustomerRequest(
            UUID partyId,
            String code,
            String name,
            String phone,
            String email,
            Integer paymentTermsDays,
            Boolean active,
            Boolean partyActive,
            Boolean updateParty,
            java.time.Instant partyUpdatedAt) {}

    public record ErrorResponse(String code, String message) {}
}
