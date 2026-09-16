begin;

create extension if not exists pgtap with schema extensions;
set local search_path = pg_catalog, extensions, public;

select no_plan();

create temporary table deletion_concurrency_state (
  key text primary key,
  value jsonb not null
);
grant select, insert, update, delete on deletion_concurrency_state to service_role;

insert into auth.users (
  instance_id,
  id,
  aud,
  role,
  email,
  encrypted_password,
  email_confirmed_at,
  raw_app_meta_data,
  raw_user_meta_data,
  created_at,
  updated_at,
  confirmation_token,
  email_change,
  email_change_token_new,
  recovery_token
)
values
  (
    '00000000-0000-0000-0000-000000000000',
    'f1000000-0000-0000-0000-000000000001',
    'authenticated',
    'authenticated',
    'deletion-concurrency-1@example.test',
    '',
    now(),
    '{"provider":"google","providers":["google"]}'::jsonb,
    '{}'::jsonb,
    now(),
    now(),
    '',
    '',
    '',
    ''
  ),
  (
    '00000000-0000-0000-0000-000000000000',
    'f1000000-0000-0000-0000-000000000002',
    'authenticated',
    'authenticated',
    'deletion-concurrency-2@example.test',
    '',
    now(),
    '{"provider":"google","providers":["google"]}'::jsonb,
    '{}'::jsonb,
    now(),
    now(),
    '',
    '',
    '',
    ''
  );

select ok(
  (
    select function.prosecdef
      and exists (
        select 1
        from unnest(function.proconfig) as setting
        where setting like 'search_path=%'
      )
    from pg_proc as function
    where function.oid = 'private.claim_request_id(uuid,uuid,text,text,timestamptz,timestamptz)'::regprocedure
  )
  and (
    select function.prosecdef
      and exists (
        select 1
        from unnest(function.proconfig) as setting
        where setting like 'search_path=%'
      )
    from pg_proc as function
    where function.oid = 'public.library_accept_account_deletion(uuid,uuid,uuid,text)'::regprocedure
  )
  and (
    select function.prosecdef
      and exists (
        select 1
        from unnest(function.proconfig) as setting
        where setting like 'search_path=%'
      )
    from pg_proc as function
    where function.oid = 'public.library_run_retention(timestamptz)'::regprocedure
  ),
  '020 replaces the three canonical SECURITY DEFINER functions with fixed search paths'
);

select ok(
  pg_get_functiondef(
    'private.claim_request_id(uuid,uuid,text,text,timestamptz,timestamptz)'::regprocedure
  ) ilike '%for key share%'
  and pg_get_functiondef(
    'public.library_run_retention(timestamptz)'::regprocedure
  ) ilike '%for update of claim skip locked%'
  and pg_get_functiondef(
    'public.library_accept_account_deletion(uuid,uuid,uuid,text)'::regprocedure
  ) ilike '%from auth.users as auth_user%for no key update%'
  and pg_get_functiondef(
    'public.library_run_retention(timestamptz)'::regprocedure
  ) ilike '%created_at < p_now - interval ''7 days''%',
  'claim validation, Auth locking, claim pruning, and challenge retention use the repaired locks and boundary'
);

set local role service_role;

insert into deletion_concurrency_state (key, value)
values (
  'fresh-challenge',
  public.library_create_delete_challenge(
    'f1000000-0000-0000-0000-000000000001',
    'f2000000-0000-0000-0000-000000000001',
    repeat('a', 64)
  )
);

reset role;

update private.auth_challenges
set created_at = now() - interval '6 minutes',
    expires_at = now() - interval '1 minute'
where id = (
  select (value ->> 'challenge_id')::uuid
  from deletion_concurrency_state
  where key = 'fresh-challenge'
);

insert into deletion_concurrency_state (key, value)
select
  'expired-snapshot',
  pg_catalog.jsonb_build_object(
    'challenge_id', challenge.id,
    'expires_at', challenge.expires_at,
    'nonce_hash', challenge.nonce_hash
  )
from private.auth_challenges as challenge
where challenge.id = (
  select (value ->> 'challenge_id')::uuid
  from deletion_concurrency_state
  where key = 'fresh-challenge'
);

set local role service_role;

insert into deletion_concurrency_state (key, value)
values ('retention-before-replay', public.library_run_retention(now()));

select ok(
  (
    select private.jsonb_has_exact_keys(
      value,
      array[
        'http_status',
        'deleted_api_requests',
        'deleted_rate_buckets',
        'deleted_challenges',
        'deleted_item_tombstones',
        'deleted_cancelled_jobs',
        'deleted_account_jobs',
        'deleted_ledger_events'
      ]::text[]
    )
    from deletion_concurrency_state
    where key = 'retention-before-replay'
  ),
  '020 preserves the fixed retention response shape'
);

select is(
  (
    select (value ->> 'deleted_challenges')::integer
    from deletion_concurrency_state
    where key = 'retention-before-replay'
  ),
  0,
  'retention keeps a five-minute-expired challenge for the seven-day replay window'
);

insert into deletion_concurrency_state (key, value)
values (
  'challenge-replay',
  public.library_create_delete_challenge(
    'f1000000-0000-0000-0000-000000000001',
    'f2000000-0000-0000-0000-000000000001',
    repeat('a', 64)
  )
);

reset role;

select is(
  (
    select value ->> 'challenge_id'
    from deletion_concurrency_state
    where key = 'challenge-replay'
  ),
  (
    select value ->> 'challenge_id'
    from deletion_concurrency_state
    where key = 'expired-snapshot'
  ),
  'replaying the original UUID returns the original expired challenge identity'
);

select is(
  (
    select (value ->> 'expires_at')::timestamptz
    from deletion_concurrency_state
    where key = 'challenge-replay'
  ),
  (
    select (value ->> 'expires_at')::timestamptz
    from deletion_concurrency_state
    where key = 'expired-snapshot'
  ),
  'replaying the original UUID does not freshen challenge expiry'
);

select is(
  (
    select challenge.nonce_hash::text
    from private.auth_challenges as challenge
    where challenge.id = (
      select (value ->> 'challenge_id')::uuid
      from deletion_concurrency_state
      where key = 'challenge-replay'
    )
  ),
  (
    select value ->> 'nonce_hash'
    from deletion_concurrency_state
    where key = 'expired-snapshot'
  ),
  'replaying the original UUID preserves the original nonce hash'
);

set local role service_role;

select throws_ok(
  format(
    'select public.library_check_delete_challenge_binding(%L::uuid,%L::uuid,%L)',
    'f1000000-0000-0000-0000-000000000001',
    (
      select value ->> 'challenge_id'
      from deletion_concurrency_state
      where key = 'challenge-replay'
    ),
    repeat('a', 64)
  ),
  'P0001',
  'DELETE_CHALLENGE_EXPIRED',
  'the retained replay challenge remains expired for proof binding'
);

insert into deletion_concurrency_state (key, value)
values (
  'old-challenge',
  public.library_create_delete_challenge(
    'f1000000-0000-0000-0000-000000000002',
    'f2000000-0000-0000-0000-000000000002',
    repeat('b', 64)
  )
);

reset role;

update private.auth_challenges
set created_at = now() - interval '8 days',
    expires_at = now() - interval '8 days' + interval '5 minutes'
where id = (
  select (value ->> 'challenge_id')::uuid
  from deletion_concurrency_state
  where key = 'old-challenge'
);

update private.request_id_claims
set claimed_at = now() - interval '8 days',
    retain_until = now() - interval '1 day'
where owner_id = 'f1000000-0000-0000-0000-000000000002'
  and request_id = 'f2000000-0000-0000-0000-000000000002';

insert into private.request_id_claims (
  owner_id,
  request_id,
  method_path,
  request_hash,
  claimed_at,
  retain_until
)
values
  (
    'f1000000-0000-0000-0000-000000000003',
    'f2000000-0000-0000-0000-000000000003',
    'POST /items',
    repeat('c', 64),
    now() - interval '8 days',
    now() - interval '1 day'
  ),
  (
    'f1000000-0000-0000-0000-000000000004',
    'f2000000-0000-0000-0000-000000000004',
    'POST /items',
    repeat('d', 64),
    now(),
    now() + interval '1 day'
  );

select lives_ok(
  $$
    select private.claim_request_id(
      'f1000000-0000-0000-0000-000000000004',
      'f2000000-0000-0000-0000-000000000004',
      'POST /items',
      repeat('d', 64),
      now(),
      now() + interval '1 day'
    )
  $$,
  'an existing matching request claim remains shareable'
);

select is(
  (
    select count(*)::integer
    from private.request_id_claims
    where owner_id = 'f1000000-0000-0000-0000-000000000004'
      and request_id = 'f2000000-0000-0000-0000-000000000004'
  ),
  1,
  'matching claim reuse preserves one owner-scoped claim row'
);

set local role service_role;

insert into deletion_concurrency_state (key, value)
values ('retention-after-seven-days', public.library_run_retention(now()));

reset role;

select ok(
  not exists (
    select 1
    from private.auth_challenges
    where id = (
      select (value ->> 'challenge_id')::uuid
      from deletion_concurrency_state
      where key = 'old-challenge'
    )
  )
  and not exists (
    select 1
    from private.request_id_claims
    where owner_id = 'f1000000-0000-0000-0000-000000000002'
      and request_id = 'f2000000-0000-0000-0000-000000000002'
  ),
  'retention prunes an expired challenge and its orphan claim only after seven days'
);

select ok(
  not exists (
    select 1
    from private.request_id_claims
    where owner_id = 'f1000000-0000-0000-0000-000000000003'
      and request_id = 'f2000000-0000-0000-0000-000000000003'
  )
  and exists (
    select 1
    from private.request_id_claims
    where owner_id = 'f1000000-0000-0000-0000-000000000004'
      and request_id = 'f2000000-0000-0000-0000-000000000004'
      and method_path = 'POST /items'
      and request_hash = repeat('d', 64)
  ),
  'claim retention deletes only eligible expired orphans and keeps the matching live claim'
);

select is(
  (
    select count(*)::integer
    from private.auth_challenges
    where owner_id = 'f1000000-0000-0000-0000-000000000001'
      and request_id = 'f2000000-0000-0000-0000-000000000001'
  ),
  1,
  'the original less-than-seven-day expired challenge still has exactly one durable row'
);

select * from finish();
rollback;
