alter table public.api_requests
  drop constraint api_requests_method_path_check,
  drop constraint api_requests_response_body_check;

alter table public.api_requests
  add constraint api_requests_method_path_check check (
    method_path = 'POST /items'
    or method_path ~* '^PATCH /items/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
  ),
  add constraint api_requests_response_body_check check (
    pg_catalog.jsonb_typeof(response_body -> 'item_id') = 'string'
    and response_body ->> 'item_id' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    and pg_catalog.jsonb_typeof(response_body -> 'http_status') = 'number'
    and (response_body ->> 'http_status')::integer = response_code
    and (
      (
        method_path = 'POST /items'
        and private.jsonb_has_exact_keys(
          response_body,
          array['item_id', 'duplicate', 'http_status']::text[]
        )
        and pg_catalog.jsonb_typeof(response_body -> 'duplicate') = 'boolean'
        and (
          (response_code = 200 and (response_body ->> 'duplicate')::boolean)
          or (response_code = 201 and not (response_body ->> 'duplicate')::boolean)
        )
      )
      or (
        method_path ~* '^PATCH /items/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        and response_code = 200
        and private.jsonb_has_exact_keys(
          response_body,
          array['item_id', 'http_status']::text[]
        )
      )
    )
  );

create function public.library_update_item(
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
          pg_catalog.btrim(
            pg_catalog.regexp_replace(
              pg_catalog.lower(normalize(category.name, NFKC)),
              '[[:space:]]+',
              ' ',
              'g'
            )
          ),
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
        pg_catalog.btrim(
          pg_catalog.regexp_replace(
            pg_catalog.lower(normalize(category.name, NFKC)),
            '[[:space:]]+',
            ' ',
            'g'
          )
        ),
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
    raise exception using errcode = 'P0001', message = 'VERSION_CONFLICT';
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

revoke all on function public.library_update_item(uuid, uuid, uuid, jsonb, jsonb)
from public, anon, authenticated, service_role;
grant execute on function public.library_update_item(uuid, uuid, uuid, jsonb, jsonb)
to service_role;
