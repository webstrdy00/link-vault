create function private.valid_asset_ocr_body(
  p_body jsonb,
  p_allow_not_requested boolean
)
returns boolean
language plpgsql
immutable
parallel safe
set search_path = ''
as $$
declare
  ocr_state_value text;
begin
  if p_body is null
    or pg_catalog.jsonb_typeof(p_body) <> 'object'
    or not (p_body ? 'expected_version')
    or not (p_body ? 'ocr_state')
    or exists (
      select 1
      from pg_catalog.jsonb_object_keys(p_body) as property(key)
      where property.key not in (
        'expected_version',
        'ocr_state',
        'ocr_text',
        'ocr_truncated'
      )
    )
    or pg_catalog.jsonb_typeof(p_body -> 'ocr_state') <> 'string'
    or (
      p_body ? 'ocr_truncated'
      and pg_catalog.jsonb_typeof(p_body -> 'ocr_truncated') <> 'boolean'
    ) then
    return false;
  end if;

  ocr_state_value := p_body ->> 'ocr_state';
  if ocr_state_value not in ('ready', 'failed')
    and not (p_allow_not_requested and ocr_state_value = 'not_requested') then
    return false;
  end if;

  if ocr_state_value = 'ready' then
    return p_body ? 'ocr_text'
      and pg_catalog.jsonb_typeof(p_body -> 'ocr_text') = 'string';
  end if;

  return (
      not (p_body ? 'ocr_text')
      or pg_catalog.jsonb_typeof(p_body -> 'ocr_text') = 'null'
    )
    and (
      not (p_body ? 'ocr_truncated')
      or not (p_body ->> 'ocr_truncated')::boolean
    );
exception
  when others then
    return false;
end;
$$;

create function public.library_replay_asset_request(
  p_owner_id uuid,
  p_request_id uuid,
  p_method text,
  p_path text,
  p_body jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  uuid_pattern constant text := '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}';
  method_path_value text;
  request_hash_value text;
  previous_request public.api_requests%rowtype;
  item_id_value uuid;
  asset_id_value uuid;
  current_item public.items%rowtype;
  current_asset public.assets%rowtype;
  result_item jsonb;
begin
  perform private.lock_library_owner(p_owner_id);

  if p_request_id is null then
    raise exception using errcode = 'P0001', message = 'INVALID_REQUEST_ID';
  end if;

  if p_method is null
    or p_path is null
    or p_body is null
    or pg_catalog.jsonb_typeof(p_body) <> 'object'
    or not (
      (
        p_method = 'POST'
        and (
          p_path ~ ('^/items/' || uuid_pattern || '/assets/reserve$')
          or p_path ~ (
            '^/items/' || uuid_pattern || '/assets/' || uuid_pattern || '/complete$'
          )
        )
      )
      or (
        p_method = 'DELETE'
        and p_path ~ (
          '^/items/' || uuid_pattern || '/assets/' || uuid_pattern || '$'
        )
      )
      or (
        p_method = 'PATCH'
        and p_path ~ (
          '^/items/' || uuid_pattern || '/assets/' || uuid_pattern || '/ocr$'
        )
      )
    ) then
    raise exception using errcode = 'P0001', message = 'INVALID_ASSET_REQUEST_IDENTITY';
  end if;

  method_path_value := p_method || ' ' || p_path;
  request_hash_value := private.asset_request_hash(method_path_value, p_body);

  select request.*
  into previous_request
  from public.api_requests as request
  where request.owner_id = p_owner_id
    and request.request_id = p_request_id
  for update;

  if not found then
    return pg_catalog.jsonb_build_object('found', false);
  end if;

  if previous_request.method_path <> method_path_value
    or previous_request.request_hash <> request_hash_value then
    raise exception using errcode = 'P0001', message = 'IDEMPOTENCY_MISMATCH';
  end if;

  item_id_value := pg_catalog.split_part(p_path, '/', 3)::uuid;
  if previous_request.response_body ->> 'item_id'
    is distinct from item_id_value::text then
    raise exception using errcode = 'P0001', message = 'INVALID_ASSET_RECEIPT';
  end if;

  select item.*
  into current_item
  from public.items as item
  where item.owner_id = p_owner_id
    and item.id = item_id_value
  for share;

  if not found then
    raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
  end if;
  if current_item.deleted_at is not null then
    raise exception using errcode = 'P0001', message = 'ITEM_DELETED';
  end if;

  if p_path !~ ('^/items/' || uuid_pattern || '/assets/reserve$') then
    asset_id_value := pg_catalog.split_part(p_path, '/', 5)::uuid;
    if previous_request.response_body ->> 'asset_id'
      is distinct from asset_id_value::text then
      raise exception using errcode = 'P0001', message = 'INVALID_ASSET_RECEIPT';
    end if;
  end if;

  if previous_request.response_code = 409 then
    return pg_catalog.jsonb_build_object(
      'found', true,
      'http_status', 409,
      'error_code', previous_request.response_body ->> 'error_code'
    );
  end if;

  if method_path_value ~ (
    '^POST /items/' || uuid_pattern || '/assets/reserve$'
  ) then
    if previous_request.response_code <> 201 then
      raise exception using errcode = 'P0001', message = 'INVALID_ASSET_RECEIPT';
    end if;

    asset_id_value := (previous_request.response_body ->> 'asset_id')::uuid;
    select asset.*
    into current_asset
    from public.assets as asset
    where asset.owner_id = p_owner_id
      and asset.item_id = item_id_value
      and asset.id = asset_id_value
    for share;

    if not found then
      raise exception using errcode = 'P0001', message = 'ASSET_NOT_FOUND';
    end if;
    if current_asset.object_path <>
      p_owner_id::text || '/' || item_id_value::text || '/' || asset_id_value::text then
      raise exception using errcode = 'P0001', message = 'ASSET_PATH_MISMATCH';
    end if;

    return pg_catalog.jsonb_build_object(
      'found', true,
      'http_status', 201,
      'asset_id', asset_id_value,
      'object_path', current_asset.object_path,
      'expires_at', current_asset.reservation_expires_at,
      'max_bytes', 2000000
    );
  end if;

  if previous_request.response_code = 200 then
    result_item := private.library_item_json(p_owner_id, item_id_value, true);
    if result_item is null then
      raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
    end if;

    return pg_catalog.jsonb_build_object(
      'found', true,
      'http_status', 200,
      'item', result_item
    );
  end if;

  if previous_request.response_code = 202
    and p_method = 'DELETE' then
    return pg_catalog.jsonb_build_object(
      'found', true,
      'http_status', 202,
      'asset_id', asset_id_value,
      'state', 'deleting'
    );
  end if;

  raise exception using errcode = 'P0001', message = 'INVALID_ASSET_RECEIPT';
exception
  when invalid_text_representation or numeric_value_out_of_range then
    raise exception using errcode = 'P0001', message = 'INVALID_ASSET_RECEIPT';
end;
$$;

create or replace function public.library_complete_asset(
  p_owner_id uuid,
  p_request_id uuid,
  p_item_id uuid,
  p_asset_id uuid,
  p_body jsonb,
  p_verified_object jsonb,
  p_prepared_index jsonb
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
  current_item public.items%rowtype;
  current_asset public.assets%rowtype;
  expected_version_value integer;
  ocr_state_value text;
  raw_ocr_text text;
  stored_ocr_text text;
  ocr_truncated_value boolean;
  verified_path text;
  verified_size bigint;
  verified_mime text;
  verified_width integer;
  verified_height integer;
  storage_id uuid;
  storage_size bigint;
  storage_mime text;
  failure_code text;
  result_item jsonb;
begin
  perform private.lock_library_owner(p_owner_id);

  if p_request_id is null then
    raise exception using errcode = 'P0001', message = 'INVALID_REQUEST_ID';
  end if;
  if p_item_id is null then
    raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
  end if;
  if p_asset_id is null then
    raise exception using errcode = 'P0001', message = 'ASSET_NOT_FOUND';
  end if;

  method_path_value := 'POST /items/' || p_item_id::text
    || '/assets/' || p_asset_id::text || '/complete';
  request_hash_value := private.asset_request_hash(method_path_value, p_body);

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

    select item.*
    into current_item
    from public.items as item
    where item.owner_id = p_owner_id
      and item.id = p_item_id;

    if not found then
      raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
    end if;
    if current_item.deleted_at is not null then
      raise exception using errcode = 'P0001', message = 'ITEM_DELETED';
    end if;

    if previous_request.response_code = 409 then
      return pg_catalog.jsonb_build_object(
        'http_status', 409,
        'error_code', previous_request.response_body ->> 'error_code'
      );
    end if;

    result_item := private.library_item_json(p_owner_id, p_item_id, true);
    if result_item is null then
      raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
    end if;

    return pg_catalog.jsonb_build_object('http_status', 200, 'item', result_item);
  end if;

  if not private.valid_asset_ocr_body(p_body, true) then
    raise exception using errcode = 'P0001', message = 'INVALID_BODY';
  end if;

  expected_version_value := private.asset_expected_version(p_body);
  ocr_state_value := p_body ->> 'ocr_state';
  raw_ocr_text := case
    when ocr_state_value = 'ready' then p_body ->> 'ocr_text'
    else null
  end;
  stored_ocr_text := case
    when ocr_state_value = 'ready' then pg_catalog.left(raw_ocr_text, 20000)
    else null
  end;
  ocr_truncated_value := ocr_state_value = 'ready'
    and (
      coalesce((p_body ->> 'ocr_truncated')::boolean, false)
      or pg_catalog.char_length(raw_ocr_text) > 20000
    );

  select item.*
  into current_item
  from public.items as item
  where item.owner_id = p_owner_id
    and item.id = p_item_id
  for update;

  if not found then
    raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
  end if;
  if current_item.deleted_at is not null then
    raise exception using errcode = 'P0001', message = 'ITEM_DELETED';
  end if;

  select asset.*
  into current_asset
  from public.assets as asset
  where asset.owner_id = p_owner_id
    and asset.item_id = p_item_id
    and asset.id = p_asset_id
  for update;

  if not found then
    raise exception using errcode = 'P0001', message = 'ASSET_NOT_FOUND';
  end if;

  if current_asset.state <> 'reserved' then
    failure_code := 'ASSET_NOT_RESERVED';
  elsif current_asset.reservation_expires_at <= pg_catalog.clock_timestamp() then
    failure_code := 'RESERVATION_EXPIRED';
  elsif current_item.version <> expected_version_value then
    failure_code := 'VERSION_CONFLICT';
  end if;

  if failure_code is not null then
    if current_asset.state = 'reserved' then
      update public.assets
      set state = 'deleting',
          ocr_state = 'not_requested',
          ocr_text = null,
          ocr_truncated = false,
          cleanup_reason = case
            when failure_code = 'RESERVATION_EXPIRED' then 'reservation_expired'
            when failure_code = 'VERSION_CONFLICT' then 'version_conflict'
            else 'invalid_upload'
          end,
          cleanup_next_run_at = pg_catalog.now(),
          deleted_at = pg_catalog.now(),
          updated_at = pg_catalog.now()
      where owner_id = p_owner_id
        and item_id = p_item_id
        and id = p_asset_id;
    end if;

    insert into public.api_requests (
      owner_id, request_id, method_path, request_hash, response_code, response_body
    ) values (
      p_owner_id,
      p_request_id,
      method_path_value,
      request_hash_value,
      409,
      pg_catalog.jsonb_build_object(
        'item_id', p_item_id,
        'asset_id', p_asset_id,
        'error_code', failure_code
      )
    );

    return pg_catalog.jsonb_build_object(
      'http_status', 409,
      'error_code', failure_code
    );
  end if;

  if not private.jsonb_has_exact_keys(
    p_verified_object,
    array['object_path', 'size', 'mime_type', 'width', 'height', 'present']::text[]
  )
    or pg_catalog.jsonb_typeof(p_verified_object -> 'object_path') <> 'string'
    or pg_catalog.jsonb_typeof(p_verified_object -> 'size') <> 'number'
    or pg_catalog.jsonb_typeof(p_verified_object -> 'mime_type') <> 'string'
    or pg_catalog.jsonb_typeof(p_verified_object -> 'width') <> 'number'
    or pg_catalog.jsonb_typeof(p_verified_object -> 'height') <> 'number'
    or pg_catalog.jsonb_typeof(p_verified_object -> 'present') <> 'boolean'
    or p_verified_object ->> 'size' !~ '^[0-9]+$'
    or p_verified_object ->> 'width' !~ '^[0-9]+$'
    or p_verified_object ->> 'height' !~ '^[0-9]+$'
    or pg_catalog.char_length(p_verified_object ->> 'size') > 19
    or pg_catalog.char_length(p_verified_object ->> 'width') > 10
    or pg_catalog.char_length(p_verified_object ->> 'height') > 10
    or (p_verified_object ->> 'size')::numeric > 9223372036854775807
    or (p_verified_object ->> 'width')::numeric > 2147483647
    or (p_verified_object ->> 'height')::numeric > 2147483647 then
    raise exception using errcode = 'P0001', message = 'INVALID_VERIFIED_OBJECT';
  end if;

  verified_path := p_verified_object ->> 'object_path';
  verified_size := (p_verified_object ->> 'size')::bigint;
  verified_mime := pg_catalog.lower(p_verified_object ->> 'mime_type');
  verified_width := (p_verified_object ->> 'width')::integer;
  verified_height := (p_verified_object ->> 'height')::integer;

  if not (p_verified_object ->> 'present')::boolean then
    failure_code := 'ASSET_OBJECT_MISSING';
  elsif verified_path <> current_asset.object_path then
    failure_code := 'ASSET_PATH_MISMATCH';
  elsif verified_size > 2000000 then
    failure_code := 'ASSET_TOO_LARGE';
  elsif verified_size < 1 then
    failure_code := 'ASSET_SIZE_INVALID';
  elsif verified_mime not in ('image/jpeg', 'image/png', 'image/webp')
    or verified_mime <> current_asset.reserved_mime_type then
    failure_code := 'ASSET_MIME_MISMATCH';
  elsif verified_width < 1 or verified_height < 1 then
    failure_code := 'ASSET_DIMENSIONS_INVALID';
  elsif verified_width::bigint * verified_height::bigint > 24000000 then
    failure_code := 'ASSET_PIXEL_LIMIT_EXCEEDED';
  end if;

  select
    object.id,
    case
      when object.metadata ->> 'size' ~ '^[0-9]+$'
        and pg_catalog.char_length(object.metadata ->> 'size') <= 19
      then case
        when (object.metadata ->> 'size')::numeric <= 9223372036854775807
          then (object.metadata ->> 'size')::bigint
        else null
      end
      else null
    end,
    pg_catalog.lower(object.metadata ->> 'mimetype')
  into storage_id, storage_size, storage_mime
  from storage.objects as object
  where object.bucket_id = 'library-images'
    and object.name = current_asset.object_path
  for share;

  if failure_code is null and not found then
    failure_code := 'ASSET_OBJECT_MISSING';
  elsif failure_code is null and storage_size is null then
    failure_code := 'ASSET_SIZE_INVALID';
  elsif failure_code is null and storage_size <> verified_size then
    failure_code := 'ASSET_SIZE_INVALID';
  elsif failure_code is null
    and (storage_mime is null or storage_mime <> verified_mime) then
    failure_code := 'ASSET_MIME_MISMATCH';
  end if;

  if failure_code is not null then
    update public.assets
    set state = 'deleting',
        ocr_state = 'not_requested',
        ocr_text = null,
        ocr_truncated = false,
        cleanup_reason = 'invalid_upload',
        cleanup_next_run_at = pg_catalog.now(),
        deleted_at = pg_catalog.now(),
        updated_at = pg_catalog.now()
    where owner_id = p_owner_id
      and item_id = p_item_id
      and id = p_asset_id;

    insert into public.api_requests (
      owner_id, request_id, method_path, request_hash, response_code, response_body
    ) values (
      p_owner_id,
      p_request_id,
      method_path_value,
      request_hash_value,
      409,
      pg_catalog.jsonb_build_object(
        'item_id', p_item_id,
        'asset_id', p_asset_id,
        'error_code', failure_code
      )
    );

    return pg_catalog.jsonb_build_object(
      'http_status', 409,
      'error_code', failure_code
    );
  end if;

  update public.assets
  set state = 'deleting',
      ocr_state = 'not_requested',
      ocr_text = null,
      ocr_truncated = false,
      cleanup_reason = 'replaced',
      cleanup_next_run_at = pg_catalog.now(),
      deleted_at = pg_catalog.now(),
      updated_at = pg_catalog.now()
  where owner_id = p_owner_id
    and item_id = p_item_id
    and state = 'active';

  update public.assets
  set state = 'active',
      actual_bytes = verified_size,
      mime_type = verified_mime,
      width = verified_width,
      height = verified_height,
      storage_object_id = storage_id,
      verified_at = pg_catalog.now(),
      ocr_state = ocr_state_value,
      ocr_text = stored_ocr_text,
      ocr_truncated = ocr_truncated_value,
      updated_at = pg_catalog.now()
  where owner_id = p_owner_id
    and item_id = p_item_id
    and id = p_asset_id;

  update public.library_usage
  set reserved_image_bytes = reserved_image_bytes - 2000000,
      used_image_bytes = used_image_bytes + verified_size,
      updated_at = pg_catalog.now()
  where owner_id = p_owner_id
    and reserved_image_bytes >= 2000000;

  if not found then
    raise exception using errcode = 'P0001', message = 'IMAGE_USAGE_CORRUPT';
  end if;

  perform private.apply_asset_text_change(
    p_owner_id,
    p_item_id,
    stored_ocr_text,
    p_prepared_index
  );

  insert into public.api_requests (
    owner_id, request_id, method_path, request_hash, response_code, response_body
  ) values (
    p_owner_id,
    p_request_id,
    method_path_value,
    request_hash_value,
    200,
    pg_catalog.jsonb_build_object(
      'item_id', p_item_id,
      'asset_id', p_asset_id,
      'http_status', 200
    )
  );

  result_item := private.library_item_json(p_owner_id, p_item_id, true);
  if result_item is null then
    raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
  end if;

  return pg_catalog.jsonb_build_object('http_status', 200, 'item', result_item);
exception
  when numeric_value_out_of_range then
    raise exception using errcode = 'P0001', message = 'INVALID_VERIFIED_OBJECT';
end;
$$;

create or replace function public.library_reject_asset_upload(
  p_owner_id uuid,
  p_request_id uuid,
  p_item_id uuid,
  p_asset_id uuid,
  p_body jsonb,
  p_failure_code text
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
  current_item public.items%rowtype;
  current_asset public.assets%rowtype;
  result_item jsonb;
begin
  perform private.lock_library_owner(p_owner_id);

  if p_request_id is null then
    raise exception using errcode = 'P0001', message = 'INVALID_REQUEST_ID';
  end if;
  if p_item_id is null then
    raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
  end if;
  if p_asset_id is null then
    raise exception using errcode = 'P0001', message = 'ASSET_NOT_FOUND';
  end if;
  if p_failure_code is null or p_failure_code not in (
    'ASSET_OBJECT_MISSING',
    'ASSET_PATH_MISMATCH',
    'ASSET_TOO_LARGE',
    'ASSET_SIZE_INVALID',
    'ASSET_MIME_MISMATCH',
    'ASSET_DECODE_FAILED',
    'ASSET_PIXEL_LIMIT_EXCEEDED',
    'ASSET_DIMENSIONS_INVALID'
  ) then
    raise exception using errcode = 'P0001', message = 'INVALID_FAILURE_CODE';
  end if;

  method_path_value := 'POST /items/' || p_item_id::text
    || '/assets/' || p_asset_id::text || '/complete';
  request_hash_value := private.asset_request_hash(method_path_value, p_body);

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

    select item.*
    into current_item
    from public.items as item
    where item.owner_id = p_owner_id
      and item.id = p_item_id;

    if not found then
      raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
    end if;
    if current_item.deleted_at is not null then
      raise exception using errcode = 'P0001', message = 'ITEM_DELETED';
    end if;

    if previous_request.response_code = 409 then
      return pg_catalog.jsonb_build_object(
        'http_status', 409,
        'error_code', previous_request.response_body ->> 'error_code'
      );
    end if;

    result_item := private.library_item_json(p_owner_id, p_item_id, true);
    if result_item is null then
      raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
    end if;
    return pg_catalog.jsonb_build_object('http_status', 200, 'item', result_item);
  end if;

  if not private.valid_asset_ocr_body(p_body, true) then
    raise exception using errcode = 'P0001', message = 'INVALID_BODY';
  end if;
  perform private.asset_expected_version(p_body);

  select item.*
  into current_item
  from public.items as item
  where item.owner_id = p_owner_id
    and item.id = p_item_id
  for update;

  if not found then
    raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
  end if;
  if current_item.deleted_at is not null then
    raise exception using errcode = 'P0001', message = 'ITEM_DELETED';
  end if;

  select asset.*
  into current_asset
  from public.assets as asset
  where asset.owner_id = p_owner_id
    and asset.item_id = p_item_id
    and asset.id = p_asset_id
  for update;

  if not found then
    raise exception using errcode = 'P0001', message = 'ASSET_NOT_FOUND';
  end if;

  if current_asset.state <> 'reserved' then
    p_failure_code := 'ASSET_NOT_RESERVED';
  elsif current_asset.reservation_expires_at <= pg_catalog.clock_timestamp() then
    p_failure_code := 'RESERVATION_EXPIRED';
  end if;

  if current_asset.state = 'reserved' then
    update public.assets
    set state = 'deleting',
        ocr_state = 'not_requested',
        ocr_text = null,
        ocr_truncated = false,
        cleanup_reason = case
          when p_failure_code = 'RESERVATION_EXPIRED' then 'reservation_expired'
          else 'invalid_upload'
        end,
        cleanup_next_run_at = pg_catalog.now(),
        deleted_at = pg_catalog.now(),
        updated_at = pg_catalog.now()
    where owner_id = p_owner_id
      and item_id = p_item_id
      and id = p_asset_id;
  end if;

  insert into public.api_requests (
    owner_id, request_id, method_path, request_hash, response_code, response_body
  ) values (
    p_owner_id,
    p_request_id,
    method_path_value,
    request_hash_value,
    409,
    pg_catalog.jsonb_build_object(
      'item_id', p_item_id,
      'asset_id', p_asset_id,
      'error_code', p_failure_code
    )
  );

  return pg_catalog.jsonb_build_object(
    'http_status', 409,
    'error_code', p_failure_code
  );
end;
$$;

create or replace function public.library_update_asset_ocr(
  p_owner_id uuid,
  p_request_id uuid,
  p_item_id uuid,
  p_asset_id uuid,
  p_body jsonb,
  p_prepared_index jsonb
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
  current_item public.items%rowtype;
  current_asset public.assets%rowtype;
  expected_version_value integer;
  ocr_state_value text;
  raw_ocr_text text;
  stored_ocr_text text;
  ocr_truncated_value boolean;
  failure_code text;
  result_item jsonb;
begin
  perform private.lock_library_owner(p_owner_id);

  if p_request_id is null then
    raise exception using errcode = 'P0001', message = 'INVALID_REQUEST_ID';
  end if;
  if p_item_id is null then
    raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
  end if;
  if p_asset_id is null then
    raise exception using errcode = 'P0001', message = 'ASSET_NOT_FOUND';
  end if;

  method_path_value := 'PATCH /items/' || p_item_id::text
    || '/assets/' || p_asset_id::text || '/ocr';
  request_hash_value := private.asset_request_hash(method_path_value, p_body);

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

    select item.*
    into current_item
    from public.items as item
    where item.owner_id = p_owner_id
      and item.id = p_item_id;

    if not found then
      raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
    end if;
    if current_item.deleted_at is not null then
      raise exception using errcode = 'P0001', message = 'ITEM_DELETED';
    end if;

    if previous_request.response_code = 409 then
      return pg_catalog.jsonb_build_object(
        'http_status', 409,
        'error_code', previous_request.response_body ->> 'error_code'
      );
    end if;

    result_item := private.library_item_json(p_owner_id, p_item_id, true);
    if result_item is null then
      raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
    end if;
    return pg_catalog.jsonb_build_object('http_status', 200, 'item', result_item);
  end if;

  if not private.valid_asset_ocr_body(p_body, false) then
    raise exception using errcode = 'P0001', message = 'INVALID_BODY';
  end if;

  expected_version_value := private.asset_expected_version(p_body);
  ocr_state_value := p_body ->> 'ocr_state';
  raw_ocr_text := case
    when ocr_state_value = 'ready' then p_body ->> 'ocr_text'
    else null
  end;
  stored_ocr_text := case
    when ocr_state_value = 'ready' then pg_catalog.left(raw_ocr_text, 20000)
    else null
  end;
  ocr_truncated_value := ocr_state_value = 'ready'
    and (
      coalesce((p_body ->> 'ocr_truncated')::boolean, false)
      or pg_catalog.char_length(raw_ocr_text) > 20000
    );

  select item.*
  into current_item
  from public.items as item
  where item.owner_id = p_owner_id
    and item.id = p_item_id
  for update;

  if not found then
    raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
  end if;
  if current_item.deleted_at is not null then
    raise exception using errcode = 'P0001', message = 'ITEM_DELETED';
  end if;

  select asset.*
  into current_asset
  from public.assets as asset
  where asset.owner_id = p_owner_id
    and asset.item_id = p_item_id
    and asset.id = p_asset_id
  for update;

  if not found then
    raise exception using errcode = 'P0001', message = 'ASSET_NOT_FOUND';
  end if;

  if current_item.version <> expected_version_value then
    failure_code := 'VERSION_CONFLICT';
  elsif current_asset.state <> 'active' then
    failure_code := 'ASSET_NOT_ACTIVE';
  end if;

  if failure_code is not null then
    insert into public.api_requests (
      owner_id, request_id, method_path, request_hash, response_code, response_body
    ) values (
      p_owner_id,
      p_request_id,
      method_path_value,
      request_hash_value,
      409,
      pg_catalog.jsonb_build_object(
        'item_id', p_item_id,
        'asset_id', p_asset_id,
        'error_code', failure_code
      )
    );

    return pg_catalog.jsonb_build_object(
      'http_status', 409,
      'error_code', failure_code
    );
  end if;

  update public.assets
  set ocr_state = ocr_state_value,
      ocr_text = stored_ocr_text,
      ocr_truncated = ocr_truncated_value,
      updated_at = pg_catalog.now()
  where owner_id = p_owner_id
    and item_id = p_item_id
    and id = p_asset_id;

  perform private.apply_asset_text_change(
    p_owner_id,
    p_item_id,
    stored_ocr_text,
    p_prepared_index
  );

  insert into public.api_requests (
    owner_id, request_id, method_path, request_hash, response_code, response_body
  ) values (
    p_owner_id,
    p_request_id,
    method_path_value,
    request_hash_value,
    200,
    pg_catalog.jsonb_build_object(
      'item_id', p_item_id,
      'asset_id', p_asset_id,
      'http_status', 200
    )
  );

  result_item := private.library_item_json(p_owner_id, p_item_id, true);
  if result_item is null then
    raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
  end if;

  return pg_catalog.jsonb_build_object('http_status', 200, 'item', result_item);
end;
$$;

revoke all on function private.valid_asset_ocr_body(jsonb, boolean)
from public, anon, authenticated, service_role;

revoke all on function public.library_replay_asset_request(
  uuid,
  uuid,
  text,
  text,
  jsonb
) from public, anon, authenticated, service_role;
revoke all on function public.library_complete_asset(
  uuid,
  uuid,
  uuid,
  uuid,
  jsonb,
  jsonb,
  jsonb
) from public, anon, authenticated, service_role;
revoke all on function public.library_reject_asset_upload(
  uuid,
  uuid,
  uuid,
  uuid,
  jsonb,
  text
) from public, anon, authenticated, service_role;
revoke all on function public.library_update_asset_ocr(
  uuid,
  uuid,
  uuid,
  uuid,
  jsonb,
  jsonb
) from public, anon, authenticated, service_role;

grant execute on function public.library_replay_asset_request(
  uuid,
  uuid,
  text,
  text,
  jsonb
) to service_role;
grant execute on function public.library_complete_asset(
  uuid,
  uuid,
  uuid,
  uuid,
  jsonb,
  jsonb,
  jsonb
) to service_role;
grant execute on function public.library_reject_asset_upload(
  uuid,
  uuid,
  uuid,
  uuid,
  jsonb,
  text
) to service_role;
grant execute on function public.library_update_asset_ocr(
  uuid,
  uuid,
  uuid,
  uuid,
  jsonb,
  jsonb
) to service_role;
