package io.github.sye1321.stockbook.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class FinancialLedgerMigrationTest {

    private static final String MIGRATION_USER = "stock_book_migrator";
    private static final String MIGRATION_PASSWORD = "migration-test-password";
    private static final String RUNTIME_USER = "stock_book_runtime_login";
    private static final String RUNTIME_PASSWORD = "runtime-test-password";
    private static final String APPROVAL_USER = "stock_book_approval_login";
    private static final String APPROVAL_PASSWORD = "approval-test-password";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void upgradesAUsedV10DatabaseAndEnforcesSeparateRuntimePrivileges() throws SQLException {
        createProductionRoles();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), MIGRATION_USER, MIGRATION_PASSWORD)
                .target("10")
                .load()
                .migrate();
        insertExistingV10Data();

        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), MIGRATION_USER, MIGRATION_PASSWORD)
                .target("13")
                .load()
                .migrate();
        insertExistingV13Funding();

        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), MIGRATION_USER, MIGRATION_PASSWORD)
                .load();
        flyway.migrate();

        assertExistingV10DataWasPreserved();
        assertRoleAndFunctionPrivileges();
        assertProductionRuntimeVerifier();
        assertRuntimeCannotBypassLedgerProtection();
        assertRestrictedRuntimeCanPostAuthorizedFundingAndReversal();
        assertTemporaryObjectsCannotShadowDefinerReferences();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("15");
    }

    private void createProductionRoles() throws SQLException {
        try (Connection connection = adminConnection(); var statement = connection.createStatement()) {
            statement.execute("create role stock_book_finance_owner"
                    + " nologin nosuperuser nocreatedb nocreaterole noreplication");
            statement.execute("create role stock_book_app_runtime"
                    + " nologin nosuperuser nocreatedb nocreaterole noreplication");
            statement.execute("create role stock_book_finance_approver"
                    + " nologin nosuperuser nocreatedb nocreaterole noreplication");
            statement.execute("create role " + MIGRATION_USER
                    + " login password '" + MIGRATION_PASSWORD + "'"
                    + " nosuperuser nocreatedb nocreaterole noreplication");
            statement.execute("create role " + RUNTIME_USER
                    + " login password '" + RUNTIME_PASSWORD + "'"
                    + " nosuperuser nocreatedb nocreaterole noreplication");
            statement.execute("create role " + APPROVAL_USER
                    + " login password '" + APPROVAL_PASSWORD + "'"
                    + " nosuperuser nocreatedb nocreaterole noreplication");
            statement.execute("grant connect, temporary on database \"" + postgres.getDatabaseName()
                    + "\" to " + MIGRATION_USER + ", " + RUNTIME_USER + ", " + APPROVAL_USER);
            statement.execute("grant stock_book_finance_owner to " + MIGRATION_USER);
            statement.execute("grant stock_book_app_runtime to " + RUNTIME_USER);
            statement.execute("grant stock_book_finance_approver to " + APPROVAL_USER);
            statement.execute("grant usage, create on schema public to " + MIGRATION_USER);
            statement.execute("grant usage, create on schema public to stock_book_finance_owner");
            statement.execute("grant usage on schema public to " + RUNTIME_USER);
            statement.execute("grant usage on schema public to " + APPROVAL_USER);
        }
    }

    private void insertExistingV10Data() throws SQLException {
        try (Connection connection = migrationConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    insert into app_user (
                        id, login_identifier, display_name, password_hash, role, active,
                        session_version, created_at, updated_at, password_changed_at
                    ) values (
                        '00000000-0000-0000-0000-000000000011', 'existing@example.com',
                        'Existing Administrator', '{noop}password', 'ADMIN', true,
                        0, now(), now(), now()
                    );
                    insert into business_location (id, code, name, active, created_at, updated_at)
                    values ('00000000-0000-0000-0000-000000000021', 'EXISTING', 'Existing Location',
                            true, now(), now());
                    insert into warehouse (id, code, name, location_id, active, created_at, updated_at)
                    values
                        ('00000000-0000-0000-0000-000000000031', 'SOURCE', 'Source Warehouse',
                         '00000000-0000-0000-0000-000000000021', true, now(), now()),
                        ('00000000-0000-0000-0000-000000000032', 'DEST', 'Destination Warehouse',
                         '00000000-0000-0000-0000-000000000021', true, now(), now());
                    insert into unit_of_measure (id, code, name)
                    values ('00000000-0000-0000-0000-000000000041', 'PIECE', 'Pieces');
                    insert into product (id, sku, name, base_unit_id, active, created_at, updated_at)
                    values ('00000000-0000-0000-0000-000000000051', 'LEGACY', 'Legacy Product',
                            '00000000-0000-0000-0000-000000000041', true, now(), now());
                    insert into supplier (id, code, name, active, created_at, updated_at)
                    values ('00000000-0000-0000-0000-000000000061', 'LEGACY', 'Legacy Supplier',
                            true, now(), now());
                    insert into inventory_transfer (
                        id, request_id, source_warehouse_id, destination_warehouse_id, product_id,
                        entered_quantity, entered_unit_id, conversion_factor, base_quantity,
                        reason, posted_by, posted_at
                    ) values (
                        '00000000-0000-0000-0000-000000000071',
                        '00000000-0000-0000-0000-000000000072',
                        '00000000-0000-0000-0000-000000000031',
                        '00000000-0000-0000-0000-000000000032',
                        '00000000-0000-0000-0000-000000000051', 2,
                        '00000000-0000-0000-0000-000000000041', 1, 2, 'Legacy transfer',
                        '00000000-0000-0000-0000-000000000011', now()
                    );
                    insert into goods_receipt (
                        id, request_id, supplier_id, warehouse_id, supplier_document_reference,
                        posted_by, posted_at
                    ) values (
                        '00000000-0000-0000-0000-000000000081',
                        '00000000-0000-0000-0000-000000000082',
                        '00000000-0000-0000-0000-000000000061',
                        '00000000-0000-0000-0000-000000000031', 'LEGACY-RECEIPT',
                        '00000000-0000-0000-0000-000000000011', now()
                    );
                    insert into goods_receipt_line (
                        id, receipt_id, line_number, product_id, entered_quantity,
                        entered_unit_id, conversion_factor, base_quantity
                    ) values (
                        '00000000-0000-0000-0000-000000000083',
                        '00000000-0000-0000-0000-000000000081', 1,
                        '00000000-0000-0000-0000-000000000051', 3,
                        '00000000-0000-0000-0000-000000000041', 1, 3
                    );
                    insert into inventory_movement (
                        id, request_id, transfer_id, receipt_line_id, warehouse_id, product_id,
                        movement_type, entered_quantity, entered_unit_id, conversion_factor,
                        base_quantity, reason, posted_by, posted_at
                    ) values
                        ('00000000-0000-0000-0000-000000000091',
                         '00000000-0000-0000-0000-000000000092', null, null,
                         '00000000-0000-0000-0000-000000000031',
                         '00000000-0000-0000-0000-000000000051', 'OPENING', 10,
                         '00000000-0000-0000-0000-000000000041', 1, 10, null,
                         '00000000-0000-0000-0000-000000000011', now()),
                        ('00000000-0000-0000-0000-000000000093', null,
                         '00000000-0000-0000-0000-000000000071', null,
                         '00000000-0000-0000-0000-000000000031',
                         '00000000-0000-0000-0000-000000000051', 'TRANSFER_OUT', 2,
                         '00000000-0000-0000-0000-000000000041', 1, -2, 'Legacy transfer',
                         '00000000-0000-0000-0000-000000000011', now()),
                        ('00000000-0000-0000-0000-000000000094', null,
                         '00000000-0000-0000-0000-000000000071', null,
                         '00000000-0000-0000-0000-000000000032',
                         '00000000-0000-0000-0000-000000000051', 'TRANSFER_IN', 2,
                         '00000000-0000-0000-0000-000000000041', 1, 2, 'Legacy transfer',
                         '00000000-0000-0000-0000-000000000011', now()),
                        ('00000000-0000-0000-0000-000000000095', null, null,
                         '00000000-0000-0000-0000-000000000083',
                         '00000000-0000-0000-0000-000000000031',
                         '00000000-0000-0000-0000-000000000051', 'RECEIPT_IN', 3,
                         '00000000-0000-0000-0000-000000000041', 1, 3, 'Goods receipt',
                         '00000000-0000-0000-0000-000000000011', now());
                    insert into stock_balance (warehouse_id, product_id, base_quantity, updated_at)
                    values
                        ('00000000-0000-0000-0000-000000000031',
                         '00000000-0000-0000-0000-000000000051', 11, now()),
                        ('00000000-0000-0000-0000-000000000032',
                         '00000000-0000-0000-0000-000000000051', 2, now());
                    """);
        }
    }

    private void assertExistingV10DataWasPreserved() throws SQLException {
        try (Connection connection = migrationConnection()) {
            assertThat(queryString(connection,
                            "select display_name from app_user where login_identifier = 'existing@example.com'"))
                    .isEqualTo("Existing Administrator");
            assertThat(queryLong(connection, "select count(*) from inventory_movement")).isEqualTo(4);
            assertThat(queryLong(connection, "select count(*) from inventory_transfer")).isEqualTo(1);
            assertThat(queryLong(connection, "select count(*) from goods_receipt")).isEqualTo(1);
            assertThat(queryLong(connection, "select count(*) from goods_receipt_line")).isEqualTo(1);
            assertThat(queryString(connection,
                            "select s.id::text from supplier s"
                                    + " join party p on p.id = s.party_id"
                                    + " where s.code = 'LEGACY' and p.name = 'Legacy Supplier'"))
                    .isEqualTo("00000000-0000-0000-0000-000000000061");
            assertThat(queryString(connection,
                            "select supplier_id::text from goods_receipt"
                                    + " where id = '00000000-0000-0000-0000-000000000081'"))
                    .isEqualTo("00000000-0000-0000-0000-000000000061");
            assertThat(queryLong(connection,
                            "select count(*) from inventory_movement"
                                    + " where receipt_line_id = '00000000-0000-0000-0000-000000000083'"))
                    .isEqualTo(1);
            assertThat(queryString(connection,
                            "select base_quantity::text from stock_balance"
                                    + " where warehouse_id = '00000000-0000-0000-0000-000000000031'"))
                    .isEqualTo("11");
            assertThat(queryString(connection,
                            "select currency_code from business_finance_settings where singleton"))
                    .isEqualTo("ETB");
            assertThat(queryLong(connection,
                            "select count(*) from owner_funding_contribution"
                                    + " where request_id = '00000000-0000-0000-0000-000000000113'"))
                    .isEqualTo(1);
            assertThat(queryLong(connection,
                            "select count(*) from owner_funding_contribution"
                                    + " where request_id = '00000000-0000-0000-0000-000000000113'"
                                    + " and approval_id is null"))
                    .isEqualTo(1);
        }
    }

    private void insertExistingV13Funding() throws SQLException {
        try (Connection connection = migrationConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    insert into app_user (
                        id, login_identifier, display_name, password_hash, role, active,
                        session_version, created_at, updated_at, password_changed_at
                    ) values (
                        '00000000-0000-0000-0000-000000000013', 'legacy-approver@example.com',
                        'Legacy Funding Approver', '{noop}password', 'STAFF', true,
                        0, now(), now(), now()
                    );
                    insert into finance_user_permission (
                        user_id, permission, granted_by, granted_at
                    ) values
                        ('00000000-0000-0000-0000-000000000011',
                         'OWNER_FUNDING_INITIATE',
                         '00000000-0000-0000-0000-000000000011', now()),
                        ('00000000-0000-0000-0000-000000000013',
                         'OWNER_FUNDING_APPROVE',
                         '00000000-0000-0000-0000-000000000011', now());
                    select finance_create_period(
                        '00000000-0000-0000-0000-000000000111',
                        'Existing V13 funding period', date '2016-01-01', date '2016-12-31'
                    );
                    select finance_post_owner_funding(
                        '00000000-0000-0000-0000-000000000112',
                        '00000000-0000-0000-0000-000000000113',
                        '10000000-0000-0000-0000-000000000001', 50.00, date '2016-06-01',
                        '00000000-0000-0000-0000-000000000011',
                        '00000000-0000-0000-0000-000000000013',
                        '00000000-0000-0000-0000-000000000114'
                    );
                    """);
        }
    }

    private void assertRoleAndFunctionPrivileges() throws SQLException {
        try (Connection connection = adminConnection()) {
            assertThat(queryBoolean(connection,
                            "select not rolcanlogin and not rolsuper and not rolcreaterole"
                                    + " from pg_roles where rolname = 'stock_book_finance_owner'"))
                    .isTrue();
            assertThat(queryBoolean(connection,
                            "select not rolcanlogin and not rolsuper and not rolcreaterole"
                                    + " from pg_roles where rolname = 'stock_book_finance_approver'"))
                    .isTrue();
            assertThat(queryBoolean(connection,
                            "select has_table_privilege('" + RUNTIME_USER
                                    + "', 'public.journal_entry', 'SELECT')"))
                    .isTrue();
            assertThat(queryBoolean(connection,
                            "select has_table_privilege('" + RUNTIME_USER
                                    + "', 'public.party', 'SELECT,INSERT,UPDATE,DELETE')"))
                    .isTrue();
            assertThat(queryBoolean(connection,
                            "select has_table_privilege('" + RUNTIME_USER
                                    + "', 'public.customer', 'SELECT,INSERT,UPDATE,DELETE')"))
                    .isTrue();
            assertThat(queryBoolean(connection,
                            "select has_table_privilege('" + RUNTIME_USER
                                    + "', 'public.journal_entry', 'INSERT,UPDATE,DELETE,TRUNCATE')"))
                    .isFalse();
            assertThat(queryBoolean(connection,
                            "select has_function_privilege('" + RUNTIME_USER
                                    + "', 'public.finance_post_journal(uuid,varchar,uuid,varchar,date,uuid,uuid,jsonb)',"
                                    + " 'EXECUTE')"))
                    .isFalse();
            assertThat(queryBoolean(connection,
                            "select not pg_has_role('" + RUNTIME_USER
                                    + "', 'stock_book_finance_owner', 'MEMBER')"
                                    + " and not pg_has_role('" + RUNTIME_USER
                                    + "', 'stock_book_finance_approver', 'MEMBER')"
                                    + " and not pg_has_role('" + RUNTIME_USER + "', '" + MIGRATION_USER
                                    + "', 'MEMBER')"))
                    .isTrue();
            assertThat(queryBoolean(connection,
                            "select has_function_privilege('" + RUNTIME_USER
                                    + "', 'public.finance_post_owner_funding(uuid,uuid,uuid,numeric,date,uuid,uuid,uuid)',"
                                    + " 'EXECUTE')"))
                    .isTrue();
            assertThat(queryBoolean(connection, """
                    select not exists (
                        select 1
                          from pg_class c,
                               aclexplode(coalesce(c.relacl, acldefault('r', c.relowner))) a
                         where c.relname in (
                             'business_finance_settings', 'finance_account', 'financial_period',
                             'journal_entry', 'journal_entry_line', 'finance_user_permission',
                             'owner_funding_contribution', 'owner_funding_reversal',
                             'owner_funding_approval', 'owner_funding_approval_consumption',
                             'finance_database_principal', 'finance_account_posting_guard'
                         )
                           and a.grantee = 0
                    )
                    """))
                    .isTrue();
            assertThat(queryBoolean(connection, """
                    select not exists (
                        select 1
                          from pg_proc p,
                               aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a
                         where p.proname like 'finance_%'
                           and a.grantee = 0
                           and a.privilege_type = 'EXECUTE'
                    )
                    """))
                    .isTrue();
            assertThat(queryBoolean(connection, """
                    select bool_and('search_path=pg_catalog, pg_temp' = any(proconfig))
                      from pg_proc
                     where proname in (
                         'finance_create_period', 'finance_close_period', 'finance_post_journal',
                         'finance_post_owner_funding', 'finance_reverse_owner_funding',
                         'finance_approve_owner_funding_intent'
                     ) and prosecdef
                    """))
                    .isTrue();
        }
    }

    private void assertRuntimeCannotBypassLedgerProtection() throws SQLException {
        try (Connection connection = runtimeConnection(); var statement = connection.createStatement()) {
            assertThat(queryString(connection,
                            "select currency_code from business_finance_settings where singleton"))
                    .isEqualTo("ETB");
            statement.execute("""
                    insert into auth_login_attempt (
                        key_type, key_value, window_started_at, failure_count, updated_at
                    ) values ('IP', 'runtime-permission-check', now(), 0, now())
                    """);
            statement.execute("delete from auth_login_attempt where key_value = 'runtime-permission-check'");
            statement.execute("select set_config('stock_book.controlled_finance_write', 'posting', false)");
            assertSqlState(() -> statement.execute("""
                    insert into journal_entry (
                        id, period_id, source_type, source_id, posting_purpose,
                        effective_date, recorded_at, posted_by
                    ) values (
                        gen_random_uuid(), gen_random_uuid(), 'SYSTEM', gen_random_uuid(),
                        'OPENING', current_date, now(),
                        '00000000-0000-0000-0000-000000000011'
                    )
                    """), "42501");
            assertSqlState(() -> statement.execute("update journal_entry set effective_date = current_date"),
                    "42501");
            assertSqlState(() -> statement.execute("delete from journal_entry"), "42501");
            assertSqlState(() -> statement.execute("truncate journal_entry_line, journal_entry"), "42501");
            assertSqlState(() -> statement.execute("select finance_create_period("
                    + "gen_random_uuid(), 'Bypass', current_date, current_date)"), "42501");
            assertSqlState(() -> statement.execute("select finance_close_period("
                    + "gen_random_uuid(), '00000000-0000-0000-0000-000000000011')"), "42501");
            assertSqlState(() -> statement.execute("select finance_post_journal("
                    + "gen_random_uuid(), 'SYSTEM', gen_random_uuid(), 'OPENING', current_date,"
                    + " '00000000-0000-0000-0000-000000000011', null, '[]'::jsonb)"), "42501");
            assertSqlState(() -> statement.execute("insert into finance_user_permission ("
                    + "user_id, permission, granted_by, granted_at) values ("
                    + "'00000000-0000-0000-0000-000000000011', 'OWNER_FUNDING_INITIATE', "
                    + "'00000000-0000-0000-0000-000000000011', now())"), "42501");
            assertSqlState(() -> statement.execute("insert into owner_funding_contribution ("
                    + "id, request_id, cash_account_id, amount, effective_date, initiated_by, "
                    + "approved_by, journal_id, posted_at) values (gen_random_uuid(), gen_random_uuid(), "
                    + "'10000000-0000-0000-0000-000000000001', 1, current_date, "
                    + "'00000000-0000-0000-0000-000000000011', "
                    + "'00000000-0000-0000-0000-000000000011', gen_random_uuid(), now())"), "42501");
            assertSqlState(() -> statement.execute("select finance_post_owner_funding("
                    + "gen_random_uuid(), gen_random_uuid(), "
                    + "'10000000-0000-0000-0000-000000000001', 1, current_date, "
                    + "'00000000-0000-0000-0000-000000000011', "
                    + "'00000000-0000-0000-0000-000000000012', gen_random_uuid())"), "23514");
            assertSqlState(() -> statement.execute("select finance_approve_owner_funding_intent("
                    + "gen_random_uuid(), 'FUNDING', gen_random_uuid(), null, "
                    + "'10000000-0000-0000-0000-000000000001', 1, current_date, "
                    + "'00000000-0000-0000-0000-000000000011')"), "42501");
            assertSqlState(() -> statement.execute("set role stock_book_finance_owner"), "42501");
        }
    }

    private void assertRestrictedRuntimeCanPostAuthorizedFundingAndReversal() throws SQLException {
        String initiator = "00000000-0000-0000-0000-000000000011";
        String approver = "00000000-0000-0000-0000-000000000012";
        String periodId = UUID.randomUUID().toString();
        String fundingRequest = UUID.randomUUID().toString();
        String fundingApproval = UUID.randomUUID().toString();
        String fundingId = UUID.randomUUID().toString();
        String fundingJournal = UUID.randomUUID().toString();
        String reversalRequest = UUID.randomUUID().toString();
        String reversalApproval = UUID.randomUUID().toString();
        String reversalId = UUID.randomUUID().toString();
        String reversalJournal = UUID.randomUUID().toString();

        try (Connection connection = migrationConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    insert into app_user (
                        id, login_identifier, display_name, password_hash, role, active,
                        session_version, created_at, updated_at, password_changed_at
                    ) values (
                        '00000000-0000-0000-0000-000000000012', 'approver@example.com',
                        'Funding Approver', '{noop}password', 'STAFF', true,
                        0, now(), now(), now()
                    )
                    """);
            statement.execute("insert into finance_user_permission ("
                    + "user_id, permission, granted_by, granted_at) values "
                    + "('" + initiator + "', 'OWNER_FUNDING_INITIATE', '" + initiator + "', now()),"
                    + "('" + approver + "', 'OWNER_FUNDING_APPROVE', '" + initiator + "', now())"
                    + " on conflict (user_id, permission) do nothing");
            statement.execute("insert into finance_database_principal ("
                    + "database_role, user_id, principal_type) values ('" + APPROVAL_USER + "', '"
                    + approver + "', 'FUNDING_APPROVER')");
            statement.execute("select finance_create_period('" + periodId
                    + "', 'Restricted runtime funding', date '2017-01-01', date '2017-12-31')");
        }

        try (Connection connection = approvalConnection(); var statement = connection.createStatement()) {
            statement.execute("select finance_approve_owner_funding_intent('" + fundingApproval
                    + "', 'FUNDING', '" + fundingRequest + "', null, "
                    + "'10000000-0000-0000-0000-000000000001', 125.40, date '2017-06-01', '"
                    + initiator + "')");
        }
        try (Connection connection = runtimeConnection(); var statement = connection.createStatement()) {
            statement.execute("select finance_post_owner_funding('" + fundingId + "', '"
                    + fundingRequest + "', '10000000-0000-0000-0000-000000000001', 125.40, "
                    + "date '2017-06-01', '" + initiator + "', '" + fundingApproval + "', '"
                    + fundingJournal + "')");
            assertThat(queryLong(connection,
                            "select count(*) from owner_funding_contribution where id = '" + fundingId + "'"))
                    .isEqualTo(1);
        }

        try (Connection connection = approvalConnection(); var statement = connection.createStatement()) {
            statement.execute("select finance_approve_owner_funding_intent('" + reversalApproval
                    + "', 'REVERSAL', '" + reversalRequest + "', '" + fundingId
                    + "', '10000000-0000-0000-0000-000000000001', 125.40, "
                    + "date '2017-06-01', '" + initiator + "')");
        }
        try (Connection connection = runtimeConnection(); var statement = connection.createStatement()) {
            statement.execute("select finance_reverse_owner_funding('" + reversalId + "', '"
                    + reversalRequest + "', '" + fundingId + "', date '2017-06-01', '"
                    + initiator + "', '" + reversalApproval + "', '" + reversalJournal + "')");
            assertThat(queryString(connection,
                            "select debit::text || ':' || credit::text from journal_entry_line"
                                    + " where journal_id = '" + reversalJournal + "'"
                                    + " and account_id = '10000000-0000-0000-0000-000000000001'"))
                    .isEqualTo("0.00:125.40");
        }
    }

    private void assertProductionRuntimeVerifier() {
        var runtimeDataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), RUNTIME_USER, RUNTIME_PASSWORD);
        new FinanceRuntimePrivilegeVerifier(JdbcClient.create(runtimeDataSource), MIGRATION_USER).run(null);

        var migrationDataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), MIGRATION_USER, MIGRATION_PASSWORD);
        assertThatThrownBy(() -> new FinanceRuntimePrivilegeVerifier(
                                JdbcClient.create(migrationDataSource), MIGRATION_USER)
                        .run(null))
                .isInstanceOf(IllegalStateException.class);
    }

    private void assertTemporaryObjectsCannotShadowDefinerReferences() throws SQLException {
        try (Connection connection = migrationConnection(); var statement = connection.createStatement()) {
            statement.execute("create temporary table business_finance_settings (singleton boolean)");
            UUID periodId = UUID.randomUUID();
            statement.execute("select public.finance_create_period('" + periodId
                    + "', 'Shadow-safe period', date '2018-01-01', date '2018-12-31')");
            assertThat(queryLong(connection,
                            "select count(*) from public.financial_period where id = '" + periodId + "'"))
                    .isEqualTo(1);
        }
    }

    private static void assertSqlState(SqlOperation operation, String sqlState) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(SQLException.class)
                .extracting(exception -> ((SQLException) exception).getSQLState())
                .isEqualTo(sqlState);
    }

    private Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private Connection migrationConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), MIGRATION_USER, MIGRATION_PASSWORD);
    }

    private Connection runtimeConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), RUNTIME_USER, RUNTIME_PASSWORD);
    }

    private Connection approvalConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), APPROVAL_USER, APPROVAL_PASSWORD);
    }

    private static String queryString(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static long queryLong(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static boolean queryBoolean(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getBoolean(1);
        }
    }

    @FunctionalInterface
    private interface SqlOperation {
        void run() throws SQLException;
    }
}
