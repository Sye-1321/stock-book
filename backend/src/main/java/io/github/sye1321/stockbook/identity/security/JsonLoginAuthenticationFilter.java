package io.github.sye1321.stockbook.identity.security;

import io.github.sye1321.stockbook.identity.LoginIdentifier;
import io.github.sye1321.stockbook.identity.StockBookUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public class JsonLoginAuthenticationFilter extends AbstractAuthenticationProcessingFilter {

    private final ObjectMapper objectMapper;
    private final LoginRateLimiter loginRateLimiter;

    public JsonLoginAuthenticationFilter(
            AuthenticationManager authenticationManager,
            ObjectMapper objectMapper,
            LoginRateLimiter loginRateLimiter) {
        super(PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/auth/login"), authenticationManager);
        this.objectMapper = objectMapper;
        this.loginRateLimiter = loginRateLimiter;
        setAuthenticationSuccessHandler(this::writeSuccess);
        setAuthenticationFailureHandler(this::writeFailure);
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response)
            throws AuthenticationException, IOException {
        LoginRequest loginRequest;
        try {
            loginRequest = objectMapper.readValue(request.getInputStream(), LoginRequest.class);
        } catch (JacksonException exception) {
            throw new InvalidLoginRequestException();
        }

        if (loginRequest == null
                || loginRequest.loginIdentifier() == null
                || loginRequest.loginIdentifier().isBlank()
                || loginRequest.loginIdentifier().length() > 190
                || loginRequest.password() == null
                || loginRequest.password().isBlank()
                || loginRequest.password().length() > 128) {
            throw new InvalidLoginRequestException();
        }

        String loginIdentifier = LoginIdentifier.normalize(loginRequest.loginIdentifier());
        String remoteAddress = request.getRemoteAddr();
        loginRateLimiter.assertAllowed(loginIdentifier, remoteAddress);

        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.unauthenticated(loginIdentifier, loginRequest.password());
        authentication.setDetails(authenticationDetailsSource.buildDetails(request));
        try {
            Authentication result = getAuthenticationManager().authenticate(authentication);
            loginRateLimiter.recordSuccess(loginIdentifier);
            return result;
        } catch (AuthenticationException exception) {
            if (loginRateLimiter.recordFailure(loginIdentifier, remoteAddress)) {
                throw new LoginRateLimiter.LoginRateLimitedException();
            }
            throw new BadCredentialsException("Invalid credentials");
        }
    }

    private void writeSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication)
            throws IOException {
        StockBookUserDetails principal = (StockBookUserDetails) authentication.getPrincipal();
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                new AuthenticatedUserResponse(
                        principal.id().toString(),
                        principal.username(),
                        principal.displayName(),
                        principal.role().name()));
    }

    private void writeFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception)
            throws IOException {
        int status;
        String code;
        String message;
        if (exception instanceof LoginRateLimiter.LoginRateLimitedException) {
            status = 429;
            code = "too_many_attempts";
            message = "Too many authentication attempts. Try again later.";
        } else if (exception instanceof InvalidLoginRequestException) {
            status = HttpServletResponse.SC_BAD_REQUEST;
            code = "invalid_request";
            message = "Login identifier and password are required";
        } else {
            status = HttpServletResponse.SC_UNAUTHORIZED;
            code = "invalid_credentials";
            message = "Invalid login identifier or password";
        }
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new ErrorResponse(code, message));
    }

    public record LoginRequest(String loginIdentifier, String password) {}

    public record AuthenticatedUserResponse(
            String id, String loginIdentifier, String displayName, String role) {}

    public record ErrorResponse(String code, String message) {}

    private static final class InvalidLoginRequestException extends AuthenticationException {
        private InvalidLoginRequestException() {
            super("Invalid login request");
        }
    }
}
