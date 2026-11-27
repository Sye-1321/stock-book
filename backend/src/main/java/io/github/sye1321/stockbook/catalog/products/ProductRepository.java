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
            select id, sku, name, base_unit_id, category_id, brand_id, family_id,
                   active, created_at, updated_at
            from product
            """;
    private static final String SELECT_SUMMARY_COLUMNS = """
            select p.id, p.sku, p.name, p.base_unit_id, u.code as base_unit_code,
                   u.name as base_unit_name,
                   p.category_id, category.name as category_name,
                   p.brand_id, brand.name as brand_name,
                   p.family_id, family.name as family_name,
                   p.active, p.created_at, p.updated_at
            from product p
            join unit_of_measure u on u.id = p.base_unit_id
            left join product_category category on category.id = p.category_id
            left join product_brand brand on brand.id = p.brand_id
            left join product_family family on family.id = p.family_id
            """;
    private static final String SELECT_CONVERSION_COLUMNS = """
            select c.product_id, c.alternate_unit_id, u.code as alternate_unit_code,
                   u.name as alternate_unit_name, c.conversion_factor, c.active,
                   c.updated_at
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
                        insert into product (
                            id, sku, name, base_unit_id, category_id, brand_id, family_id,
                            active, created_at, updated_at
                        ) values (
                            :id, :sku, :name, :baseUnitId, :categoryId, :brandId, :familyId,
                            :active, :createdAt, :updatedAt
                        )
                        """)
                .param("id", product.id())
                .param("sku", product.sku())
                .param("name", product.name())
                .param("baseUnitId", product.baseUnitId())
                .param("categoryId", product.categoryId())
                .param("brandId", product.brandId())
                .param("familyId", product.familyId())
                .param("active", product.active())
                .param("createdAt", timestamp(product.createdAt()))
                .param("updatedAt", timestamp(product.updatedAt()))
                .update();
    }

    public int update(
            UUID id,
            String sku,
            String name,
            boolean active,
            UUID categoryId,
            UUID brandId,
            UUID familyId,
            Instant expectedUpdatedAt,
            Instant updatedAt) {
        int count = jdbcClient
                .sql("""
                        update product set sku = :sku, name = :name, active = :active,
                            category_id = :categoryId, brand_id = :brandId, family_id = :familyId,
                            updated_at = :updatedAt
                        where id = :id
                          and updated_at = :expectedUpdatedAt
                        """)
                .param("id", id)
                .param("sku", sku)
                .param("name", name)
                .param("active", active)
                .param("categoryId", categoryId)
                .param("brandId", brandId)
                .param("familyId", familyId)
                .param("expectedUpdatedAt", timestamp(expectedUpdatedAt))
                .param("updatedAt", timestamp(updatedAt))
                .update();
        return count;
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

    public void lockProduct(UUID productId) {
        jdbcClient.sql("select id from product where id = :id for update")
                .param("id", productId)
                .query(UUID.class)
                .single();
    }

    public Optional<ProductUnitConversion> findConversionForUpdate(UUID productId, UUID alternateUnitId) {
        return jdbcClient
                .sql(SELECT_CONVERSION_COLUMNS
                        + " where c.product_id = :productId and c.alternate_unit_id = :alternateUnitId"
                        + " for update of c")
                .param("productId", productId)
                .param("alternateUnitId", alternateUnitId)
                .query(ProductRepository::mapConversion)
                .optional();
    }

    public void insertConversion(
            UUID productId, UUID alternateUnitId, BigDecimal factor, Instant updatedAt) {
        jdbcClient
                .sql("""
                        insert into product_unit_conversion (
                            product_id, alternate_unit_id, conversion_factor, active, created_at, updated_at
                        ) values (:productId, :alternateUnitId, :factor, true, :updatedAt, :updatedAt)
                        """)
                .param("productId", productId)
                .param("alternateUnitId", alternateUnitId)
                .param("factor", factor)
                .param("updatedAt", timestamp(updatedAt))
                .update();
    }

    public int updateConversion(
            UUID productId,
            UUID alternateUnitId,
            BigDecimal factor,
            boolean active,
            Instant expectedUpdatedAt,
            Instant updatedAt) {
        int count = jdbcClient
                .sql("""
                        update product_unit_conversion
                        set conversion_factor = :factor, active = :active, updated_at = :updatedAt
                        where product_id = :productId and alternate_unit_id = :alternateUnitId
                          and updated_at = :expectedUpdatedAt
                        """)
                .param("productId", productId)
                .param("alternateUnitId", alternateUnitId)
                .param("factor", factor)
                .param("active", active)
                .param("expectedUpdatedAt", timestamp(expectedUpdatedAt))
                .param("updatedAt", timestamp(updatedAt))
                .update();
        return count;
    }

    public void closeCurrentPricesForPackagingChange(UUID productId, UUID unitId, Instant closedAt) {
        jdbcClient.sql("select catalog_close_channel_prices_for_packaging_change(:productId, :unitId, :closedAt)")
                .param("productId", productId)
                .param("unitId", unitId)
                .param("closedAt", timestamp(closedAt))
                .query((resultSet, rowNumber) -> Boolean.TRUE)
                .single();
    }

    private static Product map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Product(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("sku"),
                resultSet.getString("name"),
                resultSet.getObject("base_unit_id", UUID.class),
                resultSet.getObject("category_id", UUID.class),
                resultSet.getObject("brand_id", UUID.class),
                resultSet.getObject("family_id", UUID.class),
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
                resultSet.getObject("category_id", UUID.class),
                resultSet.getString("category_name"),
                resultSet.getObject("brand_id", UUID.class),
                resultSet.getString("brand_name"),
                resultSet.getObject("family_id", UUID.class),
                resultSet.getString("family_name"),
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
                resultSet.getBigDecimal("conversion_factor"),
                resultSet.getBoolean("active"),
                resultSet.getBigDecimal("conversion_factor").stripTrailingZeros().scale() <= 0,
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public static final class ProductConversionNotFoundException extends RuntimeException {}
}
