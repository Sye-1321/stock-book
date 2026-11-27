package io.github.sye1321.stockbook.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sye1321.stockbook.catalog.products.ProductService;
import io.github.sye1321.stockbook.catalog.products.ProductSummary;
import io.github.sye1321.stockbook.catalog.units.UnitOfMeasure;
import io.github.sye1321.stockbook.catalog.units.UnitOfMeasureService;
import io.github.sye1321.stockbook.identity.UserAccountRepository;
import io.github.sye1321.stockbook.identity.provisioning.InitialAdminProvisioningService;
import io.github.sye1321.stockbook.inventory.InventoryService.InvalidInventoryException;
import io.github.sye1321.stockbook.inventory.InventoryService.InventoryConflictException;
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
class InventoryTransferIntegrationTest {

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
    private WarehouseSummary firstWarehouse;
    private WarehouseSummary secondWarehouse;
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
        productService.createConversion(product.id(), carton.id(), new BigDecimal("24"));
        Location location = locationService.create("NAI", "Nairobi");
        firstWarehouse = warehouseService.create("FIRST", "First Warehouse", location.id());
        secondWarehouse = warehouseService.create("SECOND", "Second Warehouse", location.id());
    }

    @Test
    void transferIsExactLinkedAtomicAndIdempotent() {
        opening(firstWarehouse.id(), "100");
        UUID requestId = UUID.randomUUID();
        InventoryService.TransferResult first = transfer(
                requestId,
                firstWarehouse.id(),
                secondWarehouse.id(),
                "2.5",
                carton.id(),
                "Rebalance stock");
        InventoryService.TransferResult retry = transfer(
                requestId,
                firstWarehouse.id(),
                secondWarehouse.id(),
                "2.50",
                carton.id(),
                " Rebalance stock ");

        assertThat(first.created()).isTrue();
        assertThat(retry.created()).isFalse();
        assertThat(retry.transfer().id()).isEqualTo(first.transfer().id());
        assertThat(first.transfer().conversionFactor()).isEqualByComparingTo("24");
        assertThat(first.transfer().baseQuantity())
                .isEqualByComparingTo("60");
        assertThat(balance(firstWarehouse.id()))
                .isEqualByComparingTo("40");
        assertThat(balance(secondWarehouse.id()))
                .isEqualByComparingTo("60");
        assertThat(jdbcClient
                        .sql("select count(*) from inventory_movement where transfer_id = :id")
                        .param("id", first.transfer().id())
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
        assertThat(jdbcClient
                        .sql("""
                                select base_quantity from inventory_movement
                                where transfer_id = :id and movement_type = 'TRANSFER_OUT'
                                """)
                        .param("id", first.transfer().id())
                        .query(BigDecimal.class)
                        .single())
                .isEqualByComparingTo("-60");

        assertThatThrownBy(() -> transfer(
                        requestId,
                        firstWarehouse.id(),
                        secondWarehouse.id(),
                        "1",
                        carton.id(),
                        "Rebalance stock"))
                .isInstanceOf(InventoryConflictException.class);
    }

    @Test
    void invalidAndInsufficientTransfersLeaveBothBalancesUnchanged() {
        opening(firstWarehouse.id(), "5");
        assertThatThrownBy(() -> transfer(
                        UUID.randomUUID(),
                        firstWarehouse.id(),
                        firstWarehouse.id(),
                        "1",
                        piece.id(),
                        "Invalid"))
                .isInstanceOf(InvalidInventoryException.class);
        assertThatThrownBy(() -> transfer(
                        UUID.randomUUID(),
                        firstWarehouse.id(),
                        secondWarehouse.id(),
                        "1",
                        piece.id(),
                        " "))
                .isInstanceOf(InvalidInventoryException.class);
        assertThatThrownBy(() -> transfer(
                        UUID.randomUUID(),
                        firstWarehouse.id(),
                        secondWarehouse.id(),
                        "0",
                        piece.id(),
                        "Invalid quantity"))
                .isInstanceOf(InvalidInventoryException.class);
        assertThatThrownBy(() -> transfer(
                        UUID.randomUUID(),
                        firstWarehouse.id(),
                        secondWarehouse.id(),
                        "1",
                        UUID.randomUUID(),
                        "Invalid unit"))
                .isInstanceOf(InvalidInventoryException.class);
        assertThatThrownBy(() -> transfer(
                        UUID.randomUUID(),
                        firstWarehouse.id(),
                        secondWarehouse.id(),
                        "6",
                        piece.id(),
                        "Too much"))
                .isInstanceOf(InventoryConflictException.class)
                .hasMessageContaining("Insufficient stock");

        assertThat(balance(firstWarehouse.id())).isEqualByComparingTo("5");
        assertThat(balanceCount(secondWarehouse.id())).isZero();
        assertThat(transferCount()).isZero();
        assertThatThrownBy(() -> inventoryService.post(
                        UUID.randomUUID(),
                        firstWarehouse.id(),
                        product.id(),
                        MovementType.TRANSFER_OUT,
                        BigDecimal.ONE,
                        piece.id(),
                        "Bypass",
                        administratorId))
                .isInstanceOf(InvalidInventoryException.class)
                .hasMessageContaining("transfers endpoint");
    }

    @Test
    void oppositeDirectionTransfersUseDeterministicLocksWithoutDeadlock() throws Exception {
        opening(firstWarehouse.id(), "10");
        opening(secondWarehouse.id(), "10");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> forward = executor.submit(() -> {
                await(start);
                transfer(
                        UUID.randomUUID(),
                        firstWarehouse.id(),
                        secondWarehouse.id(),
                        "7",
                        piece.id(),
                        "Forward");
            });
            Future<?> reverse = executor.submit(() -> {
                await(start);
                transfer(
                        UUID.randomUUID(),
                        secondWarehouse.id(),
                        firstWarehouse.id(),
                        "7",
                        piece.id(),
                        "Reverse");
            });
            start.countDown();
            forward.get();
            reverse.get();
        }

        assertThat(balance(firstWarehouse.id())).isEqualByComparingTo("10");
        assertThat(balance(secondWarehouse.id())).isEqualByComparingTo("10");
        assertThat(transferCount()).isEqualTo(2);
    }

    @Test
    void destinationDeactivationRacingWithTransferCannotLeaveStockInactive() throws Exception {
        opening(firstWarehouse.id(), "10");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> posting = executor.submit(() -> {
                await(start);
                try {
                    transfer(
                            UUID.randomUUID(),
                            firstWarehouse.id(),
                            secondWarehouse.id(),
                            "5",
                            piece.id(),
                            "Race");
                    return true;
                } catch (InvalidInventoryException exception) {
                    return false;
                }
            });
            Future<Boolean> deactivation = executor.submit(() -> {
                await(start);
                try {
                    warehouseService.update(
                            secondWarehouse.id(),
                            secondWarehouse.code(),
                            secondWarehouse.name(),
                            false);
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
                .param("id", secondWarehouse.id())
                .query(Boolean.class)
                .single();
        assertThat(active || balanceCount(secondWarehouse.id()) == 0).isTrue();
    }

    @Test
    void transferPostingRequiresAdministratorRole() throws Exception {
        byte[] body = objectMapper.writeValueAsBytes(new TransferRequest(
                UUID.randomUUID(),
                firstWarehouse.id(),
                secondWarehouse.id(),
                product.id(),
                BigDecimal.ONE,
                piece.id(),
                "Authorization"));
        mockMvc.perform(post("/api/v1/inventory/transfers")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/inventory/transfers")
                        .with(user("staff@example.com").roles("STAFF"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    private void opening(UUID warehouseId, String quantity) {
        inventoryService.post(
                UUID.randomUUID(),
                warehouseId,
                product.id(),
                MovementType.OPENING,
                new BigDecimal(quantity),
                piece.id(),
                null,
                administratorId);
    }

    private InventoryService.TransferResult transfer(
            UUID requestId,
            UUID sourceWarehouseId,
            UUID destinationWarehouseId,
            String quantity,
            UUID unitId,
            String reason) {
        return inventoryService.transfer(
                requestId,
                sourceWarehouseId,
                destinationWarehouseId,
                product.id(),
                new BigDecimal(quantity),
                unitId,
                reason,
                administratorId);
    }

    private BigDecimal balance(UUID warehouseId) {
        return jdbcClient
                .sql("""
                        select base_quantity from stock_balance
                        where warehouse_id = :warehouseId and product_id = :productId
                        """)
                .param("warehouseId", warehouseId)
                .param("productId", product.id())
                .query(BigDecimal.class)
                .single();
    }

    private long balanceCount(UUID warehouseId) {
        return jdbcClient
                .sql("select count(*) from stock_balance where warehouse_id = :warehouseId")
                .param("warehouseId", warehouseId)
                .query(Long.class)
                .single();
    }

    private long transferCount() {
        return jdbcClient.sql("select count(*) from inventory_transfer").query(Long.class).single();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private record TransferRequest(
            UUID requestId,
            UUID sourceWarehouseId,
            UUID destinationWarehouseId,
            UUID productId,
            BigDecimal quantity,
            UUID unitId,
            String reason) {}
}
