create table unit_of_measure (
    id uuid primary key,
    code varchar(50) not null unique,
    name varchar(200) not null,
    constraint unit_of_measure_code_format check (
        code = upper(btrim(code))
        and code ~ '^[A-Z0-9][A-Z0-9_-]*$'
    ),
    constraint unit_of_measure_name_not_blank check (btrim(name) <> '')
);

create table product (
    id uuid primary key,
    sku varchar(100) not null unique,
    name varchar(200) not null,
    base_unit_id uuid not null references unit_of_measure (id),
    active boolean not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint product_sku_format check (
        sku = upper(btrim(sku))
        and sku ~ '^[A-Z0-9][A-Z0-9_-]*$'
    ),
    constraint product_name_not_blank check (btrim(name) <> '')
);

create index product_base_unit_id_idx on product (base_unit_id);

create table product_unit_conversion (
    product_id uuid not null references product (id),
    alternate_unit_id uuid not null references unit_of_measure (id),
    conversion_factor numeric not null,
    primary key (product_id, alternate_unit_id),
    constraint product_unit_conversion_positive check (conversion_factor > 0)
);
