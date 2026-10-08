create or replace function private.valid_normalized_fields(p_value jsonb)
returns boolean
language sql
immutable
parallel safe
set search_path = ''
as $$
  select
    private.jsonb_has_exact_keys(
      p_value,
      array[
        'user_title',
        'fetched_title',
        'note',
        'ocr',
        'shared',
        'description',
        'body',
        'categories',
        'url'
      ]::text[]
    )
    and pg_catalog.jsonb_typeof(p_value -> 'user_title') = 'string'
    and pg_catalog.char_length(p_value ->> 'user_title') <= 5400
    and pg_catalog.jsonb_typeof(p_value -> 'fetched_title') = 'string'
    and pg_catalog.char_length(p_value ->> 'fetched_title') <= 5400
    and pg_catalog.jsonb_typeof(p_value -> 'note') = 'string'
    and pg_catalog.char_length(p_value ->> 'note') <= 72000
    and pg_catalog.jsonb_typeof(p_value -> 'ocr') = 'string'
    and pg_catalog.char_length(p_value ->> 'ocr') <= 360000
    and pg_catalog.jsonb_typeof(p_value -> 'shared') = 'string'
    and pg_catalog.char_length(p_value ->> 'shared') <= 72000
    and pg_catalog.jsonb_typeof(p_value -> 'description') = 'string'
    and pg_catalog.char_length(p_value ->> 'description') <= 72000
    and pg_catalog.jsonb_typeof(p_value -> 'body') = 'string'
    and pg_catalog.char_length(p_value ->> 'body') <= 360000
    and pg_catalog.jsonb_typeof(p_value -> 'categories') = 'string'
    and pg_catalog.char_length(p_value ->> 'categories') <= 2704
    and pg_catalog.jsonb_typeof(p_value -> 'url') = 'string'
    and pg_catalog.char_length(p_value ->> 'url') <= 73728;
$$;

create or replace function public.library_create_category(
  p_owner_id uuid,
  p_request_id uuid,
  p_body jsonb,
  p_normalized_name text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  method_path_value constant text := 'POST /categories';
  request_hash_value text;
  previous_request public.api_requests%rowtype;
  raw_name text;
  custom_count integer;
  created_category_id uuid;
  result_category jsonb;
begin
  if p_request_id is null then
    raise exception using errcode = 'P0001', message = 'INVALID_REQUEST_ID';
  end if;

  perform private.lock_library_owner(p_owner_id);

  request_hash_value := pg_catalog.encode(
    extensions.digest(
      pg_catalog.convert_to(
        method_path_value || pg_catalog.chr(10) || coalesce(p_body::text, 'null'),
        'UTF8'
      ),
      'sha256'
    ),
    'hex'
  );

  select request.*
  into previous_request
  from public.api_requests as request
  where request.owner_id = p_owner_id
    and request.request_id = p_request_id
  for update;

  if found then
    if previous_request.method_path <> method_path_value
      or previous_request.request_hash <> request_hash_value then
      raise exception using errcode = 'P0001', message = 'IDEMPOTENCY_MISMATCH';
    end if;

    if previous_request.response_code = 409 then
      return pg_catalog.jsonb_build_object(
        'http_status', 409,
        'error_code', previous_request.response_body ->> 'error_code'
      );
    end if;

    result_category := private.library_category_json(
      p_owner_id,
      (previous_request.response_body ->> 'category_id')::uuid
    );
    if result_category is null then
      raise exception using errcode = 'P0001', message = 'CATEGORY_NOT_FOUND';
    end if;

    return pg_catalog.jsonb_build_object(
      'http_status', 201,
      'category', result_category
    );
  end if;

  if not private.jsonb_has_exact_keys(p_body, array['name']::text[])
    or pg_catalog.jsonb_typeof(p_body -> 'name') <> 'string' then
    raise exception using errcode = 'P0001', message = 'INVALID_BODY';
  end if;

  raw_name := p_body ->> 'name';
  if pg_catalog.char_length(raw_name) not between 1 and 30
    or p_normalized_name is null
    or p_normalized_name <> pg_catalog.btrim(p_normalized_name)
    or pg_catalog.char_length(p_normalized_name) not between 1 and 540 then
    raise exception using errcode = 'P0001', message = 'INVALID_BODY';
  end if;

  if exists (
    select 1
    from public.categories as category
    where category.owner_id = p_owner_id
      and category.normalized_name = p_normalized_name
  ) then
    insert into public.api_requests (
      owner_id,
      request_id,
      method_path,
      request_hash,
      response_code,
      response_body
    )
    values (
      p_owner_id,
      p_request_id,
      method_path_value,
      request_hash_value,
      409,
      pg_catalog.jsonb_build_object('error_code', 'CATEGORY_NAME_EXISTS')
    );

    return pg_catalog.jsonb_build_object(
      'http_status', 409,
      'error_code', 'CATEGORY_NAME_EXISTS'
    );
  end if;

  select count(*)::integer
  into custom_count
  from public.categories as category
  where category.owner_id = p_owner_id
    and category.kind = 'custom';

  if custom_count >= 30 then
    insert into public.api_requests (
      owner_id,
      request_id,
      method_path,
      request_hash,
      response_code,
      response_body
    )
    values (
      p_owner_id,
      p_request_id,
      method_path_value,
      request_hash_value,
      409,
      pg_catalog.jsonb_build_object('error_code', 'CATEGORY_LIMIT_REACHED')
    );

    return pg_catalog.jsonb_build_object(
      'http_status', 409,
      'error_code', 'CATEGORY_LIMIT_REACHED'
    );
  end if;

  insert into public.categories (
    owner_id,
    name,
    normalized_name,
    kind,
    system_code
  )
  values (
    p_owner_id,
    raw_name,
    p_normalized_name,
    'custom',
    null
  )
  returning id into created_category_id;

  insert into public.api_requests (
    owner_id,
    request_id,
    method_path,
    request_hash,
    response_code,
    response_body
  )
  values (
    p_owner_id,
    p_request_id,
    method_path_value,
    request_hash_value,
    201,
    pg_catalog.jsonb_build_object(
      'category_id', created_category_id,
      'http_status', 201
    )
  );

  result_category := private.library_category_json(
    p_owner_id,
    created_category_id
  );

  return pg_catalog.jsonb_build_object(
    'http_status', 201,
    'category', result_category
  );
end;
$$;

create or replace function public.library_rename_category(
  p_owner_id uuid,
  p_category_id uuid,
  p_request_id uuid,
  p_body jsonb,
  p_normalized_name text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  method_path_value text;
  request_hash_value text;
  previous_request public.api_requests%rowtype;
  current_category public.categories%rowtype;
  raw_name text;
  affected_item_ids uuid[];
  result_category jsonb;
begin
  if p_category_id is null then
    raise exception using errcode = 'P0001', message = 'CATEGORY_NOT_FOUND';
  end if;
  if p_request_id is null then
    raise exception using errcode = 'P0001', message = 'INVALID_REQUEST_ID';
  end if;

  perform private.lock_library_owner(p_owner_id);

  method_path_value := 'PATCH /categories/' || p_category_id::text;
  request_hash_value := pg_catalog.encode(
    extensions.digest(
      pg_catalog.convert_to(
        method_path_value || pg_catalog.chr(10) || coalesce(p_body::text, 'null'),
        'UTF8'
      ),
      'sha256'
    ),
    'hex'
  );

  select request.*
  into previous_request
  from public.api_requests as request
  where request.owner_id = p_owner_id
    and request.request_id = p_request_id
  for update;

  if found then
    if previous_request.method_path <> method_path_value
      or previous_request.request_hash <> request_hash_value then
      raise exception using errcode = 'P0001', message = 'IDEMPOTENCY_MISMATCH';
    end if;

    if previous_request.response_code = 409 then
      return pg_catalog.jsonb_build_object(
        'http_status', 409,
        'error_code', previous_request.response_body ->> 'error_code'
      );
    end if;

    result_category := private.library_category_json(
      p_owner_id,
      (previous_request.response_body ->> 'category_id')::uuid
    );
    if result_category is null then
      raise exception using errcode = 'P0001', message = 'CATEGORY_NOT_FOUND';
    end if;

    return pg_catalog.jsonb_build_object(
      'http_status', 200,
      'category', result_category
    );
  end if;

  if not private.jsonb_has_exact_keys(p_body, array['name']::text[])
    or pg_catalog.jsonb_typeof(p_body -> 'name') <> 'string' then
    raise exception using errcode = 'P0001', message = 'INVALID_BODY';
  end if;

  raw_name := p_body ->> 'name';
  if pg_catalog.char_length(raw_name) not between 1 and 30
    or p_normalized_name is null
    or p_normalized_name <> pg_catalog.btrim(p_normalized_name)
    or pg_catalog.char_length(p_normalized_name) not between 1 and 540 then
    raise exception using errcode = 'P0001', message = 'INVALID_BODY';
  end if;

  select category.*
  into current_category
  from public.categories as category
  where category.owner_id = p_owner_id
    and category.id = p_category_id;

  if not found then
    raise exception using errcode = 'P0001', message = 'CATEGORY_NOT_FOUND';
  end if;
  if current_category.kind = 'system' then
    raise exception using errcode = 'P0001', message = 'SYSTEM_CATEGORY_READONLY';
  end if;

  if exists (
    select 1
    from public.categories as category
    where category.owner_id = p_owner_id
      and category.normalized_name = p_normalized_name
      and category.id <> p_category_id
  ) then
    insert into public.api_requests (
      owner_id,
      request_id,
      method_path,
      request_hash,
      response_code,
      response_body
    )
    values (
      p_owner_id,
      p_request_id,
      method_path_value,
      request_hash_value,
      409,
      pg_catalog.jsonb_build_object(
        'category_id', p_category_id,
        'error_code', 'CATEGORY_NAME_EXISTS'
      )
    );

    return pg_catalog.jsonb_build_object(
      'http_status', 409,
      'error_code', 'CATEGORY_NAME_EXISTS'
    );
  end if;

  select coalesce(
    pg_catalog.array_agg(selected.item_id order by selected.item_id),
    '{}'::uuid[]
  )
  into affected_item_ids
  from public.item_categories as selected
  join public.items as item
    on item.owner_id = selected.owner_id
    and item.id = selected.item_id
  where selected.owner_id = p_owner_id
    and selected.category_id = p_category_id;

  perform 1
  from public.items as item
  where item.owner_id = p_owner_id
    and item.id = any(affected_item_ids)
  order by item.id
  for update;

  select category.*
  into current_category
  from public.categories as category
  where category.owner_id = p_owner_id
    and category.id = p_category_id
  for update;

  if not found then
    raise exception using errcode = 'P0001', message = 'CATEGORY_NOT_FOUND';
  end if;
  if current_category.kind = 'system' then
    raise exception using errcode = 'P0001', message = 'SYSTEM_CATEGORY_READONLY';
  end if;

  update public.categories
  set name = raw_name,
      normalized_name = p_normalized_name,
      updated_at = pg_catalog.now()
  where owner_id = p_owner_id
    and id = p_category_id;

  update public.items
  set version = version + 1,
      updated_at = pg_catalog.now()
  where owner_id = p_owner_id
    and id = any(affected_item_ids);

  perform private.refresh_category_search(p_owner_id, affected_item_ids);

  insert into public.api_requests (
    owner_id,
    request_id,
    method_path,
    request_hash,
    response_code,
    response_body
  )
  values (
    p_owner_id,
    p_request_id,
    method_path_value,
    request_hash_value,
    200,
    pg_catalog.jsonb_build_object(
      'category_id', p_category_id,
      'http_status', 200
    )
  );

  result_category := private.library_category_json(p_owner_id, p_category_id);

  return pg_catalog.jsonb_build_object(
    'http_status', 200,
    'category', result_category
  );
end;
$$;

create or replace function public.library_backfill_category_normalization(p_snapshot jsonb)
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
      or pg_catalog.char_length(snapshot_row ->> 'normalized_name') > 540
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

revoke all on function private.valid_normalized_fields(jsonb)
from public, anon, authenticated;
revoke all on function public.library_create_category(uuid, uuid, jsonb, text)
from public, anon, authenticated;
revoke all on function public.library_rename_category(uuid, uuid, uuid, jsonb, text)
from public, anon, authenticated;
revoke all on function public.library_backfill_category_normalization(jsonb)
from public, anon, authenticated;

grant execute on function private.valid_normalized_fields(jsonb)
to service_role;
grant execute on function public.library_create_category(uuid, uuid, jsonb, text)
to service_role;
grant execute on function public.library_rename_category(uuid, uuid, uuid, jsonb, text)
to service_role;
grant execute on function public.library_backfill_category_normalization(jsonb)
to service_role;
