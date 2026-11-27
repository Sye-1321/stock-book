alter table public.journal_entry drop constraint journal_source_type;
alter table public.journal_entry add constraint journal_source_type check (
    source_type in (
        'PURCHASE', 'SALE', 'PAYMENT', 'EXPENSE', 'GOODS_RECEIPT', 'SYSTEM',
        'OWNER_FUNDING'
    )
);

alter table public.journal_entry drop constraint journal_posting_purpose;
alter table public.journal_entry add constraint journal_posting_purpose check (
    posting_purpose in (
        'INVENTORY_RECEIPT', 'SALE', 'CUSTOMER_PAYMENT', 'SUPPLIER_PAYMENT',
        'EXPENSE', 'OPENING', 'OWNER_FUNDING', 'REVERSAL'
    )
);

insert into public.finance_account (
    id, code, name, account_role, account_type, active, posting_allowed
) values
    ('10000000-0000-0000-0000-000000000009', '1010', 'Bank', 'BANK', 'ASSET', true, true),
    ('10000000-0000-0000-0000-000000000010', '3100', 'Owner Capital', 'OWNER_CAPITAL', 'EQUITY', true, true);

create table public.finance_user_permission (
    user_id uuid not null references public.app_user (id),
    permission varchar(40) not null,
    granted_by uuid not null references public.app_user (id),
    granted_at timestamptz not null,
    primary key (user_id, permission),
    constraint finance_user_permission_value check (
        permission in ('OWNER_FUNDING_INITIATE', 'OWNER_FUNDING_APPROVE')
    )
);

create table public.owner_funding_contribution (
    id uuid primary key,
    request_id uuid not null unique,
    cash_account_id uuid not null references public.finance_account (id),
    amount numeric(20,2) not null check (amount > 0),
    effective_date date not null,
    initiated_by uuid not null references public.app_user (id),
    approved_by uuid not null references public.app_user (id),
    journal_id uuid not null unique references public.journal_entry (id),
    posted_at timestamptz not null,
    constraint owner_funding_distinct_approval check (initiated_by <> approved_by)
);

create table public.owner_funding_reversal (
    id uuid primary key,
    request_id uuid not null unique,
    contribution_id uuid not null unique references public.owner_funding_contribution (id),
    effective_date date not null,
    initiated_by uuid not null references public.app_user (id),
    approved_by uuid not null references public.app_user (id),
    journal_id uuid not null unique references public.journal_entry (id),
    posted_at timestamptz not null,
    constraint owner_funding_reversal_distinct_approval check (initiated_by <> approved_by)
);

create function public.finance_reject_funding_mutation()
returns trigger
language plpgsql
set search_path = pg_catalog, pg_temp
as $$
begin
    raise exception 'Posted owner funding records are immutable'
        using errcode = '42501';
end;
$$;

create trigger owner_funding_contribution_mutation_guard
before update or delete or truncate on public.owner_funding_contribution
for each statement execute function public.finance_reject_funding_mutation();

create trigger owner_funding_reversal_mutation_guard
before update or delete or truncate on public.owner_funding_reversal
for each statement execute function public.finance_reject_funding_mutation();

create function public.finance_lock_journal_account()
returns trigger
language plpgsql
set search_path = pg_catalog, pg_temp
as $$
begin
    perform 1 from public.finance_account where id = new.account_id for share;
    return new;
end;
$$;

create trigger journal_entry_line_account_lock
before insert on public.journal_entry_line
for each row execute function public.finance_lock_journal_account();

create function public.finance_post_owner_funding(
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
    v_capital_account_id uuid;
    v_period_id uuid;
    v_timezone varchar(100);
    v_recorded_at timestamptz := clock_timestamp();
begin
    if p_id is null or p_request_id is null or p_cash_account_id is null
       or p_effective_date is null or p_initiator is null or p_approver is null
       or p_journal_id is null or p_initiator = p_approver
       or p_amount is null or p_amount <= 0 or p_amount <> trunc(p_amount, 2) then
        raise exception 'Complete, positive owner funding with distinct approval is required'
            using errcode = '22023';
    end if;

    perform pg_advisory_xact_lock(hashtextextended(p_request_id::text, 13001));
    select * into v_existing
      from public.owner_funding_contribution
     where request_id = p_request_id;
    if found then
        if v_existing.cash_account_id = p_cash_account_id
           and v_existing.amount = p_amount
           and v_existing.effective_date = p_effective_date
           and v_existing.initiated_by = p_initiator
           and v_existing.approved_by = p_approver then
            return v_existing.journal_id;
        end if;
        raise exception 'The owner funding request ID is already used by different funding'
            using errcode = '23505';
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
         where u.id = p_approver and u.active
           and p.permission = 'OWNER_FUNDING_APPROVE'
    ) then
        raise exception 'Explicit owner funding initiation and approval permissions are required'
            using errcode = '42501';
    end if;

    perform 1
      from public.finance_account
     where id = p_cash_account_id and account_role in ('CASH', 'BANK')
       and account_type = 'ASSET' and active and posting_allowed
     for update;
    if not found then
        raise exception 'Owner funding must debit an active cash or bank account'
            using errcode = '23514';
    end if;

    select id into v_capital_account_id
      from public.finance_account
     where account_role = 'OWNER_CAPITAL' and account_type = 'EQUITY'
       and active and posting_allowed
     for share;
    if not found then
        raise exception 'The owner capital account is unavailable' using errcode = '23514';
    end if;

    select timezone_name into v_timezone
      from public.business_finance_settings where singleton;
    if p_effective_date > (v_recorded_at at time zone v_timezone)::date then
        raise exception 'Future-effective owner funding is not permitted' using errcode = '22023';
    end if;

    select id into v_period_id
      from public.financial_period
     where period_state = 'OPEN'
       and p_effective_date between starts_on and ends_on
     for share;
    if not found then
        raise exception 'No open financial period contains the effective date'
            using errcode = '23514';
    end if;

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
        initiated_by, approved_by, journal_id, posted_at
    ) values (
        p_id, p_request_id, p_cash_account_id, p_amount, p_effective_date,
        p_initiator, p_approver, p_journal_id, v_recorded_at
    );
    return p_journal_id;
end;
$$;

create function public.finance_reverse_owner_funding(
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
    v_original public.journal_entry%rowtype;
    v_capital_account_id uuid;
    v_period_id uuid;
    v_timezone varchar(100);
    v_recorded_at timestamptz := clock_timestamp();
begin
    if p_id is null or p_request_id is null or p_contribution_id is null
       or p_effective_date is null or p_initiator is null or p_approver is null
       or p_journal_id is null or p_initiator = p_approver then
        raise exception 'Complete owner funding reversal with distinct approval is required'
            using errcode = '22023';
    end if;

    perform pg_advisory_xact_lock(hashtextextended(p_request_id::text, 13002));
    select * into v_existing
      from public.owner_funding_reversal
     where request_id = p_request_id;
    if found then
        if v_existing.contribution_id = p_contribution_id
           and v_existing.effective_date = p_effective_date
           and v_existing.initiated_by = p_initiator
           and v_existing.approved_by = p_approver then
            return v_existing.journal_id;
        end if;
        raise exception 'The reversal request ID is already used by a different reversal'
            using errcode = '23505';
    end if;

    select * into v_funding
      from public.owner_funding_contribution
     where id = p_contribution_id
     for update;
    if not found then
        raise exception 'Owner funding contribution not found' using errcode = 'P0002';
    end if;
    if exists (
        select 1 from public.owner_funding_reversal
         where contribution_id = p_contribution_id
    ) then
        raise exception 'The owner funding contribution has already been reversed'
            using errcode = '23505';
    end if;
    if p_effective_date < v_funding.effective_date then
        raise exception 'A funding reversal cannot predate its contribution'
            using errcode = '23514';
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
         where u.id = p_approver and u.active
           and p.permission = 'OWNER_FUNDING_APPROVE'
    ) then
        raise exception 'Explicit owner funding initiation and approval permissions are required'
            using errcode = '42501';
    end if;

    perform 1 from public.finance_account
     where id = v_funding.cash_account_id for update;
    select * into v_original from public.journal_entry
     where id = v_funding.journal_id for update;

    if exists (
        select 1
          from public.journal_entry_line l
          join public.journal_entry j on j.id = l.journal_id
         where l.account_id = v_funding.cash_account_id
           and l.credit > 0
           and j.recorded_at > v_original.recorded_at
    ) then
        raise exception 'Owner funding cannot be reversed after dependent fund usage'
            using errcode = '23514';
    end if;

    select id into v_capital_account_id
      from public.finance_account
     where account_role = 'OWNER_CAPITAL' and active and posting_allowed
     for share;
    select timezone_name into v_timezone
      from public.business_finance_settings where singleton;
    if p_effective_date > (v_recorded_at at time zone v_timezone)::date then
        raise exception 'Future-effective funding reversals are not permitted' using errcode = '22023';
    end if;
    select id into v_period_id
      from public.financial_period
     where period_state = 'OPEN'
       and p_effective_date between starts_on and ends_on
     for share;
    if not found then
        raise exception 'No open financial period contains the reversal date'
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
    ) values
        (p_journal_id, 1, v_capital_account_id, v_funding.amount, 0),
        (p_journal_id, 2, v_funding.cash_account_id, 0, v_funding.amount);
    insert into public.owner_funding_reversal (
        id, request_id, contribution_id, effective_date, initiated_by,
        approved_by, journal_id, posted_at
    ) values (
        p_id, p_request_id, p_contribution_id, p_effective_date, p_initiator,
        p_approver, p_journal_id, v_recorded_at
    );
    return p_journal_id;
end;
$$;

revoke all on table public.finance_user_permission,
    public.owner_funding_contribution, public.owner_funding_reversal from public;
revoke all on table public.finance_user_permission,
    public.owner_funding_contribution, public.owner_funding_reversal
    from stock_book_app_runtime;
revoke all on function public.finance_reject_funding_mutation() from public;
revoke all on function public.finance_lock_journal_account() from public;
revoke all on function public.finance_post_owner_funding(
    uuid, uuid, uuid, numeric, date, uuid, uuid, uuid
) from public;
revoke all on function public.finance_reverse_owner_funding(
    uuid, uuid, uuid, date, uuid, uuid, uuid
) from public;

alter table public.finance_user_permission owner to stock_book_finance_owner;
alter table public.owner_funding_contribution owner to stock_book_finance_owner;
alter table public.owner_funding_reversal owner to stock_book_finance_owner;
alter function public.finance_reject_funding_mutation()
    owner to stock_book_finance_owner;
alter function public.finance_lock_journal_account()
    owner to stock_book_finance_owner;
alter function public.finance_post_owner_funding(
    uuid, uuid, uuid, numeric, date, uuid, uuid, uuid
) owner to stock_book_finance_owner;
alter function public.finance_reverse_owner_funding(
    uuid, uuid, uuid, date, uuid, uuid, uuid
) owner to stock_book_finance_owner;

grant select on table public.owner_funding_contribution,
    public.owner_funding_reversal to stock_book_app_runtime;
grant execute on function public.finance_post_owner_funding(
    uuid, uuid, uuid, numeric, date, uuid, uuid, uuid
) to stock_book_app_runtime;
grant execute on function public.finance_reverse_owner_funding(
    uuid, uuid, uuid, date, uuid, uuid, uuid
) to stock_book_app_runtime;
