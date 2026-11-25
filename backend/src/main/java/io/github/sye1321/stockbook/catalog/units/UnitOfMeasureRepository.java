package io.github.sye1321.stockbook.catalog.units;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UnitOfMeasureRepository {

    private static final String SELECT_COLUMNS = "select id, code, name from unit_of_measure";

    private final JdbcClient jdbcClient;

    public UnitOfMeasureRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<UnitOfMeasure> findAll() {
        return jdbcClient
                .sql(SELECT_COLUMNS + " order by code")
                .query(UnitOfMeasureRepository::map)
                .list();
    }

    public Optional<UnitOfMeasure> findById(UUID id) {
        return jdbcClient
                .sql(SELECT_COLUMNS + " where id = :id")
                .param("id", id)
                .query(UnitOfMeasureRepository::map)
                .optional();
    }

    public void insert(UnitOfMeasure unit) {
        jdbcClient
                .sql("insert into unit_of_measure (id, code, name) values (:id, :code, :name)")
                .param("id", unit.id())
                .param("code", unit.code())
                .param("name", unit.name())
                .update();
    }

    public void update(UUID id, String code, String name) {
        int count = jdbcClient
                .sql("update unit_of_measure set code = :code, name = :name where id = :id")
                .param("id", id)
                .param("code", code)
                .param("name", name)
                .update();
        if (count != 1) throw new IllegalStateException("Unit of measure was not updated");
    }

    private static UnitOfMeasure map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new UnitOfMeasure(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("code"),
                resultSet.getString("name"));
    }
}
