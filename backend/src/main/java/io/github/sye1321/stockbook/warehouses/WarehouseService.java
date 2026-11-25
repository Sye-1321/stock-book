package io.github.sye1321.stockbook.warehouses;

import io.github.sye1321.stockbook.locations.Location;
import io.github.sye1321.stockbook.locations.LocationRepository;
import io.github.sye1321.stockbook.locations.LocationService;
import io.github.sye1321.stockbook.locations.LocationService.InvalidLocationException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WarehouseService {

    private final WarehouseRepository warehouseRepository;
    private final LocationRepository locationRepository;
    private final Clock clock;

    public WarehouseService(
            WarehouseRepository warehouseRepository, LocationRepository locationRepository, Clock clock) {
        this.warehouseRepository = warehouseRepository;
        this.locationRepository = locationRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<WarehouseSummary> list() {
        return warehouseRepository.findAllSummaries();
    }

    @Transactional
    public WarehouseSummary create(String code, String name, UUID locationId) {
        if (locationId == null) throw new InvalidWarehouseException("Location is required");
        String normalizedCode = normalizeCode(code);
        String normalizedName = normalizeName(name);
        Location location = locationRepository.findByIdForUpdate(locationId).orElseThrow(InvalidLocationReferenceException::new);
        if (!location.active()) throw new InvalidLocationReferenceException();

        Instant now = clock.instant();
        Warehouse warehouse =
                new Warehouse(UUID.randomUUID(), normalizedCode, normalizedName, locationId, true, now, now);
        try {
            warehouseRepository.insert(warehouse);
        } catch (DuplicateKeyException exception) {
            throw new WarehouseConflictException("A warehouse with that code already exists");
        }
        return warehouseRepository.findSummaryById(warehouse.id()).orElseThrow(WarehouseNotFoundException::new);
    }

    @Transactional
    public WarehouseSummary update(UUID id, String code, String name, Boolean active) {
        if (id == null || active == null) {
            throw new InvalidWarehouseException("Code, name and active status are required");
        }
        String normalizedCode = normalizeCode(code);
        String normalizedName = normalizeName(name);
        Warehouse existing = warehouseRepository.findByIdForUpdate(id).orElseThrow(WarehouseNotFoundException::new);
        if (existing.active() && !active && warehouseRepository.hasNonzeroStock(id)) {
            throw new WarehouseConflictException("A warehouse with nonzero stock cannot be deactivated");
        }
        if (!existing.active() && active) {
            Location location = locationRepository
                    .findByIdForUpdate(existing.locationId())
                    .orElseThrow(InvalidLocationReferenceException::new);
            if (!location.active()) throw new InvalidLocationReferenceException();
        }
        try {
            warehouseRepository.update(id, normalizedCode, normalizedName, active, clock.instant());
        } catch (DuplicateKeyException exception) {
            throw new WarehouseConflictException("A warehouse with that code already exists");
        }
        return warehouseRepository.findSummaryById(id).orElseThrow(WarehouseNotFoundException::new);
    }

    private static String normalizeCode(String code) {
        try {
            return LocationService.normalizeCode(code);
        } catch (InvalidLocationException exception) {
            throw new InvalidWarehouseException(exception.getMessage());
        }
    }

    private static String normalizeName(String name) {
        try {
            return LocationService.normalizeName(name);
        } catch (InvalidLocationException exception) {
            throw new InvalidWarehouseException(exception.getMessage());
        }
    }

    public static class InvalidWarehouseException extends RuntimeException {
        public InvalidWarehouseException(String message) {
            super(message);
        }
    }

    public static final class InvalidLocationReferenceException extends InvalidWarehouseException {
        public InvalidLocationReferenceException() {
            super("Warehouse location must exist and be active");
        }
    }

    public static final class WarehouseNotFoundException extends RuntimeException {}

    public static class WarehouseConflictException extends RuntimeException {
        public WarehouseConflictException(String message) {
            super(message);
        }
    }
}
