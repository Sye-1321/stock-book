package io.github.sye1321.stockbook.identity.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;

public class AuthenticatedSessionStrategy implements SessionAuthenticationStrategy {

    public static final String AUTHENTICATED_AT_SESSION_ATTRIBUTE =
            "io.github.sye1321.stockbook.AUTHENTICATED_AT";

    private final Clock clock;

    public AuthenticatedSessionStrategy(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void onAuthentication(
            Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        request.getSession().setAttribute(AUTHENTICATED_AT_SESSION_ATTRIBUTE, clock.instant());
    }
}
