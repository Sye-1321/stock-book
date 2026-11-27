package io.github.sye1321.stockbook.finance;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private final JdbcClient jdbcClient;

    public AccountRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public FinanceSettings settings() {
        return jdbcClient
                .sql("""
                        select currency_code, timezone_name
                        from business_finance_settings where singleton
                        """)
                .query((resultSet, rowNumber) -> new FinanceSettings(
                        resultSet.getString("currency_code"),
                        ZoneId.of(resultSet.getString("timezone_name"))))
                .single();
    }

    public List<FinanceAccount> findAll() {
        return jdbcClient
                .sql("""
                        select id, code, name, account_role, account_type, active, posting_allowed
                        from finance_account order by code
                        """)
                .query(AccountRepository::map)
                .list();
    }

    public Optional<FinanceAccount> findByRole(AccountRole role) {
        return jdbcClient
                .sql("""
                        select id, code, name, account_role, account_type, active, posting_allowed
                        from finance_account where account_role = :role
                        """)
                .param("role", role.name())
                .query(AccountRepository::map)
                .optional();
    }

    private static FinanceAccount map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new FinanceAccount(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("code"),
                resultSet.getString("name"),
                AccountRole.valueOf(resultSet.getString("account_role")),
                AccountType.valueOf(resultSet.getString("account_type")),
                resultSet.getBoolean("active"),
                resultSet.getBoolean("posting_allowed"));
    }
}
