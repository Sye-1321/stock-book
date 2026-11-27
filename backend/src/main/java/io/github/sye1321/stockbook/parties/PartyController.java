package io.github.sye1321.stockbook.parties;

import io.github.sye1321.stockbook.parties.PartyService.InvalidPartyException;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/parties")
public class PartyController {

    private final PartyService service;

    public PartyController(PartyService service) {
        this.service = service;
    }

    @GetMapping
    public List<Party> lookup(@RequestParam(defaultValue = "") String query) {
        return service.lookup(query);
    }

    @ExceptionHandler(InvalidPartyException.class)
    ResponseEntity<ErrorResponse> invalid(InvalidPartyException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("invalid_request", exception.getMessage()));
    }

    public record ErrorResponse(String code, String message) {}
}
