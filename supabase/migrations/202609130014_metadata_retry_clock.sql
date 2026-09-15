create or replace function public.library_fail_metadata_job(
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
  transition_at timestamptz;
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

  transition_at := pg_catalog.clock_timestamp();
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
      then transition_at + pg_catalog.make_interval(secs => retry_delay_seconds)
    else failed_job.next_run_at
  end;
  new_extraction_meta := current_item.extraction_meta || pg_catalog.jsonb_build_object(
    'last_checked_at', transition_at,
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
      updated_at = transition_at
  where id = failed_job.id
    and state = 'running'
    and lease_token = p_lease_token
    and lease_until > transition_at;

  if not found then
    return pg_catalog.jsonb_build_object('state', 'discarded_stale');
  end if;

  update public.items
  set metadata_state = item_state,
      extraction_meta = new_extraction_meta,
      version = version + case when flags_changed then 1 else 0 end,
      updated_at = case when flags_changed then transition_at else updated_at end
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
