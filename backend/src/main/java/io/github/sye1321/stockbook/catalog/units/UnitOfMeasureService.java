package io.github.sye1321.stockbook.catalog.units;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UnitOfMeasureService {

    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Z0-9][A-Z0-9_-]*");

    private final UnitOfMeasureRepository repository;

    public UnitOfMeasureService(UnitOfMeasureRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<UnitOfMeasure> list() {
        return repository.findAll();
    }

    @Transactional
    public UnitOfMeasure create(String code, String name) {
        UnitOfMeasure unit = new UnitOfMeasure(UUID.randomUUID(), normalizeCode(code), normalizeName(name));
        try {
            repository.insert(unit);
        } catch (DuplicateKeyException exception) {
            throw new UnitConflictException("A unit with that code already exists");
        }
        return unit;
    }

    @Transactional
    public UnitOfMeasure update(UUID id, String code, String name) {
        if (id == null) throw new InvalidUnitException("Code and name are required");
        if (repository.findById(id).isEmpty()) throw new UnitNotFoundException();
        try {
            repository.update(id, normalizeCode(code), normalizeName(name));
        } catch (DuplicateKeyException exception) {
            throw new UnitConflictException("A unit with that code already exists");
        }
        return repository.findById(id).orElseThrow(UnitNotFoundException::new);
    }

    private static String normalizeCode(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() > 50 || !CODE_PATTERN.matcher(normalized).matches()) {
            throw new InvalidUnitException(
                    "Code is required, must not exceed 50 characters and may contain letters, numbers, _ or -");
        }
        return normalized;
    }

    private static String normalizeName(String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isBlank() || normalized.length() > 200) {
            throw new InvalidUnitException("Name is required and must not exceed 200 characters");
        }
        return normalized;
    }

    public static class InvalidUnitException extends RuntimeException {
        public InvalidUnitException(String message) {
            super(message);
        }
    }

    public static final class UnitNotFoundException extends RuntimeException {}

    public static class UnitConflictException extends RuntimeException {
        public UnitConflictException(String message) {
            super(message);
        }
    }
}
