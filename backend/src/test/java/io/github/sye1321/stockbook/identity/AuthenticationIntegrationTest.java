package io.github.sye1321.stockbook.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sye1321.stockbook.identity.provisioning.InitialAdminProvisioningService;
import io.github.sye1321.stockbook.identity.provisioning.InitialAdminProvisioningService.InitialAdministratorAlreadyExistsException;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
@Import(AuthenticationIntegrationTest.ClockConfiguration.class)
class AuthenticationIntegrationTest {

    private static final String LOGIN = "admin@example.com";
    private static final String PASSWORD = "correct horse battery staple";
    private static final Instant TEST_INSTANT = Instant.parse("2026-01-01T00:00:00Z");

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
    private UserAccountRepository userAccountRepository;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetDatabaseAndClock() {
        jdbcClient.sql("delete from spring_session").update();
        jdbcClient.sql("delete from auth_login_attempt").update();
        jdbcClient.sql("delete from app_user").update();
        clock.reset(TEST_INSTANT);
    }

    @Test
    void provisionsOnlyOneInitialAdministrator() {
        provisioningService.provision("  ADMIN@example.com ", "Initial Administrator", PASSWORD.toCharArray());

        UserAccount account = userAccountRepository.findByLoginIdentifier(LOGIN).orElseThrow();
        assertThat(account.displayName()).isEqualTo("Initial Administrator");
        assertThat(account.passwordHash()).doesNotContain(PASSWORD).startsWith("{");
        assertThat(account.active()).isTrue();

        assertThatThrownBy(() -> provisioningService.provision(
                        "second@example.com", "Second Administrator", "another secure password".toCharArray()))
                .isInstanceOf(InitialAdministratorAlreadyExistsException.class);
        assertThat(userAccountRepository.count()).isOne();
    }

    @Test
    void concurrentProvisioningCreatesExactlyOneAccount() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                int attempt = index;
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        provisioningService.provision(
                                "admin" + attempt + "@example.com",
                                "Administrator " + attempt,
                                ("secure provisioning password " + attempt).toCharArray());
                        return true;
                    } catch (InitialAdministratorAlreadyExistsException exception) {
                        return false;
                    }
                }));
            }
            ready.await();
            start.countDown();
            assertThat(attempts).extracting(AuthenticationIntegrationTest::getFuture).containsExactlyInAnyOrder(true, false);
        }
        assertThat(userAccountRepository.count()).isOne();
    }

    @Test
    void loginPersistsAcrossRequestsRequiresCsrfRotationAndLogoutInvalidatesTheSession() throws Exception {
        provisionUser();
        BrowserSession anonymous = csrf(null);

        mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(anonymous.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(LOGIN, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));

        MvcResult login = performLogin(anonymous, LOGIN, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Initial Administrator"))
                .andReturn();
        Cookie authenticatedCookie = requireSessionCookie(login, anonymous.cookie());

        mockMvc.perform(get("/api/v1/auth/me").cookie(authenticatedCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginIdentifier").value(LOGIN));

        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(authenticatedCookie)
                        .header(anonymous.headerName(), anonymous.token()))
                .andExpect(status().isForbidden());

        BrowserSession authenticated = csrf(authenticatedCookie);
        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(authenticated.cookie())
                        .header(authenticated.headerName(), authenticated.token()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/auth/me").cookie(authenticated.cookie()))
                .andExpect(status().isUnauthorized());
        assertThat(csrf(null).token()).isNotBlank();
    }

    @Test
    void invalidAndDisabledAccountsReceiveTheSameGenericLoginFailure() throws Exception {
        provisionUser();
        BrowserSession firstClient = csrf(null);
        String invalidResponse = performLogin(firstClient, LOGIN, "wrong password value")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_credentials"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        UserAccount user = userAccountRepository.findByLoginIdentifier(LOGIN).orElseThrow();
        userAccountRepository.updateActive(user.id(), false, clock.instant());
        BrowserSession secondClient = csrf(null);
        String disabledResponse = performLogin(secondClient, LOGIN, PASSWORD)
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(disabledResponse).isEqualTo(invalidResponse);
    }

    @Test
    void authenticatesWithALoginIdentifierLongerThanTheOriginalSessionColumn() throws Exception {
        String longLoginIdentifier = "administrator." + "a".repeat(100) + "@example.com";
        provisioningService.provision(
                longLoginIdentifier, "Long Identifier Administrator", PASSWORD.toCharArray());
        BrowserSession anonymous = csrf(null);

        MvcResult login = performLogin(anonymous, longLoginIdentifier, PASSWORD)
                .andExpect(status().isOk())
                .andReturn();
        Cookie authenticatedCookie = requireSessionCookie(login, anonymous.cookie());

        mockMvc.perform(get("/api/v1/auth/me").cookie(authenticatedCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginIdentifier").value(longLoginIdentifier));
        assertThat(jdbcClient
                        .sql("select principal_name from spring_session where principal_name = :principalName")
                        .param("principalName", longLoginIdentifier)
                        .query(String.class)
                        .single())
                .isEqualTo(longLoginIdentifier);
    }

    @Test
    void passwordChangeRevokesEverySessionForTheUser() throws Exception {
        provisionUser();
        BrowserSession first = loginAndRefreshCsrf();
        BrowserSession second = loginAndRefreshCsrf();

        mockMvc.perform(post("/api/v1/auth/password")
                        .cookie(first.cookie())
                        .header(first.headerName(), first.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new Passwords(PASSWORD, "a new secure password value"))))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/auth/me").cookie(first.cookie())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/me").cookie(second.cookie())).andExpect(status().isUnauthorized());

        BrowserSession replacement = csrf(null);
        performLogin(replacement, LOGIN, "a new secure password value").andExpect(status().isOk());
    }

    @Test
    void disablingAnAccountRevokesAnEstablishedSession() throws Exception {
        provisionUser();
        BrowserSession session = loginAndRefreshCsrf();
        UserAccount user = userAccountRepository.findByLoginIdentifier(LOGIN).orElseThrow();

        userAccountRepository.updateActive(user.id(), false, clock.instant());

        mockMvc.perform(get("/api/v1/auth/me").cookie(session.cookie()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthenticated"));
    }

    @Test
    void loginAttemptLimitRecoversAfterTheFifteenMinuteWindow() throws Exception {
        provisionUser();
        BrowserSession client = csrf(null);

        for (int attempt = 1; attempt < 8; attempt++) {
            performLogin(client, LOGIN, "wrong password value").andExpect(status().isUnauthorized());
        }
        performLogin(client, LOGIN, "wrong password value")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("too_many_attempts"));
        performLogin(client, LOGIN, PASSWORD).andExpect(status().isTooManyRequests());

        clock.advance(Duration.ofMinutes(16));
        performLogin(client, LOGIN, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void idleTimeoutAndAbsoluteAuthenticatedLifetimeAreIndependent() throws Exception {
        provisionUser();
        BrowserSession idleSession = loginAndRefreshCsrf();
        Integer maxInactiveInterval = jdbcClient
                .sql("select max_inactive_interval from spring_session where principal_name = :principalName")
                .param("principalName", LOGIN)
                .query(Integer.class)
                .single();
        assertThat(maxInactiveInterval).isEqualTo(1_800);

        jdbcClient
                .sql("""
                        update spring_session
                        set last_access_time = 0, expiry_time = 0
                        where principal_name = :principalName
                        """)
                .param("principalName", LOGIN)
                .update();
        mockMvc.perform(get("/api/v1/auth/me").cookie(idleSession.cookie()))
                .andExpect(status().isUnauthorized());

        BrowserSession absoluteSession = loginAndRefreshCsrf();
        clock.advance(Duration.ofHours(11).plusMinutes(59));
        mockMvc.perform(get("/api/v1/auth/me").cookie(absoluteSession.cookie()))
                .andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(2));
        mockMvc.perform(get("/api/v1/auth/me").cookie(absoluteSession.cookie()))
                .andExpect(status().isUnauthorized());
    }

    private void provisionUser() {
        provisioningService.provision(LOGIN, "Initial Administrator", PASSWORD.toCharArray());
    }

    private BrowserSession loginAndRefreshCsrf() throws Exception {
        BrowserSession anonymous = csrf(null);
        MvcResult login = performLogin(anonymous, LOGIN, PASSWORD).andExpect(status().isOk()).andReturn();
        return csrf(requireSessionCookie(login, anonymous.cookie()));
    }

    private org.springframework.test.web.servlet.ResultActions performLogin(
            BrowserSession session, String loginIdentifier, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .cookie(session.cookie())
                .header(session.headerName(), session.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginJson(loginIdentifier, password)));
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
                json.get("headerName").asText(),
                json.get("token").asText());
    }

    private String loginJson(String loginIdentifier, String password) throws Exception {
        return objectMapper.writeValueAsString(new Login(loginIdentifier, password));
    }

    private static Cookie requireSessionCookie(MvcResult result, Cookie fallback) {
        Cookie cookie = result.getResponse().getCookie("STOCKBOOK_SESSION");
        if (cookie != null) {
            return cookie;
        }
        if (fallback != null) {
            return fallback;
        }
        throw new AssertionError("Expected a session cookie; Set-Cookie="
                + result.getResponse().getHeaders("Set-Cookie"));
    }

    private static boolean getFuture(Future<Boolean> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        } catch (ExecutionException exception) {
            throw new AssertionError(exception.getCause());
        }
    }

    private record Login(String loginIdentifier, String password) {}

    private record Passwords(String currentPassword, String newPassword) {}

    private record BrowserSession(Cookie cookie, String headerName, String token) {}

    @TestConfiguration
    static class ClockConfiguration {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(TEST_INSTANT);
        }

    }
}
