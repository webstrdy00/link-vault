drop function public.library_backfill_category_normalization(jsonb);

create function public.library_backfill_category_normalization(
  p_owner_id uuid,
  p_snapshot jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  maximum_snapshot_rows constant integer := 38;
  snapshot_count integer;
  owner_category_count integer;
  snapshot_row jsonb;
  snapshot_ids uuid[] := '{}'::uuid[];
  snapshot_names text[] := '{}'::text[];
  snapshot_normalized_names text[] := '{}'::text[];
  snapshot_versions smallint[] := '{}'::smallint[];
  snapshot_id uuid;
  snapshot_owner_id uuid;
  snapshot_name text;
  snapshot_normalized_name text;
  snapshot_version smallint;
  current_category public.categories%rowtype;
  snapshot_index integer;
  maximum_key_length integer := 0;
  staging_prefix text;
  affected_item_ids uuid[];
  changed_category_count integer := 0;
  changed_item_count integer := 0;
begin
  if p_owner_id is null
    or p_snapshot is null
    or pg_catalog.jsonb_typeof(p_snapshot) <> 'array'
    or pg_catalog.jsonb_array_length(p_snapshot) > maximum_snapshot_rows then
    raise exception using
      errcode = 'P0001',
      message = 'CATEGORY_NORMALIZATION_SNAPSHOT_INVALID';
  end if;

  snapshot_count := pg_catalog.jsonb_array_length(p_snapshot);

  for snapshot_row in
    select value
    from pg_catalog.jsonb_array_elements(p_snapshot)
  loop
    if not private.jsonb_has_exact_keys(
      snapshot_row,
      array[
        'id',
        'owner_id',
        'name',
        'normalized_name',
        'normalization_version'
      ]::text[]
    )
      or pg_catalog.jsonb_typeof(snapshot_row -> 'id') <> 'string'
      or pg_catalog.jsonb_typeof(snapshot_row -> 'owner_id') <> 'string'
      or pg_catalog.jsonb_typeof(snapshot_row -> 'name') <> 'string'
      or pg_catalog.jsonb_typeof(snapshot_row -> 'normalized_name') <> 'string'
      or pg_catalog.jsonb_typeof(snapshot_row -> 'normalization_version') <> 'number'
      or snapshot_row ->> 'id' !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
      or snapshot_row ->> 'owner_id' !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
      or snapshot_row ->> 'normalization_version' !~ '^[01]$'
      or pg_catalog.octet_length(snapshot_row ->> 'name') > 1024
      or pg_catalog.char_length(snapshot_row ->> 'normalized_name') not between 1 and 540
      or pg_catalog.octet_length(snapshot_row ->> 'normalized_name') > 4096 then
      raise exception using
        errcode = 'P0001',
        message = 'CATEGORY_NORMALIZATION_SNAPSHOT_INVALID';
    end if;

    begin
      snapshot_id := (snapshot_row ->> 'id')::uuid;
      snapshot_owner_id := (snapshot_row ->> 'owner_id')::uuid;
      snapshot_version := (snapshot_row ->> 'normalization_version')::smallint;
    exception
      when others then
        raise exception using
          errcode = 'P0001',
          message = 'CATEGORY_NORMALIZATION_SNAPSHOT_INVALID';
    end;

    if snapshot_owner_id <> p_owner_id
      or snapshot_id = any(snapshot_ids) then
      raise exception using
        errcode = 'P0001',
        message = 'CATEGORY_NORMALIZATION_SNAPSHOT_INVALID';
    end if;

    snapshot_name := snapshot_row ->> 'name';
    snapshot_normalized_name := snapshot_row ->> 'normalized_name';
    snapshot_ids := pg_catalog.array_append(snapshot_ids, snapshot_id);
    snapshot_names := pg_catalog.array_append(snapshot_names, snapshot_name);
    snapshot_normalized_names := pg_catalog.array_append(
      snapshot_normalized_names,
      snapshot_normalized_name
    );
    snapshot_versions := pg_catalog.array_append(snapshot_versions, snapshot_version);
  end loop;

  if not exists (
    select 1
    from public.profiles as profile
    where profile.id = p_owner_id
  ) then
    raise exception using
      errcode = 'P0001',
      message = 'CATEGORY_NORMALIZATION_SNAPSHOT_INVALID';
  end if;

  perform usage.owner_id
  from public.library_usage as usage
  where usage.owner_id = p_owner_id
  for update;

  perform item.id
  from public.items as item
  where item.owner_id = p_owner_id
  order by item.id
  for update;

  perform category.id
  from public.categories as category
  where category.owner_id = p_owner_id
  order by category.id
  for update;

  select count(*)::integer
  into owner_category_count
  from public.categories as category
  where category.owner_id = p_owner_id;

  if owner_category_count <> snapshot_count then
    raise exception using
      errcode = 'P0001',
      message = 'CATEGORY_NORMALIZATION_SNAPSHOT_INVALID';
  end if;

  for snapshot_index in 1..snapshot_count loop
    select category.*
    into current_category
    from public.categories as category
    where category.owner_id = p_owner_id
      and category.id = snapshot_ids[snapshot_index];

    if not found
      or current_category.name is distinct from snapshot_names[snapshot_index]
      or current_category.normalization_version <> snapshot_versions[snapshot_index] then
      raise exception using
        errcode = 'P0001',
        message = 'CATEGORY_NORMALIZATION_SNAPSHOT_INVALID';
    end if;
  end loop;

  if exists (
    select 1
    from pg_catalog.generate_subscripts(snapshot_ids, 1) as left_side(index)
    join pg_catalog.generate_subscripts(snapshot_ids, 1) as right_side(index)
      on right_side.index > left_side.index
    where snapshot_normalized_names[left_side.index]
      = snapshot_normalized_names[right_side.index]
  ) then
    raise exception using
      errcode = 'P0001',
      message = 'CATEGORY_NORMALIZATION_COLLISION';
  end if;

  if exists (
    select 1
    from public.item_categories as selected
    join pg_catalog.generate_subscripts(snapshot_ids, 1) as snapshot(index)
      on snapshot_ids[snapshot.index] = selected.category_id
    where selected.owner_id = p_owner_id
    group by selected.item_id
    having pg_catalog.char_length(
      pg_catalog.string_agg(
        snapshot_normalized_names[snapshot.index],
        ' '
        order by selected.created_at, selected.category_id
      )
    ) > 2704
  ) then
    raise exception using
      errcode = 'P0001',
      message = 'CATEGORY_NORMALIZATION_SNAPSHOT_INVALID';
  end if;

  insert into private.category_normalization_authorizations (
    backend_pid,
    transaction_id,
    owner_id
  )
  values (
    pg_catalog.pg_backend_pid(),
    pg_catalog.txid_current(),
    p_owner_id
  );

  select greatest(
    coalesce((
      select max(pg_catalog.char_length(category.normalized_name))
      from public.categories as category
      where category.owner_id = p_owner_id
    ), 0),
    coalesce((
      select max(pg_catalog.char_length(target.normalized_name))
      from pg_catalog.unnest(snapshot_normalized_names) as target(normalized_name)
    ), 0)
  )
  into maximum_key_length;

  staging_prefix := pg_catalog.repeat(pg_catalog.chr(1), maximum_key_length + 1);

  update public.categories as category
  set normalized_name = staging_prefix || category.id::text
  from pg_catalog.generate_subscripts(snapshot_ids, 1) as snapshot(index)
  where category.owner_id = p_owner_id
    and category.id = snapshot_ids[snapshot.index]
    and category.normalized_name is distinct from
      snapshot_normalized_names[snapshot.index];

  update public.categories as category
  set normalized_name = snapshot_normalized_names[snapshot.index],
      normalization_version = 1
  from pg_catalog.generate_subscripts(snapshot_ids, 1) as snapshot(index)
  where category.owner_id = p_owner_id
    and category.id = snapshot_ids[snapshot.index]
    and (
      category.normalized_name is distinct from
        snapshot_normalized_names[snapshot.index]
      or category.normalization_version <> 1
    );

  get diagnostics changed_category_count = row_count;

  select coalesce(
    pg_catalog.array_agg(search_record.item_id order by search_record.item_id),
    '{}'::uuid[]
  )
  into affected_item_ids
  from public.item_search as search_record
  where search_record.owner_id = p_owner_id
    and (search_record.normalized_fields ->> 'categories') is distinct from (
      select coalesce(
        pg_catalog.string_agg(
          category.normalized_name,
          ' '
          order by selected.created_at, selected.category_id
        ),
        ''
      )
      from public.item_categories as selected
      join public.categories as category
        on category.owner_id = selected.owner_id
        and category.id = selected.category_id
      where selected.owner_id = search_record.owner_id
        and selected.item_id = search_record.item_id
    );

  if pg_catalog.cardinality(affected_item_ids) > 0 then
    update public.items as item
    set version = item.version + 1,
        updated_at = pg_catalog.now()
    where item.owner_id = p_owner_id
      and item.id = any(affected_item_ids);

    get diagnostics changed_item_count = row_count;

    perform private.refresh_category_search(
      p_owner_id,
      affected_item_ids
    );
  end if;

  delete from private.category_normalization_authorizations as backfill_access
  where backfill_access.backend_pid = pg_catalog.pg_backend_pid()
    and backfill_access.transaction_id = pg_catalog.txid_current()
    and backfill_access.owner_id = p_owner_id;

  return pg_catalog.jsonb_build_object(
    'normalized_count', changed_category_count,
    'indexed_item_count', changed_item_count
  );
end;
$$;

revoke all on function public.library_backfill_category_normalization(uuid, jsonb)
from public, anon, authenticated, service_role;

grant execute on function public.library_backfill_category_normalization(uuid, jsonb)
to service_role;
