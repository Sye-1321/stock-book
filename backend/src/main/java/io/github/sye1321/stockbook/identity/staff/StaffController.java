package io.github.sye1321.stockbook.identity.staff;

import io.github.sye1321.stockbook.identity.AccountRole;
import io.github.sye1321.stockbook.identity.UserAccount;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/staff")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class StaffController {

    private final StaffAccountService staffAccountService;

    public StaffController(StaffAccountService staffAccountService) {
        this.staffAccountService = staffAccountService;
    }

    @GetMapping
    public List<StaffAccountResponse> list() {
        return staffAccountService.list().stream().map(StaffAccountResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<StaffAccountResponse> create(@RequestBody CreateStaffAccountRequest request) {
        UserAccount created = staffAccountService.create(
                request == null ? null : request.loginIdentifier(),
                request == null ? null : request.displayName(),
                request == null ? null : request.initialPassword(),
                request == null ? null : request.role());
        return ResponseEntity.created(URI.create("/api/v1/staff/" + created.id()))
                .body(StaffAccountResponse.from(created));
    }

    @PutMapping("/{id}")
    public StaffAccountResponse update(
            @PathVariable UUID id, @RequestBody UpdateStaffAccountRequest request) {
        return StaffAccountResponse.from(staffAccountService.update(
                id,
                request == null ? null : request.displayName(),
                request == null ? null : request.role(),
                request == null ? null : request.active()));
    }

    public record CreateStaffAccountRequest(
            String loginIdentifier, String displayName, String initialPassword, AccountRole role) {}

    public record UpdateStaffAccountRequest(String displayName, AccountRole role, Boolean active) {}

    public record StaffAccountResponse(
            UUID id, String loginIdentifier, String displayName, AccountRole role, boolean active) {
        static StaffAccountResponse from(UserAccount account) {
            return new StaffAccountResponse(
                    account.id(),
                    account.loginIdentifier(),
                    account.displayName(),
                    account.role(),
                    account.active());
        }
    }
}
