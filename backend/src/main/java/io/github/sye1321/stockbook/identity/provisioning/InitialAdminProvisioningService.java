package io.github.sye1321.stockbook.identity.provisioning;

import io.github.sye1321.stockbook.identity.LoginIdentifier;
import io.github.sye1321.stockbook.identity.AccountRole;
import io.github.sye1321.stockbook.identity.UserAccount;
import io.github.sye1321.stockbook.identity.UserAccountRepository;
import java.nio.CharBuffer;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InitialAdminProvisioningService {

    private static final long PROVISIONING_LOCK_KEY = 7_341_326_001L;

    private final JdbcClient jdbcClient;
    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public InitialAdminProvisioningService(
            JdbcClient jdbcClient,
            UserAccountRepository userAccountRepository,
            PasswordEncoder passwordEncoder,
            Clock clock) {
        this.jdbcClient = jdbcClient;
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional
    public UUID provision(String loginIdentifier, String displayName, char[] password) {
        String normalizedLogin = LoginIdentifier.normalize(loginIdentifier);
        String normalizedDisplayName = displayName == null ? "" : displayName.trim();
        validate(normalizedLogin, normalizedDisplayName, password);

        jdbcClient
                .sql("select pg_advisory_xact_lock(:lockKey)")
                .param("lockKey", PROVISIONING_LOCK_KEY)
                .query()
                .singleRow();

        if (userAccountRepository.count() != 0) {
            throw new InitialAdministratorAlreadyExistsException();
        }

        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        String passwordHash = passwordEncoder.encode(CharBuffer.wrap(password));
        userAccountRepository.insert(new UserAccount(
                id,
                normalizedLogin,
                normalizedDisplayName,
                passwordHash,
                true,
                AccountRole.ADMIN,
                0,
                now,
                now,
                now));
        return id;
    }

    private static void validate(String loginIdentifier, String displayName, char[] password) {
        if (loginIdentifier.isBlank() || loginIdentifier.length() > 190) {
            throw new IllegalArgumentException("Login identifier is required and must not exceed 190 characters");
        }
        if (displayName.isBlank() || displayName.length() > 200) {
            throw new IllegalArgumentException("Display name is required and must not exceed 200 characters");
        }
        if (password == null || password.length < 12 || password.length > 128) {
            throw new IllegalArgumentException("Password must contain between 12 and 128 characters");
        }
    }

    public static final class InitialAdministratorAlreadyExistsException extends RuntimeException {
        public InitialAdministratorAlreadyExistsException() {
            super("The initial administrator has already been provisioned");
        }
    }
}
