begin;
create extension if not exists pgtap with schema extensions;
set local search_path = pg_catalog, extensions, public;
select no_plan();

select ok(not has_function_privilege('authenticated', 'public.library_operation_status()', 'EXECUTE'),
  'members cannot inspect cross-owner operational counts');
select ok(not has_function_privilege('anon', 'public.library_operation_status()', 'EXECUTE'),
  'anonymous callers cannot inspect operational status');
select ok(has_function_privilege('service_role', 'public.library_operation_status()', 'EXECUTE'),
  'trusted operators can inspect bounded operational status');
select ok(not has_function_privilege('service_role', 'private.dispatch_maintenance_jobs()', 'EXECUTE'),
  'the private dispatcher is not a service RPC');
select ok(not has_function_privilege('authenticated', 'private.dispatch_maintenance_jobs()', 'EXECUTE'),
  'members cannot trigger privileged scheduled requests');
select is((select count(*)::integer from cron.job
  where jobname = 'link-vault-maintenance' and active and schedule = '*/5 * * * *'
    and command = 'select private.dispatch_maintenance_jobs();'), 1,
  'maintenance has one durable five-minute schedule');
select is((select count(*)::integer from cron.job
  where jobname = 'link-vault-processing' and active and schedule = '* * * * *'
    and command = 'select private.dispatch_classification_jobs(); select private.dispatch_enrichment_jobs();'), 1,
  'M4 minute processing is not slowed by maintenance');

create temporary table status_baseline as select public.library_operation_status() as value;
insert into auth.users (id, aud, role, email, raw_app_meta_data, raw_user_meta_data, created_at, updated_at)
values ('b5100000-0000-0000-0000-000000000001', 'authenticated', 'authenticated',
  'maintenance-fixture@example.test', '{}'::jsonb, '{}'::jsonb, now(), now());
insert into public.profiles(id, state) values ('b5100000-0000-0000-0000-000000000001', 'active');
insert into public.library_usage(owner_id, active_item_count, used_image_bytes, reserved_image_bytes)
values ('b5100000-0000-0000-0000-000000000001', 1, 0, 0);
select is((public.library_operation_status()->>'usage_mismatches')::bigint,
  (select (value->>'usage_mismatches')::bigint + 1 from status_baseline),
  'operator detects a counter that claims an absent item');
update public.library_usage set active_item_count = 0, reserved_image_bytes = 2000000
where owner_id = 'b5100000-0000-0000-0000-000000000001';
select is((public.library_operation_status()->>'usage_mismatches')::bigint,
  (select (value->>'usage_mismatches')::bigint + 1 from status_baseline),
  'operator detects a reservation without an asset');
update public.library_usage set reserved_image_bytes = 0
where owner_id = 'b5100000-0000-0000-0000-000000000001';
select is((public.library_operation_status()->>'usage_mismatches')::bigint,
  (select (value->>'usage_mismatches')::bigint from status_baseline),
  'correcting fixture accounting clears only its mismatch');
select ok(not (public.library_operation_status()::text like '%maintenance-fixture%'),
  'operator status does not expose fixture identity');

-- Rename configuration only inside this rolled-back transaction; never read secrets into test output.
do $hide_fixture$
declare
  entry record;
begin
  for entry in
    select id, name from vault.secrets
    where name in ('link_vault_worker_url', 'link_vault_worker_token')
  loop
    perform vault.update_secret(
      entry.id, null, entry.name || '_maintenance_test_hidden'
    );
  end loop;
end
$hide_fixture$;
select throws_ok('select private.dispatch_maintenance_jobs()', 'P0001',
  'MAINTENANCE_WORKER_NOT_CONFIGURED', 'missing worker provisioning fails visibly');
select * from finish();
rollback;
