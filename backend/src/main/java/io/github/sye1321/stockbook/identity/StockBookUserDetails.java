package io.github.sye1321.stockbook.identity;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class StockBookUserDetails implements UserDetails, CredentialsContainer, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID id;
    private final String username;
    private final String displayName;
    private final boolean enabled;
    private final AccountRole role;
    private final long sessionVersion;
    private transient String password;

    private StockBookUserDetails(
            UUID id,
            String username,
            String displayName,
            String password,
            boolean enabled,
            AccountRole role,
            long sessionVersion) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
        this.password = password;
        this.enabled = enabled;
        this.role = role;
        this.sessionVersion = sessionVersion;
    }

    public static StockBookUserDetails from(UserAccount user) {
        return new StockBookUserDetails(
                user.id(),
                user.loginIdentifier(),
                user.displayName(),
                user.passwordHash(),
                user.active(),
                user.role(),
                user.sessionVersion());
    }

    public UUID id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String username() {
        return username;
    }

    public long sessionVersion() {
        return sessionVersion;
    }

    public AccountRole role() {
        // Accounts that predate role support were all initial administrators. This fallback
        // keeps an already-serialized session usable while its database row is migrated.
        return role == null ? AccountRole.ADMIN : role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role().name()));
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public void eraseCredentials() {
        password = null;
    }
}
