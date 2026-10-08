create table private.auth_challenges (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users (id) on delete cascade,
  request_id uuid not null,
  nonce_hash character(64) not null,
  purpose text not null,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  used_at timestamptz,
  constraint auth_challenges_owner_request_key unique (owner_id, request_id),
  constraint auth_challenges_nonce_hash_check check (nonce_hash ~ '^[0-9a-f]{64}$'),
  constraint auth_challenges_purpose_check check (purpose = 'account_delete'),
  constraint auth_challenges_expiry_check check (expires_at > created_at),
  constraint auth_challenges_used_check check (used_at is null or used_at >= created_at)
);

create index auth_challenges_expiry_idx
on private.auth_challenges (expires_at);

create table public.account_deletion_jobs (
  owner_id uuid primary key,
  request_id uuid not null,
  request_hash character(64) not null,
  state text not null default 'queued',
  attempts integer not null default 0,
  next_run_at timestamptz not null default now(),
  lease_until timestamptz,
  lease_token uuid,
  completed_lease_token uuid,
  last_error_code text,
  requested_at timestamptz not null default now(),
  business_purged_at timestamptz,
  completed_at timestamptz,
  constraint account_deletion_jobs_request_hash_check
    check (request_hash ~ '^[0-9a-f]{64}$'),
  constraint account_deletion_jobs_owner_request_key unique (owner_id, request_id),
  constraint account_deletion_jobs_state_check
    check (state in ('queued', 'running', 'retry', 'complete')),
  constraint account_deletion_jobs_attempts_check check (attempts >= 0),
  constraint account_deletion_jobs_error_check check (
    last_error_code is null
    or last_error_code in (
      'ACCOUNT_DELETION_INVALID_JOB',
      'ACCOUNT_DELETION_INTERNAL_ERROR',
      'ACCOUNT_STORAGE_OWNERSHIP_CONFLICT',
      'ACCOUNT_STORAGE_DELETE_FAILED',
      'ACCOUNT_STORAGE_OBJECTS_REMAIN',
      'ACCOUNT_BUSINESS_PURGE_FAILED',
      'ACCOUNT_AUTH_DELETE_FAILED',
      'ACCOUNT_FINALIZE_FAILED',
      'ACCOUNT_PREPARE_FAILED'
    )
  ),
  constraint account_deletion_jobs_lease_check check (
    (
      state = 'running'
      and lease_until is not null
      and lease_token is not null
      and completed_lease_token is null
      and completed_at is null
    )
    or (
      state in ('queued', 'retry')
      and lease_until is null
      and lease_token is null
      and completed_lease_token is null
      and completed_at is null
    )
    or (
      state = 'complete'
      and lease_until is null
      and lease_token is null
      and completed_lease_token is not null
      and completed_at is not null
      and business_purged_at is not null
    )
  ),
  constraint account_deletion_jobs_completion_order_check check (
    completed_at is null
    or (
      business_purged_at is not null
      and completed_at >= business_purged_at
    )
  )
);

create index account_deletion_jobs_ready_idx
on public.account_deletion_jobs (state, next_run_at);

create table private.request_id_claims (
  owner_id uuid not null,
  request_id uuid not null,
  method_path text not null,
  request_hash character(64) not null,
  claimed_at timestamptz not null,
  retain_until timestamptz not null,
  primary key (owner_id, request_id),
  constraint request_id_claims_method_path_check check (
    pg_catalog.char_length(method_path) between 1 and 300
  ),
  constraint request_id_claims_request_hash_check check (
    request_hash ~ '^[0-9a-f]{64}$'
  ),
  constraint request_id_claims_retention_check check (
    retain_until >= claimed_at
  )
);

create index request_id_claims_retention_idx
on private.request_id_claims (retain_until, owner_id, request_id);

create function private.claim_request_id(
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
    and claim.request_id = p_request_id;

  if not found then
    raise exception using errcode = '40001', message = 'REQUEST_CLAIM_CONFLICT';
  end if;
  if existing_claim.method_path <> p_method_path
    or existing_claim.request_hash <> p_request_hash then
    raise exception using errcode = 'P0001', message = 'IDEMPOTENCY_MISMATCH';
  end if;
end;
$$;

create function private.claim_api_request_id()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform private.claim_request_id(
    new.owner_id,
    new.request_id,
    new.method_path,
    new.request_hash,
    new.created_at,
    new.created_at + interval '7 days'
  );
  return new;
end;
$$;

do $$
declare
  request_record record;
  challenge_record record;
  job_record record;
  challenge_request_hash text := pg_catalog.encode(
    extensions.digest(
      pg_catalog.convert_to(
        'POST /account/delete-challenge' || pg_catalog.chr(10) || '{}',
        'UTF8'
      ),
      'sha256'
    ),
    'hex'
  );
begin
  for request_record in
    select request.*
    from public.api_requests as request
    order by request.owner_id, request.request_id
  loop
    perform private.claim_request_id(
      request_record.owner_id,
      request_record.request_id,
      request_record.method_path,
      request_record.request_hash,
      request_record.created_at,
      request_record.created_at + interval '7 days'
    );
  end loop;

  for challenge_record in
    select challenge.*
    from private.auth_challenges as challenge
    order by challenge.owner_id, challenge.request_id
  loop
    perform private.claim_request_id(
      challenge_record.owner_id,
      challenge_record.request_id,
      'POST /account/delete-challenge',
      challenge_request_hash,
      challenge_record.created_at,
      challenge_record.created_at + interval '7 days'
    );
  end loop;

  for job_record in
    select job.*
    from public.account_deletion_jobs as job
    order by job.owner_id, job.request_id
  loop
    perform private.claim_request_id(
      job_record.owner_id,
      job_record.request_id,
      'POST /account/delete',
      job_record.request_hash,
      job_record.requested_at,
      job_record.requested_at + interval '30 days'
    );
  end loop;
exception
  when sqlstate 'P0001' then
    raise exception using
      errcode = 'P0001',
      message = 'REQUEST_ID_CLAIM_BACKFILL_CONFLICT',
      detail = sqlerrm;
end;
$$;

create trigger api_requests_claim_request_id
before insert or update on public.api_requests
for each row execute function private.claim_api_request_id();

create table private.deletion_ledger (
  sequence bigint generated always as identity primary key,
  event_id uuid not null unique default gen_random_uuid(),
  kind text not null,
  owner_id uuid not null,
  item_id uuid,
  request_id uuid,
  requested_at timestamptz not null default now(),
  constraint deletion_ledger_kind_check check (kind in ('item', 'account')),
  constraint deletion_ledger_subject_check check (
    (kind = 'item' and item_id is not null)
    or (kind = 'account' and item_id is null)
  ),
  constraint deletion_ledger_request_check check (
    kind = 'item' or request_id is not null
  )
);

create unique index deletion_ledger_item_key
on private.deletion_ledger (owner_id, item_id)
where kind = 'item';

create unique index deletion_ledger_account_key
on private.deletion_ledger (owner_id)
where kind = 'account';

create index deletion_ledger_requested_at_idx
on private.deletion_ledger (requested_at, sequence);

create table private.deletion_ledger_identity (
  singleton boolean primary key default true,
  database_id uuid not null unique default gen_random_uuid(),
  coverage_origin timestamptz not null default pg_catalog.clock_timestamp(),
  constraint deletion_ledger_identity_singleton_check check (singleton)
);

insert into private.deletion_ledger_identity (singleton)
values (true);

create table private.deletion_ledger_exports (
  export_id uuid primary key default gen_random_uuid(),
  database_id uuid not null,
  through_sequence bigint not null,
  coverage_from timestamptz not null,
  exported_through timestamptz not null,
  created_at timestamptz not null default now(),
  acknowledged_at timestamptz,
  constraint deletion_ledger_exports_database_fkey
    foreign key (database_id)
    references private.deletion_ledger_identity (database_id),
  constraint deletion_ledger_exports_sequence_check check (
    through_sequence >= 0
  ),
  constraint deletion_ledger_exports_coverage_check check (
    exported_through >= coverage_from
  ),
  constraint deletion_ledger_exports_ack_check check (
    acknowledged_at is null or acknowledged_at >= created_at
  )
);

create index deletion_ledger_exports_created_at_idx
on private.deletion_ledger_exports (created_at, export_id);

create table private.deletion_ledger_export_events (
  export_id uuid not null
    references private.deletion_ledger_exports (export_id) on delete cascade,
  sequence bigint not null,
  event_id uuid not null,
  kind text not null,
  owner_id uuid not null,
  item_id uuid,
  request_id uuid,
  requested_at timestamptz not null,
  primary key (export_id, sequence),
  constraint deletion_ledger_export_events_event_key unique (export_id, event_id),
  constraint deletion_ledger_export_events_kind_check check (kind in ('item', 'account')),
  constraint deletion_ledger_export_events_subject_check check (
    (kind = 'item' and item_id is not null)
    or (kind = 'account' and item_id is null)
  ),
  constraint deletion_ledger_export_events_request_check check (
    kind = 'item' or request_id is not null
  )
);

insert into private.deletion_ledger (
  kind,
  owner_id,
  item_id,
  request_id,
  requested_at
)
select
  'item',
  tombstone.owner_id,
  tombstone.item_id,
  (
    select request.request_id
    from public.api_requests as request
    where request.owner_id = tombstone.owner_id
      and request.method_path = 'DELETE /items/' || tombstone.item_id::text
      and request.response_code = 202
    order by request.created_at desc, request.request_id desc
    limit 1
  ),
  tombstone.deleted_at
from private.item_deletion_tombstones as tombstone
on conflict (owner_id, item_id) where kind = 'item' do nothing;

create function private.record_item_deletion_ledger()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  deletion_request_id uuid;
begin
  if exists (
    select 1
    from private.deletion_ledger as ledger
    where ledger.kind = 'item'
      and ledger.owner_id = new.owner_id
      and ledger.item_id = new.item_id
  ) then
    return new;
  end if;

  select request.request_id
  into deletion_request_id
  from public.api_requests as request
  where request.owner_id = new.owner_id
    and request.method_path = 'DELETE /items/' || new.item_id::text
    and request.response_code = 202
  order by request.created_at desc, request.request_id desc
  limit 1;

  insert into private.deletion_ledger (
    kind,
    owner_id,
    item_id,
    request_id,
    requested_at
  ) values (
    'item',
    new.owner_id,
    new.item_id,
    deletion_request_id,
    new.deleted_at
  )
  on conflict (owner_id, item_id) where kind = 'item' do nothing;

  return new;
end;
$$;

create trigger item_deletion_tombstones_record_ledger
after insert on private.item_deletion_tombstones
for each row execute function private.record_item_deletion_ledger();

create function private.record_item_deletion_request()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  item_id_value uuid;
begin
  if new.response_code <> 202
    or new.method_path !~* '^DELETE /items/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
    return new;
  end if;

  item_id_value := pg_catalog.split_part(new.method_path, '/', 3)::uuid;

  update private.deletion_ledger
  set request_id = coalesce(request_id, new.request_id)
  where kind = 'item'
    and owner_id = new.owner_id
    and item_id = item_id_value;

  if not found and exists (
    select 1
    from private.item_deletion_tombstones as tombstone
    where tombstone.owner_id = new.owner_id
      and tombstone.item_id = item_id_value
  ) then
    insert into private.deletion_ledger as existing_ledger (
      kind,
      owner_id,
      item_id,
      request_id,
      requested_at
    )
    select
      'item',
      tombstone.owner_id,
      tombstone.item_id,
      new.request_id,
      tombstone.deleted_at
    from private.item_deletion_tombstones as tombstone
    where tombstone.owner_id = new.owner_id
      and tombstone.item_id = item_id_value
    on conflict (owner_id, item_id) where kind = 'item' do update
    set request_id = coalesce(existing_ledger.request_id, excluded.request_id);
  end if;

  return new;
end;
$$;

create trigger api_requests_record_item_deletion_request
after insert on public.api_requests
for each row execute function private.record_item_deletion_request();

alter table public.account_deletion_jobs enable row level security;

revoke all privileges on table private.auth_challenges
from public, anon, authenticated, service_role;
revoke all privileges on table public.account_deletion_jobs
from public, anon, authenticated, service_role;
revoke all privileges on table private.request_id_claims
from public, anon, authenticated, service_role;
revoke all privileges on table private.deletion_ledger
from public, anon, authenticated, service_role;
revoke all privileges on table private.deletion_ledger_identity
from public, anon, authenticated, service_role;
revoke all privileges on table private.deletion_ledger_exports
from public, anon, authenticated, service_role;
revoke all privileges on table private.deletion_ledger_export_events
from public, anon, authenticated, service_role;
revoke all on function private.claim_request_id(
  uuid, uuid, text, text, timestamptz, timestamptz
)
from public, anon, authenticated, service_role;
revoke all on function private.claim_api_request_id()
from public, anon, authenticated, service_role;
revoke all on function private.record_item_deletion_ledger()
from public, anon, authenticated, service_role;
revoke all on function private.record_item_deletion_request()
from public, anon, authenticated, service_role;

create function public.library_create_delete_challenge(
  p_owner_id uuid,
  p_request_id uuid,
  p_nonce_hash text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  existing_challenge private.auth_challenges%rowtype;
  challenge_id_value uuid;
  created_at_value timestamptz;
  expires_at_value timestamptz;
  request_hash_value text := pg_catalog.encode(
    extensions.digest(
      pg_catalog.convert_to(
        'POST /account/delete-challenge' || pg_catalog.chr(10) || '{}',
        'UTF8'
      ),
      'sha256'
    ),
    'hex'
  );
begin
  if p_owner_id is null then
    raise exception using errcode = 'P0001', message = 'UNAUTHENTICATED';
  end if;
  if p_request_id is null then
    raise exception using errcode = 'P0001', message = 'INVALID_REQUEST_ID';
  end if;
  if p_nonce_hash is null or p_nonce_hash !~ '^[0-9a-f]{64}$' then
    raise exception using errcode = 'P0001', message = 'INVALID_NONCE_HASH';
  end if;

  created_at_value := pg_catalog.clock_timestamp();

  perform private.claim_request_id(
    p_owner_id,
    p_request_id,
    'POST /account/delete-challenge',
    request_hash_value,
    created_at_value,
    created_at_value + interval '7 days'
  );

  select challenge.*
  into existing_challenge
  from private.auth_challenges as challenge
  where challenge.owner_id = p_owner_id
    and challenge.request_id = p_request_id
  for update;

  if found then
    if existing_challenge.purpose <> 'account_delete' then
      raise exception using errcode = 'P0001', message = 'IDEMPOTENCY_MISMATCH';
    end if;

    return pg_catalog.jsonb_build_object(
      'http_status', 201,
      'challenge_id', existing_challenge.id,
      'expires_at', existing_challenge.expires_at
    );
  end if;

  if not exists (
    select 1
    from auth.users as auth_user
    where auth_user.id = p_owner_id
  ) then
    raise exception using errcode = 'P0001', message = 'ACCOUNT_NOT_FOUND';
  end if;

  if exists (
    select 1
    from public.account_deletion_jobs as job
    where job.owner_id = p_owner_id
  ) or exists (
    select 1
    from public.profiles as profile
    where profile.id = p_owner_id
      and profile.state = 'deleting'
  ) then
    raise exception using errcode = 'P0001', message = 'ACCOUNT_DELETING';
  end if;

  challenge_id_value := gen_random_uuid();
  expires_at_value := created_at_value + interval '5 minutes';

  insert into private.auth_challenges (
    id,
    owner_id,
    request_id,
    nonce_hash,
    purpose,
    created_at,
    expires_at
  ) values (
    challenge_id_value,
    p_owner_id,
    p_request_id,
    p_nonce_hash,
    'account_delete',
    created_at_value,
    expires_at_value
  );

  return pg_catalog.jsonb_build_object(
    'http_status', 201,
    'challenge_id', challenge_id_value,
    'expires_at', expires_at_value
  );
end;
$$;

create function public.library_check_delete_challenge_binding(
  p_owner_id uuid,
  p_challenge_id uuid,
  p_nonce_hash text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  challenge private.auth_challenges%rowtype;
begin
  if p_owner_id is null or p_challenge_id is null then
    raise exception using errcode = 'P0001', message = 'DELETE_CHALLENGE_INVALID';
  end if;
  if p_nonce_hash is null or p_nonce_hash !~ '^[0-9a-f]{64}$' then
    raise exception using errcode = 'P0001', message = 'INVALID_NONCE_HASH';
  end if;

  select stored.*
  into challenge
  from private.auth_challenges as stored
  where stored.id = p_challenge_id
  for share;

  if not found
    or challenge.owner_id <> p_owner_id
    or challenge.purpose <> 'account_delete'
    or challenge.nonce_hash <> p_nonce_hash then
    raise exception using errcode = 'P0001', message = 'DELETE_CHALLENGE_INVALID';
  end if;
  if challenge.used_at is not null then
    raise exception using errcode = 'P0001', message = 'DELETE_CHALLENGE_USED';
  end if;
  if challenge.expires_at <= pg_catalog.clock_timestamp() then
    raise exception using errcode = 'P0001', message = 'DELETE_CHALLENGE_EXPIRED';
  end if;

  return pg_catalog.jsonb_build_object('http_status', 200, 'state', 'valid');
end;
$$;

create function public.library_replay_account_deletion(
  p_owner_id uuid,
  p_request_id uuid,
  p_request_hash text
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  existing_job public.account_deletion_jobs%rowtype;
  existing_claim private.request_id_claims%rowtype;
begin
  if p_owner_id is null then
    raise exception using errcode = 'P0001', message = 'UNAUTHENTICATED';
  end if;
  if p_request_id is null then
    raise exception using errcode = 'P0001', message = 'INVALID_REQUEST_ID';
  end if;
  if p_request_hash is null or p_request_hash !~ '^[0-9a-f]{64}$' then
    raise exception using errcode = 'P0001', message = 'INVALID_REQUEST_HASH';
  end if;

  select claim.*
  into existing_claim
  from private.request_id_claims as claim
  where claim.owner_id = p_owner_id
    and claim.request_id = p_request_id;

  if found and (
    existing_claim.method_path <> 'POST /account/delete'
    or existing_claim.request_hash <> p_request_hash
  ) then
    raise exception using errcode = 'P0001', message = 'IDEMPOTENCY_MISMATCH';
  end if;

  select job.*
  into existing_job
  from public.account_deletion_jobs as job
  where job.owner_id = p_owner_id
    and job.request_id = p_request_id;

  if not found then
    return null;
  end if;

  if existing_job.request_hash <> p_request_hash then
    raise exception using errcode = 'P0001', message = 'IDEMPOTENCY_MISMATCH';
  end if;

  return pg_catalog.jsonb_build_object('http_status', 202, 'state', 'deleting');
end;
$$;

create function public.library_accept_account_deletion(
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
  for update;

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

create function public.library_claim_account_deletion_jobs(p_limit integer default 10)
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

  with candidates as materialized (
    select job.owner_id
    from public.account_deletion_jobs as job
    where (
      job.state in ('queued', 'retry')
      and job.next_run_at <= pg_catalog.clock_timestamp()
    ) or (
      job.state = 'running'
      and job.lease_until <= pg_catalog.clock_timestamp()
    )
    order by job.next_run_at, job.requested_at, job.owner_id
    for update skip locked
    limit p_limit
  ),
  claimed as (
    update public.account_deletion_jobs as job
    set state = 'running',
        attempts = job.attempts + 1,
        lease_token = gen_random_uuid(),
        lease_until = pg_catalog.clock_timestamp() + interval '5 minutes',
        last_error_code = null
    from candidates
    where job.owner_id = candidates.owner_id
    returning job.*
  )
  select coalesce(
    pg_catalog.jsonb_agg(
      pg_catalog.jsonb_build_object(
        'owner_id', claimed.owner_id,
        'request_id', claimed.request_id,
        'attempts', claimed.attempts,
        'lease_token', claimed.lease_token,
        'lease_until', claimed.lease_until,
        'business_purged', claimed.business_purged_at is not null
      )
      order by claimed.next_run_at, claimed.requested_at, claimed.owner_id
    ),
    '[]'::jsonb
  )
  into jobs
  from claimed;

  return pg_catalog.jsonb_build_object('http_status', 200, 'jobs', jobs);
end;
$$;

create function public.library_prepare_account_deletion(
  p_owner_id uuid,
  p_lease_token uuid
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  current_job public.account_deletion_jobs%rowtype;
  owned_objects jsonb;
  owned_object_count integer;
  conflict_count integer;
  asset_count integer;
  owner_prefix text;
begin
  if p_owner_id is null or p_lease_token is null then
    raise exception using errcode = 'P0001', message = 'INVALID_CLEANUP_IDENTITY';
  end if;

  select job.*
  into current_job
  from public.account_deletion_jobs as job
  where job.owner_id = p_owner_id
  for update;

  if not found then
    raise exception using errcode = 'P0001', message = 'ACCOUNT_DELETION_NOT_FOUND';
  end if;
  if current_job.state <> 'running'
    or current_job.lease_token is distinct from p_lease_token
    or current_job.lease_until <= pg_catalog.clock_timestamp() then
    return pg_catalog.jsonb_build_object('http_status', 409, 'error_code', 'LEASE_LOST');
  end if;

  owner_prefix := p_owner_id::text || '/';

  select coalesce(
    pg_catalog.jsonb_agg(
      pg_catalog.jsonb_build_object(
        'object_id', object.id,
        'bucket_id', object.bucket_id,
        'object_path', object.name
      )
      order by object.bucket_id, object.name, object.id
    ),
    '[]'::jsonb
  ), count(*)::integer
  into owned_objects, owned_object_count
  from storage.objects as object
  where object.owner_id = p_owner_id::text
    or (
      object.bucket_id = 'library-images'
      and object.name like owner_prefix || '%'
      and (object.owner_id is null or object.owner_id = p_owner_id::text)
    );

  select count(*)::integer
  into conflict_count
  from storage.objects as object
  where object.name like owner_prefix || '%'
    and object.owner_id is distinct from p_owner_id::text
    and not (object.bucket_id = 'library-images' and object.owner_id is null);

  select count(*)::integer
  into asset_count
  from public.assets as asset
  where asset.owner_id = p_owner_id;

  return pg_catalog.jsonb_build_object(
    'http_status', 200,
    'state', 'storage_cleanup',
    'objects', owned_objects,
    'object_count', owned_object_count,
    'ownership_conflict_count', conflict_count,
    'asset_count', asset_count
  );
end;
$$;

create function public.library_purge_account_deletion(
  p_owner_id uuid,
  p_lease_token uuid
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  current_job public.account_deletion_jobs%rowtype;
  owner_prefix text;
  storage_object_count integer;
  conflict_count integer;
  asset_count integer;
  release_reserved bigint;
  release_used bigint;
  current_reserved bigint;
  current_used bigint;
  purge_time timestamptz := pg_catalog.clock_timestamp();
begin
  if p_owner_id is null or p_lease_token is null then
    raise exception using errcode = 'P0001', message = 'INVALID_CLEANUP_IDENTITY';
  end if;

  select job.*
  into current_job
  from public.account_deletion_jobs as job
  where job.owner_id = p_owner_id
  for update;

  if not found then
    raise exception using errcode = 'P0001', message = 'ACCOUNT_DELETION_NOT_FOUND';
  end if;
  if current_job.state <> 'running'
    or current_job.lease_token is distinct from p_lease_token
    or current_job.lease_until <= pg_catalog.clock_timestamp() then
    return pg_catalog.jsonb_build_object('http_status', 409, 'error_code', 'LEASE_LOST');
  end if;

  owner_prefix := p_owner_id::text || '/';

  select count(*)::integer
  into conflict_count
  from storage.objects as object
  where object.name like owner_prefix || '%'
    and object.owner_id is distinct from p_owner_id::text
    and not (object.bucket_id = 'library-images' and object.owner_id is null);

  if conflict_count > 0 then
    return pg_catalog.jsonb_build_object(
      'http_status', 409,
      'error_code', 'STORAGE_OWNERSHIP_CONFLICT',
      'ownership_conflict_count', conflict_count
    );
  end if;

  select count(*)::integer
  into storage_object_count
  from storage.objects as object
  where object.owner_id = p_owner_id::text
    or (
      object.bucket_id = 'library-images'
      and object.name like owner_prefix || '%'
      and object.owner_id is null
    );

  if storage_object_count > 0 then
    return pg_catalog.jsonb_build_object(
      'http_status', 409,
      'error_code', 'STORAGE_OBJECTS_PRESENT',
      'object_count', storage_object_count
    );
  end if;

  if current_job.business_purged_at is not null then
    return pg_catalog.jsonb_build_object(
      'http_status', 200,
      'state', 'ready_for_auth_delete',
      'released_reserved_bytes', 0,
      'released_used_bytes', 0
    );
  end if;

  select usage.reserved_image_bytes, usage.used_image_bytes
  into current_reserved, current_used
  from public.library_usage as usage
  where usage.owner_id = p_owner_id
  for update;

  perform 1
  from public.assets as asset
  where asset.owner_id = p_owner_id
  for update;

  select
    count(*)::integer,
    coalesce(sum(case when asset.actual_bytes is null then 2000000 else 0 end), 0),
    coalesce(sum(coalesce(asset.actual_bytes, 0)), 0)
  into asset_count, release_reserved, release_used
  from public.assets as asset
  where asset.owner_id = p_owner_id;

  if asset_count > 0 and current_reserved is null then
    raise exception using errcode = 'P0001', message = 'IMAGE_USAGE_CORRUPT';
  end if;
  if current_reserved is not null
    and (current_reserved < release_reserved or current_used < release_used) then
    raise exception using errcode = 'P0001', message = 'IMAGE_USAGE_CORRUPT';
  end if;

  if asset_count > 0 then
    update public.library_usage
    set reserved_image_bytes = reserved_image_bytes - release_reserved,
        used_image_bytes = used_image_bytes - release_used,
        updated_at = purge_time
    where owner_id = p_owner_id;

    insert into private.asset_cleanup_receipts (
      asset_id,
      owner_id,
      item_id,
      lease_token,
      released_reserved_bytes,
      released_used_bytes,
      completed_at
    )
    select
      asset.id,
      asset.owner_id,
      asset.item_id,
      p_lease_token,
      case when asset.actual_bytes is null then 2000000 else 0 end,
      coalesce(asset.actual_bytes, 0),
      purge_time
    from public.assets as asset
    where asset.owner_id = p_owner_id
    on conflict (asset_id) do nothing;

    delete from public.assets
    where owner_id = p_owner_id;
  end if;

  delete from private.category_normalization_authorizations
  where owner_id = p_owner_id;
  delete from public.api_requests
  where owner_id = p_owner_id;
  delete from public.api_rate_buckets
  where owner_id = p_owner_id;
  delete from public.items
  where owner_id = p_owner_id;
  delete from public.categories
  where owner_id = p_owner_id;
  delete from public.library_usage
  where owner_id = p_owner_id;
  delete from public.beta_members
  where owner_id = p_owner_id;

  update public.account_deletion_jobs
  set business_purged_at = purge_time
  where owner_id = p_owner_id;

  return pg_catalog.jsonb_build_object(
    'http_status', 200,
    'state', 'ready_for_auth_delete',
    'released_reserved_bytes', release_reserved,
    'released_used_bytes', release_used
  );
end;
$$;

create function public.library_fail_account_deletion(
  p_owner_id uuid,
  p_lease_token uuid,
  p_error_code text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  current_job public.account_deletion_jobs%rowtype;
  next_run_value timestamptz;
begin
  if p_owner_id is null or p_lease_token is null then
    raise exception using errcode = 'P0001', message = 'INVALID_CLEANUP_IDENTITY';
  end if;
  if p_error_code is null or p_error_code not in (
    'ACCOUNT_DELETION_INVALID_JOB',
    'ACCOUNT_DELETION_INTERNAL_ERROR',
    'ACCOUNT_STORAGE_OWNERSHIP_CONFLICT',
    'ACCOUNT_STORAGE_DELETE_FAILED',
    'ACCOUNT_STORAGE_OBJECTS_REMAIN',
    'ACCOUNT_BUSINESS_PURGE_FAILED',
    'ACCOUNT_AUTH_DELETE_FAILED',
    'ACCOUNT_FINALIZE_FAILED',
    'ACCOUNT_PREPARE_FAILED'
  ) then
    raise exception using errcode = 'P0001', message = 'INVALID_ERROR_CODE';
  end if;

  select job.*
  into current_job
  from public.account_deletion_jobs as job
  where job.owner_id = p_owner_id
  for update;

  if not found then
    raise exception using errcode = 'P0001', message = 'ACCOUNT_DELETION_NOT_FOUND';
  end if;
  if current_job.state = 'complete' then
    if current_job.completed_lease_token = p_lease_token then
      return pg_catalog.jsonb_build_object('http_status', 200, 'state', 'complete');
    end if;
    return pg_catalog.jsonb_build_object('http_status', 409, 'error_code', 'LEASE_LOST');
  end if;
  if current_job.state <> 'running'
    or current_job.lease_token is distinct from p_lease_token
    or current_job.lease_until <= pg_catalog.clock_timestamp() then
    return pg_catalog.jsonb_build_object('http_status', 409, 'error_code', 'LEASE_LOST');
  end if;

  next_run_value := pg_catalog.clock_timestamp() + case
    when current_job.attempts <= 1 then interval '1 minute'
    when current_job.attempts = 2 then interval '5 minutes'
    when current_job.attempts = 3 then interval '30 minutes'
    else interval '2 hours'
  end;

  update public.account_deletion_jobs
  set state = 'retry',
      next_run_at = next_run_value,
      lease_until = null,
      lease_token = null,
      last_error_code = p_error_code
  where owner_id = p_owner_id;

  return pg_catalog.jsonb_build_object(
    'http_status', 202,
    'state', 'retry',
    'next_run_at', next_run_value,
    'attempts', current_job.attempts,
    'error_code', p_error_code
  );
end;
$$;

create function public.library_finalize_account_deletion(
  p_owner_id uuid,
  p_lease_token uuid
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  current_job public.account_deletion_jobs%rowtype;
  completed_time timestamptz := pg_catalog.clock_timestamp();
  owner_prefix text;
begin
  if p_owner_id is null or p_lease_token is null then
    raise exception using errcode = 'P0001', message = 'INVALID_CLEANUP_IDENTITY';
  end if;

  select job.*
  into current_job
  from public.account_deletion_jobs as job
  where job.owner_id = p_owner_id
  for update;

  if not found then
    raise exception using errcode = 'P0001', message = 'ACCOUNT_DELETION_NOT_FOUND';
  end if;
  if current_job.state = 'complete' then
    if current_job.completed_lease_token = p_lease_token then
      return pg_catalog.jsonb_build_object('http_status', 200, 'state', 'complete');
    end if;
    return pg_catalog.jsonb_build_object('http_status', 409, 'error_code', 'LEASE_LOST');
  end if;
  if current_job.state <> 'running'
    or current_job.lease_token is distinct from p_lease_token
    or current_job.lease_until <= pg_catalog.clock_timestamp() then
    return pg_catalog.jsonb_build_object('http_status', 409, 'error_code', 'LEASE_LOST');
  end if;
  if current_job.business_purged_at is null then
    return pg_catalog.jsonb_build_object(
      'http_status', 409,
      'error_code', 'BUSINESS_PURGE_REQUIRED'
    );
  end if;

  owner_prefix := p_owner_id::text || '/';

  if exists (
    select 1
    from storage.objects as object
    where object.owner_id = p_owner_id::text
      or object.name like owner_prefix || '%'
  ) then
    return pg_catalog.jsonb_build_object(
      'http_status', 409,
      'error_code', 'STORAGE_OBJECTS_PRESENT'
    );
  end if;

  if exists (
    select 1
    from auth.users as auth_user
    where auth_user.id = p_owner_id
  ) then
    return pg_catalog.jsonb_build_object(
      'http_status', 409,
      'error_code', 'AUTH_USER_PRESENT'
    );
  end if;

  if exists (select 1 from public.items where owner_id = p_owner_id)
    or exists (select 1 from public.assets where owner_id = p_owner_id)
    or exists (select 1 from public.categories where owner_id = p_owner_id)
    or exists (select 1 from public.library_usage where owner_id = p_owner_id)
    or exists (select 1 from public.beta_members where owner_id = p_owner_id)
    or exists (select 1 from public.api_requests where owner_id = p_owner_id)
    or exists (select 1 from public.api_rate_buckets where owner_id = p_owner_id) then
    return pg_catalog.jsonb_build_object(
      'http_status', 409,
      'error_code', 'BUSINESS_DATA_PRESENT'
    );
  end if;

  delete from public.profiles
  where id = p_owner_id;

  update public.account_deletion_jobs
  set state = 'complete',
      next_run_at = completed_time,
      lease_until = null,
      lease_token = null,
      completed_lease_token = p_lease_token,
      last_error_code = null,
      completed_at = completed_time
  where owner_id = p_owner_id;

  return pg_catalog.jsonb_build_object('http_status', 200, 'state', 'complete');
end;
$$;

create function public.library_export_deletion_ledger()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  database_id_value uuid;
  coverage_from_value timestamptz;
  exported_through_value timestamptz;
  through_sequence_value bigint;
  export_id_value uuid;
begin
  perform pg_catalog.pg_advisory_xact_lock(713002609130018::bigint);

  lock table private.deletion_ledger in share mode;

  select ledger_identity.database_id, ledger_identity.coverage_origin
  into database_id_value, coverage_from_value
  from private.deletion_ledger_identity as ledger_identity
  where ledger_identity.singleton
  for share;

  if not found then
    raise exception using errcode = 'P0001', message = 'DELETION_LEDGER_IDENTITY_MISSING';
  end if;

  exported_through_value := pg_catalog.clock_timestamp();

  select coalesce(max(ledger.sequence), 0)
  into through_sequence_value
  from private.deletion_ledger as ledger;

  export_id_value := gen_random_uuid();

  insert into private.deletion_ledger_exports (
    export_id,
    database_id,
    through_sequence,
    coverage_from,
    exported_through,
    created_at
  ) values (
    export_id_value,
    database_id_value,
    through_sequence_value,
    coverage_from_value,
    exported_through_value,
    exported_through_value
  );

  insert into private.deletion_ledger_export_events (
    export_id,
    sequence,
    event_id,
    kind,
    owner_id,
    item_id,
    request_id,
    requested_at
  )
  select
    export_id_value,
    ledger.sequence,
    ledger.event_id,
    ledger.kind,
    ledger.owner_id,
    ledger.item_id,
    ledger.request_id,
    ledger.requested_at
  from private.deletion_ledger as ledger
  order by ledger.sequence;

  return pg_catalog.jsonb_build_object(
    'database_id', database_id_value,
    'export_id', export_id_value,
    'coverage_from', coverage_from_value,
    'exported_through', exported_through_value,
    'through_sequence', through_sequence_value
  );
end;
$$;

create function public.library_read_deletion_ledger_export(
  p_export_id uuid,
  p_after_sequence bigint default 0,
  p_limit integer default 500
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  ledger_export private.deletion_ledger_exports%rowtype;
  cursor_sequence bigint;
  next_sequence_value bigint;
  events_value jsonb;
  has_more_value boolean;
begin
  if p_export_id is null then
    raise exception using errcode = 'P0001', message = 'INVALID_EXPORT_ID';
  end if;
  if p_after_sequence is null or p_after_sequence < 0 then
    raise exception using errcode = 'P0001', message = 'INVALID_EXPORT_CURSOR';
  end if;
  if p_limit is null or p_limit < 1 or p_limit > 1000 then
    raise exception using errcode = 'P0001', message = 'INVALID_LIMIT';
  end if;

  select stored_export.*
  into ledger_export
  from private.deletion_ledger_exports as stored_export
  where stored_export.export_id = p_export_id;

  if not found then
    raise exception using errcode = 'P0001', message = 'DELETION_LEDGER_EXPORT_NOT_FOUND';
  end if;
  if p_after_sequence > ledger_export.through_sequence then
    raise exception using errcode = 'P0001', message = 'INVALID_EXPORT_CURSOR';
  end if;

  cursor_sequence := p_after_sequence;

  with page as materialized (
    select snapshot.*
    from private.deletion_ledger_export_events as snapshot
    where snapshot.export_id = p_export_id
      and snapshot.sequence > cursor_sequence
    order by snapshot.sequence
    limit p_limit
  )
  select
    coalesce(
      pg_catalog.jsonb_agg(
        pg_catalog.jsonb_build_object(
          'sequence', page.sequence,
          'event_id', page.event_id,
          'kind', page.kind,
          'owner_id', page.owner_id,
          'item_id', page.item_id,
          'request_id', page.request_id,
          'requested_at', page.requested_at
        )
        order by page.sequence
      ),
      '[]'::jsonb
    ),
    coalesce(max(page.sequence), cursor_sequence)
  into events_value, next_sequence_value
  from page;

  select exists (
    select 1
    from private.deletion_ledger_export_events as snapshot
    where snapshot.export_id = p_export_id
      and snapshot.sequence > next_sequence_value
  )
  into has_more_value;

  return pg_catalog.jsonb_build_object(
    'events', events_value,
    'next_sequence', next_sequence_value,
    'has_more', has_more_value
  );
end;
$$;

create function public.library_ack_deletion_ledger_export(p_export_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  ledger_export private.deletion_ledger_exports%rowtype;
begin
  if p_export_id is null then
    raise exception using errcode = 'P0001', message = 'INVALID_EXPORT_ID';
  end if;

  select stored_export.*
  into ledger_export
  from private.deletion_ledger_exports as stored_export
  where stored_export.export_id = p_export_id
  for update;

  if not found then
    raise exception using errcode = 'P0001', message = 'DELETION_LEDGER_EXPORT_NOT_FOUND';
  end if;

  if ledger_export.acknowledged_at is null then
    update private.deletion_ledger_exports
    set acknowledged_at = pg_catalog.clock_timestamp()
    where export_id = p_export_id;
  end if;

  return pg_catalog.jsonb_build_object(
    'export_id', p_export_id,
    'acknowledged', true
  );
end;
$$;

create function public.library_run_retention(p_now timestamptz default now())
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
  where expires_at <= p_now;
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

  delete from private.request_id_claims as claim
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
    );

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

revoke all on function public.library_create_delete_challenge(uuid, uuid, text)
from public, anon, authenticated, service_role;
revoke all on function public.library_check_delete_challenge_binding(uuid, uuid, text)
from public, anon, authenticated, service_role;
revoke all on function public.library_replay_account_deletion(uuid, uuid, text)
from public, anon, authenticated, service_role;
revoke all on function public.library_accept_account_deletion(uuid, uuid, uuid, text)
from public, anon, authenticated, service_role;
revoke all on function public.library_claim_account_deletion_jobs(integer)
from public, anon, authenticated, service_role;
revoke all on function public.library_prepare_account_deletion(uuid, uuid)
from public, anon, authenticated, service_role;
revoke all on function public.library_purge_account_deletion(uuid, uuid)
from public, anon, authenticated, service_role;
revoke all on function public.library_fail_account_deletion(uuid, uuid, text)
from public, anon, authenticated, service_role;
revoke all on function public.library_finalize_account_deletion(uuid, uuid)
from public, anon, authenticated, service_role;
revoke all on function public.library_export_deletion_ledger()
from public, anon, authenticated, service_role;
revoke all on function public.library_read_deletion_ledger_export(uuid, bigint, integer)
from public, anon, authenticated, service_role;
revoke all on function public.library_ack_deletion_ledger_export(uuid)
from public, anon, authenticated, service_role;
revoke all on function public.library_run_retention(timestamptz)
from public, anon, authenticated, service_role;

grant execute on function public.library_create_delete_challenge(uuid, uuid, text)
to service_role;
grant execute on function public.library_check_delete_challenge_binding(uuid, uuid, text)
to service_role;
grant execute on function public.library_replay_account_deletion(uuid, uuid, text)
to service_role;
grant execute on function public.library_accept_account_deletion(uuid, uuid, uuid, text)
to service_role;
grant execute on function public.library_claim_account_deletion_jobs(integer)
to service_role;
grant execute on function public.library_prepare_account_deletion(uuid, uuid)
to service_role;
grant execute on function public.library_purge_account_deletion(uuid, uuid)
to service_role;
grant execute on function public.library_fail_account_deletion(uuid, uuid, text)
to service_role;
grant execute on function public.library_finalize_account_deletion(uuid, uuid)
to service_role;
grant execute on function public.library_export_deletion_ledger()
to service_role;
grant execute on function public.library_read_deletion_ledger_export(uuid, bigint, integer)
to service_role;
grant execute on function public.library_ack_deletion_ledger_export(uuid)
to service_role;
grant execute on function public.library_run_retention(timestamptz)
to service_role;
