package io.github.sye1321.stockbook.parties;

import io.github.sye1321.stockbook.locations.LocationService;
import io.github.sye1321.stockbook.locations.LocationService.InvalidLocationException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PartyService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+$");

    private final PartyRepository repository;
    private final Clock clock;

    public PartyService(PartyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Party> lookup(String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.length() > 200) {
            throw new InvalidPartyException("Party lookup must not exceed 200 characters");
        }
        return repository.findForLookup(normalized);
    }

    public Party create(String name, String phone, String email) {
        Instant now = clock.instant();
        Party party = new Party(
                UUID.randomUUID(),
                normalizeName(name),
                normalizeOptional(phone, 100, "Phone"),
                normalizeEmail(email),
                true,
                false,
                false,
                now,
                now);
        repository.insert(party);
        return party;
    }

    public Party requireForUpdate(UUID id) {
        if (id == null) throw new InvalidPartyException("Party identifier is required");
        return repository.findByIdForUpdate(id).orElseThrow(PartyNotFoundException::new);
    }

    public void updateIfRequested(
            Party current,
            String name,
            String phone,
            String email,
            Boolean active,
            Boolean updateParty,
            Instant expectedUpdatedAt,
            Instant requestedUpdatedAt) {
        if (Boolean.FALSE.equals(updateParty)) return;

        String normalizedName = normalizeName(name);
        String normalizedPhone = normalizeOptional(phone, 100, "Phone");
        String normalizedEmail = normalizeEmail(email);
        boolean normalizedActive = active == null ? current.active() : active;
        boolean changed = !normalizedName.equals(current.name())
                || !Objects.equals(normalizedPhone, current.phone())
                || !Objects.equals(normalizedEmail, current.email())
                || normalizedActive != current.active();
        if (!changed) return;

        if (expectedUpdatedAt == null || !expectedUpdatedAt.equals(current.updatedAt())) {
            throw new PartyConflictException(
                    "Shared party information changed since it was loaded; reload and try again");
        }
        Instant minimumUpdatedAt = current.updatedAt().truncatedTo(ChronoUnit.MICROS)
                .plus(1, ChronoUnit.MICROS);
        Instant requestedMicrosecond = requestedUpdatedAt.truncatedTo(ChronoUnit.MICROS);
        Instant nextUpdatedAt = requestedMicrosecond.isAfter(minimumUpdatedAt)
                ? requestedMicrosecond
                : minimumUpdatedAt;
        if (!repository.update(
                current.id(),
                normalizedName,
                normalizedPhone,
                normalizedEmail,
                normalizedActive,
                expectedUpdatedAt,
                nextUpdatedAt)) {
            throw new PartyConflictException(
                    "Shared party information changed since it was loaded; reload and try again");
        }
    }

    public static String normalizeName(String name) {
        try {
            return LocationService.normalizeName(name);
        } catch (InvalidLocationException exception) {
            throw new InvalidPartyException(exception.getMessage());
        }
    }

    public static String normalizeEmail(String email) {
        String normalized = normalizeOptional(email, 254, "Email");
        if (normalized == null) return null;
        normalized = normalized.toLowerCase(Locale.ROOT);
        if (!EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new InvalidPartyException("Email must be a valid address");
        }
        return normalized;
    }

    public static String normalizeOptional(String value, int maxLength, String label) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > maxLength) {
            throw new InvalidPartyException(label + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    public static class InvalidPartyException extends RuntimeException {
        public InvalidPartyException(String message) {
            super(message);
        }
    }

    public static final class PartyNotFoundException extends RuntimeException {}

    public static class PartyConflictException extends RuntimeException {
        public PartyConflictException(String message) {
            super(message);
        }
    }
}
