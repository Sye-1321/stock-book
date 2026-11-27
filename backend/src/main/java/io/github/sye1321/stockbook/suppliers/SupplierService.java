package io.github.sye1321.stockbook.suppliers;

import io.github.sye1321.stockbook.locations.LocationService;
import io.github.sye1321.stockbook.locations.LocationService.InvalidLocationException;
import io.github.sye1321.stockbook.parties.Party;
import io.github.sye1321.stockbook.parties.PartyService;
import io.github.sye1321.stockbook.parties.PartyService.InvalidPartyException;
import io.github.sye1321.stockbook.parties.PartyService.PartyConflictException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SupplierService {

    private final SupplierRepository repository;
    private final PartyService partyService;
    private final Clock clock;

    public SupplierService(SupplierRepository repository, PartyService partyService, Clock clock) {
        this.repository = repository;
        this.partyService = partyService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Supplier> list() {
        return repository.findAll();
    }

    @Transactional
    public Supplier create(String code, String name, String phone, String email) {
        Instant now = clock.instant();
        Party party;
        try {
            party = partyService.create(name, phone, email);
        } catch (InvalidPartyException exception) {
            throw new InvalidSupplierException(exception.getMessage());
        }
        Supplier supplier = new Supplier(
                UUID.randomUUID(),
                party.id(),
                normalizeCode(code),
                party.name(),
                party.phone(),
                party.email(),
                true,
                true,
                party.updatedAt(),
                now,
                now);
        try {
            repository.insert(supplier);
        } catch (DuplicateKeyException exception) {
            throw new SupplierConflictException("A supplier with that code already exists");
        }
        return repository.findById(supplier.id()).orElseThrow(SupplierNotFoundException::new);
    }

    @Transactional
    public Supplier update(
            UUID id, String code, String name, String phone, String email, Boolean active) {
        return update(id, code, name, phone, email, active, null, null, null);
    }

    @Transactional
    public Supplier update(
            UUID id,
            String code,
            String name,
            String phone,
            String email,
            Boolean active,
            Boolean partyActive,
            Boolean updateParty,
            Instant partyUpdatedAt) {
        if (id == null || active == null) {
            throw new InvalidSupplierException("Code, name and active status are required");
        }
        Supplier supplier = repository.findByIdForUpdate(id).orElseThrow(SupplierNotFoundException::new);
        try {
            Instant now = clock.instant();
            partyService.updateIfRequested(
                    new Party(
                            supplier.partyId(),
                            supplier.name(),
                            supplier.phone(),
                            supplier.email(),
                            supplier.partyActive(),
                            true,
                            false,
                            supplier.partyUpdatedAt(),
                            supplier.partyUpdatedAt()),
                    name,
                    phone,
                    email,
                    partyActive,
                    updateParty,
                    partyUpdatedAt,
                    now);
            repository.update(id, normalizeCode(code), active, now);
        } catch (DuplicateKeyException exception) {
            throw new SupplierConflictException("A supplier with that code already exists");
        } catch (PartyConflictException exception) {
            throw new SupplierConflictException(exception.getMessage());
        } catch (InvalidPartyException exception) {
            throw new InvalidSupplierException(exception.getMessage());
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
