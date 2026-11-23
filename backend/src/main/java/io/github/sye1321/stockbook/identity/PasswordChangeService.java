package io.github.sye1321.stockbook.identity;

import java.time.Clock;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordChangeService {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public PasswordChangeService(
            UserAccountRepository userAccountRepository, PasswordEncoder passwordEncoder, Clock clock) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional
    public String changePassword(UUID userId, String currentPassword, String newPassword) {
        if (currentPassword == null || newPassword == null) {
            throw new InvalidPasswordChangeException("Both current and new passwords are required");
        }
        if (newPassword.length() < 12 || newPassword.length() > 128) {
            throw new InvalidPasswordChangeException("New password must contain between 12 and 128 characters");
        }

        UserAccount user = userAccountRepository
                .findByIdForUpdate(userId)
                .orElseThrow(() -> new InvalidPasswordChangeException("User account is unavailable"));
        if (!passwordEncoder.matches(currentPassword, user.passwordHash())) {
            throw new InvalidCurrentPasswordException();
        }

        userAccountRepository.updatePassword(userId, passwordEncoder.encode(newPassword), clock.instant());
        return user.loginIdentifier();
    }

    public static class InvalidPasswordChangeException extends RuntimeException {
        public InvalidPasswordChangeException(String message) {
            super(message);
        }
    }

    public static final class InvalidCurrentPasswordException extends InvalidPasswordChangeException {
        public InvalidCurrentPasswordException() {
            super("Current password is incorrect");
        }
    }
}
