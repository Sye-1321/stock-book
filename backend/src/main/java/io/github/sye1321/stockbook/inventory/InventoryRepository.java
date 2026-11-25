package io.github.sye1321.stockbook.inventory;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InventoryRepository {

    private static final String MOVEMENT_SELECT = """
            select m.id, m.request_id, m.transfer_id, m.warehouse_id, w.code as warehouse_code,
                   m.product_id, p.sku as product_sku, m.movement_type,
                   m.entered_quantity, m.entered_unit_id, u.code as entered_unit_code,
                   m.conversion_factor, m.base_quantity, m.reason, m.posted_by,
                   a.display_name as posted_by_name, m.posted_at
            from inventory_movement m
            join warehouse w on w.id = m.warehouse_id
            join product p on p.id = m.product_id
            join unit_of_measure u on u.id = m.entered_unit_id
            join app_user a on a.id = m.posted_by
            """;
    private static final String TRANSFER_SELECT = """
            select t.id, t.request_id, t.source_warehouse_id,
                   sw.code as source_warehouse_code, t.destination_warehouse_id,
                   dw.code as destination_warehouse_code, t.product_id,
                   p.sku as product_sku, t.entered_quantity, t.entered_unit_id,
                   u.code as entered_unit_code, t.conversion_factor, t.base_quantity,
                   t.reason, t.posted_by, a.display_name as posted_by_name, t.posted_at
            from inventory_transfer t
            join warehouse sw on sw.id = t.source_warehouse_id
            join warehouse dw on dw.id = t.destination_warehouse_id
            join product p on p.id = t.product_id
            join unit_of_measure u on u.id = t.entered_unit_id
            join app_user a on a.id = t.posted_by
            """;

    private final JdbcClient jdbcClient;

    public InventoryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void lockRequest(UUID requestId) {
        long lockKey = requestId.getMostSignificantBits() ^ requestId.getLeastSignificantBits();
        jdbcClient
                .sql("select pg_advisory_xact_lock(:lockKey)")
                .param("lockKey", lockKey)
                .query((resultSet, rowNumber) -> 0)
                .single();
    }

    public Optional<InventoryMovement> findMovementByRequestId(UUID requestId) {
        return jdbcClient
                .sql(MOVEMENT_SELECT + " where m.request_id = :requestId")
                .param("requestId", requestId)
                .query(InventoryRepository::mapMovement)
                .optional();
    }

    public Optional<InventoryTransfer> findTransferByRequestId(UUID requestId) {
        return jdbcClient
                .sql(TRANSFER_SELECT + " where t.request_id = :requestId")
                .param("requestId", requestId)
                .query(InventoryRepository::mapTransfer)
                .optional();
    }

    public Optional<WarehouseState> lockWarehouse(UUID warehouseId) {
        return jdbcClient
                .sql("""
                        select w.id, w.active, l.active as location_active
                        from warehouse w
                        join business_location l on l.id = w.location_id
                        where w.id = :warehouseId
                        for update of w
                        """)
                .param("warehouseId", warehouseId)
                .query((resultSet, rowNumber) -> new WarehouseState(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getBoolean("active"),
                        resultSet.getBoolean("location_active")))
                .optional();
    }

    public Optional<BigDecimal> lockBalance(UUID warehouseId, UUID productId) {
        return jdbcClient
                .sql("""
                        select base_quantity from stock_balance
                        where warehouse_id = :warehouseId and product_id = :productId
                        for update
                        """)
                .param("warehouseId", warehouseId)
                .param("productId", productId)
                .query(BigDecimal.class)
                .optional();
    }

    public boolean hasMovements(UUID warehouseId, UUID productId) {
        return jdbcClient
                        .sql("""
                                select count(*) from inventory_movement
                                where warehouse_id = :warehouseId and product_id = :productId
                                """)
                        .param("warehouseId", warehouseId)
                        .param("productId", productId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    public void insertMovement(
            UUID id,
            UUID requestId,
            UUID transferId,
            UUID warehouseId,
            UUID productId,
            MovementType type,
            BigDecimal enteredQuantity,
            UUID enteredUnitId,
            BigDecimal conversionFactor,
            BigDecimal baseQuantity,
            String reason,
            UUID postedBy,
            Instant postedAt) {
        jdbcClient
                .sql("""
                        insert into inventory_movement (
                            id, request_id, transfer_id, warehouse_id, product_id, movement_type,
                            entered_quantity, entered_unit_id, conversion_factor, base_quantity,
                            reason, posted_by, posted_at
                        ) values (
                            :id, :requestId, :transferId, :warehouseId, :productId, :type,
                            :enteredQuantity, :enteredUnitId, :conversionFactor, :baseQuantity,
                            :reason, :postedBy, :postedAt
                        )
                        """)
                .param("id", id)
                .param("requestId", requestId, Types.OTHER)
                .param("transferId", transferId, Types.OTHER)
                .param("warehouseId", warehouseId)
                .param("productId", productId)
                .param("type", type.name())
                .param("enteredQuantity", enteredQuantity)
                .param("enteredUnitId", enteredUnitId)
                .param("conversionFactor", conversionFactor)
                .param("baseQuantity", baseQuantity)
                .param("reason", reason)
                .param("postedBy", postedBy)
                .param("postedAt", timestamp(postedAt))
                .update();
    }

    public void insertTransfer(
            UUID id,
            UUID requestId,
            UUID sourceWarehouseId,
            UUID destinationWarehouseId,
            UUID productId,
            BigDecimal enteredQuantity,
            UUID enteredUnitId,
            BigDecimal conversionFactor,
            BigDecimal baseQuantity,
            String reason,
            UUID postedBy,
            Instant postedAt) {
        jdbcClient
                .sql("""
                        insert into inventory_transfer (
                            id, request_id, source_warehouse_id, destination_warehouse_id,
                            product_id, entered_quantity, entered_unit_id, conversion_factor,
                            base_quantity, reason, posted_by, posted_at
                        ) values (
                            :id, :requestId, :sourceWarehouseId, :destinationWarehouseId,
                            :productId, :enteredQuantity, :enteredUnitId, :conversionFactor,
                            :baseQuantity, :reason, :postedBy, :postedAt
                        )
                        """)
                .param("id", id)
                .param("requestId", requestId)
                .param("sourceWarehouseId", sourceWarehouseId)
                .param("destinationWarehouseId", destinationWarehouseId)
                .param("productId", productId)
                .param("enteredQuantity", enteredQuantity)
                .param("enteredUnitId", enteredUnitId)
                .param("conversionFactor", conversionFactor)
                .param("baseQuantity", baseQuantity)
                .param("reason", reason)
                .param("postedBy", postedBy)
                .param("postedAt", timestamp(postedAt))
                .update();
    }

    public void upsertBalance(
            UUID warehouseId, UUID productId, BigDecimal baseQuantity, Instant updatedAt) {
        jdbcClient
                .sql("""
                        insert into stock_balance (warehouse_id, product_id, base_quantity, updated_at)
                        values (:warehouseId, :productId, :baseQuantity, :updatedAt)
                        on conflict (warehouse_id, product_id) do update
                        set base_quantity = excluded.base_quantity, updated_at = excluded.updated_at
                        """)
                .param("warehouseId", warehouseId)
                .param("productId", productId)
                .param("baseQuantity", baseQuantity)
                .param("updatedAt", timestamp(updatedAt))
                .update();
    }

    public List<StockBalance> findBalances(UUID warehouseId, UUID productId) {
        StringBuilder sql = new StringBuilder("""
                select b.warehouse_id, w.code as warehouse_code, w.name as warehouse_name,
                       b.product_id, p.sku as product_sku, p.name as product_name,
                       p.base_unit_id, u.code as base_unit_code, b.base_quantity, b.updated_at
                from stock_balance b
                join warehouse w on w.id = b.warehouse_id
                join product p on p.id = b.product_id
                join unit_of_measure u on u.id = p.base_unit_id
                where 1 = 1
                """);
        if (warehouseId != null) sql.append(" and b.warehouse_id = :warehouseId");
        if (productId != null) sql.append(" and b.product_id = :productId");
        sql.append(" order by w.code, p.sku");
        JdbcClient.StatementSpec statement = jdbcClient.sql(sql.toString());
        if (warehouseId != null) statement = statement.param("warehouseId", warehouseId);
        if (productId != null) statement = statement.param("productId", productId);
        return statement.query(InventoryRepository::mapBalance).list();
    }

    public List<InventoryMovement> findMovements(
            UUID warehouseId, UUID productId, int offset, int limit) {
        StringBuilder sql = new StringBuilder(MOVEMENT_SELECT + " where 1 = 1");
        if (warehouseId != null) sql.append(" and m.warehouse_id = :warehouseId");
        if (productId != null) sql.append(" and m.product_id = :productId");
        sql.append(" order by m.posted_at desc, m.id desc offset :offset limit :limit");
        JdbcClient.StatementSpec statement = jdbcClient
                .sql(sql.toString())
                .param("offset", offset)
                .param("limit", limit);
        if (warehouseId != null) statement = statement.param("warehouseId", warehouseId);
        if (productId != null) statement = statement.param("productId", productId);
        return statement.query(InventoryRepository::mapMovement).list();
    }

    private static InventoryMovement mapMovement(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new InventoryMovement(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("request_id", UUID.class),
                resultSet.getObject("transfer_id", UUID.class),
                resultSet.getObject("warehouse_id", UUID.class),
                resultSet.getString("warehouse_code"),
                resultSet.getObject("product_id", UUID.class),
                resultSet.getString("product_sku"),
                MovementType.valueOf(resultSet.getString("movement_type")),
                resultSet.getBigDecimal("entered_quantity"),
                resultSet.getObject("entered_unit_id", UUID.class),
                resultSet.getString("entered_unit_code"),
                resultSet.getBigDecimal("conversion_factor"),
                resultSet.getBigDecimal("base_quantity"),
                resultSet.getString("reason"),
                resultSet.getObject("posted_by", UUID.class),
                resultSet.getString("posted_by_name"),
                resultSet.getObject("posted_at", OffsetDateTime.class).toInstant());
    }

    private static InventoryTransfer mapTransfer(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new InventoryTransfer(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("request_id", UUID.class),
                resultSet.getObject("source_warehouse_id", UUID.class),
                resultSet.getString("source_warehouse_code"),
                resultSet.getObject("destination_warehouse_id", UUID.class),
                resultSet.getString("destination_warehouse_code"),
                resultSet.getObject("product_id", UUID.class),
                resultSet.getString("product_sku"),
                resultSet.getBigDecimal("entered_quantity"),
                resultSet.getObject("entered_unit_id", UUID.class),
                resultSet.getString("entered_unit_code"),
                resultSet.getBigDecimal("conversion_factor"),
                resultSet.getBigDecimal("base_quantity"),
                resultSet.getString("reason"),
                resultSet.getObject("posted_by", UUID.class),
                resultSet.getString("posted_by_name"),
                resultSet.getObject("posted_at", OffsetDateTime.class).toInstant());
    }

    private static StockBalance mapBalance(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StockBalance(
                resultSet.getObject("warehouse_id", UUID.class),
                resultSet.getString("warehouse_code"),
                resultSet.getString("warehouse_name"),
                resultSet.getObject("product_id", UUID.class),
                resultSet.getString("product_sku"),
                resultSet.getString("product_name"),
                resultSet.getObject("base_unit_id", UUID.class),
                resultSet.getString("base_unit_code"),
                resultSet.getBigDecimal("base_quantity"),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record WarehouseState(UUID id, boolean active, boolean locationActive) {}
}
