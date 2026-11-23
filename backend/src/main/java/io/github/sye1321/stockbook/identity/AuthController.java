package io.github.sye1321.stockbook.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuthController {

    private final PasswordChangeService passwordChangeService;
    private final SessionRevocationService sessionRevocationService;

    public AuthController(
            PasswordChangeService passwordChangeService, SessionRevocationService sessionRevocationService) {
        this.passwordChangeService = passwordChangeService;
        this.sessionRevocationService = sessionRevocationService;
    }

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken csrfToken) {
        return new CsrfResponse(csrfToken.getHeaderName(), csrfToken.getParameterName(), csrfToken.getToken());
    }

    @GetMapping("/me")
    public AuthenticatedUserResponse me(@AuthenticationPrincipal StockBookUserDetails principal) {
        return AuthenticatedUserResponse.from(principal);
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal StockBookUserDetails principal,
            @RequestBody PasswordChangeRequest passwordChangeRequest,
            HttpServletRequest request) {
        String loginIdentifier = passwordChangeService.changePassword(
                principal.id(),
                passwordChangeRequest == null ? null : passwordChangeRequest.currentPassword(),
                passwordChangeRequest == null ? null : passwordChangeRequest.newPassword());

        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        sessionRevocationService.revokeAll(loginIdentifier);
        return ResponseEntity.noContent().build();
    }

    public record CsrfResponse(String headerName, String parameterName, String token) {}

    public record PasswordChangeRequest(String currentPassword, String newPassword) {}

    public record AuthenticatedUserResponse(UUID id, String loginIdentifier, String displayName) {
        static AuthenticatedUserResponse from(StockBookUserDetails principal) {
            return new AuthenticatedUserResponse(principal.id(), principal.username(), principal.displayName());
        }
    }
}
