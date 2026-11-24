package io.github.sye1321.stockbook.system;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sye1321.stockbook.identity.provisioning.InitialAdminProvisioningService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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
class HealthEndpointDatabaseOutageIntegrationTest {

    private static final String LOGIN = "health-check-admin@example.com";
    private static final String PASSWORD = "health check test password";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InitialAdminProvisioningService provisioningService;

    @Test
    void healthEndpointsRemainAvailableWithAnAuthenticatedCookieDuringDatabaseOutage() throws Exception {
        provisioningService.provision(LOGIN, "Health Check Administrator", PASSWORD.toCharArray());
        MvcResult csrfResult = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode csrf = objectMapper.readTree(csrfResult.getResponse().getContentAsByteArray());
        Cookie anonymousCookie = requireSessionCookie(csrfResult);

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(anonymousCookie)
                        .header(csrf.get("headerName").stringValue(), csrf.get("token").stringValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new Login(LOGIN, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie authenticatedCookie = requireSessionCookie(loginResult);

        postgres.stop();

        mockMvc.perform(get("/api/v1/liveness").cookie(authenticatedCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.database").value("NOT_CHECKED"));
        mockMvc.perform(get("/api/v1/status").cookie(authenticatedCookie))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.database").value("DOWN"));
    }

    private static Cookie requireSessionCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie("STOCKBOOK_SESSION");
        if (cookie == null) {
            throw new AssertionError("Expected a session cookie");
        }
        return cookie;
    }

    private record Login(String loginIdentifier, String password) {}
}
