package io.github.sye1321.stockbook.finance;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
class FinanceRuntimePrivilegeVerifier implements ApplicationRunner {

    private final JdbcClient jdbcClient;
    private final String migrationUser;

    FinanceRuntimePrivilegeVerifier(
            JdbcClient jdbcClient, @Value("${spring.flyway.user}") String migrationUser) {
        this.jdbcClient = jdbcClient;
        this.migrationUser = migrationUser;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        Boolean restricted = jdbcClient
                .sql("""
                        select current_user <> :migrationUser
                           and pg_has_role(current_user, 'stock_book_app_runtime', 'MEMBER')
                           and not pg_has_role(current_user, 'stock_book_finance_owner', 'MEMBER')
                           and not pg_has_role(current_user, 'stock_book_finance_approver', 'MEMBER')
                           and not pg_has_role(current_user, :migrationUser, 'MEMBER')
                           and not r.rolsuper and not r.rolcreatedb and not r.rolcreaterole
                          from pg_roles r where r.rolname = current_user
                        """)
                .param("migrationUser", migrationUser)
                .query(Boolean.class)
                .single();
        if (!Boolean.TRUE.equals(restricted)) {
            throw new IllegalStateException(
                    "The production runtime database identity must be restricted and separate from migration and finance-owner identities");
        }
    }
}
