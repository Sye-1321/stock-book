create table business_finance_settings (
    singleton boolean primary key default true check (singleton),
    currency_code varchar(3) not null,
    timezone_name varchar(100) not null,
    constraint business_finance_currency_format check (currency_code ~ '^[A-Z]{3}$'),
    constraint business_finance_timezone_not_blank check (btrim(timezone_name) <> '')
);

insert into business_finance_settings (singleton, currency_code, timezone_name)
values (true, 'ETB', 'Africa/Addis_Ababa');

create table finance_account (
    id uuid primary key,
    code varchar(20) not null unique,
    name varchar(100) not null,
    account_role varchar(50) not null unique,
    account_type varchar(20) not null,
    active boolean not null default true,
    posting_allowed boolean not null,
    constraint finance_account_code_format check (code ~ '^[0-9]{4}$'),
    constraint finance_account_name_not_blank check (btrim(name) <> ''),
    constraint finance_account_type check (
        account_type in ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')
    )
);

insert into finance_account (id, code, name, account_role, account_type, posting_allowed) values
    ('10000000-0000-0000-0000-000000000001', '1000', 'Cash', 'CASH', 'ASSET', true),
    ('10000000-0000-0000-0000-000000000002', '1100', 'Accounts Receivable', 'ACCOUNTS_RECEIVABLE', 'ASSET', true),
    ('10000000-0000-0000-0000-000000000003', '1200', 'Inventory', 'INVENTORY', 'ASSET', true),
    ('10000000-0000-0000-0000-000000000004', '2000', 'Accounts Payable', 'ACCOUNTS_PAYABLE', 'LIABILITY', true),
    ('10000000-0000-0000-0000-000000000005', '3000', 'Retained Earnings', 'RETAINED_EARNINGS', 'EQUITY', true),
    ('10000000-0000-0000-0000-000000000006', '4000', 'Sales Revenue', 'SALES_REVENUE', 'REVENUE', true),
    ('10000000-0000-0000-0000-000000000007', '5000', 'Cost of Goods Sold', 'COST_OF_GOODS_SOLD', 'EXPENSE', true),
    ('10000000-0000-0000-0000-000000000008', '6000', 'Operating Expense', 'OPERATING_EXPENSE', 'EXPENSE', true);

create table financial_period (
    id uuid primary key,
    name varchar(100) not null unique,
    starts_on date not null,
    ends_on date not null,
    period_state varchar(10) not null,
    created_at timestamptz not null,
    closed_at timestamptz,
    closed_by uuid references app_user (id),
    constraint financial_period_name_not_blank check (btrim(name) <> ''),
    constraint financial_period_dates check (starts_on <= ends_on),
    constraint financial_period_state check (period_state in ('OPEN', 'CLOSED')),
    constraint financial_period_closure check (
        (period_state = 'OPEN' and closed_at is null and closed_by is null)
        or (period_state = 'CLOSED' and closed_at is not null and closed_by is not null)
    )
);

create index financial_period_date_idx on financial_period (starts_on, ends_on);

create table journal_entry (
    id uuid primary key,
    period_id uuid not null references financial_period (id),
    source_type varchar(30) not null,
    source_id uuid not null,
    posting_purpose varchar(40) not null,
    effective_date date not null,
    recorded_at timestamptz not null,
    posted_by uuid not null references app_user (id),
    reverses_journal_id uuid unique references journal_entry (id),
    constraint journal_source_type check (
        source_type in ('PURCHASE', 'SALE', 'PAYMENT', 'EXPENSE', 'GOODS_RECEIPT', 'SYSTEM')
    ),
    constraint journal_posting_purpose check (
        posting_purpose in (
            'INVENTORY_RECEIPT', 'SALE', 'CUSTOMER_PAYMENT', 'SUPPLIER_PAYMENT',
            'EXPENSE', 'OPENING', 'REVERSAL'
        )
    ),
    constraint journal_reversal_link check (
        reverses_journal_id is null or reverses_journal_id <> id
    ),
    constraint journal_reversal_purpose check (
        (posting_purpose = 'REVERSAL') = (reverses_journal_id is not null)
    ),
    unique (source_type, source_id, posting_purpose)
);

create index journal_entry_effective_date_idx on journal_entry (effective_date, id);
create index journal_entry_period_idx on journal_entry (period_id);

create table journal_entry_line (
    journal_id uuid not null references journal_entry (id),
    line_number integer not null,
    account_id uuid not null references finance_account (id),
    debit numeric(20,2) not null default 0,
    credit numeric(20,2) not null default 0,
    primary key (journal_id, line_number),
    constraint journal_line_number_positive check (line_number > 0),
    constraint journal_line_one_sided_positive check (
        (debit > 0 and credit = 0) or (credit > 0 and debit = 0)
    )
);

create index journal_entry_line_account_idx on journal_entry_line (account_id, journal_id);

create function finance_reject_direct_journal_write()
returns trigger
language plpgsql
as $$
begin
    if tg_op <> 'INSERT'
       or coalesce(current_setting('stock_book.controlled_finance_write', true), '') <> 'posting' then
        raise exception 'Posted journals are immutable and may only be created through the controlled posting function'
            using errcode = '42501';
    end if;
    return new;
end;
$$;

create trigger journal_entry_write_guard
before insert or update or delete on journal_entry
for each row execute function finance_reject_direct_journal_write();

create trigger journal_entry_line_write_guard
before insert or update or delete on journal_entry_line
for each row execute function finance_reject_direct_journal_write();

create function finance_validate_journal()
returns trigger
language plpgsql
as $$
declare
    v_journal_id uuid;
    v_line_count integer;
    v_debits numeric(20,2);
    v_credits numeric(20,2);
begin
    if tg_table_name = 'journal_entry' then
        v_journal_id := coalesce(new.id, old.id);
    else
        v_journal_id := coalesce(new.journal_id, old.journal_id);
    end if;
    select count(*), coalesce(sum(debit), 0), coalesce(sum(credit), 0)
      into v_line_count, v_debits, v_credits
      from journal_entry_line
     where journal_id = v_journal_id;

    if v_line_count < 2 or v_debits <> v_credits then
        raise exception 'Journal % must contain at least two balanced lines', v_journal_id
            using errcode = '23514';
    end if;
    return null;
end;
$$;

create constraint trigger journal_entry_balance_guard
after insert on journal_entry
deferrable initially deferred
for each row execute function finance_validate_journal();

create constraint trigger journal_entry_line_balance_guard
after insert or update or delete on journal_entry_line
deferrable initially deferred
for each row execute function finance_validate_journal();

create function finance_create_period(
    p_id uuid,
    p_name varchar,
    p_starts_on date,
    p_ends_on date
) returns uuid
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
    perform 1 from business_finance_settings where singleton for update;
    if p_name is null or btrim(p_name) = '' or length(p_name) > 100 or p_starts_on > p_ends_on then
        raise exception 'Invalid financial period' using errcode = '22023';
    end if;
    if exists (
        select 1 from financial_period
         where daterange(starts_on, ends_on, '[]') && daterange(p_starts_on, p_ends_on, '[]')
    ) then
        raise exception 'Financial periods may not overlap' using errcode = '23505';
    end if;
    insert into financial_period (id, name, starts_on, ends_on, period_state, created_at)
    values (p_id, btrim(p_name), p_starts_on, p_ends_on, 'OPEN', clock_timestamp());
    return p_id;
end;
$$;

create function finance_close_period(p_id uuid, p_actor uuid)
returns uuid
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    v_state varchar(10);
begin
    select period_state into v_state from financial_period where id = p_id for update;
    if not found then
        raise exception 'Financial period not found' using errcode = 'P0002';
    end if;
    if v_state <> 'OPEN' then
        raise exception 'Financial period is already closed' using errcode = '23514';
    end if;
    update financial_period
       set period_state = 'CLOSED', closed_at = clock_timestamp(), closed_by = p_actor
     where id = p_id;
    return p_id;
end;
$$;

create function finance_post_journal(
    p_id uuid,
    p_source_type varchar,
    p_source_id uuid,
    p_posting_purpose varchar,
    p_effective_date date,
    p_actor uuid,
    p_reverses_journal_id uuid,
    p_lines jsonb
) returns uuid
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    v_period_id uuid;
    v_timezone varchar(100);
    v_line jsonb;
    v_account_id uuid;
    v_debit numeric;
    v_credit numeric;
    v_debit_total numeric := 0;
    v_credit_total numeric := 0;
    v_line_number integer := 0;
    v_recorded_at timestamptz := clock_timestamp();
begin
    select timezone_name into v_timezone from business_finance_settings where singleton;
    if p_effective_date > (v_recorded_at at time zone v_timezone)::date then
        raise exception 'Future-effective journals are not permitted' using errcode = '22023';
    end if;

    select id into v_period_id
      from financial_period
     where period_state = 'OPEN'
       and p_effective_date between starts_on and ends_on
     for share;
    if not found then
        raise exception 'No open financial period contains the effective date' using errcode = '23514';
    end if;

    if p_lines is null or jsonb_typeof(p_lines) <> 'array' or jsonb_array_length(p_lines) < 2 then
        raise exception 'A journal requires at least two lines' using errcode = '22023';
    end if;

    if p_reverses_journal_id is not null and not exists (
        select 1 from journal_entry where id = p_reverses_journal_id
    ) then
        raise exception 'Reversed journal not found' using errcode = '23503';
    end if;

    perform set_config('stock_book.controlled_finance_write', 'posting', true);
    insert into journal_entry (
        id, period_id, source_type, source_id, posting_purpose, effective_date,
        recorded_at, posted_by, reverses_journal_id
    ) values (
        p_id, v_period_id, p_source_type, p_source_id, p_posting_purpose, p_effective_date,
        v_recorded_at, p_actor, p_reverses_journal_id
    );

    for v_line in select value from jsonb_array_elements(p_lines)
    loop
        v_line_number := v_line_number + 1;
        begin
            v_account_id := (v_line ->> 'accountId')::uuid;
            v_debit := (v_line ->> 'debit')::numeric;
            v_credit := (v_line ->> 'credit')::numeric;
        exception when others then
            raise exception 'Invalid journal line' using errcode = '22023';
        end;

        if ((v_debit > 0 and v_credit = 0) or (v_credit > 0 and v_debit = 0)) is not true
           or v_debit <> trunc(v_debit, 2) or v_credit <> trunc(v_credit, 2) then
            raise exception 'Journal amounts must be positive, one-sided, and have at most two decimals'
                using errcode = '22023';
        end if;
        if not exists (
            select 1 from finance_account
             where id = v_account_id and active and posting_allowed
        ) then
            raise exception 'Journal account is not active and posting-enabled' using errcode = '23514';
        end if;

        insert into journal_entry_line (journal_id, line_number, account_id, debit, credit)
        values (p_id, v_line_number, v_account_id, v_debit, v_credit);
        v_debit_total := v_debit_total + v_debit;
        v_credit_total := v_credit_total + v_credit;
    end loop;

    if v_debit_total <> v_credit_total then
        raise exception 'Journal debits and credits must balance' using errcode = '23514';
    end if;
    perform set_config('stock_book.controlled_finance_write', '', true);
    return p_id;
end;
$$;

revoke all on business_finance_settings, finance_account, financial_period,
    journal_entry, journal_entry_line from public;
grant select on business_finance_settings, finance_account, financial_period,
    journal_entry, journal_entry_line to public;

revoke all on function finance_create_period(uuid, varchar, date, date) from public;
revoke all on function finance_close_period(uuid, uuid) from public;
revoke all on function finance_post_journal(
    uuid, varchar, uuid, varchar, date, uuid, uuid, jsonb
) from public;
grant execute on function finance_create_period(uuid, varchar, date, date) to public;
grant execute on function finance_close_period(uuid, uuid) to public;
grant execute on function finance_post_journal(
    uuid, varchar, uuid, varchar, date, uuid, uuid, jsonb
) to public;
