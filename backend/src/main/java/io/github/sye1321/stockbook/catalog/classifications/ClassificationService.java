package io.github.sye1321.stockbook.catalog.classifications;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClassificationService {
    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Z0-9][A-Z0-9_-]*");
    private final ClassificationRepository repository;
    private final Clock clock;

    public ClassificationService(ClassificationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ProductClassification> list(ClassificationKind kind) {
        return repository.findAll(kind);
    }

    @Transactional
    public ProductClassification create(ClassificationKind kind, String code, String name) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        ProductClassification value = new ProductClassification(
                UUID.randomUUID(), normalizeCode(code), normalizeName(name), true, now, now);
        try {
            repository.insert(kind, value);
        } catch (DuplicateKeyException exception) {
            throw new ClassificationConflictException("A " + label(kind) + " with that code already exists");
        }
        return value;
    }

    @Transactional
    public ProductClassification update(
            ClassificationKind kind,
            UUID id,
            String code,
            String name,
            Boolean active,
            Instant expectedUpdatedAt) {
        if (id == null || active == null || expectedUpdatedAt == null) {
            throw new InvalidClassificationException("Code, name, active status and version are required");
        }
        ProductClassification existing = repository.findById(kind, id)
                .orElseThrow(ClassificationNotFoundException::new);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant next = now.isAfter(existing.updatedAt())
                ? now
                : existing.updatedAt().plus(1, ChronoUnit.MICROS);
        try {
            if (repository.update(kind, id, normalizeCode(code), normalizeName(name), active,
                            expectedUpdatedAt, next) != 1) {
                throw new ClassificationConflictException("The " + label(kind) + " was changed by another user");
            }
        } catch (DuplicateKeyException exception) {
            throw new ClassificationConflictException("A " + label(kind) + " with that code already exists");
        }
        return repository.findById(kind, id).orElseThrow(ClassificationNotFoundException::new);
    }

    private static String normalizeCode(String code) {
        String value = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (value.length() > 50 || !CODE_PATTERN.matcher(value).matches()) {
            throw new InvalidClassificationException(
                    "Code is required, must not exceed 50 characters and may contain letters, numbers, _ or -");
        }
        return value;
    }

    private static String normalizeName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isBlank() || value.length() > 200) {
            throw new InvalidClassificationException("Name is required and must not exceed 200 characters");
        }
        return value;
    }

    private static String label(ClassificationKind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    public static class InvalidClassificationException extends RuntimeException {
        public InvalidClassificationException(String message) { super(message); }
    }
    public static class ClassificationConflictException extends RuntimeException {
        public ClassificationConflictException(String message) { super(message); }
    }
    public static final class ClassificationNotFoundException extends RuntimeException {}
}
