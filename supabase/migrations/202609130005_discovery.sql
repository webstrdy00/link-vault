alter table public.categories
  alter column normalized_name drop expression,
  alter column normalized_name set not null;

create or replace function public.member_bootstrap(p_request_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller_id uuid := auth.uid();
  profile_state text;
  member_enabled boolean;
  member_approved_at timestamptz;
  result jsonb;
begin
  if caller_id is null then
    raise exception using errcode = 'P0001', message = 'UNAUTHENTICATED';
  end if;

  if p_request_id is null then
    raise exception using errcode = '22004', message = 'REQUEST_ID_REQUIRED';
  end if;

  select profile.state
  into profile_state
  from public.profiles as profile
  where profile.id = caller_id
  for update;

  if found and profile_state = 'deleting' then
    raise exception using errcode = 'P0001', message = 'ACCOUNT_DELETING';
  end if;

  select member.enabled, member.approved_at
  into member_enabled, member_approved_at
  from public.beta_members as member
  where member.owner_id = caller_id
  for share;

  if not found or not member_enabled or member_approved_at is null then
    raise exception using errcode = 'P0001', message = 'BETA_ACCESS_REQUIRED';
  end if;

  insert into public.profiles (id, state)
  values (caller_id, 'active')
  on conflict (id) do nothing;

  select profile.state
  into profile_state
  from public.profiles as profile
  where profile.id = caller_id
  for update;

  if profile_state = 'deleting' then
    raise exception using errcode = 'P0001', message = 'ACCOUNT_DELETING';
  end if;

  insert into public.library_usage (owner_id)
  values (caller_id)
  on conflict (owner_id) do nothing;

  insert into public.categories (owner_id, name, normalized_name, kind, system_code)
  values
    (caller_id, '여행', '여행', 'system', 'travel'),
    (caller_id, '음식·맛집', '음식·맛집', 'system', 'food'),
    (caller_id, '업무·학습', '업무·학습', 'system', 'work'),
    (caller_id, '쇼핑', '쇼핑', 'system', 'shopping'),
    (caller_id, '앱·도구', '앱·도구', 'system', 'tools'),
    (caller_id, '생활·건강', '생활·건강', 'system', 'life'),
    (caller_id, '문화·읽을거리', '문화·읽을거리', 'system', 'culture'),
    (caller_id, '기타', '기타', 'system', 'other')
  on conflict (owner_id, system_code) do nothing;

  select pg_catalog.jsonb_build_object(
    'profile', pg_catalog.jsonb_build_object(
      'id', profile.id,
      'state', profile.state
    ),
    'limits', pg_catalog.jsonb_build_object(
      'items', 100,
      'image_bytes', 20000000
    ),
    'usage', pg_catalog.jsonb_build_object(
      'active_item_count', usage.active_item_count,
      'used_image_bytes', usage.used_image_bytes,
      'reserved_image_bytes', usage.reserved_image_bytes
    ),
    'categories', coalesce(
      (
        select pg_catalog.jsonb_agg(
          pg_catalog.jsonb_build_object(
            'id', category.id,
            'name', category.name,
            'kind', category.kind,
            'system_code', category.system_code
          )
          order by
            case category.system_code
              when 'travel' then 1
              when 'food' then 2
              when 'work' then 3
              when 'shopping' then 4
              when 'tools' then 5
              when 'life' then 6
              when 'culture' then 7
              when 'other' then 8
              else 9
            end,
            category.created_at,
            category.id
        )
        from public.categories as category
        where category.owner_id = caller_id
      ),
      '[]'::jsonb
    )
  )
  into result
  from public.profiles as profile
  join public.library_usage as usage
    on usage.owner_id = profile.id
  where profile.id = caller_id;

  return result;
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
    or method_path ~* '^POST /items/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/cue-dismiss$'
    or method_path ~* '^POST /items/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/reclassify$'
  ),
  add constraint api_requests_response_code_check check (
    response_code in (200, 201, 202, 204, 409)
  ),
  add constraint api_requests_response_body_check check (
    (
      method_path = 'POST /items'
      and response_code in (200, 201)
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
      and response_code = 201
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
      method_path ~* '^PATCH /categories/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
      and response_code = 200
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

create function private.search_field_score(
  p_fields jsonb,
  p_aliases jsonb,
  p_kind text,
  p_value text
)
returns integer
language sql
immutable
parallel safe
set search_path = ''
as $$
  select max(field.weight)
  from (
    values
      ('user_title', 10),
      ('fetched_title', 10),
      ('note', 6),
      ('ocr', 4),
      ('shared', 3),
      ('description', 3),
      ('body', 3),
      ('categories', 2),
      ('url', 1)
  ) as field(name, weight)
  where (
    p_kind = 'literal'
    and pg_catalog.strpos(coalesce(p_fields ->> field.name, ''), p_value) > 0
  )
  or (
    p_kind = 'concept'
    and field.name not in ('categories', 'url')
    and coalesce(p_aliases -> field.name, '[]'::jsonb) ? p_value
  );
$$;

create function private.parse_iso_instant(p_value text)
returns timestamptz
language plpgsql
stable
strict
set search_path = ''
as $$
declare
  parsed_value timestamptz;
begin
  if p_value !~* '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$' then
    raise exception using errcode = 'P0001', message = 'INVALID_FILTER';
  end if;

  if pg_catalog.substr(p_value, 12, 2)::integer > 23
    or pg_catalog.substr(p_value, 15, 2)::integer > 59
    or pg_catalog.substr(p_value, 18, 2)::integer > 59
    or (
      pg_catalog.upper(pg_catalog.right(p_value, 1)) <> 'Z'
      and (
        pg_catalog.substr(pg_catalog.right(p_value, 5), 1, 2)::integer > 23
        or pg_catalog.substr(pg_catalog.right(p_value, 5), 4, 2)::integer > 59
      )
    ) then
    raise exception using errcode = 'P0001', message = 'INVALID_FILTER';
  end if;

  begin
    parsed_value := p_value::timestamptz;
  exception
    when others then
      raise exception using errcode = 'P0001', message = 'INVALID_FILTER';
  end;

  return parsed_value;
end;
$$;

create function private.refresh_category_search(
  p_owner_id uuid,
  p_item_ids uuid[]
)
returns void
language sql
security definer
set search_path = ''
as $$
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
      'has_attachment', false,
      'metadata_state', item.metadata_state,
      'ocr_state', 'not_requested',
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
        'active_asset', null,
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
  where item.owner_id = p_owner_id
    and item.id = p_item_id
    and item.deleted_at is null;
$$;

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
    and source_value not in ('instagram', 'threads', 'naver_blog', 'other') then
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

create function public.library_list_categories()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  caller_id uuid := auth.uid();
  profile_state text;
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

  select pg_catalog.jsonb_build_object(
    'categories', coalesce(
      (
        select pg_catalog.jsonb_agg(
          pg_catalog.jsonb_build_object(
            'id', category.id,
            'name', category.name,
            'kind', category.kind,
            'system_code', category.system_code,
            'item_count', (
              select count(*)::integer
              from public.item_categories as selected
              join public.items as item
                on item.owner_id = selected.owner_id
                and item.id = selected.item_id
                and item.deleted_at is null
              where selected.owner_id = category.owner_id
                and selected.category_id = category.id
            )
          )
          order by
            case category.system_code
              when 'travel' then 1
              when 'food' then 2
              when 'work' then 3
              when 'shopping' then 4
              when 'tools' then 5
              when 'life' then 6
              when 'culture' then 7
              when 'other' then 8
              else 9
            end,
            category.created_at,
            category.id
        )
        from public.categories as category
        where category.owner_id = caller_id
      ),
      '[]'::jsonb
    ),
    'count', (
      select count(*)::integer
      from public.categories as category
      where category.owner_id = caller_id
    ),
    'unclassified_count', (
      select count(*)::integer
      from public.items as item
      where item.owner_id = caller_id
        and item.deleted_at is null
        and not exists (
          select 1
          from public.item_categories as selected
          where selected.owner_id = item.owner_id
            and selected.item_id = item.id
        )
    )
  )
  into result;

  return result;
end;
$$;

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
    or p_prepared #>> '{normalized_fields,body}' <> ''
    or p_prepared #>> '{normalized_fields,categories}' <> expected_normalized_categories then
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
      raise exception using errcode = 'P0001', message = 'URL_HASH_COLLISION';
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
    raise exception using errcode = 'P0001', message = 'ITEM_LIMIT_REACHED';
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
    p_prepared -> 'normalized_fields',
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

create or replace function public.library_update_item(
  p_owner_id uuid,
  p_item_id uuid,
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
  method_path_value text;
  request_hash_value text;
  previous_request public.api_requests%rowtype;
  replay_item public.items%rowtype;
  current_item public.items%rowtype;
  current_search public.item_search%rowtype;
  current_classification public.item_classification%rowtype;
  current_controls public.item_category_controls%rowtype;
  expected_version_value integer;
  snapshot_version_value integer;
  raw_title text;
  raw_note text;
  new_title text;
  new_note text;
  title_changed boolean;
  note_changed boolean;
  text_changed boolean;
  categories_supplied boolean;
  selected_category_count integer := 0;
  owned_category_count integer := 0;
  distinct_category_count integer := 0;
  normalized_category_names text := '';
  new_text_revision integer;
  new_normalized_fields jsonb;
  new_alias_concepts jsonb;
  has_classification_text boolean;
  metadata_was_active boolean;
  manual_override_value boolean;
  result_item jsonb;
begin
  if p_owner_id is null then
    raise exception using errcode = 'P0001', message = 'UNAUTHENTICATED';
  end if;

  if p_item_id is null then
    raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
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

  method_path_value := 'PATCH /items/' || p_item_id::text;
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
        'error_code', 'VERSION_CONFLICT'
      );
    end if;

    select item.*
    into replay_item
    from public.items as item
    where item.owner_id = p_owner_id
      and item.id = (previous_request.response_body ->> 'item_id')::uuid
    for update;

    if not found then
      raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
    end if;

    if replay_item.deleted_at is not null then
      raise exception using errcode = 'P0001', message = 'ITEM_DELETED';
    end if;

    result_item := private.library_item_json(p_owner_id, replay_item.id, true);
    if result_item is null then
      raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
    end if;

    return pg_catalog.jsonb_build_object(
      'http_status', 200,
      'item', result_item
    );
  end if;

  if p_body is null or pg_catalog.jsonb_typeof(p_body) <> 'object' then
    raise exception using errcode = 'P0001', message = 'INVALID_BODY';
  end if;

  if not (p_body ? 'expected_version')
    or not (
      p_body ? 'title'
      or p_body ? 'note'
      or p_body ? 'category_ids'
    )
    or exists (
      select 1
      from pg_catalog.jsonb_object_keys(p_body) as property(key)
      where property.key not in ('expected_version', 'title', 'note', 'category_ids')
    ) then
    raise exception using errcode = 'P0001', message = 'INVALID_BODY';
  end if;

  if pg_catalog.jsonb_typeof(p_body -> 'expected_version') <> 'number' then
    raise exception using errcode = 'P0001', message = 'INVALID_BODY';
  end if;

  if p_body ->> 'expected_version' !~ '^[1-9][0-9]*$'
    or pg_catalog.char_length(p_body ->> 'expected_version') > 10
    or (p_body ->> 'expected_version')::numeric > 2147483647 then
    raise exception using errcode = 'P0001', message = 'INVALID_BODY';
  end if;
  expected_version_value := (p_body ->> 'expected_version')::integer;

  if (p_body ? 'title' and pg_catalog.jsonb_typeof(p_body -> 'title') not in ('string', 'null'))
    or (p_body ? 'note' and pg_catalog.jsonb_typeof(p_body -> 'note') not in ('string', 'null')) then
    raise exception using errcode = 'P0001', message = 'INVALID_BODY';
  end if;

  raw_title := p_body ->> 'title';
  raw_note := p_body ->> 'note';
  if pg_catalog.char_length(raw_title) > 300
    or pg_catalog.char_length(raw_note) > 4000 then
    raise exception using errcode = 'P0001', message = 'INVALID_BODY';
  end if;

  categories_supplied := p_body ? 'category_ids';
  if categories_supplied then
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

  if categories_supplied then
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
    into owned_category_count, normalized_category_names
    from pg_catalog.jsonb_array_elements_text(p_body -> 'category_ids')
      with ordinality as requested(category_id, ordinality)
    left join public.categories as category
      on category.owner_id = p_owner_id
      and category.id = requested.category_id::uuid;

    if owned_category_count <> selected_category_count then
      raise exception using errcode = 'P0001', message = 'INVALID_CATEGORY_IDS';
    end if;
  else
    select coalesce(
      pg_catalog.string_agg(
        category.normalized_name,
        ' '
        order by selected.created_at, selected.category_id
      ),
      ''
    )
    into normalized_category_names
    from public.item_categories as selected
    join public.categories as category
      on category.owner_id = selected.owner_id
      and category.id = selected.category_id
    where selected.owner_id = p_owner_id
      and selected.item_id = p_item_id;
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
      'metadata_allowed',
      'snapshot_version'
    ]::text[]
  )
    or pg_catalog.jsonb_typeof(p_prepared -> 'normalized_url') <> 'string'
    or pg_catalog.jsonb_typeof(p_prepared -> 'url_hash') <> 'string'
    or pg_catalog.jsonb_typeof(p_prepared -> 'source') <> 'string'
    or pg_catalog.jsonb_typeof(p_prepared -> 'display_fallback') <> 'string'
    or pg_catalog.jsonb_typeof(p_prepared -> 'cue_state') <> 'string'
    or pg_catalog.jsonb_typeof(p_prepared -> 'metadata_allowed') <> 'boolean'
    or pg_catalog.jsonb_typeof(p_prepared -> 'snapshot_version') <> 'number'
    or not private.valid_normalized_fields(p_prepared -> 'normalized_fields')
    or not private.valid_alias_concepts(p_prepared -> 'alias_concepts')
    or not private.valid_cue_flags(p_prepared -> 'cue_flags') then
    raise exception using errcode = 'P0001', message = 'INVALID_PREPARED';
  end if;

  if p_prepared ->> 'snapshot_version' !~ '^[1-9][0-9]*$'
    or pg_catalog.char_length(p_prepared ->> 'snapshot_version') > 10
    or (p_prepared ->> 'snapshot_version')::numeric > 2147483647 then
    raise exception using errcode = 'P0001', message = 'INVALID_PREPARED';
  end if;
  snapshot_version_value := (p_prepared ->> 'snapshot_version')::integer;

  if expected_version_value <> current_item.version
    or snapshot_version_value <> current_item.version then
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
        'item_id', p_item_id,
        'error_code', 'VERSION_CONFLICT'
      )
    );

    return pg_catalog.jsonb_build_object(
      'http_status', 409,
      'error_code', 'VERSION_CONFLICT'
    );
  end if;

  new_title := case when p_body ? 'title' then raw_title else current_item.user_title end;
  new_note := case when p_body ? 'note' then raw_note else current_item.note end;
  title_changed := new_title is distinct from current_item.user_title;
  note_changed := new_note is distinct from current_item.note;
  text_changed := title_changed or note_changed;

  if p_prepared ->> 'normalized_url' <> current_item.normalized_url
    or p_prepared ->> 'url_hash' <> current_item.url_hash
    or p_prepared ->> 'source' <> current_item.source
    or p_prepared ->> 'display_fallback' <> current_item.display_fallback
    or p_prepared #>> '{normalized_fields,categories}' <> normalized_category_names
    or (p_prepared #>> '{normalized_fields,url}')
      <> (current_search.normalized_fields ->> 'url')
    or (
      title_changed
      and p_prepared #>> '{normalized_fields,user_title}' <>
        pg_catalog.btrim(
          pg_catalog.regexp_replace(
            pg_catalog.lower(normalize(coalesce(new_title, ''), NFKC)),
            '[[:space:]]+',
            ' ',
            'g'
          )
        )
    )
    or (
      note_changed
      and p_prepared #>> '{normalized_fields,note}' <>
        pg_catalog.btrim(
          pg_catalog.regexp_replace(
            pg_catalog.lower(normalize(coalesce(new_note, ''), NFKC)),
            '[[:space:]]+',
            ' ',
            'g'
          )
        )
    )
    or p_prepared ->> 'cue_state' not in ('pending', 'missing', 'limited', 'available') then
    raise exception using errcode = 'P0001', message = 'INVALID_PREPARED';
  end if;

  new_text_revision := current_item.text_revision + case when text_changed then 1 else 0 end;
  new_normalized_fields := current_search.normalized_fields
    || pg_catalog.jsonb_build_object(
      'user_title', case
        when title_changed then p_prepared #>> '{normalized_fields,user_title}'
        else current_search.normalized_fields ->> 'user_title'
      end,
      'note', case
        when note_changed then p_prepared #>> '{normalized_fields,note}'
        else current_search.normalized_fields ->> 'note'
      end,
      'categories', normalized_category_names
    );
  new_alias_concepts := current_search.alias_concepts
    || pg_catalog.jsonb_build_object(
      'user_title', case
        when title_changed then p_prepared #> '{alias_concepts,user_title}'
        else current_search.alias_concepts -> 'user_title'
      end,
      'note', case
        when note_changed then p_prepared #> '{alias_concepts,note}'
        else current_search.alias_concepts -> 'note'
      end
    );

  has_classification_text := exists (
    select 1
    from pg_catalog.jsonb_each_text(new_normalized_fields) as field(key, value)
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
  metadata_was_active := current_item.metadata_state in ('queued', 'running');
  manual_override_value := case
    when categories_supplied then true
    else current_controls.manual_override
  end;

  if categories_supplied then
    delete from public.item_categories as selected
    where selected.owner_id = p_owner_id
      and selected.item_id = p_item_id;

    if selected_category_count > 0 then
      insert into public.item_categories (owner_id, item_id, category_id, origin)
      select p_owner_id, p_item_id, requested.category_id::uuid, 'manual'
      from pg_catalog.jsonb_array_elements_text(p_body -> 'category_ids')
        with ordinality as requested(category_id, ordinality)
      order by requested.ordinality;
    end if;

    update public.item_category_controls
    set manual_override = true
    where owner_id = p_owner_id
      and item_id = p_item_id;
  end if;

  if text_changed then
    update public.processing_jobs
    set state = 'cancelled',
        lease_until = null,
        lease_token = null,
        updated_at = pg_catalog.now()
    where owner_id = p_owner_id
      and item_id = p_item_id
      and kind in ('metadata', 'classify')
      and state in ('queued', 'running', 'retry');
  elsif categories_supplied then
    update public.processing_jobs
    set state = 'cancelled',
        lease_until = null,
        lease_token = null,
        updated_at = pg_catalog.now()
    where owner_id = p_owner_id
      and item_id = p_item_id
      and kind = 'classify'
      and state in ('queued', 'running', 'retry');
  end if;

  if text_changed or categories_supplied then
    update public.item_classification
    set target_revision = new_text_revision,
        state = case
          when manual_override_value then 'manual'
          when has_classification_text then 'pending'
          else 'unclassified'
        end,
        reasons = '[]'::jsonb,
        updated_at = pg_catalog.now()
    where owner_id = p_owner_id
      and item_id = p_item_id;
  end if;

  update public.items
  set user_title = case when p_body ? 'title' then raw_title else user_title end,
      note = case when p_body ? 'note' then raw_note else note end,
      text_revision = new_text_revision,
      version = version + 1,
      metadata_state = case
        when text_changed and metadata_was_active then 'queued'
        else metadata_state
      end,
      updated_at = pg_catalog.now()
  where owner_id = p_owner_id
    and id = p_item_id;

  update public.item_search
  set text_revision = new_text_revision,
      normalized_fields = new_normalized_fields,
      alias_concepts = new_alias_concepts,
      cue_version = 'cues-v1.0.0',
      cue_state = p_prepared ->> 'cue_state',
      cue_flags = p_prepared -> 'cue_flags',
      updated_at = pg_catalog.now()
  where owner_id = p_owner_id
    and item_id = p_item_id;

  if text_changed and metadata_was_active then
    insert into public.processing_jobs (
      owner_id,
      item_id,
      kind,
      target_revision,
      state
    )
    values (p_owner_id, p_item_id, 'metadata', new_text_revision, 'queued')
    on conflict (owner_id, item_id, kind, target_revision)
      where state in ('queued', 'running', 'retry')
    do nothing;
  end if;

  if text_changed
    and not manual_override_value
    and has_classification_text then
    insert into public.processing_jobs (
      owner_id,
      item_id,
      kind,
      target_revision,
      state
    )
    values (p_owner_id, p_item_id, 'classify', new_text_revision, 'queued')
    on conflict (owner_id, item_id, kind, target_revision)
      where state in ('queued', 'running', 'retry')
    do nothing;
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
    method_path_value,
    request_hash_value,
    200,
    pg_catalog.jsonb_build_object(
      'item_id', p_item_id,
      'http_status', 200
    )
  );

  result_item := private.library_item_json(p_owner_id, p_item_id, true);
  if result_item is null then
    raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
  end if;

  return pg_catalog.jsonb_build_object(
    'http_status', 200,
    'item', result_item
  );
end;
$$;

create function private.lock_library_owner(p_owner_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  profile_state text;
  member_enabled boolean;
  member_approved_at timestamptz;
begin
  if p_owner_id is null then
    raise exception using errcode = 'P0001', message = 'UNAUTHENTICATED';
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

  perform 1
  from public.library_usage as usage
  where usage.owner_id = p_owner_id
  for update;

  if not found then
    raise exception using errcode = 'P0001', message = 'BETA_ACCESS_REQUIRED';
  end if;
end;
$$;

create function private.library_category_json(
  p_owner_id uuid,
  p_category_id uuid
)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select pg_catalog.jsonb_build_object(
    'id', category.id,
    'name', category.name,
    'kind', category.kind,
    'system_code', category.system_code,
    'item_count', (
      select count(*)::integer
      from public.item_categories as selected
      join public.items as item
        on item.owner_id = selected.owner_id
        and item.id = selected.item_id
        and item.deleted_at is null
      where selected.owner_id = category.owner_id
        and selected.category_id = category.id
    )
  )
  from public.categories as category
  where category.owner_id = p_owner_id
    and category.id = p_category_id;
$$;

create function public.library_create_category(
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
    raise exception using errcode = 'P0001', message = 'CATEGORY_NAME_EXISTS';
  end if;

  select count(*)::integer
  into custom_count
  from public.categories as category
  where category.owner_id = p_owner_id
    and category.kind = 'custom';

  if custom_count >= 30 then
    raise exception using errcode = 'P0001', message = 'CATEGORY_LIMIT_REACHED';
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

create function public.library_rename_category(
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
    raise exception using errcode = 'P0001', message = 'CATEGORY_NAME_EXISTS';
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

create function public.library_delete_category(
  p_owner_id uuid,
  p_category_id uuid,
  p_request_id uuid,
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
  current_category public.categories%rowtype;
  affected_item_ids uuid[];
begin
  if p_category_id is null then
    raise exception using errcode = 'P0001', message = 'CATEGORY_NOT_FOUND';
  end if;
  if p_request_id is null then
    raise exception using errcode = 'P0001', message = 'INVALID_REQUEST_ID';
  end if;

  perform private.lock_library_owner(p_owner_id);

  method_path_value := 'DELETE /categories/' || p_category_id::text;
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

    return pg_catalog.jsonb_build_object('http_status', 204);
  end if;

  if not private.jsonb_has_exact_keys(p_body, '{}'::text[]) then
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

  delete from public.categories
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
    204,
    pg_catalog.jsonb_build_object(
      'category_id', p_category_id,
      'http_status', 204
    )
  );

  return pg_catalog.jsonb_build_object('http_status', 204);
end;
$$;

create function public.library_cue_dismiss(
  p_owner_id uuid,
  p_item_id uuid,
  p_request_id uuid,
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
  text_revision_value integer;
  result_item jsonb;
begin
  if p_item_id is null then
    raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
  end if;
  if p_request_id is null then
    raise exception using errcode = 'P0001', message = 'INVALID_REQUEST_ID';
  end if;

  perform private.lock_library_owner(p_owner_id);

  method_path_value := 'POST /items/' || p_item_id::text || '/cue-dismiss';
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
        'error_code', 'VERSION_CONFLICT'
      );
    end if;

    result_item := private.library_item_json(p_owner_id, p_item_id, true);
    if result_item is null then
      raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
    end if;

    return pg_catalog.jsonb_build_object(
      'http_status', 200,
      'item', result_item
    );
  end if;

  if not private.jsonb_has_exact_keys(
    p_body,
    array['expected_version', 'text_revision']::text[]
  )
    or pg_catalog.jsonb_typeof(p_body -> 'expected_version') <> 'number'
    or pg_catalog.jsonb_typeof(p_body -> 'text_revision') <> 'number'
    or p_body ->> 'expected_version' !~ '^[1-9][0-9]*$'
    or p_body ->> 'text_revision' !~ '^[1-9][0-9]*$'
    or pg_catalog.char_length(p_body ->> 'expected_version') > 10
    or pg_catalog.char_length(p_body ->> 'text_revision') > 10
    or (p_body ->> 'expected_version')::numeric > 2147483647
    or (p_body ->> 'text_revision')::numeric > 2147483647 then
    raise exception using errcode = 'P0001', message = 'INVALID_BODY';
  end if;

  expected_version_value := (p_body ->> 'expected_version')::integer;
  text_revision_value := (p_body ->> 'text_revision')::integer;

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

  perform 1
  from public.item_category_controls as controls
  where controls.owner_id = p_owner_id
    and controls.item_id = p_item_id
  for update;

  if not found then
    raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
  end if;

  if current_item.version <> expected_version_value
    or current_item.text_revision <> text_revision_value then
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
        'item_id', p_item_id,
        'error_code', 'VERSION_CONFLICT'
      )
    );

    return pg_catalog.jsonb_build_object(
      'http_status', 409,
      'error_code', 'VERSION_CONFLICT'
    );
  end if;

  update public.item_category_controls
  set cue_dismissed_revision = current_item.text_revision
  where owner_id = p_owner_id
    and item_id = p_item_id;

  update public.items
  set version = version + 1,
      updated_at = pg_catalog.now()
  where owner_id = p_owner_id
    and id = p_item_id;

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
      'item_id', p_item_id,
      'http_status', 200
    )
  );

  result_item := private.library_item_json(p_owner_id, p_item_id, true);
  if result_item is null then
    raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
  end if;

  return pg_catalog.jsonb_build_object(
    'http_status', 200,
    'item', result_item
  );
end;
$$;

create function public.library_reclassify_item(
  p_owner_id uuid,
  p_item_id uuid,
  p_request_id uuid,
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
  classifier_job_id uuid;
begin
  if p_item_id is null then
    raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
  end if;
  if p_request_id is null then
    raise exception using errcode = 'P0001', message = 'INVALID_REQUEST_ID';
  end if;

  perform private.lock_library_owner(p_owner_id);

  method_path_value := 'POST /items/' || p_item_id::text || '/reclassify';
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
        'error_code', 'VERSION_CONFLICT'
      );
    end if;

    perform 1
    from public.items as item
    where item.owner_id = p_owner_id
      and item.id = p_item_id
      and item.deleted_at is null;

    if not found then
      raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
    end if;

    return pg_catalog.jsonb_build_object(
      'http_status', 202,
      'job_id', previous_request.response_body ->> 'job_id',
      'item_id', p_item_id
    );
  end if;

  if not private.jsonb_has_exact_keys(
    p_body,
    array['expected_version']::text[]
  )
    or pg_catalog.jsonb_typeof(p_body -> 'expected_version') <> 'number'
    or p_body ->> 'expected_version' !~ '^[1-9][0-9]*$'
    or pg_catalog.char_length(p_body ->> 'expected_version') > 10
    or (p_body ->> 'expected_version')::numeric > 2147483647 then
    raise exception using errcode = 'P0001', message = 'INVALID_BODY';
  end if;

  expected_version_value := (p_body ->> 'expected_version')::integer;

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

  perform 1
  from public.item_search as search_record
  where search_record.owner_id = p_owner_id
    and search_record.item_id = p_item_id
  for update;
  if not found then
    raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
  end if;

  perform 1
  from public.item_classification as classification
  where classification.owner_id = p_owner_id
    and classification.item_id = p_item_id
  for update;
  if not found then
    raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
  end if;

  perform 1
  from public.item_category_controls as controls
  where controls.owner_id = p_owner_id
    and controls.item_id = p_item_id
  for update;
  if not found then
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
    )
    values (
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

  update public.processing_jobs
  set state = 'cancelled',
      lease_until = null,
      lease_token = null,
      updated_at = pg_catalog.now()
  where owner_id = p_owner_id
    and item_id = p_item_id
    and kind = 'classify'
    and target_revision <> current_item.text_revision
    and state in ('queued', 'running', 'retry');

  select job.id
  into classifier_job_id
  from public.processing_jobs as job
  where job.owner_id = p_owner_id
    and job.item_id = p_item_id
    and job.kind = 'classify'
    and job.target_revision = current_item.text_revision
    and job.state in ('queued', 'running', 'retry')
  order by job.created_at, job.id
  limit 1
  for update;

  if not found then
    insert into public.processing_jobs (
      owner_id,
      item_id,
      kind,
      target_revision,
      state
    )
    values (
      p_owner_id,
      p_item_id,
      'classify',
      current_item.text_revision,
      'queued'
    )
    returning id into classifier_job_id;
  end if;

  delete from public.item_categories as selected
  using public.categories as category
  where selected.owner_id = p_owner_id
    and selected.item_id = p_item_id
    and category.owner_id = selected.owner_id
    and category.id = selected.category_id
    and category.kind = 'system';

  update public.item_category_controls
  set manual_override = false
  where owner_id = p_owner_id
    and item_id = p_item_id;

  update public.item_classification
  set rules_version = 'rules-v2.0.0',
      target_revision = current_item.text_revision,
      state = 'pending',
      reasons = '[]'::jsonb,
      updated_at = pg_catalog.now()
  where owner_id = p_owner_id
    and item_id = p_item_id;

  update public.items
  set version = version + 1,
      updated_at = pg_catalog.now()
  where owner_id = p_owner_id
    and id = p_item_id;

  perform private.refresh_category_search(
    p_owner_id,
    array[p_item_id]::uuid[]
  );

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
    202,
    pg_catalog.jsonb_build_object(
      'item_id', p_item_id,
      'job_id', classifier_job_id,
      'http_status', 202
    )
  );

  return pg_catalog.jsonb_build_object(
    'http_status', 202,
    'job_id', classifier_job_id,
    'item_id', p_item_id
  );
end;
$$;

revoke all on function private.search_field_score(jsonb, jsonb, text, text)
from public, anon, authenticated, service_role;
revoke all on function private.parse_iso_instant(text)
from public, anon, authenticated, service_role;
revoke all on function private.refresh_category_search(uuid, uuid[])
from public, anon, authenticated, service_role;
revoke all on function private.lock_library_owner(uuid)
from public, anon, authenticated, service_role;
revoke all on function private.library_category_json(uuid, uuid)
from public, anon, authenticated, service_role;
revoke all on function private.library_item_json(uuid, uuid, boolean)
from public, anon, authenticated, service_role;

revoke all on function public.library_search_items(jsonb, jsonb, integer, integer)
from public, anon, authenticated, service_role;
revoke all on function public.library_list_categories()
from public, anon, authenticated, service_role;
revoke all on function public.library_create_category(uuid, uuid, jsonb, text)
from public, anon, authenticated, service_role;
revoke all on function public.library_rename_category(uuid, uuid, uuid, jsonb, text)
from public, anon, authenticated, service_role;
revoke all on function public.library_delete_category(uuid, uuid, uuid, jsonb)
from public, anon, authenticated, service_role;
revoke all on function public.library_cue_dismiss(uuid, uuid, uuid, jsonb)
from public, anon, authenticated, service_role;
revoke all on function public.library_reclassify_item(uuid, uuid, uuid, jsonb)
from public, anon, authenticated, service_role;

grant execute on function public.library_search_items(jsonb, jsonb, integer, integer)
to authenticated;
grant execute on function public.library_list_categories()
to authenticated;
grant execute on function public.library_create_category(uuid, uuid, jsonb, text)
to service_role;
grant execute on function public.library_rename_category(uuid, uuid, uuid, jsonb, text)
to service_role;
grant execute on function public.library_delete_category(uuid, uuid, uuid, jsonb)
to service_role;
grant execute on function public.library_cue_dismiss(uuid, uuid, uuid, jsonb)
to service_role;
grant execute on function public.library_reclassify_item(uuid, uuid, uuid, jsonb)
to service_role;
