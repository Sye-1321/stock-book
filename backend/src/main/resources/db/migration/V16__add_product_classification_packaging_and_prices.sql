create table product_category (
    id uuid primary key,
    code varchar(50) not null unique,
    name varchar(200) not null,
    active boolean not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint product_category_code_format check (
        code = upper(btrim(code)) and code ~ '^[A-Z0-9][A-Z0-9_-]*$'
    ),
    constraint product_category_name_not_blank check (btrim(name) <> '')
);

create table product_brand (
    id uuid primary key,
    code varchar(50) not null unique,
    name varchar(200) not null,
    active boolean not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint product_brand_code_format check (
        code = upper(btrim(code)) and code ~ '^[A-Z0-9][A-Z0-9_-]*$'
    ),
    constraint product_brand_name_not_blank check (btrim(name) <> '')
);

create table product_family (
    id uuid primary key,
    code varchar(50) not null unique,
    name varchar(200) not null,
    active boolean not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint product_family_code_format check (
        code = upper(btrim(code)) and code ~ '^[A-Z0-9][A-Z0-9_-]*$'
    ),
    constraint product_family_name_not_blank check (btrim(name) <> '')
);

alter table product
    add column category_id uuid references product_category (id),
    add column brand_id uuid references product_brand (id),
    add column family_id uuid references product_family (id);

create index product_category_id_idx on product (category_id);
create index product_brand_id_idx on product (brand_id);
create index product_family_id_idx on product (family_id);

alter table product_unit_conversion
    add column active boolean not null default true,
    add column created_at timestamptz not null default now(),
    add column updated_at timestamptz not null default now();

create function validate_product_packaging_factor()
returns trigger
language plpgsql
set search_path = pg_catalog, public, pg_temp
as $function$
begin
    if new.conversion_factor <> trunc(new.conversion_factor) then
        raise exception 'Packaging conversion factor must be a positive integer'
            using errcode = '23514';
    end if;
    return new;
end;
$function$;

create trigger product_packaging_factor_guard
before insert or update of conversion_factor on product_unit_conversion
for each row execute function validate_product_packaging_factor();

create table product_channel_price (
    id uuid primary key,
    product_id uuid not null references product (id),
    unit_id uuid not null references unit_of_measure (id),
    channel varchar(20) not null,
    amount numeric(20,2) not null,
    valid_from timestamptz not null,
    valid_to timestamptz,
    recorded_by uuid not null references app_user (id),
    constraint product_channel_price_channel check (channel in ('WHOLESALE', 'RETAIL')),
    constraint product_channel_price_nonnegative check (amount >= 0),
    constraint product_channel_price_interval check (valid_to is null or valid_to > valid_from)
);

create unique index product_channel_price_current_unique
    on product_channel_price (product_id, unit_id, channel)
    where valid_to is null;
create index product_channel_price_history_idx
    on product_channel_price (product_id, unit_id, channel, valid_from desc);

create function protect_product_channel_price_history()
returns trigger
language plpgsql
set search_path = pg_catalog, public, pg_temp
as $function$
begin
    if new.id is distinct from old.id
       or new.product_id is distinct from old.product_id
       or new.unit_id is distinct from old.unit_id
       or new.channel is distinct from old.channel
       or new.amount is distinct from old.amount
       or new.valid_from is distinct from old.valid_from
       or new.recorded_by is distinct from old.recorded_by
       or old.valid_to is not null
       or new.valid_to is null then
        raise exception 'Channel price revisions are immutable'
            using errcode = '55000';
    end if;
    return new;
end;
$function$;

create trigger product_channel_price_history_guard
before update on product_channel_price
for each row execute function protect_product_channel_price_history();

grant select, insert, update, delete on table
    product_category, product_brand, product_family, product_channel_price
    to stock_book_app_runtime;
grant update on table product, product_unit_conversion to stock_book_app_runtime;
