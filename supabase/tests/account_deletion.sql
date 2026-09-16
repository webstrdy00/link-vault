begin;

create extension if not exists pgtap with schema extensions;
set local search_path = pg_catalog, extensions, public;
-- Rollback-only metadata fixtures; real file removal is tested through Storage HTTP.
set local storage.allow_delete_query = 'true';

select no_plan();

create temporary table account_deletion_test_state (
  key text primary key,
  value jsonb not null
);
grant select, insert, update, delete on account_deletion_test_state to service_role;

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
select
  '00000000-0000-0000-0000-000000000000'::uuid,
  ('e1000000-0000-0000-0000-' || lpad(fixture_number::text, 12, '0'))::uuid,
  'authenticated',
  'authenticated',
  'account-delete-' || fixture_number || '@example.test',
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
from generate_series(1, 6) as fixture(fixture_number);

insert into public.beta_members (owner_id, enabled, approved_at)
values
  ('e1000000-0000-0000-0000-000000000001', true, now()),
  ('e1000000-0000-0000-0000-000000000003', false, now());

insert into public.profiles (id, state)
values
  ('e1000000-0000-0000-0000-000000000001', 'active'),
  ('e1000000-0000-0000-0000-000000000003', 'active'),
  ('e1000000-0000-0000-0000-000000000006', 'active');

insert into public.library_usage (
  owner_id,
  active_item_count,
  used_image_bytes,
  reserved_image_bytes
)
values
  ('e1000000-0000-0000-0000-000000000001', 3, 0, 2000000),
  ('e1000000-0000-0000-0000-000000000003', 1, 0, 0),
  ('e1000000-0000-0000-0000-000000000006', 0, 0, 0);

insert into public.items (
  id,
  owner_id,
  original_url,
  normalized_url,
  url_hash,
  source,
  display_fallback,
  metadata_state
)
values
  (
    'f1000000-0000-0000-0000-000000000001',
    'e1000000-0000-0000-0000-000000000001',
    'https://example.test/account-a-1',
    'https://example.test/account-a-1',
    repeat('1', 64),
    'other',
    'account a 1',
    'queued'
  ),
  (
    'f1000000-0000-0000-0000-000000000002',
    'e1000000-0000-0000-0000-000000000001',
    'https://example.test/account-a-2',
    'https://example.test/account-a-2',
    repeat('2', 64),
    'other',
    'account a 2',
    'queued'
  ),
  (
    'f1000000-0000-0000-0000-000000000003',
    'e1000000-0000-0000-0000-000000000003',
    'https://example.test/account-c-1',
    'https://example.test/account-c-1',
    repeat('3', 64),
    'other',
    'account c 1',
    'queued'
  );

insert into public.processing_jobs (
  id,
  owner_id,
  item_id,
  kind,
  target_revision,
  state,
  lease_until,
  lease_token
)
values
  (
    'fa000000-0000-0000-0000-000000000001',
    'e1000000-0000-0000-0000-000000000001',
    'f1000000-0000-0000-0000-000000000001',
    'metadata',
    1,
    'queued',
    null,
    null
  ),
  (
    'fa000000-0000-0000-0000-000000000002',
    'e1000000-0000-0000-0000-000000000001',
    'f1000000-0000-0000-0000-000000000002',
    'classify',
    1,
    'running',
    now() + interval '2 minutes',
    'fa100000-0000-0000-0000-000000000002'
  ),
  (
    'fa000000-0000-0000-0000-000000000003',
    'e1000000-0000-0000-0000-000000000003',
    'f1000000-0000-0000-0000-000000000003',
    'metadata',
    1,
    'queued',
    null,
    null
  );

insert into public.assets (
  id,
  owner_id,
  item_id,
  object_path,
  state,
  reserved_mime_type,
  reservation_expires_at
)
values (
  'fb000000-0000-0000-0000-000000000001',
  'e1000000-0000-0000-0000-000000000001',
  'f1000000-0000-0000-0000-000000000001',
  'e1000000-0000-0000-0000-000000000001/f1000000-0000-0000-0000-000000000001/fb000000-0000-0000-0000-000000000001',
  'reserved',
  'image/jpeg',
  now() + interval '15 minutes'
);

insert into storage.objects (id, bucket_id, name, metadata)
values (
  'fc000000-0000-0000-0000-000000000001',
  'library-images',
  'e1000000-0000-0000-0000-000000000001/f1000000-0000-0000-0000-000000000001/fb000000-0000-0000-0000-000000000001',
  '{"size":123,"mimetype":"image/jpeg"}'::jsonb
);

select ok(
  (
    select count(*) = 7
      and bool_and(
        case
          when namespace.nspname = 'public' then class.relrowsecurity
          else not class.relrowsecurity
        end
      )
    from pg_class as class
    join pg_namespace as namespace on namespace.oid = class.relnamespace
    where (namespace.nspname, class.relname) in (
      ('public', 'account_deletion_jobs'),
      ('private', 'auth_challenges'),
      ('private', 'request_id_claims'),
      ('private', 'deletion_ledger'),
      ('private', 'deletion_ledger_identity'),
      ('private', 'deletion_ledger_exports'),
      ('private', 'deletion_ledger_export_events')
    )
  ),
  'the public account job uses RLS while private deletion tables remain non-public surfaces'
);

select ok(
  (
    select class.relrowsecurity
    from pg_class as class
    where class.oid = 'public.account_deletion_jobs'::regclass
  ),
  'account deletion jobs enable RLS without a member policy'
);

select ok(
  not has_table_privilege('authenticated', 'public.account_deletion_jobs', 'SELECT')
  and not has_table_privilege('authenticated', 'private.auth_challenges', 'SELECT')
  and not has_table_privilege('authenticated', 'private.request_id_claims', 'SELECT')
  and not has_table_privilege('authenticated', 'private.deletion_ledger', 'SELECT')
  and not has_table_privilege('authenticated', 'private.deletion_ledger_exports', 'SELECT')
  and not has_table_privilege('authenticated', 'private.deletion_ledger_export_events', 'SELECT')
  and not has_table_privilege('service_role', 'public.account_deletion_jobs', 'SELECT')
  and not has_table_privilege('service_role', 'private.auth_challenges', 'SELECT'),
  'member and service roles have no direct deletion table access'
);

select ok(
  (
    select count(*) = 1
    from pg_trigger as installed_trigger
    where installed_trigger.tgrelid = 'public.api_requests'::regclass
      and installed_trigger.tgname = 'api_requests_claim_request_id'
      and not installed_trigger.tgisinternal
      and (installed_trigger.tgtype & 1) = 1
      and (installed_trigger.tgtype & 2) = 2
      and (installed_trigger.tgtype & 4) = 4
      and (installed_trigger.tgtype & 16) = 16
  ),
  'api_requests has a row-level BEFORE claim trigger for inserts and updates'
);

select ok(
  not exists (
    select 1
    from pg_constraint as constraint_record
    where constraint_record.conrelid in (
      'private.auth_challenges'::regclass,
      'public.account_deletion_jobs'::regclass
    )
      and constraint_record.contype = 'u'
      and pg_get_constraintdef(constraint_record.oid) in (
        'UNIQUE (request_id)',
        'UNIQUE(request_id)'
      )
  ),
  'challenge and account job request IDs are unique only within an owner'
);

select ok(
  (
    select count(*) = 13
      and bool_and(function.prosecdef)
      and bool_and(exists (
        select 1
        from unnest(function.proconfig) as setting
        where setting like 'search_path=%'
      ))
    from pg_proc as function
    join pg_namespace as namespace on namespace.oid = function.pronamespace
    where namespace.nspname = 'public'
      and function.proname in (
        'library_create_delete_challenge',
        'library_check_delete_challenge_binding',
        'library_replay_account_deletion',
        'library_accept_account_deletion',
        'library_claim_account_deletion_jobs',
        'library_prepare_account_deletion',
        'library_purge_account_deletion',
        'library_fail_account_deletion',
        'library_finalize_account_deletion',
        'library_export_deletion_ledger',
        'library_read_deletion_ledger_export',
        'library_ack_deletion_ledger_export',
        'library_run_retention'
      )
  ),
  'all account deletion RPCs are SECURITY DEFINER with fixed search paths'
);

select ok(
  has_function_privilege(
    'service_role',
    'public.library_accept_account_deletion(uuid,uuid,uuid,text)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated',
    'public.library_accept_account_deletion(uuid,uuid,uuid,text)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'public.library_accept_account_deletion(uuid,uuid,uuid,text)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated',
    'public.library_create_delete_challenge(uuid,uuid,text)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated',
    'public.library_claim_account_deletion_jobs(integer)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated',
    'public.library_export_deletion_ledger()',
    'EXECUTE'
  ),
  'challenge, acceptance, and cleanup RPCs are service-role only'
);

select is(
  (
    select count(*)::integer
    from pg_proc as function
    join pg_namespace as namespace on namespace.oid = function.pronamespace
    where namespace.nspname = 'public'
      and function.proname in (
        'library_create_delete_challenge',
        'library_accept_account_deletion'
      )
  ),
  2,
  'only the exact challenge and acceptance RPC names exist without legacy aliases'
);

select is(
  to_regprocedure(
    'public.library_set_deletion_ledger_export_watermark(timestamptz,uuid)'
  ),
  null,
  'caller-supplied timestamp watermark RPC has no legacy alias'
);

set local role service_role;

insert into account_deletion_test_state (key, value)
values ('zero-ledger-export', public.library_export_deletion_ledger());

select is(
  (select value ->> 'through_sequence' from account_deletion_test_state where key = 'zero-ledger-export'),
  '0',
  'an empty deletion journal exports with a zero frozen sequence bound'
);

select is(
  public.library_read_deletion_ledger_export(
    (
      select (value ->> 'export_id')::uuid
      from account_deletion_test_state
      where key = 'zero-ledger-export'
    )
  ),
  '{"events":[],"has_more":false,"next_sequence":0}'::jsonb,
  'an empty deletion journal has one complete empty page'
);

select is(
  public.library_ack_deletion_ledger_export(
    (
      select (value ->> 'export_id')::uuid
      from account_deletion_test_state
      where key = 'zero-ledger-export'
    )
  ) ->> 'acknowledged',
  'true',
  'empty journal export can be durably acknowledged'
);

reset role;

select is(
  (
    select (value ->> 'coverage_from')::timestamptz
    from account_deletion_test_state
    where key = 'zero-ledger-export'
  ),
  (
    select coverage_origin
    from private.deletion_ledger_identity
    where singleton
  ),
  'zero-event backup reports the persistent database coverage origin instead of export time'
);

set local role service_role;

insert into account_deletion_test_state (key, value)
values (
  'challenge-a',
  public.library_create_delete_challenge(
    'e1000000-0000-0000-0000-000000000001',
    'ed000000-0000-0000-0000-000000000001',
    repeat('a', 64)
  )
);

select is(
  (select value ->> 'http_status' from account_deletion_test_state where key = 'challenge-a'),
  '201',
  'challenge creation returns 201'
);

select ok(
  (
    select
      value ?& array['http_status', 'challenge_id', 'expires_at']
      and not (value ? 'nonce')
    from account_deletion_test_state
    where key = 'challenge-a'
  ),
  'challenge persistence response never contains the raw nonce'
);

reset role;

select is(
  (
    select expires_at - created_at
    from private.auth_challenges
    where id = (
      select (value ->> 'challenge_id')::uuid
      from account_deletion_test_state
      where key = 'challenge-a'
    )
  ),
  interval '5 minutes',
  'deletion challenge is valid for exactly five minutes'
);

select ok(
  not exists (
    select 1
    from information_schema.columns
    where table_schema = 'private'
      and table_name = 'auth_challenges'
      and column_name in ('nonce', 'google_id_token', 'raw_body')
  ),
  'challenge storage has no raw nonce, Google token, or request body column'
);

set local role service_role;

select is(
  public.library_create_delete_challenge(
    'e1000000-0000-0000-0000-000000000001',
    'ed000000-0000-0000-0000-000000000001',
    repeat('a', 64)
  ) ->> 'challenge_id',
  (select value ->> 'challenge_id' from account_deletion_test_state where key = 'challenge-a'),
  'same challenge request and nonce hash replay the challenge identity'
);

select is(
  public.library_create_delete_challenge(
    'e1000000-0000-0000-0000-000000000001',
    'ed000000-0000-0000-0000-000000000001',
    repeat('b', 64)
  ) ->> 'challenge_id',
  (select value ->> 'challenge_id' from account_deletion_test_state where key = 'challenge-a'),
  'challenge request identity is constant for empty JSON and never derived from nonce hash'
);

reset role;

insert into public.api_requests (
  owner_id,
  request_id,
  method_path,
  request_hash,
  response_code,
  response_body
)
values (
  'e1000000-0000-0000-0000-000000000006',
  'ed000000-0000-0000-0000-000000000060',
  'POST /items',
  repeat('6', 64),
  201,
  '{"item_id":"f1000000-0000-0000-0000-000000000060","duplicate":false,"http_status":201}'::jsonb
);

set local role service_role;

select throws_ok(
  $$
    select public.library_create_delete_challenge(
      'e1000000-0000-0000-0000-000000000006',
      'ed000000-0000-0000-0000-000000000060',
      repeat('6', 64)
    )
  $$,
  'P0001',
  'IDEMPOTENCY_MISMATCH',
  'an item request claim rejects challenge reuse of the same owner request UUID'
);

select throws_ok(
  $$
    select public.library_replay_account_deletion(
      'e1000000-0000-0000-0000-000000000006',
      'ed000000-0000-0000-0000-000000000060',
      repeat('6', 64)
    )
  $$,
  'P0001',
  'IDEMPOTENCY_MISMATCH',
  'account replay rejects another route before verifying fresh Google proof'
);

insert into account_deletion_test_state (key, value)
values (
  'challenge-cross-route',
  public.library_create_delete_challenge(
    'e1000000-0000-0000-0000-000000000006',
    'ed000000-0000-0000-0000-000000000061',
    repeat('6', 64)
  )
);

reset role;

select throws_ok(
  $$
    insert into public.api_requests (
      owner_id,
      request_id,
      method_path,
      request_hash,
      response_code,
      response_body
    ) values (
      'e1000000-0000-0000-0000-000000000006',
      'ed000000-0000-0000-0000-000000000061',
      'POST /items',
      repeat('6', 64),
      201,
      '{"item_id":"f1000000-0000-0000-0000-000000000061","duplicate":false,"http_status":201}'::jsonb
    )
  $$,
  'P0001',
  'IDEMPOTENCY_MISMATCH',
  'a challenge claim rejects a later item receipt with the same owner request UUID'
);

set local role service_role;

select throws_ok(
  format(
    'select public.library_accept_account_deletion(%L::uuid,%L::uuid,%L::uuid,%L)',
    'e1000000-0000-0000-0000-000000000006',
    'ed000000-0000-0000-0000-000000000061',
    (
      select value ->> 'challenge_id'
      from account_deletion_test_state
      where key = 'challenge-cross-route'
    ),
    repeat('6', 64)
  ),
  'P0001',
  'IDEMPOTENCY_MISMATCH',
  'a challenge request claim rejects account deletion reuse of the same owner UUID'
);

insert into account_deletion_test_state (key, value)
values (
  'challenge-item-delete-conflict',
  public.library_create_delete_challenge(
    'e1000000-0000-0000-0000-000000000006',
    'ed000000-0000-0000-0000-000000000062',
    repeat('7', 64)
  )
);

reset role;

insert into public.api_requests (
  owner_id,
  request_id,
  method_path,
  request_hash,
  response_code,
  response_body
)
values (
  'e1000000-0000-0000-0000-000000000006',
  'ee000000-0000-0000-0000-000000000063',
  'POST /items',
  repeat('7', 64),
  201,
  '{"item_id":"f1000000-0000-0000-0000-000000000063","duplicate":false,"http_status":201}'::jsonb
);

set local role service_role;

select throws_ok(
  format(
    'select public.library_accept_account_deletion(%L::uuid,%L::uuid,%L::uuid,%L)',
    'e1000000-0000-0000-0000-000000000006',
    'ee000000-0000-0000-0000-000000000063',
    (
      select value ->> 'challenge_id'
      from account_deletion_test_state
      where key = 'challenge-item-delete-conflict'
    ),
    repeat('7', 64)
  ),
  'P0001',
  'IDEMPOTENCY_MISMATCH',
  'an item request claim rejects account deletion reuse after owner-domain serialization'
);

reset role;

select ok(
  exists (
    select 1
    from public.profiles
    where id = 'e1000000-0000-0000-0000-000000000006'
      and state = 'active'
      and deletion_requested_at is null
  )
  and exists (
    select 1
    from public.library_usage
    where owner_id = 'e1000000-0000-0000-0000-000000000006'
      and active_item_count = 0
  )
  and not exists (
    select 1
    from public.account_deletion_jobs
    where owner_id = 'e1000000-0000-0000-0000-000000000006'
  )
  and exists (
    select 1
    from private.auth_challenges
    where id = (
      select (value ->> 'challenge_id')::uuid
      from account_deletion_test_state
      where key = 'challenge-cross-route'
    )
      and used_at is null
  )
  and exists (
    select 1
    from private.auth_challenges
    where id = (
      select (value ->> 'challenge_id')::uuid
      from account_deletion_test_state
      where key = 'challenge-item-delete-conflict'
    )
      and used_at is null
  ),
  'cross-route mismatches roll back without profile, usage, job, or challenge mutation'
);

set local role service_role;

insert into account_deletion_test_state (key, value)
values
  (
    'challenge-owner-isolation-a',
    public.library_create_delete_challenge(
      'e1000000-0000-0000-0000-000000000004',
      'ed000000-0000-0000-0000-000000000099',
      repeat('4', 64)
    )
  ),
  (
    'challenge-owner-isolation-b',
    public.library_create_delete_challenge(
      'e1000000-0000-0000-0000-000000000005',
      'ed000000-0000-0000-0000-000000000099',
      repeat('5', 64)
    )
  );

select isnt(
  (
    select value ->> 'challenge_id'
    from account_deletion_test_state
    where key = 'challenge-owner-isolation-a'
  ),
  (
    select value ->> 'challenge_id'
    from account_deletion_test_state
    where key = 'challenge-owner-isolation-b'
  ),
  'different owners may independently use the same request UUID'
);

select is(
  public.library_replay_account_deletion(
    'e1000000-0000-0000-0000-000000000001',
    'ee000000-0000-0000-0000-000000000001',
    repeat('1', 64)
  ),
  null,
  'replay lookup returns null before fresh identity verification and acceptance'
);

select throws_ok(
  format(
    'select public.library_check_delete_challenge_binding(%L::uuid,%L::uuid,%L)',
    'e1000000-0000-0000-0000-000000000004',
    (select value ->> 'challenge_id' from account_deletion_test_state where key = 'challenge-a'),
    repeat('a', 64)
  ),
  'P0001',
  'DELETE_CHALLENGE_INVALID',
  'a challenge cannot be checked under a different owner'
);

select throws_ok(
  format(
    'select public.library_check_delete_challenge_binding(%L::uuid,%L::uuid,%L)',
    'e1000000-0000-0000-0000-000000000001',
    (select value ->> 'challenge_id' from account_deletion_test_state where key = 'challenge-a'),
    repeat('b', 64)
  ),
  'P0001',
  'DELETE_CHALLENGE_INVALID',
  'a verified nonce claim must hash to the stored challenge binding'
);

select is(
  public.library_check_delete_challenge_binding(
    'e1000000-0000-0000-0000-000000000001',
    (select (value ->> 'challenge_id')::uuid from account_deletion_test_state where key = 'challenge-a'),
    repeat('a', 64)
  ),
  '{"http_status":200,"state":"valid"}'::jsonb,
  'the owner-bound unexpired nonce binding is valid'
);

insert into account_deletion_test_state (key, value)
values (
  'challenge-expired',
  public.library_create_delete_challenge(
    'e1000000-0000-0000-0000-000000000005',
    'ed000000-0000-0000-0000-000000000005',
    repeat('e', 64)
  )
);

reset role;

update private.auth_challenges
set created_at = now() - interval '2 hours',
    expires_at = now() - interval '1 hour'
where id = (
  select (value ->> 'challenge_id')::uuid
  from account_deletion_test_state
  where key = 'challenge-expired'
);

set local role service_role;

select throws_ok(
  format(
    'select public.library_check_delete_challenge_binding(%L::uuid,%L::uuid,%L)',
    'e1000000-0000-0000-0000-000000000005',
    (select value ->> 'challenge_id' from account_deletion_test_state where key = 'challenge-expired'),
    repeat('e', 64)
  ),
  'P0001',
  'DELETE_CHALLENGE_EXPIRED',
  'expired challenges cannot authorize deletion'
);

select is(
  public.library_accept_account_deletion(
    'e1000000-0000-0000-0000-000000000001',
    'ee000000-0000-0000-0000-000000000001',
    (select (value ->> 'challenge_id')::uuid from account_deletion_test_state where key = 'challenge-a'),
    repeat('1', 64)
  ),
  '{"http_status":202,"state":"deleting"}'::jsonb,
  'fresh verified challenge acceptance returns deleting'
);

reset role;

select throws_ok(
  $$
    insert into public.api_requests (
      owner_id, request_id, method_path, request_hash, response_code, response_body
    ) values (
      'e1000000-0000-0000-0000-000000000001',
      'ee000000-0000-0000-0000-000000000001',
      'POST /items', repeat('1', 64), 201,
      '{"item_id":"f1000000-0000-0000-0000-000000000064","duplicate":false,"http_status":201}'::jsonb
    )
  $$,
  'P0001',
  'IDEMPOTENCY_MISMATCH',
  'an accepted account deletion claim rejects later item receipt reuse'
);

set local role service_role;

select throws_ok(
  $$
    select public.library_create_delete_challenge(
      'e1000000-0000-0000-0000-000000000001',
      'ee000000-0000-0000-0000-000000000001',
      repeat('1', 64)
    )
  $$,
  'P0001',
  'IDEMPOTENCY_MISMATCH',
  'an accepted account deletion claim rejects later challenge reuse'
);

select is(
  public.library_replay_account_deletion(
    'e1000000-0000-0000-0000-000000000001',
    'ee000000-0000-0000-0000-000000000001',
    repeat('1', 64)
  ),
  '{"http_status":202,"state":"deleting"}'::jsonb,
  'lost 202 is replayed before a nonce or Google token is reused'
);

select is(
  public.library_accept_account_deletion(
    'e1000000-0000-0000-0000-000000000001',
    'ee000000-0000-0000-0000-000000000001',
    (select (value ->> 'challenge_id')::uuid from account_deletion_test_state where key = 'challenge-a'),
    repeat('1', 64)
  ),
  '{"http_status":202,"state":"deleting"}'::jsonb,
  'acceptance itself replays before inspecting a consumed challenge'
);

select throws_ok(
  $$
    select public.library_replay_account_deletion(
      'e1000000-0000-0000-0000-000000000001',
      'ee000000-0000-0000-0000-000000000001',
      repeat('2', 64)
    )
  $$,
  'P0001',
  'IDEMPOTENCY_MISMATCH',
  'changed canonical request body hash remains a sticky mismatch'
);

select throws_ok(
  format(
    'select public.library_check_delete_challenge_binding(%L::uuid,%L::uuid,%L)',
    'e1000000-0000-0000-0000-000000000001',
    (select value ->> 'challenge_id' from account_deletion_test_state where key = 'challenge-a'),
    repeat('a', 64)
  ),
  'P0001',
  'DELETE_CHALLENGE_USED',
  'accepted challenge is one-use'
);

reset role;

select is(
  (select state from public.profiles where id = 'e1000000-0000-0000-0000-000000000001'),
  'deleting',
  'acceptance atomically fences the profile as deleting'
);

select ok(
  not (select enabled from public.beta_members where owner_id = 'e1000000-0000-0000-0000-000000000001'),
  'acceptance disables an existing beta membership'
);

select is(
  (select active_item_count from public.library_usage where owner_id = 'e1000000-0000-0000-0000-000000000001'),
  1,
  'active count decrements by exactly the two newly deleted items instead of being faked to zero'
);

select is(
  (select count(*)::integer from public.items where owner_id = 'e1000000-0000-0000-0000-000000000001' and deleted_at is null),
  0,
  'all account items are immediately marked deleted'
);

select ok(
  (
    select bool_and(
      original_url is null
      and normalized_url is null
      and shared_text is null
      and note is null
      and body_text is null
    )
    from public.items
    where owner_id = 'e1000000-0000-0000-0000-000000000001'
  ),
  'account acceptance scrubs item content before durable cleanup'
);

select ok(
  (
    select count(*) = 2
      and bool_and(state = 'cancelled')
      and bool_and(lease_until is null)
      and bool_and(lease_token is null)
    from public.processing_jobs
    where owner_id = 'e1000000-0000-0000-0000-000000000001'
  ),
  'all live processing work is cancelled and leased execution is fenced'
);

select is(
  (
    select pg_catalog.jsonb_build_object(
      'state', state,
      'cleanup_reason', cleanup_reason,
      'ocr_text', ocr_text
    )
    from public.assets
    where owner_id = 'e1000000-0000-0000-0000-000000000001'
  ),
  '{"state":"deleting","cleanup_reason":"account_delete","ocr_text":null}'::jsonb,
  'assets become deleting and content-free while their bytes remain reserved'
);

select is(
  (select reserved_image_bytes from public.library_usage where owner_id = 'e1000000-0000-0000-0000-000000000001'),
  2000000::bigint,
  'image quota remains charged until physical object absence is proven'
);

select is(
  (select count(*)::integer from private.item_deletion_tombstones where owner_id = 'e1000000-0000-0000-0000-000000000001'),
  2,
  'account acceptance records one minimal item tombstone per newly deleted item'
);

set local role authenticated;
set local "request.jwt.claim.sub" = 'e1000000-0000-0000-0000-000000000001';

select is(public.member_me(), '{"state":"deleting"}'::jsonb, 'old JWT receives only minimal deleting state');
select is((select count(*)::integer from public.items), 0, 'old JWT cannot read deleted account items through RLS');
select is((select count(*)::integer from public.assets), 0, 'old JWT cannot read deleting assets through RLS');
select throws_ok(
  $$select public.member_bootstrap('ef000000-0000-0000-0000-000000000001')$$,
  'P0001',
  'ACCOUNT_DELETING',
  'old JWT cannot bootstrap the fenced account'
);
select throws_ok(
  $$
    select public.library_accept_account_deletion(
      'e1000000-0000-0000-0000-000000000001',
      'ee000000-0000-0000-0000-000000000001',
      'ed000000-0000-0000-0000-000000000001',
      repeat('1', 64)
    )
  $$,
  '42501',
  null,
  'authenticated clients cannot bypass Edge Google verification by calling acceptance SQL'
);

reset role;
reset "request.jwt.claim.sub";

select is(
  (select count(*)::integer from public.profiles where id = 'e1000000-0000-0000-0000-000000000002'),
  0,
  'pending unapproved account starts with no profile'
);

set local role service_role;

insert into account_deletion_test_state (key, value)
values
  (
    'challenge-b',
    public.library_create_delete_challenge(
      'e1000000-0000-0000-0000-000000000002',
      'ed000000-0000-0000-0000-000000000002',
      repeat('b', 64)
    )
  ),
  (
    'challenge-c',
    public.library_create_delete_challenge(
      'e1000000-0000-0000-0000-000000000003',
      'ed000000-0000-0000-0000-000000000003',
      repeat('c', 64)
    )
  );

select is(
  public.library_accept_account_deletion(
    'e1000000-0000-0000-0000-000000000002',
    'ee000000-0000-0000-0000-000000000002',
    (select (value ->> 'challenge_id')::uuid from account_deletion_test_state where key = 'challenge-b'),
    repeat('2', 64)
  ) ->> 'state',
  'deleting',
  'unapproved account with no profile can accept account deletion'
);

select is(
  public.library_accept_account_deletion(
    'e1000000-0000-0000-0000-000000000003',
    'ee000000-0000-0000-0000-000000000002',
    (select (value ->> 'challenge_id')::uuid from account_deletion_test_state where key = 'challenge-c'),
    repeat('3', 64)
  ) ->> 'state',
  'deleting',
  'revoked beta member can accept account deletion'
);

reset role;

select is(
  (
    select count(*)::integer
    from public.account_deletion_jobs
    where request_id = 'ee000000-0000-0000-0000-000000000002'
      and owner_id in (
        'e1000000-0000-0000-0000-000000000002',
        'e1000000-0000-0000-0000-000000000003'
      )
  ),
  2,
  'account deletion request UUID uniqueness is owner scoped'
);

select ok(
  exists (
    select 1
    from public.profiles
    where id = 'e1000000-0000-0000-0000-000000000002'
      and state = 'deleting'
      and deletion_requested_at is not null
  )
  and not exists (
    select 1
    from public.library_usage
    where owner_id = 'e1000000-0000-0000-0000-000000000002'
  ),
  'pending account receives only the minimal deleting profile, not bootstrap business rows'
);

select ok(
  exists (
    select 1
    from public.account_deletion_jobs
    where owner_id = 'e1000000-0000-0000-0000-000000000003'
      and state = 'queued'
  ),
  'revoked owner receives a durable cleanup job'
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
  'e1000000-0000-0000-0000-000000000003',
  'ef000000-0000-0000-0000-000000000030',
  'DELETE /items/f1000000-0000-0000-0000-000000000030',
  repeat('3', 64),
  202,
  '{"item_id":"f1000000-0000-0000-0000-000000000030","http_status":202,"state":"deleting"}'::jsonb
);
insert into private.item_deletion_tombstones (owner_id, item_id, deleted_at)
values (
  'e1000000-0000-0000-0000-000000000003',
  'f1000000-0000-0000-0000-000000000030',
  now()
);

select is(
  (
    select request_id
    from private.deletion_ledger
    where kind = 'item'
      and owner_id = 'e1000000-0000-0000-0000-000000000003'
      and item_id = 'f1000000-0000-0000-0000-000000000030'
  ),
  'ef000000-0000-0000-0000-000000000030'::uuid,
  'deferred item tombstone trigger unifies the committed item request in the deletion ledger'
);

update public.account_deletion_jobs
set next_run_at = now() + interval '1 day'
where owner_id in (
  'e1000000-0000-0000-0000-000000000002',
  'e1000000-0000-0000-0000-000000000003'
);
update public.account_deletion_jobs
set next_run_at = now() - interval '1 minute'
where owner_id = 'e1000000-0000-0000-0000-000000000001';

set local role service_role;

select throws_ok(
  'select public.library_claim_account_deletion_jobs(11)',
  'P0001',
  'INVALID_LIMIT',
  'account cleanup claim is capped at ten jobs'
);

insert into account_deletion_test_state (key, value)
values ('claim-a-1', public.library_claim_account_deletion_jobs(10));

select is(
  jsonb_array_length((select value -> 'jobs' from account_deletion_test_state where key = 'claim-a-1')),
  1,
  'claim acquires only the ready cleanup job despite deleting profile and disabled beta access'
);

select is(
  public.library_prepare_account_deletion(
    'e1000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000000'
  ),
  '{"http_status":409,"error_code":"LEASE_LOST"}'::jsonb,
  'prepare rejects a stale worker lease'
);

insert into account_deletion_test_state (key, value)
values (
  'prepare-a-1',
  public.library_prepare_account_deletion(
    'e1000000-0000-0000-0000-000000000001',
    (select (value #>> '{jobs,0,lease_token}')::uuid from account_deletion_test_state where key = 'claim-a-1')
  )
);

select is(
  (select value ->> 'object_count' from account_deletion_test_state where key = 'prepare-a-1'),
  '1',
  'prepare returns the exact registered Storage object for Storage API deletion'
);

reset role;

insert into storage.buckets (id, name, public)
values ('account-delete-foreign', 'account-delete-foreign', false);
insert into storage.objects (id, bucket_id, name, metadata)
values (
  'fc000000-0000-0000-0000-000000000002',
  'account-delete-foreign',
  'e1000000-0000-0000-0000-000000000001/unowned-object',
  '{}'::jsonb
);

set local role service_role;

select is(
  public.library_purge_account_deletion(
    'e1000000-0000-0000-0000-000000000001',
    (select (value #>> '{jobs,0,lease_token}')::uuid from account_deletion_test_state where key = 'claim-a-1')
  ) ->> 'error_code',
  'STORAGE_OWNERSHIP_CONFLICT',
  'foreign-bucket owner-path collision fails closed instead of deleting an unrelated object'
);

reset role;
set local storage.allow_delete_query = 'true';
delete from storage.objects
where id = 'fc000000-0000-0000-0000-000000000002';
delete from storage.buckets
where id = 'account-delete-foreign';

set local role service_role;

select is(
  public.library_purge_account_deletion(
    'e1000000-0000-0000-0000-000000000001',
    (select (value #>> '{jobs,0,lease_token}')::uuid from account_deletion_test_state where key = 'claim-a-1')
  ) ->> 'error_code',
  'STORAGE_OBJECTS_PRESENT',
  'business purge cannot run before the Storage API removes registered objects'
);

reset role;

select ok(
  exists (select 1 from public.items where owner_id = 'e1000000-0000-0000-0000-000000000001')
  and exists (select 1 from public.library_usage where owner_id = 'e1000000-0000-0000-0000-000000000001'),
  'failed physical cleanup leaves scrubbed business rows resumable'
);

set local storage.allow_delete_query = 'true';
delete from storage.objects
where id = 'fc000000-0000-0000-0000-000000000001';

set local role service_role;

insert into account_deletion_test_state (key, value)
values (
  'purge-a',
  public.library_purge_account_deletion(
    'e1000000-0000-0000-0000-000000000001',
    (select (value #>> '{jobs,0,lease_token}')::uuid from account_deletion_test_state where key = 'claim-a-1')
  )
);

select is(
  (select value ->> 'state' from account_deletion_test_state where key = 'purge-a'),
  'ready_for_auth_delete',
  'business purge begins only after physical Storage absence'
);

select is(
  (select value ->> 'released_reserved_bytes' from account_deletion_test_state where key = 'purge-a'),
  '2000000',
  'purge releases the exact reserved byte count after object absence'
);

select is(
  public.library_finalize_account_deletion(
    'e1000000-0000-0000-0000-000000000001',
    (select (value #>> '{jobs,0,lease_token}')::uuid from account_deletion_test_state where key = 'claim-a-1')
  ) ->> 'error_code',
  'AUTH_USER_PRESENT',
  'finalization cannot precede external Auth admin deletion'
);

select is(
  public.library_fail_account_deletion(
    'e1000000-0000-0000-0000-000000000001',
    (select (value #>> '{jobs,0,lease_token}')::uuid from account_deletion_test_state where key = 'claim-a-1'),
    'ACCOUNT_AUTH_DELETE_FAILED'
  ) ->> 'state',
  'retry',
  'Auth cleanup failure is durably rescheduled rather than abandoned'
);

select is(
  public.library_prepare_account_deletion(
    'e1000000-0000-0000-0000-000000000001',
    (select (value #>> '{jobs,0,lease_token}')::uuid from account_deletion_test_state where key = 'claim-a-1')
  ) ->> 'error_code',
  'LEASE_LOST',
  'failed job clears the old lease token'
);

reset role;

select ok(
  not exists (select 1 from public.items where owner_id = 'e1000000-0000-0000-0000-000000000001')
  and not exists (select 1 from public.assets where owner_id = 'e1000000-0000-0000-0000-000000000001')
  and not exists (select 1 from public.library_usage where owner_id = 'e1000000-0000-0000-0000-000000000001')
  and not exists (select 1 from public.beta_members where owner_id = 'e1000000-0000-0000-0000-000000000001')
  and exists (
    select 1
    from public.profiles
    where id = 'e1000000-0000-0000-0000-000000000001'
      and state = 'deleting'
  ),
  'purge removes business data but keeps the deleting profile until Auth deletion'
);

select is(
  (
    select released_reserved_bytes
    from private.asset_cleanup_receipts
    where asset_id = 'fb000000-0000-0000-0000-000000000001'
  ),
  2000000::bigint,
  'asset cleanup receipt records exact quota release without content'
);

update public.account_deletion_jobs
set next_run_at = now() - interval '1 minute'
where owner_id = 'e1000000-0000-0000-0000-000000000001';

set local role service_role;
insert into account_deletion_test_state (key, value)
values ('claim-a-2', public.library_claim_account_deletion_jobs(1));

select is(
  (select value #>> '{jobs,0,business_purged}' from account_deletion_test_state where key = 'claim-a-2'),
  'true',
  'retry claim resumes from the durable business-purged phase'
);

reset role;

delete from auth.users
where id = 'e1000000-0000-0000-0000-000000000001';

select ok(
  not exists (select 1 from auth.users where id = 'e1000000-0000-0000-0000-000000000001')
  and not exists (select 1 from public.profiles where id = 'e1000000-0000-0000-0000-000000000001'),
  'external Auth deletion cascades the final deleting profile'
);

set local role service_role;

select is(
  public.library_finalize_account_deletion(
    'e1000000-0000-0000-0000-000000000001',
    (select (value #>> '{jobs,0,lease_token}')::uuid from account_deletion_test_state where key = 'claim-a-2')
  ),
  '{"http_status":200,"state":"complete"}'::jsonb,
  'finalize marks the independent job complete only after Auth absence'
);

select is(
  public.library_finalize_account_deletion(
    'e1000000-0000-0000-0000-000000000001',
    (select (value #>> '{jobs,0,lease_token}')::uuid from account_deletion_test_state where key = 'claim-a-2')
  ),
  '{"http_status":200,"state":"complete"}'::jsonb,
  'finalize replay with the completing lease is idempotent'
);

select is(
  public.library_finalize_account_deletion(
    'e1000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000000'
  ) ->> 'error_code',
  'LEASE_LOST',
  'completed job rejects a different stale lease'
);

reset role;

select ok(
  exists (
    select 1
    from public.account_deletion_jobs
    where owner_id = 'e1000000-0000-0000-0000-000000000001'
      and state = 'complete'
      and completed_at is not null
  ),
  'completed account job survives Auth deletion without an Auth foreign key'
);

select is(
  (
    select array_agg(column_name::text order by ordinal_position)
    from information_schema.columns
    where table_schema = 'private'
      and table_name = 'deletion_ledger'
  ),
  array[
    'sequence',
    'event_id',
    'kind',
    'owner_id',
    'item_id',
    'request_id',
    'requested_at'
  ]::text[],
  'deletion ledger contains only sequence, minimal identities, kind, and request time'
);

select ok(
  (
    select count(*) = 3
      and count(*) filter (where kind = 'account' and item_id is null) = 1
      and count(*) filter (where kind = 'item' and item_id is not null) = 2
      and bool_and(request_id = 'ee000000-0000-0000-0000-000000000001')
      and bool_and(
        kind not like '%token%'
        and kind not like '%nonce%'
        and kind not like '%url%'
        and kind not like '%note%'
        and kind not like '%ocr%'
      )
    from private.deletion_ledger
    where owner_id = 'e1000000-0000-0000-0000-000000000001'
  ),
  'durable ledger records unified item and account requests without content or credentials'
);

insert into public.api_requests (
  owner_id,
  request_id,
  method_path,
  request_hash,
  response_code,
  response_body,
  created_at
)
values (
  'e1000000-0000-0000-0000-000000000003',
  'ef000000-0000-0000-0000-000000000003',
  'POST /items',
  repeat('f', 64),
  201,
  '{"item_id":"f1000000-0000-0000-0000-000000000003","duplicate":false,"http_status":201}'::jsonb,
  now() - interval '8 days'
);

insert into public.api_rate_buckets (
  owner_id,
  operation,
  window_start,
  request_count
)
values (
  'e1000000-0000-0000-0000-000000000003',
  'create_item',
  date_trunc('minute', now() - interval '2 days'),
  1
);

update private.item_deletion_tombstones
set deleted_at = now() - interval '31 days'
where owner_id = 'e1000000-0000-0000-0000-000000000003'
  and item_id = 'f1000000-0000-0000-0000-000000000003';

update public.processing_jobs
set updated_at = now() - interval '31 days'
where owner_id = 'e1000000-0000-0000-0000-000000000003'
  and state = 'cancelled';

insert into private.asset_cleanup_receipts (
  asset_id,
  owner_id,
  item_id,
  lease_token,
  released_reserved_bytes,
  released_used_bytes,
  completed_at
)
values (
  'fb000000-0000-0000-0000-000000000099',
  'e1000000-0000-0000-0000-000000000003',
  'f1000000-0000-0000-0000-000000000099',
  'fb100000-0000-0000-0000-000000000099',
  0,
  0,
  now() - interval '31 days'
);

update public.account_deletion_jobs
set requested_at = now() - interval '41 days',
    business_purged_at = now() - interval '40 days',
    completed_at = now() - interval '39 days',
    next_run_at = now() - interval '39 days'
where owner_id = 'e1000000-0000-0000-0000-000000000001';
update private.deletion_ledger
set requested_at = now() - interval '40 days'
where owner_id = 'e1000000-0000-0000-0000-000000000001';
update private.item_deletion_tombstones
set deleted_at = now() - interval '40 days'
where owner_id = 'e1000000-0000-0000-0000-000000000001';

insert into private.deletion_ledger (
  kind,
  owner_id,
  item_id,
  request_id,
  requested_at
)
values (
  'account',
  'e1000000-0000-0000-0000-000000000004',
  null,
  'ee000000-0000-0000-0000-000000000044',
  now() - interval '40 days'
);

update private.deletion_ledger_identity
set coverage_origin = now() - interval '60 days'
where singleton;

set local role service_role;

insert into account_deletion_test_state (key, value)
values ('ledger-export-a', public.library_export_deletion_ledger());

select ok(
  (
    select value ?& array[
      'database_id',
      'export_id',
      'coverage_from',
      'exported_through',
      'through_sequence'
    ]
      and (select count(*) from jsonb_object_keys(value)) = 5
      and (value ->> 'through_sequence')::bigint > 0
    from account_deletion_test_state
    where key = 'ledger-export-a'
  ),
  'database-created full export fixes identity, coverage, time, and sequence upper bound'
);

insert into account_deletion_test_state (key, value)
values ('ledger-export-unacked', public.library_export_deletion_ledger());

select isnt(
  (select value ->> 'export_id' from account_deletion_test_state where key = 'ledger-export-unacked'),
  (select value ->> 'export_id' from account_deletion_test_state where key = 'ledger-export-a'),
  'a failed unacknowledged backup never blocks creation of a fresh full export'
);

select throws_ok(
  format(
    'select public.library_read_deletion_ledger_export(%L::uuid,0,1001)',
    (select value ->> 'export_id' from account_deletion_test_state where key = 'ledger-export-a')
  ),
  'P0001',
  'INVALID_LIMIT',
  'ledger export page size is capped at one thousand events'
);

insert into account_deletion_test_state (key, value)
values (
  'ledger-page-a',
  public.library_read_deletion_ledger_export(
    (select (value ->> 'export_id')::uuid from account_deletion_test_state where key = 'ledger-export-a'),
    0,
    1000
  )
);

select ok(
  (
    select
      value ?& array['events', 'next_sequence', 'has_more']
      and (select count(*) from jsonb_object_keys(value)) = 3
      and (value ->> 'has_more')::boolean is false
      and (value ->> 'next_sequence')::bigint = (
        select (export.value ->> 'through_sequence')::bigint
        from account_deletion_test_state as export
        where export.key = 'ledger-export-a'
      )
      and not exists (
        select 1
        from jsonb_array_elements(value -> 'events') as event(value)
        where not (event.value ?& array[
          'sequence',
          'event_id',
          'kind',
          'owner_id',
          'item_id',
          'request_id',
          'requested_at'
        ])
          or (select count(*) from jsonb_object_keys(event.value)) <> 7
      )
    from account_deletion_test_state
    where key = 'ledger-page-a'
  ),
  'paged export returns exact minimal frozen events through the sequence bound'
);

select throws_ok(
  $$
    select public.library_ack_deletion_ledger_export(
      'ef000000-0000-0000-0000-000000000099'
    )
  $$,
  'P0001',
  'DELETION_LEDGER_EXPORT_NOT_FOUND',
  'acknowledgement cannot invent an export range'
);

select is(
  public.library_ack_deletion_ledger_export(
    (select (value ->> 'export_id')::uuid from account_deletion_test_state where key = 'ledger-export-a')
  ) ->> 'acknowledged',
  'true',
  'verified encrypted export acknowledgement confirms only the stored range'
);

select is(
  public.library_ack_deletion_ledger_export(
    (select (value ->> 'export_id')::uuid from account_deletion_test_state where key = 'ledger-export-a')
  ) ->> 'acknowledged',
  'true',
  'export acknowledgement is idempotent'
);

reset role;

select is(
  (select value ->> 'database_id' from account_deletion_test_state where key = 'ledger-export-a'),
  (select database_id::text from private.deletion_ledger_identity where singleton),
  'export uses the persistent database identity'
);

insert into private.deletion_ledger (
  kind,
  owner_id,
  item_id,
  request_id,
  requested_at
)
values (
  'item',
  'e1000000-0000-0000-0000-000000000004',
  'f1000000-0000-0000-0000-000000000045',
  'ee000000-0000-0000-0000-000000000045',
  now() - interval '31 days'
);
insert into private.item_deletion_tombstones (owner_id, item_id, deleted_at)
values (
  'e1000000-0000-0000-0000-000000000004',
  'f1000000-0000-0000-0000-000000000045',
  now() - interval '31 days'
);

set local role service_role;

insert into account_deletion_test_state (key, value)
values ('ledger-export-b', public.library_export_deletion_ledger());

select isnt(
  (select value ->> 'export_id' from account_deletion_test_state where key = 'ledger-export-b'),
  (select value ->> 'export_id' from account_deletion_test_state where key = 'ledger-export-a'),
  'an acknowledged export is followed by a new full export identity'
);

insert into account_deletion_test_state (key, value)
values (
  'ledger-page-b',
  public.library_read_deletion_ledger_export(
    (select (value ->> 'export_id')::uuid from account_deletion_test_state where key = 'ledger-export-b'),
    0,
    1000
  )
);

select ok(
  (
    select
      not exists (
        select 1
        from account_deletion_test_state as old_page
        cross join lateral jsonb_array_elements(old_page.value -> 'events') as old_event(value)
        where old_page.key = 'ledger-page-a'
          and not (
            page.value -> 'events'
            @> pg_catalog.jsonb_build_array(old_event.value)
          )
      )
      and page.value -> 'events' @> '[{
        "kind":"item",
        "owner_id":"e1000000-0000-0000-0000-000000000004",
        "item_id":"f1000000-0000-0000-0000-000000000045"
      }]'::jsonb
    from account_deletion_test_state as page
    where page.key = 'ledger-page-b'
  ),
  'new export contains both earlier retained events and a later deletion event'
);

select is(
  public.library_ack_deletion_ledger_export(
    (select (value ->> 'export_id')::uuid from account_deletion_test_state where key = 'ledger-export-b')
  ) ->> 'acknowledged',
  'true',
  'new full journal export can be acknowledged independently'
);

reset role;

update private.deletion_ledger_exports
set created_at = now() - interval '31 days'
where export_id in (
  (
    select (value ->> 'export_id')::uuid
    from account_deletion_test_state
    where key = 'zero-ledger-export'
  ),
  (
    select (value ->> 'export_id')::uuid
    from account_deletion_test_state
    where key = 'ledger-export-unacked'
  )
);

insert into private.deletion_ledger (
  kind,
  owner_id,
  item_id,
  request_id,
  requested_at
)
values (
  'item',
  'e1000000-0000-0000-0000-000000000005',
  'f1000000-0000-0000-0000-000000000046',
  'ee000000-0000-0000-0000-000000000046',
  now() - interval '31 days'
);
insert into private.item_deletion_tombstones (owner_id, item_id, deleted_at)
values (
  'e1000000-0000-0000-0000-000000000005',
  'f1000000-0000-0000-0000-000000000046',
  now() - interval '31 days'
);

set local role service_role;

select is(
  public.library_read_deletion_ledger_export(
    (select (value ->> 'export_id')::uuid from account_deletion_test_state where key = 'ledger-export-a'),
    0,
    1000
  ),
  (select value from account_deletion_test_state where key = 'ledger-page-a'),
  'new inserts cannot change a previously frozen export snapshot'
);

insert into account_deletion_test_state (key, value)
values ('retention', public.library_run_retention(now()));

select is((select (value ->> 'deleted_api_requests')::integer from account_deletion_test_state where key = 'retention'), 1, 'retention removes API receipts older than seven days');
select is((select (value ->> 'deleted_rate_buckets')::integer from account_deletion_test_state where key = 'retention'), 1, 'retention removes rate buckets older than one day');
select is((select (value ->> 'deleted_challenges')::integer from account_deletion_test_state where key = 'retention'), 0, 'retention preserves expired challenge identity for seven days');
select is((select (value ->> 'deleted_item_tombstones')::integer from account_deletion_test_state where key = 'retention'), 4, 'retention removes acknowledged item tombstones after thirty days');
select is((select (value ->> 'deleted_cancelled_jobs')::integer from account_deletion_test_state where key = 'retention'), 1, 'retention removes cancelled processing jobs after thirty days');
select is((select (value ->> 'deleted_account_jobs')::integer from account_deletion_test_state where key = 'retention'), 1, 'retention removes exported completed account jobs after thirty days');
select is((select (value ->> 'deleted_ledger_events')::integer from account_deletion_test_state where key = 'retention'), 5, 'retention removes only old ledger events covered by an acknowledged immutable export');

select is(
  public.library_read_deletion_ledger_export(
    (select (value ->> 'export_id')::uuid from account_deletion_test_state where key = 'ledger-export-a'),
    0,
    1000
  ),
  (select value from account_deletion_test_state where key = 'ledger-page-a'),
  'ledger retention cannot change a retained export snapshot page'
);

reset role;

select ok(
  not exists (
    select 1
    from private.deletion_ledger_exports
    where export_id in (
      (
        select (value ->> 'export_id')::uuid
        from account_deletion_test_state
        where key = 'zero-ledger-export'
      ),
      (
        select (value ->> 'export_id')::uuid
        from account_deletion_test_state
        where key = 'ledger-export-unacked'
      )
    )
  )
  and not exists (
    select 1
    from private.deletion_ledger_export_events
    where export_id = (
      select (value ->> 'export_id')::uuid
      from account_deletion_test_state
      where key = 'ledger-export-unacked'
    )
  ),
  'retention expires acknowledged and abandoned export snapshots after thirty days'
);

select ok(
  exists (
    select 1
    from private.deletion_ledger
    where owner_id = 'e1000000-0000-0000-0000-000000000005'
      and request_id = 'ee000000-0000-0000-0000-000000000046'
  ),
  'old but unexported ledger evidence is never dropped'
);

select ok(
  exists (
    select 1
    from private.item_deletion_tombstones
    where owner_id = 'e1000000-0000-0000-0000-000000000005'
      and item_id = 'f1000000-0000-0000-0000-000000000046'
  ),
  'old tombstone is retained until its ledger sequence is acknowledged'
);

select is(
  (
    select coverage_origin
    from private.deletion_ledger_identity
    where singleton
  ),
  now() - interval '30 days',
  'removing acknowledged old ledger rows advances coverage to the retention cutoff'
);

select ok(
  not exists (
    select 1
    from private.asset_cleanup_receipts
    where asset_id = 'fb000000-0000-0000-0000-000000000099'
  ),
  'retention removes content-free asset cleanup receipts after thirty days'
);

set local role service_role;

insert into account_deletion_test_state (key, value)
values ('ledger-export-c', public.library_export_deletion_ledger());

select is(
  (
    select (value ->> 'coverage_from')::timestamptz
    from account_deletion_test_state
    where key = 'ledger-export-c'
  ),
  now() - interval '30 days',
  'fresh export reports the advanced coverage cutoff for restore validation'
);

select ok(
  (
    select exists (
      select 1
      from jsonb_array_elements(page.value -> 'events') as event(value)
      where event.value ->> 'owner_id' = 'e1000000-0000-0000-0000-000000000005'
        and event.value ->> 'item_id' = 'f1000000-0000-0000-0000-000000000046'
    )
    from (
      select public.library_read_deletion_ledger_export(
        (
          select (value ->> 'export_id')::uuid
          from account_deletion_test_state
          where key = 'ledger-export-c'
        ),
        0,
        1000
      ) as value
    ) as page
  ),
  'fresh full export retains previously unexported old evidence despite advanced coverage'
);

select ok(
  now() - interval '31 days' < (
    select (value ->> 'coverage_from')::timestamptz
    from account_deletion_test_state
    where key = 'ledger-export-c'
  ),
  'a backup older than coverage is explicitly outside the restore-valid range'
);

reset role;

select ok(
  exists (
    select 1
    from pg_constraint
    where conrelid = 'public.account_deletion_jobs'::regclass
      and contype = 'u'
      and pg_get_constraintdef(oid) = 'UNIQUE (owner_id, request_id)'
  )
  and exists (
    select 1
    from pg_constraint
    where conrelid = 'private.request_id_claims'::regclass
      and contype = 'p'
      and pg_get_constraintdef(oid) = 'PRIMARY KEY (owner_id, request_id)'
  ),
  'account receipts and shared request claims have owner-scoped unique identities'
);

select ok(
  pg_get_functiondef('public.library_export_deletion_ledger()'::regprocedure)
    ilike '%lock table private.deletion_ledger in share mode%',
  'export snapshot locks the live ledger against concurrent inserts and retention'
);

select * from finish();
rollback;
