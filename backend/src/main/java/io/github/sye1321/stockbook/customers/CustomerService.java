package io.github.sye1321.stockbook.customers;

import io.github.sye1321.stockbook.locations.LocationService;
import io.github.sye1321.stockbook.locations.LocationService.InvalidLocationException;
import io.github.sye1321.stockbook.parties.Party;
import io.github.sye1321.stockbook.parties.PartyService;
import io.github.sye1321.stockbook.parties.PartyService.InvalidPartyException;
import io.github.sye1321.stockbook.parties.PartyService.PartyNotFoundException;
import io.github.sye1321.stockbook.parties.PartyService.PartyConflictException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerService {

    private final CustomerRepository repository;
    private final PartyService partyService;
    private final Clock clock;

    public CustomerService(CustomerRepository repository, PartyService partyService, Clock clock) {
        this.repository = repository;
        this.partyService = partyService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Customer> list() {
        return repository.findAll();
    }

    @Transactional
    public Customer create(
            UUID partyId,
            String code,
            String name,
            String phone,
            String email,
            Integer paymentTermsDays) {
        int terms = validatePaymentTerms(paymentTermsDays);
        Instant now = clock.instant();
        Party party;
        try {
            if (partyId == null) {
                party = partyService.create(name, phone, email);
            } else {
                if (hasText(name) || hasText(phone) || hasText(email)) {
                    throw new InvalidCustomerException(
                            "Shared identity fields must not be supplied when linking an existing party");
                }
                party = partyService.requireForUpdate(partyId);
                if (party.customer()) {
                    throw new CustomerConflictException("That party already has a customer profile");
                }
            }
        } catch (InvalidPartyException exception) {
            throw new InvalidCustomerException(exception.getMessage());
        } catch (PartyNotFoundException exception) {
            throw new InvalidCustomerException("Party was not found");
        }

        Customer customer = new Customer(
                UUID.randomUUID(),
                party.id(),
                normalizeCode(code),
                party.name(),
                party.phone(),
                party.email(),
                terms,
                true,
                party.active(),
                party.updatedAt(),
                now,
                now);
        try {
            repository.insert(customer);
        } catch (DuplicateKeyException exception) {
            throw new CustomerConflictException("A customer with that code or party already exists");
        }
        return repository.findById(customer.id()).orElseThrow(CustomerNotFoundException::new);
    }

    @Transactional
    public Customer update(
            UUID id,
            String code,
            String name,
            String phone,
            String email,
            Integer paymentTermsDays,
            Boolean active,
            Boolean partyActive) {
        return update(
                id,
                code,
                name,
                phone,
                email,
                paymentTermsDays,
                active,
                partyActive,
                null,
                null);
    }

    @Transactional
    public Customer update(
            UUID id,
            String code,
            String name,
            String phone,
            String email,
            Integer paymentTermsDays,
            Boolean active,
            Boolean partyActive,
            Boolean updateParty,
            Instant partyUpdatedAt) {
        if (id == null || active == null) {
            throw new InvalidCustomerException("Customer active status is required");
        }
        Customer customer = repository.findByIdForUpdate(id).orElseThrow(CustomerNotFoundException::new);
        int terms = validatePaymentTerms(paymentTermsDays);
        try {
            Instant now = clock.instant();
            partyService.updateIfRequested(
                    new Party(
                            customer.partyId(),
                            customer.name(),
                            customer.phone(),
                            customer.email(),
                            customer.partyActive(),
                            false,
                            true,
                            customer.partyUpdatedAt(),
                            customer.partyUpdatedAt()),
                    name,
                    phone,
                    email,
                    partyActive,
                    updateParty,
                    partyUpdatedAt,
                    now);
            repository.update(id, normalizeCode(code), terms, active, now);
        } catch (DuplicateKeyException exception) {
            throw new CustomerConflictException("A customer with that code already exists");
        } catch (PartyConflictException exception) {
            throw new CustomerConflictException(exception.getMessage());
        } catch (InvalidPartyException exception) {
            throw new InvalidCustomerException(exception.getMessage());
        }
        return repository.findById(id).orElseThrow(CustomerNotFoundException::new);
    }

    private static String normalizeCode(String code) {
        try {
            return LocationService.normalizeCode(code);
        } catch (InvalidLocationException exception) {
            throw new InvalidCustomerException(exception.getMessage());
        }
    }

    private static int validatePaymentTerms(Integer paymentTermsDays) {
        if (paymentTermsDays == null || paymentTermsDays < 0 || paymentTermsDays > 3650) {
            throw new InvalidCustomerException("Payment terms must be between 0 and 3650 days");
        }
        return paymentTermsDays;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public static class InvalidCustomerException extends RuntimeException {
        public InvalidCustomerException(String message) {
            super(message);
        }
    }

    public static final class CustomerNotFoundException extends RuntimeException {}

    public static class CustomerConflictException extends RuntimeException {
        public CustomerConflictException(String message) {
            super(message);
        }
    }
}
