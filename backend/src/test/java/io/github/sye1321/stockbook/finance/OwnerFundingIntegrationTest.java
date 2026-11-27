package io.github.sye1321.stockbook.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sye1321.stockbook.finance.OwnerFundingService.InvalidOwnerFundingException;
import io.github.sye1321.stockbook.finance.OwnerFundingService.OwnerFundingAuthorizationException;
import io.github.sye1321.stockbook.identity.UserAccountRepository;
import io.github.sye1321.stockbook.identity.provisioning.InitialAdminProvisioningService;
import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OwnerFundingIntegrationTest {

    private static final AtomicInteger PERIOD_SEQUENCE = new AtomicInteger();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired private OwnerFundingService fundingService;
    @Autowired private AccountService accountService;
    @Autowired private PeriodService periodService;
    @Autowired private InitialAdminProvisioningService provisioningService;
    @Autowired private UserAccountRepository userAccountRepository;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private TransactionTemplate transactionTemplate;

    private UUID initiatorId;
    private UUID approverId;
    private FinanceAccount cash;
    private FinanceAccount bank;
    private FinanceAccount expense;
    private FinanceAccount capital;
    private LocalDate effectiveDate;
    private FinancialPeriod period;

    @BeforeAll
    void createActors() {
        provisioningService.provision(
                "funding-initiator@example.com",
                "Funding Initiator",
                "correct horse battery staple".toCharArray());
        initiatorId = userAccountRepository
                .findByLoginIdentifier("funding-initiator@example.com")
                .orElseThrow()
                .id();
        approverId = UUID.randomUUID();
        jdbcClient
                .sql("""
                        insert into app_user (
                            id, login_identifier, display_name, password_hash, active, role,
                            session_version, created_at, updated_at, password_changed_at
                        ) values (
                            :id, 'funding-approver@example.com', 'Funding Approver',
                            '{noop}unused', true, 'STAFF', 0, now(), now(), now()
                        )
                        """)
                .param("id", approverId)
                .update();
        String databaseRole = jdbcClient.sql("select current_user").query(String.class).single();
        jdbcClient
                .sql("""
                        insert into finance_database_principal (
                            database_role, user_id, principal_type
                        ) values (:databaseRole, :userId, 'FUNDING_APPROVER')
                        """)
                .param("databaseRole", databaseRole)
                .param("userId", approverId)
                .update();
        cash = accountService.require(AccountRole.CASH);
        bank = accountService.require(AccountRole.BANK);
        expense = accountService.require(AccountRole.OPERATING_EXPENSE);
        capital = accountService.require(AccountRole.OWNER_CAPITAL);
    }

    @BeforeEach
    void createPeriodAndResetPermissions() {
        jdbcClient.sql("delete from finance_user_permission").update();
        int sequence = PERIOD_SEQUENCE.incrementAndGet();
        effectiveDate = LocalDate.of(2010, 1, 1).plusDays(sequence * 3L);
        period = periodService.create(
                "Funding period " + sequence, effectiveDate.minusDays(1), effectiveDate);
    }

    @Test
    void postsTypedFundingToBankAndOwnerCapital() {
        grantFundingPermissions();
        UUID requestId = UUID.randomUUID();

        OwnerFundingContribution contribution =
                fundingService.post(approvedRequest(requestId, bank.id(), "12500.25"));

        assertThat(contribution.requestId()).isEqualTo(requestId);
        assertThat(contribution.amount()).isEqualByComparingTo("12500.25");
        assertThat(contribution.initiatedBy()).isEqualTo(initiatorId);
        assertThat(contribution.approvedBy()).isEqualTo(approverId);
        assertThat(journalValue(contribution.journalId(), "source_type")).isEqualTo("OWNER_FUNDING");
        assertThat(journalValue(contribution.journalId(), "posting_purpose"))
                .isEqualTo("OWNER_FUNDING");
        assertThat(lineAmount(contribution.journalId(), bank.id(), "debit"))
                .isEqualByComparingTo("12500.25");
        assertThat(lineAmount(contribution.journalId(), capital.id(), "credit"))
                .isEqualByComparingTo("12500.25");
    }

    @Test
    void adminMembershipAloneDoesNotAuthorizeFundingAndArbitraryAccountsRollBack() {
        UUID unauthorizedRequest = UUID.randomUUID();
        assertThatThrownBy(() -> fundingService.post(new OwnerFundingRequest(
                        unauthorizedRequest,
                        cash.id(),
                        new BigDecimal("20.00"),
                        effectiveDate,
                        initiatorId,
                        UUID.randomUUID())))
                .isInstanceOf(OwnerFundingAuthorizationException.class);
        assertThat(contributionCount(unauthorizedRequest)).isZero();

        grantFundingPermissions();
        UUID forgedRequest = UUID.randomUUID();
        assertThatThrownBy(() -> fundingService.post(new OwnerFundingRequest(
                        forgedRequest,
                        cash.id(),
                        new BigDecimal("20.00"),
                        effectiveDate,
                        initiatorId,
                        UUID.randomUUID())))
                .isInstanceOf(OwnerFundingAuthorizationException.class);
        assertThat(contributionCount(forgedRequest)).isZero();

        UUID invalidAccountRequest = UUID.randomUUID();
        UUID cashApproval = approveFunding(invalidAccountRequest, cash.id(), "20.00");
        assertThatThrownBy(
                        () -> fundingService.post(new OwnerFundingRequest(
                                invalidAccountRequest,
                                expense.id(),
                                new BigDecimal("20.00"),
                                effectiveDate,
                                initiatorId,
                                cashApproval)))
                .isInstanceOf(OwnerFundingAuthorizationException.class);
        assertThat(contributionCount(invalidAccountRequest)).isZero();
        assertThat(periodJournalCount()).isZero();
    }

    @Test
    void concurrentRetriesAreIdempotentAndChangedPayloadConflicts() throws Exception {
        grantFundingPermissions();
        UUID requestId = UUID.randomUUID();
        OwnerFundingRequest request = approvedRequest(requestId, cash.id(), "100.00");

        OwnerFundingContribution first;
        OwnerFundingContribution retry;
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<OwnerFundingContribution> firstPosting =
                    executor.submit(() -> fundingService.post(request));
            Future<OwnerFundingContribution> retryPosting =
                    executor.submit(() -> fundingService.post(request));
            first = firstPosting.get();
            retry = retryPosting.get();
        }

        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(retry.journalId()).isEqualTo(first.journalId());
        assertThat(contributionCount(requestId)).isEqualTo(1);
        assertThatThrownBy(() -> fundingService.post(new OwnerFundingRequest(
                        requestId,
                        cash.id(),
                        new BigDecimal("100.01"),
                        effectiveDate,
                        initiatorId,
                        request.approvalId())))
                .isInstanceOf(OwnerFundingAuthorizationException.class);
        UUID reusedApprovalRequest = UUID.randomUUID();
        assertThatThrownBy(() -> fundingService.post(new OwnerFundingRequest(
                        reusedApprovalRequest,
                        cash.id(),
                        request.amount(),
                        effectiveDate,
                        initiatorId,
                        request.approvalId())))
                .isInstanceOf(OwnerFundingAuthorizationException.class);
        assertThat(contributionCount(reusedApprovalRequest)).isZero();

        jdbcClient.sql("delete from finance_user_permission").update();
        assertThatThrownBy(() -> fundingService.post(request))
                .isInstanceOf(OwnerFundingAuthorizationException.class);
        assertThat(periodJournalCount()).isEqualTo(1);
    }

    @Test
    void protectedAccountRejectsBackdatedFundingAfterALaterEffectivePosting() {
        grantFundingPermissions();
        fundingService.post(approvedRequest(UUID.randomUUID(), bank.id(), "40.00"));

        UUID backdatedRequestId = UUID.randomUUID();
        LocalDate backdated = effectiveDate.minusDays(1);
        UUID approvalId = approveFunding(backdatedRequestId, bank.id(), "10.00", backdated);
        OwnerFundingRequest request = new OwnerFundingRequest(
                backdatedRequestId,
                bank.id(),
                new BigDecimal("10.00"),
                backdated,
                initiatorId,
                approvalId);

        assertThatThrownBy(() -> fundingService.post(request))
                .isInstanceOf(InvalidOwnerFundingException.class);
        assertThat(contributionCount(backdatedRequestId)).isZero();
    }

    @Test
    void concurrentLaterPostingSerializesAndRejectsConflictingBackdatedPosting() throws Exception {
        grantFundingPermissions();
        OwnerFundingRequest later = approvedRequest(UUID.randomUUID(), bank.id(), "60.00");
        UUID earlierRequestId = UUID.randomUUID();
        LocalDate earlierDate = effectiveDate.minusDays(1);
        OwnerFundingRequest earlier = new OwnerFundingRequest(
                earlierRequestId,
                bank.id(),
                new BigDecimal("5.00"),
                earlierDate,
                initiatorId,
                approveFunding(earlierRequestId, bank.id(), "5.00", earlierDate));
        CountDownLatch protectedAccountLocked = new CountDownLatch(1);
        CountDownLatch allowLaterPosting = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<OwnerFundingContribution> laterPosting = executor.submit(() ->
                    transactionTemplate.execute(status -> {
                        jdbcClient
                                .sql("select 1 from pg_advisory_xact_lock("
                                        + "hashtextextended(cast(:id as text), 14001))")
                                .param("id", later.requestId())
                                .query(Integer.class)
                                .single();
                        lockPeriod("for share");
                        jdbcClient
                                .sql("select id from owner_funding_approval where id = :id for update")
                                .param("id", later.approvalId())
                                .query(UUID.class)
                                .single();
                        jdbcClient
                                .sql("select id from finance_account where id = :id for update")
                                .param("id", bank.id())
                                .query(UUID.class)
                                .single();
                        protectedAccountLocked.countDown();
                        await(allowLaterPosting);
                        return fundingService.post(later);
                    }));
            protectedAccountLocked.await();
            Future<Boolean> earlierPosting = executor.submit(() -> {
                try {
                    fundingService.post(earlier);
                    return true;
                } catch (InvalidOwnerFundingException exception) {
                    return false;
                }
            });
            allowLaterPosting.countDown();

            assertThat(laterPosting.get()).isNotNull();
            assertThat(earlierPosting.get()).isFalse();
        }
        assertThat(contributionCount(earlierRequestId)).isZero();
    }

    @Test
    void reversalIsExactIdempotentAndRequiresValidChronology() {
        grantFundingPermissions();
        OwnerFundingContribution funding =
                fundingService.post(approvedRequest(UUID.randomUUID(), bank.id(), "75.40"));

        UUID reversalRequestId = UUID.randomUUID();
        UUID reversalApproval = approveReversal(reversalRequestId, funding, effectiveDate);

        jdbcClient.sql("delete from finance_user_permission").update();
        assertThatThrownBy(() -> fundingService.reverse(new OwnerFundingReversalRequest(
                        reversalRequestId,
                        funding.id(),
                        effectiveDate,
                        initiatorId,
                        reversalApproval)))
                .isInstanceOf(OwnerFundingAuthorizationException.class);
        grantFundingPermissions();

        assertThatThrownBy(() -> fundingService.reverse(new OwnerFundingReversalRequest(
                        reversalRequestId,
                        funding.id(),
                        effectiveDate.minusDays(1),
                        initiatorId,
                        reversalApproval)))
                .isInstanceOf(OwnerFundingAuthorizationException.class);

        OwnerFundingReversalRequest reversalRequest = new OwnerFundingReversalRequest(
                reversalRequestId, funding.id(), effectiveDate, initiatorId, reversalApproval);
        OwnerFundingReversal reversal = fundingService.reverse(reversalRequest);
        OwnerFundingReversal retry = fundingService.reverse(reversalRequest);

        assertThat(retry.id()).isEqualTo(reversal.id());
        assertThat(lineAmount(reversal.journalId(), capital.id(), "debit"))
                .isEqualByComparingTo("75.40");
        assertThat(lineAmount(reversal.journalId(), bank.id(), "credit"))
                .isEqualByComparingTo("75.40");
        assertThat(journalUuid(reversal.journalId(), "reverses_journal_id"))
                .isEqualTo(funding.journalId());
    }

    @Test
    void rejectsReversalAfterARecordedOutflowUsesTheFundedAccount() {
        grantFundingPermissions();
        OwnerFundingContribution funding =
                fundingService.post(approvedRequest(UUID.randomUUID(), cash.id(), "50.00"));
        insertDependentOutflow(funding);

        UUID reversalRequestId = UUID.randomUUID();
        UUID reversalApproval = approveReversal(reversalRequestId, funding, effectiveDate);

        assertThatThrownBy(() -> fundingService.reverse(new OwnerFundingReversalRequest(
                        reversalRequestId,
                        funding.id(),
                        effectiveDate,
                        initiatorId,
                        reversalApproval)))
                .isInstanceOf(InvalidOwnerFundingException.class);
        assertThat(reversalCount(funding.id())).isZero();
    }

    @Test
    void fundingPostingCompletesBeforeConcurrentPeriodClosure() throws Exception {
        grantFundingPermissions();
        OwnerFundingRequest request = approvedRequest(UUID.randomUUID(), cash.id(), "30.00");
        CountDownLatch periodLocked = new CountDownLatch(1);
        CountDownLatch allowPosting = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<OwnerFundingContribution> posting = executor.submit(() ->
                    transactionTemplate.execute(status -> {
                        lockPeriod("for share");
                        periodLocked.countDown();
                        await(allowPosting);
                        return fundingService.post(request);
                    }));
            periodLocked.await();
            Future<FinancialPeriod> closure =
                    executor.submit(() -> periodService.close(period.id(), initiatorId));
            allowPosting.countDown();

            assertThat(posting.get()).isNotNull();
            assertThat(closure.get().state()).isEqualTo(PeriodState.CLOSED);
        }
        assertThat(periodJournalCount()).isEqualTo(1);
    }

    @Test
    void periodClosureCompletesBeforeConcurrentFundingAndFundingRollsBack() throws Exception {
        grantFundingPermissions();
        UUID requestId = UUID.randomUUID();
        OwnerFundingRequest request = approvedRequest(requestId, cash.id(), "30.00");
        CountDownLatch periodLocked = new CountDownLatch(1);
        CountDownLatch allowClosure = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<FinancialPeriod> closure = executor.submit(() ->
                    transactionTemplate.execute(status -> {
                        lockPeriod("for update");
                        periodLocked.countDown();
                        await(allowClosure);
                        return periodService.close(period.id(), initiatorId);
                    }));
            periodLocked.await();
            Future<Boolean> posting = executor.submit(() -> {
                try {
                    fundingService.post(request);
                    return true;
                } catch (InvalidOwnerFundingException exception) {
                    return false;
                }
            });
            allowClosure.countDown();

            assertThat(closure.get().state()).isEqualTo(PeriodState.CLOSED);
            assertThat(posting.get()).isFalse();
        }
        assertThat(contributionCount(requestId)).isZero();
        assertThat(periodJournalCount()).isZero();
    }

    private void grantFundingPermissions() {
        jdbcClient
                .sql("""
                        insert into finance_user_permission (
                            user_id, permission, granted_by, granted_at
                        ) values
                            (:initiator, 'OWNER_FUNDING_INITIATE', :grantor, now()),
                            (:approver, 'OWNER_FUNDING_APPROVE', :grantor, now())
                        """)
                .param("initiator", initiatorId)
                .param("approver", approverId)
                .param("grantor", initiatorId)
                .update();
    }

    private OwnerFundingRequest approvedRequest(UUID requestId, UUID accountId, String amount) {
        UUID approvalId = approveFunding(requestId, accountId, amount);
        return new OwnerFundingRequest(
                requestId,
                accountId,
                new BigDecimal(amount),
                effectiveDate,
                initiatorId,
                approvalId);
    }

    private UUID approveFunding(UUID requestId, UUID accountId, String amount) {
        return approveFunding(requestId, accountId, amount, effectiveDate);
    }

    private UUID approveFunding(
            UUID requestId, UUID accountId, String amount, LocalDate approvedEffectiveDate) {
        UUID approvalId = UUID.randomUUID();
        return jdbcClient
                .sql("""
                        select finance_approve_owner_funding_intent(
                            :id, 'FUNDING', :requestId, cast(null as uuid), :accountId,
                            :amount, :effectiveDate, :initiator
                        )
                        """)
                .param("id", approvalId)
                .param("requestId", requestId)
                .param("accountId", accountId)
                .param("amount", new BigDecimal(amount))
                .param("effectiveDate", approvedEffectiveDate)
                .param("initiator", initiatorId)
                .query(UUID.class)
                .single();
    }

    private UUID approveReversal(
            UUID requestId, OwnerFundingContribution funding, LocalDate reversalDate) {
        UUID approvalId = UUID.randomUUID();
        return jdbcClient
                .sql("""
                        select finance_approve_owner_funding_intent(
                            :id, 'REVERSAL', :requestId, :contributionId, :accountId,
                            :amount, :effectiveDate, :initiator
                        )
                        """)
                .param("id", approvalId)
                .param("requestId", requestId)
                .param("contributionId", funding.id())
                .param("accountId", funding.cashAccountId())
                .param("amount", funding.amount())
                .param("effectiveDate", reversalDate)
                .param("initiator", initiatorId)
                .query(UUID.class)
                .single();
    }

    private void insertDependentOutflow(OwnerFundingContribution funding) {
        transactionTemplate.executeWithoutResult(status -> {
            UUID journalId = UUID.randomUUID();
            jdbcClient
                    .sql("""
                            insert into journal_entry (
                                id, period_id, source_type, source_id, posting_purpose,
                                effective_date, recorded_at, posted_by
                            ) values (
                                :id, :periodId, 'EXPENSE', :sourceId, 'EXPENSE',
                                :effectiveDate, clock_timestamp() + interval '1 second', :actor
                            )
                            """)
                    .param("id", journalId)
                    .param("periodId", period.id())
                    .param("sourceId", UUID.randomUUID())
                    .param("effectiveDate", effectiveDate)
                    .param("actor", initiatorId)
                    .update();
            jdbcClient
                    .sql("""
                            insert into journal_entry_line (
                                journal_id, line_number, account_id, debit, credit
                            ) values
                                (:id, 1, :expense, 10.00, 0),
                                (:id, 2, :cash, 0, 10.00)
                            """)
                    .param("id", journalId)
                    .param("expense", expense.id())
                    .param("cash", funding.cashAccountId())
                    .update();
        });
    }

    private void lockPeriod(String lockMode) {
        jdbcClient
                .sql("select id from financial_period where id = :id " + lockMode)
                .param("id", period.id())
                .query(UUID.class)
                .single();
    }

    private String journalValue(UUID journalId, String column) {
        return jdbcClient
                .sql("select " + column + " from journal_entry where id = :id")
                .param("id", journalId)
                .query(String.class)
                .single();
    }

    private UUID journalUuid(UUID journalId, String column) {
        return jdbcClient
                .sql("select " + column + " from journal_entry where id = :id")
                .param("id", journalId)
                .query(UUID.class)
                .single();
    }

    private BigDecimal lineAmount(UUID journalId, UUID accountId, String column) {
        return jdbcClient
                .sql("select " + column
                        + " from journal_entry_line where journal_id = :journalId and account_id = :accountId")
                .param("journalId", journalId)
                .param("accountId", accountId)
                .query(BigDecimal.class)
                .single();
    }

    private long contributionCount(UUID requestId) {
        return jdbcClient
                .sql("select count(*) from owner_funding_contribution where request_id = :requestId")
                .param("requestId", requestId)
                .query(Long.class)
                .single();
    }

    private long reversalCount(UUID contributionId) {
        return jdbcClient
                .sql("select count(*) from owner_funding_reversal where contribution_id = :id")
                .param("id", contributionId)
                .query(Long.class)
                .single();
    }

    private long periodJournalCount() {
        return jdbcClient
                .sql("select count(*) from journal_entry where period_id = :id")
                .param("id", period.id())
                .query(Long.class)
                .single();
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
