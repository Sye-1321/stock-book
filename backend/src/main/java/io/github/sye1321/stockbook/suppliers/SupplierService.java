package io.github.sye1321.stockbook.suppliers;

import io.github.sye1321.stockbook.locations.LocationService;
import io.github.sye1321.stockbook.locations.LocationService.InvalidLocationException;
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
public class SupplierService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+$");

    private final SupplierRepository repository;
    private final Clock clock;

    public SupplierService(SupplierRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Supplier> list() {
        return repository.findAll();
    }

    @Transactional
    public Supplier create(String code, String name, String phone, String email) {
        Instant now = clock.instant();
        Supplier supplier = new Supplier(
                UUID.randomUUID(),
                normalizeCode(code),
                normalizeName(name),
                normalizeOptional(phone, 100, "Phone"),
                normalizeEmail(email),
                true,
                now,
                now);
        try {
            repository.insert(supplier);
        } catch (DuplicateKeyException exception) {
            throw new SupplierConflictException("A supplier with that code already exists");
        }
        return supplier;
    }

    @Transactional
    public Supplier update(
            UUID id, String code, String name, String phone, String email, Boolean active) {
        if (id == null || active == null) {
            throw new InvalidSupplierException("Code, name and active status are required");
        }
        repository.findByIdForUpdate(id).orElseThrow(SupplierNotFoundException::new);
        try {
            repository.update(
                    id,
                    normalizeCode(code),
                    normalizeName(name),
                    normalizeOptional(phone, 100, "Phone"),
                    normalizeEmail(email),
                    active,
                    clock.instant());
        } catch (DuplicateKeyException exception) {
            throw new SupplierConflictException("A supplier with that code already exists");
        }
        return repository.findById(id).orElseThrow(SupplierNotFoundException::new);
    }

    private static String normalizeCode(String code) {
        try {
            return LocationService.normalizeCode(code);
        } catch (InvalidLocationException exception) {
            throw new InvalidSupplierException(exception.getMessage());
        }
    }

    private static String normalizeName(String name) {
        try {
            return LocationService.normalizeName(name);
        } catch (InvalidLocationException exception) {
            throw new InvalidSupplierException(exception.getMessage());
        }
    }

    private static String normalizeEmail(String email) {
        String normalized = normalizeOptional(email, 254, "Email");
        if (normalized == null) return null;
        normalized = normalized.toLowerCase(Locale.ROOT);
        if (!EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new InvalidSupplierException("Email must be a valid address");
        }
        return normalized;
    }

    private static String normalizeOptional(String value, int maxLength, String label) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > maxLength) {
            throw new InvalidSupplierException(label + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    public static class InvalidSupplierException extends RuntimeException {
        public InvalidSupplierException(String message) {
            super(message);
        }
    }

    public static final class SupplierNotFoundException extends RuntimeException {}

    public static class SupplierConflictException extends RuntimeException {
        public SupplierConflictException(String message) {
            super(message);
        }
    }
}
