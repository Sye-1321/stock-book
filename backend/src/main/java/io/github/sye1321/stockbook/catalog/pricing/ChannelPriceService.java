package io.github.sye1321.stockbook.catalog.pricing;

import io.github.sye1321.stockbook.catalog.products.Product;
import io.github.sye1321.stockbook.catalog.products.ProductRepository;
import io.github.sye1321.stockbook.catalog.products.ProductUnitConversion;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChannelPriceService {
    private final ChannelPriceRepository repository;
    private final ProductRepository productRepository;
    private final Clock clock;

    public ChannelPriceService(ChannelPriceRepository repository, ProductRepository productRepository, Clock clock) {
        this.repository = repository;
        this.productRepository = productRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ChannelPrice> history(UUID productId) {
        requireProduct(productId);
        return repository.findHistory(productId);
    }

    @Transactional(readOnly = true)
    public Optional<ChannelPrice> current(UUID productId, UUID unitId, SalesChannel channel) {
        Product product = requireProduct(productId);
        if (unitId == null || channel == null) {
            throw new InvalidPriceException("Unit and channel are required");
        }
        validatePackaging(product, unitId);
        return repository.findCurrent(productId, unitId, channel);
    }

    @Transactional
    public ChannelPrice setCurrent(
            UUID productId,
            UUID unitId,
            SalesChannel channel,
            BigDecimal amount,
            Instant expectedCurrentValidFrom,
            UUID actorId) {
        if (unitId == null || channel == null || amount == null || actorId == null) {
            throw new InvalidPriceException("Unit, channel, amount and actor are required");
        }
        int decimalPlaces = Math.max(amount.scale(), 0);
        int integerDigits = Math.max(amount.precision() - amount.scale(), 0);
        if (amount.signum() < 0 || decimalPlaces > 8 || integerDigits > 16) {
            throw new InvalidPriceException("Price must be nonnegative with at most eight decimal places");
        }
        repository.lockProduct(productId);
        Product product = requireProduct(productId);
        BigDecimal conversionFactor = validatePackaging(product, unitId);
        var current = repository.findCurrentForUpdate(productId, unitId, channel);
        if (current.isPresent()) {
            if (expectedCurrentValidFrom == null
                    || !current.get().validFrom().equals(expectedCurrentValidFrom)) {
                throw new PriceConflictException("The current price was changed by another user");
            }
        } else if (expectedCurrentValidFrom != null) {
            throw new PriceConflictException("The current price was changed by another user");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (current.isPresent() && !now.isAfter(current.get().validFrom())) {
            now = current.get().validFrom().plus(1, ChronoUnit.MICROS);
        }
        ChannelPrice price = new ChannelPrice(
                UUID.randomUUID(), productId, unitId, "", channel, amount, conversionFactor,
                now, null, actorId);
        repository.insert(price, expectedCurrentValidFrom);
        return repository.findHistory(productId).stream()
                .filter(value -> value.id().equals(price.id())).findFirst().orElseThrow();
    }

    private Product requireProduct(UUID id) {
        if (id == null) throw new InvalidPriceException("Product is required");
        return productRepository.findById(id).orElseThrow(PriceNotFoundException::new);
    }

    private BigDecimal validatePackaging(Product product, UUID unitId) {
        if (product.baseUnitId().equals(unitId)) return BigDecimal.ONE;
        ProductUnitConversion conversion = productRepository.findConversion(product.id(), unitId)
                .orElseThrow(() -> new InvalidPriceException("Price unit is not configured for this product"));
        if (!conversion.active() || !conversion.packagingCompatible()) {
            throw new InvalidPriceException("Price unit must be an active integer packaging configuration");
        }
        return conversion.conversionFactor();
    }

    public static class InvalidPriceException extends RuntimeException {
        public InvalidPriceException(String message) { super(message); }
    }
    public static class PriceConflictException extends RuntimeException {
        public PriceConflictException(String message) { super(message); }
    }
    public static final class PriceNotFoundException extends RuntimeException {}
}
