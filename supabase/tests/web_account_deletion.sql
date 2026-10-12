begin;
create extension if not exists pgtap with schema extensions;
set local search_path = pg_catalog, extensions, public;
select no_plan();

-- Rollback-only fixtures. These RPCs trust the Edge verifier, not a browser;
-- signature/audience/freshness verification is covered in the Deno tests.
create temporary table web_test_state (key text primary key, value jsonb not null);
create function pg_temp.new_challenge(p_key text, p_purpose text default 'status')
returns uuid language plpgsql as $$
declare result jsonb;
begin
  result := public.library_web_account_challenge(gen_random_uuid(), p_purpose, repeat('a', 64));
  insert into web_test_state values (p_key, result);
  return (result ->> 'challenge_id')::uuid;
end;
$$;
create function pg_temp.challenge_id(p_key text)
returns uuid language sql as $$ select (value ->> 'challenge_id')::uuid from web_test_state where key = p_key; $$;
create function pg_temp.prove(
  p_key text, p_purpose text, p_subject text, p_operation uuid default gen_random_uuid(),
  p_nonce_hash text default repeat('a', 64), p_hash text default repeat('b', 64)
)
returns jsonb language plpgsql as $$
declare result jsonb;
begin
  result := public.library_web_account_begin(p_operation, pg_temp.challenge_id(p_key), p_purpose, p_hash);
  if result ->> 'state' <> 'verify' or result ->> 'state' is null then return result; end if;
  return public.library_web_account_finish(p_operation, pg_temp.challenge_id(p_key), p_purpose, p_hash, p_subject, p_nonce_hash);
end;
$$;

insert into auth.users (instance_id, id, aud, role, email, encrypted_password,
  email_confirmed_at, raw_app_meta_data, raw_user_meta_data, created_at, updated_at,
  confirmation_token, email_change, email_change_token_new, recovery_token)
select '00000000-0000-0000-0000-000000000000'::uuid,
  ('ee220000-0000-0000-0000-' || lpad(n::text, 12, '0'))::uuid,
  'authenticated', 'authenticated', 'web-delete-' || n || '@example.test', '', now(),
  '{"provider":"google","providers":["google"]}'::jsonb, '{}'::jsonb, now(), now(), '', '', '', ''
from generate_series(1, 4) as fixture(n);
insert into auth.identities (id, user_id, provider, provider_id, identity_data, created_at, updated_at)
values
  (gen_random_uuid(), 'ee220000-0000-0000-0000-000000000001', 'google', 'web-google-owner-1', '{"sub":"web-google-owner-1","email":"web-delete-1@example.test"}', now(), now()),
  (gen_random_uuid(), 'ee220000-0000-0000-0000-000000000002', 'google', 'web-google-owner-2', '{"sub":"web-google-owner-2","email":"web-delete-1@example.test"}', now(), now()),
  (gen_random_uuid(), 'ee220000-0000-0000-0000-000000000003', 'email', 'web-google-email-only', '{"sub":"web-google-email-only","email":"web-delete-1@example.test"}', now(), now()),
  (gen_random_uuid(), 'ee220000-0000-0000-0000-000000000004', 'google', 'web-google-conflict', '{"sub":"different-subject","email":"web-delete-1@example.test"}', now(), now());
insert into public.profiles (id, state) values ('ee220000-0000-0000-0000-000000000001', 'active');
insert into public.beta_members (owner_id, enabled, approved_at) values ('ee220000-0000-0000-0000-000000000001', true, now());
insert into public.library_usage (owner_id, active_item_count, used_image_bytes, reserved_image_bytes)
values ('ee220000-0000-0000-0000-000000000001', 0, 0, 0);

select ok(not has_function_privilege('anon', 'public.library_web_account_challenge(uuid,text,text)', 'execute'), 'anon cannot mint database challenges directly');
select ok(not has_function_privilege('authenticated', 'public.library_web_account_begin(uuid,uuid,text,text)', 'execute'), 'Auth sessions cannot bypass Google verifier');
select ok(not has_function_privilege('anon', 'public.library_web_account_finish(uuid,uuid,text,text,text,text)', 'execute'), 'anon cannot forge a verified subject');
select ok(not has_function_privilege('authenticated', 'public.library_web_account_finish(uuid,uuid,text,text,text,text)', 'execute'), 'authenticated cannot forge a verified subject');
select ok(has_function_privilege('service_role', 'public.library_web_account_challenge(uuid,text,text)', 'execute'), 'service can create browser challenge');
select ok(has_function_privilege('service_role', 'public.library_web_account_begin(uuid,uuid,text,text)', 'execute'), 'service can count proof attempts');
select ok(has_function_privilege('service_role', 'public.library_web_account_finish(uuid,uuid,text,text,text,text)', 'execute'), 'service can submit a verified proof');
select ok(not has_table_privilege('service_role', 'private.web_account_challenges', 'SELECT'), 'service has no raw challenge-table access');
select ok(not has_table_privilege('anon', 'private.web_account_subject_jobs', 'SELECT'), 'retained mapping is private');
select ok(not has_function_privilege('service_role', 'private.web_account_capture_job()', 'execute'), 'capture trigger cannot be invoked as public RPC');
select ok(exists(select 1 from cron.job where jobname = 'link-vault-web-account-retention' and active and schedule = '*/5 * * * *' and command = 'select private.web_account_cleanup();'), 'expired status mappings are cleaned without browser traffic');

insert into web_test_state values ('challenge_request', to_jsonb(gen_random_uuid()));
insert into web_test_state values ('challenge_first', public.library_web_account_challenge((select (value #>> '{}')::uuid from web_test_state where key = 'challenge_request'), 'delete', repeat('a', 64)));
select is((select value ->> 'http_status' from web_test_state where key = 'challenge_first'), '201', 'public challenge is created without an Auth user');
select is(public.library_web_account_challenge((select (value #>> '{}')::uuid from web_test_state where key = 'challenge_request'), 'delete', repeat('a', 64)), (select value from web_test_state where key = 'challenge_first'), 'challenge replay preserves challenge identity and expiry');
select is(public.library_web_account_challenge((select (value #>> '{}')::uuid from web_test_state where key = 'challenge_request'), 'status', repeat('a', 64)) ->> 'error_code', 'IDEMPOTENCY_MISMATCH', 'challenge request UUID cannot change purpose');
select is(public.library_web_account_challenge((select (value #>> '{}')::uuid from web_test_state where key = 'challenge_request'), 'delete', repeat('c', 64)) ->> 'error_code', 'IDEMPOTENCY_MISMATCH', 'challenge request UUID cannot change nonce');
select ok((select expires_at - created_at = interval '5 minutes' and retain_until - created_at = interval '24 hours' from private.web_account_challenges where id = (select (value ->> 'challenge_id')::uuid from web_test_state where key = 'challenge_first')), 'fresh challenge is five minutes and exact-replay record is bounded to 24 hours');
select is(public.library_web_account_challenge(gen_random_uuid(), 'anything', repeat('a', 64)) ->> 'error_code', 'WEB_CHALLENGE_INVALID', 'unknown purpose fails closed');
select is(public.library_web_account_challenge(gen_random_uuid(), 'delete', 'raw-nonce') ->> 'error_code', 'WEB_CHALLENGE_INVALID', 'database receives SHA256 hex, not raw nonce');

select pg_temp.new_challenge('not_requested');
select is(pg_temp.prove('not_requested', 'status', 'web-google-owner-1'), '{"http_status":200,"state":"not_requested","requested_at":null,"completed_at":null,"status_available_until":null}'::jsonb, 'existing subject without a deletion job is not_requested, not deleted');
select pg_temp.new_challenge('unknown_delete', 'delete');
select is(pg_temp.prove('unknown_delete', 'delete', 'unknown-google-subject') ->> 'error_code', 'ACCOUNT_NOT_FOUND', 'unknown subject cannot delete another account');
select is((select count(*)::integer from auth.users where id::text like 'ee220000-%'), 4, 'unknown deletion never signs up a user');
select is((select count(*)::integer from public.account_deletion_jobs where owner_id::text like 'ee220000-%'), 0, 'unknown deletion never creates a deletion job');
select pg_temp.new_challenge('unknown_status');
select is(pg_temp.prove('unknown_status', 'status', 'unknown-google-subject') ->> 'error_code', 'STATUS_NOT_FOUND', 'unknown status is unavailable, never completed');
select pg_temp.new_challenge('email_provider');
select is(pg_temp.prove('email_provider', 'status', 'web-google-email-only') ->> 'error_code', 'STATUS_NOT_FOUND', 'same subject in non-Google provider does not authorize status');
select pg_temp.new_challenge('conflicting_provider');
select is(pg_temp.prove('conflicting_provider', 'status', 'web-google-conflict') ->> 'error_code', 'STATUS_NOT_FOUND', 'conflicting provider_id and identity_data.sub are not trusted');

select pg_temp.new_challenge('wrong_purpose', 'delete');
select is(pg_temp.prove('wrong_purpose', 'status', 'web-google-owner-1') ->> 'error_code', 'WEB_CHALLENGE_INVALID', 'delete challenge cannot authorize status');
select is((select attempts from private.web_account_challenges where id = pg_temp.challenge_id('wrong_purpose')), 0, 'wrong-purpose request cannot enter proof verification');
select pg_temp.new_challenge('reverse_purpose');
select is(pg_temp.prove('reverse_purpose', 'delete', 'web-google-owner-1') ->> 'error_code', 'WEB_CHALLENGE_INVALID', 'status challenge cannot authorize deletion');
select pg_temp.new_challenge('wrong_nonce', 'delete');
select is(pg_temp.prove('wrong_nonce', 'delete', 'web-google-owner-1', gen_random_uuid(), repeat('c', 64)) ->> 'error_code', 'WEB_CHALLENGE_INVALID', 'valid Google subject with wrong nonce does not delete');
select is((select attempts from private.web_account_challenges where id = pg_temp.challenge_id('wrong_nonce')), 1, 'failed nonce still counts an attempt');
select ok((select consumed_request_id is null from private.web_account_challenges where id = pg_temp.challenge_id('wrong_nonce')), 'nonce mismatch cannot consume a legitimate challenge');
select pg_temp.new_challenge('expired', 'delete');
update private.web_account_challenges set created_at = clock_timestamp() - interval '10 minutes', expires_at = clock_timestamp() - interval '5 minutes' where id = pg_temp.challenge_id('expired');
select is(pg_temp.prove('expired', 'delete', 'web-google-owner-1') ->> 'error_code', 'WEB_CHALLENGE_INVALID', 'expired Google challenge cannot delete');
select pg_temp.new_challenge('no_begin', 'delete');
select is(public.library_web_account_finish(gen_random_uuid(), pg_temp.challenge_id('no_begin'), 'delete', repeat('b', 64), 'web-google-owner-1', repeat('a', 64)) ->> 'error_code', 'WEB_CHALLENGE_INVALID', 'finish without counting a proof attempt is rejected');
select pg_temp.new_challenge('attempts');
do $$ begin
  for n in 1..5 loop
    perform public.library_web_account_begin(gen_random_uuid(), pg_temp.challenge_id('attempts'), 'status', repeat('b', 64));
  end loop;
end; $$;
select is(public.library_web_account_begin(gen_random_uuid(), pg_temp.challenge_id('attempts'), 'status', repeat('b', 64)) ->> 'error_code', 'RATE_LIMITED', 'sixth proof attempt is blocked before JWKS verification');
select is((select attempts from private.web_account_challenges where id = pg_temp.challenge_id('attempts')), 5, 'attempt limit remains committed across failed operations');

select pg_temp.new_challenge('accepted_delete', 'delete');
insert into web_test_state values ('delete_operation', to_jsonb(gen_random_uuid()));
insert into web_test_state values ('accepted_reply', pg_temp.prove('accepted_delete', 'delete', 'web-google-owner-1', (select (value #>> '{}')::uuid from web_test_state where key = 'delete_operation')));
select is((select value from web_test_state where key = 'accepted_reply'), '{"http_status":202,"state":"deleting"}'::jsonb, 'acceptance is 202 deleting, not completion');
select is((select state from public.account_deletion_jobs where owner_id = 'ee220000-0000-0000-0000-000000000001'), 'queued', 'existing pipeline creates deferred worker job');
select is((select state from public.profiles where id = 'ee220000-0000-0000-0000-000000000001'), 'deleting', 'existing pipeline revokes active account access transactionally');
select ok(exists(select 1 from private.deletion_ledger where kind = 'account' and owner_id = 'ee220000-0000-0000-0000-000000000001'), 'acceptance writes existing durable account deletion ledger');
select ok(exists(select 1 from private.request_id_claims where owner_id = 'ee220000-0000-0000-0000-000000000001' and request_id = (select (value #>> '{}')::uuid from web_test_state where key = 'delete_operation') and method_path = 'POST /account/delete'), 'existing transactional request-id claims are preserved');
select ok(exists(select 1 from auth.users where id = 'ee220000-0000-0000-0000-000000000001'), 'acceptance never raw-admin-deletes Auth');
select is((select subject_hash from private.web_account_subject_jobs where owner_id = 'ee220000-0000-0000-0000-000000000001'), private.web_google_subject_hash('web-google-owner-1'), 'only hashed Google subject and job identity are retained');
select ok((select expires_at is null from private.web_account_subject_jobs where owner_id = 'ee220000-0000-0000-0000-000000000001'), 'in-flight status mapping does not expire before worker completion');

select is(pg_temp.prove('accepted_delete', 'delete', 'web-google-owner-1', (select (value #>> '{}')::uuid from web_test_state where key = 'delete_operation')), (select value from web_test_state where key = 'accepted_reply'), 'exact delete request replay returns its accepted snapshot');
select is(pg_temp.prove('accepted_delete', 'delete', 'other-google-subject', (select (value #>> '{}')::uuid from web_test_state where key = 'delete_operation'), repeat('a', 64), repeat('c', 64)) ->> 'error_code', 'IDEMPOTENCY_MISMATCH', 'different subject/token digest cannot reuse an accepted request');
select is(pg_temp.prove('accepted_delete', 'delete', 'web-google-owner-1') ->> 'error_code', 'IDEMPOTENCY_MISMATCH', 'one-use challenge cannot authorize a second operation UUID');
select is(pg_temp.prove('accepted_delete', 'status', 'web-google-owner-1', (select (value #>> '{}')::uuid from web_test_state where key = 'delete_operation')) ->> 'error_code', 'WEB_CHALLENGE_INVALID', 'accepted delete proof cannot be replayed as status');
select pg_temp.new_challenge('cross_challenge', 'delete');
select is(pg_temp.prove('cross_challenge', 'delete', 'web-google-owner-1', (select (value #>> '{}')::uuid from web_test_state where key = 'delete_operation')) ->> 'error_code', 'IDEMPOTENCY_MISMATCH', 'operation UUID cannot move to a different challenge');
select is(public.library_web_account_challenge((select (value #>> '{}')::uuid from web_test_state where key = 'delete_operation'), 'status', repeat('a', 64)) ->> 'error_code', 'IDEMPOTENCY_MISMATCH', 'operation UUID cannot become a challenge UUID');
update private.web_account_challenges set created_at = clock_timestamp() - interval '10 minutes', expires_at = clock_timestamp() - interval '5 minutes' where id = pg_temp.challenge_id('accepted_delete');
select is(pg_temp.prove('accepted_delete', 'delete', 'web-google-owner-1', (select (value #>> '{}')::uuid from web_test_state where key = 'delete_operation')), (select value from web_test_state where key = 'accepted_reply'), 'exact replay survives five-minute expiry within the bounded replay window');

update public.account_deletion_jobs set state = 'retry', last_error_code = 'ACCOUNT_AUTH_DELETE_FAILED'
where owner_id = 'ee220000-0000-0000-0000-000000000001';
select pg_temp.new_challenge('worker_retry_status');
insert into web_test_state values ('worker_retry_reply', pg_temp.prove('worker_retry_status', 'status', 'web-google-owner-1'));
select is((select value ->> 'state' from web_test_state where key = 'worker_retry_reply'), 'deleting', 'worker failure/retry remains incomplete deletion');
select is((select value ->> 'completed_at' from web_test_state where key = 'worker_retry_reply'), null::text, 'worker Auth-removal failure has no completion timestamp');

select pg_temp.new_challenge('other_subject');
select is(pg_temp.prove('other_subject', 'status', 'web-google-owner-2') ->> 'state', 'not_requested', 'another Google subject with the victim email cannot read victim job');
select pg_temp.new_challenge('job_id_as_subject');
select is(pg_temp.prove('job_id_as_subject', 'status', (select (value #>> '{}') from web_test_state where key = 'delete_operation')) ->> 'error_code', 'STATUS_NOT_FOUND', 'knowing a job request UUID is not status authorization');
select pg_temp.new_challenge('email_as_subject');
select is(pg_temp.prove('email_as_subject', 'status', 'web-delete-1@example.test') ->> 'error_code', 'STATUS_NOT_FOUND', 'knowing an email is not status authorization');
select pg_temp.new_challenge('deleting_status');
select is(pg_temp.prove('deleting_status', 'status', 'web-google-owner-1') ->> 'state', 'deleting', 'queued job reports in-progress, not deleted');
select pg_temp.new_challenge('already_deleting', 'delete');
select is(pg_temp.prove('already_deleting', 'delete', 'web-google-owner-1') ->> 'error_code', 'ACCOUNT_DELETING', 'new deletion proof does not create a second accepted job');

-- Exercise real purge/finalize RPCs with rollback-only worker metadata. Auth
-- removal here represents the worker step, never a browser backend operation.
insert into web_test_state values ('lease', to_jsonb(gen_random_uuid()));
update public.account_deletion_jobs set state = 'running', attempts = 1,
  lease_token = (select (value #>> '{}')::uuid from web_test_state where key = 'lease'),
  lease_until = clock_timestamp() + interval '5 minutes'
where owner_id = 'ee220000-0000-0000-0000-000000000001';
select is(public.library_purge_account_deletion('ee220000-0000-0000-0000-000000000001', (select (value #>> '{}')::uuid from web_test_state where key = 'lease')) ->> 'state', 'ready_for_auth_delete', 'existing business purge prepares deferred Auth removal');
delete from auth.users where id = 'ee220000-0000-0000-0000-000000000001';
select ok(not exists(select 1 from auth.identities where user_id = 'ee220000-0000-0000-0000-000000000001'), 'worker Auth removal also removes linked identity');
select ok(exists(select 1 from private.web_account_subject_jobs where owner_id = 'ee220000-0000-0000-0000-000000000001'), 'minimal status mapping survives Auth cascade');
select pg_temp.new_challenge('auth_removed_status');
select is(pg_temp.prove('auth_removed_status', 'status', 'web-google-owner-1') ->> 'state', 'deleting', 'missing Auth row alone is never proof of completed deletion');
select is(public.library_finalize_account_deletion('ee220000-0000-0000-0000-000000000001', (select (value #>> '{}')::uuid from web_test_state where key = 'lease')) ->> 'state', 'complete', 'only existing finalize worker establishes authoritative completion');
select pg_temp.new_challenge('completed_status');
insert into web_test_state values ('status_operation', to_jsonb(gen_random_uuid()));
insert into web_test_state values ('completed_reply', pg_temp.prove('completed_status', 'status', 'web-google-owner-1', (select (value #>> '{}')::uuid from web_test_state where key = 'status_operation')));
select is((select value ->> 'state' from web_test_state where key = 'completed_reply'), 'deleted', 'fresh verified Google subject sees completion after Auth deletion');
select is((select (value ->> 'completed_at')::timestamptz from web_test_state where key = 'completed_reply'), (select completed_at from public.account_deletion_jobs where owner_id = 'ee220000-0000-0000-0000-000000000001'), 'completion timestamp comes from authoritative job');
select is((select (value ->> 'status_available_until')::timestamptz from web_test_state where key = 'completed_reply'), (select completed_at + interval '30 days' from public.account_deletion_jobs where owner_id = 'ee220000-0000-0000-0000-000000000001'), 'terminal status lifetime is exactly 30 days after completion');
select is(pg_temp.prove('completed_status', 'status', 'web-google-owner-1', (select (value #>> '{}')::uuid from web_test_state where key = 'status_operation')), (select value from web_test_state where key = 'completed_reply'), 'exact status replay returns its prior snapshot');
select pg_temp.new_challenge('post_auth_wrong_subject');
select is(pg_temp.prove('post_auth_wrong_subject', 'status', 'another-unlinked-google-subject') ->> 'error_code', 'STATUS_NOT_FOUND', 'wrong subject cannot read retained completed job');
select pg_temp.new_challenge('already_completed', 'delete');
select is(pg_temp.prove('already_completed', 'delete', 'web-google-owner-1') ->> 'error_code', 'ACCOUNT_ALREADY_DELETED', 'new delete request does not mislabel a completed job as freshly accepted');

-- Terminal mapping expires even if backup/ledger retention retains its job.
update public.account_deletion_jobs set requested_at = clock_timestamp() - interval '32 days',
  business_purged_at = clock_timestamp() - interval '31 days', completed_at = clock_timestamp() - interval '31 days'
where owner_id = 'ee220000-0000-0000-0000-000000000001';
select private.web_account_cleanup();
select ok(not exists(select 1 from private.web_account_subject_jobs where owner_id = 'ee220000-0000-0000-0000-000000000001'), 'cleanup erases terminal Google correlation at the documented deadline');
select ok(exists(select 1 from public.account_deletion_jobs where owner_id = 'ee220000-0000-0000-0000-000000000001'), 'status expiration does not bypass ledger-gated job retention');
select pg_temp.new_challenge('expired_status_mapping');
select is(pg_temp.prove('expired_status_mapping', 'status', 'web-google-owner-1') ->> 'error_code', 'STATUS_NOT_FOUND', 'expired mapping is unavailable, never inferred completion');
update private.web_account_challenges set reply = jsonb_set(reply, '{status_available_until}', to_jsonb((clock_timestamp() - interval '1 second')::text)) where id = pg_temp.challenge_id('completed_status');
select is(pg_temp.prove('completed_status', 'status', 'web-google-owner-1', (select (value #>> '{}')::uuid from web_test_state where key = 'status_operation')) ->> 'error_code', 'STATUS_NOT_FOUND', 'cached completion cannot extend terminal correlation lifetime');

select pg_temp.new_challenge('replay_expired');
update private.web_account_challenges set created_at = clock_timestamp() - interval '2 days',
  expires_at = clock_timestamp() - interval '2 days' + interval '5 minutes', retain_until = clock_timestamp() - interval '1 day'
where id = pg_temp.challenge_id('replay_expired');
select is(pg_temp.prove('replay_expired', 'status', 'web-google-owner-2') ->> 'error_code', 'WEB_CHALLENGE_INVALID', '24-hour replay record expiration is enforced before proof');
select private.web_account_cleanup();
select ok(not exists(select 1 from private.web_account_challenges where id = pg_temp.challenge_id('replay_expired')), 'cleanup removes expired challenge rows');

update private.web_account_budget set window_start = date_trunc('minute', clock_timestamp()), challenges = 60, proofs = 240 where singleton;
select is(public.library_web_account_challenge(gen_random_uuid(), 'status', repeat('a', 64)) ->> 'error_code', 'RATE_LIMITED', 'global challenge quota cannot be evaded by new request UUID');
select is(public.library_web_account_begin(gen_random_uuid(), gen_random_uuid(), 'status', repeat('b', 64)) ->> 'error_code', 'RATE_LIMITED', 'global proof quota bounds verification including unknown challenge attacks');
select is((select count(*)::integer from private.web_account_budget), 1, 'quota state cannot be spammed with attacker-chosen keys');
update private.web_account_budget set window_start = date_trunc('minute', clock_timestamp()), challenges = 0, proofs = 0 where singleton;
insert into private.web_account_challenges (request_id, purpose, nonce_hash, created_at, expires_at, retain_until)
select gen_random_uuid(), 'status', repeat('f', 64), clock_timestamp(), clock_timestamp() + interval '5 minutes', clock_timestamp() + interval '24 hours'
from generate_series(1, 5000);
select is(public.library_web_account_challenge(gen_random_uuid(), 'status', repeat('a', 64)) ->> 'error_code', 'RATE_LIMITED', 'hard retained-row cap prevents challenge-table spam');
delete from private.web_account_challenges where nonce_hash = repeat('f', 64);

-- Missing/corrupt deletion metadata is an error, not "not requested".
insert into public.profiles (id, state, deletion_requested_at)
values ('ee220000-0000-0000-0000-000000000002', 'deleting', clock_timestamp());
select pg_temp.new_challenge('missing_job');
select is(pg_temp.prove('missing_job', 'status', 'web-google-owner-2') ->> 'error_code', 'DEPENDENCY_UNAVAILABLE', 'deleting profile with missing job is an explicit dependency error');
select ok((select consumed_request_id is null from private.web_account_challenges where id = pg_temp.challenge_id('missing_job')), 'dependency failure does not consume a retriable valid challenge');
delete from public.profiles where id = 'ee220000-0000-0000-0000-000000000002';

insert into web_test_state values ('native_conflict_operation', to_jsonb(gen_random_uuid()));
select private.claim_request_id('ee220000-0000-0000-0000-000000000002',
  (select (value #>> '{}')::uuid from web_test_state where key = 'native_conflict_operation'),
  'POST /items', repeat('c', 64), clock_timestamp(), clock_timestamp() + interval '7 days');
select pg_temp.new_challenge('native_conflict', 'delete');
select is(pg_temp.prove('native_conflict', 'delete', 'web-google-owner-2',
  (select (value #>> '{}')::uuid from web_test_state where key = 'native_conflict_operation')) ->> 'error_code', 'IDEMPOTENCY_MISMATCH', 'native request UUID collision fails without bypassing the ledger protocol');
select ok(not exists(select 1 from public.account_deletion_jobs where owner_id = 'ee220000-0000-0000-0000-000000000002'), 'failed native acceptance rolls back its deletion job');
select ok(not exists(select 1 from private.auth_challenges where owner_id = 'ee220000-0000-0000-0000-000000000002'), 'failed native acceptance rolls back its bridge challenge');

-- Prove that the unchanged native RPC pipeline also captures a status binding.
insert into web_test_state values ('native_challenge', public.library_create_delete_challenge(
  'ee220000-0000-0000-0000-000000000002', gen_random_uuid(), repeat('d', 64)));
select is(public.library_accept_account_deletion('ee220000-0000-0000-0000-000000000002', gen_random_uuid(),
  (select (value ->> 'challenge_id')::uuid from web_test_state where key = 'native_challenge'), repeat('e', 64)),
  '{"http_status":202,"state":"deleting"}'::jsonb, 'native acceptance response and protocol remain unchanged');
select ok(exists(select 1 from private.web_account_subject_jobs where owner_id = 'ee220000-0000-0000-0000-000000000002'
  and subject_hash = private.web_google_subject_hash('web-google-owner-2')), 'native deletion job receives the same minimal subject binding');
select pg_temp.new_challenge('native_status');
select is(pg_temp.prove('native_status', 'status', 'web-google-owner-2') ->> 'state', 'deleting', 'browser verifies status of native-accepted job using Google subject only');

-- Deliberately damaged rollback-only metadata cannot yield a fake completion.
delete from auth.users where id = 'ee220000-0000-0000-0000-000000000002';
delete from public.account_deletion_jobs where owner_id = 'ee220000-0000-0000-0000-000000000002';
select ok(not exists(select 1 from private.web_account_subject_jobs where owner_id = 'ee220000-0000-0000-0000-000000000002'), 'job removal cascades its status mapping rather than leaving an orphaned completion receipt');
select pg_temp.new_challenge('missing_authoritative_job');
select is(pg_temp.prove('missing_authoritative_job', 'status', 'web-google-owner-2') ->> 'error_code', 'STATUS_NOT_FOUND', 'absent Auth and absent authoritative job is unavailable, not deleted');

-- Rollback-only re-registration snapshots: the same Google subject first has
-- an older pending deletion, then a newer completed deletion on another owner.
-- Both Auth rows are gone, so neither email nor newest-job selection can safely
-- disambiguate which deletion the Google-proof status request concerns.
update auth.identities set provider = 'google', provider_id = 'web-google-reregistered',
  identity_data = '{"sub":"web-google-reregistered"}'::jsonb
where user_id = 'ee220000-0000-0000-0000-000000000003';
insert into public.account_deletion_jobs (owner_id, request_id, request_hash, state, requested_at)
values ('ee220000-0000-0000-0000-000000000003', gen_random_uuid(), repeat('3', 64), 'queued', clock_timestamp() - interval '2 hours');
delete from auth.users where id = 'ee220000-0000-0000-0000-000000000003';
update auth.identities set provider_id = 'web-google-reregistered',
  identity_data = '{"sub":"web-google-reregistered"}'::jsonb
where user_id = 'ee220000-0000-0000-0000-000000000004';
insert into public.account_deletion_jobs (owner_id, request_id, request_hash, state,
  requested_at, business_purged_at, completed_at, completed_lease_token)
values ('ee220000-0000-0000-0000-000000000004', gen_random_uuid(), repeat('4', 64), 'complete',
  clock_timestamp() - interval '1 hour', clock_timestamp() - interval '30 minutes',
  clock_timestamp() - interval '20 minutes', gen_random_uuid());
delete from auth.users where id = 'ee220000-0000-0000-0000-000000000004';
select is((select count(*)::integer from auth.users where id in (
  'ee220000-0000-0000-0000-000000000003', 'ee220000-0000-0000-0000-000000000004')), 0,
  'both re-registration Auth rows are absent');
select is((select count(*)::integer from private.web_account_subject_jobs
  where subject_hash = private.web_google_subject_hash('web-google-reregistered')
    and (expires_at is null or expires_at > clock_timestamp())), 2,
  'same verified Google subject retains both eligible owner/job mappings');
select pg_temp.new_challenge('ambiguous_retained_status');
insert into web_test_state values ('ambiguous_retained_status_reply',
  pg_temp.prove('ambiguous_retained_status', 'status', 'web-google-reregistered'));
select is((select value from web_test_state where key = 'ambiguous_retained_status_reply'),
  '{"http_status":403,"error_code":"GOOGLE_IDENTITY_MISMATCH"}'::jsonb,
  'newer completed job cannot mask an older pending deletion for the same Google subject');
select pg_temp.new_challenge('ambiguous_retained_delete', 'delete');
select is(pg_temp.prove('ambiguous_retained_delete', 'delete', 'web-google-reregistered'),
  '{"http_status":403,"error_code":"GOOGLE_IDENTITY_MISMATCH"}'::jsonb,
  'ambiguous retained delete proof also fails closed without owner or completion data');
select is((select state from public.account_deletion_jobs
  where owner_id = 'ee220000-0000-0000-0000-000000000003'), 'queued',
  'ambiguity handling leaves the older unfinished job unchanged');
select is((select state from public.account_deletion_jobs
  where owner_id = 'ee220000-0000-0000-0000-000000000004'), 'complete',
  'ambiguity handling leaves the newer authoritative completed job unchanged');

select * from finish();
rollback;
