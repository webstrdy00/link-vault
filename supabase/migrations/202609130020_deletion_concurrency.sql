create or replace function private.claim_request_id(
  p_owner_id uuid,
  p_request_id uuid,
  p_method_path text,
  p_request_hash text,
  p_claimed_at timestamptz,
  p_retain_until timestamptz
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  existing_claim private.request_id_claims%rowtype;
begin
  if p_owner_id is null or p_request_id is null then
    raise exception using errcode = 'P0001', message = 'INVALID_REQUEST_ID';
  end if;
  if p_method_path is null
    or pg_catalog.char_length(p_method_path) not between 1 and 300 then
    raise exception using errcode = 'P0001', message = 'INVALID_METHOD_PATH';
  end if;
  if p_request_hash is null or p_request_hash !~ '^[0-9a-f]{64}$' then
    raise exception using errcode = 'P0001', message = 'INVALID_REQUEST_HASH';
  end if;
  if p_claimed_at is null
    or p_retain_until is null
    or p_retain_until < p_claimed_at then
    raise exception using errcode = 'P0001', message = 'INVALID_REQUEST_RETENTION';
  end if;

  insert into private.request_id_claims (
    owner_id,
    request_id,
    method_path,
    request_hash,
    claimed_at,
    retain_until
  ) values (
    p_owner_id,
    p_request_id,
    p_method_path,
    p_request_hash,
    p_claimed_at,
    p_retain_until
  )
  on conflict (owner_id, request_id) do nothing;

  if found then
    return;
  end if;

  select claim.*
  into existing_claim
  from private.request_id_claims as claim
  where claim.owner_id = p_owner_id
    and claim.request_id = p_request_id
  for key share;

  if not found then
    raise exception using errcode = '40001', message = 'REQUEST_CLAIM_CONFLICT';
  end if;
  if existing_claim.method_path <> p_method_path
    or existing_claim.request_hash <> p_request_hash then
    raise exception using errcode = 'P0001', message = 'IDEMPOTENCY_MISMATCH';
  end if;
end;
$$;

create or replace function public.library_accept_account_deletion(
  p_owner_id uuid,
  p_request_id uuid,
  p_challenge_id uuid,
  p_request_hash text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  existing_job public.account_deletion_jobs%rowtype;
  existing_claim private.request_id_claims%rowtype;
  challenge private.auth_challenges%rowtype;
  profile_state text;
  active_items_to_delete integer;
  usage_item_count integer;
  deletion_time timestamptz := pg_catalog.clock_timestamp();
begin
  if p_owner_id is null then
    raise exception using errcode = 'P0001', message = 'UNAUTHENTICATED';
  end if;
  if p_request_id is null then
    raise exception using errcode = 'P0001', message = 'INVALID_REQUEST_ID';
  end if;
  if p_challenge_id is null then
    raise exception using errcode = 'P0001', message = 'DELETE_CHALLENGE_INVALID';
  end if;
  if p_request_hash is null or p_request_hash !~ '^[0-9a-f]{64}$' then
    raise exception using errcode = 'P0001', message = 'INVALID_REQUEST_HASH';
  end if;

  select stored.*
  into challenge
  from private.auth_challenges as stored
  where stored.owner_id = p_owner_id
    and stored.id = p_challenge_id
  for update;

  if not found
    or challenge.purpose <> 'account_delete' then
    raise exception using errcode = 'P0001', message = 'DELETE_CHALLENGE_INVALID';
  end if;

  select job.*
  into existing_job
  from public.account_deletion_jobs as job
  where job.owner_id = p_owner_id
    and job.request_id = p_request_id
  for update;

  if found then
    if existing_job.request_hash <> p_request_hash then
      raise exception using errcode = 'P0001', message = 'IDEMPOTENCY_MISMATCH';
    end if;

    select claim.*
    into existing_claim
    from private.request_id_claims as claim
    where claim.owner_id = p_owner_id
      and claim.request_id = p_request_id;

    if not found then
      raise exception using errcode = 'P0001', message = 'REQUEST_ID_CLAIM_MISSING';
    end if;
    if existing_claim.method_path <> 'POST /account/delete'
      or existing_claim.request_hash <> p_request_hash then
      raise exception using errcode = 'P0001', message = 'IDEMPOTENCY_MISMATCH';
    end if;

    return pg_catalog.jsonb_build_object('http_status', 202, 'state', 'deleting');
  end if;

  if challenge.used_at is not null then
    raise exception using errcode = 'P0001', message = 'DELETE_CHALLENGE_USED';
  end if;
  if challenge.expires_at <= pg_catalog.clock_timestamp() then
    raise exception using errcode = 'P0001', message = 'DELETE_CHALLENGE_EXPIRED';
  end if;

  perform 1
  from auth.users as auth_user
  where auth_user.id = p_owner_id
  for no key update;

  if not found then
    raise exception using errcode = 'P0001', message = 'ACCOUNT_NOT_FOUND';
  end if;

  select profile.state
  into profile_state
  from public.profiles as profile
  where profile.id = p_owner_id
  for update;

  if found and profile_state = 'deleting' then
    raise exception using errcode = 'P0001', message = 'ACCOUNT_DELETING';
  end if;

  perform 1
  from public.beta_members as member
  where member.owner_id = p_owner_id
  for share;

  select usage.active_item_count
  into usage_item_count
  from public.library_usage as usage
  where usage.owner_id = p_owner_id
  for update;

  perform private.claim_request_id(
    p_owner_id,
    p_request_id,
    'POST /account/delete',
    p_request_hash,
    deletion_time,
    deletion_time + interval '30 days'
  );

  select job.*
  into existing_job
  from public.account_deletion_jobs as job
  where job.owner_id = p_owner_id
  for update;

  if found then
    if existing_job.request_id = p_request_id
      and existing_job.request_hash = p_request_hash then
      return pg_catalog.jsonb_build_object('http_status', 202, 'state', 'deleting');
    end if;
    raise exception using errcode = 'P0001', message = 'ACCOUNT_DELETING';
  end if;

  insert into public.profiles (id, state, deletion_requested_at)
  values (p_owner_id, 'deleting', deletion_time)
  on conflict (id) do update
  set state = 'deleting',
      deletion_requested_at = coalesce(
        public.profiles.deletion_requested_at,
        excluded.deletion_requested_at
      );

  update public.beta_members
  set enabled = false
  where owner_id = p_owner_id;

  perform 1
  from public.items as item
  where item.owner_id = p_owner_id
  for update;

  select count(*)::integer
  into active_items_to_delete
  from public.items as item
  where item.owner_id = p_owner_id
    and item.deleted_at is null;

  if active_items_to_delete > 0 and usage_item_count is null then
    raise exception using errcode = 'P0001', message = 'ITEM_USAGE_CORRUPT';
  end if;
  if usage_item_count is not null and usage_item_count < active_items_to_delete then
    raise exception using errcode = 'P0001', message = 'ITEM_USAGE_CORRUPT';
  end if;

  insert into private.item_deletion_tombstones (owner_id, item_id, deleted_at)
  select item.owner_id, item.id, deletion_time
  from public.items as item
  where item.owner_id = p_owner_id
    and item.deleted_at is null
  on conflict (owner_id, item_id) do nothing;

  insert into private.deletion_ledger as existing_ledger (
    kind,
    owner_id,
    item_id,
    request_id,
    requested_at
  )
  select
    'item',
    item.owner_id,
    item.id,
    p_request_id,
    deletion_time
  from public.items as item
  where item.owner_id = p_owner_id
    and item.deleted_at is null
  on conflict (owner_id, item_id) where kind = 'item' do update
  set request_id = coalesce(
        existing_ledger.request_id,
        excluded.request_id
      ),
      requested_at = least(
        existing_ledger.requested_at,
        excluded.requested_at
      );

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
      deleted_at = deletion_time,
      version = version + 1,
      updated_at = deletion_time
  where owner_id = p_owner_id
    and deleted_at is null;

  if usage_item_count is not null then
    update public.library_usage
    set active_item_count = active_item_count - active_items_to_delete,
        updated_at = deletion_time
    where owner_id = p_owner_id;
  end if;

  update public.processing_jobs
  set state = 'cancelled',
      lease_until = null,
      lease_token = null,
      last_error_code = 'ACCOUNT_DELETING',
      updated_at = deletion_time
  where owner_id = p_owner_id
    and state in ('queued', 'running', 'retry');

  delete from public.item_categories
  where owner_id = p_owner_id;
  delete from public.item_category_controls
  where owner_id = p_owner_id;
  delete from public.item_classification
  where owner_id = p_owner_id;
  delete from public.item_search
  where owner_id = p_owner_id;

  update public.assets
  set state = 'deleting',
      ocr_state = 'not_requested',
      ocr_text = null,
      ocr_truncated = false,
      cleanup_reason = 'account_delete',
      cleanup_next_run_at = deletion_time,
      cleanup_lease_until = null,
      cleanup_lease_token = null,
      cleanup_error_code = null,
      deleted_at = coalesce(deleted_at, deletion_time),
      updated_at = deletion_time
  where owner_id = p_owner_id;

  update private.auth_challenges
  set used_at = deletion_time
  where id = p_challenge_id;

  insert into public.account_deletion_jobs (
    owner_id,
    request_id,
    request_hash,
    state,
    next_run_at,
    requested_at
  ) values (
    p_owner_id,
    p_request_id,
    p_request_hash,
    'queued',
    deletion_time,
    deletion_time
  );

  insert into private.deletion_ledger (
    kind,
    owner_id,
    request_id,
    requested_at
  ) values (
    'account',
    p_owner_id,
    p_request_id,
    deletion_time
  )
  on conflict (owner_id) where kind = 'account' do nothing;

  return pg_catalog.jsonb_build_object('http_status', 202, 'state', 'deleting');
end;
$$;

create or replace function public.library_run_retention(p_now timestamptz default now())
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  verified_now timestamptz := pg_catalog.clock_timestamp();
  retention_cutoff timestamptz;
  api_requests_deleted integer;
  rate_buckets_deleted integer;
  challenges_deleted integer;
  tombstones_deleted integer;
  cancelled_jobs_deleted integer;
  ledger_deleted integer := 0;
  account_jobs_deleted integer := 0;
begin
  if p_now is null or p_now > verified_now then
    raise exception using errcode = 'P0001', message = 'INVALID_RETENTION_TIME';
  end if;

  retention_cutoff := p_now - interval '30 days';

  delete from public.api_requests
  where created_at < p_now - interval '7 days';
  get diagnostics api_requests_deleted = row_count;

  delete from public.api_rate_buckets
  where window_start < p_now - interval '1 day';
  get diagnostics rate_buckets_deleted = row_count;

  delete from private.auth_challenges
  where expires_at <= p_now
    and created_at < p_now - interval '7 days';
  get diagnostics challenges_deleted = row_count;

  delete from private.item_deletion_tombstones as tombstone
  where tombstone.deleted_at < retention_cutoff
    and exists (
      select 1
      from private.deletion_ledger as ledger
      join private.deletion_ledger_export_events as snapshot
        on snapshot.sequence = ledger.sequence
       and snapshot.event_id = ledger.event_id
      join private.deletion_ledger_exports as ledger_export
        on ledger_export.export_id = snapshot.export_id
      where ledger.kind = 'item'
        and ledger.owner_id = tombstone.owner_id
        and ledger.item_id = tombstone.item_id
        and ledger_export.acknowledged_at is not null
    );
  get diagnostics tombstones_deleted = row_count;

  delete from public.processing_jobs as job
  where job.state = 'cancelled'
    and job.updated_at < retention_cutoff
    and exists (
      select 1
      from private.deletion_ledger as ledger
      join private.deletion_ledger_export_events as snapshot
        on snapshot.sequence = ledger.sequence
       and snapshot.event_id = ledger.event_id
      join private.deletion_ledger_exports as ledger_export
        on ledger_export.export_id = snapshot.export_id
      where ledger.kind = 'item'
        and ledger.owner_id = job.owner_id
        and ledger.item_id = job.item_id
        and ledger_export.acknowledged_at is not null
    );
  get diagnostics cancelled_jobs_deleted = row_count;

  delete from public.account_deletion_jobs as job
  where job.state = 'complete'
    and job.completed_at < retention_cutoff
    and exists (
      select 1
      from private.deletion_ledger as ledger
      join private.deletion_ledger_export_events as snapshot
        on snapshot.sequence = ledger.sequence
       and snapshot.event_id = ledger.event_id
      join private.deletion_ledger_exports as ledger_export
        on ledger_export.export_id = snapshot.export_id
      where ledger.kind = 'account'
        and ledger.owner_id = job.owner_id
        and ledger.request_id = job.request_id
        and ledger_export.acknowledged_at is not null
    );
  get diagnostics account_jobs_deleted = row_count;

  delete from private.deletion_ledger as retained_ledger
  where retained_ledger.requested_at < retention_cutoff
    and exists (
      select 1
      from private.deletion_ledger_export_events as snapshot
      join private.deletion_ledger_exports as ledger_export
        on ledger_export.export_id = snapshot.export_id
      where snapshot.sequence = retained_ledger.sequence
        and snapshot.event_id = retained_ledger.event_id
        and ledger_export.acknowledged_at is not null
    )
    and (
      (
        retained_ledger.kind = 'item'
        and not exists (
          select 1
          from private.item_deletion_tombstones as tombstone
          where tombstone.owner_id = retained_ledger.owner_id
            and tombstone.item_id = retained_ledger.item_id
        )
      )
      or (
        retained_ledger.kind = 'account'
        and not exists (
          select 1
          from public.account_deletion_jobs as job
          where job.owner_id = retained_ledger.owner_id
            and job.request_id = retained_ledger.request_id
        )
      )
    );
  get diagnostics ledger_deleted = row_count;

  if ledger_deleted > 0 then
    update private.deletion_ledger_identity
    set coverage_origin = greatest(coverage_origin, retention_cutoff)
    where singleton;
  end if;

  delete from private.asset_cleanup_receipts
  where completed_at < p_now - interval '30 days';

  delete from private.deletion_ledger_exports
  where created_at < retention_cutoff;

  with candidates as materialized (
    select claim.owner_id, claim.request_id
    from private.request_id_claims as claim
    where claim.retain_until <= p_now
      and not exists (
        select 1
        from public.api_requests as request
        where request.owner_id = claim.owner_id
          and request.request_id = claim.request_id
      )
      and not exists (
        select 1
        from private.auth_challenges as challenge
        where challenge.owner_id = claim.owner_id
          and challenge.request_id = claim.request_id
      )
      and not exists (
        select 1
        from public.account_deletion_jobs as job
        where job.owner_id = claim.owner_id
          and job.request_id = claim.request_id
      )
    order by claim.retain_until, claim.owner_id, claim.request_id
    for update of claim skip locked
  )
  delete from private.request_id_claims as claim
  using candidates
  where claim.owner_id = candidates.owner_id
    and claim.request_id = candidates.request_id;

  return pg_catalog.jsonb_build_object(
    'http_status', 200,
    'deleted_api_requests', api_requests_deleted,
    'deleted_rate_buckets', rate_buckets_deleted,
    'deleted_challenges', challenges_deleted,
    'deleted_item_tombstones', tombstones_deleted,
    'deleted_cancelled_jobs', cancelled_jobs_deleted,
    'deleted_account_jobs', account_jobs_deleted,
    'deleted_ledger_events', ledger_deleted
  );
end;
$$;
