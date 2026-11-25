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
import io.github.sye1321.stockbook.catalog.units.UnitOfMeasure;
import io.github.sye1321.stockbook.catalog.units.UnitOfMeasureService;
import io.github.sye1321.stockbook.identity.AccountRole;
import io.github.sye1321.stockbook.identity.provisioning.InitialAdminProvisioningService;
import io.github.sye1321.stockbook.identity.staff.StaffAccountService;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
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

    @BeforeEach
    void resetDatabase() {
        jdbcClient.sql("delete from product_unit_conversion").update();
        jdbcClient.sql("delete from product").update();
        jdbcClient.sql("delete from unit_of_measure").update();
        jdbcClient.sql("delete from spring_session").update();
        jdbcClient.sql("delete from auth_login_attempt").update();
        jdbcClient.sql("delete from app_user").update();
        provisioningService.provision(ADMIN_LOGIN, "Initial Administrator", ADMIN_PASSWORD.toCharArray());
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
                        .content(json(new UpdateProduct("SOAP-100", "Bar Soap", true, cartonId))))
                .andExpect(status().isBadRequest());

        createConversion(administrator, productId, pieceId, new BigDecimal("1"), status().isBadRequest());
        createConversion(
                administrator, productId, UUID.randomUUID(), new BigDecimal("2"), status().isBadRequest());
        createConversion(administrator, productId, cartonId, BigDecimal.ZERO, status().isBadRequest());
        createConversion(administrator, productId, cartonId, new BigDecimal("24.125"), status().isCreated())
                .andExpect(jsonPath("$.conversionFactor").value("24.125"));
        createConversion(administrator, productId, cartonId, new BigDecimal("24"), status().isConflict());

        mockMvc.perform(put("/api/v1/products/{id}/conversions/{unitId}", productId, cartonId)
                        .cookie(administrator.cookie())
                        .header(administrator.headerName(), administrator.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateConversion(new BigDecimal("12.5001")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversionFactor").value("12.5001"));

        BigDecimal storedFactor = jdbcClient
                .sql("""
                        select conversion_factor from product_unit_conversion
                        where product_id = :productId and alternate_unit_id = :unitId
                        """)
                .param("productId", productId)
                .param("unitId", cartonId)
                .query(BigDecimal.class)
                .single();
        assertThat(storedFactor).isEqualByComparingTo("12.5001");

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

        BrowserSession staff = login("staff@example.com", STAFF_PASSWORD);
        mockMvc.perform(get("/api/v1/units").cookie(staff.cookie())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/products").cookie(staff.cookie())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/products/{id}/conversions", product.id()).cookie(staff.cookie()))
                .andExpect(status().isOk());

        createUnit(staff, "BOX", "Boxes", status().isForbidden());
        updateUnit(staff, piece.id()).andExpect(status().isForbidden());
        createProduct(staff, "SECOND", "Second", piece.id(), status().isForbidden());
        updateProduct(staff, product.id()).andExpect(status().isForbidden());
        createConversion(staff, product.id(), UUID.randomUUID(), BigDecimal.ONE, status().isForbidden());
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

    private record CreateConversion(UUID alternateUnitId, BigDecimal conversionFactor) {}

    private record UpdateConversion(BigDecimal conversionFactor) {}

    private record BrowserSession(Cookie cookie, String headerName, String token) {}
}
