create table business_location (
    id uuid primary key,
    code varchar(50) not null unique,
    name varchar(200) not null,
    active boolean not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint business_location_code_format check (
        code = upper(btrim(code))
        and code ~ '^[A-Z0-9][A-Z0-9_-]*$'
    ),
    constraint business_location_name_not_blank check (btrim(name) <> '')
);

create table warehouse (
    id uuid primary key,
    code varchar(50) not null unique,
    name varchar(200) not null,
    location_id uuid not null references business_location (id),
    active boolean not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint warehouse_code_format check (
        code = upper(btrim(code))
        and code ~ '^[A-Z0-9][A-Z0-9_-]*$'
    ),
    constraint warehouse_name_not_blank check (btrim(name) <> '')
);

create index warehouse_location_id_idx on warehouse (location_id);
