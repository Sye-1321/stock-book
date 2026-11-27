package io.github.sye1321.stockbook.customers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sye1321.stockbook.customers.CustomerService.CustomerConflictException;
import io.github.sye1321.stockbook.customers.CustomerService.InvalidCustomerException;
import io.github.sye1321.stockbook.parties.PartyService;
import io.github.sye1321.stockbook.parties.PartyService.PartyConflictException;
import io.github.sye1321.stockbook.parties.Party;
import io.github.sye1321.stockbook.parties.PartyRepository;
import io.github.sye1321.stockbook.suppliers.Supplier;
import io.github.sye1321.stockbook.suppliers.SupplierService;
import java.time.Instant;
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
class BusinessPartyIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired private CustomerService customerService;
    @Autowired private SupplierService supplierService;
    @Autowired private PartyService partyService;
    @Autowired private PartyRepository partyRepository;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void resetDatabase() {
        jdbcClient.sql("delete from customer").update();
        jdbcClient.sql("delete from supplier").update();
        jdbcClient.sql("delete from party").update();
    }

    @Test
    void linksSupplierAndCustomerToOneIdentityWithIndependentProfiles() {
        Supplier supplier = supplierService.create(
                " supply ", "Shared Trading", "+251900000000", " INFO@SHARED.TEST ");
        Customer customer = customerService.create(
                supplier.partyId(), " retail ", null, null, null, 30);

        assertThat(customer.partyId()).isEqualTo(supplier.partyId());
        assertThat(customer.name()).isEqualTo("Shared Trading");
        assertThat(customer.email()).isEqualTo("info@shared.test");
        assertThat(partyService.lookup("shared")).singleElement().satisfies(party -> {
            assertThat(party.supplier()).isTrue();
            assertThat(party.customer()).isTrue();
        });

        customer = customerService.update(
                customer.id(),
                customer.code(),
                "Shared Trading Updated",
                customer.phone(),
                customer.email(),
                45,
                false,
                false,
                true,
                customer.partyUpdatedAt());
        Supplier refreshedSupplier = supplierService.list().getFirst();

        assertThat(customer.active()).isFalse();
        assertThat(customer.paymentTermsDays()).isEqualTo(45);
        assertThat(refreshedSupplier.active()).isTrue();
        assertThat(refreshedSupplier.partyActive()).isFalse();
        assertThat(refreshedSupplier.name()).isEqualTo("Shared Trading Updated");

        supplierService.update(
                refreshedSupplier.id(),
                refreshedSupplier.code(),
                refreshedSupplier.name(),
                refreshedSupplier.phone(),
                refreshedSupplier.email(),
                false);
        assertThat(customerService.list().getFirst().active()).isFalse();
        assertThat(jdbcClient.sql("select count(*) from party").query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void concurrentCustomerCreationAllowsOnlyOneProfilePerParty() throws Exception {
        Supplier supplier = supplierService.create("SUPPLY", "Shared Party", null, null);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Object> first = executor.submit(() -> createLinked(start, supplier.partyId(), "ONE"));
            Future<Object> second = executor.submit(() -> createLinked(start, supplier.partyId(), "TWO"));
            start.countDown();

            Object firstResult = first.get();
            Object secondResult = second.get();
            assertThat(firstResult instanceof Customer ^ secondResult instanceof Customer).isTrue();
            assertThat(firstResult instanceof CustomerConflictException
                            ^ secondResult instanceof CustomerConflictException)
                    .isTrue();
        }

        assertThat(customerService.list()).singleElement();
    }

    @Test
    void staleSharedEditsConflictAndProfileOnlyUpdatesDoNotReactivateParty() {
        Supplier staleSupplier = supplierService.create("SUPPLY", "Original Name", null, null);
        Customer staleCustomer = customerService.create(
                staleSupplier.partyId(), "CUSTOMER", null, null, null, 30);

        Supplier renamed = supplierService.update(
                staleSupplier.id(),
                staleSupplier.code(),
                "Renamed by supplier",
                staleSupplier.phone(),
                staleSupplier.email(),
                staleSupplier.active(),
                staleSupplier.partyActive(),
                true,
                staleSupplier.partyUpdatedAt());

        assertThatThrownBy(() -> customerService.update(
                        staleCustomer.id(),
                        staleCustomer.code(),
                        staleCustomer.name(),
                        staleCustomer.phone(),
                        "customer@example.test",
                        staleCustomer.paymentTermsDays(),
                        staleCustomer.active(),
                        staleCustomer.partyActive(),
                        true,
                        staleCustomer.partyUpdatedAt()))
                .isInstanceOf(CustomerConflictException.class)
                .hasMessageContaining("changed since it was loaded");

        Customer currentCustomer = customerService.list().getFirst();
        customerService.update(
                currentCustomer.id(),
                currentCustomer.code(),
                currentCustomer.name(),
                currentCustomer.phone(),
                currentCustomer.email(),
                currentCustomer.paymentTermsDays(),
                currentCustomer.active(),
                false,
                true,
                currentCustomer.partyUpdatedAt());

        supplierService.update(
                staleSupplier.id(),
                "SUPPLY-UPDATED",
                staleSupplier.name(),
                staleSupplier.phone(),
                staleSupplier.email(),
                false,
                true,
                false,
                staleSupplier.partyUpdatedAt());

        Supplier finalSupplier = supplierService.list().getFirst();
        assertThat(finalSupplier.code()).isEqualTo("SUPPLY-UPDATED");
        assertThat(finalSupplier.active()).isFalse();
        assertThat(finalSupplier.partyActive()).isFalse();
        assertThat(finalSupplier.name()).isEqualTo(renamed.name());
    }

    @Test
    void staleSharedApiUpdateReturnsClearConflict() throws Exception {
        Customer stale = customerService.create(null, "ACME", "Acme", null, null, 30);
        customerService.update(
                stale.id(),
                stale.code(),
                "Current name",
                stale.phone(),
                stale.email(),
                stale.paymentTermsDays(),
                stale.active(),
                stale.partyActive(),
                true,
                stale.partyUpdatedAt());

        CustomerRequest request = new CustomerRequest(
                null,
                stale.code(),
                "Stale name",
                stale.phone(),
                stale.email(),
                stale.paymentTermsDays(),
                stale.active(),
                stale.partyActive(),
                true,
                stale.partyUpdatedAt());
        mockMvc.perform(put("/api/v1/customers/{id}", stale.id())
                        .with(user("admin@example.com").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("conflict"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "changed since it was loaded")));
    }

    @Test
    void sharedPartyVersionAdvancesWhenJavaTimestampsShareOneMicrosecond() {
        Supplier supplier = supplierService.create("SUPPLY", "Original", null, null);
        Party original = partyRepository.findByIdForUpdate(supplier.partyId()).orElseThrow();
        Instant firstJavaTimestamp = original.updatedAt().plusNanos(100);
        Instant secondJavaTimestamp = original.updatedAt().plusNanos(200);

        partyService.updateIfRequested(
                original,
                "First update",
                original.phone(),
                original.email(),
                original.active(),
                true,
                original.updatedAt(),
                firstJavaTimestamp);

        Party persisted = partyRepository.findByIdForUpdate(original.id()).orElseThrow();
        assertThat(persisted.updatedAt()).isEqualTo(original.updatedAt().plusNanos(1_000));
        assertThatThrownBy(() -> partyService.updateIfRequested(
                        persisted,
                        "Stale second update",
                        persisted.phone(),
                        persisted.email(),
                        persisted.active(),
                        true,
                        original.updatedAt(),
                        secondJavaTimestamp))
                .isInstanceOf(PartyConflictException.class)
                .hasMessageContaining("changed since it was loaded");
        assertThat(partyRepository.findByIdForUpdate(original.id()).orElseThrow().name())
                .isEqualTo("First update");
    }

    @Test
    void rejectsDuplicatesInvalidDataAndRollsBackCrossTableCreation() {
        Customer existing = customerService.create(
                null, "CUSTOMER", "First Customer", null, "first@example.test", 0);
        long partiesBefore = partyCount();

        assertThatThrownBy(() -> customerService.create(
                        null, " customer ", "Duplicate", null, null, 30))
                .isInstanceOf(CustomerConflictException.class);
        assertThat(partyCount()).isEqualTo(partiesBefore);

        assertThatThrownBy(() -> customerService.create(
                        existing.partyId(), "SECOND", "Spoofed identity", null, null, 30))
                .isInstanceOf(InvalidCustomerException.class)
                .hasMessageContaining("must not be supplied");
        assertThatThrownBy(() -> customerService.create(
                        null, "SECOND", "Second", null, null, -1))
                .isInstanceOf(InvalidCustomerException.class);
        assertThatThrownBy(() -> customerService.create(
                        existing.partyId(), "SECOND", null, null, null, 30))
                .isInstanceOf(CustomerConflictException.class);

        assertThat(customerService.list()).singleElement();
        assertThat(partyCount()).isEqualTo(1);
    }

    @Test
    void customerAndPartyEndpointsEnforceReadAndWriteAuthorization() throws Exception {
        Customer customer = customerService.create(
                null, "ACME", "Acme Customer", null, null, 30);
        CustomerRequest request = new CustomerRequest(
                null, "NEW", "New Customer", null, null, 15, true, true, null, null);

        mockMvc.perform(get("/api/v1/customers")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/customers")
                        .with(user("staff@example.com").roles("STAFF")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("ACME"));
        mockMvc.perform(get("/api/v1/parties")
                        .param("query", "Acme")
                        .with(user("staff@example.com").roles("STAFF")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(customer.partyId().toString()));
        mockMvc.perform(post("/api/v1/customers")
                        .with(user("staff@example.com").roles("STAFF"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/customers/{id}", customer.id())
                        .with(user("staff@example.com").roles("STAFF"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/customers")
                        .with(user("admin@example.com").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("NEW"));
    }

    private long partyCount() {
        return jdbcClient.sql("select count(*) from party").query(Long.class).single();
    }

    private Object createLinked(CountDownLatch start, UUID partyId, String code)
            throws InterruptedException {
        start.await();
        try {
            return customerService.create(partyId, code, null, null, null, 30);
        } catch (CustomerConflictException exception) {
            return exception;
        }
    }

    private record CustomerRequest(
            UUID partyId,
            String code,
            String name,
            String phone,
            String email,
            Integer paymentTermsDays,
            Boolean active,
            Boolean partyActive,
            Boolean updateParty,
            Instant partyUpdatedAt) {}
}
