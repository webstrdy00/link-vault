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
    or method_path ~* '^POST /items/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/cue-dismiss$'
    or method_path ~* '^POST /items/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/reclassify$'
  ),
  add constraint api_requests_response_code_check check (
    response_code in (200, 201, 202, 204, 409)
  ),
  add constraint api_requests_response_body_check check (
    (
      method_path = 'POST /items'
      and (
        (
          response_code in (200, 201)
          and private.jsonb_has_exact_keys(
            response_body,
            array['item_id', 'duplicate', 'http_status']::text[]
          )
          and pg_catalog.jsonb_typeof(response_body -> 'item_id') = 'string'
          and response_body ->> 'item_id' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
          and pg_catalog.jsonb_typeof(response_body -> 'duplicate') = 'boolean'
          and pg_catalog.jsonb_typeof(response_body -> 'http_status') = 'number'
          and (response_body ->> 'http_status')::integer = response_code
          and (
            (response_code = 200 and (response_body ->> 'duplicate')::boolean)
            or (response_code = 201 and not (response_body ->> 'duplicate')::boolean)
          )
        )
        or (
          response_code = 409
          and private.jsonb_has_exact_keys(
            response_body,
            array['error_code']::text[]
          )
          and pg_catalog.jsonb_typeof(response_body -> 'error_code') = 'string'
          and response_body ->> 'error_code' in (
            'ITEM_LIMIT_REACHED',
            'URL_HASH_COLLISION'
          )
        )
      )
    )
    or (
      (
        method_path ~* '^PATCH /items/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        or method_path ~* '^POST /items/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/cue-dismiss$'
      )
      and (
        (
          response_code = 200
          and private.jsonb_has_exact_keys(
            response_body,
            array['item_id', 'http_status']::text[]
          )
          and pg_catalog.jsonb_typeof(response_body -> 'item_id') = 'string'
          and response_body ->> 'item_id' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
          and pg_catalog.jsonb_typeof(response_body -> 'http_status') = 'number'
          and (response_body ->> 'http_status')::integer = response_code
        )
        or (
          response_code = 409
          and private.jsonb_has_exact_keys(
            response_body,
            array['item_id', 'error_code']::text[]
          )
          and pg_catalog.jsonb_typeof(response_body -> 'item_id') = 'string'
          and response_body ->> 'item_id' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
          and pg_catalog.jsonb_typeof(response_body -> 'error_code') = 'string'
          and response_body ->> 'error_code' = 'VERSION_CONFLICT'
        )
      )
    )
    or (
      method_path ~* '^POST /items/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/reclassify$'
      and (
        (
          response_code = 202
          and private.jsonb_has_exact_keys(
            response_body,
            array['item_id', 'job_id', 'http_status']::text[]
          )
          and pg_catalog.jsonb_typeof(response_body -> 'item_id') = 'string'
          and response_body ->> 'item_id' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
          and pg_catalog.jsonb_typeof(response_body -> 'job_id') = 'string'
          and response_body ->> 'job_id' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
          and pg_catalog.jsonb_typeof(response_body -> 'http_status') = 'number'
          and (response_body ->> 'http_status')::integer = response_code
        )
        or (
          response_code = 409
          and private.jsonb_has_exact_keys(
            response_body,
            array['item_id', 'error_code']::text[]
          )
          and pg_catalog.jsonb_typeof(response_body -> 'item_id') = 'string'
          and response_body ->> 'item_id' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
          and pg_catalog.jsonb_typeof(response_body -> 'error_code') = 'string'
          and response_body ->> 'error_code' = 'VERSION_CONFLICT'
        )
      )
    )
    or (
      method_path = 'POST /categories'
      and (
        (
          response_code = 201
          and private.jsonb_has_exact_keys(
            response_body,
            array['category_id', 'http_status']::text[]
          )
          and pg_catalog.jsonb_typeof(response_body -> 'category_id') = 'string'
          and response_body ->> 'category_id' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
          and pg_catalog.jsonb_typeof(response_body -> 'http_status') = 'number'
          and (response_body ->> 'http_status')::integer = response_code
        )
        or (
          response_code = 409
          and private.jsonb_has_exact_keys(
            response_body,
            array['error_code']::text[]
          )
          and pg_catalog.jsonb_typeof(response_body -> 'error_code') = 'string'
          and response_body ->> 'error_code' in (
            'CATEGORY_NAME_EXISTS',
            'CATEGORY_LIMIT_REACHED'
          )
        )
      )
    )
    or (
      method_path ~* '^PATCH /categories/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
      and (
        (
          response_code = 200
          and private.jsonb_has_exact_keys(
            response_body,
            array['category_id', 'http_status']::text[]
          )
          and pg_catalog.jsonb_typeof(response_body -> 'category_id') = 'string'
          and response_body ->> 'category_id' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
          and pg_catalog.jsonb_typeof(response_body -> 'http_status') = 'number'
          and (response_body ->> 'http_status')::integer = response_code
        )
        or (
          response_code = 409
          and private.jsonb_has_exact_keys(
            response_body,
            array['category_id', 'error_code']::text[]
          )
          and pg_catalog.jsonb_typeof(response_body -> 'category_id') = 'string'
          and response_body ->> 'category_id' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
          and pg_catalog.jsonb_typeof(response_body -> 'error_code') = 'string'
          and response_body ->> 'error_code' = 'CATEGORY_NAME_EXISTS'
        )
      )
    )
    or (
      method_path ~* '^DELETE /categories/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
      and response_code = 204
      and private.jsonb_has_exact_keys(
        response_body,
        array['category_id', 'http_status']::text[]
      )
      and pg_catalog.jsonb_typeof(response_body -> 'category_id') = 'string'
      and response_body ->> 'category_id' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
      and pg_catalog.jsonb_typeof(response_body -> 'http_status') = 'number'
      and (response_body ->> 'http_status')::integer = response_code
    )
  );

create or replace function public.library_create_item(
  p_owner_id uuid,
  p_request_id uuid,
  p_body jsonb,
  p_prepared jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  profile_state text;
  member_enabled boolean;
  member_approved_at timestamptz;
  usage_count integer;
  request_hash_value text;
  previous_request public.api_requests%rowtype;
  previous_item_id uuid;
  previous_item_deleted_at timestamptz;
  raw_url text;
  raw_title text;
  raw_note text;
  raw_shared_text text;
  normalized_url_value text;
  url_hash_value text;
  source_value text;
  display_fallback_value text;
  metadata_allowed_value boolean;
  selected_category_count integer := 0;
  owned_category_count integer := 0;
  distinct_category_count integer := 0;
  expected_normalized_categories text := '';
  existing_item_id uuid;
  existing_normalized_url text;
  created_item_id uuid;
  classification_state_value text;
  has_classification_text boolean;
  current_window timestamptz;
  current_rate_count integer;
  result_item jsonb;
begin
  if p_owner_id is null then
    raise exception using errcode = 'P0001', message = 'UNAUTHENTICATED';
  end if;

  if p_request_id is null then
    raise exception using errcode = 'P0001', message = 'INVALID_REQUEST_ID';
  end if;

  select profile.state
  into profile_state
  from public.profiles as profile
  where profile.id = p_owner_id
  for share;

  if not found then
    raise exception using errcode = 'P0001', message = 'BETA_ACCESS_REQUIRED';
  end if;

  if profile_state = 'deleting' then
    raise exception using errcode = 'P0001', message = 'ACCOUNT_DELETING';
  end if;

  select member.enabled, member.approved_at
  into member_enabled, member_approved_at
  from public.beta_members as member
  where member.owner_id = p_owner_id
  for share;

  if profile_state <> 'active'
    or not found
    or not member_enabled
    or member_approved_at is null then
    raise exception using errcode = 'P0001', message = 'BETA_ACCESS_REQUIRED';
  end if;

  select usage.active_item_count
  into usage_count
  from public.library_usage as usage
  where usage.owner_id = p_owner_id
  for update;

  if not found then
    raise exception using errcode = 'P0001', message = 'BETA_ACCESS_REQUIRED';
  end if;

  request_hash_value := pg_catalog.encode(
    extensions.digest(
      pg_catalog.convert_to(
        'POST /items' || pg_catalog.chr(10) || coalesce(p_body::text, 'null'),
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
    if previous_request.method_path <> 'POST /items'
      or previous_request.request_hash <> request_hash_value then
      raise exception using errcode = 'P0001', message = 'IDEMPOTENCY_MISMATCH';
    end if;

    if previous_request.response_code = 409 then
      return pg_catalog.jsonb_build_object(
        'http_status', 409,
        'error_code', previous_request.response_body ->> 'error_code'
      );
    end if;

    previous_item_id := (previous_request.response_body ->> 'item_id')::uuid;

    select item.deleted_at
    into previous_item_deleted_at
    from public.items as item
    where item.owner_id = p_owner_id
      and item.id = previous_item_id;

    if not found then
      raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
    end if;

    if previous_item_deleted_at is not null then
      raise exception using errcode = 'P0001', message = 'ITEM_DELETED';
    end if;

    result_item := private.library_item_json(p_owner_id, previous_item_id, true);
    if result_item is null then
      raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
    end if;

    return pg_catalog.jsonb_build_object(
      'http_status', previous_request.response_code,
      'duplicate', (previous_request.response_body ->> 'duplicate')::boolean,
      'item', result_item
    );
  end if;

  if p_body is null or pg_catalog.jsonb_typeof(p_body) <> 'object'
    or not (p_body ? 'url')
    or exists (
      select 1
      from pg_catalog.jsonb_object_keys(p_body) as property(key)
      where property.key not in ('url', 'title', 'note', 'shared_text', 'category_ids')
    ) then
    raise exception using errcode = 'P0001', message = 'INVALID_BODY';
  end if;

  if pg_catalog.jsonb_typeof(p_body -> 'url') <> 'string' then
    raise exception using errcode = 'P0001', message = 'INVALID_BODY';
  end if;

  raw_url := p_body ->> 'url';
  if pg_catalog.char_length(raw_url) not between 1 and 4096
    or raw_url <> pg_catalog.btrim(raw_url)
    or raw_url !~* '^https?://[^/?#[:space:]]+([/?#][^[:space:]]*)?$'
    or pg_catalog.strpos(
      pg_catalog.split_part(
        pg_catalog.split_part(
          pg_catalog.split_part(
            pg_catalog.regexp_replace(raw_url, '^[^:]+://', '', 'i'),
            '/',
            1
          ),
          '?',
          1
        ),
        '#',
        1
      ),
      '@'
    ) <> 0 then
    raise exception using errcode = 'P0001', message = 'INVALID_BODY';
  end if;

  if (p_body ? 'title' and pg_catalog.jsonb_typeof(p_body -> 'title') not in ('string', 'null'))
    or (p_body ? 'note' and pg_catalog.jsonb_typeof(p_body -> 'note') not in ('string', 'null'))
    or (
      p_body ? 'shared_text'
      and pg_catalog.jsonb_typeof(p_body -> 'shared_text') not in ('string', 'null')
    ) then
    raise exception using errcode = 'P0001', message = 'INVALID_BODY';
  end if;

  raw_title := p_body ->> 'title';
  raw_note := p_body ->> 'note';
  raw_shared_text := p_body ->> 'shared_text';

  if pg_catalog.char_length(raw_title) > 300
    or pg_catalog.char_length(raw_note) > 4000
    or pg_catalog.char_length(raw_shared_text) > 4000 then
    raise exception using errcode = 'P0001', message = 'INVALID_BODY';
  end if;

  if p_body ? 'category_ids' then
    if pg_catalog.jsonb_typeof(p_body -> 'category_ids') <> 'array' then
      raise exception using errcode = 'P0001', message = 'INVALID_BODY';
    end if;

    selected_category_count := pg_catalog.jsonb_array_length(p_body -> 'category_ids');
    if selected_category_count > 5 then
      raise exception using errcode = 'P0001', message = 'CATEGORY_LIMIT_REACHED';
    end if;

    if exists (
      select 1
      from pg_catalog.jsonb_array_elements(p_body -> 'category_ids') as category_id(value)
      where pg_catalog.jsonb_typeof(category_id.value) <> 'string'
        or category_id.value #>> '{}' !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    ) then
      raise exception using errcode = 'P0001', message = 'INVALID_BODY';
    end if;

    select count(*)::integer, count(distinct category_id.value #>> '{}')::integer
    into owned_category_count, distinct_category_count
    from pg_catalog.jsonb_array_elements(p_body -> 'category_ids') as category_id(value);

    if owned_category_count <> distinct_category_count then
      raise exception using errcode = 'P0001', message = 'INVALID_BODY';
    end if;

    select
      count(category.id)::integer,
      coalesce(
        pg_catalog.string_agg(
          category.normalized_name,
          ' '
          order by requested.ordinality
        ),
        ''
      )
    into owned_category_count, expected_normalized_categories
    from pg_catalog.jsonb_array_elements_text(p_body -> 'category_ids')
      with ordinality as requested(category_id, ordinality)
    left join public.categories as category
      on category.owner_id = p_owner_id
      and category.id = requested.category_id::uuid;

    if owned_category_count <> selected_category_count then
      raise exception using errcode = 'P0001', message = 'INVALID_CATEGORY_IDS';
    end if;
  end if;

  if not private.jsonb_has_exact_keys(
    p_prepared,
    array[
      'normalized_url',
      'url_hash',
      'source',
      'display_fallback',
      'normalized_fields',
      'alias_concepts',
      'cue_state',
      'cue_flags',
      'metadata_allowed'
    ]::text[]
  )
    or pg_catalog.jsonb_typeof(p_prepared -> 'normalized_url') <> 'string'
    or pg_catalog.jsonb_typeof(p_prepared -> 'url_hash') <> 'string'
    or pg_catalog.jsonb_typeof(p_prepared -> 'source') <> 'string'
    or pg_catalog.jsonb_typeof(p_prepared -> 'display_fallback') <> 'string'
    or pg_catalog.jsonb_typeof(p_prepared -> 'cue_state') <> 'string'
    or pg_catalog.jsonb_typeof(p_prepared -> 'metadata_allowed') <> 'boolean'
    or not private.valid_normalized_fields(p_prepared -> 'normalized_fields')
    or not private.valid_alias_concepts(p_prepared -> 'alias_concepts')
    or not private.valid_cue_flags(p_prepared -> 'cue_flags') then
    raise exception using errcode = 'P0001', message = 'INVALID_PREPARED';
  end if;

  normalized_url_value := p_prepared ->> 'normalized_url';
  url_hash_value := p_prepared ->> 'url_hash';
  source_value := p_prepared ->> 'source';
  display_fallback_value := p_prepared ->> 'display_fallback';
  metadata_allowed_value := (p_prepared ->> 'metadata_allowed')::boolean;

  if pg_catalog.char_length(normalized_url_value) not between 1 and 4096
    or normalized_url_value <> pg_catalog.btrim(normalized_url_value)
    or normalized_url_value !~ '^https?://[^/?#[:space:]]+([/?#][^[:space:]]*)?$'
    or pg_catalog.strpos(
      pg_catalog.split_part(
        pg_catalog.split_part(
          pg_catalog.split_part(
            pg_catalog.regexp_replace(normalized_url_value, '^[^:]+://', '', 'i'),
            '/',
            1
          ),
          '?',
          1
        ),
        '#',
        1
      ),
      '@'
    ) <> 0
    or url_hash_value !~ '^[0-9a-f]{64}$'
    or url_hash_value <> pg_catalog.encode(
      extensions.digest(pg_catalog.convert_to(normalized_url_value, 'UTF8'), 'sha256'),
      'hex'
    )
    or source_value not in ('instagram', 'threads', 'naver_blog', 'other')
    or pg_catalog.char_length(pg_catalog.btrim(display_fallback_value)) not between 1 and 300
    or p_prepared ->> 'cue_state' not in ('pending', 'missing', 'limited', 'available')
    or p_prepared #>> '{normalized_fields,fetched_title}' <> ''
    or p_prepared #>> '{normalized_fields,ocr}' <> ''
    or p_prepared #>> '{normalized_fields,description}' <> ''
    or p_prepared #>> '{normalized_fields,body}' <> '' then
    raise exception using errcode = 'P0001', message = 'INVALID_PREPARED';
  end if;

  select item.id, item.normalized_url
  into existing_item_id, existing_normalized_url
  from public.items as item
  where item.owner_id = p_owner_id
    and item.url_hash = url_hash_value
    and item.deleted_at is null
  for update;

  if found then
    if existing_normalized_url <> normalized_url_value then
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
        'POST /items',
        request_hash_value,
        409,
        pg_catalog.jsonb_build_object('error_code', 'URL_HASH_COLLISION')
      );

      return pg_catalog.jsonb_build_object(
        'http_status', 409,
        'error_code', 'URL_HASH_COLLISION'
      );
    end if;

    result_item := private.library_item_json(p_owner_id, existing_item_id, true);
    if result_item is null then
      raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
    end if;

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
      'POST /items',
      request_hash_value,
      200,
      pg_catalog.jsonb_build_object(
        'item_id', existing_item_id,
        'duplicate', true,
        'http_status', 200
      )
    );

    return pg_catalog.jsonb_build_object(
      'http_status', 200,
      'duplicate', true,
      'item', result_item
    );
  end if;

  if usage_count >= 100 then
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
      'POST /items',
      request_hash_value,
      409,
      pg_catalog.jsonb_build_object('error_code', 'ITEM_LIMIT_REACHED')
    );

    return pg_catalog.jsonb_build_object(
      'http_status', 409,
      'error_code', 'ITEM_LIMIT_REACHED'
    );
  end if;

  current_window := pg_catalog.date_trunc('minute', pg_catalog.clock_timestamp());

  insert into public.api_rate_buckets (owner_id, operation, window_start, request_count)
  values (p_owner_id, 'create_item', current_window, 1)
  on conflict (owner_id, operation, window_start)
  do update set request_count = public.api_rate_buckets.request_count + 1
  returning request_count into current_rate_count;

  if current_rate_count > 10 then
    raise exception using errcode = 'P0001', message = 'RATE_LIMITED';
  end if;

  has_classification_text := exists (
    select 1
    from pg_catalog.jsonb_each_text(p_prepared -> 'normalized_fields') as field(key, value)
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

  classification_state_value := case
    when selected_category_count > 0 then 'manual'
    when has_classification_text then 'pending'
    else 'unclassified'
  end;

  insert into public.items (
    owner_id,
    original_url,
    normalized_url,
    url_hash,
    source,
    display_fallback,
    shared_text,
    user_title,
    fetched_title,
    description,
    body_text,
    note,
    extraction_meta,
    text_revision,
    version,
    metadata_state
  )
  values (
    p_owner_id,
    raw_url,
    normalized_url_value,
    url_hash_value,
    source_value,
    display_fallback_value,
    raw_shared_text,
    raw_title,
    null,
    null,
    null,
    raw_note,
    '{}'::jsonb,
    1,
    1,
    case when metadata_allowed_value then 'queued' else 'unsupported' end
  )
  returning id into created_item_id;

  insert into public.item_search (
    owner_id,
    item_id,
    text_revision,
    search_version,
    normalized_fields,
    alias_concepts,
    cue_version,
    cue_state,
    cue_flags
  )
  values (
    p_owner_id,
    created_item_id,
    1,
    'search-v2.0.0',
    pg_catalog.jsonb_set(
      p_prepared -> 'normalized_fields',
      '{categories}'::text[],
      pg_catalog.to_jsonb(expected_normalized_categories),
      false
    ),
    p_prepared -> 'alias_concepts',
    'cues-v1.0.0',
    p_prepared ->> 'cue_state',
    p_prepared -> 'cue_flags'
  );

  insert into public.item_classification (
    owner_id,
    item_id,
    rules_version,
    target_revision,
    state,
    reasons
  )
  values (
    p_owner_id,
    created_item_id,
    'rules-v2.0.0',
    1,
    classification_state_value,
    '[]'::jsonb
  );

  insert into public.item_category_controls (
    owner_id,
    item_id,
    manual_override,
    cue_dismissed_revision
  )
  values (
    p_owner_id,
    created_item_id,
    selected_category_count > 0,
    null
  );

  if selected_category_count > 0 then
    insert into public.item_categories (owner_id, item_id, category_id, origin)
    select p_owner_id, created_item_id, requested.category_id::uuid, 'manual'
    from pg_catalog.jsonb_array_elements_text(p_body -> 'category_ids')
      with ordinality as requested(category_id, ordinality)
    order by requested.ordinality;
  end if;

  if metadata_allowed_value then
    insert into public.processing_jobs (
      owner_id,
      item_id,
      kind,
      target_revision,
      state
    )
    values (p_owner_id, created_item_id, 'metadata', 1, 'queued');
  end if;

  if selected_category_count = 0 and has_classification_text then
    insert into public.processing_jobs (
      owner_id,
      item_id,
      kind,
      target_revision,
      state
    )
    values (p_owner_id, created_item_id, 'classify', 1, 'queued');
  end if;

  update public.library_usage
  set active_item_count = active_item_count + 1,
      updated_at = pg_catalog.now()
  where owner_id = p_owner_id;

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
    'POST /items',
    request_hash_value,
    201,
    pg_catalog.jsonb_build_object(
      'item_id', created_item_id,
      'duplicate', false,
      'http_status', 201
    )
  );

  result_item := private.library_item_json(p_owner_id, created_item_id, true);
  if result_item is null then
    raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
  end if;

  return pg_catalog.jsonb_build_object(
    'http_status', 201,
    'duplicate', false,
    'item', result_item
  );
end;
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
    or pg_catalog.char_length(p_normalized_name) not between 1 and 30 then
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
    or pg_catalog.char_length(p_normalized_name) not between 1 and 30 then
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
