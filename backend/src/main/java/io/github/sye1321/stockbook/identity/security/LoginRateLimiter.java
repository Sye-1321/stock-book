package io.github.sye1321.stockbook.identity.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoginRateLimiter {

    static final int IDENTIFIER_LIMIT = 8;
    static final int IP_LIMIT = 30;
    static final Duration WINDOW = Duration.ofMinutes(15);
    private static final Duration RETENTION = Duration.ofDays(1);

    private final JdbcClient jdbcClient;
    private final Clock clock;

    public LoginRateLimiter(JdbcClient jdbcClient, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
    }

    public void assertAllowed(String loginIdentifier, String remoteAddress) {
        Instant now = clock.instant();
        if (isBlocked("LOGIN", loginIdentifier, now) || isBlocked("IP", remoteAddress, now)) {
            throw new LoginRateLimitedException();
        }
    }

    @Transactional
    public boolean recordFailure(String loginIdentifier, String remoteAddress) {
        Instant now = clock.instant();
        boolean blocked = record("LOGIN", loginIdentifier, IDENTIFIER_LIMIT, now);
        blocked = record("IP", remoteAddress, IP_LIMIT, now) || blocked;
        jdbcClient
                .sql("delete from auth_login_attempt where updated_at < :cutoff")
                .param("cutoff", OffsetDateTime.ofInstant(now.minus(RETENTION), ZoneOffset.UTC))
                .update();
        return blocked;
    }

    public void recordSuccess(String loginIdentifier) {
        jdbcClient
                .sql("delete from auth_login_attempt where key_type = 'LOGIN' and key_value = :keyValue")
                .param("keyValue", loginIdentifier)
                .update();
    }

    private boolean isBlocked(String keyType, String keyValue, Instant now) {
        return jdbcClient
                .sql("""
                        select blocked_until
                        from auth_login_attempt
                        where key_type = :keyType and key_value = :keyValue
                        """)
                .param("keyType", keyType)
                .param("keyValue", keyValue)
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant)
                .filter(blockedUntil -> blockedUntil.isAfter(now))
                .isPresent();
    }

    private boolean record(String keyType, String keyValue, int limit, Instant now) {
        Instant windowStart = now.minus(WINDOW);
        Instant blockedUntil = now.plus(WINDOW);
        Boolean result = jdbcClient
                .sql("""
                        insert into auth_login_attempt (
                            key_type, key_value, window_started_at, failure_count, blocked_until, updated_at
                        ) values (
                            :keyType, :keyValue, :now, 1, null, :now
                        )
                        on conflict (key_type, key_value) do update set
                            window_started_at = case
                                when auth_login_attempt.window_started_at <= :windowStart
                                  or auth_login_attempt.blocked_until <= :now
                                then :now else auth_login_attempt.window_started_at end,
                            failure_count = case
                                when auth_login_attempt.window_started_at <= :windowStart
                                  or auth_login_attempt.blocked_until <= :now
                                then 1 else auth_login_attempt.failure_count + 1 end,
                            blocked_until = case
                                when (case
                                    when auth_login_attempt.window_started_at <= :windowStart
                                      or auth_login_attempt.blocked_until <= :now
                                    then 1 else auth_login_attempt.failure_count + 1 end) >= :attemptLimit
                                then :blockedUntil
                                else null end,
                            updated_at = :now
                        returning blocked_until is not null
                        """)
                .param("keyType", keyType)
                .param("keyValue", keyValue)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("windowStart", OffsetDateTime.ofInstant(windowStart, ZoneOffset.UTC))
                .param("attemptLimit", limit)
                .param("blockedUntil", OffsetDateTime.ofInstant(blockedUntil, ZoneOffset.UTC))
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(result);
    }

    public static final class LoginRateLimitedException extends AuthenticationException {
        public LoginRateLimitedException() {
            super("Too many authentication attempts");
        }
    }
}
