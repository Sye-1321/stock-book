package io.github.sye1321.stockbook.catalog.products;

import io.github.sye1321.stockbook.catalog.products.ProductRepository.ProductConversionNotFoundException;
import io.github.sye1321.stockbook.catalog.products.ProductService.InvalidProductException;
import io.github.sye1321.stockbook.catalog.products.ProductService.ProductConflictException;
import io.github.sye1321.stockbook.catalog.products.ProductService.ProductNotFoundException;
import java.math.BigDecimal;
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
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProductSummary> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<ProductSummary> create(@RequestBody CreateProductRequest request) {
        ProductSummary created = service.create(
                request == null ? null : request.sku(),
                request == null ? null : request.name(),
                request == null ? null : request.baseUnitId());
        return ResponseEntity.created(URI.create("/api/v1/products/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public ProductSummary update(@PathVariable UUID id, @RequestBody UpdateProductRequest request) {
        return service.update(
                id,
                request == null ? null : request.sku(),
                request == null ? null : request.name(),
                request == null ? null : request.active(),
                request == null ? null : request.baseUnitId());
    }

    @GetMapping("/{id}/conversions")
    public List<ConversionResponse> listConversions(@PathVariable UUID id) {
        return service.listConversions(id).stream().map(ConversionResponse::from).toList();
    }

    @PostMapping("/{id}/conversions")
    public ResponseEntity<ConversionResponse> createConversion(
            @PathVariable UUID id, @RequestBody CreateConversionRequest request) {
        ProductUnitConversion created = service.createConversion(
                id,
                request == null ? null : request.alternateUnitId(),
                request == null ? null : request.conversionFactor());
        return ResponseEntity.created(
                        URI.create("/api/v1/products/" + id + "/conversions/" + created.alternateUnitId()))
                .body(ConversionResponse.from(created));
    }

    @PutMapping("/{id}/conversions/{alternateUnitId}")
    public ConversionResponse updateConversion(
            @PathVariable UUID id,
            @PathVariable UUID alternateUnitId,
            @RequestBody UpdateConversionRequest request) {
        return ConversionResponse.from(service.updateConversion(
                id, alternateUnitId, request == null ? null : request.conversionFactor()));
    }

    @ExceptionHandler(InvalidProductException.class)
    ResponseEntity<ErrorResponse> invalid(InvalidProductException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("invalid_request", exception.getMessage()));
    }

    @ExceptionHandler(ProductNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(ProductNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("not_found", "Product was not found"));
    }

    @ExceptionHandler(ProductConversionNotFoundException.class)
    ResponseEntity<ErrorResponse> conversionNotFound(ProductConversionNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("not_found", "Product conversion was not found"));
    }

    @ExceptionHandler(ProductConflictException.class)
    ResponseEntity<ErrorResponse> conflict(ProductConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("conflict", exception.getMessage()));
    }

    public record CreateProductRequest(String sku, String name, UUID baseUnitId) {}

    public record UpdateProductRequest(String sku, String name, Boolean active, UUID baseUnitId) {}

    public record CreateConversionRequest(UUID alternateUnitId, BigDecimal conversionFactor) {}

    public record UpdateConversionRequest(BigDecimal conversionFactor) {}

    public record ConversionResponse(
            UUID productId,
            UUID alternateUnitId,
            String alternateUnitCode,
            String alternateUnitName,
            String conversionFactor) {
        static ConversionResponse from(ProductUnitConversion conversion) {
            return new ConversionResponse(
                    conversion.productId(),
                    conversion.alternateUnitId(),
                    conversion.alternateUnitCode(),
                    conversion.alternateUnitName(),
                    conversion.conversionFactor().toPlainString());
        }
    }

    public record ErrorResponse(String code, String message) {}
}
