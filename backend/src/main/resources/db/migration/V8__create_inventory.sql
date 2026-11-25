create table stock_balance (
    warehouse_id uuid not null references warehouse (id),
    product_id uuid not null references product (id),
    base_quantity numeric not null,
    updated_at timestamptz not null,
    primary key (warehouse_id, product_id),
    constraint stock_balance_nonnegative check (base_quantity >= 0)
);

create index stock_balance_product_id_idx on stock_balance (product_id);

create table inventory_movement (
    id uuid primary key,
    request_id uuid not null unique,
    warehouse_id uuid not null references warehouse (id),
    product_id uuid not null references product (id),
    movement_type varchar(20) not null,
    entered_quantity numeric not null,
    entered_unit_id uuid not null references unit_of_measure (id),
    conversion_factor numeric not null,
    base_quantity numeric not null,
    reason varchar(500),
    posted_by uuid not null references app_user (id),
    posted_at timestamptz not null,
    constraint inventory_movement_type check (
        movement_type in ('OPENING', 'ADJUSTMENT_IN', 'ADJUSTMENT_OUT')
    ),
    constraint inventory_movement_entered_quantity_positive check (entered_quantity > 0),
    constraint inventory_movement_conversion_factor_positive check (conversion_factor > 0),
    constraint inventory_movement_signed_quantity check (
        (movement_type in ('OPENING', 'ADJUSTMENT_IN') and base_quantity > 0)
        or (movement_type = 'ADJUSTMENT_OUT' and base_quantity < 0)
    ),
    constraint inventory_movement_adjustment_reason check (
        movement_type = 'OPENING' or btrim(reason) <> ''
    )
);

create unique index inventory_movement_one_opening_idx
    on inventory_movement (warehouse_id, product_id)
    where movement_type = 'OPENING';

create index inventory_movement_warehouse_product_history_idx
    on inventory_movement (warehouse_id, product_id, posted_at desc, id desc);
create index inventory_movement_product_history_idx
    on inventory_movement (product_id, posted_at desc, id desc);
create index inventory_movement_posted_at_idx
    on inventory_movement (posted_at desc, id desc);
