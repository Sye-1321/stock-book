package io.github.sye1321.stockbook.system;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class DatabaseStatusChecker {

    private final JdbcClient jdbcClient;

    public DatabaseStatusChecker(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public boolean isAvailable() {
        try {
            return jdbcClient.sql("select 1").query(Integer.class).single() == 1;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
