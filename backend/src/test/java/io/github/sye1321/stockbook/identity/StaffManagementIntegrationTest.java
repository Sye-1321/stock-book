package io.github.sye1321.stockbook.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sye1321.stockbook.identity.provisioning.InitialAdminProvisioningService;
import io.github.sye1321.stockbook.identity.staff.StaffAccountService;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class StaffManagementIntegrationTest {

    private static final String ADMIN_LOGIN = "admin@example.com";
    private static final String ADMIN_PASSWORD = "correct horse battery staple";
    private static final String STAFF_PASSWORD = "staff initial password value";

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
    private UserAccountRepository userAccountRepository;

    @BeforeEach
    void resetDatabase() {
        jdbcClient.sql("delete from spring_session").update();
        jdbcClient.sql("delete from auth_login_attempt").update();
        jdbcClient.sql("delete from app_user").update();
        provisioningService.provision(ADMIN_LOGIN, "Initial Administrator", ADMIN_PASSWORD.toCharArray());
    }

    @Test
    void provisioningRetainsAdminRoleAndStaffEndpointsEnforceAuthorization() throws Exception {
        assertThat(userAccountRepository.findByLoginIdentifier(ADMIN_LOGIN).orElseThrow().role())
                .isEqualTo(AccountRole.ADMIN);
        mockMvc.perform(get("/api/v1/staff")).andExpect(status().isUnauthorized());

        BrowserSession administrator = login(ADMIN_LOGIN, ADMIN_PASSWORD);
        mockMvc.perform(get("/api/v1/auth/me").cookie(administrator.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        String createRequest = objectMapper.writeValueAsString(
                new CreateAccount("staff@example.com", "Warehouse Staff", STAFF_PASSWORD, AccountRole.STAFF));
        mockMvc.perform(post("/api/v1/staff")
                        .cookie(administrator.cookie())
                        .header(administrator.headerName(), administrator.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.loginIdentifier").value("staff@example.com"))
                .andExpect(jsonPath("$.role").value("STAFF"))
                .andExpect(jsonPath("$.initialPassword").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        mockMvc.perform(get("/api/v1/staff").cookie(administrator.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$[1].passwordHash").doesNotExist());

        BrowserSession staff = login("staff@example.com", STAFF_PASSWORD);
        mockMvc.perform(get("/api/v1/staff").cookie(staff.cookie())).andExpect(status().isForbidden());

        BrowserSession staffWithCsrf = csrf(staff.cookie());
        mockMvc.perform(post("/api/v1/staff")
                        .cookie(staffWithCsrf.cookie())
                        .header(staffWithCsrf.headerName(), staffWithCsrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateAccount(
                                "blocked@example.com", "Blocked Account", STAFF_PASSWORD, AccountRole.STAFF))))
                .andExpect(status().isForbidden());

        UserAccount staffAccount = userAccountRepository.findByLoginIdentifier("staff@example.com").orElseThrow();
        mockMvc.perform(put("/api/v1/staff/{id}", staffAccount.id())
                        .cookie(staffWithCsrf.cookie())
                        .header(staffWithCsrf.headerName(), staffWithCsrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateAccount("Unauthorized Update", AccountRole.ADMIN, true))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/auth/password")
                        .cookie(staffWithCsrf.cookie())
                        .header(staffWithCsrf.headerName(), staffWithCsrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new Passwords(STAFF_PASSWORD, "staff replacement password"))))
                .andExpect(status().isNoContent());
    }

    @Test
    void roleAndEnabledStatusChangesRevokeExistingSessions() throws Exception {
        UserAccount staff = staffAccountService.create(
                "staff@example.com", "Warehouse Staff", STAFF_PASSWORD, AccountRole.STAFF);
        BrowserSession staffSession = login("staff@example.com", STAFF_PASSWORD);
        BrowserSession administrator = login(ADMIN_LOGIN, ADMIN_PASSWORD);

        updateAccount(administrator, staff.id(), "Warehouse Staff", AccountRole.ADMIN, true)
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/auth/me").cookie(staffSession.cookie()))
                .andExpect(status().isUnauthorized());

        BrowserSession promotedSession = login("staff@example.com", STAFF_PASSWORD);
        updateAccount(administrator, staff.id(), "Warehouse Staff", AccountRole.ADMIN, false)
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/auth/me").cookie(promotedSession.cookie()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void lastActiveAdministratorCannotBeDisabledOrDemoted() throws Exception {
        UserAccount administrator = userAccountRepository.findByLoginIdentifier(ADMIN_LOGIN).orElseThrow();
        BrowserSession session = login(ADMIN_LOGIN, ADMIN_PASSWORD);

        updateAccount(session, administrator.id(), administrator.displayName(), AccountRole.STAFF, true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("conflict"));
        updateAccount(session, administrator.id(), administrator.displayName(), AccountRole.ADMIN, false)
                .andExpect(status().isConflict());

        assertThat(userAccountRepository.findById(administrator.id()).orElseThrow().role())
                .isEqualTo(AccountRole.ADMIN);
        assertThat(userAccountRepository.findById(administrator.id()).orElseThrow().active())
                .isTrue();
    }

    @Test
    void concurrentRequestsCannotDemoteBothActiveAdministrators() throws Exception {
        UserAccount first = userAccountRepository.findByLoginIdentifier(ADMIN_LOGIN).orElseThrow();
        UserAccount second = staffAccountService.create(
                "second.admin@example.com", "Second Administrator", STAFF_PASSWORD, AccountRole.ADMIN);
        BrowserSession firstSession = login(ADMIN_LOGIN, ADMIN_PASSWORD);
        BrowserSession secondSession = login("second.admin@example.com", STAFF_PASSWORD);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> firstResult = executor.submit(() -> updateConcurrently(
                    ready, start, firstSession, first.id(), first.displayName()));
            Future<Integer> secondResult = executor.submit(() -> updateConcurrently(
                    ready, start, secondSession, second.id(), second.displayName()));
            ready.await();
            start.countDown();

            assertThat(List.of(firstResult.get(), secondResult.get()))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(userAccountRepository.countActiveAdministrators()).isOne();
    }

    private int updateConcurrently(
            CountDownLatch ready,
            CountDownLatch start,
            BrowserSession session,
            java.util.UUID id,
            String displayName)
            throws Exception {
        ready.countDown();
        start.await();
        return updateAccount(session, id, displayName, AccountRole.STAFF, true)
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private org.springframework.test.web.servlet.ResultActions updateAccount(
            BrowserSession session,
            java.util.UUID id,
            String displayName,
            AccountRole role,
            boolean active)
            throws Exception {
        return mockMvc.perform(put("/api/v1/staff/{id}", id)
                .cookie(session.cookie())
                .header(session.headerName(), session.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UpdateAccount(displayName, role, active))));
    }

    private BrowserSession login(String loginIdentifier, String password) throws Exception {
        BrowserSession anonymous = csrf(null);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(anonymous.cookie())
                        .header(anonymous.headerName(), anonymous.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new Login(loginIdentifier, password))))
                .andExpect(status().isOk())
                .andReturn();
        return csrf(requireSessionCookie(result, anonymous.cookie()));
    }

    private BrowserSession csrf(Cookie cookie) throws Exception {
        var request = get("/api/v1/auth/csrf");
        if (cookie != null) {
            request.cookie(cookie);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        return new BrowserSession(
                requireSessionCookie(result, cookie),
                json.get("headerName").stringValue(),
                json.get("token").stringValue());
    }

    private static Cookie requireSessionCookie(MvcResult result, Cookie fallback) {
        Cookie cookie = result.getResponse().getCookie("STOCKBOOK_SESSION");
        if (cookie != null) return cookie;
        if (fallback != null) return fallback;
        throw new AssertionError("Expected a session cookie");
    }

    private record Login(String loginIdentifier, String password) {}

    private record Passwords(String currentPassword, String newPassword) {}

    private record CreateAccount(
            String loginIdentifier, String displayName, String initialPassword, AccountRole role) {}

    private record UpdateAccount(String displayName, AccountRole role, boolean active) {}

    private record BrowserSession(Cookie cookie, String headerName, String token) {}
}
