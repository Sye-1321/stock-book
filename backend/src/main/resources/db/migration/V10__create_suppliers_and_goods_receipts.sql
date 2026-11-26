create table supplier (
    id uuid primary key,
    code varchar(50) not null unique,
    name varchar(200) not null,
    phone varchar(100),
    email varchar(254),
    active boolean not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint supplier_code_format check (
        code = upper(btrim(code))
        and code ~ '^[A-Z0-9][A-Z0-9_-]*$'
    ),
    constraint supplier_name_not_blank check (btrim(name) <> ''),
    constraint supplier_phone_not_blank check (phone is null or btrim(phone) <> ''),
    constraint supplier_email_not_blank check (email is null or btrim(email) <> '')
);

create table goods_receipt (
    id uuid primary key,
    request_id uuid not null unique,
    supplier_id uuid not null references supplier (id),
    warehouse_id uuid not null references warehouse (id),
    supplier_document_reference varchar(100),
    posted_by uuid not null references app_user (id),
    posted_at timestamptz not null,
    constraint goods_receipt_reference_not_blank check (
        supplier_document_reference is null or btrim(supplier_document_reference) <> ''
    )
);

create index goods_receipt_supplier_history_idx
    on goods_receipt (supplier_id, posted_at desc, id desc);
create index goods_receipt_warehouse_history_idx
    on goods_receipt (warehouse_id, posted_at desc, id desc);

create table goods_receipt_line (
    id uuid primary key,
    receipt_id uuid not null references goods_receipt (id),
    line_number integer not null,
    product_id uuid not null references product (id),
    entered_quantity numeric not null,
    entered_unit_id uuid not null references unit_of_measure (id),
    conversion_factor numeric not null,
    base_quantity numeric not null,
    constraint goods_receipt_line_number_positive check (line_number > 0),
    constraint goods_receipt_line_quantity_positive check (entered_quantity > 0),
    constraint goods_receipt_line_factor_positive check (conversion_factor > 0),
    constraint goods_receipt_line_base_quantity_positive check (base_quantity > 0),
    unique (receipt_id, line_number),
    unique (receipt_id, product_id)
);

create index goods_receipt_line_product_idx on goods_receipt_line (product_id);

alter table inventory_movement
    drop constraint inventory_movement_type,
    drop constraint inventory_movement_signed_quantity,
    drop constraint inventory_movement_reason,
    drop constraint inventory_movement_transfer_link,
    add column receipt_line_id uuid references goods_receipt_line (id),
    add constraint inventory_movement_type check (
        movement_type in (
            'OPENING', 'ADJUSTMENT_IN', 'ADJUSTMENT_OUT',
            'TRANSFER_IN', 'TRANSFER_OUT', 'RECEIPT_IN'
        )
    ),
    add constraint inventory_movement_signed_quantity check (
        (movement_type in ('OPENING', 'ADJUSTMENT_IN', 'TRANSFER_IN', 'RECEIPT_IN') and base_quantity > 0)
        or (movement_type in ('ADJUSTMENT_OUT', 'TRANSFER_OUT') and base_quantity < 0)
    ),
    add constraint inventory_movement_reason check (
        movement_type = 'OPENING' or btrim(reason) <> ''
    ),
    add constraint inventory_movement_source_link check (
        (movement_type in ('TRANSFER_IN', 'TRANSFER_OUT')
            and transfer_id is not null and receipt_line_id is null and request_id is null)
        or (movement_type = 'RECEIPT_IN'
            and receipt_line_id is not null and transfer_id is null and request_id is null)
        or (movement_type not in ('TRANSFER_IN', 'TRANSFER_OUT', 'RECEIPT_IN')
            and transfer_id is null and receipt_line_id is null and request_id is not null)
    );

create unique index inventory_movement_receipt_line_idx
    on inventory_movement (receipt_line_id)
    where receipt_line_id is not null;
