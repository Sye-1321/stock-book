package io.github.sye1321.stockbook.identity;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class StockBookUserDetailsService implements UserDetailsService {

    private final UserAccountRepository userAccountRepository;

    public StockBookUserDetailsService(UserAccountRepository userAccountRepository) {
        this.userAccountRepository = userAccountRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userAccountRepository
                .findByLoginIdentifier(LoginIdentifier.normalize(username))
                .map(StockBookUserDetails::from)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }
}
