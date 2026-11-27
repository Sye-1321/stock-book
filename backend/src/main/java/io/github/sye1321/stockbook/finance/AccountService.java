package io.github.sye1321.stockbook.finance;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public FinanceSettings settings() {
        return accountRepository.settings();
    }

    @Transactional(readOnly = true)
    public List<FinanceAccount> list() {
        return accountRepository.findAll();
    }

    @Transactional(readOnly = true)
    public FinanceAccount require(AccountRole role) {
        return accountRepository.findByRole(role).orElseThrow(
                () -> new IllegalStateException("Required finance account is missing: " + role));
    }
}
