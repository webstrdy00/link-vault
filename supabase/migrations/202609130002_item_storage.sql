create extension if not exists pgcrypto with schema extensions;

create schema if not exists private;
revoke all on schema private from public, anon, authenticated, service_role;

create function private.jsonb_has_exact_keys(p_value jsonb, p_keys text[])
returns boolean
language plpgsql
immutable
parallel safe
set search_path = ''
as $$
declare
  expected_key text;
  actual_key_count integer;
begin
  if p_value is null or pg_catalog.jsonb_typeof(p_value) <> 'object' then
    return false;
  end if;

  select count(*)::integer
  into actual_key_count
  from pg_catalog.jsonb_object_keys(p_value);

  if actual_key_count <> pg_catalog.cardinality(p_keys) then
    return false;
  end if;

  foreach expected_key in array p_keys loop
    if not (p_value ? expected_key) then
      return false;
    end if;
  end loop;

  return true;
end;
$$;

create function private.valid_normalized_fields(p_value jsonb)
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
    and pg_catalog.char_length(p_value ->> 'user_title') <= 300
    and pg_catalog.jsonb_typeof(p_value -> 'fetched_title') = 'string'
    and pg_catalog.char_length(p_value ->> 'fetched_title') <= 300
    and pg_catalog.jsonb_typeof(p_value -> 'note') = 'string'
    and pg_catalog.char_length(p_value ->> 'note') <= 4000
    and pg_catalog.jsonb_typeof(p_value -> 'ocr') = 'string'
    and pg_catalog.char_length(p_value ->> 'ocr') <= 20000
    and pg_catalog.jsonb_typeof(p_value -> 'shared') = 'string'
    and pg_catalog.char_length(p_value ->> 'shared') <= 4000
    and pg_catalog.jsonb_typeof(p_value -> 'description') = 'string'
    and pg_catalog.char_length(p_value ->> 'description') <= 4000
    and pg_catalog.jsonb_typeof(p_value -> 'body') = 'string'
    and pg_catalog.char_length(p_value ->> 'body') <= 20000
    and pg_catalog.jsonb_typeof(p_value -> 'categories') = 'string'
    and pg_catalog.char_length(p_value ->> 'categories') <= 154
    and pg_catalog.jsonb_typeof(p_value -> 'url') = 'string'
    and pg_catalog.char_length(p_value ->> 'url') <= 4096;
$$;

create function private.valid_alias_concepts(p_value jsonb)
returns boolean
language plpgsql
immutable
parallel safe
set search_path = ''
as $$
declare
  field_name text;
  concepts jsonb;
  concept_count integer;
  distinct_concept_count integer;
begin
  if not private.jsonb_has_exact_keys(
    p_value,
    array[
      'user_title',
      'fetched_title',
      'note',
      'ocr',
      'shared',
      'description',
      'body'
    ]::text[]
  ) then
    return false;
  end if;

  foreach field_name in array array[
    'user_title',
    'fetched_title',
    'note',
    'ocr',
    'shared',
    'description',
    'body'
  ]::text[] loop
    concepts := p_value -> field_name;
    if pg_catalog.jsonb_typeof(concepts) <> 'array'
      or pg_catalog.jsonb_array_length(concepts) > 8 then
      return false;
    end if;

    if exists (
      select 1
      from pg_catalog.jsonb_array_elements(concepts) as concept(value)
      where pg_catalog.jsonb_typeof(concept.value) <> 'string'
        or concept.value #>> '{}' not in (
          'kakaotalk',
          'profile_photo',
          'instagram',
          'threads',
          'excel',
          'android',
          'youtube',
          'reels'
        )
    ) then
      return false;
    end if;

    select count(*)::integer, count(distinct concept.value #>> '{}')::integer
    into concept_count, distinct_concept_count
    from pg_catalog.jsonb_array_elements(concepts) as concept(value);

    if concept_count <> distinct_concept_count then
      return false;
    end if;
  end loop;

  return true;
end;
$$;

create function private.valid_cue_flags(p_value jsonb)
returns boolean
language plpgsql
immutable
parallel safe
set search_path = ''
as $$
declare
  flag_count integer;
  distinct_flag_count integer;
begin
  if p_value is null or pg_catalog.jsonb_typeof(p_value) <> 'array' then
    return false;
  end if;

  if pg_catalog.jsonb_array_length(p_value) > 5 or exists (
    select 1
    from pg_catalog.jsonb_array_elements(p_value) as flag(value)
    where pg_catalog.jsonb_typeof(flag.value) <> 'string'
      or flag.value #>> '{}' not in (
        'generic_title',
        'metadata_failed',
        'ocr_failed',
        'truncated',
        'short_text'
      )
  ) then
    return false;
  end if;

  select count(*)::integer, count(distinct flag.value #>> '{}')::integer
  into flag_count, distinct_flag_count
  from pg_catalog.jsonb_array_elements(p_value) as flag(value);

  return flag_count = distinct_flag_count;
end;
$$;

create function private.valid_extraction_meta(p_value jsonb)
returns boolean
language sql
immutable
parallel safe
set search_path = ''
as $$
  select
    p_value is not null
    and pg_catalog.jsonb_typeof(p_value) = 'object'
    and not exists (
      select 1
      from pg_catalog.jsonb_object_keys(p_value) as property(key)
      where property.key not in (
        'adapter_version',
        'final_url',
        'title_truncated',
        'description_truncated',
        'body_truncated',
        'last_checked_at',
        'error_code'
      )
    )
    and (
      not (p_value ? 'adapter_version')
      or pg_catalog.jsonb_typeof(p_value -> 'adapter_version') in ('string', 'null')
    )
    and (
      not (p_value ? 'final_url')
      or pg_catalog.jsonb_typeof(p_value -> 'final_url') in ('string', 'null')
    )
    and (
      not (p_value ? 'title_truncated')
      or pg_catalog.jsonb_typeof(p_value -> 'title_truncated') = 'boolean'
    )
    and (
      not (p_value ? 'description_truncated')
      or pg_catalog.jsonb_typeof(p_value -> 'description_truncated') = 'boolean'
    )
    and (
      not (p_value ? 'body_truncated')
      or pg_catalog.jsonb_typeof(p_value -> 'body_truncated') = 'boolean'
    )
    and (
      not (p_value ? 'last_checked_at')
      or pg_catalog.jsonb_typeof(p_value -> 'last_checked_at') in ('string', 'null')
    )
    and (
      not (p_value ? 'error_code')
      or pg_catalog.jsonb_typeof(p_value -> 'error_code') in ('string', 'null')
    );
$$;

create table public.items (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references public.profiles (id) on delete cascade,
  original_url text not null,
  normalized_url text not null,
  url_hash character(64) not null,
  source text not null,
  display_fallback text not null,
  shared_text text,
  user_title text,
  fetched_title text,
  description text,
  body_text text,
  note text,
  extraction_meta jsonb not null default '{}'::jsonb,
  text_revision integer not null default 1,
  version integer not null default 1,
  metadata_state text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  constraint items_owner_id_id_key unique (owner_id, id),
  constraint items_original_url_check check (
    pg_catalog.char_length(original_url) between 1 and 4096
    and original_url = pg_catalog.btrim(original_url)
    and original_url ~* '^https?://[^/?#[:space:]]+([/?#][^[:space:]]*)?$'
    and pg_catalog.strpos(
      pg_catalog.split_part(
        pg_catalog.split_part(
          pg_catalog.split_part(
            pg_catalog.regexp_replace(original_url, '^[^:]+://', '', 'i'),
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
    ) = 0
  ),
  constraint items_normalized_url_check check (
    pg_catalog.char_length(normalized_url) between 1 and 4096
    and normalized_url = pg_catalog.btrim(normalized_url)
    and normalized_url ~ '^https?://[^/?#[:space:]]+([/?#][^[:space:]]*)?$'
    and pg_catalog.strpos(
      pg_catalog.split_part(
        pg_catalog.split_part(
          pg_catalog.split_part(
            pg_catalog.regexp_replace(normalized_url, '^[^:]+://', '', 'i'),
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
    ) = 0
  ),
  constraint items_url_hash_check check (url_hash ~ '^[0-9a-f]{64}$'),
  constraint items_source_check check (source in ('instagram', 'threads', 'naver_blog', 'other')),
  constraint items_display_fallback_check check (
    pg_catalog.char_length(pg_catalog.btrim(display_fallback)) between 1 and 300
  ),
  constraint items_shared_text_check check (
    shared_text is null or pg_catalog.char_length(shared_text) <= 4000
  ),
  constraint items_user_title_check check (
    user_title is null or pg_catalog.char_length(user_title) <= 300
  ),
  constraint items_fetched_title_check check (
    fetched_title is null or pg_catalog.char_length(fetched_title) <= 300
  ),
  constraint items_description_check check (
    description is null or pg_catalog.char_length(description) <= 4000
  ),
  constraint items_body_text_check check (
    body_text is null or pg_catalog.char_length(body_text) <= 20000
  ),
  constraint items_note_check check (note is null or pg_catalog.char_length(note) <= 4000),
  constraint items_extraction_meta_check check (private.valid_extraction_meta(extraction_meta)),
  constraint items_text_revision_check check (text_revision >= 1),
  constraint items_version_check check (version >= 1),
  constraint items_metadata_state_check check (
    metadata_state in ('queued', 'running', 'ready', 'partial', 'unsupported', 'failed')
  )
);

create unique index items_owner_url_hash_active_key
on public.items (owner_id, url_hash)
where deleted_at is null;

create index items_owner_created_active_idx
on public.items (owner_id, created_at desc, id desc)
where deleted_at is null;

create table public.item_search (
  owner_id uuid not null,
  item_id uuid not null,
  text_revision integer not null,
  search_version text not null,
  normalized_fields jsonb not null,
  alias_concepts jsonb not null,
  cue_version text not null,
  cue_state text not null,
  cue_flags jsonb not null,
  updated_at timestamptz not null default now(),
  primary key (owner_id, item_id),
  constraint item_search_item_fkey
    foreign key (owner_id, item_id)
    references public.items (owner_id, id)
    on delete cascade,
  constraint item_search_text_revision_check check (text_revision >= 1),
  constraint item_search_search_version_check check (search_version = 'search-v2.0.0'),
  constraint item_search_normalized_fields_check check (
    private.valid_normalized_fields(normalized_fields)
  ),
  constraint item_search_alias_concepts_check check (
    private.valid_alias_concepts(alias_concepts)
  ),
  constraint item_search_cue_version_check check (cue_version = 'cues-v1.0.0'),
  constraint item_search_cue_state_check check (
    cue_state in ('pending', 'missing', 'limited', 'available')
  ),
  constraint item_search_cue_flags_check check (private.valid_cue_flags(cue_flags))
);

create table public.item_classification (
  owner_id uuid not null,
  item_id uuid not null,
  rules_version text not null,
  target_revision integer not null,
  state text not null,
  reasons jsonb not null default '[]'::jsonb,
  updated_at timestamptz not null default now(),
  primary key (owner_id, item_id),
  constraint item_classification_item_fkey
    foreign key (owner_id, item_id)
    references public.items (owner_id, id)
    on delete cascade,
  constraint item_classification_rules_version_check check (rules_version = 'rules-v2.0.0'),
  constraint item_classification_target_revision_check check (target_revision >= 1),
  constraint item_classification_state_check check (
    state in ('pending', 'automatic', 'manual', 'unclassified')
  ),
  constraint item_classification_reasons_check check (
    pg_catalog.jsonb_typeof(reasons) = 'array'
  )
);

create table public.item_category_controls (
  owner_id uuid not null,
  item_id uuid not null,
  manual_override boolean not null default false,
  cue_dismissed_revision integer,
  primary key (owner_id, item_id),
  constraint item_category_controls_item_fkey
    foreign key (owner_id, item_id)
    references public.items (owner_id, id)
    on delete cascade,
  constraint item_category_controls_cue_revision_check check (
    cue_dismissed_revision is null or cue_dismissed_revision >= 1
  )
);

create table public.item_categories (
  owner_id uuid not null,
  item_id uuid not null,
  category_id uuid not null,
  origin text not null,
  created_at timestamptz not null default now(),
  primary key (owner_id, item_id, category_id),
  constraint item_categories_item_fkey
    foreign key (owner_id, item_id)
    references public.items (owner_id, id)
    on delete cascade,
  constraint item_categories_category_fkey
    foreign key (owner_id, category_id)
    references public.categories (owner_id, id)
    on delete cascade,
  constraint item_categories_origin_check check (origin in ('auto', 'manual'))
);

create index item_categories_owner_category_item_idx
on public.item_categories (owner_id, category_id, item_id);

create function private.enforce_item_category_limit()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  selected_count integer;
begin
  perform 1
  from public.items as item
  where item.owner_id = new.owner_id
    and item.id = new.item_id
  for update;

  if not found then
    raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
  end if;

  select count(*)::integer
  into selected_count
  from public.item_categories as selected
  where selected.owner_id = new.owner_id
    and selected.item_id = new.item_id;

  if selected_count >= 5 then
    raise exception using errcode = 'P0001', message = 'CATEGORY_LIMIT_REACHED';
  end if;

  return new;
end;
$$;

create trigger item_categories_enforce_limit
before insert on public.item_categories
for each row execute function private.enforce_item_category_limit();

create table public.processing_jobs (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null,
  item_id uuid not null,
  kind text not null,
  target_revision integer not null,
  state text not null default 'queued',
  attempts integer not null default 0,
  next_run_at timestamptz not null default now(),
  lease_until timestamptz,
  lease_token uuid,
  last_error_code text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint processing_jobs_item_fkey
    foreign key (owner_id, item_id)
    references public.items (owner_id, id)
    on delete cascade,
  constraint processing_jobs_kind_check check (kind in ('metadata', 'classify', 'cleanup')),
  constraint processing_jobs_target_revision_check check (target_revision >= 1),
  constraint processing_jobs_state_check check (
    state in ('queued', 'running', 'retry', 'succeeded', 'failed', 'cancelled')
  ),
  constraint processing_jobs_attempts_check check (attempts >= 0),
  constraint processing_jobs_lease_check check (
    (state = 'running' and lease_until is not null and lease_token is not null)
    or (state <> 'running' and lease_until is null and lease_token is null)
  ),
  constraint processing_jobs_last_error_code_check check (
    last_error_code is null or pg_catalog.char_length(last_error_code) between 1 and 100
  )
);

create unique index processing_jobs_active_key
on public.processing_jobs (owner_id, item_id, kind, target_revision)
where state in ('queued', 'running', 'retry');

create index processing_jobs_ready_idx
on public.processing_jobs (state, next_run_at);

create table public.api_requests (
  owner_id uuid not null references public.profiles (id) on delete cascade,
  request_id uuid not null,
  method_path text not null,
  request_hash character(64) not null,
  response_code integer not null,
  response_body jsonb not null,
  created_at timestamptz not null default now(),
  primary key (owner_id, request_id),
  constraint api_requests_method_path_check check (method_path = 'POST /items'),
  constraint api_requests_request_hash_check check (request_hash ~ '^[0-9a-f]{64}$'),
  constraint api_requests_response_code_check check (response_code in (200, 201)),
  constraint api_requests_response_body_check check (
    private.jsonb_has_exact_keys(
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
);

create index api_requests_created_at_idx
on public.api_requests (created_at);

create table public.api_rate_buckets (
  owner_id uuid not null references public.profiles (id) on delete cascade,
  operation text not null,
  window_start timestamptz not null,
  request_count integer not null default 0,
  primary key (owner_id, operation, window_start),
  constraint api_rate_buckets_operation_check check (operation = 'create_item'),
  constraint api_rate_buckets_window_check check (
    window_start = pg_catalog.date_trunc('minute', window_start)
  ),
  constraint api_rate_buckets_count_check check (request_count >= 0)
);

alter table public.items enable row level security;
alter table public.item_search enable row level security;
alter table public.item_classification enable row level security;
alter table public.item_category_controls enable row level security;
alter table public.item_categories enable row level security;
alter table public.processing_jobs enable row level security;
alter table public.api_requests enable row level security;
alter table public.api_rate_buckets enable row level security;

create policy items_select_own_active_member
on public.items
for select
to authenticated
using (
  deleted_at is null
  and public.member_access_allowed(owner_id)
);

create policy item_search_select_own_active_member
on public.item_search
for select
to authenticated
using (
  public.member_access_allowed(owner_id)
  and exists (
    select 1
    from public.items as parent_item
    where parent_item.owner_id = item_search.owner_id
      and parent_item.id = item_search.item_id
      and parent_item.deleted_at is null
  )
);

create policy item_classification_select_own_active_member
on public.item_classification
for select
to authenticated
using (
  public.member_access_allowed(owner_id)
  and exists (
    select 1
    from public.items as parent_item
    where parent_item.owner_id = item_classification.owner_id
      and parent_item.id = item_classification.item_id
      and parent_item.deleted_at is null
  )
);

create policy item_category_controls_select_own_active_member
on public.item_category_controls
for select
to authenticated
using (
  public.member_access_allowed(owner_id)
  and exists (
    select 1
    from public.items as parent_item
    where parent_item.owner_id = item_category_controls.owner_id
      and parent_item.id = item_category_controls.item_id
      and parent_item.deleted_at is null
  )
);

create policy item_categories_select_own_active_member
on public.item_categories
for select
to authenticated
using (
  public.member_access_allowed(owner_id)
  and exists (
    select 1
    from public.items as parent_item
    where parent_item.owner_id = item_categories.owner_id
      and parent_item.id = item_categories.item_id
      and parent_item.deleted_at is null
  )
);

revoke all privileges on table public.items from public, anon, authenticated, service_role;
revoke all privileges on table public.item_search from public, anon, authenticated, service_role;
revoke all privileges on table public.item_classification from public, anon, authenticated, service_role;
revoke all privileges on table public.item_category_controls from public, anon, authenticated, service_role;
revoke all privileges on table public.item_categories from public, anon, authenticated, service_role;
revoke all privileges on table public.processing_jobs from public, anon, authenticated, service_role;
revoke all privileges on table public.api_requests from public, anon, authenticated, service_role;
revoke all privileges on table public.api_rate_buckets from public, anon, authenticated, service_role;

grant select on table public.items to authenticated;
grant select on table public.item_search to authenticated;
grant select on table public.item_classification to authenticated;
grant select on table public.item_category_controls to authenticated;
grant select on table public.item_categories to authenticated;

grant select, insert, update, delete on table public.items to service_role;
grant select, insert, update, delete on table public.item_search to service_role;
grant select, insert, update, delete on table public.item_classification to service_role;
grant select, insert, update, delete on table public.item_category_controls to service_role;
grant select, insert, update, delete on table public.item_categories to service_role;
grant select, insert, update, delete on table public.processing_jobs to service_role;
grant select, insert, update, delete on table public.api_requests to service_role;
grant select, insert, update, delete on table public.api_rate_buckets to service_role;

grant usage on schema private to service_role;
grant execute on function private.jsonb_has_exact_keys(jsonb, text[]) to service_role;
grant execute on function private.valid_normalized_fields(jsonb) to service_role;
grant execute on function private.valid_alias_concepts(jsonb) to service_role;
grant execute on function private.valid_cue_flags(jsonb) to service_role;
grant execute on function private.valid_extraction_meta(jsonb) to service_role;

create function private.library_item_json(
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
        'search_version', search_record.search_version,
        'rules_version', classification.rules_version,
        'classification_reasons', classification.reasons,
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

create function public.library_create_item(
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

create function public.library_list_items(
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

  with page as materialized (
    select item.id, item.created_at
    from public.items as item
    where item.owner_id = caller_id
      and item.deleted_at is null
    order by item.created_at desc, item.id desc
    offset p_offset
    limit p_limit + 1
  )
  select pg_catalog.jsonb_build_object(
    'items', coalesce(
      (
        select pg_catalog.jsonb_agg(
          private.library_item_json(caller_id, visible.id, false)
          order by visible.created_at desc, visible.id desc
        )
        from (
          select page.id, page.created_at
          from page
          order by page.created_at desc, page.id desc
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

create function public.library_get_item(p_item_id uuid)
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

  if p_item_id is null then
    raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
  end if;

  result := private.library_item_json(caller_id, p_item_id, true);
  if result is null then
    raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
  end if;

  return result;
end;
$$;

revoke all on function private.jsonb_has_exact_keys(jsonb, text[]) from public, anon, authenticated;
revoke all on function private.valid_normalized_fields(jsonb) from public, anon, authenticated;
revoke all on function private.valid_alias_concepts(jsonb) from public, anon, authenticated;
revoke all on function private.valid_cue_flags(jsonb) from public, anon, authenticated;
revoke all on function private.valid_extraction_meta(jsonb) from public, anon, authenticated;
revoke all on function private.enforce_item_category_limit() from public, anon, authenticated, service_role;
revoke all on function private.library_item_json(uuid, uuid, boolean) from public, anon, authenticated, service_role;

revoke all on function public.library_create_item(uuid, uuid, jsonb, jsonb) from public, anon, authenticated, service_role;
revoke all on function public.library_list_items(integer, integer) from public, anon, authenticated, service_role;
revoke all on function public.library_get_item(uuid) from public, anon, authenticated, service_role;

grant execute on function public.library_create_item(uuid, uuid, jsonb, jsonb) to service_role;
grant execute on function public.library_list_items(integer, integer) to authenticated;
grant execute on function public.library_get_item(uuid) to authenticated;
