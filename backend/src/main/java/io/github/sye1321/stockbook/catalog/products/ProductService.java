package io.github.sye1321.stockbook.catalog.products;

import io.github.sye1321.stockbook.catalog.products.ProductRepository.ProductConversionNotFoundException;
import io.github.sye1321.stockbook.catalog.units.UnitOfMeasureRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    private static final Pattern SKU_PATTERN = Pattern.compile("[A-Z0-9][A-Z0-9_-]*");

    private final ProductRepository productRepository;
    private final UnitOfMeasureRepository unitRepository;
    private final Clock clock;

    public ProductService(
            ProductRepository productRepository, UnitOfMeasureRepository unitRepository, Clock clock) {
        this.productRepository = productRepository;
        this.unitRepository = unitRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ProductSummary> list() {
        return productRepository.findAllSummaries();
    }

    @Transactional
    public ProductSummary create(String sku, String name, UUID baseUnitId) {
        if (baseUnitId == null || unitRepository.findById(baseUnitId).isEmpty()) {
            throw new InvalidProductException("Base unit must reference an existing unit of measure");
        }
        Instant now = clock.instant();
        Product product = new Product(
                UUID.randomUUID(), normalizeSku(sku), normalizeName(name), baseUnitId, true, now, now);
        try {
            productRepository.insert(product);
        } catch (DuplicateKeyException exception) {
            throw new ProductConflictException("A product with that SKU already exists");
        }
        return productRepository.findSummaryById(product.id()).orElseThrow(ProductNotFoundException::new);
    }

    @Transactional
    public ProductSummary update(
            UUID id, String sku, String name, Boolean active, UUID requestedBaseUnitId) {
        if (id == null || active == null) {
            throw new InvalidProductException("SKU, name and active status are required");
        }
        Product existing = productRepository.findById(id).orElseThrow(ProductNotFoundException::new);
        if (requestedBaseUnitId != null && !requestedBaseUnitId.equals(existing.baseUnitId())) {
            throw new InvalidProductException("A product's base unit cannot be changed");
        }
        try {
            productRepository.update(id, normalizeSku(sku), normalizeName(name), active, clock.instant());
        } catch (DuplicateKeyException exception) {
            throw new ProductConflictException("A product with that SKU already exists");
        }
        return productRepository.findSummaryById(id).orElseThrow(ProductNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public List<ProductUnitConversion> listConversions(UUID productId) {
        requireProduct(productId);
        return productRepository.findConversions(productId);
    }

    @Transactional
    public ProductUnitConversion createConversion(
            UUID productId, UUID alternateUnitId, BigDecimal factor) {
        Product product = requireProduct(productId);
        validateConversion(product, alternateUnitId, factor);
        try {
            productRepository.insertConversion(productId, alternateUnitId, factor);
        } catch (DuplicateKeyException exception) {
            throw new ProductConflictException("That alternate unit already has a conversion for this product");
        }
        return productRepository
                .findConversion(productId, alternateUnitId)
                .orElseThrow(ProductConversionNotFoundException::new);
    }

    @Transactional
    public ProductUnitConversion updateConversion(
            UUID productId, UUID alternateUnitId, BigDecimal factor) {
        Product product = requireProduct(productId);
        validateConversion(product, alternateUnitId, factor);
        productRepository.updateConversion(productId, alternateUnitId, factor);
        return productRepository
                .findConversion(productId, alternateUnitId)
                .orElseThrow(ProductConversionNotFoundException::new);
    }

    private Product requireProduct(UUID productId) {
        if (productId == null) throw new ProductNotFoundException();
        return productRepository.findById(productId).orElseThrow(ProductNotFoundException::new);
    }

    private void validateConversion(Product product, UUID alternateUnitId, BigDecimal factor) {
        if (alternateUnitId == null || unitRepository.findById(alternateUnitId).isEmpty()) {
            throw new InvalidProductException("Alternate unit must reference an existing unit of measure");
        }
        if (alternateUnitId.equals(product.baseUnitId())) {
            throw new InvalidProductException("The base unit already has an implicit conversion factor of 1");
        }
        if (factor == null || factor.signum() <= 0) {
            throw new InvalidProductException("Conversion factor must be greater than zero");
        }
    }

    private static String normalizeSku(String sku) {
        String normalized = sku == null ? "" : sku.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() > 100 || !SKU_PATTERN.matcher(normalized).matches()) {
            throw new InvalidProductException(
                    "SKU is required, must not exceed 100 characters and may contain letters, numbers, _ or -");
        }
        return normalized;
    }

    private static String normalizeName(String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isBlank() || normalized.length() > 200) {
            throw new InvalidProductException("Name is required and must not exceed 200 characters");
        }
        return normalized;
    }

    public static class InvalidProductException extends RuntimeException {
        public InvalidProductException(String message) {
            super(message);
        }
    }

    public static final class ProductNotFoundException extends RuntimeException {}

    public static class ProductConflictException extends RuntimeException {
        public ProductConflictException(String message) {
            super(message);
        }
    }
}
