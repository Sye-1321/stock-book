package io.github.sye1321.stockbook.identity.security;

import io.github.sye1321.stockbook.identity.StockBookUserDetails;
import io.github.sye1321.stockbook.identity.UserAccountRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class SessionValidationFilter extends OncePerRequestFilter {

    static final Duration ABSOLUTE_AUTHENTICATED_LIFETIME = Duration.ofHours(12);

    private final UserAccountRepository userAccountRepository;
    private final Clock clock;

    public SessionValidationFilter(UserAccountRepository userAccountRepository, Clock clock) {
        this.userAccountRepository = userAccountRepository;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof StockBookUserDetails principal
                && isInvalid(request.getSession(false), principal)) {
            invalidate(request);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"unauthenticated\",\"message\":\"Authentication is required\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isInvalid(HttpSession session, StockBookUserDetails principal) {
        if (session == null) {
            return true;
        }
        Object authenticatedAtValue =
                session.getAttribute(AuthenticatedSessionStrategy.AUTHENTICATED_AT_SESSION_ATTRIBUTE);
        if (!(authenticatedAtValue instanceof Instant authenticatedAt)
                || !clock.instant().isBefore(authenticatedAt.plus(ABSOLUTE_AUTHENTICATED_LIFETIME))) {
            return true;
        }

        return userAccountRepository
                .findById(principal.id())
                .map(user -> !user.active() || user.sessionVersion() != principal.sessionVersion())
                .orElse(true);
    }

    private static void invalidate(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
