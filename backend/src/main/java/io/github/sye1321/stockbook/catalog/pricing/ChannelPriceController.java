package io.github.sye1321.stockbook.catalog.pricing;

import io.github.sye1321.stockbook.catalog.pricing.ChannelPriceService.InvalidPriceException;
import io.github.sye1321.stockbook.catalog.pricing.ChannelPriceService.PriceConflictException;
import io.github.sye1321.stockbook.catalog.pricing.ChannelPriceService.PriceNotFoundException;
import io.github.sye1321.stockbook.identity.StockBookUserDetails;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products/{productId}/prices")
public class ChannelPriceController {
    private final ChannelPriceService service;
    public ChannelPriceController(ChannelPriceService service) { this.service = service; }

    @GetMapping
    public List<PriceResponse> history(@PathVariable UUID productId) {
        return service.history(productId).stream().map(PriceResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<PriceResponse> setCurrent(
            @PathVariable UUID productId,
            @RequestBody PriceRequest request,
            @AuthenticationPrincipal StockBookUserDetails user) {
        ChannelPrice price = service.setCurrent(productId,
                request == null ? null : request.unitId(), request == null ? null : request.channel(),
                request == null ? null : request.amount(),
                request == null ? null : request.expectedCurrentValidFrom(), user.id());
        return ResponseEntity.created(URI.create("/api/v1/products/" + productId + "/prices/" + price.id()))
                .body(PriceResponse.from(price));
    }

    @ExceptionHandler(InvalidPriceException.class)
    ResponseEntity<ErrorResponse> invalid(InvalidPriceException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("invalid_request", exception.getMessage()));
    }
    @ExceptionHandler(PriceNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("not_found", "Product was not found"));
    }
    @ExceptionHandler(PriceConflictException.class)
    ResponseEntity<ErrorResponse> conflict(PriceConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("conflict", exception.getMessage()));
    }

    public record PriceRequest(
            UUID unitId, SalesChannel channel, BigDecimal amount, Instant expectedCurrentValidFrom) {}
    public record PriceResponse(
            UUID id, UUID productId, UUID unitId, String unitCode, SalesChannel channel,
            String amount, String conversionFactor, Instant validFrom, Instant validTo) {
        static PriceResponse from(ChannelPrice price) {
            return new PriceResponse(price.id(), price.productId(), price.unitId(), price.unitCode(),
                    price.channel(), price.amount().toPlainString(), price.conversionFactor() == null
                            ? null : price.conversionFactor().toPlainString(),
                    price.validFrom(), price.validTo());
        }
    }
    public record ErrorResponse(String code, String message) {}
}
