create table party (
    id uuid primary key,
    name varchar(200) not null,
    phone varchar(100),
    email varchar(254),
    active boolean not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint party_name_not_blank check (btrim(name) <> ''),
    constraint party_phone_not_blank check (phone is null or btrim(phone) <> ''),
    constraint party_email_not_blank check (email is null or btrim(email) <> '')
);

insert into party (id, name, phone, email, active, created_at, updated_at)
select id, name, phone, email, true, created_at, updated_at
from supplier;

alter table supplier add column party_id uuid;

update supplier set party_id = id;

alter table supplier
    alter column party_id set not null,
    add constraint supplier_party_unique unique (party_id),
    add constraint supplier_party_fk foreign key (party_id) references party (id),
    drop column name,
    drop column phone,
    drop column email;

create table customer (
    id uuid primary key,
    party_id uuid not null unique references party (id),
    code varchar(50) not null unique,
    payment_terms_days integer not null,
    active boolean not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint customer_code_format check (
        code = upper(btrim(code))
        and code ~ '^[A-Z0-9][A-Z0-9_-]*$'
    ),
    constraint customer_payment_terms_range check (
        payment_terms_days between 0 and 3650
    )
);

create index party_name_lookup_idx on party (lower(name), id);

grant select, insert, update, delete on table party, customer
    to stock_book_app_runtime;
