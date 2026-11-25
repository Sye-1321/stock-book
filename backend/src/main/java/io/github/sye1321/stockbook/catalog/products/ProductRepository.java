package io.github.sye1321.stockbook.catalog.products;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProductRepository {

    private static final String SELECT_COLUMNS = """
            select id, sku, name, base_unit_id, active, created_at, updated_at
            from product
            """;
    private static final String SELECT_SUMMARY_COLUMNS = """
            select p.id, p.sku, p.name, p.base_unit_id, u.code as base_unit_code,
                   u.name as base_unit_name, p.active, p.created_at, p.updated_at
            from product p
            join unit_of_measure u on u.id = p.base_unit_id
            """;
    private static final String SELECT_CONVERSION_COLUMNS = """
            select c.product_id, c.alternate_unit_id, u.code as alternate_unit_code,
                   u.name as alternate_unit_name, c.conversion_factor
            from product_unit_conversion c
            join unit_of_measure u on u.id = c.alternate_unit_id
            """;

    private final JdbcClient jdbcClient;

    public ProductRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<ProductSummary> findAllSummaries() {
        return jdbcClient
                .sql(SELECT_SUMMARY_COLUMNS + " order by p.sku")
                .query(ProductRepository::mapSummary)
                .list();
    }

    public Optional<Product> findById(UUID id) {
        return jdbcClient
                .sql(SELECT_COLUMNS + " where id = :id")
                .param("id", id)
                .query(ProductRepository::map)
                .optional();
    }

    public Optional<ProductSummary> findSummaryById(UUID id) {
        return jdbcClient
                .sql(SELECT_SUMMARY_COLUMNS + " where p.id = :id")
                .param("id", id)
                .query(ProductRepository::mapSummary)
                .optional();
    }

    public void insert(Product product) {
        jdbcClient
                .sql("""
                        insert into product (id, sku, name, base_unit_id, active, created_at, updated_at)
                        values (:id, :sku, :name, :baseUnitId, :active, :createdAt, :updatedAt)
                        """)
                .param("id", product.id())
                .param("sku", product.sku())
                .param("name", product.name())
                .param("baseUnitId", product.baseUnitId())
                .param("active", product.active())
                .param("createdAt", timestamp(product.createdAt()))
                .param("updatedAt", timestamp(product.updatedAt()))
                .update();
    }

    public void update(UUID id, String sku, String name, boolean active, Instant updatedAt) {
        int count = jdbcClient
                .sql("""
                        update product
                        set sku = :sku, name = :name, active = :active, updated_at = :updatedAt
                        where id = :id
                        """)
                .param("id", id)
                .param("sku", sku)
                .param("name", name)
                .param("active", active)
                .param("updatedAt", timestamp(updatedAt))
                .update();
        if (count != 1) throw new IllegalStateException("Product was not updated");
    }

    public List<ProductUnitConversion> findConversions(UUID productId) {
        return jdbcClient
                .sql(SELECT_CONVERSION_COLUMNS
                        + " where c.product_id = :productId order by u.code")
                .param("productId", productId)
                .query(ProductRepository::mapConversion)
                .list();
    }

    public Optional<ProductUnitConversion> findConversion(UUID productId, UUID alternateUnitId) {
        return jdbcClient
                .sql(SELECT_CONVERSION_COLUMNS
                        + " where c.product_id = :productId and c.alternate_unit_id = :alternateUnitId")
                .param("productId", productId)
                .param("alternateUnitId", alternateUnitId)
                .query(ProductRepository::mapConversion)
                .optional();
    }

    public void insertConversion(UUID productId, UUID alternateUnitId, BigDecimal factor) {
        jdbcClient
                .sql("""
                        insert into product_unit_conversion (product_id, alternate_unit_id, conversion_factor)
                        values (:productId, :alternateUnitId, :factor)
                        """)
                .param("productId", productId)
                .param("alternateUnitId", alternateUnitId)
                .param("factor", factor)
                .update();
    }

    public void updateConversion(UUID productId, UUID alternateUnitId, BigDecimal factor) {
        int count = jdbcClient
                .sql("""
                        update product_unit_conversion set conversion_factor = :factor
                        where product_id = :productId and alternate_unit_id = :alternateUnitId
                        """)
                .param("productId", productId)
                .param("alternateUnitId", alternateUnitId)
                .param("factor", factor)
                .update();
        if (count != 1) throw new ProductConversionNotFoundException();
    }

    private static Product map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Product(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("sku"),
                resultSet.getString("name"),
                resultSet.getObject("base_unit_id", UUID.class),
                resultSet.getBoolean("active"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static ProductSummary mapSummary(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ProductSummary(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("sku"),
                resultSet.getString("name"),
                resultSet.getObject("base_unit_id", UUID.class),
                resultSet.getString("base_unit_code"),
                resultSet.getString("base_unit_name"),
                resultSet.getBoolean("active"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static ProductUnitConversion mapConversion(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ProductUnitConversion(
                resultSet.getObject("product_id", UUID.class),
                resultSet.getObject("alternate_unit_id", UUID.class),
                resultSet.getString("alternate_unit_code"),
                resultSet.getString("alternate_unit_name"),
                resultSet.getBigDecimal("conversion_factor"));
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public static final class ProductConversionNotFoundException extends RuntimeException {}
}
