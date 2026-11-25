package io.github.sye1321.stockbook.locations;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sye1321.stockbook.identity.AccountRole;
import io.github.sye1321.stockbook.identity.provisioning.InitialAdminProvisioningService;
import io.github.sye1321.stockbook.identity.staff.StaffAccountService;
import io.github.sye1321.stockbook.warehouses.WarehouseService;
import io.github.sye1321.stockbook.warehouses.WarehouseSummary;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
class LocationWarehouseIntegrationTest {

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
    private LocationService locationService;

    @Autowired
    private WarehouseService warehouseService;

    @BeforeEach
    void resetDatabase() {
        jdbcClient.sql("delete from warehouse").update();
        jdbcClient.sql("delete from business_location").update();
        jdbcClient.sql("delete from spring_session").update();
        jdbcClient.sql("delete from auth_login_attempt").update();
        jdbcClient.sql("delete from app_user").update();
        provisioningService.provision(ADMIN_LOGIN, "Initial Administrator", ADMIN_PASSWORD.toCharArray());
    }

    @Test
    void normalizedCodesReferencesUniquenessAndDeactivationRulesAreEnforced() throws Exception {
        BrowserSession administrator = login(ADMIN_LOGIN, ADMIN_PASSWORD);
        MvcResult locationResult = mockMvc.perform(post("/api/v1/locations")
                        .cookie(administrator.cookie())
                        .header(administrator.headerName(), administrator.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateLocation("  nai_main  ", " Nairobi Main "))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("NAI_MAIN"))
                .andExpect(jsonPath("$.name").value("Nairobi Main"))
                .andReturn();
        UUID locationId = UUID.fromString(read(locationResult).get("id").stringValue());

        mockMvc.perform(post("/api/v1/locations")
                        .cookie(administrator.cookie())
                        .header(administrator.headerName(), administrator.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateLocation("NAI_MAIN", "Duplicate"))))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/warehouses")
                        .cookie(administrator.cookie())
                        .header(administrator.headerName(), administrator.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateWarehouse("UNKNOWN", "Unknown", UUID.randomUUID()))))
                .andExpect(status().isBadRequest());

        MvcResult warehouseResult = mockMvc.perform(post("/api/v1/warehouses")
                        .cookie(administrator.cookie())
                        .header(administrator.headerName(), administrator.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateWarehouse(" central-1 ", " Central Warehouse ", locationId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CENTRAL-1"))
                .andExpect(jsonPath("$.locationId").value(locationId.toString()))
                .andReturn();
        UUID warehouseId = UUID.fromString(read(warehouseResult).get("id").stringValue());

        mockMvc.perform(post("/api/v1/warehouses")
                        .cookie(administrator.cookie())
                        .header(administrator.headerName(), administrator.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateWarehouse("CENTRAL-1", "Duplicate", locationId))))
                .andExpect(status().isConflict());

        updateLocation(administrator, locationId, "NAI_MAIN", "Nairobi Main", false)
                .andExpect(status().isConflict());
        updateWarehouse(administrator, warehouseId, "CENTRAL-1", "Central Warehouse", false)
                .andExpect(status().isOk());
        updateLocation(administrator, locationId, "NAI_MAIN", "Nairobi Main", false)
                .andExpect(status().isOk());
        updateWarehouse(administrator, warehouseId, "CENTRAL-1", "Central Warehouse", true)
                .andExpect(status().isBadRequest());
    }

    @Test
    void authenticatedUsersCanReadButOnlyAdministratorsCanWrite() throws Exception {
        Location location = locationService.create("NAI", "Nairobi");
        WarehouseSummary warehouse = warehouseService.create("MAIN", "Main Warehouse", location.id());
        staffAccountService.create("staff@example.com", "Warehouse Staff", STAFF_PASSWORD, AccountRole.STAFF);

        mockMvc.perform(get("/api/v1/locations")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/warehouses")).andExpect(status().isUnauthorized());

        BrowserSession staff = login("staff@example.com", STAFF_PASSWORD);
        mockMvc.perform(get("/api/v1/locations").cookie(staff.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("NAI"));
        mockMvc.perform(get("/api/v1/warehouses").cookie(staff.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("MAIN"));

        mockMvc.perform(post("/api/v1/locations")
                        .cookie(staff.cookie())
                        .header(staff.headerName(), staff.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateLocation("MSA", "Mombasa"))))
                .andExpect(status().isForbidden());
        updateLocation(staff, location.id(), "NAI", "Changed", true).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/warehouses")
                        .cookie(staff.cookie())
                        .header(staff.headerName(), staff.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateWarehouse("SECOND", "Second", location.id()))))
                .andExpect(status().isForbidden());
        updateWarehouse(staff, warehouse.id(), "MAIN", "Changed", true).andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.ResultActions updateLocation(
            BrowserSession session, UUID id, String code, String name, boolean active) throws Exception {
        return mockMvc.perform(put("/api/v1/locations/{id}", id)
                .cookie(session.cookie())
                .header(session.headerName(), session.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new UpdateEntity(code, name, active))));
    }

    private org.springframework.test.web.servlet.ResultActions updateWarehouse(
            BrowserSession session, UUID id, String code, String name, boolean active) throws Exception {
        return mockMvc.perform(put("/api/v1/warehouses/{id}", id)
                .cookie(session.cookie())
                .header(session.headerName(), session.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new UpdateEntity(code, name, active))));
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

    private record CreateLocation(String code, String name) {}

    private record CreateWarehouse(String code, String name, UUID locationId) {}

    private record UpdateEntity(String code, String name, boolean active) {}

    private record BrowserSession(Cookie cookie, String headerName, String token) {}
}
