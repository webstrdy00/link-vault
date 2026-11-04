-- Keep source identifiers fixed across storage, creation and server-side search.
alter table public.items
  drop constraint items_source_check,
  add constraint items_source_check check (
    source in ('instagram', 'threads', 'naver_blog', 'x', 'facebook', 'bluesky', 'mastodon', 'other')
  );

-- Reclassify only active legacy "other" rows, using the original URL authority.
-- Reject userinfo, encoded/lookalike hosts, malformed labels/ports and browser URL
-- repair cases. No authored content, timestamps, revisions or related rows change.
with parsed as materialized (
  select item.id,
    pg_catalog.regexp_match(
      item.original_url collate "C",
      '^([Hh][Tt][Tt][Pp][Ss]?)://([^/?#]+)(/[^?#]*)?([?][^#]*)?(#.*)?$'
    ) as parts
  from public.items as item
  where item.deleted_at is null
    and item.source = 'other'
    and pg_catalog.char_length(item.original_url) between 1 and 4096
    and item.original_url !~ '[[:space:][:cntrl:]]'
    and pg_catalog.strpos(item.original_url, pg_catalog.chr(92)) = 0
    and item.original_url collate "C" !~ '%([^0-9A-Fa-f]|[0-9A-Fa-f]([^0-9A-Fa-f]|$)|$)'
), authorities as materialized (
  select parsed.id,
    pg_catalog.regexp_match(
      pg_catalog.lower(parsed.parts[2] collate "C"),
      '^([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:[.][a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*)(?::([0-9]+))?$'
    ) as parts
  from parsed
), classified as materialized (
  select authorities.id,
    case
      when authorities.parts[1] ~ '(^|[.])(x[.]com|twitter[.]com)$' then 'x'
      when authorities.parts[1] ~ '(^|[.])(facebook[.]com|fb[.]com|fb[.]watch)$' then 'facebook'
      when authorities.parts[1] ~ '(^|[.])bsky[.]app$' then 'bluesky'
      when authorities.parts[1] ~ '(^|[.])mastodon[.]social$' then 'mastodon'
    end as source
  from authorities
  where pg_catalog.char_length(authorities.parts[1]) <= 253
    and case
      when authorities.parts[2] is null then true
      when pg_catalog.char_length(pg_catalog.ltrim(authorities.parts[2], '0')) between 1 and 5
        then pg_catalog.ltrim(authorities.parts[2], '0')::integer between 1 and 65535
      else false
    end
)
update public.items as item
set source = classified.source
from classified
where item.id = classified.id
  and item.deleted_at is null
  and item.source = 'other'
  and classified.source is not null;

-- Preserve the complete 025 owner lock, replay, social deduplication, independent
-- hash-collision defense, category validation, quotas, rate limits and job writes.
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
  post_identity_value text;
  source_value text;
  display_fallback_value text;
  metadata_allowed_value boolean;
  selected_category_count integer := 0;
  owned_category_count integer := 0;
  distinct_category_count integer := 0;
  expected_normalized_categories text := '';
  existing_item_id uuid;
  existing_normalized_url text;
  existing_post_identity text;
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
    or source_value not in ('instagram', 'threads', 'naver_blog', 'x', 'facebook', 'bluesky', 'mastodon', 'other')
    or pg_catalog.char_length(pg_catalog.btrim(display_fallback_value)) not between 1 and 300
    or p_prepared ->> 'cue_state' not in ('pending', 'missing', 'limited', 'available')
    or p_prepared #>> '{normalized_fields,fetched_title}' <> ''
    or p_prepared #>> '{normalized_fields,ocr}' <> ''
    or p_prepared #>> '{normalized_fields,description}' <> ''
    or p_prepared #>> '{normalized_fields,body}' <> '' then
    raise exception using errcode = 'P0001', message = 'INVALID_PREPARED';
  end if;

  post_identity_value := private.social_post_identity(raw_url);

  -- Check the hash independently: a valid older post match must not conceal a
  -- different item's colliding hash. Two unknown identities are not a match.
  select item.id, item.normalized_url, private.social_post_identity(item.original_url)
  into existing_item_id, existing_normalized_url, existing_post_identity
  from public.items as item
  where item.owner_id = p_owner_id
    and item.url_hash = url_hash_value
    and item.deleted_at is null
  for update;

  if found and existing_normalized_url <> normalized_url_value
    and (
      post_identity_value is null
      or existing_post_identity is distinct from post_identity_value
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

  select item.id
  into existing_item_id
  from public.items as item
  where item.owner_id = p_owner_id
    and item.deleted_at is null
    and (
      item.url_hash = url_hash_value
      or (
        post_identity_value is not null
        and extensions.digest(private.social_post_identity(item.original_url), 'sha256')
          = extensions.digest(post_identity_value, 'sha256')
        and private.social_post_identity(item.original_url) = post_identity_value
      )
    )
  order by item.created_at, item.id
  limit 1
  for update;

  if found then
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

-- Migration 008 moved the 005 search implementation behind a normalization fence.
-- Replace that implementation without changing matching, ranking or pagination.
create or replace function private.library_search_items_unfenced(
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
declare
  caller_id uuid := auth.uid();
  profile_state text;
  query_value text;
  aliases_value boolean := true;
  unclassified_value boolean := false;
  needs_cues_value boolean := false;
  category_id_value uuid;
  source_value text;
  date_from_value timestamptz;
  date_to_value timestamptz;
  result jsonb;
begin
  if caller_id is null then
    raise exception using errcode = 'P0001', message = 'UNAUTHENTICATED';
  end if;

  select profile.state
  into profile_state
  from public.profiles as profile
  where profile.id = caller_id;

  if found and profile_state = 'deleting' then
    raise exception using errcode = 'P0001', message = 'ACCOUNT_DELETING';
  end if;

  if not found or profile_state <> 'active' or not exists (
    select 1
    from public.beta_members as member
    where member.owner_id = caller_id
      and member.enabled
      and member.approved_at is not null
  ) then
    raise exception using errcode = 'P0001', message = 'BETA_ACCESS_REQUIRED';
  end if;

  if p_limit is null or p_limit < 1 or p_limit > 50
    or p_offset is null or p_offset < 0 then
    raise exception using errcode = 'P0001', message = 'INVALID_BODY';
  end if;

  if not private.jsonb_has_exact_keys(
    p_plan,
    array['query', 'terms', 'groups']::text[]
  )
    or pg_catalog.jsonb_typeof(p_plan -> 'query') <> 'string'
    or pg_catalog.jsonb_typeof(p_plan -> 'terms') <> 'array'
    or pg_catalog.jsonb_typeof(p_plan -> 'groups') <> 'array' then
    raise exception using errcode = 'P0001', message = 'INVALID_SEARCH_PLAN';
  end if;

  query_value := p_plan ->> 'query';
  if pg_catalog.char_length(query_value) > 200
    or pg_catalog.jsonb_array_length(p_plan -> 'terms') > 10
    or pg_catalog.jsonb_array_length(p_plan -> 'groups') > 10
    or exists (
      select 1
      from pg_catalog.jsonb_array_elements(p_plan -> 'terms') as term(value)
      where pg_catalog.jsonb_typeof(term.value) <> 'string'
        or pg_catalog.char_length(term.value #>> '{}') not between 1 and 200
    )
    or (
      select count(*)
      from pg_catalog.jsonb_array_elements_text(p_plan -> 'terms') as term(value)
    ) <> (
      select count(distinct term.value)
      from pg_catalog.jsonb_array_elements_text(p_plan -> 'terms') as term(value)
    )
    or exists (
      select 1
      from pg_catalog.jsonb_array_elements(p_plan -> 'groups') as search_group(value)
      where not private.jsonb_has_exact_keys(
        search_group.value,
        array['kind', 'value']::text[]
      )
        or pg_catalog.jsonb_typeof(search_group.value -> 'kind') <> 'string'
        or pg_catalog.jsonb_typeof(search_group.value -> 'value') <> 'string'
        or search_group.value ->> 'kind' not in ('concept', 'literal')
        or pg_catalog.char_length(search_group.value ->> 'value') not between 1 and 200
        or (
          search_group.value ->> 'kind' = 'concept'
          and search_group.value ->> 'value' not in (
            'kakaotalk',
            'profile_photo',
            'instagram',
            'threads',
            'excel',
            'android',
            'youtube',
            'reels'
          )
        )
    )
    or (
      select count(*)
      from pg_catalog.jsonb_array_elements(p_plan -> 'groups') as search_group(value)
    ) <> (
      select count(distinct (
        search_group.value ->> 'kind',
        search_group.value ->> 'value'
      ))
      from pg_catalog.jsonb_array_elements(p_plan -> 'groups') as search_group(value)
    )
    or (
      query_value = ''
      and (
        pg_catalog.jsonb_array_length(p_plan -> 'terms') <> 0
        or pg_catalog.jsonb_array_length(p_plan -> 'groups') <> 0
      )
    )
    or (
      query_value <> ''
      and (
        pg_catalog.jsonb_array_length(p_plan -> 'terms') = 0
        or pg_catalog.jsonb_array_length(p_plan -> 'groups') = 0
      )
    ) then
    raise exception using errcode = 'P0001', message = 'QUERY_LIMIT';
  end if;

  if p_filters is null
    or pg_catalog.jsonb_typeof(p_filters) <> 'object'
    or exists (
      select 1
      from pg_catalog.jsonb_object_keys(p_filters) as property(key)
      where property.key not in (
        'category_id',
        'unclassified',
        'source',
        'date_from',
        'date_to',
        'aliases',
        'needs_cues'
      )
    )
    or (
      p_filters ? 'category_id'
      and pg_catalog.jsonb_typeof(p_filters -> 'category_id') <> 'string'
    )
    or (
      p_filters ? 'unclassified'
      and pg_catalog.jsonb_typeof(p_filters -> 'unclassified') <> 'boolean'
    )
    or (
      p_filters ? 'source'
      and pg_catalog.jsonb_typeof(p_filters -> 'source') <> 'string'
    )
    or (
      p_filters ? 'date_from'
      and pg_catalog.jsonb_typeof(p_filters -> 'date_from') <> 'string'
    )
    or (
      p_filters ? 'date_to'
      and pg_catalog.jsonb_typeof(p_filters -> 'date_to') <> 'string'
    )
    or (
      p_filters ? 'aliases'
      and pg_catalog.jsonb_typeof(p_filters -> 'aliases') <> 'boolean'
    )
    or (
      p_filters ? 'needs_cues'
      and pg_catalog.jsonb_typeof(p_filters -> 'needs_cues') <> 'boolean'
    ) then
    raise exception using errcode = 'P0001', message = 'INVALID_FILTER';
  end if;

  if p_filters ? 'category_id' then
    if p_filters ->> 'category_id'
      !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
      raise exception using errcode = 'P0001', message = 'INVALID_FILTER';
    end if;
    category_id_value := (p_filters ->> 'category_id')::uuid;
  end if;

  unclassified_value := coalesce((p_filters ->> 'unclassified')::boolean, false);
  aliases_value := coalesce((p_filters ->> 'aliases')::boolean, true);
  needs_cues_value := coalesce((p_filters ->> 'needs_cues')::boolean, false);
  source_value := p_filters ->> 'source';

  if category_id_value is not null and unclassified_value then
    raise exception using errcode = 'P0001', message = 'INVALID_FILTER';
  end if;

  if source_value is not null
    and source_value not in ('instagram', 'threads', 'naver_blog', 'x', 'facebook', 'bluesky', 'mastodon', 'other') then
    raise exception using errcode = 'P0001', message = 'INVALID_FILTER';
  end if;

  if p_filters ? 'date_from' then
    date_from_value := private.parse_iso_instant(p_filters ->> 'date_from');
  end if;
  if p_filters ? 'date_to' then
    date_to_value := private.parse_iso_instant(p_filters ->> 'date_to');
  end if;
  if date_from_value is not null
    and date_to_value is not null
    and date_from_value >= date_to_value then
    raise exception using errcode = 'P0001', message = 'INVALID_FILTER';
  end if;

  with candidates as materialized (
    select
      item.id,
      item.created_at,
      search_record.normalized_fields,
      search_record.alias_concepts
    from public.items as item
    join public.item_search as search_record
      on search_record.owner_id = item.owner_id
      and search_record.item_id = item.id
      and search_record.text_revision = item.text_revision
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
    where item.owner_id = caller_id
      and item.deleted_at is null
      and (
        category_id_value is null
        or exists (
          select 1
          from public.item_categories as selected
          where selected.owner_id = item.owner_id
            and selected.item_id = item.id
            and selected.category_id = category_id_value
        )
      )
      and (
        not unclassified_value
        or not exists (
          select 1
          from public.item_categories as selected
          where selected.owner_id = item.owner_id
            and selected.item_id = item.id
        )
      )
      and (source_value is null or item.source = source_value)
      and (date_from_value is null or item.created_at >= date_from_value)
      and (date_to_value is null or item.created_at < date_to_value)
      and (
        not needs_cues_value
        or search_record.cue_state in ('limited', 'missing')
      )
  ),
  literal_rank as materialized (
    select
      candidate.id,
      candidate.created_at,
      0 as bucket,
      coalesce(
        (
          select sum(
            private.search_field_score(
              candidate.normalized_fields,
              candidate.alias_concepts,
              'literal',
              term.value
            )
          )::integer
          from pg_catalog.jsonb_array_elements_text(
            p_plan -> 'terms'
          ) as term(value)
        ),
        0
      ) as score,
      case when query_value = '' then null else 'literal' end as match_type
    from candidates as candidate
    where not exists (
      select 1
      from pg_catalog.jsonb_array_elements_text(
        p_plan -> 'terms'
      ) as term(value)
      where private.search_field_score(
        candidate.normalized_fields,
        candidate.alias_concepts,
        'literal',
        term.value
      ) is null
    )
  ),
  alias_rank as materialized (
    select
      candidate.id,
      candidate.created_at,
      1 as bucket,
      (
        select sum(
          private.search_field_score(
            candidate.normalized_fields,
            candidate.alias_concepts,
            search_group.value ->> 'kind',
            search_group.value ->> 'value'
          )
        )::integer
        from pg_catalog.jsonb_array_elements(
          p_plan -> 'groups'
        ) as search_group(value)
      ) as score,
      'alias'::text as match_type
    from candidates as candidate
    where aliases_value
      and query_value <> ''
      and not exists (
        select 1
        from literal_rank as direct
        where direct.id = candidate.id
      )
      and not exists (
        select 1
        from pg_catalog.jsonb_array_elements(
          p_plan -> 'groups'
        ) as search_group(value)
        where private.search_field_score(
          candidate.normalized_fields,
          candidate.alias_concepts,
          search_group.value ->> 'kind',
          search_group.value ->> 'value'
        ) is null
      )
  ),
  ranked as materialized (
    select * from literal_rank
    union all
    select * from alias_rank
  ),
  page as materialized (
    select ranked.*
    from ranked
    order by
      ranked.bucket,
      ranked.score desc,
      ranked.created_at desc,
      ranked.id desc
    offset p_offset
    limit p_limit + 1
  )
  select pg_catalog.jsonb_build_object(
    'items', coalesce(
      (
        select pg_catalog.jsonb_agg(
          private.library_item_json(caller_id, visible.id, false)
            || pg_catalog.jsonb_build_object('match_type', visible.match_type)
          order by
            visible.bucket,
            visible.score desc,
            visible.created_at desc,
            visible.id desc
        )
        from (
          select page.*
          from page
          order by
            page.bucket,
            page.score desc,
            page.created_at desc,
            page.id desc
          limit p_limit
        ) as visible
      ),
      '[]'::jsonb
    ),
    'has_more', (select count(*) > p_limit from page)
  )
  into result;

  return result;
end;
$$;

create or replace function public.library_search_items(
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

revoke all on function public.library_create_item(uuid, uuid, jsonb, jsonb)
from public, anon, authenticated, service_role;
grant execute on function public.library_create_item(uuid, uuid, jsonb, jsonb)
to service_role;

revoke all on function private.library_search_items_unfenced(jsonb, jsonb, integer, integer)
from public, anon, authenticated, service_role;
revoke all on function public.library_search_items(jsonb, jsonb, integer, integer)
from public, anon, authenticated, service_role;
grant execute on function public.library_search_items(jsonb, jsonb, integer, integer)
to authenticated;
