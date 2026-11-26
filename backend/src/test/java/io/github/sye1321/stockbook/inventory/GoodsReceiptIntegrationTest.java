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
import io.github.sye1321.stockbook.inventory.InventoryService.InvalidInventoryException;
import io.github.sye1321.stockbook.inventory.InventoryService.InventoryConflictException;
import io.github.sye1321.stockbook.inventory.InventoryService.ReceiptLineRequest;
import io.github.sye1321.stockbook.locations.Location;
import io.github.sye1321.stockbook.locations.LocationService;
import io.github.sye1321.stockbook.suppliers.Supplier;
import io.github.sye1321.stockbook.suppliers.SupplierService;
import io.github.sye1321.stockbook.warehouses.WarehouseService;
import io.github.sye1321.stockbook.warehouses.WarehouseSummary;
import java.math.BigDecimal;
import java.util.List;
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
class GoodsReceiptIntegrationTest {

    private static final String ADMIN_LOGIN = "admin@example.com";
    private static final String ADMIN_PASSWORD = "correct horse battery staple";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired private InventoryService inventoryService;
    @Autowired private SupplierService supplierService;
    @Autowired private UnitOfMeasureService unitService;
    @Autowired private ProductService productService;
    @Autowired private LocationService locationService;
    @Autowired private WarehouseService warehouseService;
    @Autowired private InitialAdminProvisioningService provisioningService;
    @Autowired private UserAccountRepository userAccountRepository;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private Supplier supplier;
    private UnitOfMeasure piece;
    private UnitOfMeasure carton;
    private ProductSummary soap;
    private ProductSummary rice;
    private WarehouseSummary warehouse;
    private UUID administratorId;

    @BeforeEach
    void resetDatabase() {
        jdbcClient.sql("delete from inventory_movement").update();
        jdbcClient.sql("delete from goods_receipt_line").update();
        jdbcClient.sql("delete from goods_receipt").update();
        jdbcClient.sql("delete from inventory_transfer").update();
        jdbcClient.sql("delete from stock_balance").update();
        jdbcClient.sql("delete from product_unit_conversion").update();
        jdbcClient.sql("delete from product").update();
        jdbcClient.sql("delete from unit_of_measure").update();
        jdbcClient.sql("delete from supplier").update();
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
        supplier = supplierService.create(" acme ", "Acme Supplies", null, " SALES@ACME.TEST ");
        piece = unitService.create("PIECE", "Pieces");
        carton = unitService.create("CARTON", "Cartons");
        soap = productService.create("SOAP", "Bar Soap", piece.id());
        rice = productService.create("RICE", "Rice", piece.id());
        productService.createConversion(soap.id(), carton.id(), new BigDecimal("24.125"));
        Location location = locationService.create("NAI", "Nairobi");
        warehouse = warehouseService.create("MAIN", "Main Warehouse", location.id());
    }

    @Test
    void supplierDataIsNormalizedAndReceiptPostsMultipleExactLinesIdempotently() {
        assertThat(supplier.code()).isEqualTo("ACME");
        assertThat(supplier.email()).isEqualTo("sales@acme.test");
        UUID requestId = UUID.randomUUID();
        List<ReceiptLineRequest> lines = List.of(
                new ReceiptLineRequest(rice.id(), new BigDecimal("3.25"), piece.id()),
                new ReceiptLineRequest(
                        soap.id(), new BigDecimal("2.5000000000000000001"), carton.id()));
        InventoryService.ReceiptResult first = receive(requestId, " INV-100 ", lines);
        InventoryService.ReceiptResult retry = receive(
                requestId,
                "INV-100",
                List.of(
                        new ReceiptLineRequest(
                                soap.id(), new BigDecimal("2.50000000000000000010"), carton.id()),
                        new ReceiptLineRequest(rice.id(), new BigDecimal("3.250"), piece.id())));

        assertThat(first.created()).isTrue();
        assertThat(retry.created()).isFalse();
        assertThat(retry.receipt().id()).isEqualTo(first.receipt().id());
        assertThat(first.receipt().supplierDocumentReference()).isEqualTo("INV-100");
        assertThat(balance(soap.id())).isEqualByComparingTo("60.3125000000000000024125");
        assertThat(balance(rice.id())).isEqualByComparingTo("3.25");
        assertThat(jdbcClient
                        .sql("""
                                select count(*) from inventory_movement m
                                join goods_receipt_line l on l.id = m.receipt_line_id
                                where l.receipt_id = :receiptId and m.movement_type = 'RECEIPT_IN'
                                """)
                        .param("receiptId", first.receipt().id())
                        .query(Long.class)
                        .single())
                .isEqualTo(2);

        assertThatThrownBy(() -> receive(
                        requestId,
                        "CHANGED",
                        lines))
                .isInstanceOf(InventoryConflictException.class);
    }

    @Test
    void invalidOrInactiveReferencesRollBackTheWholeReceipt() {
        assertThatThrownBy(() -> receive(
                        UUID.randomUUID(),
                        null,
                        List.of(
                                new ReceiptLineRequest(soap.id(), BigDecimal.ONE, piece.id()),
                                new ReceiptLineRequest(soap.id(), BigDecimal.TEN, carton.id()))))
                .isInstanceOf(InvalidInventoryException.class)
                .hasMessageContaining("only once");
        assertThatThrownBy(() -> receive(
                        UUID.randomUUID(),
                        null,
                        List.of(
                                new ReceiptLineRequest(soap.id(), BigDecimal.ONE, piece.id()),
                                new ReceiptLineRequest(rice.id(), BigDecimal.ONE, UUID.randomUUID()))))
                .isInstanceOf(InvalidInventoryException.class);
        supplier = supplierService.update(
                supplier.id(), supplier.code(), supplier.name(), null, supplier.email(), false);
        assertThatThrownBy(() -> receive(
                        UUID.randomUUID(),
                        null,
                        List.of(new ReceiptLineRequest(soap.id(), BigDecimal.ONE, piece.id()))))
                .isInstanceOf(InvalidInventoryException.class)
                .hasMessageContaining("active");

        assertThat(receiptCount()).isZero();
        assertThat(jdbcClient.sql("select count(*) from stock_balance").query(Long.class).single())
                .isZero();
        assertThat(jdbcClient.sql("select count(*) from inventory_movement").query(Long.class).single())
                .isZero();
    }

    @Test
    void concurrentRetriesAddStockOnlyOnce() throws Exception {
        UUID requestId = UUID.randomUUID();
        List<ReceiptLineRequest> lines =
                List.of(new ReceiptLineRequest(soap.id(), new BigDecimal("5"), piece.id()));
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<InventoryService.ReceiptResult> first = executor.submit(() -> {
                start.await();
                return receive(requestId, "CONCURRENT", lines);
            });
            Future<InventoryService.ReceiptResult> second = executor.submit(() -> {
                start.await();
                return receive(requestId, "CONCURRENT", lines);
            });
            start.countDown();
            assertThat(first.get().created() ^ second.get().created()).isTrue();
        }

        assertThat(receiptCount()).isEqualTo(1);
        assertThat(balance(soap.id())).isEqualByComparingTo("5");
    }

    @Test
    void receiptAndSupplierWritesRequireAdministratorRole() throws Exception {
        mockMvc.perform(get("/api/v1/suppliers")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/suppliers")
                        .with(user("staff@example.com").roles("STAFF")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/suppliers")
                        .with(user("staff@example.com").roles("STAFF"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                new SupplierRequest("SECOND", "Second", null, null))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/inventory/receipts")
                        .with(user("staff@example.com").roles("STAFF"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new ReceiptRequest(
                                UUID.randomUUID(),
                                supplier.id(),
                                warehouse.id(),
                                null,
                                List.of(new ReceiptLine(soap.id(), BigDecimal.ONE, piece.id()))))))
                .andExpect(status().isForbidden());
    }

    @Test
    void receiptMovementCannotBePostedManually() {
        assertThatThrownBy(() -> inventoryService.post(
                        UUID.randomUUID(),
                        warehouse.id(),
                        soap.id(),
                        MovementType.RECEIPT_IN,
                        BigDecimal.ONE,
                        piece.id(),
                        "Bypass",
                        administratorId))
                .isInstanceOf(InvalidInventoryException.class)
                .hasMessageContaining("receipts endpoint");
    }

    private InventoryService.ReceiptResult receive(
            UUID requestId, String reference, List<ReceiptLineRequest> lines) {
        return inventoryService.receive(
                requestId,
                supplier.id(),
                warehouse.id(),
                reference,
                lines,
                administratorId);
    }

    private BigDecimal balance(UUID productId) {
        return jdbcClient
                .sql("""
                        select base_quantity from stock_balance
                        where warehouse_id = :warehouseId and product_id = :productId
                        """)
                .param("warehouseId", warehouse.id())
                .param("productId", productId)
                .query(BigDecimal.class)
                .single();
    }

    private long receiptCount() {
        return jdbcClient.sql("select count(*) from goods_receipt").query(Long.class).single();
    }

    private record SupplierRequest(String code, String name, String phone, String email) {}

    private record ReceiptRequest(
            UUID requestId,
            UUID supplierId,
            UUID warehouseId,
            String supplierDocumentReference,
            List<ReceiptLine> lines) {}

    private record ReceiptLine(UUID productId, BigDecimal quantity, UUID unitId) {}
}
