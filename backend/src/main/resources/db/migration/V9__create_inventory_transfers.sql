create table inventory_transfer (
    id uuid primary key,
    request_id uuid not null unique,
    source_warehouse_id uuid not null references warehouse (id),
    destination_warehouse_id uuid not null references warehouse (id),
    product_id uuid not null references product (id),
    entered_quantity numeric not null,
    entered_unit_id uuid not null references unit_of_measure (id),
    conversion_factor numeric not null,
    base_quantity numeric not null,
    reason varchar(500) not null,
    posted_by uuid not null references app_user (id),
    posted_at timestamptz not null,
    constraint inventory_transfer_distinct_warehouses check (
        source_warehouse_id <> destination_warehouse_id
    ),
    constraint inventory_transfer_entered_quantity_positive check (entered_quantity > 0),
    constraint inventory_transfer_conversion_factor_positive check (conversion_factor > 0),
    constraint inventory_transfer_base_quantity_positive check (base_quantity > 0),
    constraint inventory_transfer_reason_not_blank check (btrim(reason) <> '')
);

create index inventory_transfer_source_history_idx
    on inventory_transfer (source_warehouse_id, posted_at desc, id desc);
create index inventory_transfer_destination_history_idx
    on inventory_transfer (destination_warehouse_id, posted_at desc, id desc);
create index inventory_transfer_product_history_idx
    on inventory_transfer (product_id, posted_at desc, id desc);

alter table inventory_movement
    drop constraint inventory_movement_type,
    drop constraint inventory_movement_signed_quantity,
    drop constraint inventory_movement_adjustment_reason,
    alter column request_id drop not null,
    add column transfer_id uuid references inventory_transfer (id),
    add constraint inventory_movement_type check (
        movement_type in (
            'OPENING', 'ADJUSTMENT_IN', 'ADJUSTMENT_OUT',
            'TRANSFER_IN', 'TRANSFER_OUT'
        )
    ),
    add constraint inventory_movement_signed_quantity check (
        (movement_type in ('OPENING', 'ADJUSTMENT_IN', 'TRANSFER_IN') and base_quantity > 0)
        or (movement_type in ('ADJUSTMENT_OUT', 'TRANSFER_OUT') and base_quantity < 0)
    ),
    add constraint inventory_movement_reason check (
        movement_type = 'OPENING' or btrim(reason) <> ''
    ),
    add constraint inventory_movement_transfer_link check (
        (movement_type in ('TRANSFER_IN', 'TRANSFER_OUT') and transfer_id is not null and request_id is null)
        or (movement_type not in ('TRANSFER_IN', 'TRANSFER_OUT') and transfer_id is null and request_id is not null)
    );

create unique index inventory_movement_transfer_type_idx
    on inventory_movement (transfer_id, movement_type)
    where transfer_id is not null;

create index inventory_movement_transfer_id_idx on inventory_movement (transfer_id);
