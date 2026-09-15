create or replace function private.valid_library_api_receipt(
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
  if p_method_path = 'POST /items' then
    return (
      p_response_code in (200, 201)
      and private.jsonb_has_exact_keys(
        p_response_body,
        array['item_id', 'duplicate', 'http_status']::text[]
      )
      and pg_catalog.jsonb_typeof(p_response_body -> 'item_id') = 'string'
      and p_response_body ->> 'item_id' ~* ('^' || uuid_pattern || '$')
      and pg_catalog.jsonb_typeof(p_response_body -> 'duplicate') = 'boolean'
      and pg_catalog.jsonb_typeof(p_response_body -> 'http_status') = 'number'
      and (p_response_body ->> 'http_status')::integer = p_response_code
      and (
        (p_response_code = 200 and (p_response_body ->> 'duplicate')::boolean)
        or (p_response_code = 201 and not (p_response_body ->> 'duplicate')::boolean)
      )
    ) or (
      p_response_code = 409
      and private.jsonb_has_exact_keys(p_response_body, array['error_code']::text[])
      and p_response_body ->> 'error_code' in ('ITEM_LIMIT_REACHED', 'URL_HASH_COLLISION')
    );
  end if;

  if p_method_path ~* ('^PATCH /items/' || uuid_pattern || '$')
    or p_method_path ~* ('^POST /items/' || uuid_pattern || '/cue-dismiss$') then
    return (
      p_response_code = 200
      and private.jsonb_has_exact_keys(
        p_response_body,
        array['item_id', 'http_status']::text[]
      )
      and p_response_body ->> 'item_id' ~* ('^' || uuid_pattern || '$')
      and (p_response_body ->> 'http_status')::integer = 200
    ) or (
      p_response_code = 409
      and private.jsonb_has_exact_keys(
        p_response_body,
        array['item_id', 'error_code']::text[]
      )
      and p_response_body ->> 'item_id' ~* ('^' || uuid_pattern || '$')
      and p_response_body ->> 'error_code' = 'VERSION_CONFLICT'
    );
  end if;

  if p_method_path ~* ('^POST /items/' || uuid_pattern || '/(reclassify|retry-metadata)$') then
    return (
      p_response_code = 202
      and private.jsonb_has_exact_keys(
        p_response_body,
        array['item_id', 'job_id', 'http_status']::text[]
      )
      and p_response_body ->> 'item_id' ~* ('^' || uuid_pattern || '$')
      and p_response_body ->> 'job_id' ~* ('^' || uuid_pattern || '$')
      and (p_response_body ->> 'http_status')::integer = 202
    ) or (
      p_response_code = 409
      and private.jsonb_has_exact_keys(
        p_response_body,
        array['item_id', 'error_code']::text[]
      )
      and p_response_body ->> 'item_id' ~* ('^' || uuid_pattern || '$')
      and p_response_body ->> 'error_code' = 'VERSION_CONFLICT'
    );
  end if;

  if p_method_path = 'POST /categories' then
    return (
      p_response_code = 201
      and private.jsonb_has_exact_keys(
        p_response_body,
        array['category_id', 'http_status']::text[]
      )
      and p_response_body ->> 'category_id' ~* ('^' || uuid_pattern || '$')
      and (p_response_body ->> 'http_status')::integer = 201
    ) or (
      p_response_code = 409
      and private.jsonb_has_exact_keys(p_response_body, array['error_code']::text[])
      and p_response_body ->> 'error_code' in (
        'CATEGORY_NAME_EXISTS',
        'CATEGORY_LIMIT_REACHED'
      )
    );
  end if;

  if p_method_path ~* ('^PATCH /categories/' || uuid_pattern || '$') then
    return (
      p_response_code = 200
      and private.jsonb_has_exact_keys(
        p_response_body,
        array['category_id', 'http_status']::text[]
      )
      and p_response_body ->> 'category_id' ~* ('^' || uuid_pattern || '$')
      and (p_response_body ->> 'http_status')::integer = 200
    ) or (
      p_response_code = 409
      and private.jsonb_has_exact_keys(
        p_response_body,
        array['category_id', 'error_code']::text[]
      )
      and p_response_body ->> 'category_id' ~* ('^' || uuid_pattern || '$')
      and p_response_body ->> 'error_code' = 'CATEGORY_NAME_EXISTS'
    );
  end if;

  if p_method_path ~* ('^DELETE /categories/' || uuid_pattern || '$') then
    return p_response_code = 204
      and private.jsonb_has_exact_keys(
        p_response_body,
        array['category_id', 'http_status']::text[]
      )
      and p_response_body ->> 'category_id' ~* ('^' || uuid_pattern || '$')
      and (p_response_body ->> 'http_status')::integer = 204;
  end if;

  if p_method_path ~* ('^POST /items/' || uuid_pattern || '/assets/reserve$') then
    return (
      p_response_code = 201
      and private.jsonb_has_exact_keys(
        p_response_body,
        array['item_id', 'asset_id', 'http_status']::text[]
      )
      and p_response_body ->> 'item_id' ~* ('^' || uuid_pattern || '$')
      and p_response_body ->> 'asset_id' ~* ('^' || uuid_pattern || '$')
      and (p_response_body ->> 'http_status')::integer = 201
    ) or (
      p_response_code = 409
      and private.jsonb_has_exact_keys(
        p_response_body,
        array['item_id', 'error_code']::text[]
      )
      and p_response_body ->> 'item_id' ~* ('^' || uuid_pattern || '$')
      and p_response_body ->> 'error_code' in (
        'VERSION_CONFLICT',
        'ASSET_RESERVATION_EXISTS',
        'STORAGE_LIMIT_REACHED'
      )
    );
  end if;

  if p_method_path ~* (
    '^POST /items/' || uuid_pattern || '/assets/' || uuid_pattern || '/complete$'
  ) then
    return (
      p_response_code = 200
      and private.jsonb_has_exact_keys(
        p_response_body,
        array['item_id', 'asset_id', 'http_status']::text[]
      )
      and p_response_body ->> 'item_id' ~* ('^' || uuid_pattern || '$')
      and p_response_body ->> 'asset_id' ~* ('^' || uuid_pattern || '$')
      and (p_response_body ->> 'http_status')::integer = 200
    ) or (
      p_response_code = 409
      and private.jsonb_has_exact_keys(
        p_response_body,
        array['item_id', 'asset_id', 'error_code']::text[]
      )
      and p_response_body ->> 'item_id' ~* ('^' || uuid_pattern || '$')
      and p_response_body ->> 'asset_id' ~* ('^' || uuid_pattern || '$')
      and p_response_body ->> 'error_code' in (
        'VERSION_CONFLICT',
        'RESERVATION_EXPIRED',
        'ASSET_NOT_RESERVED',
        'ASSET_OBJECT_MISSING',
        'ASSET_PATH_MISMATCH',
        'ASSET_TOO_LARGE',
        'ASSET_SIZE_INVALID',
        'ASSET_MIME_MISMATCH',
        'ASSET_DECODE_FAILED',
        'ASSET_PIXEL_LIMIT_EXCEEDED',
        'ASSET_DIMENSIONS_INVALID'
      )
    );
  end if;

  if p_method_path ~* (
    '^DELETE /items/' || uuid_pattern || '/assets/' || uuid_pattern || '$'
  ) then
    return (
      p_response_code = 202
      and private.jsonb_has_exact_keys(
        p_response_body,
        array['item_id', 'asset_id', 'http_status']::text[]
      )
      and p_response_body ->> 'item_id' ~* ('^' || uuid_pattern || '$')
      and p_response_body ->> 'asset_id' ~* ('^' || uuid_pattern || '$')
      and (p_response_body ->> 'http_status')::integer = 202
    ) or (
      p_response_code = 409
      and private.jsonb_has_exact_keys(
        p_response_body,
        array['item_id', 'asset_id', 'error_code']::text[]
      )
      and p_response_body ->> 'item_id' ~* ('^' || uuid_pattern || '$')
      and p_response_body ->> 'asset_id' ~* ('^' || uuid_pattern || '$')
      and p_response_body ->> 'error_code' in ('VERSION_CONFLICT', 'ASSET_NOT_ACTIVE')
    );
  end if;

  if p_method_path ~* (
    '^PATCH /items/' || uuid_pattern || '/assets/' || uuid_pattern || '/ocr$'
  ) then
    return (
      p_response_code = 200
      and private.jsonb_has_exact_keys(
        p_response_body,
        array['item_id', 'asset_id', 'http_status']::text[]
      )
      and p_response_body ->> 'item_id' ~* ('^' || uuid_pattern || '$')
      and p_response_body ->> 'asset_id' ~* ('^' || uuid_pattern || '$')
      and (p_response_body ->> 'http_status')::integer = 200
    ) or (
      p_response_code = 409
      and private.jsonb_has_exact_keys(
        p_response_body,
        array['item_id', 'asset_id', 'error_code']::text[]
      )
      and p_response_body ->> 'item_id' ~* ('^' || uuid_pattern || '$')
      and p_response_body ->> 'asset_id' ~* ('^' || uuid_pattern || '$')
      and p_response_body ->> 'error_code' in ('VERSION_CONFLICT', 'ASSET_NOT_ACTIVE')
    );
  end if;

  return false;
exception
  when others then
    return false;
end;
$$;

alter table public.api_requests
  drop constraint api_requests_method_path_check,
  drop constraint api_requests_response_code_check,
  drop constraint api_requests_response_body_check;

alter table public.api_requests
  add constraint api_requests_method_path_check check (
    method_path = 'POST /items'
    or method_path = 'POST /categories'
    or method_path ~* '^PATCH /items/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    or method_path ~* '^PATCH /categories/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    or method_path ~* '^DELETE /categories/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    or method_path ~* '^POST /items/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/(cue-dismiss|reclassify|retry-metadata)$'
    or method_path ~* '^POST /items/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/assets/reserve$'
    or method_path ~* '^POST /items/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/assets/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/complete$'
    or method_path ~* '^DELETE /items/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/assets/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    or method_path ~* '^PATCH /items/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/assets/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/ocr$'
  ),
  add constraint api_requests_response_code_check check (
    response_code in (200, 201, 202, 204, 409)
  ),
  add constraint api_requests_response_body_check check (
    private.valid_library_api_receipt(method_path, response_code, response_body) is true
  );

create table public.assets (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null,
  item_id uuid not null,
  object_path text not null unique,
  state text not null default 'reserved',
  reserved_bytes bigint not null default 2000000,
  reserved_mime_type text not null,
  actual_bytes bigint,
  mime_type text,
  width integer,
  height integer,
  storage_object_id uuid,
  verified_at timestamptz,
  ocr_state text not null default 'not_requested',
  ocr_text text,
  ocr_truncated boolean not null default false,
  reservation_expires_at timestamptz not null,
  cleanup_reason text,
  cleanup_attempts integer not null default 0,
  cleanup_next_run_at timestamptz,
  cleanup_lease_until timestamptz,
  cleanup_lease_token uuid,
  cleanup_error_code text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  constraint assets_owner_item_id_key unique (owner_id, item_id, id),
  constraint assets_item_fkey
    foreign key (owner_id, item_id)
    references public.items (owner_id, id),
  constraint assets_state_check check (state in ('reserved', 'active', 'deleting')),
  constraint assets_reserved_bytes_check check (reserved_bytes = 2000000),
  constraint assets_reserved_mime_type_check check (
    reserved_mime_type in ('image/jpeg', 'image/png', 'image/webp')
  ),
  constraint assets_object_path_check check (
    object_path = owner_id::text || '/' || item_id::text || '/' || id::text
  ),
  constraint assets_actual_file_check check (
    (
      actual_bytes is null
      and mime_type is null
      and width is null
      and height is null
      and storage_object_id is null
      and verified_at is null
    )
    or (
      actual_bytes is not null
      and mime_type is not null
      and width is not null
      and height is not null
      and storage_object_id is not null
      and verified_at is not null
      and actual_bytes between 1 and 2000000
      and mime_type in ('image/jpeg', 'image/png', 'image/webp')
      and width > 0
      and height > 0
      and width::bigint * height::bigint <= 24000000
    )
  ),
  constraint assets_ocr_state_check check (
    ocr_state in ('not_requested', 'ready', 'failed')
  ),
  constraint assets_ocr_text_check check (
    (
      ocr_state = 'ready'
      and ocr_text is not null
      and pg_catalog.char_length(ocr_text) <= 20000
    )
    or (
      ocr_state in ('not_requested', 'failed')
      and ocr_text is null
      and not ocr_truncated
    )
  ),
  constraint assets_ocr_truncated_check check (
    not ocr_truncated or ocr_state = 'ready'
  ),
  constraint assets_cleanup_attempts_check check (cleanup_attempts >= 0),
  constraint assets_cleanup_error_code_check check (
    cleanup_error_code is null
    or pg_catalog.char_length(cleanup_error_code) between 1 and 100
  ),
  constraint assets_cleanup_lease_check check (
    (
      cleanup_lease_until is null
      and cleanup_lease_token is null
    )
    or (
      state = 'deleting'
      and cleanup_lease_until is not null
      and cleanup_lease_token is not null
    )
  ),
  constraint assets_lifecycle_check check (
    (
      state = 'reserved'
      and actual_bytes is null
      and deleted_at is null
      and cleanup_reason is null
      and cleanup_next_run_at is null
      and cleanup_lease_until is null
      and cleanup_lease_token is null
    )
    or (
      state = 'active'
      and actual_bytes is not null
      and deleted_at is null
      and cleanup_reason is null
      and cleanup_next_run_at is null
      and cleanup_lease_until is null
      and cleanup_lease_token is null
    )
    or (
      state = 'deleting'
      and deleted_at is not null
      and cleanup_reason in (
        'replaced',
        'user_delete',
        'reservation_expired',
        'version_conflict',
        'invalid_upload',
        'item_delete',
        'account_delete'
      )
      and cleanup_next_run_at is not null
      and ocr_state = 'not_requested'
      and ocr_text is null
      and not ocr_truncated
    )
  )
);

create unique index assets_one_active_per_item_key
on public.assets (owner_id, item_id)
where state = 'active';

create unique index assets_one_reserved_per_item_key
on public.assets (owner_id, item_id)
where state = 'reserved';

create index assets_cleanup_ready_idx
on public.assets (cleanup_next_run_at, deleted_at, id)
where state = 'deleting';

create table private.asset_cleanup_receipts (
  asset_id uuid primary key,
  owner_id uuid not null,
  item_id uuid not null,
  lease_token uuid not null,
  released_reserved_bytes bigint not null,
  released_used_bytes bigint not null,
  completed_at timestamptz not null default now(),
  constraint asset_cleanup_receipts_reserved_check check (
    released_reserved_bytes in (0, 2000000)
  ),
  constraint asset_cleanup_receipts_used_check check (released_used_bytes >= 0),
  constraint asset_cleanup_receipts_single_counter_check check (
    released_reserved_bytes = 0 or released_used_bytes = 0
  )
);

insert into storage.buckets (
  id,
  name,
  public,
  file_size_limit,
  allowed_mime_types
)
values (
  'library-images',
  'library-images',
  false,
  2000000,
  array['image/jpeg', 'image/png', 'image/webp']::text[]
);

alter table public.assets enable row level security;

create policy assets_select_own_active_member
on public.assets
for select
to authenticated
using (
  state = 'active'
  and public.member_access_allowed(owner_id)
  and exists (
    select 1
    from public.items as parent_item
    where parent_item.owner_id = assets.owner_id
      and parent_item.id = assets.item_id
      and parent_item.deleted_at is null
  )
);

create function public.library_image_upload_allowed(
  p_bucket_id text,
  p_object_path text
)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  if p_bucket_id <> 'library-images' or auth.uid() is null then
    return false;
  end if;

  perform 1
  from public.assets as asset
  join public.items as item
    on item.owner_id = asset.owner_id
    and item.id = asset.item_id
  join public.profiles as profile
    on profile.id = asset.owner_id
    and profile.state = 'active'
  join public.beta_members as member
    on member.owner_id = asset.owner_id
    and member.enabled
    and member.approved_at is not null
  where asset.owner_id = auth.uid()
    and asset.object_path = p_object_path
    and asset.state = 'reserved'
    and asset.reservation_expires_at > pg_catalog.clock_timestamp()
    and item.deleted_at is null
  for update of asset;

  return found;
end;
$$;

create policy library_images_insert_reserved_exact
on storage.objects
for insert
to authenticated
with check (public.library_image_upload_allowed(bucket_id, name));

create function private.asset_request_hash(
  p_method_path text,
  p_body jsonb
)
returns text
language sql
immutable
parallel safe
set search_path = ''
as $$
  select pg_catalog.encode(
    extensions.digest(
      pg_catalog.convert_to(
        p_method_path || pg_catalog.chr(10) || coalesce(p_body::text, 'null'),
        'UTF8'
      ),
      'sha256'
    ),
    'hex'
  );
$$;

create function private.asset_expected_version(p_body jsonb)
returns integer
language plpgsql
immutable
parallel safe
set search_path = ''
as $$
begin
  if p_body is null
    or pg_catalog.jsonb_typeof(p_body) <> 'object'
    or not (p_body ? 'expected_version')
    or pg_catalog.jsonb_typeof(p_body -> 'expected_version') <> 'number'
    or p_body ->> 'expected_version' !~ '^[1-9][0-9]*$'
    or pg_catalog.char_length(p_body ->> 'expected_version') > 10
    or (p_body ->> 'expected_version')::numeric > 2147483647 then
    raise exception using errcode = 'P0001', message = 'INVALID_BODY';
  end if;

  return (p_body ->> 'expected_version')::integer;
exception
  when numeric_value_out_of_range then
    raise exception using errcode = 'P0001', message = 'INVALID_BODY';
end;
$$;

create function private.apply_asset_text_change(
  p_owner_id uuid,
  p_item_id uuid,
  p_ocr_text text,
  p_prepared_index jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  current_item public.items%rowtype;
  current_search public.item_search%rowtype;
  current_classification public.item_classification%rowtype;
  current_controls public.item_category_controls%rowtype;
  normalized_ocr text;
  new_revision integer;
  new_version integer;
  has_classification_text boolean;
  metadata_was_active boolean;
begin
  select item.*
  into current_item
  from public.items as item
  where item.owner_id = p_owner_id
    and item.id = p_item_id
  for update;

  if not found or current_item.deleted_at is not null then
    raise exception using errcode = 'P0001', message = 'ITEM_DELETED';
  end if;

  select search_record.*
  into current_search
  from public.item_search as search_record
  where search_record.owner_id = p_owner_id
    and search_record.item_id = p_item_id
  for update;

  select classification.*
  into current_classification
  from public.item_classification as classification
  where classification.owner_id = p_owner_id
    and classification.item_id = p_item_id
  for update;

  select controls.*
  into current_controls
  from public.item_category_controls as controls
  where controls.owner_id = p_owner_id
    and controls.item_id = p_item_id
  for update;

  if current_search.item_id is null
    or current_classification.item_id is null
    or current_controls.item_id is null then
    raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
  end if;

  normalized_ocr := pg_catalog.btrim(
    pg_catalog.regexp_replace(
      pg_catalog.lower(normalize(coalesce(p_ocr_text, ''), NFKC)),
      '[[:space:]]+',
      ' ',
      'g'
    )
  );

  if not private.valid_metadata_prepared(p_prepared_index, current_item.version)
    or p_prepared_index #>> '{normalized_fields,ocr}' <> normalized_ocr
    or (p_prepared_index -> 'normalized_fields') - 'ocr'
      <> current_search.normalized_fields - 'ocr'
    or (p_prepared_index -> 'alias_concepts') - 'ocr'
      <> current_search.alias_concepts - 'ocr' then
    raise exception using errcode = 'P0001', message = 'INVALID_PREPARED';
  end if;

  has_classification_text := exists (
    select 1
    from pg_catalog.jsonb_each_text(
      p_prepared_index -> 'normalized_fields'
    ) as field(key, value)
    where field.key in (
      'user_title',
      'fetched_title',
      'note',
      'ocr',
      'shared',
      'description',
      'body'
    )
      and pg_catalog.btrim(field.value) <> ''
  );

  select exists (
    select 1
    from public.processing_jobs as job
    where job.owner_id = p_owner_id
      and job.item_id = p_item_id
      and job.kind = 'metadata'
      and job.state in ('queued', 'running', 'retry')
  )
  into metadata_was_active;

  update public.processing_jobs
  set state = 'cancelled',
      lease_until = null,
      lease_token = null,
      updated_at = pg_catalog.now()
  where owner_id = p_owner_id
    and item_id = p_item_id
    and kind in ('metadata', 'classify')
    and state in ('queued', 'running', 'retry');

  new_revision := current_item.text_revision + 1;
  new_version := current_item.version + 1;

  update public.items
  set text_revision = new_revision,
      version = new_version,
      metadata_state = case
        when metadata_was_active then 'queued'
        else metadata_state
      end,
      updated_at = pg_catalog.now()
  where owner_id = p_owner_id
    and id = p_item_id;

  update public.item_search
  set text_revision = new_revision,
      normalized_fields = p_prepared_index -> 'normalized_fields',
      alias_concepts = p_prepared_index -> 'alias_concepts',
      cue_version = 'cues-v1.0.0',
      cue_state = p_prepared_index ->> 'cue_state',
      cue_flags = p_prepared_index -> 'cue_flags',
      updated_at = pg_catalog.now()
  where owner_id = p_owner_id
    and item_id = p_item_id;

  update public.item_classification
  set target_revision = new_revision,
      state = case
        when current_controls.manual_override then 'manual'
        when has_classification_text then 'pending'
        else 'unclassified'
      end,
      reasons = '[]'::jsonb,
      updated_at = pg_catalog.now()
  where owner_id = p_owner_id
    and item_id = p_item_id;

  if metadata_was_active then
    insert into public.processing_jobs (
      owner_id,
      item_id,
      kind,
      target_revision,
      state
    )
    values (p_owner_id, p_item_id, 'metadata', new_revision, 'queued')
    on conflict (owner_id, item_id, kind, target_revision)
      where state in ('queued', 'running', 'retry')
    do nothing;
  end if;

  if not current_controls.manual_override and has_classification_text then
    insert into public.processing_jobs (
      owner_id,
      item_id,
      kind,
      target_revision,
      state
    )
    values (p_owner_id, p_item_id, 'classify', new_revision, 'queued')
    on conflict (owner_id, item_id, kind, target_revision)
      where state in ('queued', 'running', 'retry')
    do nothing;
  end if;

  return pg_catalog.jsonb_build_object(
    'version', new_version,
    'text_revision', new_revision
  );
exception
  when numeric_value_out_of_range then
    raise exception using errcode = 'P0001', message = 'INVALID_PREPARED';
end;
$$;

create or replace function private.library_item_json(
  p_owner_id uuid,
  p_item_id uuid,
  p_include_detail boolean
)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select
    pg_catalog.jsonb_build_object(
      'id', item.id,
      'version', item.version,
      'text_revision', item.text_revision,
      'url', item.original_url,
      'display_title', coalesce(
        nullif(pg_catalog.btrim(item.user_title), ''),
        nullif(pg_catalog.btrim(item.fetched_title), ''),
        item.display_fallback
      ),
      'source', item.source,
      'note_excerpt', case
        when item.note is null then null
        else pg_catalog.left(item.note, 120)
      end,
      'category_refs', coalesce(
        (
          select pg_catalog.jsonb_agg(
            pg_catalog.jsonb_build_object(
              'id', category.id,
              'name', category.name,
              'kind', category.kind,
              'system_code', category.system_code,
              'origin', selected.origin
            )
            order by selected.created_at, category.id
          )
          from public.item_categories as selected
          join public.categories as category
            on category.owner_id = selected.owner_id
            and category.id = selected.category_id
          where selected.owner_id = item.owner_id
            and selected.item_id = item.id
        ),
        '[]'::jsonb
      ),
      'has_attachment', active_asset.id is not null,
      'metadata_state', item.metadata_state,
      'ocr_state', coalesce(active_asset.ocr_state, 'not_requested'),
      'classification_state', classification.state,
      'cue_state', search_record.cue_state,
      'cue_flags', search_record.cue_flags,
      'match_type', null,
      'created_at', item.created_at,
      'updated_at', item.updated_at
    )
    || case
      when p_include_detail then pg_catalog.jsonb_build_object(
        'user_title', item.user_title,
        'fetched_title', item.fetched_title,
        'shared_text', item.shared_text,
        'description', item.description,
        'body_text', item.body_text,
        'note', item.note,
        'extraction_meta', item.extraction_meta,
        'active_asset', case
          when active_asset.id is null then null
          else pg_catalog.jsonb_build_object(
            'id', active_asset.id,
            'object_path', active_asset.object_path,
            'mime_type', active_asset.mime_type,
            'byte_size', active_asset.actual_bytes,
            'width', active_asset.width,
            'height', active_asset.height,
            'ocr_text', active_asset.ocr_text,
            'ocr_truncated', active_asset.ocr_truncated
          )
        end,
        'manual_override', controls.manual_override,
        'search_version', case
          when search_record.text_revision = item.text_revision
            then search_record.search_version
          else null
        end,
        'rules_version', case
          when classification.target_revision = item.text_revision
            then classification.rules_version
          else null
        end,
        'classification_reasons', case
          when classification.target_revision = item.text_revision
            then classification.reasons
          else '[]'::jsonb
        end,
        'cue_prompt_dismissed', coalesce(
          controls.cue_dismissed_revision = item.text_revision,
          false
        )
      )
      else '{}'::jsonb
    end
  from public.items as item
  join public.item_search as search_record
    on search_record.owner_id = item.owner_id
    and search_record.item_id = item.id
  join public.item_classification as classification
    on classification.owner_id = item.owner_id
    and classification.item_id = item.id
  join public.item_category_controls as controls
    on controls.owner_id = item.owner_id
    and controls.item_id = item.id
  join public.profiles as profile
    on profile.id = item.owner_id
    and profile.state = 'active'
  join public.beta_members as member
    on member.owner_id = item.owner_id
    and member.enabled
    and member.approved_at is not null
  left join public.assets as active_asset
    on active_asset.owner_id = item.owner_id
    and active_asset.item_id = item.id
    and active_asset.state = 'active'
  where item.owner_id = p_owner_id
    and item.id = p_item_id
    and item.deleted_at is null;
$$;

create function public.library_reserve_asset(
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
  reserved_asset public.assets%rowtype;
  expected_version_value integer;
  mime_type_value text;
  usage_used bigint;
  usage_reserved bigint;
  asset_id_value uuid;
  object_path_value text;
  expires_at_value timestamptz;
begin
  perform private.lock_library_owner(p_owner_id);

  if p_request_id is null then
    raise exception using errcode = 'P0001', message = 'INVALID_REQUEST_ID';
  end if;
  if p_item_id is null then
    raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
  end if;

  method_path_value := 'POST /items/' || p_item_id::text || '/assets/reserve';
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

    select asset.*
    into reserved_asset
    from public.assets as asset
    where asset.owner_id = p_owner_id
      and asset.item_id = p_item_id
      and asset.id = (previous_request.response_body ->> 'asset_id')::uuid;

    if not found then
      raise exception using errcode = 'P0001', message = 'ASSET_NOT_FOUND';
    end if;

    return pg_catalog.jsonb_build_object(
      'http_status', 201,
      'asset_id', reserved_asset.id,
      'object_path', reserved_asset.object_path,
      'expires_at', reserved_asset.reservation_expires_at,
      'max_bytes', 2000000
    );
  end if;

  if not private.jsonb_has_exact_keys(
    p_body,
    array['expected_version', 'mime_type']::text[]
  )
    or pg_catalog.jsonb_typeof(p_body -> 'mime_type') <> 'string' then
    raise exception using errcode = 'P0001', message = 'INVALID_BODY';
  end if;

  expected_version_value := private.asset_expected_version(p_body);
  mime_type_value := p_body ->> 'mime_type';
  if mime_type_value not in ('image/jpeg', 'image/png', 'image/webp') then
    raise exception using errcode = 'P0001', message = 'INVALID_BODY';
  end if;

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

  if current_item.version <> expected_version_value then
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
        'error_code', 'VERSION_CONFLICT'
      )
    );

    return pg_catalog.jsonb_build_object(
      'http_status', 409,
      'error_code', 'VERSION_CONFLICT'
    );
  end if;

  update public.assets
  set state = 'deleting',
      cleanup_reason = 'reservation_expired',
      cleanup_next_run_at = pg_catalog.now(),
      deleted_at = pg_catalog.now(),
      updated_at = pg_catalog.now()
  where owner_id = p_owner_id
    and item_id = p_item_id
    and state = 'reserved'
    and reservation_expires_at <= pg_catalog.clock_timestamp();

  if exists (
    select 1
    from public.assets as asset
    where asset.owner_id = p_owner_id
      and asset.item_id = p_item_id
      and asset.state = 'reserved'
  ) then
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
        'error_code', 'ASSET_RESERVATION_EXISTS'
      )
    );

    return pg_catalog.jsonb_build_object(
      'http_status', 409,
      'error_code', 'ASSET_RESERVATION_EXISTS'
    );
  end if;

  select usage.used_image_bytes, usage.reserved_image_bytes
  into usage_used, usage_reserved
  from public.library_usage as usage
  where usage.owner_id = p_owner_id;

  if usage_used + usage_reserved + 2000000 > 20000000 then
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
        'error_code', 'STORAGE_LIMIT_REACHED'
      )
    );

    return pg_catalog.jsonb_build_object(
      'http_status', 409,
      'error_code', 'STORAGE_LIMIT_REACHED'
    );
  end if;

  asset_id_value := gen_random_uuid();
  object_path_value := p_owner_id::text || '/' || p_item_id::text || '/' || asset_id_value::text;
  expires_at_value := pg_catalog.clock_timestamp() + interval '15 minutes';

  insert into public.assets (
    id,
    owner_id,
    item_id,
    object_path,
    state,
    reserved_bytes,
    reserved_mime_type,
    reservation_expires_at
  ) values (
    asset_id_value,
    p_owner_id,
    p_item_id,
    object_path_value,
    'reserved',
    2000000,
    mime_type_value,
    expires_at_value
  );

  update public.library_usage
  set reserved_image_bytes = reserved_image_bytes + 2000000,
      updated_at = pg_catalog.now()
  where owner_id = p_owner_id;

  insert into public.api_requests (
    owner_id, request_id, method_path, request_hash, response_code, response_body
  ) values (
    p_owner_id,
    p_request_id,
    method_path_value,
    request_hash_value,
    201,
    pg_catalog.jsonb_build_object(
      'item_id', p_item_id,
      'asset_id', asset_id_value,
      'http_status', 201
    )
  );

  return pg_catalog.jsonb_build_object(
    'http_status', 201,
    'asset_id', asset_id_value,
    'object_path', object_path_value,
    'expires_at', expires_at_value,
    'max_bytes', 2000000
  );
end;
$$;

create function public.library_complete_asset(
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

  if p_body is null
    or pg_catalog.jsonb_typeof(p_body) <> 'object'
    or not (p_body ? 'expected_version')
    or not (p_body ? 'ocr_state')
    or exists (
      select 1
      from pg_catalog.jsonb_object_keys(p_body) as property(key)
      where property.key not in ('expected_version', 'ocr_state', 'ocr_text')
    )
    or pg_catalog.jsonb_typeof(p_body -> 'ocr_state') <> 'string'
    or p_body ->> 'ocr_state' not in ('ready', 'failed', 'not_requested') then
    raise exception using errcode = 'P0001', message = 'INVALID_BODY';
  end if;

  expected_version_value := private.asset_expected_version(p_body);
  ocr_state_value := p_body ->> 'ocr_state';

  if (ocr_state_value = 'ready' and (
      not (p_body ? 'ocr_text')
      or pg_catalog.jsonb_typeof(p_body -> 'ocr_text') <> 'string'
    ))
    or (ocr_state_value in ('failed', 'not_requested') and (
      p_body ? 'ocr_text'
      and pg_catalog.jsonb_typeof(p_body -> 'ocr_text') <> 'null'
    )) then
    raise exception using errcode = 'P0001', message = 'INVALID_BODY';
  end if;

  raw_ocr_text := case when ocr_state_value = 'ready' then p_body ->> 'ocr_text' else null end;
  stored_ocr_text := case when ocr_state_value = 'ready' then pg_catalog.left(raw_ocr_text, 20000) else null end;
  ocr_truncated_value := ocr_state_value = 'ready'
    and pg_catalog.char_length(raw_ocr_text) > 20000;

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

create function public.library_reject_asset_upload(
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
    return pg_catalog.jsonb_build_object('http_status', 200, 'item', result_item);
  end if;

  if p_body is null
    or pg_catalog.jsonb_typeof(p_body) <> 'object'
    or not (p_body ? 'expected_version')
    or not (p_body ? 'ocr_state')
    or exists (
      select 1
      from pg_catalog.jsonb_object_keys(p_body) as property(key)
      where property.key not in ('expected_version', 'ocr_state', 'ocr_text')
    )
    or pg_catalog.jsonb_typeof(p_body -> 'ocr_state') <> 'string'
    or p_body ->> 'ocr_state' not in ('ready', 'failed', 'not_requested')
    or (
      p_body ->> 'ocr_state' = 'ready'
      and (
        not (p_body ? 'ocr_text')
        or pg_catalog.jsonb_typeof(p_body -> 'ocr_text') <> 'string'
      )
    )
    or (
      p_body ->> 'ocr_state' in ('failed', 'not_requested')
      and p_body ? 'ocr_text'
      and pg_catalog.jsonb_typeof(p_body -> 'ocr_text') <> 'null'
    ) then
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

create function public.library_delete_asset(
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
  failure_code text;
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

  method_path_value := 'DELETE /items/' || p_item_id::text || '/assets/' || p_asset_id::text;
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

    return pg_catalog.jsonb_build_object(
      'http_status', 202,
      'asset_id', p_asset_id,
      'state', 'deleting'
    );
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
  set state = 'deleting',
      ocr_state = 'not_requested',
      ocr_text = null,
      ocr_truncated = false,
      cleanup_reason = 'user_delete',
      cleanup_next_run_at = pg_catalog.now(),
      deleted_at = pg_catalog.now(),
      updated_at = pg_catalog.now()
  where owner_id = p_owner_id
    and item_id = p_item_id
    and id = p_asset_id;

  perform private.apply_asset_text_change(
    p_owner_id,
    p_item_id,
    null,
    p_prepared_index
  );

  insert into public.api_requests (
    owner_id, request_id, method_path, request_hash, response_code, response_body
  ) values (
    p_owner_id,
    p_request_id,
    method_path_value,
    request_hash_value,
    202,
    pg_catalog.jsonb_build_object(
      'item_id', p_item_id,
      'asset_id', p_asset_id,
      'http_status', 202
    )
  );

  return pg_catalog.jsonb_build_object(
    'http_status', 202,
    'asset_id', p_asset_id,
    'state', 'deleting'
  );
end;
$$;

create function public.library_update_asset_ocr(
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

  if p_body is null
    or pg_catalog.jsonb_typeof(p_body) <> 'object'
    or not (p_body ? 'expected_version')
    or not (p_body ? 'ocr_state')
    or exists (
      select 1
      from pg_catalog.jsonb_object_keys(p_body) as property(key)
      where property.key not in ('expected_version', 'ocr_state', 'ocr_text')
    )
    or pg_catalog.jsonb_typeof(p_body -> 'ocr_state') <> 'string'
    or p_body ->> 'ocr_state' not in ('ready', 'failed') then
    raise exception using errcode = 'P0001', message = 'INVALID_BODY';
  end if;

  expected_version_value := private.asset_expected_version(p_body);
  ocr_state_value := p_body ->> 'ocr_state';
  if (ocr_state_value = 'ready' and (
      not (p_body ? 'ocr_text')
      or pg_catalog.jsonb_typeof(p_body -> 'ocr_text') <> 'string'
    ))
    or (ocr_state_value = 'failed' and (
      p_body ? 'ocr_text'
      and pg_catalog.jsonb_typeof(p_body -> 'ocr_text') <> 'null'
    )) then
    raise exception using errcode = 'P0001', message = 'INVALID_BODY';
  end if;

  raw_ocr_text := case when ocr_state_value = 'ready' then p_body ->> 'ocr_text' else null end;
  stored_ocr_text := case when ocr_state_value = 'ready' then pg_catalog.left(raw_ocr_text, 20000) else null end;
  ocr_truncated_value := ocr_state_value = 'ready'
    and pg_catalog.char_length(raw_ocr_text) > 20000;

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

create function public.library_claim_asset_cleanup_jobs(p_limit integer default 10)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  jobs jsonb;
begin
  if p_limit is null or p_limit < 1 or p_limit > 10 then
    raise exception using errcode = 'P0001', message = 'INVALID_LIMIT';
  end if;

  update public.assets
  set state = 'deleting',
      ocr_state = 'not_requested',
      ocr_text = null,
      ocr_truncated = false,
      cleanup_reason = 'reservation_expired',
      cleanup_next_run_at = pg_catalog.now(),
      deleted_at = pg_catalog.now(),
      updated_at = pg_catalog.now()
  where state = 'reserved'
    and reservation_expires_at <= pg_catalog.clock_timestamp();

  with candidates as materialized (
    select asset.id
    from public.assets as asset
    where asset.state = 'deleting'
      and asset.cleanup_next_run_at <= pg_catalog.clock_timestamp()
      and (
        asset.cleanup_lease_until is null
        or asset.cleanup_lease_until <= pg_catalog.clock_timestamp()
      )
    order by
      case when asset.actual_bytes is not null then 0 else 1 end,
      asset.cleanup_next_run_at,
      asset.deleted_at,
      asset.id
    for update skip locked
    limit p_limit
  ),
  claimed as (
    update public.assets as asset
    set cleanup_attempts = asset.cleanup_attempts + 1,
        cleanup_lease_token = gen_random_uuid(),
        cleanup_lease_until = pg_catalog.clock_timestamp() + interval '180 seconds',
        cleanup_error_code = null,
        updated_at = pg_catalog.now()
    from candidates
    where asset.id = candidates.id
    returning asset.*
  )
  select coalesce(
    pg_catalog.jsonb_agg(
      pg_catalog.jsonb_build_object(
        'asset_id', claimed.id,
        'owner_id', claimed.owner_id,
        'item_id', claimed.item_id,
        'bucket_id', 'library-images',
        'object_path', claimed.object_path,
        'storage_object_id', claimed.storage_object_id,
        'cleanup_reason', claimed.cleanup_reason,
        'attempts', claimed.cleanup_attempts,
        'lease_token', claimed.cleanup_lease_token,
        'lease_until', claimed.cleanup_lease_until
      )
      order by
        case when claimed.actual_bytes is not null then 0 else 1 end,
        claimed.cleanup_next_run_at,
        claimed.deleted_at,
        claimed.id
    ),
    '[]'::jsonb
  )
  into jobs
  from claimed;

  return pg_catalog.jsonb_build_object('http_status', 200, 'jobs', jobs);
end;
$$;

create function public.library_finish_asset_cleanup(
  p_asset_id uuid,
  p_lease_token uuid
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  completed private.asset_cleanup_receipts%rowtype;
  current_asset public.assets%rowtype;
  owner_id_value uuid;
  release_reserved bigint;
  release_used bigint;
begin
  if p_asset_id is null or p_lease_token is null then
    raise exception using errcode = 'P0001', message = 'INVALID_CLEANUP_IDENTITY';
  end if;

  select receipt.*
  into completed
  from private.asset_cleanup_receipts as receipt
  where receipt.asset_id = p_asset_id;

  if found then
    if completed.lease_token <> p_lease_token then
      return pg_catalog.jsonb_build_object(
        'http_status', 409,
        'error_code', 'LEASE_LOST'
      );
    end if;

    return pg_catalog.jsonb_build_object(
      'http_status', 200,
      'state', 'complete',
      'asset_id', completed.asset_id,
      'released_reserved_bytes', completed.released_reserved_bytes,
      'released_used_bytes', completed.released_used_bytes
    );
  end if;

  select asset.owner_id
  into owner_id_value
  from public.assets as asset
  where asset.id = p_asset_id;

  if not found then
    select receipt.*
    into completed
    from private.asset_cleanup_receipts as receipt
    where receipt.asset_id = p_asset_id;

    if not found then
      raise exception using errcode = 'P0001', message = 'ASSET_CLEANUP_NOT_FOUND';
    end if;
    if completed.lease_token <> p_lease_token then
      return pg_catalog.jsonb_build_object(
        'http_status', 409,
        'error_code', 'LEASE_LOST'
      );
    end if;

    return pg_catalog.jsonb_build_object(
      'http_status', 200,
      'state', 'complete',
      'asset_id', completed.asset_id,
      'released_reserved_bytes', completed.released_reserved_bytes,
      'released_used_bytes', completed.released_used_bytes
    );
  end if;

  perform 1
  from public.library_usage as usage
  where usage.owner_id = owner_id_value
  for update;

  if not found then
    raise exception using errcode = 'P0001', message = 'IMAGE_USAGE_CORRUPT';
  end if;

  select asset.*
  into current_asset
  from public.assets as asset
  where asset.id = p_asset_id
  for update;

  if not found then
    select receipt.*
    into completed
    from private.asset_cleanup_receipts as receipt
    where receipt.asset_id = p_asset_id;

    if not found then
      raise exception using errcode = 'P0001', message = 'ASSET_CLEANUP_NOT_FOUND';
    end if;
    if completed.lease_token <> p_lease_token then
      return pg_catalog.jsonb_build_object(
        'http_status', 409,
        'error_code', 'LEASE_LOST'
      );
    end if;

    return pg_catalog.jsonb_build_object(
      'http_status', 200,
      'state', 'complete',
      'asset_id', completed.asset_id,
      'released_reserved_bytes', completed.released_reserved_bytes,
      'released_used_bytes', completed.released_used_bytes
    );
  end if;

  if current_asset.state <> 'deleting'
    or current_asset.cleanup_lease_token is distinct from p_lease_token
    or current_asset.cleanup_lease_until <= pg_catalog.clock_timestamp() then
    return pg_catalog.jsonb_build_object(
      'http_status', 409,
      'error_code', 'LEASE_LOST'
    );
  end if;

  if exists (
    select 1
    from storage.objects as object
    where object.bucket_id = 'library-images'
      and object.name = current_asset.object_path
  ) then
    return pg_catalog.jsonb_build_object(
      'http_status', 409,
      'error_code', 'STORAGE_OBJECT_PRESENT'
    );
  end if;

  release_reserved := case when current_asset.actual_bytes is null then 2000000 else 0 end;
  release_used := coalesce(current_asset.actual_bytes, 0);

  update public.library_usage
  set reserved_image_bytes = reserved_image_bytes - release_reserved,
      used_image_bytes = used_image_bytes - release_used,
      updated_at = pg_catalog.now()
  where owner_id = current_asset.owner_id
    and reserved_image_bytes >= release_reserved
    and used_image_bytes >= release_used;

  if not found then
    raise exception using errcode = 'P0001', message = 'IMAGE_USAGE_CORRUPT';
  end if;

  insert into private.asset_cleanup_receipts (
    asset_id,
    owner_id,
    item_id,
    lease_token,
    released_reserved_bytes,
    released_used_bytes
  ) values (
    current_asset.id,
    current_asset.owner_id,
    current_asset.item_id,
    p_lease_token,
    release_reserved,
    release_used
  );

  delete from public.assets
  where id = current_asset.id;

  return pg_catalog.jsonb_build_object(
    'http_status', 200,
    'state', 'complete',
    'asset_id', current_asset.id,
    'released_reserved_bytes', release_reserved,
    'released_used_bytes', release_used
  );
end;
$$;

create function public.library_fail_asset_cleanup(
  p_asset_id uuid,
  p_lease_token uuid,
  p_error_code text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  completed private.asset_cleanup_receipts%rowtype;
  current_asset public.assets%rowtype;
  next_run_value timestamptz;
begin
  if p_asset_id is null or p_lease_token is null then
    raise exception using errcode = 'P0001', message = 'INVALID_CLEANUP_IDENTITY';
  end if;
  if p_error_code is null
    or pg_catalog.char_length(p_error_code) not between 1 and 100 then
    raise exception using errcode = 'P0001', message = 'INVALID_ERROR_CODE';
  end if;

  select receipt.*
  into completed
  from private.asset_cleanup_receipts as receipt
  where receipt.asset_id = p_asset_id;

  if found then
    if completed.lease_token <> p_lease_token then
      return pg_catalog.jsonb_build_object(
        'http_status', 409,
        'error_code', 'LEASE_LOST'
      );
    end if;

    return pg_catalog.jsonb_build_object(
      'http_status', 200,
      'state', 'complete',
      'asset_id', completed.asset_id,
      'released_reserved_bytes', completed.released_reserved_bytes,
      'released_used_bytes', completed.released_used_bytes
    );
  end if;

  select asset.*
  into current_asset
  from public.assets as asset
  where asset.id = p_asset_id
  for update;

  if not found then
    raise exception using errcode = 'P0001', message = 'ASSET_CLEANUP_NOT_FOUND';
  end if;

  if current_asset.state <> 'deleting'
    or current_asset.cleanup_lease_token is distinct from p_lease_token
    or current_asset.cleanup_lease_until <= pg_catalog.clock_timestamp() then
    return pg_catalog.jsonb_build_object(
      'http_status', 409,
      'error_code', 'LEASE_LOST'
    );
  end if;

  next_run_value := pg_catalog.clock_timestamp() + case
    when current_asset.cleanup_attempts <= 1 then interval '1 minute'
    when current_asset.cleanup_attempts = 2 then interval '5 minutes'
    else interval '30 minutes'
  end;

  update public.assets
  set cleanup_next_run_at = next_run_value,
      cleanup_lease_until = null,
      cleanup_lease_token = null,
      cleanup_error_code = p_error_code,
      updated_at = pg_catalog.now()
  where id = p_asset_id;

  return pg_catalog.jsonb_build_object(
    'http_status', 202,
    'state', 'retry',
    'asset_id', p_asset_id,
    'next_run_at', next_run_value
  );
end;
$$;

revoke all privileges on table public.assets
from public, anon, authenticated, service_role;
grant select on table public.assets to authenticated;
grant select, insert, update, delete on table public.assets to service_role;

revoke all privileges on table private.asset_cleanup_receipts
from public, anon, authenticated, service_role;

revoke all on function private.valid_library_api_receipt(text, integer, jsonb)
from public, anon, authenticated, service_role;
revoke all on function private.asset_request_hash(text, jsonb)
from public, anon, authenticated, service_role;
revoke all on function private.asset_expected_version(jsonb)
from public, anon, authenticated, service_role;
revoke all on function private.apply_asset_text_change(uuid, uuid, text, jsonb)
from public, anon, authenticated, service_role;
revoke all on function private.library_item_json(uuid, uuid, boolean)
from public, anon, authenticated, service_role;
grant execute on function private.valid_library_api_receipt(text, integer, jsonb)
to service_role;

revoke all on function public.library_image_upload_allowed(text, text)
from public, anon, authenticated, service_role;
grant execute on function public.library_image_upload_allowed(text, text)
to authenticated;

revoke all on function public.library_reserve_asset(uuid, uuid, uuid, jsonb)
from public, anon, authenticated, service_role;
revoke all on function public.library_complete_asset(uuid, uuid, uuid, uuid, jsonb, jsonb, jsonb)
from public, anon, authenticated, service_role;
revoke all on function public.library_reject_asset_upload(uuid, uuid, uuid, uuid, jsonb, text)
from public, anon, authenticated, service_role;
revoke all on function public.library_delete_asset(uuid, uuid, uuid, uuid, jsonb, jsonb)
from public, anon, authenticated, service_role;
revoke all on function public.library_update_asset_ocr(uuid, uuid, uuid, uuid, jsonb, jsonb)
from public, anon, authenticated, service_role;
revoke all on function public.library_claim_asset_cleanup_jobs(integer)
from public, anon, authenticated, service_role;
revoke all on function public.library_finish_asset_cleanup(uuid, uuid)
from public, anon, authenticated, service_role;
revoke all on function public.library_fail_asset_cleanup(uuid, uuid, text)
from public, anon, authenticated, service_role;

grant execute on function public.library_reserve_asset(uuid, uuid, uuid, jsonb)
to service_role;
grant execute on function public.library_complete_asset(uuid, uuid, uuid, uuid, jsonb, jsonb, jsonb)
to service_role;
grant execute on function public.library_reject_asset_upload(uuid, uuid, uuid, uuid, jsonb, text)
to service_role;
grant execute on function public.library_delete_asset(uuid, uuid, uuid, uuid, jsonb, jsonb)
to service_role;
grant execute on function public.library_update_asset_ocr(uuid, uuid, uuid, uuid, jsonb, jsonb)
to service_role;
grant execute on function public.library_claim_asset_cleanup_jobs(integer)
to service_role;
grant execute on function public.library_finish_asset_cleanup(uuid, uuid)
to service_role;
grant execute on function public.library_fail_asset_cleanup(uuid, uuid, text)
to service_role;
