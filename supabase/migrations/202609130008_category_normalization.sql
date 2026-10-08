alter table public.categories
  add column normalization_version smallint not null default 0,
  add constraint categories_normalization_version_check
    check (normalization_version in (0, 1));

update public.categories as category
set normalization_version = 1
from (
  values
    ('travel', '여행'),
    ('food', '음식·맛집'),
    ('work', '업무·학습'),
    ('shopping', '쇼핑'),
    ('tools', '앱·도구'),
    ('life', '생활·건강'),
    ('culture', '문화·읽을거리'),
    ('other', '기타')
) as known(system_code, name)
where category.kind = 'system'
  and category.system_code = known.system_code
  and category.name = known.name
  and category.normalized_name = known.name;

alter table public.categories
  alter column normalization_version set default 1;

create table private.category_normalization_authorizations (
  backend_pid integer not null,
  transaction_id bigint not null,
  owner_id uuid not null,
  primary key (backend_pid, transaction_id, owner_id)
);

revoke all privileges on table private.category_normalization_authorizations
from public, anon, authenticated, service_role;

create function private.assert_category_normalization_ready(p_owner_id uuid)
returns void
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  if exists (
    select 1
    from public.categories as category
    where category.owner_id = p_owner_id
      and category.normalization_version <> 1
  ) then
    raise exception using
      errcode = 'P0001',
      message = 'CATEGORY_NORMALIZATION_REQUIRED';
  end if;
end;
$$;

create function private.enforce_category_normalization_ready()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if exists (
    select 1
    from private.category_normalization_authorizations as backfill_access
    where backfill_access.backend_pid = pg_catalog.pg_backend_pid()
      and backfill_access.transaction_id = pg_catalog.txid_current()
      and backfill_access.owner_id = new.owner_id
  ) then
    return new;
  end if;

  if new.normalization_version <> 1 then
    raise exception using
      errcode = 'P0001',
      message = 'CATEGORY_NORMALIZATION_REQUIRED';
  end if;

  perform private.assert_category_normalization_ready(new.owner_id);

  if tg_op = 'UPDATE' and old.owner_id <> new.owner_id then
    perform private.assert_category_normalization_ready(old.owner_id);
  end if;

  return new;
end;
$$;

create trigger categories_require_normalization
before insert or update on public.categories
for each row execute function private.enforce_category_normalization_ready();

create function private.enforce_category_search_normalization_ready()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform private.assert_category_normalization_ready(new.owner_id);
  return new;
end;
$$;

create trigger item_search_requires_category_normalization
before insert or update on public.item_search
for each row execute function private.enforce_category_search_normalization_ready();

create or replace function private.refresh_category_search(
  p_owner_id uuid,
  p_item_ids uuid[]
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform private.assert_category_normalization_ready(p_owner_id);

  update public.item_search as search_record
  set normalized_fields = pg_catalog.jsonb_set(
        search_record.normalized_fields,
        '{categories}'::text[],
        pg_catalog.to_jsonb(
          coalesce(
            (
              select pg_catalog.string_agg(
                category.normalized_name,
                ' '
                order by selected.created_at, selected.category_id
              )
              from public.item_categories as selected
              join public.categories as category
                on category.owner_id = selected.owner_id
                and category.id = selected.category_id
              where selected.owner_id = search_record.owner_id
                and selected.item_id = search_record.item_id
            ),
            ''
          )
        ),
        false
      ),
      updated_at = pg_catalog.now()
  where search_record.owner_id = p_owner_id
    and search_record.item_id = any(p_item_ids);
end;
$$;

alter function public.library_search_items(jsonb, jsonb, integer, integer)
set schema private;

alter function private.library_search_items(jsonb, jsonb, integer, integer)
rename to library_search_items_unfenced;

revoke all on function private.library_search_items_unfenced(jsonb, jsonb, integer, integer)
from public, anon, authenticated, service_role;

create function public.library_search_items(
  p_plan jsonb,
  p_filters jsonb,
  p_limit integer default 20,
  p_offset integer default 0
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  perform private.assert_category_normalization_ready(auth.uid());
  return private.library_search_items_unfenced(
    p_plan,
    p_filters,
    p_limit,
    p_offset
  );
end;
$$;

create function public.library_backfill_category_normalization(p_snapshot jsonb)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  maximum_snapshot_rows constant integer := 228;
  snapshot_count integer;
  database_count integer;
  snapshot_row jsonb;
  snapshot_ids uuid[] := '{}'::uuid[];
  snapshot_owner_ids uuid[] := '{}'::uuid[];
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
  left_index integer;
  right_index integer;
  maximum_key_length integer := 0;
  staging_prefix text;
  affected_item_ids uuid[];
  affected_owner_id uuid;
  changed_category_count integer := 0;
  changed_item_count integer := 0;
  statement_item_count integer;
begin
  if p_snapshot is null
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
      or pg_catalog.char_length(snapshot_row ->> 'normalized_name') > 154
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

    if snapshot_id = any(snapshot_ids) then
      raise exception using
        errcode = 'P0001',
        message = 'CATEGORY_NORMALIZATION_SNAPSHOT_INVALID';
    end if;

    snapshot_name := snapshot_row ->> 'name';
    snapshot_normalized_name := snapshot_row ->> 'normalized_name';
    snapshot_ids := pg_catalog.array_append(snapshot_ids, snapshot_id);
    snapshot_owner_ids := pg_catalog.array_append(snapshot_owner_ids, snapshot_owner_id);
    snapshot_names := pg_catalog.array_append(snapshot_names, snapshot_name);
    snapshot_normalized_names := pg_catalog.array_append(
      snapshot_normalized_names,
      snapshot_normalized_name
    );
    snapshot_versions := pg_catalog.array_append(snapshot_versions, snapshot_version);
  end loop;

  perform usage.owner_id
  from public.library_usage as usage
  order by usage.owner_id
  for update;

  perform item.id
  from public.items as item
  order by item.owner_id, item.id
  for update;

  lock table public.categories in share mode;

  perform category.id
  from public.categories as category
  order by category.owner_id, category.id
  for update;

  select count(*)::integer
  into database_count
  from public.categories;

  if database_count <> snapshot_count then
    raise exception using
      errcode = 'P0001',
      message = 'CATEGORY_NORMALIZATION_SNAPSHOT_INVALID';
  end if;

  for snapshot_index in 1..snapshot_count loop
    select category.*
    into current_category
    from public.categories as category
    where category.id = snapshot_ids[snapshot_index];

    if not found
      or current_category.owner_id <> snapshot_owner_ids[snapshot_index]
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
    where snapshot_owner_ids[left_side.index] = snapshot_owner_ids[right_side.index]
      and snapshot_normalized_names[left_side.index]
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
      and snapshot_owner_ids[snapshot.index] = selected.owner_id
    group by selected.owner_id, selected.item_id
    having pg_catalog.char_length(
      pg_catalog.string_agg(
        snapshot_normalized_names[snapshot.index],
        ' '
        order by selected.created_at, selected.category_id
      )
    ) > 154
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
  select distinct
    pg_catalog.pg_backend_pid(),
    pg_catalog.txid_current(),
    owner.owner_id
  from pg_catalog.unnest(snapshot_owner_ids) as owner(owner_id);

  select greatest(
    coalesce((
      select max(pg_catalog.char_length(category.normalized_name))
      from public.categories as category
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
  where category.id = snapshot_ids[snapshot.index]
    and category.normalized_name is distinct from
      snapshot_normalized_names[snapshot.index];

  update public.categories as category
  set normalized_name = snapshot_normalized_names[snapshot.index],
      normalization_version = 1
  from pg_catalog.generate_subscripts(snapshot_ids, 1) as snapshot(index)
  where category.id = snapshot_ids[snapshot.index]
    and (
      category.normalized_name is distinct from
        snapshot_normalized_names[snapshot.index]
      or category.normalization_version <> 1
    );

  get diagnostics changed_category_count = row_count;

  for affected_owner_id in
    select distinct owner.owner_id
    from pg_catalog.unnest(snapshot_owner_ids) as owner(owner_id)
    order by owner.owner_id
  loop
    select coalesce(
      pg_catalog.array_agg(search_record.item_id order by search_record.item_id),
      '{}'::uuid[]
    )
    into affected_item_ids
    from public.item_search as search_record
    where search_record.owner_id = affected_owner_id
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
      where item.owner_id = affected_owner_id
        and item.id = any(affected_item_ids);

      get diagnostics statement_item_count = row_count;
      changed_item_count := changed_item_count + statement_item_count;

      perform private.refresh_category_search(
        affected_owner_id,
        affected_item_ids
      );
    end if;
  end loop;

  delete from private.category_normalization_authorizations as backfill_access
  where backfill_access.backend_pid = pg_catalog.pg_backend_pid()
    and backfill_access.transaction_id = pg_catalog.txid_current();

  return pg_catalog.jsonb_build_object(
    'normalized_count', changed_category_count,
    'indexed_item_count', changed_item_count
  );
end;
$$;

revoke all on function private.assert_category_normalization_ready(uuid)
from public, anon, authenticated, service_role;
revoke all on function private.enforce_category_normalization_ready()
from public, anon, authenticated, service_role;
revoke all on function private.enforce_category_search_normalization_ready()
from public, anon, authenticated, service_role;
revoke all on function private.refresh_category_search(uuid, uuid[])
from public, anon, authenticated, service_role;
revoke all on function public.library_search_items(jsonb, jsonb, integer, integer)
from public, anon, authenticated, service_role;
revoke all on function public.library_backfill_category_normalization(jsonb)
from public, anon, authenticated, service_role;

grant execute on function public.library_search_items(jsonb, jsonb, integer, integer)
to authenticated;
grant execute on function public.library_backfill_category_normalization(jsonb)
to service_role;
