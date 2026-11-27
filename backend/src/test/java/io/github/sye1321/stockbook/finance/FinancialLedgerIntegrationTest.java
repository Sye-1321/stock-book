package io.github.sye1321.stockbook.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sye1321.stockbook.finance.JournalService.DuplicateJournalException;
import io.github.sye1321.stockbook.finance.JournalService.InvalidJournalException;
import io.github.sye1321.stockbook.identity.UserAccountRepository;
import io.github.sye1321.stockbook.identity.provisioning.InitialAdminProvisioningService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinancialLedgerIntegrationTest {

    private static final String ADMIN_LOGIN = "finance-admin@example.com";
    private static final String ADMIN_PASSWORD = "correct horse battery staple";
    private static final AtomicInteger PERIOD_SEQUENCE = new AtomicInteger();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired private AccountService accountService;
    @Autowired private PeriodService periodService;
    @Autowired private JournalService journalService;
    @Autowired private InitialAdminProvisioningService provisioningService;
    @Autowired private UserAccountRepository userAccountRepository;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private TransactionTemplate transactionTemplate;

    private UUID administratorId;
    private FinanceAccount cash;
    private FinanceAccount expense;
    private LocalDate today;
    private FinancialPeriod period;

    @BeforeAll
    void provisionAdministrator() {
        provisioningService.provision(ADMIN_LOGIN, "Finance Administrator", ADMIN_PASSWORD.toCharArray());
        administratorId = userAccountRepository
                .findByLoginIdentifier(ADMIN_LOGIN)
                .orElseThrow()
                .id();
        cash = accountService.require(AccountRole.CASH);
        expense = accountService.require(AccountRole.OPERATING_EXPENSE);
    }

    @BeforeEach
    void createPeriod() {
        int sequence = PERIOD_SEQUENCE.incrementAndGet();
        today = LocalDate.of(2020, 1, 1).plusDays(sequence * 3L);
        period = periodService.create("Test period " + sequence, today.minusDays(1), today);
    }

    @Test
    void providesDefaultSettingsAndFixedPostingAccounts() {
        assertThat(accountService.settings().currencyCode()).isEqualTo("ETB");
        assertThat(accountService.settings().timezone().getId()).isEqualTo("Africa/Addis_Ababa");
        assertThat(accountService.list()).hasSize(10).allMatch(FinanceAccount::postingAllowed);
        assertThat(accountService.require(AccountRole.BANK).type()).isEqualTo(AccountType.ASSET);
        assertThat(accountService.require(AccountRole.OWNER_CAPITAL).type()).isEqualTo(AccountType.EQUITY);
    }

    @Test
    void postsBalancedExactMoneyAndDerivesBalancesFromLines() {
        BigDecimal cashBefore = balance(cash.id()).netAmount();
        BigDecimal expenseBefore = balance(expense.id()).netAmount();
        JournalEntry journal = journalService.post(request(period.id(), "1250.40"));

        assertThat(journal.sourceId()).isEqualTo(period.id());
        assertThat(journal.lines()).hasSize(2);
        assertThat(journal.lines().getFirst().debit()).isEqualByComparingTo("1250.40");
        AccountBalance cashBalance = balance(cash.id());
        AccountBalance expenseBalance = balance(expense.id());
        assertThat(cashBalance.netAmount()).isEqualByComparingTo(cashBefore.subtract(money("1250.40")));
        assertThat(expenseBalance.netAmount())
                .isEqualByComparingTo(expenseBefore.add(money("1250.40")));
    }

    @Test
    void rejectsInvalidJournalsAndRollsBackEveryEffect() {
        JournalPostingRequest unbalanced = new JournalPostingRequest(
                JournalSourceType.EXPENSE,
                UUID.randomUUID(),
                PostingPurpose.EXPENSE,
                today,
                administratorId,
                null,
                List.of(
                        new JournalLineRequest(expense.id(), money("10.00"), money("0")),
                        new JournalLineRequest(cash.id(), money("0"), money("9.99"))));
        assertThatThrownBy(() -> journalService.post(unbalanced)).isInstanceOf(InvalidJournalException.class);

        JournalPostingRequest invalidAccount = new JournalPostingRequest(
                JournalSourceType.EXPENSE,
                UUID.randomUUID(),
                PostingPurpose.EXPENSE,
                today,
                administratorId,
                null,
                List.of(
                        new JournalLineRequest(UUID.randomUUID(), money("10.00"), money("0")),
                        new JournalLineRequest(cash.id(), money("0"), money("10.00"))));
        assertThatThrownBy(() -> journalService.post(invalidAccount)).isInstanceOf(InvalidJournalException.class);
        assertThat(periodJournalCount()).isZero();

        assertThatThrownBy(() -> journalService.post(request(period.id(), "1.001")))
                .isInstanceOf(InvalidJournalException.class);
        assertThatThrownBy(() -> journalService.post(new JournalPostingRequest(
                        JournalSourceType.EXPENSE,
                        UUID.randomUUID(),
                        PostingPurpose.EXPENSE,
                        today.plusDays(1),
                        administratorId,
                        null,
                        request(UUID.randomUUID(), "1.00").lines())))
                .isInstanceOf(InvalidJournalException.class);
        assertThatThrownBy(() -> journalService.post(new JournalPostingRequest(
                        JournalSourceType.EXPENSE,
                        period.id(),
                        PostingPurpose.EXPENSE,
                        today,
                        administratorId,
                        null,
                        request(UUID.randomUUID(), "10.00").lines())))
                .isInstanceOf(InvalidJournalException.class);
        assertThatThrownBy(() -> journalService.post(new JournalPostingRequest(
                        JournalSourceType.SYSTEM,
                        period.id(),
                        PostingPurpose.OPENING,
                        today,
                        UUID.randomUUID(),
                        null,
                        request(UUID.randomUUID(), "10.00").lines())))
                .isInstanceOf(InvalidJournalException.class);
        assertThat(periodJournalCount()).isZero();
    }

    @Test
    void uniqueSourceAndPurposePreventsDuplicateEffects() {
        UUID sourceId = period.id();
        journalService.post(request(sourceId, "15.00"));

        assertThatThrownBy(() -> journalService.post(request(sourceId, "15.00")))
                .isInstanceOf(DuplicateJournalException.class);
        assertThat(periodJournalCount()).isEqualTo(1);
    }

    @Test
    void reversalsRequireEligibleSourceChronologyAndExactInverseEffects() {
        JournalEntry original = journalService.post(request(period.id(), "25.00"));

        assertThatThrownBy(() -> journalService.post(reversal(original, today.minusDays(1), "25.00")))
                .isInstanceOf(InvalidJournalException.class);
        assertThatThrownBy(() -> journalService.post(new JournalPostingRequest(
                        JournalSourceType.SYSTEM,
                        UUID.randomUUID(),
                        PostingPurpose.REVERSAL,
                        today,
                        administratorId,
                        original.id(),
                        reversal(original, today, "25.00").lines())))
                .isInstanceOf(InvalidJournalException.class);
        assertThatThrownBy(() -> journalService.post(reversal(original, today, "24.99")))
                .isInstanceOf(InvalidJournalException.class);

        JournalEntry reversal = journalService.post(reversal(original, today, "25.00"));
        assertThat(reversal.reversesJournalId()).isEqualTo(original.id());
        assertThat(reversal.lines().getFirst().credit()).isEqualByComparingTo("25.00");
        assertThatThrownBy(() -> journalService.post(reversal(original, today, "25.00")))
                .isInstanceOf(DuplicateJournalException.class);
        assertThat(periodJournalCount()).isEqualTo(2);
    }

    @Test
    void rejectsReversalOfAnUnsupportedOriginalPostingPurpose() {
        UUID originalId = insertPrivilegedUnsupportedJournal();
        JournalPostingRequest reversal = new JournalPostingRequest(
                JournalSourceType.SALE,
                originalId,
                PostingPurpose.REVERSAL,
                today,
                administratorId,
                originalId,
                List.of(
                        new JournalLineRequest(expense.id(), money("0"), money("12.00")),
                        new JournalLineRequest(cash.id(), money("12.00"), money("0"))));

        assertThatThrownBy(() -> journalService.post(reversal)).isInstanceOf(InvalidJournalException.class);
        assertThat(periodJournalCount()).isEqualTo(1);
    }

    @Test
    void databaseRejectsMutationOfPostedRecords() {
        JournalEntry posted = journalService.post(request(period.id(), "20.00"));
        assertThatThrownBy(() -> jdbcClient
                        .sql("update journal_entry set effective_date = :date where id = :id")
                        .param("date", today.minusDays(1))
                        .param("id", posted.id())
                        .update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbcClient
                        .sql("delete from journal_entry_line where journal_id = :id")
                        .param("id", posted.id())
                        .update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbcClient
                        .sql("""
                                truncate owner_funding_approval_consumption,
                                         owner_funding_reversal, owner_funding_contribution,
                                         owner_funding_approval, finance_account_posting_guard,
                                         journal_entry_line, journal_entry
                                """)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("immutable");
    }

    @Test
    void postingLockCompletesBeforeConcurrentPeriodClosure() throws Exception {
        CountDownLatch periodLocked = new CountDownLatch(1);
        CountDownLatch allowPosting = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<JournalEntry> posting = executor.submit(() -> transactionTemplate.execute(status -> {
                jdbcClient
                        .sql("select id from financial_period where id = :id for share")
                        .param("id", period.id())
                        .query(UUID.class)
                        .single();
                periodLocked.countDown();
                await(allowPosting);
                return journalService.post(request(period.id(), "30.00"));
            }));
            periodLocked.await();
            Future<FinancialPeriod> closure = executor.submit(() -> periodService.close(period.id(), administratorId));
            allowPosting.countDown();

            assertThat(posting.get()).isNotNull();
            assertThat(closure.get().state()).isEqualTo(PeriodState.CLOSED);
        }
        assertThat(periodJournalCount()).isEqualTo(1);
    }

    @Test
    void periodClosureCompletesBeforeConcurrentPostingAndPostingIsRejected() throws Exception {
        CountDownLatch periodLocked = new CountDownLatch(1);
        CountDownLatch allowClosure = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<FinancialPeriod> closure = executor.submit(() -> transactionTemplate.execute(status -> {
                jdbcClient
                        .sql("select id from financial_period where id = :id for update")
                        .param("id", period.id())
                        .query(UUID.class)
                        .single();
                periodLocked.countDown();
                await(allowClosure);
                return periodService.close(period.id(), administratorId);
            }));
            periodLocked.await();
            Future<Boolean> posting = executor.submit(() -> {
                try {
                    journalService.post(request(period.id(), "30.00"));
                    return true;
                } catch (InvalidJournalException exception) {
                    return false;
                }
            });
            allowClosure.countDown();

            assertThat(closure.get().state()).isEqualTo(PeriodState.CLOSED);
            assertThat(posting.get()).isFalse();
        }
        assertThat(periodJournalCount()).isZero();
    }

    @Test
    void closedPeriodRejectsPosting() {
        periodService.close(period.id(), administratorId);
        assertThatThrownBy(() -> journalService.post(request(period.id(), "10.00")))
                .isInstanceOf(InvalidJournalException.class);
        assertThat(periodJournalCount()).isZero();
    }

    private JournalPostingRequest request(UUID sourceId, String amount) {
        return new JournalPostingRequest(
                JournalSourceType.SYSTEM,
                sourceId,
                PostingPurpose.OPENING,
                today,
                administratorId,
                null,
                List.of(
                        new JournalLineRequest(expense.id(), money(amount), money("0")),
                        new JournalLineRequest(cash.id(), money("0"), money(amount))));
    }

    private JournalPostingRequest reversal(JournalEntry original, LocalDate effectiveDate, String amount) {
        return new JournalPostingRequest(
                original.sourceType(),
                original.id(),
                PostingPurpose.REVERSAL,
                effectiveDate,
                administratorId,
                original.id(),
                List.of(
                        new JournalLineRequest(expense.id(), money("0"), money(amount)),
                        new JournalLineRequest(cash.id(), money(amount), money("0"))));
    }

    private AccountBalance balance(UUID accountId) {
        return journalService.balances().stream()
                .filter(balance -> balance.accountId().equals(accountId))
                .findFirst()
                .orElseThrow();
    }

    private UUID insertPrivilegedUnsupportedJournal() {
        UUID journalId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status -> {
            jdbcClient
                    .sql("""
                            insert into journal_entry (
                                id, period_id, source_type, source_id, posting_purpose,
                                effective_date, recorded_at, posted_by
                            ) values (
                                :id, :periodId, 'SALE', :sourceId, 'SALE',
                                :effectiveDate, now(), :actor
                            )
                            """)
                    .param("id", journalId)
                    .param("periodId", period.id())
                    .param("sourceId", UUID.randomUUID())
                    .param("effectiveDate", today)
                    .param("actor", administratorId)
                    .update();
            jdbcClient
                    .sql("""
                            insert into journal_entry_line (
                                journal_id, line_number, account_id, debit, credit
                            ) values
                                (:id, 1, :expense, 12.00, 0),
                                (:id, 2, :cash, 0, 12.00)
                            """)
                    .param("id", journalId)
                    .param("expense", expense.id())
                    .param("cash", cash.id())
                    .update();
        });
        return journalId;
    }

    private long periodJournalCount() {
        return jdbcClient
                .sql("select count(*) from journal_entry where period_id = :periodId")
                .param("periodId", period.id())
                .query(Long.class)
                .single();
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
