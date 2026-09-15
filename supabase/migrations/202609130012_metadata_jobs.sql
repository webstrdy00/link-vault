alter table public.api_requests
  drop constraint api_requests_method_path_check,
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
    or method_path ~* '^POST /items/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/retry-metadata$'
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
          and private.jsonb_has_exact_keys(response_body, array['error_code']::text[])
          and pg_catalog.jsonb_typeof(response_body -> 'error_code') = 'string'
          and response_body ->> 'error_code' in ('ITEM_LIMIT_REACHED', 'URL_HASH_COLLISION')
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
          and private.jsonb_has_exact_keys(response_body, array['item_id', 'http_status']::text[])
          and pg_catalog.jsonb_typeof(response_body -> 'item_id') = 'string'
          and response_body ->> 'item_id' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
          and pg_catalog.jsonb_typeof(response_body -> 'http_status') = 'number'
          and (response_body ->> 'http_status')::integer = response_code
        )
        or (
          response_code = 409
          and private.jsonb_has_exact_keys(response_body, array['item_id', 'error_code']::text[])
          and pg_catalog.jsonb_typeof(response_body -> 'item_id') = 'string'
          and response_body ->> 'item_id' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
          and response_body ->> 'error_code' = 'VERSION_CONFLICT'
        )
      )
    )
    or (
      method_path ~* '^POST /items/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/(reclassify|retry-metadata)$'
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
          and private.jsonb_has_exact_keys(response_body, array['item_id', 'error_code']::text[])
          and pg_catalog.jsonb_typeof(response_body -> 'item_id') = 'string'
          and response_body ->> 'item_id' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
          and response_body ->> 'error_code' = 'VERSION_CONFLICT'
        )
      )
    )
    or (
      method_path = 'POST /categories'
      and (
        (
          response_code = 201
          and private.jsonb_has_exact_keys(response_body, array['category_id', 'http_status']::text[])
          and pg_catalog.jsonb_typeof(response_body -> 'category_id') = 'string'
          and response_body ->> 'category_id' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
          and pg_catalog.jsonb_typeof(response_body -> 'http_status') = 'number'
          and (response_body ->> 'http_status')::integer = response_code
        )
        or (
          response_code = 409
          and private.jsonb_has_exact_keys(response_body, array['error_code']::text[])
          and response_body ->> 'error_code' in ('CATEGORY_NAME_EXISTS', 'CATEGORY_LIMIT_REACHED')
        )
      )
    )
    or (
      method_path ~* '^PATCH /categories/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
      and (
        (
          response_code = 200
          and private.jsonb_has_exact_keys(response_body, array['category_id', 'http_status']::text[])
          and pg_catalog.jsonb_typeof(response_body -> 'category_id') = 'string'
          and response_body ->> 'category_id' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
          and pg_catalog.jsonb_typeof(response_body -> 'http_status') = 'number'
          and (response_body ->> 'http_status')::integer = response_code
        )
        or (
          response_code = 409
          and private.jsonb_has_exact_keys(response_body, array['category_id', 'error_code']::text[])
          and pg_catalog.jsonb_typeof(response_body -> 'category_id') = 'string'
          and response_body ->> 'category_id' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
          and response_body ->> 'error_code' = 'CATEGORY_NAME_EXISTS'
        )
      )
    )
    or (
      method_path ~* '^DELETE /categories/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
      and response_code = 204
      and private.jsonb_has_exact_keys(response_body, array['category_id', 'http_status']::text[])
      and pg_catalog.jsonb_typeof(response_body -> 'category_id') = 'string'
      and response_body ->> 'category_id' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
      and pg_catalog.jsonb_typeof(response_body -> 'http_status') = 'number'
      and (response_body ->> 'http_status')::integer = response_code
    )
  );

alter table public.api_rate_buckets
  drop constraint api_rate_buckets_operation_check,
  drop constraint api_rate_buckets_window_check;

alter table public.api_rate_buckets
  add constraint api_rate_buckets_operation_check check (
    operation = 'create_item'
    or operation ~ '^metadata_(fetch|manual):[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
  ),
  add constraint api_rate_buckets_window_check check (
    (operation = 'create_item' and window_start = pg_catalog.date_trunc('minute', window_start))
    or (
      operation ~ '^metadata_'
      and window_start = pg_catalog.date_trunc('day', window_start at time zone 'UTC') at time zone 'UTC'
    )
  );

create function private.metadata_url_allowed(p_url text)
returns boolean
language sql
immutable
parallel safe
set search_path = ''
as $$
  select coalesce(
    p_url ~* '^https://(blog[.]naver[.]com|m[.]blog[.]naver[.]com)(:443)?([/?#]|$)',
    false
  );
$$;

create function private.valid_metadata_result(p_result jsonb)
returns boolean
language sql
immutable
parallel safe
set search_path = ''
as $$
  select
    private.jsonb_has_exact_keys(
      p_result,
      array[
        'fetched_title',
        'description',
        'body_text',
        'metadata_state',
        'extraction_meta'
      ]::text[]
    )
    and pg_catalog.jsonb_typeof(p_result -> 'fetched_title') in ('string', 'null')
    and pg_catalog.jsonb_typeof(p_result -> 'description') in ('string', 'null')
    and pg_catalog.jsonb_typeof(p_result -> 'body_text') in ('string', 'null')
    and coalesce(pg_catalog.char_length(p_result ->> 'fetched_title'), 0) <= 300
    and coalesce(pg_catalog.char_length(p_result ->> 'description'), 0) <= 4000
    and coalesce(pg_catalog.char_length(p_result ->> 'body_text'), 0) <= 20000
    and pg_catalog.jsonb_typeof(p_result -> 'metadata_state') = 'string'
    and p_result ->> 'metadata_state' in ('ready', 'partial')
    and (
      p_result ->> 'metadata_state' = 'partial'
      or nullif(pg_catalog.btrim(p_result ->> 'fetched_title'), '') is not null
      or nullif(pg_catalog.btrim(p_result ->> 'description'), '') is not null
      or nullif(pg_catalog.btrim(p_result ->> 'body_text'), '') is not null
    )
    and private.valid_extraction_meta(p_result -> 'extraction_meta')
    and (
      not (p_result -> 'extraction_meta' ? 'adapter_version')
      or p_result #>> '{extraction_meta,adapter_version}' is null
      or pg_catalog.char_length(p_result #>> '{extraction_meta,adapter_version}') between 1 and 100
    )
    and (
      not (p_result -> 'extraction_meta' ? 'final_url')
      or p_result #>> '{extraction_meta,final_url}' is null
      or (
        pg_catalog.char_length(p_result #>> '{extraction_meta,final_url}') between 1 and 4096
        and private.metadata_url_allowed(p_result #>> '{extraction_meta,final_url}')
      )
    )
    and (
      not (p_result -> 'extraction_meta' ? 'last_checked_at')
      or p_result #>> '{extraction_meta,last_checked_at}' is null
      or p_result #>> '{extraction_meta,last_checked_at}'
        ~* '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$'
    )
    and (
      not (p_result -> 'extraction_meta' ? 'error_code')
      or p_result #>> '{extraction_meta,error_code}' is null
      or p_result #>> '{extraction_meta,error_code}' in (
        'METADATA_TIMEOUT',
        'NETWORK_ERROR',
        'RATE_LIMITED',
        'ACCESS_DENIED',
        'INVALID_CONTENT',
        'RESPONSE_TOO_LARGE',
        'METADATA_BUDGET_EXHAUSTED',
        'INVALID_RESULT',
        'RETRY_AFTER_EXCEEDED',
        'INTERNAL_ERROR'
      )
    );
$$;

create function private.valid_metadata_prepared(
  p_prepared jsonb,
  p_snapshot_version integer
)
returns boolean
language sql
immutable
parallel safe
set search_path = ''
as $$
  select
    private.jsonb_has_exact_keys(
      p_prepared,
      array[
        'normalized_fields',
        'alias_concepts',
        'cue_state',
        'cue_flags',
        'snapshot_version'
      ]::text[]
    )
    and private.valid_normalized_fields(p_prepared -> 'normalized_fields')
    and private.valid_alias_concepts(p_prepared -> 'alias_concepts')
    and pg_catalog.jsonb_typeof(p_prepared -> 'cue_state') = 'string'
    and p_prepared ->> 'cue_state' in ('pending', 'missing', 'limited', 'available')
    and private.valid_cue_flags(p_prepared -> 'cue_flags')
    and pg_catalog.jsonb_typeof(p_prepared -> 'snapshot_version') = 'number'
    and p_prepared ->> 'snapshot_version' ~ '^[1-9][0-9]*$'
    and pg_catalog.char_length(p_prepared ->> 'snapshot_version') <= 10
    and (p_prepared ->> 'snapshot_version')::numeric <= 2147483647
    and (p_prepared ->> 'snapshot_version')::integer = p_snapshot_version;
$$;

create function private.metadata_error_retryable(p_error_code text)
returns boolean
language sql
immutable
parallel safe
set search_path = ''
as $$
  select p_error_code in (
    'METADATA_TIMEOUT',
    'NETWORK_ERROR',
    'RATE_LIMITED',
    'INTERNAL_ERROR'
  );
$$;

create function private.ensure_current_metadata_job(
  p_owner_id uuid,
  p_item_id uuid,
  p_target_revision integer
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  ensured_job_id uuid;
begin
  select job.id
  into ensured_job_id
  from public.processing_jobs as job
  where job.owner_id = p_owner_id
    and job.item_id = p_item_id
    and job.kind = 'metadata'
    and job.target_revision = p_target_revision
    and job.state in ('queued', 'running', 'retry')
  order by job.created_at, job.id
  limit 1;

  if ensured_job_id is null then
    insert into public.processing_jobs (
      owner_id,
      item_id,
      kind,
      target_revision,
      state
    )
    values (p_owner_id, p_item_id, 'metadata', p_target_revision, 'queued')
    on conflict (owner_id, item_id, kind, target_revision)
      where state in ('queued', 'running', 'retry')
    do nothing
    returning id into ensured_job_id;

    if ensured_job_id is null then
      select job.id
      into ensured_job_id
      from public.processing_jobs as job
      where job.owner_id = p_owner_id
        and job.item_id = p_item_id
        and job.kind = 'metadata'
        and job.target_revision = p_target_revision
        and job.state in ('queued', 'running', 'retry')
      order by job.created_at, job.id
      limit 1;
    end if;
  end if;

  return ensured_job_id;
end;
$$;

create function public.library_claim_metadata_jobs(p_limit integer default 10)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  effective_limit integer;
  current_job public.processing_jobs%rowtype;
  current_item public.items%rowtype;
  candidate record;
  budget_window timestamptz;
  budget_operation text;
  budget_count integer;
  lease_token_value uuid;
  expected_version_value integer;
  new_extraction_meta jsonb;
  state_changed boolean;
  claimed_jobs jsonb := '[]'::jsonb;
begin
  if p_limit is null or p_limit < 1 then
    raise exception using errcode = 'P0001', message = 'INVALID_LIMIT';
  end if;
  effective_limit := least(p_limit, 10);
  budget_window := pg_catalog.date_trunc(
    'day',
    pg_catalog.clock_timestamp() at time zone 'UTC'
  ) at time zone 'UTC';

  with obsolete as materialized (
    select job.id, job.owner_id, job.item_id
    from public.processing_jobs as job
    left join public.items as item
      on item.owner_id = job.owner_id
      and item.id = job.item_id
    left join public.item_search as search_record
      on search_record.owner_id = job.owner_id
      and search_record.item_id = job.item_id
    left join public.item_classification as classification
      on classification.owner_id = job.owner_id
      and classification.item_id = job.item_id
    left join public.item_category_controls as controls
      on controls.owner_id = job.owner_id
      and controls.item_id = job.item_id
    left join public.profiles as profile
      on profile.id = job.owner_id
    left join public.beta_members as member
      on member.owner_id = job.owner_id
    where job.kind = 'metadata'
      and (
        (job.state in ('queued', 'retry') and job.next_run_at <= pg_catalog.clock_timestamp())
        or (job.state = 'running' and job.lease_until <= pg_catalog.clock_timestamp())
      )
      and (
        item.id is null
        or item.deleted_at is not null
        or item.text_revision <> job.target_revision
        or search_record.item_id is null
        or search_record.text_revision <> job.target_revision
        or classification.item_id is null
        or classification.target_revision <> job.target_revision
        or controls.item_id is null
        or item.metadata_state not in ('queued', 'running')
        or profile.state is distinct from 'active'
        or member.enabled is distinct from true
        or member.approved_at is null
      )
    for update of job skip locked
  ), cancelled as (
    update public.processing_jobs as job
    set state = 'cancelled',
        lease_until = null,
        lease_token = null,
        last_error_code = null,
        updated_at = pg_catalog.clock_timestamp()
    from obsolete
    where job.id = obsolete.id
    returning obsolete.owner_id, obsolete.item_id
  )
  insert into public.processing_jobs (
    owner_id,
    item_id,
    kind,
    target_revision,
    state
  )
  select distinct
    item.owner_id,
    item.id,
    'metadata',
    item.text_revision,
    'queued'
  from cancelled
  join public.items as item
    on item.owner_id = cancelled.owner_id
    and item.id = cancelled.item_id
    and item.deleted_at is null
    and item.metadata_state in ('queued', 'running')
  join public.item_search as search_record
    on search_record.owner_id = item.owner_id
    and search_record.item_id = item.id
    and search_record.text_revision = item.text_revision
  join public.item_classification as classification
    on classification.owner_id = item.owner_id
    and classification.item_id = item.id
    and classification.target_revision = item.text_revision
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
  where private.metadata_url_allowed(item.original_url)
  on conflict (owner_id, item_id, kind, target_revision)
    where state in ('queued', 'running', 'retry')
  do nothing;

  for candidate in
    select job.id
    from public.processing_jobs as job
    join public.items as item
      on item.owner_id = job.owner_id
      and item.id = job.item_id
      and item.deleted_at is null
      and item.text_revision = job.target_revision
      and item.metadata_state in ('queued', 'running')
    join public.item_search as search_record
      on search_record.owner_id = job.owner_id
      and search_record.item_id = job.item_id
      and search_record.text_revision = job.target_revision
    join public.item_classification as classification
      on classification.owner_id = job.owner_id
      and classification.item_id = job.item_id
      and classification.target_revision = job.target_revision
    join public.item_category_controls as controls
      on controls.owner_id = job.owner_id
      and controls.item_id = job.item_id
    join public.profiles as profile
      on profile.id = job.owner_id
      and profile.state = 'active'
    join public.beta_members as member
      on member.owner_id = job.owner_id
      and member.enabled
      and member.approved_at is not null
    where job.kind = 'metadata'
      and (
        (job.state in ('queued', 'retry') and job.next_run_at <= pg_catalog.clock_timestamp())
        or (job.state = 'running' and job.lease_until <= pg_catalog.clock_timestamp())
      )
    order by job.next_run_at, job.created_at, job.id
    for update of job, item skip locked
    limit effective_limit
  loop
    select job.*
    into current_job
    from public.processing_jobs as job
    where job.id = candidate.id;

    select item.*
    into current_item
    from public.items as item
    where item.owner_id = current_job.owner_id
      and item.id = current_job.item_id;

    if not private.metadata_url_allowed(current_item.original_url) then
      new_extraction_meta := current_item.extraction_meta || pg_catalog.jsonb_build_object(
        'last_checked_at', pg_catalog.clock_timestamp(),
        'error_code', 'INVALID_CONTENT'
      );
      state_changed := current_item.metadata_state is distinct from 'unsupported'
        or current_item.extraction_meta is distinct from new_extraction_meta;

      update public.processing_jobs
      set state = 'failed',
          lease_until = null,
          lease_token = null,
          last_error_code = 'INVALID_CONTENT',
          updated_at = pg_catalog.clock_timestamp()
      where id = current_job.id;

      update public.items
      set metadata_state = 'unsupported',
          extraction_meta = new_extraction_meta,
          version = version + case when state_changed then 1 else 0 end,
          updated_at = case when state_changed then pg_catalog.clock_timestamp() else updated_at end
      where owner_id = current_job.owner_id
        and id = current_job.item_id;
      continue;
    end if;

    if current_job.attempts >= 4 then
      new_extraction_meta := current_item.extraction_meta || pg_catalog.jsonb_build_object(
        'last_checked_at', pg_catalog.clock_timestamp(),
        'error_code', 'METADATA_TIMEOUT'
      );
      state_changed := current_item.metadata_state is distinct from
          case
            when current_item.fetched_title is not null
              or current_item.description is not null
              or current_item.body_text is not null then 'partial'
            else 'failed'
          end
        or current_item.extraction_meta is distinct from new_extraction_meta;

      update public.processing_jobs
      set state = 'failed',
          lease_until = null,
          lease_token = null,
          last_error_code = 'METADATA_TIMEOUT',
          updated_at = pg_catalog.clock_timestamp()
      where id = current_job.id;

      update public.items
      set metadata_state = case
            when fetched_title is not null or description is not null or body_text is not null
              then 'partial'
            else 'failed'
          end,
          extraction_meta = new_extraction_meta,
          version = version + case when state_changed then 1 else 0 end,
          updated_at = case when state_changed then pg_catalog.clock_timestamp() else updated_at end
      where owner_id = current_job.owner_id
        and id = current_job.item_id;
      continue;
    end if;

    budget_operation := 'metadata_fetch:' || current_job.item_id::text;
    budget_count := null;
    insert into public.api_rate_buckets (
      owner_id,
      operation,
      window_start,
      request_count
    )
    values (current_job.owner_id, budget_operation, budget_window, 1)
    on conflict (owner_id, operation, window_start)
    do update
    set request_count = public.api_rate_buckets.request_count + 1
    where public.api_rate_buckets.request_count < 6
    returning request_count into budget_count;

    if budget_count is null then
      new_extraction_meta := current_item.extraction_meta || pg_catalog.jsonb_build_object(
        'last_checked_at', pg_catalog.clock_timestamp(),
        'error_code', 'METADATA_BUDGET_EXHAUSTED'
      );
      state_changed := current_item.metadata_state is distinct from
          case
            when current_item.fetched_title is not null
              or current_item.description is not null
              or current_item.body_text is not null then 'partial'
            else 'failed'
          end
        or current_item.extraction_meta is distinct from new_extraction_meta;

      update public.processing_jobs
      set state = 'failed',
          lease_until = null,
          lease_token = null,
          last_error_code = 'METADATA_BUDGET_EXHAUSTED',
          updated_at = pg_catalog.clock_timestamp()
      where id = current_job.id;

      update public.items
      set metadata_state = case
            when fetched_title is not null or description is not null or body_text is not null
              then 'partial'
            else 'failed'
          end,
          extraction_meta = new_extraction_meta,
          version = version + case when state_changed then 1 else 0 end,
          updated_at = case when state_changed then pg_catalog.clock_timestamp() else updated_at end
      where owner_id = current_job.owner_id
        and id = current_job.item_id;
      continue;
    end if;

    lease_token_value := extensions.gen_random_uuid();
    update public.processing_jobs
    set state = 'running',
        attempts = attempts + 1,
        lease_until = pg_catalog.clock_timestamp() + interval '180 seconds',
        lease_token = lease_token_value,
        last_error_code = null,
        updated_at = pg_catalog.clock_timestamp()
    where id = current_job.id;

    update public.items
    set metadata_state = 'running',
        version = version + case when metadata_state is distinct from 'running' then 1 else 0 end,
        updated_at = case
          when metadata_state is distinct from 'running' then pg_catalog.clock_timestamp()
          else updated_at
        end
    where owner_id = current_job.owner_id
      and id = current_job.item_id
    returning version into expected_version_value;

    claimed_jobs := claimed_jobs || pg_catalog.jsonb_build_array(
      pg_catalog.jsonb_build_object(
        'job_id', current_job.id,
        'lease_token', lease_token_value,
        'owner_id', current_job.owner_id,
        'target_revision', current_job.target_revision,
        'expected_version', expected_version_value,
        'budget_reserved', true,
        'item', private.library_item_json(
          current_job.owner_id,
          current_job.item_id,
          true
        )
      )
    );
  end loop;

  return pg_catalog.jsonb_build_object('jobs', claimed_jobs);
end;
$$;

create function public.library_complete_metadata_job(
  p_job_id uuid,
  p_lease_token uuid,
  p_expected_version integer,
  p_result jsonb,
  p_prepared jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  initial_owner_id uuid;
  initial_item_id uuid;
  current_job public.processing_jobs%rowtype;
  current_item public.items%rowtype;
  current_search public.item_search%rowtype;
  current_classification public.item_classification%rowtype;
  current_controls public.item_category_controls%rowtype;
  profile_state text;
  member_enabled boolean;
  member_approved_at timestamptz;
  new_fetched_title text;
  new_description text;
  new_body_text text;
  new_metadata_state text;
  new_extraction_meta jsonb;
  raw_changed boolean;
  flags_changed boolean;
  new_text_revision integer;
  has_classification_text boolean;
  result_item jsonb;
begin
  if p_job_id is null or p_lease_token is null then
    return pg_catalog.jsonb_build_object('state', 'discarded_stale');
  end if;

  select job.owner_id, job.item_id
  into initial_owner_id, initial_item_id
  from public.processing_jobs as job
  where job.id = p_job_id
    and job.kind = 'metadata';

  if not found then
    return pg_catalog.jsonb_build_object('state', 'discarded_stale');
  end if;

  select profile.state
  into profile_state
  from public.profiles as profile
  where profile.id = initial_owner_id
  for share;

  select member.enabled, member.approved_at
  into member_enabled, member_approved_at
  from public.beta_members as member
  where member.owner_id = initial_owner_id
  for share;

  select item.*
  into current_item
  from public.items as item
  where item.owner_id = initial_owner_id
    and item.id = initial_item_id
  for update;

  if found then
    select search_record.*
    into current_search
    from public.item_search as search_record
    where search_record.owner_id = initial_owner_id
      and search_record.item_id = initial_item_id
    for update;

    select classification.*
    into current_classification
    from public.item_classification as classification
    where classification.owner_id = initial_owner_id
      and classification.item_id = initial_item_id
    for update;

    select controls.*
    into current_controls
    from public.item_category_controls as controls
    where controls.owner_id = initial_owner_id
      and controls.item_id = initial_item_id
    for update;
  end if;

  select job.*
  into current_job
  from public.processing_jobs as job
  where job.id = p_job_id
  for update;

  if not found
    or current_job.kind <> 'metadata'
    or current_job.owner_id <> initial_owner_id
    or current_job.item_id <> initial_item_id
    or current_job.state <> 'running'
    or current_job.lease_token <> p_lease_token
    or current_job.lease_until <= pg_catalog.clock_timestamp() then
    return pg_catalog.jsonb_build_object('state', 'discarded_stale');
  end if;

  if current_item.id is null
    or current_item.deleted_at is not null
    or profile_state is distinct from 'active'
    or member_enabled is distinct from true
    or member_approved_at is null
    or current_search.item_id is null
    or current_classification.item_id is null
    or current_controls.item_id is null then
    update public.processing_jobs
    set state = 'cancelled',
        lease_until = null,
        lease_token = null,
        last_error_code = null,
        updated_at = pg_catalog.clock_timestamp()
    where id = current_job.id;

    return pg_catalog.jsonb_build_object('state', 'discarded_stale');
  end if;

  if current_item.text_revision <> current_job.target_revision
    or current_search.text_revision <> current_job.target_revision
    or current_classification.target_revision <> current_job.target_revision then
    update public.processing_jobs
    set state = 'cancelled',
        lease_until = null,
        lease_token = null,
        last_error_code = null,
        updated_at = pg_catalog.clock_timestamp()
    where id = current_job.id;

    if current_item.metadata_state in ('queued', 'running')
      and private.metadata_url_allowed(current_item.original_url)
      and current_search.text_revision = current_item.text_revision
      and current_classification.target_revision = current_item.text_revision then
      if current_item.metadata_state <> 'queued' then
        update public.items
        set metadata_state = 'queued',
            version = version + 1,
            updated_at = pg_catalog.clock_timestamp()
        where owner_id = current_item.owner_id
          and id = current_item.id;
      end if;
      perform private.ensure_current_metadata_job(
        current_item.owner_id,
        current_item.id,
        current_item.text_revision
      );
    end if;

    return pg_catalog.jsonb_build_object('state', 'discarded_stale');
  end if;

  if not private.valid_metadata_result(p_result) then
    raise exception using errcode = 'P0001', message = 'INVALID_METADATA_RESULT';
  end if;

  if p_expected_version is null or p_expected_version < 1 then
    raise exception using errcode = 'P0001', message = 'INVALID_EXPECTED_VERSION';
  end if;

  if p_expected_version <> current_item.version then
    result_item := private.library_item_json(
      current_job.owner_id,
      current_job.item_id,
      true
    );
    if result_item is null then
      return pg_catalog.jsonb_build_object('state', 'discarded_stale');
    end if;

    return pg_catalog.jsonb_build_object(
      'state', 'version_conflict',
      'item', result_item
    );
  end if;

  new_fetched_title := coalesce(p_result ->> 'fetched_title', current_item.fetched_title);
  new_description := coalesce(p_result ->> 'description', current_item.description);
  new_body_text := coalesce(p_result ->> 'body_text', current_item.body_text);

  if not private.valid_metadata_prepared(p_prepared, current_item.version)
    or p_prepared #>> '{normalized_fields,user_title}'
      <> current_search.normalized_fields ->> 'user_title'
    or p_prepared #>> '{normalized_fields,note}'
      <> current_search.normalized_fields ->> 'note'
    or p_prepared #>> '{normalized_fields,ocr}'
      <> current_search.normalized_fields ->> 'ocr'
    or p_prepared #>> '{normalized_fields,shared}'
      <> current_search.normalized_fields ->> 'shared'
    or p_prepared #>> '{normalized_fields,categories}'
      <> current_search.normalized_fields ->> 'categories'
    or p_prepared #>> '{normalized_fields,url}'
      <> current_search.normalized_fields ->> 'url'
    or p_prepared #>> '{normalized_fields,fetched_title}' <>
      pg_catalog.btrim(
        pg_catalog.regexp_replace(
          pg_catalog.lower(normalize(coalesce(new_fetched_title, ''), NFKC)),
          '[[:space:]]+',
          ' ',
          'g'
        )
      )
    or p_prepared #>> '{normalized_fields,description}' <>
      pg_catalog.btrim(
        pg_catalog.regexp_replace(
          pg_catalog.lower(normalize(coalesce(new_description, ''), NFKC)),
          '[[:space:]]+',
          ' ',
          'g'
        )
      )
    or p_prepared #>> '{normalized_fields,body}' <>
      pg_catalog.btrim(
        pg_catalog.regexp_replace(
          pg_catalog.lower(normalize(coalesce(new_body_text, ''), NFKC)),
          '[[:space:]]+',
          ' ',
          'g'
        )
      )
    or p_prepared #> '{alias_concepts,user_title}'
      <> current_search.alias_concepts -> 'user_title'
    or p_prepared #> '{alias_concepts,note}'
      <> current_search.alias_concepts -> 'note'
    or p_prepared #> '{alias_concepts,ocr}'
      <> current_search.alias_concepts -> 'ocr'
    or p_prepared #> '{alias_concepts,shared}'
      <> current_search.alias_concepts -> 'shared' then
    raise exception using errcode = 'P0001', message = 'INVALID_PREPARED';
  end if;

  new_metadata_state := p_result ->> 'metadata_state';
  new_extraction_meta := p_result -> 'extraction_meta';
  raw_changed := new_fetched_title is distinct from current_item.fetched_title
    or new_description is distinct from current_item.description
    or new_body_text is distinct from current_item.body_text;
  flags_changed := new_metadata_state is distinct from current_item.metadata_state
    or new_extraction_meta is distinct from current_item.extraction_meta;
  new_text_revision := current_item.text_revision + case when raw_changed then 1 else 0 end;

  if raw_changed then
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

    update public.processing_jobs
    set state = 'cancelled',
        lease_until = null,
        lease_token = null,
        updated_at = pg_catalog.clock_timestamp()
    where owner_id = current_job.owner_id
      and item_id = current_job.item_id
      and id <> current_job.id
      and kind in ('metadata', 'classify')
      and state in ('queued', 'running', 'retry');

    update public.item_search
    set text_revision = new_text_revision,
        normalized_fields = p_prepared -> 'normalized_fields',
        alias_concepts = p_prepared -> 'alias_concepts',
        cue_version = 'cues-v1.0.0',
        cue_state = p_prepared ->> 'cue_state',
        cue_flags = p_prepared -> 'cue_flags',
        updated_at = pg_catalog.clock_timestamp()
    where owner_id = current_job.owner_id
      and item_id = current_job.item_id;

    update public.item_classification
    set target_revision = new_text_revision,
        state = case
          when current_controls.manual_override then 'manual'
          when has_classification_text then 'pending'
          else 'unclassified'
        end,
        reasons = '[]'::jsonb,
        updated_at = pg_catalog.clock_timestamp()
    where owner_id = current_job.owner_id
      and item_id = current_job.item_id;

    if not current_controls.manual_override and has_classification_text then
      insert into public.processing_jobs (
        owner_id,
        item_id,
        kind,
        target_revision,
        state
      )
      values (
        current_job.owner_id,
        current_job.item_id,
        'classify',
        new_text_revision,
        'queued'
      )
      on conflict (owner_id, item_id, kind, target_revision)
        where state in ('queued', 'running', 'retry')
      do nothing;
    end if;
  end if;

  update public.items
  set fetched_title = new_fetched_title,
      description = new_description,
      body_text = new_body_text,
      metadata_state = new_metadata_state,
      extraction_meta = new_extraction_meta,
      text_revision = new_text_revision,
      version = version + case when raw_changed or flags_changed then 1 else 0 end,
      updated_at = case
        when raw_changed or flags_changed then pg_catalog.clock_timestamp()
        else updated_at
      end
  where owner_id = current_job.owner_id
    and id = current_job.item_id;

  update public.processing_jobs
  set state = 'succeeded',
      lease_until = null,
      lease_token = null,
      last_error_code = null,
      updated_at = pg_catalog.clock_timestamp()
  where id = current_job.id
    and state = 'running'
    and lease_token = p_lease_token
    and lease_until > pg_catalog.clock_timestamp();

  if not found then
    raise exception using errcode = '40001', message = 'METADATA_COMMIT_CONFLICT';
  end if;

  result_item := private.library_item_json(
    current_job.owner_id,
    current_job.item_id,
    true
  );
  if result_item is null then
    raise exception using errcode = '40001', message = 'METADATA_COMMIT_CONFLICT';
  end if;

  return pg_catalog.jsonb_build_object(
    'state', 'succeeded',
    'item', result_item
  );
end;
$$;

create function public.library_fail_metadata_job(
  p_job_id uuid,
  p_lease_token uuid,
  p_error_code text,
  p_retry_after_seconds integer default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  initial_owner_id uuid;
  initial_item_id uuid;
  failed_job public.processing_jobs%rowtype;
  current_item public.items%rowtype;
  profile_state text;
  member_enabled boolean;
  member_approved_at timestamptz;
  should_retry boolean;
  next_state text;
  item_state text;
  base_delay_seconds integer;
  retry_delay_seconds integer;
  retry_at timestamptz;
  new_extraction_meta jsonb;
  flags_changed boolean;
begin
  if p_job_id is null or p_lease_token is null then
    return pg_catalog.jsonb_build_object('state', 'discarded_stale');
  end if;

  if p_error_code is null
    or p_error_code not in (
      'METADATA_TIMEOUT',
      'NETWORK_ERROR',
      'RATE_LIMITED',
      'ACCESS_DENIED',
      'INVALID_CONTENT',
      'RESPONSE_TOO_LARGE',
      'METADATA_BUDGET_EXHAUSTED',
      'INVALID_RESULT',
      'RETRY_AFTER_EXCEEDED',
      'INTERNAL_ERROR'
    )
    or p_retry_after_seconds < 0 then
    raise exception using errcode = 'P0001', message = 'INVALID_FAILURE';
  end if;

  select job.owner_id, job.item_id
  into initial_owner_id, initial_item_id
  from public.processing_jobs as job
  where job.id = p_job_id
    and job.kind = 'metadata';

  if not found then
    return pg_catalog.jsonb_build_object('state', 'discarded_stale');
  end if;

  select profile.state
  into profile_state
  from public.profiles as profile
  where profile.id = initial_owner_id
  for share;

  select member.enabled, member.approved_at
  into member_enabled, member_approved_at
  from public.beta_members as member
  where member.owner_id = initial_owner_id
  for share;

  select item.*
  into current_item
  from public.items as item
  where item.owner_id = initial_owner_id
    and item.id = initial_item_id
  for update;

  select job.*
  into failed_job
  from public.processing_jobs as job
  where job.id = p_job_id
  for update;

  if not found
    or failed_job.kind <> 'metadata'
    or failed_job.state <> 'running'
    or failed_job.lease_token <> p_lease_token
    or failed_job.lease_until <= pg_catalog.clock_timestamp() then
    return pg_catalog.jsonb_build_object('state', 'discarded_stale');
  end if;

  if current_item.id is null
    or current_item.deleted_at is not null
    or profile_state is distinct from 'active'
    or member_enabled is distinct from true
    or member_approved_at is null then
    update public.processing_jobs
    set state = 'cancelled',
        lease_until = null,
        lease_token = null,
        last_error_code = null,
        updated_at = pg_catalog.clock_timestamp()
    where id = failed_job.id;
    return pg_catalog.jsonb_build_object('state', 'discarded_stale');
  end if;

  if current_item.text_revision <> failed_job.target_revision then
    update public.processing_jobs
    set state = 'cancelled',
        lease_until = null,
        lease_token = null,
        last_error_code = null,
        updated_at = pg_catalog.clock_timestamp()
    where id = failed_job.id;

    if current_item.metadata_state in ('queued', 'running')
      and private.metadata_url_allowed(current_item.original_url) then
      if current_item.metadata_state <> 'queued' then
        update public.items
        set metadata_state = 'queued',
            version = version + 1,
            updated_at = pg_catalog.clock_timestamp()
        where owner_id = current_item.owner_id
          and id = current_item.id;
      end if;
      perform private.ensure_current_metadata_job(
        current_item.owner_id,
        current_item.id,
        current_item.text_revision
      );
    end if;

    return pg_catalog.jsonb_build_object('state', 'discarded_stale');
  end if;

  should_retry := private.metadata_error_retryable(p_error_code)
    and failed_job.attempts < 4;
  next_state := case when should_retry then 'retry' else 'failed' end;
  item_state := case
    when should_retry then 'queued'
    when current_item.fetched_title is not null
      or current_item.description is not null
      or current_item.body_text is not null then 'partial'
    else 'failed'
  end;
  base_delay_seconds := case failed_job.attempts
    when 1 then 60
    when 2 then 300
    else 1800
  end;
  retry_delay_seconds := greatest(base_delay_seconds, coalesce(p_retry_after_seconds, 0));
  retry_at := case
    when should_retry
      then pg_catalog.clock_timestamp() + pg_catalog.make_interval(secs => retry_delay_seconds)
    else failed_job.next_run_at
  end;
  new_extraction_meta := current_item.extraction_meta || pg_catalog.jsonb_build_object(
    'last_checked_at', pg_catalog.clock_timestamp(),
    'error_code', p_error_code
  );
  flags_changed := current_item.metadata_state is distinct from item_state
    or current_item.extraction_meta is distinct from new_extraction_meta;

  update public.processing_jobs
  set state = next_state,
      next_run_at = retry_at,
      lease_until = null,
      lease_token = null,
      last_error_code = p_error_code,
      updated_at = pg_catalog.clock_timestamp()
  where id = failed_job.id
    and state = 'running'
    and lease_token = p_lease_token
    and lease_until > pg_catalog.clock_timestamp();

  if not found then
    return pg_catalog.jsonb_build_object('state', 'discarded_stale');
  end if;

  update public.items
  set metadata_state = item_state,
      extraction_meta = new_extraction_meta,
      version = version + case when flags_changed then 1 else 0 end,
      updated_at = case when flags_changed then pg_catalog.clock_timestamp() else updated_at end
  where owner_id = failed_job.owner_id
    and id = failed_job.item_id;

  if should_retry then
    return pg_catalog.jsonb_build_object(
      'state', 'retry',
      'next_run_at', retry_at
    );
  end if;

  return pg_catalog.jsonb_build_object('state', 'failed');
end;
$$;

create function public.library_retry_metadata(
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
  profile_state text;
  member_enabled boolean;
  member_approved_at timestamptz;
  method_path_value text;
  request_hash_value text;
  previous_request public.api_requests%rowtype;
  current_item public.items%rowtype;
  expected_version_value integer;
  current_job_id uuid;
  current_job_state text;
  manual_window timestamptz;
  manual_operation text;
  manual_count integer;
  new_extraction_meta jsonb;
  flags_changed boolean;
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

  method_path_value := 'POST /items/' || p_item_id::text || '/retry-metadata';
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
    into current_item
    from public.items as item
    where item.owner_id = p_owner_id
      and item.id = (previous_request.response_body ->> 'item_id')::uuid
    for update;

    if not found then
      raise exception using errcode = 'P0001', message = 'ITEM_NOT_FOUND';
    end if;
    if current_item.deleted_at is not null then
      raise exception using errcode = 'P0001', message = 'ITEM_DELETED';
    end if;

    select profile.state
    into profile_state
    from public.profiles as profile
    where profile.id = p_owner_id
    for share;

    select member.enabled, member.approved_at
    into member_enabled, member_approved_at
    from public.beta_members as member
    where member.owner_id = p_owner_id
    for share;

    if profile_state = 'deleting' then
      raise exception using errcode = 'P0001', message = 'ACCOUNT_DELETING';
    end if;
    if profile_state is distinct from 'active'
      or member_enabled is distinct from true
      or member_approved_at is null then
      raise exception using errcode = 'P0001', message = 'BETA_ACCESS_REQUIRED';
    end if;

    if previous_request.response_code = 409 then
      return pg_catalog.jsonb_build_object(
        'http_status', 409,
        'error_code', 'VERSION_CONFLICT'
      );
    end if;

    return pg_catalog.jsonb_build_object(
      'http_status', 202,
      'job_id', previous_request.response_body ->> 'job_id'
    );
  end if;

  if p_body is null
    or pg_catalog.jsonb_typeof(p_body) <> 'object'
    or not private.jsonb_has_exact_keys(p_body, array['expected_version']::text[])
    or pg_catalog.jsonb_typeof(p_body -> 'expected_version') <> 'number'
    or p_body ->> 'expected_version' !~ '^[1-9][0-9]*$'
    or pg_catalog.char_length(p_body ->> 'expected_version') > 10
    or (p_body ->> 'expected_version')::numeric > 2147483647 then
    raise exception using errcode = 'P0001', message = 'INVALID_BODY';
  end if;
  expected_version_value := (p_body ->> 'expected_version')::integer;

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

  if expected_version_value <> current_item.version then
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

  if not private.metadata_url_allowed(current_item.original_url) then
    raise exception using errcode = 'P0001', message = 'METADATA_UNSUPPORTED';
  end if;

  manual_window := pg_catalog.date_trunc(
    'day',
    pg_catalog.clock_timestamp() at time zone 'UTC'
  ) at time zone 'UTC';
  manual_operation := 'metadata_manual:' || p_item_id::text;
  manual_count := null;
  insert into public.api_rate_buckets (
    owner_id,
    operation,
    window_start,
    request_count
  )
  values (p_owner_id, manual_operation, manual_window, 1)
  on conflict (owner_id, operation, window_start)
  do update
  set request_count = public.api_rate_buckets.request_count + 1
  where public.api_rate_buckets.request_count < 3
  returning request_count into manual_count;

  if manual_count is null then
    raise exception using errcode = 'P0001', message = 'RATE_LIMITED';
  end if;

  select job.id, job.state
  into current_job_id, current_job_state
  from public.processing_jobs as job
  where job.owner_id = p_owner_id
    and job.item_id = p_item_id
    and job.kind = 'metadata'
    and job.target_revision = current_item.text_revision
    and job.state in ('queued', 'running', 'retry')
  order by job.created_at, job.id
  limit 1;

  if current_job_id is null then
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
      'metadata',
      current_item.text_revision,
      'queued'
    )
    returning id, state into current_job_id, current_job_state;
  end if;

  new_extraction_meta := current_item.extraction_meta - 'error_code';
  flags_changed := current_item.metadata_state is distinct from
      case when current_job_state = 'running' then 'running' else 'queued' end
    or current_item.extraction_meta is distinct from new_extraction_meta;

  update public.items
  set metadata_state = case when current_job_state = 'running' then 'running' else 'queued' end,
      extraction_meta = new_extraction_meta,
      version = version + case when flags_changed then 1 else 0 end,
      updated_at = case when flags_changed then pg_catalog.clock_timestamp() else updated_at end
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
    202,
    pg_catalog.jsonb_build_object(
      'item_id', p_item_id,
      'job_id', current_job_id,
      'http_status', 202
    )
  );

  return pg_catalog.jsonb_build_object(
    'http_status', 202,
    'job_id', current_job_id
  );
end;
$$;

revoke all on function private.metadata_url_allowed(text)
from public, anon, authenticated, service_role;
revoke all on function private.valid_metadata_result(jsonb)
from public, anon, authenticated, service_role;
revoke all on function private.valid_metadata_prepared(jsonb, integer)
from public, anon, authenticated, service_role;
revoke all on function private.metadata_error_retryable(text)
from public, anon, authenticated, service_role;
revoke all on function private.ensure_current_metadata_job(uuid, uuid, integer)
from public, anon, authenticated, service_role;

revoke all on function public.library_claim_metadata_jobs(integer)
from public, anon, authenticated, service_role;
revoke all on function public.library_complete_metadata_job(uuid, uuid, integer, jsonb, jsonb)
from public, anon, authenticated, service_role;
revoke all on function public.library_fail_metadata_job(uuid, uuid, text, integer)
from public, anon, authenticated, service_role;
revoke all on function public.library_retry_metadata(uuid, uuid, uuid, jsonb)
from public, anon, authenticated, service_role;

grant execute on function public.library_claim_metadata_jobs(integer) to service_role;
grant execute on function public.library_complete_metadata_job(uuid, uuid, integer, jsonb, jsonb)
to service_role;
grant execute on function public.library_fail_metadata_job(uuid, uuid, text, integer)
to service_role;
grant execute on function public.library_retry_metadata(uuid, uuid, uuid, jsonb)
to service_role;
