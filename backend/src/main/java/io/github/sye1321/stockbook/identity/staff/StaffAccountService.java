package io.github.sye1321.stockbook.identity.staff;

import io.github.sye1321.stockbook.identity.AccountRole;
import io.github.sye1321.stockbook.identity.LoginIdentifier;
import io.github.sye1321.stockbook.identity.SessionRevocationService;
import io.github.sye1321.stockbook.identity.UserAccount;
import io.github.sye1321.stockbook.identity.UserAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class StaffAccountService {

    private static final long STAFF_ADMINISTRATION_LOCK_KEY = 7_341_326_002L;

    private final JdbcClient jdbcClient;
    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final SessionRevocationService sessionRevocationService;
    private final Clock clock;

    public StaffAccountService(
            JdbcClient jdbcClient,
            UserAccountRepository userAccountRepository,
            PasswordEncoder passwordEncoder,
            SessionRevocationService sessionRevocationService,
            Clock clock) {
        this.jdbcClient = jdbcClient;
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.sessionRevocationService = sessionRevocationService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<UserAccount> list() {
        return userAccountRepository.findAll();
    }

    @Transactional
    public UserAccount create(String loginIdentifier, String displayName, String password, AccountRole role) {
        String normalizedLogin = LoginIdentifier.normalize(loginIdentifier);
        String normalizedDisplayName = normalizeDisplayName(displayName);
        validateLoginIdentifier(normalizedLogin);
        validatePassword(password);
        if (role == null) {
            throw new InvalidStaffAccountException("Role is required");
        }

        lockAdministration();
        Instant now = clock.instant();
        UserAccount account = new UserAccount(
                UUID.randomUUID(),
                normalizedLogin,
                normalizedDisplayName,
                passwordEncoder.encode(password),
                true,
                role,
                0,
                now,
                now,
                now);
        try {
            userAccountRepository.insert(account);
        } catch (DuplicateKeyException exception) {
            throw new StaffAccountConflictException("An account with that login identifier already exists");
        }
        return account;
    }

    @Transactional
    public UserAccount update(UUID id, String displayName, AccountRole role, Boolean active) {
        if (id == null || role == null || active == null) {
            throw new InvalidStaffAccountException("Display name, role and active status are required");
        }
        String normalizedDisplayName = normalizeDisplayName(displayName);

        lockAdministration();
        UserAccount existing = userAccountRepository
                .findByIdForUpdate(id)
                .orElseThrow(StaffAccountNotFoundException::new);
        boolean removesActiveAdministrator = existing.active()
                && existing.role() == AccountRole.ADMIN
                && (!active || role != AccountRole.ADMIN);
        if (removesActiveAdministrator && userAccountRepository.countActiveAdministrators() <= 1) {
            throw new LastActiveAdministratorException();
        }

        boolean accessChanged = existing.active() != active || existing.role() != role;
        userAccountRepository.updateStaffAccount(id, normalizedDisplayName, role, active, clock.instant());
        if (accessChanged) {
            sessionRevocationService.revokeAll(existing.loginIdentifier());
        }
        return userAccountRepository.findById(id).orElseThrow(StaffAccountNotFoundException::new);
    }

    private void lockAdministration() {
        jdbcClient
                .sql("select pg_advisory_xact_lock(:lockKey)")
                .param("lockKey", STAFF_ADMINISTRATION_LOCK_KEY)
                .query()
                .singleRow();
    }

    private static String normalizeDisplayName(String displayName) {
        String normalized = displayName == null ? "" : displayName.trim();
        if (normalized.isBlank() || normalized.length() > 200) {
            throw new InvalidStaffAccountException("Display name is required and must not exceed 200 characters");
        }
        return normalized;
    }

    private static void validateLoginIdentifier(String loginIdentifier) {
        if (loginIdentifier.isBlank() || loginIdentifier.length() > 190) {
            throw new InvalidStaffAccountException(
                    "Login identifier is required and must not exceed 190 characters");
        }
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 128) {
            throw new InvalidStaffAccountException("Password must contain between 12 and 128 characters");
        }
    }

    public static class InvalidStaffAccountException extends RuntimeException {
        public InvalidStaffAccountException(String message) {
            super(message);
        }
    }

    public static final class StaffAccountNotFoundException extends RuntimeException {}

    public static class StaffAccountConflictException extends RuntimeException {
        public StaffAccountConflictException(String message) {
            super(message);
        }
    }

    public static final class LastActiveAdministratorException extends StaffAccountConflictException {
        public LastActiveAdministratorException() {
            super("The last active administrator cannot be disabled or assigned the staff role");
        }
    }
}
