do $$
begin
    if not exists (
        select 1 from pg_catalog.pg_roles
         where rolname = 'stock_book_finance_approver'
    ) then
        create role stock_book_finance_approver
            nologin nosuperuser nocreatedb nocreaterole noreplication;
    end if;
end;
$$;

do $$
begin
    if exists (
        select 1 from pg_catalog.pg_roles
         where rolname = 'stock_book_finance_approver'
           and (rolcanlogin or rolsuper or rolcreatedb or rolcreaterole or rolreplication)
    ) then
        raise exception 'The Stock Book finance approver role has unsafe attributes';
    end if;
end;
$$;

create table public.finance_database_principal (
    database_role varchar(63) primary key,
    user_id uuid not null unique references public.app_user (id),
    principal_type varchar(20) not null,
    constraint finance_database_principal_type check (principal_type = 'FUNDING_APPROVER')
);

create table public.owner_funding_approval (
    id uuid primary key,
    operation varchar(20) not null,
    request_id uuid not null,
    contribution_id uuid references public.owner_funding_contribution (id),
    cash_account_id uuid not null references public.finance_account (id),
    amount numeric(20,2) not null check (amount > 0),
    effective_date date not null,
    initiated_by uuid not null references public.app_user (id),
    approved_by uuid not null references public.app_user (id),
    approved_at timestamptz not null,
    unique (operation, request_id),
    constraint owner_funding_approval_operation check (
        operation in ('FUNDING', 'REVERSAL')
    ),
    constraint owner_funding_approval_document check (
        (operation = 'FUNDING' and contribution_id is null)
        or (operation = 'REVERSAL' and contribution_id is not null)
    ),
    constraint owner_funding_approval_distinct_actor check (initiated_by <> approved_by)
);

create table public.owner_funding_approval_consumption (
    approval_id uuid primary key references public.owner_funding_approval (id),
    request_id uuid not null unique,
    consumed_at timestamptz not null
);

alter table public.owner_funding_contribution
    add column approval_id uuid unique references public.owner_funding_approval (id);
alter table public.owner_funding_reversal
    add column approval_id uuid unique references public.owner_funding_approval (id);

create table public.finance_account_posting_guard (
    account_id uuid primary key references public.finance_account (id),
    latest_effective_date date not null,
    latest_journal_id uuid not null references public.journal_entry (id)
        deferrable initially deferred
);

insert into public.finance_account_posting_guard (
    account_id, latest_effective_date, latest_journal_id
)
select distinct on (l.account_id)
       l.account_id, j.effective_date, j.id
  from public.journal_entry_line l
  join public.journal_entry j on j.id = l.journal_id
  join public.finance_account a on a.id = l.account_id
 where a.account_role in ('CASH', 'BANK')
 order by l.account_id, j.effective_date desc, j.recorded_at desc, j.id desc;

set constraints finance_account_posting_guard_latest_journal_id_fkey immediate;

create trigger owner_funding_approval_mutation_guard
before update or delete or truncate on public.owner_funding_approval
for each statement execute function public.finance_reject_funding_mutation();

create trigger owner_funding_approval_consumption_mutation_guard
before update or delete or truncate on public.owner_funding_approval_consumption
for each statement execute function public.finance_reject_funding_mutation();

create function public.finance_guard_protected_account(
    p_account_id uuid,
    p_effective_date date,
    p_journal_id uuid
) returns void
language plpgsql
set search_path = pg_catalog, pg_temp
as $$
declare
    v_role varchar(50);
    v_latest date;
begin
    select account_role into v_role
      from public.finance_account
     where id = p_account_id
     for update;
    if not found then
        raise exception 'Financial account not found' using errcode = '23503';
    end if;
    if v_role not in ('CASH', 'BANK') then
        return;
    end if;

    select latest_effective_date into v_latest
      from public.finance_account_posting_guard
     where account_id = p_account_id
     for update;
    if found and p_effective_date < v_latest then
        raise exception 'A protected account cannot be posted before its latest effective date'
            using errcode = '23514';
    end if;

    insert into public.finance_account_posting_guard (
        account_id, latest_effective_date, latest_journal_id
    ) values (
        p_account_id, p_effective_date, p_journal_id
    )
    on conflict (account_id) do update
       set latest_effective_date = greatest(
               public.finance_account_posting_guard.latest_effective_date,
               excluded.latest_effective_date
           ),
           latest_journal_id = case
               when excluded.latest_effective_date
                    >= public.finance_account_posting_guard.latest_effective_date
               then excluded.latest_journal_id
               else public.finance_account_posting_guard.latest_journal_id
           end;
end;
$$;

create or replace function public.finance_lock_journal_account()
returns trigger
language plpgsql
set search_path = pg_catalog, pg_temp
as $$
declare
    v_effective_date date;
begin
    select effective_date into v_effective_date
      from public.journal_entry where id = new.journal_id;
    perform public.finance_guard_protected_account(
        new.account_id, v_effective_date, new.journal_id
    );
    return new;
end;
$$;

create function public.finance_approve_owner_funding_intent(
    p_id uuid,
    p_operation varchar,
    p_request_id uuid,
    p_contribution_id uuid,
    p_cash_account_id uuid,
    p_amount numeric,
    p_effective_date date,
    p_initiator uuid
) returns uuid
language plpgsql
security definer
set search_path = pg_catalog, pg_temp
as $$
declare
    v_approver uuid;
    v_funding public.owner_funding_contribution%rowtype;
begin
    select user_id into v_approver
      from public.finance_database_principal
     where database_role = session_user
       and principal_type = 'FUNDING_APPROVER';
    if not found then
        raise exception 'The database principal is not an authorized funding approver'
            using errcode = '42501';
    end if;
    if p_id is null or p_request_id is null or p_cash_account_id is null
       or p_effective_date is null or p_initiator is null
       or p_operation not in ('FUNDING', 'REVERSAL')
       or p_amount is null or p_amount <= 0 or p_amount <> trunc(p_amount, 2)
       or p_initiator = v_approver then
        raise exception 'A complete exact intent with distinct actors is required'
            using errcode = '22023';
    end if;
    if not exists (
        select 1
          from public.app_user u
          join public.finance_user_permission p on p.user_id = u.id
         where u.id = p_initiator and u.active
           and p.permission = 'OWNER_FUNDING_INITIATE'
    ) or not exists (
        select 1
          from public.app_user u
          join public.finance_user_permission p on p.user_id = u.id
         where u.id = v_approver and u.active
           and p.permission = 'OWNER_FUNDING_APPROVE'
    ) then
        raise exception 'Current funding initiation and approval permissions are required'
            using errcode = '42501';
    end if;
    if not exists (
        select 1 from public.finance_account
         where id = p_cash_account_id and account_role in ('CASH', 'BANK')
           and account_type = 'ASSET' and active and posting_allowed
    ) then
        raise exception 'Funding approval requires an active cash or bank account'
            using errcode = '23514';
    end if;

    if p_operation = 'FUNDING' and p_contribution_id is not null then
        raise exception 'A funding intent cannot identify an existing contribution'
            using errcode = '22023';
    elsif p_operation = 'REVERSAL' then
        select * into v_funding
          from public.owner_funding_contribution
         where id = p_contribution_id;
        if not found
           or v_funding.cash_account_id <> p_cash_account_id
           or v_funding.amount <> p_amount
           or p_effective_date < v_funding.effective_date then
            raise exception 'The reversal intent must exactly identify eligible funding'
                using errcode = '23514';
        end if;
    end if;

    insert into public.owner_funding_approval (
        id, operation, request_id, contribution_id, cash_account_id, amount,
        effective_date, initiated_by, approved_by, approved_at
    ) values (
        p_id, p_operation, p_request_id, p_contribution_id, p_cash_account_id,
        p_amount, p_effective_date, p_initiator, v_approver, clock_timestamp()
    );
    return p_id;
end;
$$;

create or replace function public.finance_post_owner_funding(
    p_id uuid,
    p_request_id uuid,
    p_cash_account_id uuid,
    p_amount numeric,
    p_effective_date date,
    p_initiator uuid,
    p_approver uuid,
    p_journal_id uuid
) returns uuid
language plpgsql
security definer
set search_path = pg_catalog, pg_temp
as $$
declare
    v_existing public.owner_funding_contribution%rowtype;
    v_approval public.owner_funding_approval%rowtype;
    v_capital_account_id uuid;
    v_period_id uuid;
    v_timezone varchar(100);
    v_recorded_at timestamptz := clock_timestamp();
begin
    if p_id is null or p_request_id is null or p_cash_account_id is null
       or p_effective_date is null or p_initiator is null or p_approver is null
       or p_journal_id is null or p_amount is null or p_amount <= 0
       or p_amount <> trunc(p_amount, 2) then
        raise exception 'Complete positive owner funding and approval are required'
            using errcode = '22023';
    end if;

    perform pg_advisory_xact_lock(hashtextextended(p_request_id::text, 14001));
    select * into v_existing from public.owner_funding_contribution
     where request_id = p_request_id;

    if found then
        select j.period_id into v_period_id from public.journal_entry j
         where j.id = v_existing.journal_id;
        perform 1 from public.financial_period where id = v_period_id for share;
        select * into v_existing from public.owner_funding_contribution
         where id = v_existing.id for update;
    else
        select timezone_name into v_timezone
          from public.business_finance_settings where singleton;
        if p_effective_date > (v_recorded_at at time zone v_timezone)::date then
            raise exception 'Future-effective owner funding is not permitted' using errcode = '22023';
        end if;
        select id into v_period_id from public.financial_period
         where period_state = 'OPEN'
           and p_effective_date between starts_on and ends_on
         for share;
        if not found then
            raise exception 'No open financial period contains the effective date'
                using errcode = '23514';
        end if;
    end if;

    select * into v_approval from public.owner_funding_approval
     where id = p_approver for update;
    if not found
       or v_approval.operation <> 'FUNDING'
       or v_approval.request_id <> p_request_id
       or v_approval.contribution_id is not null
       or v_approval.cash_account_id <> p_cash_account_id
       or v_approval.amount <> p_amount
       or v_approval.effective_date <> p_effective_date
       or v_approval.initiated_by <> p_initiator then
        raise exception 'Funding approval does not match the exact posting intent'
            using errcode = '42501';
    end if;
    if not exists (
        select 1 from public.app_user u
        join public.finance_user_permission p on p.user_id = u.id
         where u.id = p_initiator and u.active
           and p.permission = 'OWNER_FUNDING_INITIATE'
    ) or not exists (
        select 1 from public.app_user u
        join public.finance_user_permission p on p.user_id = u.id
         where u.id = v_approval.approved_by and u.active
           and p.permission = 'OWNER_FUNDING_APPROVE'
    ) or p_initiator = v_approval.approved_by then
        raise exception 'Current distinct funding authorization is required'
            using errcode = '42501';
    end if;

    if v_existing.id is not null then
        if v_existing.cash_account_id = p_cash_account_id
           and v_existing.amount = p_amount
           and v_existing.effective_date = p_effective_date
           and v_existing.initiated_by = p_initiator
           and v_existing.approved_by = v_approval.approved_by
           and v_existing.approval_id = p_approver
           and exists (
               select 1 from public.owner_funding_approval_consumption
                where approval_id = p_approver and request_id = p_request_id
           ) then
            return v_existing.journal_id;
        end if;
        raise exception 'The request ID is already used by different funding'
            using errcode = '23505';
    end if;
    if exists (select 1 from public.owner_funding_approval_consumption
                where approval_id = p_approver) then
        raise exception 'The funding approval has already been consumed'
            using errcode = '23505';
    end if;

    select id into v_capital_account_id from public.finance_account
     where account_role = 'OWNER_CAPITAL' and account_type = 'EQUITY'
       and active and posting_allowed for share;
    if not found then
        raise exception 'The owner capital account is unavailable' using errcode = '23514';
    end if;
    perform public.finance_guard_protected_account(
        p_cash_account_id, p_effective_date, p_journal_id
    );

    insert into public.journal_entry (
        id, period_id, source_type, source_id, posting_purpose, effective_date,
        recorded_at, posted_by, reverses_journal_id
    ) values (
        p_journal_id, v_period_id, 'OWNER_FUNDING', p_id, 'OWNER_FUNDING',
        p_effective_date, v_recorded_at, p_initiator, null
    );
    insert into public.journal_entry_line (
        journal_id, line_number, account_id, debit, credit
    ) values
        (p_journal_id, 1, p_cash_account_id, p_amount, 0),
        (p_journal_id, 2, v_capital_account_id, 0, p_amount);
    insert into public.owner_funding_contribution (
        id, request_id, cash_account_id, amount, effective_date,
        initiated_by, approved_by, journal_id, posted_at, approval_id
    ) values (
        p_id, p_request_id, p_cash_account_id, p_amount, p_effective_date,
        p_initiator, v_approval.approved_by, p_journal_id, v_recorded_at, p_approver
    );
    insert into public.owner_funding_approval_consumption (
        approval_id, request_id, consumed_at
    ) values (p_approver, p_request_id, v_recorded_at);
    return p_journal_id;
end;
$$;

create or replace function public.finance_reverse_owner_funding(
    p_id uuid,
    p_request_id uuid,
    p_contribution_id uuid,
    p_effective_date date,
    p_initiator uuid,
    p_approver uuid,
    p_journal_id uuid
) returns uuid
language plpgsql
security definer
set search_path = pg_catalog, pg_temp
as $$
declare
    v_existing public.owner_funding_reversal%rowtype;
    v_funding public.owner_funding_contribution%rowtype;
    v_approval public.owner_funding_approval%rowtype;
    v_original public.journal_entry%rowtype;
    v_period_id uuid;
    v_timezone varchar(100);
    v_recorded_at timestamptz := clock_timestamp();
begin
    if p_id is null or p_request_id is null or p_contribution_id is null
       or p_effective_date is null or p_initiator is null or p_approver is null
       or p_journal_id is null then
        raise exception 'Complete owner funding reversal and approval are required'
            using errcode = '22023';
    end if;

    perform pg_advisory_xact_lock(hashtextextended(p_request_id::text, 14002));
    select * into v_existing from public.owner_funding_reversal
     where request_id = p_request_id;
    if found then
        select j.period_id into v_period_id from public.journal_entry j
         where j.id = v_existing.journal_id;
        perform 1 from public.financial_period where id = v_period_id for share;
        select * into v_existing from public.owner_funding_reversal
         where id = v_existing.id for update;
    else
        select timezone_name into v_timezone from public.business_finance_settings
         where singleton;
        if p_effective_date > (v_recorded_at at time zone v_timezone)::date then
            raise exception 'Future-effective funding reversals are not permitted'
                using errcode = '22023';
        end if;
        select id into v_period_id from public.financial_period
         where period_state = 'OPEN'
           and p_effective_date between starts_on and ends_on
         for share;
        if not found then
            raise exception 'No open financial period contains the reversal date'
                using errcode = '23514';
        end if;
    end if;

    select * into v_approval from public.owner_funding_approval
     where id = p_approver for update;
    select * into v_funding from public.owner_funding_contribution
     where id = p_contribution_id for update;
    if not found then
        raise exception 'Owner funding contribution not found' using errcode = 'P0002';
    end if;
    if v_approval.id is null
       or v_approval.operation <> 'REVERSAL'
       or v_approval.request_id <> p_request_id
       or v_approval.contribution_id <> p_contribution_id
       or v_approval.cash_account_id <> v_funding.cash_account_id
       or v_approval.amount <> v_funding.amount
       or v_approval.effective_date <> p_effective_date
       or v_approval.initiated_by <> p_initiator then
        raise exception 'Reversal approval does not match the exact original funding intent'
            using errcode = '42501';
    end if;
    if p_effective_date < v_funding.effective_date
       or not exists (
           select 1 from public.app_user u
           join public.finance_user_permission p on p.user_id = u.id
            where u.id = p_initiator and u.active
              and p.permission = 'OWNER_FUNDING_INITIATE'
       ) or not exists (
           select 1 from public.app_user u
           join public.finance_user_permission p on p.user_id = u.id
            where u.id = v_approval.approved_by and u.active
              and p.permission = 'OWNER_FUNDING_APPROVE'
       ) or p_initiator = v_approval.approved_by then
        raise exception 'Current distinct reversal authorization and chronology are required'
            using errcode = '42501';
    end if;

    if v_existing.id is not null then
        if v_existing.contribution_id = p_contribution_id
           and v_existing.effective_date = p_effective_date
           and v_existing.initiated_by = p_initiator
           and v_existing.approved_by = v_approval.approved_by
           and v_existing.approval_id = p_approver
           and exists (
               select 1 from public.owner_funding_approval_consumption
                where approval_id = p_approver and request_id = p_request_id
           ) then
            return v_existing.journal_id;
        end if;
        raise exception 'The reversal request ID is already used by a different reversal'
            using errcode = '23505';
    end if;
    if exists (select 1 from public.owner_funding_reversal
                where contribution_id = p_contribution_id)
       or exists (select 1 from public.owner_funding_approval_consumption
                   where approval_id = p_approver) then
        raise exception 'The funding or approval has already been reversed or consumed'
            using errcode = '23505';
    end if;

    perform public.finance_guard_protected_account(
        v_funding.cash_account_id, p_effective_date, p_journal_id
    );
    select * into v_original from public.journal_entry
     where id = v_funding.journal_id for update;
    if not found or v_original.source_type <> 'OWNER_FUNDING'
       or v_original.posting_purpose <> 'OWNER_FUNDING'
       or v_original.effective_date <> v_funding.effective_date then
        raise exception 'The original funding journal is invalid' using errcode = '23514';
    end if;
    if exists (
        select 1 from public.journal_entry_line l
        join public.journal_entry j on j.id = l.journal_id
         where l.account_id = v_funding.cash_account_id and l.credit > 0
           and j.recorded_at > v_original.recorded_at
    ) then
        raise exception 'Owner funding cannot be reversed after dependent fund usage'
            using errcode = '23514';
    end if;

    insert into public.journal_entry (
        id, period_id, source_type, source_id, posting_purpose, effective_date,
        recorded_at, posted_by, reverses_journal_id
    ) values (
        p_journal_id, v_period_id, 'OWNER_FUNDING', p_contribution_id, 'REVERSAL',
        p_effective_date, v_recorded_at, p_initiator, v_funding.journal_id
    );
    insert into public.journal_entry_line (
        journal_id, line_number, account_id, debit, credit
    )
    select p_journal_id, line_number, account_id, credit, debit
      from public.journal_entry_line
     where journal_id = v_funding.journal_id
     order by line_number;
    insert into public.owner_funding_reversal (
        id, request_id, contribution_id, effective_date, initiated_by,
        approved_by, journal_id, posted_at, approval_id
    ) values (
        p_id, p_request_id, p_contribution_id, p_effective_date, p_initiator,
        v_approval.approved_by, p_journal_id, v_recorded_at, p_approver
    );
    insert into public.owner_funding_approval_consumption (
        approval_id, request_id, consumed_at
    ) values (p_approver, p_request_id, v_recorded_at);
    return p_journal_id;
end;
$$;

revoke all on table public.finance_database_principal,
    public.owner_funding_approval, public.owner_funding_approval_consumption,
    public.finance_account_posting_guard from public, stock_book_app_runtime,
    stock_book_finance_approver;
revoke all on function public.finance_guard_protected_account(uuid, date, uuid)
    from public, stock_book_app_runtime, stock_book_finance_approver;
revoke all on function public.finance_approve_owner_funding_intent(
    uuid, varchar, uuid, uuid, uuid, numeric, date, uuid
) from public, stock_book_app_runtime;

alter table public.finance_database_principal owner to stock_book_finance_owner;
alter table public.owner_funding_approval owner to stock_book_finance_owner;
alter table public.owner_funding_approval_consumption owner to stock_book_finance_owner;
alter table public.finance_account_posting_guard owner to stock_book_finance_owner;
alter function public.finance_guard_protected_account(uuid, date, uuid)
    owner to stock_book_finance_owner;
alter function public.finance_approve_owner_funding_intent(
    uuid, varchar, uuid, uuid, uuid, numeric, date, uuid
) owner to stock_book_finance_owner;

grant execute on function public.finance_approve_owner_funding_intent(
    uuid, varchar, uuid, uuid, uuid, numeric, date, uuid
) to stock_book_finance_approver;
