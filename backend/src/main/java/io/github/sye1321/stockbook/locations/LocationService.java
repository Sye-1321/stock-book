package io.github.sye1321.stockbook.locations;

import io.github.sye1321.stockbook.warehouses.WarehouseRepository;
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
public class LocationService {

    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Z0-9][A-Z0-9_-]*");

    private final LocationRepository locationRepository;
    private final WarehouseRepository warehouseRepository;
    private final Clock clock;

    public LocationService(
            LocationRepository locationRepository, WarehouseRepository warehouseRepository, Clock clock) {
        this.locationRepository = locationRepository;
        this.warehouseRepository = warehouseRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Location> list() {
        return locationRepository.findAll();
    }

    @Transactional
    public Location create(String code, String name) {
        String normalizedCode = normalizeCode(code);
        String normalizedName = normalizeName(name);
        Instant now = clock.instant();
        Location location = new Location(UUID.randomUUID(), normalizedCode, normalizedName, true, now, now);
        try {
            locationRepository.insert(location);
        } catch (DuplicateKeyException exception) {
            throw new LocationConflictException("A location with that code already exists");
        }
        return location;
    }

    @Transactional
    public Location update(UUID id, String code, String name, Boolean active) {
        if (id == null || active == null) {
            throw new InvalidLocationException("Code, name and active status are required");
        }
        String normalizedCode = normalizeCode(code);
        String normalizedName = normalizeName(name);
        Location existing = locationRepository.findByIdForUpdate(id).orElseThrow(LocationNotFoundException::new);
        if (existing.active() && !active && warehouseRepository.countActiveByLocation(id) > 0) {
            throw new LocationConflictException("A location with active warehouses cannot be deactivated");
        }
        try {
            locationRepository.update(id, normalizedCode, normalizedName, active, clock.instant());
        } catch (DuplicateKeyException exception) {
            throw new LocationConflictException("A location with that code already exists");
        }
        return locationRepository.findById(id).orElseThrow(LocationNotFoundException::new);
    }

    public static String normalizeCode(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() > 50 || !CODE_PATTERN.matcher(normalized).matches()) {
            throw new InvalidLocationException(
                    "Code is required, must not exceed 50 characters and may contain letters, numbers, _ or -");
        }
        return normalized;
    }

    public static String normalizeName(String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isBlank() || normalized.length() > 200) {
            throw new InvalidLocationException("Name is required and must not exceed 200 characters");
        }
        return normalized;
    }

    public static class InvalidLocationException extends RuntimeException {
        public InvalidLocationException(String message) {
            super(message);
        }
    }

    public static final class LocationNotFoundException extends RuntimeException {}

    public static class LocationConflictException extends RuntimeException {
        public LocationConflictException(String message) {
            super(message);
        }
    }
}
