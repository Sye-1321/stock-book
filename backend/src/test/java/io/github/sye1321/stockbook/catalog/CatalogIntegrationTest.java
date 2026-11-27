package io.github.sye1321.stockbook.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sye1321.stockbook.catalog.products.ProductService;
import io.github.sye1321.stockbook.catalog.products.ProductSummary;
import io.github.sye1321.stockbook.catalog.classifications.ClassificationKind;
import io.github.sye1321.stockbook.catalog.classifications.ClassificationService;
import io.github.sye1321.stockbook.catalog.classifications.ProductClassification;
import io.github.sye1321.stockbook.catalog.pricing.ChannelPriceService;
import io.github.sye1321.stockbook.catalog.pricing.SalesChannel;
import io.github.sye1321.stockbook.catalog.units.UnitOfMeasure;
import io.github.sye1321.stockbook.catalog.units.UnitOfMeasureService;
import io.github.sye1321.stockbook.identity.AccountRole;
import io.github.sye1321.stockbook.identity.provisioning.InitialAdminProvisioningService;
import io.github.sye1321.stockbook.identity.staff.StaffAccountService;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class CatalogIntegrationTest {

    private static final String ADMIN_LOGIN = "admin@example.com";
    private static final String ADMIN_PASSWORD = "correct horse battery staple";
    private static final String STAFF_PASSWORD = "staff password value";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private InitialAdminProvisioningService provisioningService;

    @Autowired
    private StaffAccountService staffAccountService;

    @Autowired
    private UnitOfMeasureService unitService;

    @Autowired
    private ProductService productService;

    @Autowired
    private ClassificationService classificationService;

    @Autowired
    private ChannelPriceService channelPriceService;

    @BeforeEach
    void resetDatabase() {
        jdbcClient.sql("delete from product_channel_price").update();
        jdbcClient.sql("delete from product_unit_conversion").update();
        jdbcClient.sql("delete from product").update();
        jdbcClient.sql("delete from product_category").update();
        jdbcClient.sql("delete from product_brand").update();
        jdbcClient.sql("delete from product_family").update();
        jdbcClient.sql("delete from unit_of_measure").update();
        jdbcClient.sql("delete from spring_session").update();
        jdbcClient.sql("delete from auth_login_attempt").update();
        jdbcClient.sql("delete from app_user").update();
        provisioningService.provision(ADMIN_LOGIN, "Initial Administrator", ADMIN_PASSWORD.toCharArray());
    }

    @Test
    void classificationsPackagingAndIndependentChannelPriceHistoryAreEnforced() {
        ProductClassification category = classificationService.create(
                ClassificationKind.CATEGORY, " soap ", "Soap");
        ProductClassification brand = classificationService.create(
                ClassificationKind.BRAND, "ACME", "Acme");
        ProductClassification family = classificationService.create(
                ClassificationKind.FAMILY, "BARS", "Bars");
        assertThatThrownBy(() -> classificationService.create(
                        ClassificationKind.CATEGORY, "SOAP", "Duplicate"))
                .isInstanceOf(ClassificationService.ClassificationConflictException.class);

        UnitOfMeasure piece = unitService.create("PIECE", "Pieces");
        UnitOfMeasure carton = unitService.create("CARTON", "Cartons");
        ProductSummary product = productService.create(
                "SOAP", "Bar Soap", piece.id(), category.id(), brand.id(), family.id());
        assertThat(product.categoryId()).isEqualTo(category.id());
        assertThat(product.brandId()).isEqualTo(brand.id());
        assertThat(product.familyId()).isEqualTo(family.id());

        assertThatThrownBy(() -> productService.createConversion(
                        product.id(), carton.id(), new BigDecimal("24.5")))
                .isInstanceOf(ProductService.InvalidProductException.class);
        var packaging = productService.createConversion(product.id(), carton.id(), new BigDecimal("24"));
        productService.updateConversion(product.id(), carton.id(), new BigDecimal("12"), true, packaging.updatedAt());
        assertThatThrownBy(() -> productService.updateConversion(
                        product.id(), carton.id(), new BigDecimal("6"), true, packaging.updatedAt()))
                .isInstanceOf(ProductService.ProductConflictException.class);

        ProductSummary reclassified = productService.update(
                product.id(), product.sku(), product.name(), true, product.baseUnitId(),
                null, brand.id(), family.id(), product.updatedAt());
        assertThat(reclassified.categoryId()).isNull();
        assertThatThrownBy(() -> productService.update(
                        product.id(), product.sku(), product.name(), true, product.baseUnitId(),
                        category.id(), brand.id(), family.id(), product.updatedAt()))
                .isInstanceOf(ProductService.ProductConflictException.class);

        UUID actorId = jdbcClient.sql("select id from app_user where login_identifier = :login")
                .param("login", ADMIN_LOGIN).query(UUID.class).single();
        var wholesale = channelPriceService.setCurrent(
                product.id(), carton.id(), SalesChannel.WHOLESALE,
                new BigDecimal("100.12345678"), null, actorId);
        channelPriceService.setCurrent(
                product.id(), carton.id(), SalesChannel.RETAIL,
                new BigDecimal("120.00"), null, actorId);
        var revisedWholesale = channelPriceService.setCurrent(
                product.id(), carton.id(), SalesChannel.WHOLESALE,
                new BigDecimal("105.50"), wholesale.validFrom(), actorId);

        var history = channelPriceService.history(product.id());
        assertThat(history).hasSize(3);
        assertThatThrownBy(() -> jdbcClient.sql(
                        "update product_channel_price set amount = 1 where id = :id")
                .param("id", wholesale.id()).update())
                .isInstanceOf(DataAccessException.class);
        assertThat(history.stream().filter(price -> price.validTo() == null
                        && price.channel() == SalesChannel.WHOLESALE)
                .findFirst().orElseThrow().amount()).isEqualByComparingTo("105.50");
        assertThat(history.stream().filter(price -> price.id().equals(wholesale.id()))
                .findFirst().orElseThrow().amount()).isEqualByComparingTo("100.12345678");
        assertThat(history.stream().filter(price -> price.id().equals(wholesale.id()))
                .findFirst().orElseThrow().conversionFactor()).isEqualByComparingTo("12");
        assertThat(history.stream().filter(price -> price.validTo() == null
                        && price.channel() == SalesChannel.RETAIL)
                .findFirst().orElseThrow().amount()).isEqualByComparingTo("120.00");
        assertThatThrownBy(() -> channelPriceService.setCurrent(
                        product.id(), carton.id(), SalesChannel.WHOLESALE,
                        new BigDecimal("110.00"), wholesale.validFrom(), actorId))
                .isInstanceOf(ChannelPriceService.PriceConflictException.class);
        assertThatThrownBy(() -> channelPriceService.setCurrent(
                        product.id(), carton.id(), SalesChannel.WHOLESALE,
                        new BigDecimal("1.123456789"), revisedWholesale.validFrom(), actorId))
                .isInstanceOf(ChannelPriceService.InvalidPriceException.class);

        var retail = history.stream().filter(price -> price.validTo() == null
                        && price.channel() == SalesChannel.RETAIL)
                .findFirst().orElseThrow();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> concurrentPriceUpdate(
                    start, product.id(), carton.id(), retail.validFrom(), actorId, "121.00"));
            var second = executor.submit(() -> concurrentPriceUpdate(
                    start, product.id(), carton.id(), retail.validFrom(), actorId, "122.00"));
            start.countDown();
            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder("updated", "conflict");
        } catch (Exception exception) {
            throw new AssertionError("Concurrent price updates did not complete", exception);
        }

        var currentPackaging = productService.listConversions(product.id()).getFirst();
        productService.updateConversion(product.id(), carton.id(), new BigDecimal("24"), true,
                currentPackaging.updatedAt());
        assertThat(channelPriceService.history(product.id()))
                .allMatch(price -> price.validTo() != null);
        assertThat(channelPriceService.current(product.id(), carton.id(), SalesChannel.WHOLESALE)).isEmpty();

        assertThatThrownBy(() -> jdbcClient.sql("""
                        select catalog_supersede_channel_price(
                            :id, :productId, :unitId, 'WHOLESALE', 130.00, 24,
                            :validFrom, null, :actorId
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("productId", product.id())
                .param("unitId", carton.id())
                .param("validFrom", java.time.OffsetDateTime.ofInstant(
                        revisedWholesale.validFrom(), java.time.ZoneOffset.UTC))
                .param("actorId", actorId)
                .query(UUID.class)
                .single())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("must not overlap");

        var packagingBeforeRace = productService.listConversions(product.id()).getFirst();
        CountDownLatch packagingRace = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var pricePost = executor.submit(() -> {
                packagingRace.await();
                return channelPriceService.setCurrent(
                        product.id(), carton.id(), SalesChannel.WHOLESALE,
                        new BigDecimal("130.00"), null, actorId);
            });
            var packagingChange = executor.submit(() -> {
                packagingRace.await();
                return productService.updateConversion(
                        product.id(), carton.id(), new BigDecimal("48"), true,
                        packagingBeforeRace.updatedAt());
            });
            packagingRace.countDown();
            pricePost.get();
            packagingChange.get();
        } catch (Exception exception) {
            throw new AssertionError("Concurrent packaging and price changes did not complete", exception);
        }
        channelPriceService.current(product.id(), carton.id(), SalesChannel.WHOLESALE)
                .ifPresent(price -> assertThat(price.conversionFactor()).isEqualByComparingTo("48"));
        assertThat(jdbcClient.sql("""
                        select count(*)
                          from product_channel_price first_price
                          join product_channel_price second_price
                            on second_price.product_id = first_price.product_id
                           and second_price.unit_id = first_price.unit_id
                           and second_price.channel = first_price.channel
                           and second_price.id > first_price.id
                           and first_price.valid_from < coalesce(second_price.valid_to, 'infinity')
                           and second_price.valid_from < coalesce(first_price.valid_to, 'infinity')
                         where first_price.product_id = :productId
                        """)
                .param("productId", product.id())
                .query(Long.class).single()).isZero();

        ProductClassification disabled = classificationService.update(
                ClassificationKind.CATEGORY, category.id(), category.code(), category.name(),
                false, category.updatedAt());
        assertThatThrownBy(() -> classificationService.update(
                        ClassificationKind.CATEGORY, category.id(), category.code(), "Stale", true,
                        category.updatedAt()))
                .isInstanceOf(ClassificationService.ClassificationConflictException.class);
        assertThatThrownBy(() -> productService.create(
                        "SECOND", "Second", piece.id(), disabled.id(), null, null))
                .isInstanceOf(ProductService.InvalidProductException.class);
    }

    private String concurrentPriceUpdate(
            CountDownLatch start,
            UUID productId,
            UUID unitId,
            java.time.Instant expected,
            UUID actorId,
            String amount)
            throws InterruptedException {
        start.await();
        try {
            channelPriceService.setCurrent(productId, unitId, SalesChannel.RETAIL,
                    new BigDecimal(amount), expected, actorId);
            return "updated";
        } catch (ChannelPriceService.PriceConflictException exception) {
            return "conflict";
        }
    }

    @Test
    void normalizedUniquenessImmutableBaseUnitsAndExactConversionsAreEnforced() throws Exception {
        BrowserSession administrator = login(ADMIN_LOGIN, ADMIN_PASSWORD);
        UUID pieceId = createUnit(administrator, " piece ", "Pieces", status().isCreated());
        UUID cartonId = createUnit(administrator, "CARTON", "Cartons", status().isCreated());
        createUnit(administrator, "PIECE", "Duplicate pieces", status().isConflict());

        mockMvc.perform(post("/api/v1/products")
                        .cookie(administrator.cookie())
                        .header(administrator.headerName(), administrator.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateProduct("INVALID", "Invalid", UUID.randomUUID()))))
                .andExpect(status().isBadRequest());

        UUID productId = createProduct(administrator, " soap-100 ", "Bar Soap", pieceId, status().isCreated());
        createProduct(administrator, "SOAP-100", "Duplicate", pieceId, status().isConflict());

        mockMvc.perform(put("/api/v1/products/{id}", productId)
                        .cookie(administrator.cookie())
                        .header(administrator.headerName(), administrator.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateProduct("SOAP-100", "Changed", true, null))))
                .andExpect(status().isBadRequest());
        ProductSummary versionedProduct = productService.list().stream()
                .filter(value -> value.id().equals(productId))
                .findFirst().orElseThrow();
        var versionedUpdate = new VersionedUpdateProduct(
                "SOAP-100", "Bar Soap", true, pieceId, null, null, null,
                versionedProduct.updatedAt());
        mockMvc.perform(put("/api/v1/products/{id}", productId)
                        .cookie(administrator.cookie())
                        .header(administrator.headerName(), administrator.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(versionedUpdate)))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/products/{id}", productId)
                        .cookie(administrator.cookie())
                        .header(administrator.headerName(), administrator.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(versionedUpdate)))
                .andExpect(status().isConflict());

        mockMvc.perform(put("/api/v1/products/{id}", productId)
                        .cookie(administrator.cookie())
                        .header(administrator.headerName(), administrator.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateProduct("SOAP-100", "Bar Soap", true, cartonId))))
                .andExpect(status().isBadRequest());

        createConversion(administrator, productId, pieceId, new BigDecimal("1"), status().isBadRequest());
        createConversion(
                administrator, productId, UUID.randomUUID(), new BigDecimal("2"), status().isBadRequest());
        createConversion(administrator, productId, cartonId, BigDecimal.ZERO, status().isBadRequest());
        createConversion(administrator, productId, cartonId, new BigDecimal("24.125"), status().isBadRequest());
        createConversion(administrator, productId, cartonId, new BigDecimal("24"), status().isCreated())
                .andExpect(jsonPath("$.conversionFactor").value("24"));
        createConversion(administrator, productId, cartonId, new BigDecimal("24"), status().isConflict());

        mockMvc.perform(put("/api/v1/products/{id}/conversions/{unitId}", productId, cartonId)
                        .cookie(administrator.cookie())
                        .header(administrator.headerName(), administrator.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateConversion(new BigDecimal("12")))))
                .andExpect(status().isBadRequest());

        String conversionVersion = mockMvc.perform(get("/api/v1/products/{id}/conversions", productId)
                        .cookie(administrator.cookie()))
                .andReturn().getResponse().getContentAsString();
        String updatedAt = objectMapper.readTree(conversionVersion).get(0).get("updatedAt").stringValue();
        mockMvc.perform(put("/api/v1/products/{id}/conversions/{unitId}", productId, cartonId)
                        .cookie(administrator.cookie())
                        .header(administrator.headerName(), administrator.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversionFactor\":12,\"active\":true,\"expectedUpdatedAt\":\""
                                + updatedAt + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversionFactor").value("12"));

        BigDecimal storedFactor = jdbcClient
                .sql("""
                        select conversion_factor from product_unit_conversion
                        where product_id = :productId and alternate_unit_id = :unitId
                        """)
                .param("productId", productId)
                .param("unitId", cartonId)
                .query(BigDecimal.class)
                .single();
        assertThat(storedFactor).isEqualByComparingTo("12");

        assertThatThrownBy(() -> jdbcClient
                        .sql("""
                                update product_unit_conversion set conversion_factor = 0
                                where product_id = :productId and alternate_unit_id = :unitId
                                """)
                        .param("productId", productId)
                        .param("unitId", cartonId)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void authenticatedUsersCanReadButOnlyAdministratorsCanWriteCatalogData() throws Exception {
        UnitOfMeasure piece = unitService.create("PIECE", "Pieces");
        UnitOfMeasure carton = unitService.create("CARTON", "Cartons");
        ProductSummary product = productService.create("SOAP", "Bar Soap", piece.id());
        productService.createConversion(product.id(), carton.id(), new BigDecimal("24"));
        staffAccountService.create("staff@example.com", "Catalog Staff", STAFF_PASSWORD, AccountRole.STAFF);

        mockMvc.perform(get("/api/v1/units")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/products")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/products/{id}/conversions", product.id()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/catalog/categories")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/products/{id}/prices", product.id()))
                .andExpect(status().isUnauthorized());

        BrowserSession staff = login("staff@example.com", STAFF_PASSWORD);
        mockMvc.perform(get("/api/v1/units").cookie(staff.cookie())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/products").cookie(staff.cookie())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/products/{id}/conversions", product.id()).cookie(staff.cookie()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/catalog/categories").cookie(staff.cookie()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/products/{id}/prices", product.id()).cookie(staff.cookie()))
                .andExpect(status().isOk());

        createUnit(staff, "BOX", "Boxes", status().isForbidden());
        updateUnit(staff, piece.id()).andExpect(status().isForbidden());
        createProduct(staff, "SECOND", "Second", piece.id(), status().isForbidden());
        updateProduct(staff, product.id()).andExpect(status().isForbidden());
        createConversion(staff, product.id(), UUID.randomUUID(), BigDecimal.ONE, status().isForbidden());
        mockMvc.perform(post("/api/v1/catalog/categories")
                        .cookie(staff.cookie()).header(staff.headerName(), staff.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/products/{id}/prices", product.id())
                        .cookie(staff.cookie()).header(staff.headerName(), staff.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/products/{id}/conversions/{unitId}", product.id(), carton.id())
                        .cookie(staff.cookie())
                        .header(staff.headerName(), staff.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateConversion(BigDecimal.TEN))))
                .andExpect(status().isForbidden());
    }

    private UUID createUnit(
            BrowserSession session,
            String code,
            String name,
            org.springframework.test.web.servlet.ResultMatcher expectedStatus)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/units")
                        .cookie(session.cookie())
                        .header(session.headerName(), session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UnitRequest(code, name))))
                .andExpect(expectedStatus)
                .andReturn();
        return result.getResponse().getStatus() == 201
                ? UUID.fromString(read(result).get("id").stringValue())
                : null;
    }

    private UUID createProduct(
            BrowserSession session,
            String sku,
            String name,
            UUID baseUnitId,
            org.springframework.test.web.servlet.ResultMatcher expectedStatus)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/products")
                        .cookie(session.cookie())
                        .header(session.headerName(), session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateProduct(sku, name, baseUnitId))))
                .andExpect(expectedStatus)
                .andReturn();
        return result.getResponse().getStatus() == 201
                ? UUID.fromString(read(result).get("id").stringValue())
                : null;
    }

    private org.springframework.test.web.servlet.ResultActions createConversion(
            BrowserSession session,
            UUID productId,
            UUID alternateUnitId,
            BigDecimal factor,
            org.springframework.test.web.servlet.ResultMatcher expectedStatus)
            throws Exception {
        return mockMvc.perform(post("/api/v1/products/{id}/conversions", productId)
                        .cookie(session.cookie())
                        .header(session.headerName(), session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateConversion(alternateUnitId, factor))))
                .andExpect(expectedStatus);
    }

    private org.springframework.test.web.servlet.ResultActions updateUnit(BrowserSession session, UUID id)
            throws Exception {
        return mockMvc.perform(put("/api/v1/units/{id}", id)
                .cookie(session.cookie())
                .header(session.headerName(), session.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new UnitRequest("PIECE", "Changed"))));
    }

    private org.springframework.test.web.servlet.ResultActions updateProduct(BrowserSession session, UUID id)
            throws Exception {
        return mockMvc.perform(put("/api/v1/products/{id}", id)
                .cookie(session.cookie())
                .header(session.headerName(), session.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new UpdateProduct("SOAP", "Changed", true, null))));
    }

    private BrowserSession login(String loginIdentifier, String password) throws Exception {
        BrowserSession anonymous = csrf(null);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(anonymous.cookie())
                        .header(anonymous.headerName(), anonymous.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new Login(loginIdentifier, password))))
                .andExpect(status().isOk())
                .andReturn();
        return csrf(requireSessionCookie(result, anonymous.cookie()));
    }

    private BrowserSession csrf(Cookie cookie) throws Exception {
        var request = get("/api/v1/auth/csrf");
        if (cookie != null) request.cookie(cookie);
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        JsonNode body = read(result);
        return new BrowserSession(
                requireSessionCookie(result, cookie),
                body.get("headerName").stringValue(),
                body.get("token").stringValue());
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private static Cookie requireSessionCookie(MvcResult result, Cookie fallback) {
        Cookie cookie = result.getResponse().getCookie("STOCKBOOK_SESSION");
        if (cookie != null) return cookie;
        if (fallback != null) return fallback;
        throw new AssertionError("Expected a session cookie");
    }

    private record Login(String loginIdentifier, String password) {}

    private record UnitRequest(String code, String name) {}

    private record CreateProduct(String sku, String name, UUID baseUnitId) {}

    private record UpdateProduct(String sku, String name, boolean active, UUID baseUnitId) {}

    private record VersionedUpdateProduct(
            String sku,
            String name,
            boolean active,
            UUID baseUnitId,
            UUID categoryId,
            UUID brandId,
            UUID familyId,
            java.time.Instant expectedUpdatedAt) {}

    private record CreateConversion(UUID alternateUnitId, BigDecimal conversionFactor) {}

    private record UpdateConversion(BigDecimal conversionFactor) {}

    private record BrowserSession(Cookie cookie, String headerName, String token) {}
}
