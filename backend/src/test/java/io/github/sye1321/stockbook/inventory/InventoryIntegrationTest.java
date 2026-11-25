package io.github.sye1321.stockbook.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sye1321.stockbook.catalog.products.ProductService;
import io.github.sye1321.stockbook.catalog.products.ProductSummary;
import io.github.sye1321.stockbook.catalog.units.UnitOfMeasure;
import io.github.sye1321.stockbook.catalog.units.UnitOfMeasureService;
import io.github.sye1321.stockbook.identity.UserAccountRepository;
import io.github.sye1321.stockbook.identity.provisioning.InitialAdminProvisioningService;
import io.github.sye1321.stockbook.inventory.InventoryService.InventoryConflictException;
import io.github.sye1321.stockbook.inventory.InventoryService.InvalidInventoryException;
import io.github.sye1321.stockbook.locations.Location;
import io.github.sye1321.stockbook.locations.LocationService;
import io.github.sye1321.stockbook.warehouses.WarehouseService;
import io.github.sye1321.stockbook.warehouses.WarehouseService.WarehouseConflictException;
import io.github.sye1321.stockbook.warehouses.WarehouseSummary;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class InventoryIntegrationTest {

    private static final String ADMIN_LOGIN = "admin@example.com";
    private static final String ADMIN_PASSWORD = "correct horse battery staple";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired private InventoryService inventoryService;
    @Autowired private UnitOfMeasureService unitService;
    @Autowired private ProductService productService;
    @Autowired private LocationService locationService;
    @Autowired private WarehouseService warehouseService;
    @Autowired private InitialAdminProvisioningService provisioningService;
    @Autowired private UserAccountRepository userAccountRepository;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private UnitOfMeasure piece;
    private UnitOfMeasure carton;
    private ProductSummary product;
    private WarehouseSummary warehouse;
    private UUID administratorId;

    @BeforeEach
    void resetDatabase() {
        jdbcClient.sql("delete from inventory_movement").update();
        jdbcClient.sql("delete from inventory_transfer").update();
        jdbcClient.sql("delete from stock_balance").update();
        jdbcClient.sql("delete from product_unit_conversion").update();
        jdbcClient.sql("delete from product").update();
        jdbcClient.sql("delete from unit_of_measure").update();
        jdbcClient.sql("delete from warehouse").update();
        jdbcClient.sql("delete from business_location").update();
        jdbcClient.sql("delete from spring_session").update();
        jdbcClient.sql("delete from auth_login_attempt").update();
        jdbcClient.sql("delete from app_user").update();

        provisioningService.provision(ADMIN_LOGIN, "Initial Administrator", ADMIN_PASSWORD.toCharArray());
        administratorId = userAccountRepository
                .findByLoginIdentifier(ADMIN_LOGIN)
                .orElseThrow()
                .id();
        piece = unitService.create("PIECE", "Pieces");
        carton = unitService.create("CARTON", "Cartons");
        product = productService.create("SOAP", "Bar Soap", piece.id());
        productService.createConversion(product.id(), carton.id(), new BigDecimal("24.125"));
        Location location = locationService.create("NAI", "Nairobi");
        warehouse = warehouseService.create("MAIN", "Main Warehouse", location.id());
    }

    @Test
    void snapshotsExactConversionsAndMakesIdenticalRequestRetriesIdempotent() {
        UUID requestId = UUID.randomUUID();
        InventoryService.PostResult first = postMovement(
                requestId, MovementType.OPENING, "2.5000000000000000001", carton.id(), null);
        InventoryService.PostResult retry = postMovement(
                requestId, MovementType.OPENING, "2.50000000000000000010", carton.id(), null);

        assertThat(first.created()).isTrue();
        assertThat(retry.created()).isFalse();
        assertThat(retry.movement().id()).isEqualTo(first.movement().id());
        assertThat(first.movement().conversionFactor()).isEqualByComparingTo("24.125");
        assertThat(first.movement().baseQuantity())
                .isEqualByComparingTo("60.3125000000000000024125");
        assertThat(balance()).isEqualByComparingTo("60.3125000000000000024125");
        assertThat(movementCount()).isEqualTo(1);

        assertThatThrownBy(() -> postMovement(
                        requestId, MovementType.OPENING, "3", carton.id(), null))
                .isInstanceOf(InventoryConflictException.class);
        assertThat(balance()).isEqualByComparingTo("60.3125000000000000024125");
        assertThat(movementCount()).isEqualTo(1);
    }

    @Test
    void openingIsRejectedAfterAnyMovementAndInsufficientStockRollsBackAtomically() {
        postMovement(UUID.randomUUID(), MovementType.ADJUSTMENT_IN, "10", piece.id(), "Count correction");

        assertThatThrownBy(() -> postMovement(
                        UUID.randomUUID(), MovementType.OPENING, "1", piece.id(), null))
                .isInstanceOf(InventoryConflictException.class);
        assertThatThrownBy(() -> postMovement(
                        UUID.randomUUID(), MovementType.ADJUSTMENT_OUT, "11", piece.id(), "Damage"))
                .isInstanceOf(InventoryConflictException.class)
                .hasMessageContaining("Insufficient stock");

        assertThat(balance()).isEqualByComparingTo("10");
        assertThat(movementCount()).isEqualTo(1);
    }

    @Test
    void concurrentDeductionsAreSerializedAndCannotMakeStockNegative() throws Exception {
        postMovement(UUID.randomUUID(), MovementType.OPENING, "10", piece.id(), null);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> deductAfter(start));
            Future<Boolean> second = executor.submit(() -> deductAfter(start));
            start.countDown();
            assertThat(first.get() ^ second.get()).isTrue();
        }

        assertThat(balance()).isEqualByComparingTo("3");
        assertThat(movementCount()).isEqualTo(2);
    }

    @Test
    void warehouseWithStockCannotBeDeactivatedButZeroBalanceCan() {
        postMovement(UUID.randomUUID(), MovementType.OPENING, "5", piece.id(), null);
        assertThatThrownBy(() -> warehouseService.update(
                        warehouse.id(), warehouse.code(), warehouse.name(), false))
                .isInstanceOf(WarehouseConflictException.class)
                .hasMessageContaining("nonzero stock");

        postMovement(UUID.randomUUID(), MovementType.ADJUSTMENT_OUT, "5", piece.id(), "Write-off");
        assertThat(warehouseService.update(
                                warehouse.id(), warehouse.code(), warehouse.name(), false)
                        .active())
                .isFalse();
    }

    @Test
    void concurrentFirstPostingAndWarehouseDeactivationPreserveTheInvariant() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> posting = executor.submit(() -> {
                start.await();
                try {
                    postMovement(UUID.randomUUID(), MovementType.OPENING, "5", piece.id(), null);
                    return true;
                } catch (InvalidInventoryException exception) {
                    return false;
                }
            });
            Future<Boolean> deactivation = executor.submit(() -> {
                start.await();
                try {
                    warehouseService.update(warehouse.id(), warehouse.code(), warehouse.name(), false);
                    return true;
                } catch (WarehouseConflictException exception) {
                    return false;
                }
            });
            start.countDown();
            assertThat(posting.get() ^ deactivation.get()).isTrue();
        }

        boolean active = jdbcClient
                .sql("select active from warehouse where id = :id")
                .param("id", warehouse.id())
                .query(Boolean.class)
                .single();
        long nonzeroBalances = jdbcClient
                .sql("""
                        select count(*) from stock_balance
                        where warehouse_id = :warehouseId and base_quantity <> 0
                        """)
                .param("warehouseId", warehouse.id())
                .query(Long.class)
                .single();
        assertThat(active || nonzeroBalances == 0).isTrue();
    }

    @Test
    void inventoryReadsRequireAuthenticationAndPostingRequiresAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/balances")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/inventory/movements")).andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/inventory/balances")
                        .with(user("staff@example.com").roles("STAFF")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/inventory/movements")
                        .with(user("staff@example.com").roles("STAFF"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new PostRequest(
                                UUID.randomUUID(),
                                warehouse.id(),
                                product.id(),
                                MovementType.OPENING,
                                new BigDecimal("1"),
                                piece.id(),
                                null))))
                .andExpect(status().isForbidden());
    }

    private boolean deductAfter(CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            postMovement(UUID.randomUUID(), MovementType.ADJUSTMENT_OUT, "7", piece.id(), "Concurrent sale");
            return true;
        } catch (InventoryConflictException exception) {
            return false;
        }
    }

    private InventoryService.PostResult postMovement(
            UUID requestId, MovementType type, String quantity, UUID unitId, String reason) {
        return inventoryService.post(
                requestId,
                warehouse.id(),
                product.id(),
                type,
                new BigDecimal(quantity),
                unitId,
                reason,
                administratorId);
    }

    private BigDecimal balance() {
        return jdbcClient
                .sql("""
                        select base_quantity from stock_balance
                        where warehouse_id = :warehouseId and product_id = :productId
                        """)
                .param("warehouseId", warehouse.id())
                .param("productId", product.id())
                .query(BigDecimal.class)
                .single();
    }

    private long movementCount() {
        return jdbcClient
                .sql("select count(*) from inventory_movement")
                .query(Long.class)
                .single();
    }

    private record PostRequest(
            UUID requestId,
            UUID warehouseId,
            UUID productId,
            MovementType type,
            BigDecimal quantity,
            UUID unitId,
            String reason) {}
}
