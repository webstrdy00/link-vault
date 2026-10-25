-- Browser verification is independent of Auth sessions. Only the trusted Edge
-- verifier may call these RPCs; neither Google proofs nor email are persisted.
create table private.web_account_challenges (
  id uuid primary key default gen_random_uuid(),
  request_id uuid not null unique,
  purpose text not null check (purpose in ('delete', 'status')),
  nonce_hash text not null check (nonce_hash ~ '^[0-9a-f]{64}$'),
  created_at timestamptz not null,
  expires_at timestamptz not null,
  retain_until timestamptz not null,
  attempts integer not null default 0 check (attempts between 0 and 5),
  consumed_request_id uuid unique,
  request_hash text check (request_hash ~ '^[0-9a-f]{64}$'),
  reply jsonb,
  check (expires_at > created_at and retain_until > expires_at),
  check ((consumed_request_id is null and request_hash is null and reply is null)
    or (consumed_request_id is not null and request_hash is not null and reply is not null))
);
create index web_account_challenges_retention_idx on private.web_account_challenges (retain_until);

-- One row, not a client-supplied IP/subject bucket: unauthenticated callers cannot
-- manufacture unbounded quota keys. Global ceilings also bound JWKS work.
create table private.web_account_budget (
  singleton boolean primary key default true check (singleton),
  window_start timestamptz not null,
  challenges integer not null default 0,
  proofs integer not null default 0
);
insert into private.web_account_budget (window_start) values (pg_catalog.clock_timestamp());

-- No auth.users FK: status must survive that row's deletion. A job FK prevents
-- an orphaned mapping from ever being interpreted as completed deletion.
create table private.web_account_subject_jobs (
  subject_hash text not null check (subject_hash ~ '^[0-9a-f]{64}$'),
  owner_id uuid not null,
  request_id uuid not null,
  expires_at timestamptz,
  primary key (subject_hash, owner_id),
  foreign key (owner_id, request_id)
    references public.account_deletion_jobs (owner_id, request_id) on delete cascade
);
create index web_account_subject_jobs_expiry_idx on private.web_account_subject_jobs (expires_at);
revoke all on table private.web_account_challenges, private.web_account_budget,
  private.web_account_subject_jobs from public, anon, authenticated, service_role;

create function private.web_google_subject_hash(p_subject text)
returns text
language sql immutable strict
set search_path = ''
as $$
  select pg_catalog.encode(extensions.digest(pg_catalog.convert_to(
    'link-vault/web-account/google-subject/v1' || pg_catalog.chr(10) || p_subject,
    'UTF8'), 'sha256'), 'hex');
$$;

-- Preserve the verified linked identity before the existing worker removes Auth.
-- This also makes future native-accepted deletions observable without changing
-- any native HTTP request, response, nonce, or worker/ledger protocol.
create function private.web_account_capture_job()
returns trigger
language plpgsql security definer
set search_path = ''
as $$
begin
  insert into private.web_account_subject_jobs (subject_hash, owner_id, request_id, expires_at)
  select private.web_google_subject_hash(coalesce(nullif(identity.provider_id, ''), identity.identity_data ->> 'sub')),
    new.owner_id, new.request_id,
    case when new.state = 'complete' then new.completed_at + interval '30 days' else null end
  from auth.identities as identity
  where identity.user_id = new.owner_id and identity.provider = 'google'
    and coalesce(nullif(identity.provider_id, ''), identity.identity_data ->> 'sub') ~ '^[!-~]{1,255}$'
    and (nullif(identity.provider_id, '') is null or identity.identity_data ->> 'sub' is null
      or identity.provider_id = identity.identity_data ->> 'sub')
  on conflict (subject_hash, owner_id) do update
    set request_id = excluded.request_id, expires_at = excluded.expires_at;

  update private.web_account_subject_jobs
  set expires_at = case when new.state = 'complete' then new.completed_at + interval '30 days' else null end
  where owner_id = new.owner_id and request_id = new.request_id;
  return new;
end;
$$;
create trigger web_account_capture_job
  after insert or update of state, completed_at on public.account_deletion_jobs
  for each row execute function private.web_account_capture_job();

insert into private.web_account_subject_jobs (subject_hash, owner_id, request_id, expires_at)
select private.web_google_subject_hash(coalesce(nullif(identity.provider_id, ''), identity.identity_data ->> 'sub')),
  job.owner_id, job.request_id,
  case when job.state = 'complete' then job.completed_at + interval '30 days' else null end
from public.account_deletion_jobs as job
join auth.identities as identity on identity.user_id = job.owner_id and identity.provider = 'google'
where coalesce(nullif(identity.provider_id, ''), identity.identity_data ->> 'sub') ~ '^[!-~]{1,255}$'
  and (nullif(identity.provider_id, '') is null or identity.identity_data ->> 'sub' is null
    or identity.provider_id = identity.identity_data ->> 'sub')
  and (job.completed_at is null or job.completed_at + interval '30 days' > pg_catalog.clock_timestamp())
on conflict do nothing;

create function private.web_account_cleanup()
returns void
language plpgsql security definer
set search_path = ''
as $$
begin
  delete from private.web_account_challenges where retain_until <= pg_catalog.clock_timestamp();
  delete from private.web_account_subject_jobs where expires_at <= pg_catalog.clock_timestamp();
end;
$$;
-- In-flight mappings live until authoritative completion. Completed mappings
-- expire 30 days after completion, even if ledger export holds the job longer.
select cron.schedule('link-vault-web-account-retention', '*/5 * * * *',
  'select private.web_account_cleanup();');

create function private.web_account_take_budget(p_kind text)
returns boolean
language plpgsql security definer
set search_path = ''
as $$
declare
  bucket private.web_account_budget%rowtype;
  current_minute timestamptz := pg_catalog.date_trunc('minute', pg_catalog.clock_timestamp());
begin
  select * into bucket from private.web_account_budget where singleton for update;
  if bucket.window_start <> current_minute then
    update private.web_account_budget set window_start = current_minute, challenges = 0, proofs = 0 where singleton;
    bucket.challenges := 0;
    bucket.proofs := 0;
  end if;
  if p_kind = 'challenge' then
    if bucket.challenges >= 60 then return false; end if;
    update private.web_account_budget set challenges = challenges + 1 where singleton;
  elsif p_kind = 'proof' then
    if bucket.proofs >= 240 then return false; end if;
    update private.web_account_budget set proofs = proofs + 1 where singleton;
  else
    return false;
  end if;
  return true;
end;
$$;

create function public.library_web_account_challenge(p_request_id uuid, p_purpose text, p_nonce_hash text)
returns jsonb
language plpgsql security definer
set search_path = ''
as $$
declare
  challenge private.web_account_challenges%rowtype;
  created_time timestamptz := pg_catalog.clock_timestamp();
begin
  perform pg_catalog.pg_advisory_xact_lock(713002610120022::bigint);
  if p_request_id is null or p_purpose is null or p_purpose not in ('delete', 'status')
    or p_nonce_hash is null or p_nonce_hash !~ '^[0-9a-f]{64}$' then
    return pg_catalog.jsonb_build_object('http_status', 409, 'error_code', 'WEB_CHALLENGE_INVALID');
  end if;
  -- Errors are returned, not raised: quotas must commit on unsuccessful calls.
  if not private.web_account_take_budget('challenge') then
    return pg_catalog.jsonb_build_object('http_status', 429, 'error_code', 'RATE_LIMITED');
  end if;
  perform private.web_account_cleanup();
  created_time := pg_catalog.clock_timestamp();
  select * into challenge from private.web_account_challenges where request_id = p_request_id for update;
  if found then
    if challenge.purpose <> p_purpose or challenge.nonce_hash <> p_nonce_hash then
      return pg_catalog.jsonb_build_object('http_status', 409, 'error_code', 'IDEMPOTENCY_MISMATCH');
    end if;
    if challenge.expires_at <= created_time or challenge.consumed_request_id is not null then
      return pg_catalog.jsonb_build_object('http_status', 409, 'error_code', 'WEB_CHALLENGE_INVALID');
    end if;
  else
    if exists (select 1 from private.web_account_challenges where consumed_request_id = p_request_id) then
      return pg_catalog.jsonb_build_object('http_status', 409, 'error_code', 'IDEMPOTENCY_MISMATCH');
    end if;
    if (select count(*) from private.web_account_challenges) >= 5000 then
      return pg_catalog.jsonb_build_object('http_status', 429, 'error_code', 'RATE_LIMITED');
    end if;
    insert into private.web_account_challenges (request_id, purpose, nonce_hash, created_at, expires_at, retain_until)
    values (p_request_id, p_purpose, p_nonce_hash, created_time,
      created_time + interval '5 minutes', created_time + interval '24 hours') returning * into challenge;
  end if;
  return pg_catalog.jsonb_build_object('http_status', 201, 'challenge_id', challenge.id, 'expires_at', challenge.expires_at);
end;
$$;

create function public.library_web_account_begin(p_request_id uuid, p_challenge_id uuid, p_purpose text, p_request_hash text)
returns jsonb
language plpgsql security definer
set search_path = ''
as $$
declare
  challenge private.web_account_challenges%rowtype;
begin
  if not private.web_account_take_budget('proof') then
    return pg_catalog.jsonb_build_object('http_status', 429, 'error_code', 'RATE_LIMITED');
  end if;
  if p_request_id is null or p_challenge_id is null or p_purpose is null or p_purpose not in ('delete', 'status')
    or p_request_hash is null or p_request_hash !~ '^[0-9a-f]{64}$' then
    return pg_catalog.jsonb_build_object('http_status', 409, 'error_code', 'WEB_CHALLENGE_INVALID');
  end if;
  select * into challenge from private.web_account_challenges where id = p_challenge_id for update;
  if not found or challenge.purpose <> p_purpose or challenge.retain_until <= pg_catalog.clock_timestamp() then
    return pg_catalog.jsonb_build_object('http_status', 409, 'error_code', 'WEB_CHALLENGE_INVALID');
  end if;
  if challenge.consumed_request_id is not null then
    if challenge.consumed_request_id = p_request_id and challenge.request_hash = p_request_hash then
      if challenge.reply ->> 'state' = 'deleted'
        and (challenge.reply ->> 'status_available_until')::timestamptz <= pg_catalog.clock_timestamp() then
        return pg_catalog.jsonb_build_object('http_status', 404, 'error_code', 'STATUS_NOT_FOUND');
      end if;
      return challenge.reply;
    end if;
    return pg_catalog.jsonb_build_object('http_status', 409, 'error_code', 'IDEMPOTENCY_MISMATCH');
  end if;
  if exists (select 1 from private.web_account_challenges where request_id = p_request_id
    or consumed_request_id = p_request_id) then
    return pg_catalog.jsonb_build_object('http_status', 409, 'error_code', 'IDEMPOTENCY_MISMATCH');
  end if;
  if challenge.expires_at <= pg_catalog.clock_timestamp() then
    return pg_catalog.jsonb_build_object('http_status', 409, 'error_code', 'WEB_CHALLENGE_INVALID');
  end if;
  if challenge.attempts >= 5 then
    return pg_catalog.jsonb_build_object('http_status', 429, 'error_code', 'RATE_LIMITED');
  end if;
  update private.web_account_challenges set attempts = attempts + 1 where id = p_challenge_id;
  return pg_catalog.jsonb_build_object('http_status', 200, 'state', 'verify', 'created_at', challenge.created_at);
end;
$$;

create function public.library_web_account_finish(
  p_request_id uuid, p_challenge_id uuid, p_purpose text, p_request_hash text,
  p_google_subject text, p_nonce_hash text
)
returns jsonb
language plpgsql security definer
set search_path = ''
as $$
declare
  challenge private.web_account_challenges%rowtype;
  identity_owner uuid;
  matched_owner uuid;
  owner_count integer := 0;
  job public.account_deletion_jobs%rowtype;
  retained_job_count integer := 0;
  subject_hash_value text;
  native_challenge uuid;
  reply_value jsonb;
  failure text;
begin
  -- Serialize request-ID claims across challenges. Do not hold the global quota
  -- row while accepting deletion (the existing pipeline may lock many items).
  perform pg_catalog.pg_advisory_xact_lock(713002610120022::bigint);
  if p_request_id is null or p_challenge_id is null or p_purpose is null or p_purpose not in ('delete', 'status')
    or p_request_hash is null or p_request_hash !~ '^[0-9a-f]{64}$'
    or p_google_subject is null or p_google_subject !~ '^[!-~]{1,255}$'
    or p_nonce_hash is null or p_nonce_hash !~ '^[0-9a-f]{64}$' then
    return pg_catalog.jsonb_build_object('http_status', 409, 'error_code', 'WEB_CHALLENGE_INVALID');
  end if;
  select * into challenge from private.web_account_challenges where id = p_challenge_id for update;
  if not found or challenge.purpose <> p_purpose or challenge.nonce_hash <> p_nonce_hash
    or challenge.retain_until <= pg_catalog.clock_timestamp() then
    return pg_catalog.jsonb_build_object('http_status', 409, 'error_code', 'WEB_CHALLENGE_INVALID');
  end if;
  if challenge.consumed_request_id is not null then
    if challenge.consumed_request_id = p_request_id and challenge.request_hash = p_request_hash then
      if challenge.reply ->> 'state' = 'deleted'
        and (challenge.reply ->> 'status_available_until')::timestamptz <= pg_catalog.clock_timestamp() then
        return pg_catalog.jsonb_build_object('http_status', 404, 'error_code', 'STATUS_NOT_FOUND');
      end if;
      return challenge.reply;
    end if;
    return pg_catalog.jsonb_build_object('http_status', 409, 'error_code', 'IDEMPOTENCY_MISMATCH');
  end if;
  if exists (select 1 from private.web_account_challenges where request_id = p_request_id
    or consumed_request_id = p_request_id) then
    return pg_catalog.jsonb_build_object('http_status', 409, 'error_code', 'IDEMPOTENCY_MISMATCH');
  end if;
  if challenge.expires_at <= pg_catalog.clock_timestamp() or challenge.attempts = 0 then
    return pg_catalog.jsonb_build_object('http_status', 409, 'error_code', 'WEB_CHALLENGE_INVALID');
  end if;

  -- Only a matching Google provider subject grants access. Equal emails, Auth
  -- user UUIDs, deletion request UUIDs, and client-supplied job IDs never do.
  for matched_owner in
    select identity.user_id from auth.identities as identity
    where identity.provider = 'google'
      and coalesce(nullif(identity.provider_id, ''), identity.identity_data ->> 'sub') = p_google_subject
      and (nullif(identity.provider_id, '') is null or identity.identity_data ->> 'sub' is null
        or identity.provider_id = identity.identity_data ->> 'sub')
  loop
    if identity_owner is distinct from matched_owner then
      identity_owner := matched_owner;
      owner_count := owner_count + 1;
    end if;
  end loop;
  if owner_count = 1 then
    -- Match the worker's parent-before-child lock order. Locking an identity
    -- before auth.users could deadlock against the worker's Auth cascade.
    perform 1 from auth.users where id = identity_owner for key share;
    if not found then
      identity_owner := null;
    else
      perform 1 from auth.identities as identity
      where identity.user_id = identity_owner and identity.provider = 'google'
        and coalesce(nullif(identity.provider_id, ''), identity.identity_data ->> 'sub') = p_google_subject
        and (nullif(identity.provider_id, '') is null or identity.identity_data ->> 'sub' is null
          or identity.provider_id = identity.identity_data ->> 'sub')
      for share of identity;
      if not found then identity_owner := null; end if;
    end if;
  end if;
  subject_hash_value := private.web_google_subject_hash(p_google_subject);
  if owner_count > 1 then
    reply_value := pg_catalog.jsonb_build_object('http_status', 403, 'error_code', 'GOOGLE_IDENTITY_MISMATCH');
  else
    if identity_owner is not null then
      select * into job from public.account_deletion_jobs where owner_id = identity_owner;
    else
      -- Re-registration can leave multiple retained jobs for one Google subject.
      -- Inspect one snapshot and reject ambiguity rather than picking a newer
      -- completed job that could mask an older unfinished deletion.
      for job in
        select stored_job.* from private.web_account_subject_jobs as mapping
        join public.account_deletion_jobs as stored_job
          on stored_job.owner_id = mapping.owner_id and stored_job.request_id = mapping.request_id
        where mapping.subject_hash = subject_hash_value
          and (mapping.expires_at is null or mapping.expires_at > pg_catalog.clock_timestamp())
        limit 2
      loop
        retained_job_count := retained_job_count + 1;
      end loop;
    end if;

    if retained_job_count > 1 then
      reply_value := pg_catalog.jsonb_build_object('http_status', 403, 'error_code', 'GOOGLE_IDENTITY_MISMATCH');
    elsif p_purpose = 'status' then
      if job.owner_id is not null then
        if job.state = 'complete' and (job.completed_at is null
          or job.completed_at + interval '30 days' <= pg_catalog.clock_timestamp()) then
          reply_value := pg_catalog.jsonb_build_object('http_status', 404, 'error_code', 'STATUS_NOT_FOUND');
        else
          reply_value := pg_catalog.jsonb_build_object('http_status', 200,
            'state', case when job.state = 'complete' then 'deleted' else 'deleting' end,
            'requested_at', job.requested_at, 'completed_at', job.completed_at,
            'status_available_until', case when job.state = 'complete' then job.completed_at + interval '30 days' else null end);
        end if;
      elsif identity_owner is not null and (
        exists (select 1 from public.profiles where id = identity_owner and state = 'deleting')
        or exists (select 1 from private.deletion_ledger where kind = 'account' and owner_id = identity_owner)
      ) then
        reply_value := pg_catalog.jsonb_build_object('http_status', 503, 'error_code', 'DEPENDENCY_UNAVAILABLE');
      elsif identity_owner is not null then
        reply_value := pg_catalog.jsonb_build_object('http_status', 200, 'state', 'not_requested',
          'requested_at', null, 'completed_at', null, 'status_available_until', null);
      else
        reply_value := pg_catalog.jsonb_build_object('http_status', 404, 'error_code', 'STATUS_NOT_FOUND');
      end if;
    elsif job.owner_id is not null then
      reply_value := pg_catalog.jsonb_build_object('http_status',
        case when job.state = 'complete' then 409 else 403 end,
        'error_code', case when job.state = 'complete' then 'ACCOUNT_ALREADY_DELETED' else 'ACCOUNT_DELETING' end);
    elsif identity_owner is null then
      reply_value := pg_catalog.jsonb_build_object('http_status', 404, 'error_code', 'ACCOUNT_NOT_FOUND');
    else
      -- Bridge only after server-verified subject+nonce, within this transaction.
      -- Never raw-admin-delete Auth: the existing acceptance RPC creates its
      -- job, item tombstones and durable deletion ledger before worker deletion.
      begin
        insert into private.auth_challenges (owner_id, request_id, nonce_hash, purpose, created_at, expires_at)
        values (identity_owner, gen_random_uuid(), p_nonce_hash, 'account_delete',
          pg_catalog.clock_timestamp(), challenge.expires_at) returning id into native_challenge;
        reply_value := public.library_accept_account_deletion(identity_owner, p_request_id, native_challenge, p_request_hash);
      exception when others then
        get stacked diagnostics failure = message_text;
        reply_value := case failure
          when 'IDEMPOTENCY_MISMATCH' then pg_catalog.jsonb_build_object('http_status', 409, 'error_code', failure)
          when 'ACCOUNT_DELETING' then pg_catalog.jsonb_build_object('http_status', 403, 'error_code', failure)
          when 'ACCOUNT_NOT_FOUND' then pg_catalog.jsonb_build_object('http_status', 404, 'error_code', failure)
          else pg_catalog.jsonb_build_object('http_status', 503, 'error_code', 'DEPENDENCY_UNAVAILABLE') end;
      end;
    end if;
  end if;
  if reply_value ->> 'http_status' = '503' then return reply_value; end if;
  update private.web_account_challenges set consumed_request_id = p_request_id,
    request_hash = p_request_hash, reply = reply_value where id = p_challenge_id;
  return reply_value;
end;
$$;

revoke all on function private.web_google_subject_hash(text) from public, anon, authenticated, service_role;
revoke all on function private.web_account_capture_job() from public, anon, authenticated, service_role;
revoke all on function private.web_account_cleanup() from public, anon, authenticated, service_role;
revoke all on function private.web_account_take_budget(text) from public, anon, authenticated, service_role;
revoke all on function public.library_web_account_challenge(uuid, text, text) from public, anon, authenticated, service_role;
revoke all on function public.library_web_account_begin(uuid, uuid, text, text) from public, anon, authenticated, service_role;
revoke all on function public.library_web_account_finish(uuid, uuid, text, text, text, text) from public, anon, authenticated, service_role;
grant execute on function public.library_web_account_challenge(uuid, text, text) to service_role;
grant execute on function public.library_web_account_begin(uuid, uuid, text, text) to service_role;
grant execute on function public.library_web_account_finish(uuid, uuid, text, text, text, text) to service_role;
