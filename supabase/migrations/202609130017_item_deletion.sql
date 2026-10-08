create table private.item_deletion_tombstones (
  owner_id uuid not null,
  item_id uuid not null,
  deleted_at timestamptz not null,
  primary key (owner_id, item_id)
);

create index item_deletion_tombstones_deleted_at_idx
on private.item_deletion_tombstones (deleted_at, owner_id, item_id);

revoke all privileges on table private.item_deletion_tombstones
from public, anon, authenticated, service_role;

create function private.valid_item_deletion_receipt(
  p_method_path text,
  p_response_code integer,
  p_response_body jsonb
)
returns boolean
language plpgsql
immutable
parallel safe
set search_path = ''
as $$
declare
  uuid_pattern constant text := '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}';
begin
  if p_method_path !~ ('^DELETE /items/' || uuid_pattern || '$') then
    return false;
  end if;

  if p_response_code = 202 then
    return private.jsonb_has_exact_keys(
        p_response_body,
        array['item_id', 'http_status', 'state']::text[]
      )
      and pg_catalog.jsonb_typeof(p_response_body -> 'item_id') = 'string'
      and p_response_body ->> 'item_id' = pg_catalog.split_part(p_method_path, '/', 3)
      and pg_catalog.jsonb_typeof(p_response_body -> 'http_status') = 'number'
      and (p_response_body ->> 'http_status')::integer = 202
      and pg_catalog.jsonb_typeof(p_response_body -> 'state') = 'string'
      and p_response_body ->> 'state' = 'deleting';
  end if;

  if p_response_code = 409 then
    return private.jsonb_has_exact_keys(
        p_response_body,
        array['item_id', 'error_code']::text[]
      )
      and pg_catalog.jsonb_typeof(p_response_body -> 'item_id') = 'string'
      and p_response_body ->> 'item_id' = pg_catalog.split_part(p_method_path, '/', 3)
      and p_response_body ->> 'error_code' = 'VERSION_CONFLICT';
  end if;

  return false;
exception
  when others then
    return false;
end;
$$;

alter table public.api_requests
  drop constraint api_requests_method_path_check,
  drop constraint api_requests_response_body_check;

alter table public.api_requests
  add constraint api_requests_method_path_check check (
    method_path = 'POST /items'
    or method_path = 'POST /categories'
    or method_path ~* '^PATCH /items/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    or method_path ~* '^DELETE /items/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    or method_path ~* '^PATCH /categories/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    or method_path ~* '^DELETE /categories/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    or method_path ~* '^POST /items/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/(cue-dismiss|reclassify|retry-metadata)$'
    or method_path ~* '^POST /items/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/assets/reserve$'
    or method_path ~* '^POST /items/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/assets/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/complete$'
    or method_path ~* '^DELETE /items/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/assets/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    or method_path ~* '^PATCH /items/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/assets/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/ocr$'
  ),
  add constraint api_requests_response_body_check check (
    private.valid_library_api_receipt(method_path, response_code, response_body) is true
    or private.valid_item_deletion_receipt(method_path, response_code, response_body) is true
  );

alter table public.items
  alter column original_url drop not null,
  alter column normalized_url drop not null,
  alter column url_hash drop not null,
  alter column source drop not null,
  alter column display_fallback drop not null,
  alter column metadata_state drop not null;

insert into private.item_deletion_tombstones (owner_id, item_id, deleted_at)
select item.owner_id, item.id, item.deleted_at
from public.items as item
where item.deleted_at is not null
on conflict (owner_id, item_id) do nothing;

update public.processing_jobs as job
set state = 'cancelled',
    lease_until = null,
    lease_token = null,
    last_error_code = null,
    updated_at = pg_catalog.now()
from public.items as item
where item.owner_id = job.owner_id
  and item.id = job.item_id
  and item.deleted_at is not null
  and job.state in ('queued', 'running', 'retry');

delete from public.item_categories as selected
using public.items as item
where item.owner_id = selected.owner_id
  and item.id = selected.item_id
  and item.deleted_at is not null;

delete from public.item_category_controls as controls
using public.items as item
where item.owner_id = controls.owner_id
  and item.id = controls.item_id
  and item.deleted_at is not null;

delete from public.item_classification as classification
using public.items as item
where item.owner_id = classification.owner_id
  and item.id = classification.item_id
  and item.deleted_at is not null;

delete from public.item_search as search_record
using public.items as item
where item.owner_id = search_record.owner_id
  and item.id = search_record.item_id
  and item.deleted_at is not null;

update public.assets as asset
set state = 'deleting',
    ocr_state = 'not_requested',
    ocr_text = null,
    ocr_truncated = false,
    cleanup_reason = 'item_delete',
    cleanup_next_run_at = pg_catalog.now(),
    cleanup_lease_until = null,
    cleanup_lease_token = null,
    cleanup_error_code = null,
    deleted_at = coalesce(asset.deleted_at, pg_catalog.now()),
    updated_at = pg_catalog.now()
from public.items as item
where item.owner_id = asset.owner_id
  and item.id = asset.item_id
  and item.deleted_at is not null
  and asset.state in ('reserved', 'active');

update public.items
set original_url = null,
    normalized_url = null,
    url_hash = null,
    source = null,
    display_fallback = null,
    shared_text = null,
    user_title = null,
    fetched_title = null,
    description = null,
    body_text = null,
    note = null,
    extraction_meta = '{}'::jsonb,
    metadata_state = null,
    updated_at = pg_catalog.now()
where deleted_at is not null;

alter table public.items
  add constraint items_deletion_privacy_check check (
    (
      deleted_at is null
      and original_url is not null
      and normalized_url is not null
      and url_hash is not null
      and source is not null
      and display_fallback is not null
      and metadata_state is not null
    )
    or (
      deleted_at is not null
      and original_url is null
      and normalized_url is null
      and url_hash is null
      and source is null
      and display_fallback is null
      and shared_text is null
      and user_title is null
      and fetched_title is null
      and description is null
      and body_text is null
      and note is null
      and extraction_meta = '{}'::jsonb
      and metadata_state is null
    )
  );

create function public.library_delete_item(
  p_owner_id uuid,
  p_request_id uuid,
  p_item_id uuid,
  p_body jsonb
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
  expected_version_value integer;
  deletion_time timestamptz;
  affected_count integer;
begin
  perform private.lock_library_owner(p_owner_id);

  if p_request_id is null then
    raise exception using errcode = 'P0001', message = 'INVALID_REQUEST_ID';
  end if;
  if p_item_id is null then
    raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
  end if;

  method_path_value := 'DELETE /items/' || p_item_id::text;
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

    if previous_request.response_code = 202 then
      return pg_catalog.jsonb_build_object(
        'http_status', 202,
        'item_id', p_item_id,
        'state', 'deleting'
      );
    end if;
    if previous_request.response_code = 409
      and previous_request.response_body ->> 'error_code' = 'VERSION_CONFLICT' then
      return pg_catalog.jsonb_build_object(
        'http_status', 409,
        'error_code', 'VERSION_CONFLICT'
      );
    end if;

    raise exception using errcode = 'P0001', message = 'INVALID_DELETE_RECEIPT';
  end if;

  if not private.jsonb_has_exact_keys(p_body, array['expected_version']::text[]) then
    raise exception using errcode = 'P0001', message = 'INVALID_BODY';
  end if;
  expected_version_value := private.asset_expected_version(p_body);

  select item.*
  into current_item
  from public.items as item
  where item.owner_id = p_owner_id
    and item.id = p_item_id
  for update;

  if not found or current_item.deleted_at is not null then
    raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
  end if;

  if current_item.version <> expected_version_value then
    insert into public.api_requests (
      owner_id,
      request_id,
      method_path,
      request_hash,
      response_code,
      response_body
    ) values (
      p_owner_id,
      p_request_id,
      method_path_value,
      request_hash_value,
      409,
      pg_catalog.jsonb_build_object(
        'item_id', p_item_id,
        'error_code', 'VERSION_CONFLICT'
      )
    );

    return pg_catalog.jsonb_build_object(
      'http_status', 409,
      'error_code', 'VERSION_CONFLICT'
    );
  end if;

  deletion_time := pg_catalog.clock_timestamp();

  insert into private.item_deletion_tombstones (owner_id, item_id, deleted_at)
  values (p_owner_id, p_item_id, deletion_time)
  on conflict (owner_id, item_id) do nothing;

  update public.items
  set original_url = null,
      normalized_url = null,
      url_hash = null,
      source = null,
      display_fallback = null,
      shared_text = null,
      user_title = null,
      fetched_title = null,
      description = null,
      body_text = null,
      note = null,
      extraction_meta = '{}'::jsonb,
      metadata_state = null,
      version = version + 1,
      updated_at = deletion_time,
      deleted_at = deletion_time
  where owner_id = p_owner_id
    and id = p_item_id
    and deleted_at is null
    and version = expected_version_value;
  get diagnostics affected_count = row_count;

  if affected_count <> 1 then
    raise exception using errcode = '40001', message = 'DELETE_COMMIT_CONFLICT';
  end if;

  update public.processing_jobs
  set state = 'cancelled',
      lease_until = null,
      lease_token = null,
      last_error_code = null,
      updated_at = deletion_time
  where owner_id = p_owner_id
    and item_id = p_item_id
    and state in ('queued', 'running', 'retry');

  delete from public.item_categories
  where owner_id = p_owner_id
    and item_id = p_item_id;

  delete from public.item_category_controls
  where owner_id = p_owner_id
    and item_id = p_item_id;

  delete from public.item_classification
  where owner_id = p_owner_id
    and item_id = p_item_id;

  delete from public.item_search
  where owner_id = p_owner_id
    and item_id = p_item_id;

  update public.assets
  set state = 'deleting',
      ocr_state = 'not_requested',
      ocr_text = null,
      ocr_truncated = false,
      cleanup_reason = 'item_delete',
      cleanup_next_run_at = deletion_time,
      cleanup_lease_until = null,
      cleanup_lease_token = null,
      cleanup_error_code = null,
      deleted_at = deletion_time,
      updated_at = deletion_time
  where owner_id = p_owner_id
    and item_id = p_item_id
    and state in ('reserved', 'active');

  update public.library_usage
  set active_item_count = active_item_count - 1,
      updated_at = deletion_time
  where owner_id = p_owner_id
    and active_item_count > 0;
  get diagnostics affected_count = row_count;

  if affected_count <> 1 then
    raise exception using errcode = 'P0001', message = 'ITEM_USAGE_CORRUPT';
  end if;

  insert into public.api_requests (
    owner_id,
    request_id,
    method_path,
    request_hash,
    response_code,
    response_body
  ) values (
    p_owner_id,
    p_request_id,
    method_path_value,
    request_hash_value,
    202,
    pg_catalog.jsonb_build_object(
      'item_id', p_item_id,
      'http_status', 202,
      'state', 'deleting'
    )
  );

  return pg_catalog.jsonb_build_object(
    'http_status', 202,
    'item_id', p_item_id,
    'state', 'deleting'
  );
end;
$$;

create function public.library_purge_deleted_items(p_limit integer default 10)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  purged_item_count integer;
begin
  if p_limit is null or p_limit < 1 or p_limit > 10 then
    raise exception using errcode = 'P0001', message = 'INVALID_LIMIT';
  end if;

  with candidates as materialized (
    select item.owner_id, item.id
    from public.items as item
    where item.deleted_at is not null
      and item.deleted_at <= pg_catalog.clock_timestamp() - interval '30 days'
      and not exists (
        select 1
        from public.assets as asset
        where asset.owner_id = item.owner_id
          and asset.item_id = item.id
      )
      and not exists (
        select 1
        from storage.objects as object
        where object.bucket_id = 'library-images'
          and object.name like item.owner_id::text || '/' || item.id::text || '/%'
      )
      and not exists (
        select 1
        from public.api_requests as request
        where request.owner_id = item.owner_id
          and request.response_body ->> 'item_id' = item.id::text
      )
    order by item.deleted_at, item.owner_id, item.id
    for update of item skip locked
    limit p_limit
  ), deleted_items as (
    delete from public.items as item
    using candidates
    where item.owner_id = candidates.owner_id
      and item.id = candidates.id
    returning item.id
  )
  select count(*)::integer
  into purged_item_count
  from deleted_items;

  return pg_catalog.jsonb_build_object('purged_items', purged_item_count);
end;
$$;

revoke all on function private.valid_item_deletion_receipt(text, integer, jsonb)
from public, anon, authenticated, service_role;
revoke all on function public.library_delete_item(uuid, uuid, uuid, jsonb)
from public, anon, authenticated, service_role;
revoke all on function public.library_purge_deleted_items(integer)
from public, anon, authenticated, service_role;

grant execute on function public.library_delete_item(uuid, uuid, uuid, jsonb)
to service_role;
grant execute on function public.library_purge_deleted_items(integer)
to service_role;
