alter table public.product_channel_price
    alter column conversion_factor drop not null;

drop trigger product_channel_price_history_guard on public.product_channel_price;

update public.product_channel_price price
   set conversion_factor = case
           when price.unit_id = product.base_unit_id then 1
           else null
       end,
       valid_to = case
           when price.unit_id <> product.base_unit_id and price.valid_to is null
               then greatest(now(), price.valid_from + interval '1 microsecond')
           else price.valid_to
       end
  from public.product product
 where product.id = price.product_id;

create trigger product_channel_price_history_guard
before update on public.product_channel_price
for each row execute function public.protect_product_channel_price_history();

create or replace function public.catalog_supersede_channel_price(
    p_id uuid,
    p_product_id uuid,
    p_unit_id uuid,
    p_channel varchar,
    p_amount numeric,
    p_conversion_factor numeric,
    p_valid_from timestamptz,
    p_expected_current_valid_from timestamptz,
    p_recorded_by uuid
)
returns uuid
language plpgsql
security definer
set search_path = pg_catalog, pg_temp
as $function$
declare
    current_price public.product_channel_price%rowtype;
    valid_packaging boolean;
begin
    if p_id is null or p_product_id is null or p_unit_id is null
       or p_recorded_by is null or p_valid_from is null
       or p_channel not in ('WHOLESALE', 'RETAIL')
       or p_amount is null or p_amount < 0
       or scale(p_amount) > 8 or p_amount >= 10000000000000000
       or p_conversion_factor is null or p_conversion_factor <= 0 then
        raise exception 'Invalid channel price revision' using errcode = '23514';
    end if;

    perform 1 from public.product where id = p_product_id for update;
    if not found then
        raise exception 'Product was not found' using errcode = '23503';
    end if;

    select case
        when product.base_unit_id = p_unit_id then p_conversion_factor = 1
        else exists (
            select 1
              from public.product_unit_conversion conversion
             where conversion.product_id = p_product_id
               and conversion.alternate_unit_id = p_unit_id
               and conversion.active
               and conversion.conversion_factor = trunc(conversion.conversion_factor)
               and conversion.conversion_factor = p_conversion_factor
        )
    end
      into valid_packaging
      from public.product product
     where product.id = p_product_id;
    if valid_packaging is distinct from true then
        raise exception 'Price must use the current active packaging conversion'
            using errcode = '23514';
    end if;

    select * into current_price
      from public.product_channel_price
     where product_id = p_product_id
       and unit_id = p_unit_id
       and channel = p_channel
       and valid_to is null
     for update;

    if found then
        if current_price.valid_from is distinct from p_expected_current_valid_from then
            raise exception 'The current price was changed by another user' using errcode = '40001';
        end if;
        if p_valid_from <= current_price.valid_from then
            raise exception 'Price revision time must advance' using errcode = '23514';
        end if;
    elsif p_expected_current_valid_from is not null then
        raise exception 'The current price was changed by another user' using errcode = '40001';
    end if;

    if exists (
        select 1
          from public.product_channel_price history
         where history.product_id = p_product_id
           and history.unit_id = p_unit_id
           and history.channel = p_channel
           and (current_price.id is null or history.id <> current_price.id)
           and (history.valid_to is null or history.valid_to > p_valid_from)
    ) then
        raise exception 'Channel price validity intervals must not overlap'
            using errcode = '23514';
    end if;

    if current_price.id is not null then
        update public.product_channel_price
           set valid_to = p_valid_from
         where id = current_price.id;
    end if;

    insert into public.product_channel_price (
        id, product_id, unit_id, channel, amount, conversion_factor,
        valid_from, valid_to, recorded_by
    ) values (
        p_id, p_product_id, p_unit_id, p_channel, p_amount, p_conversion_factor,
        p_valid_from, null, p_recorded_by
    );
    return p_id;
end;
$function$;

create or replace function public.catalog_close_channel_prices_for_packaging_change(
    p_product_id uuid,
    p_unit_id uuid,
    p_closed_at timestamptz
)
returns void
language plpgsql
security definer
set search_path = pg_catalog, pg_temp
as $function$
declare
    price record;
    close_time timestamptz;
begin
    if p_product_id is null or p_unit_id is null or p_closed_at is null then
        raise exception 'Product, unit and closure time are required' using errcode = '23514';
    end if;

    perform 1 from public.product where id = p_product_id for update;
    if not found then
        raise exception 'Product was not found' using errcode = '23503';
    end if;

    if not exists (
        select 1
          from public.product_unit_conversion conversion
         where conversion.product_id = p_product_id
           and conversion.alternate_unit_id = p_unit_id
           and conversion.updated_at = p_closed_at
    ) or exists (
        select 1
          from public.product_unit_conversion conversion
          join public.product_channel_price current_price
            on current_price.product_id = conversion.product_id
           and current_price.unit_id = conversion.alternate_unit_id
           and current_price.valid_to is null
         where conversion.product_id = p_product_id
           and conversion.alternate_unit_id = p_unit_id
           and conversion.conversion_factor = current_price.conversion_factor
    ) then
        raise exception 'Current prices may be closed only for a versioned packaging factor change'
            using errcode = '23514';
    end if;
    for price in
        select id, valid_from
          from public.product_channel_price
         where product_id = p_product_id and unit_id = p_unit_id and valid_to is null
         for update
    loop
        close_time := greatest(p_closed_at, price.valid_from + interval '1 microsecond');
        update public.product_channel_price set valid_to = close_time where id = price.id;
    end loop;
end;
$function$;
