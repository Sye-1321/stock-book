package io.github.sye1321.stockbook.identity;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class StockBookUserDetails implements UserDetails, CredentialsContainer, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID id;
    private final String username;
    private final String displayName;
    private final boolean enabled;
    private final long sessionVersion;
    private transient String password;

    private StockBookUserDetails(
            UUID id,
            String username,
            String displayName,
            String password,
            boolean enabled,
            long sessionVersion) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
        this.password = password;
        this.enabled = enabled;
        this.sessionVersion = sessionVersion;
    }

    public static StockBookUserDetails from(UserAccount user) {
        return new StockBookUserDetails(
                user.id(),
                user.loginIdentifier(),
                user.displayName(),
                user.passwordHash(),
                user.active(),
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

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
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
