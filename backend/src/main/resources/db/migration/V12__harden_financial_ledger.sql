do $$
begin
    if not exists (select 1 from pg_catalog.pg_roles where rolname = 'stock_book_finance_owner') then
        create role stock_book_finance_owner
            nologin nosuperuser nocreatedb nocreaterole noreplication;
    end if;
    if not exists (select 1 from pg_catalog.pg_roles where rolname = 'stock_book_app_runtime') then
        create role stock_book_app_runtime
            nologin nosuperuser nocreatedb nocreaterole noreplication;
    end if;
end;
$$;

do $$
begin
    if exists (
        select 1 from pg_catalog.pg_roles
         where rolname in ('stock_book_finance_owner', 'stock_book_app_runtime')
           and (rolcanlogin or rolsuper or rolcreatedb or rolcreaterole or rolreplication)
    ) then
        raise exception 'Stock Book finance roles already exist with unsafe attributes';
    end if;
end;
$$;

do $$
begin
    if not pg_has_role(current_user, 'stock_book_finance_owner', 'MEMBER') then
        execute format(
            'grant stock_book_finance_owner to %I',
            current_user
        );
    end if;
end;
$$;

drop trigger journal_entry_write_guard on public.journal_entry;
drop trigger journal_entry_line_write_guard on public.journal_entry_line;

create or replace function public.finance_reject_direct_journal_write()
returns trigger
language plpgsql
set search_path = pg_catalog, pg_temp
as $$
begin
    raise exception 'Posted journals are immutable'
        using errcode = '42501';
end;
$$;

create trigger journal_entry_mutation_guard
before update or delete on public.journal_entry
for each row execute function public.finance_reject_direct_journal_write();

create trigger journal_entry_line_mutation_guard
before update or delete on public.journal_entry_line
for each row execute function public.finance_reject_direct_journal_write();

create trigger journal_entry_truncate_guard
before truncate on public.journal_entry
for each statement execute function public.finance_reject_direct_journal_write();

create trigger journal_entry_line_truncate_guard
before truncate on public.journal_entry_line
for each statement execute function public.finance_reject_direct_journal_write();

create or replace function public.finance_validate_journal()
returns trigger
language plpgsql
set search_path = pg_catalog, pg_temp
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
      from public.journal_entry_line
     where journal_id = v_journal_id;

    if v_line_count < 2 or v_debits <> v_credits then
        raise exception 'Journal % must contain at least two balanced lines', v_journal_id
            using errcode = '23514';
    end if;
    return null;
end;
$$;

create or replace function public.finance_create_period(
    p_id uuid,
    p_name varchar,
    p_starts_on date,
    p_ends_on date
) returns uuid
language plpgsql
security definer
set search_path = pg_catalog, pg_temp
as $$
begin
    perform 1
      from public.business_finance_settings
     where singleton
     for update;

    if p_name is null
       or btrim(p_name) = ''
       or length(p_name) > 100
       or p_starts_on is null
       or p_ends_on is null
       or p_starts_on > p_ends_on then
        raise exception 'Invalid financial period' using errcode = '22023';
    end if;
    if exists (
        select 1
          from public.financial_period
         where daterange(starts_on, ends_on, '[]')
               && daterange(p_starts_on, p_ends_on, '[]')
    ) then
        raise exception 'Financial periods may not overlap' using errcode = '23505';
    end if;

    insert into public.financial_period (
        id, name, starts_on, ends_on, period_state, created_at
    ) values (
        p_id, btrim(p_name), p_starts_on, p_ends_on, 'OPEN', clock_timestamp()
    );
    return p_id;
end;
$$;

create or replace function public.finance_close_period(p_id uuid, p_actor uuid)
returns uuid
language plpgsql
security definer
set search_path = pg_catalog, pg_temp
as $$
declare
    v_state varchar(10);
begin
    if not exists (
        select 1 from public.app_user
         where id = p_actor and active and role = 'ADMIN'
    ) then
        raise exception 'An active administrator is required to close a financial period'
            using errcode = '42501';
    end if;

    select period_state
      into v_state
      from public.financial_period
     where id = p_id
     for update;
    if not found then
        raise exception 'Financial period not found' using errcode = 'P0002';
    end if;
    if v_state <> 'OPEN' then
        raise exception 'Financial period is already closed' using errcode = '23514';
    end if;

    update public.financial_period
       set period_state = 'CLOSED', closed_at = clock_timestamp(), closed_by = p_actor
     where id = p_id;
    return p_id;
end;
$$;

create or replace function public.finance_post_journal(
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
set search_path = pg_catalog, pg_temp
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
    v_original public.journal_entry%rowtype;
begin
    if not exists (
        select 1 from public.app_user
         where id = p_actor and active and role = 'ADMIN'
    ) then
        raise exception 'An active administrator is required to post a journal'
            using errcode = '42501';
    end if;

    select timezone_name
      into v_timezone
      from public.business_finance_settings
     where singleton;
    if p_effective_date is null
       or p_effective_date > (v_recorded_at at time zone v_timezone)::date then
        raise exception 'Future-effective journals are not permitted' using errcode = '22023';
    end if;

    select id
      into v_period_id
      from public.financial_period
     where period_state = 'OPEN'
       and p_effective_date between starts_on and ends_on
     for share;
    if not found then
        raise exception 'No open financial period contains the effective date' using errcode = '23514';
    end if;

    if p_lines is null
       or jsonb_typeof(p_lines) <> 'array'
       or jsonb_array_length(p_lines) < 2 then
        raise exception 'A journal requires at least two lines' using errcode = '22023';
    end if;

    if p_posting_purpose = 'REVERSAL' then
        if p_reverses_journal_id is null then
            raise exception 'A reversal must identify its original journal' using errcode = '22023';
        end if;
        select *
          into v_original
          from public.journal_entry
         where id = p_reverses_journal_id
         for update;
        if not found then
            raise exception 'Reversed journal not found' using errcode = '23503';
        end if;
        if v_original.posting_purpose <> 'OPENING'
           or v_original.reverses_journal_id is not null
           or p_source_type <> v_original.source_type
           or p_source_id <> v_original.id
           or p_effective_date < v_original.effective_date then
            raise exception 'The original journal is not eligible for this reversal'
                using errcode = '23514';
        end if;
        if exists (
            select 1 from public.journal_entry
             where reverses_journal_id = v_original.id
        ) then
            raise exception 'The original journal has already been reversed'
                using errcode = '23505';
        end if;
    elsif p_source_type <> 'SYSTEM'
          or p_posting_purpose <> 'OPENING'
          or p_source_id <> v_period_id
          or p_reverses_journal_id is not null then
        raise exception 'No authorized business posting source exists for this journal'
            using errcode = '42501';
    end if;

    insert into public.journal_entry (
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
           or v_debit <> trunc(v_debit, 2)
           or v_credit <> trunc(v_credit, 2) then
            raise exception 'Journal amounts must be positive, one-sided, and have at most two decimals'
                using errcode = '22023';
        end if;
        if not exists (
            select 1 from public.finance_account
             where id = v_account_id and active and posting_allowed
        ) then
            raise exception 'Journal account is not active and posting-enabled' using errcode = '23514';
        end if;

        insert into public.journal_entry_line (
            journal_id, line_number, account_id, debit, credit
        ) values (
            p_id, v_line_number, v_account_id, v_debit, v_credit
        );
        v_debit_total := v_debit_total + v_debit;
        v_credit_total := v_credit_total + v_credit;
    end loop;

    if v_debit_total <> v_credit_total then
        raise exception 'Journal debits and credits must balance' using errcode = '23514';
    end if;

    if p_posting_purpose = 'REVERSAL' and exists (
        with original as (
            select account_id, sum(debit) as debit, sum(credit) as credit
              from public.journal_entry_line
             where journal_id = v_original.id
             group by account_id
        ), reversal as (
            select account_id, sum(debit) as debit, sum(credit) as credit
              from public.journal_entry_line
             where journal_id = p_id
             group by account_id
        )
        select 1
          from original
          full join reversal using (account_id)
         where coalesce(reversal.debit, 0) <> coalesce(original.credit, 0)
            or coalesce(reversal.credit, 0) <> coalesce(original.debit, 0)
    ) then
        raise exception 'A reversal must exactly invert every original account effect'
            using errcode = '23514';
    end if;

    return p_id;
end;
$$;

revoke all on table public.business_finance_settings, public.finance_account,
    public.financial_period, public.journal_entry, public.journal_entry_line from public;
revoke all on table public.business_finance_settings, public.finance_account,
    public.financial_period, public.journal_entry, public.journal_entry_line
    from stock_book_app_runtime;

revoke all on function public.finance_reject_direct_journal_write() from public;
revoke all on function public.finance_validate_journal() from public;
revoke all on function public.finance_create_period(uuid, varchar, date, date) from public;
revoke all on function public.finance_close_period(uuid, uuid) from public;
revoke all on function public.finance_post_journal(
    uuid, varchar, uuid, varchar, date, uuid, uuid, jsonb
) from public;

revoke all on function public.finance_reject_direct_journal_write()
    from stock_book_app_runtime;
revoke all on function public.finance_validate_journal()
    from stock_book_app_runtime;
revoke all on function public.finance_create_period(uuid, varchar, date, date)
    from stock_book_app_runtime;
revoke all on function public.finance_close_period(uuid, uuid)
    from stock_book_app_runtime;
revoke all on function public.finance_post_journal(
    uuid, varchar, uuid, varchar, date, uuid, uuid, jsonb
) from stock_book_app_runtime;

do $$
begin
    if not has_schema_privilege('stock_book_finance_owner', 'public', 'CREATE') then
        grant usage, create on schema public to stock_book_finance_owner;
    end if;
end;
$$;

alter table public.business_finance_settings owner to stock_book_finance_owner;
alter table public.finance_account owner to stock_book_finance_owner;
alter table public.financial_period owner to stock_book_finance_owner;
alter table public.journal_entry owner to stock_book_finance_owner;
alter table public.journal_entry_line owner to stock_book_finance_owner;

alter function public.finance_reject_direct_journal_write()
    owner to stock_book_finance_owner;
alter function public.finance_validate_journal()
    owner to stock_book_finance_owner;
alter function public.finance_create_period(uuid, varchar, date, date)
    owner to stock_book_finance_owner;
alter function public.finance_close_period(uuid, uuid)
    owner to stock_book_finance_owner;
alter function public.finance_post_journal(
    uuid, varchar, uuid, varchar, date, uuid, uuid, jsonb
) owner to stock_book_finance_owner;

grant select on table public.business_finance_settings, public.finance_account,
    public.financial_period, public.journal_entry, public.journal_entry_line
    to stock_book_app_runtime;

grant select, insert, update, delete on table
    public.app_user,
    public.auth_login_attempt,
    public.spring_session,
    public.spring_session_attributes,
    public.business_location,
    public.warehouse,
    public.unit_of_measure,
    public.product,
    public.product_unit_conversion,
    public.stock_balance,
    public.inventory_movement,
    public.inventory_transfer,
    public.supplier,
    public.goods_receipt,
    public.goods_receipt_line
    to stock_book_app_runtime;

grant select on table public.app_user to stock_book_finance_owner;
