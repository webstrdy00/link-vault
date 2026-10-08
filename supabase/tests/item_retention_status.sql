begin;
create extension if not exists pgtap with schema extensions;
set local search_path = pg_catalog, extensions, public;
-- Rollback-only metadata fixtures; actual object deletion uses the Storage API.
set local storage.allow_delete_query = 'true';
select no_plan();

select ok(
  has_function_privilege(
    'service_role',
    'public.library_operation_status()',
    'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated',
    'public.library_operation_status()',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'public.library_operation_status()',
    'EXECUTE'
  ),
  'operation status remains service-role-only'
);

select ok(
  (
    select installed_function.prosecdef
      and installed_function.provolatile = 's'
      and installed_function.proconfig = array['search_path=""']::text[]
    from pg_catalog.pg_proc as installed_function
    where installed_function.oid = 'public.library_operation_status()'::regprocedure
  ),
  'operation status remains STABLE and SECURITY DEFINER with an empty search path'
);

select is(
  (
    select pg_catalog.array_agg(status_key order by status_key)
    from pg_catalog.jsonb_object_keys(
      public.library_operation_status()
    ) as status_keys(status_key)
  ),
  array[
    'account_deletions_overdue',
    'account_deletions_pending',
    'account_deletions_retry',
    'asset_cleanups_overdue',
    'asset_cleanups_pending',
    'asset_cleanups_retry',
    'checked_at',
    'expired_account_leases',
    'item_deletions_overdue',
    'item_deletions_pending',
    'ledger_export_verified_at',
    'maintenance_last_dispatch_succeeded_at',
    'maintenance_scheduled',
    'untracked_storage_objects',
    'usage_mismatches'
  ]::text[],
  'operation status preserves its public response keys'
);

create temporary table item_retention_status_baseline as
select public.library_operation_status() as value;

insert into auth.users (
  id,
  aud,
  role,
  email,
  raw_app_meta_data,
  raw_user_meta_data,
  created_at,
  updated_at
)
values
  (
    '02110000-0000-0000-0000-000000000001',
    'authenticated',
    'authenticated',
    'item-retention-status@example.test',
    '{}'::jsonb,
    '{}'::jsonb,
    now(),
    now()
  ),
  (
    '02110000-0000-0000-0000-000000000002',
    'authenticated',
    'authenticated',
    'account-retention-status@example.test',
    '{}'::jsonb,
    '{}'::jsonb,
    now(),
    now()
  ),
  (
    '02110000-0000-0000-0000-000000000003',
    'authenticated',
    'authenticated',
    'asset-retention-status@example.test',
    '{}'::jsonb,
    '{}'::jsonb,
    now(),
    now()
  );

insert into public.profiles (id, state)
values ('02110000-0000-0000-0000-000000000001', 'active');

insert into public.library_usage (
  owner_id,
  active_item_count,
  used_image_bytes,
  reserved_image_bytes
)
values ('02110000-0000-0000-0000-000000000001', 0, 0, 0);

insert into public.items (
  id,
  owner_id,
  created_at,
  updated_at,
  deleted_at
)
values
  (
    '02120000-0000-0000-0000-000000000001',
    '02110000-0000-0000-0000-000000000001',
    now() - interval '72 hours',
    now() - interval '72 hours',
    now() - interval '72 hours'
  ),
  (
    '02120000-0000-0000-0000-000000000002',
    '02110000-0000-0000-0000-000000000001',
    now() - interval '72 hours 1 second',
    now() - interval '72 hours 1 second',
    now() - interval '72 hours 1 second'
  ),
  (
    '02120000-0000-0000-0000-000000000003',
    '02110000-0000-0000-0000-000000000001',
    now() - interval '29 days',
    now() - interval '29 days',
    now() - interval '29 days'
  ),
  (
    '02120000-0000-0000-0000-000000000004',
    '02110000-0000-0000-0000-000000000001',
    now() - interval '30 days',
    now() - interval '30 days',
    now() - interval '30 days'
  ),
  (
    '02120000-0000-0000-0000-000000000005',
    '02110000-0000-0000-0000-000000000001',
    now() - interval '30 days 1 second',
    now() - interval '30 days 1 second',
    now() - interval '30 days 1 second'
  ),
  (
    '02120000-0000-0000-0000-000000000007',
    '02110000-0000-0000-0000-000000000001',
    now() - interval '2 days',
    now() - interval '2 days',
    now() - interval '2 days'
  ),
  (
    '02120000-0000-0000-0000-000000000008',
    '02110000-0000-0000-0000-000000000001',
    now() - interval '4 days',
    now() - interval '4 days',
    now() - interval '4 days'
  );

insert into private.item_deletion_tombstones (owner_id, item_id, deleted_at)
select item.owner_id, item.id, item.deleted_at
from public.items as item
where item.owner_id = '02110000-0000-0000-0000-000000000001';

select is(
  (
    select count(*)
    from public.items as item
    where item.owner_id = '02110000-0000-0000-0000-000000000001'
      and item.deleted_at is not null
      and item.original_url is null
      and item.normalized_url is null
      and item.url_hash is null
      and item.source is null
      and item.display_fallback is null
      and item.shared_text is null
      and item.user_title is null
      and item.fetched_title is null
      and item.description is null
      and item.body_text is null
      and item.note is null
      and item.extraction_meta = '{}'::jsonb
      and item.metadata_state is null
  ),
  7::bigint,
  'all seven retention fixtures are content-free retained rows'
);

-- Pending includes content-free rows throughout retention, not missed deadlines.
select is(
  (public.library_operation_status()->>'item_deletions_pending')::bigint,
  (
    select (value->>'item_deletions_pending')::bigint + 7
    from item_retention_status_baseline
  ),
  '2 days, 72 hours, 4 days, 29 days and both 30-day boundary rows remain pending'
);

-- The item alert is strictly >30 days; account and file alerts stay at >72 hours.
select is(
  (public.library_operation_status()->>'item_deletions_overdue')::bigint,
  (
    select (value->>'item_deletions_overdue')::bigint + 1
    from item_retention_status_baseline
  ),
  'only the item strictly older than 30 days is overdue'
);

select ok(
  not exists (
    select 1
    from public.assets as asset
    where asset.owner_id = '02110000-0000-0000-0000-000000000001'
  )
  and not exists (
    select 1
    from storage.objects as object
    where object.bucket_id = 'library-images'
      and object.name like '02110000-0000-0000-0000-000000000001/%'
  )
  and not exists (
    select 1
    from public.api_requests as request
    where request.owner_id = '02110000-0000-0000-0000-000000000001'
  ),
  'purgeable retention fixtures have no hidden asset, object, or receipt dependency'
);

select is(
  (public.library_purge_deleted_items(10)->>'purged_items')::integer,
  2,
  'purge includes the exact 30-day boundary while status warns only after it'
);

select ok(
  exists (
    select 1 from public.items
    where id = '02120000-0000-0000-0000-000000000001'
  )
  and exists (
    select 1 from public.items
    where id = '02120000-0000-0000-0000-000000000002'
  )
  and exists (
    select 1 from public.items
    where id = '02120000-0000-0000-0000-000000000003'
  )
  and exists (
    select 1 from public.items
    where id = '02120000-0000-0000-0000-000000000007'
  )
  and exists (
    select 1 from public.items
    where id = '02120000-0000-0000-0000-000000000008'
  )
  and not exists (
    select 1 from public.items
    where id = '02120000-0000-0000-0000-000000000004'
  )
  and not exists (
    select 1 from public.items
    where id = '02120000-0000-0000-0000-000000000005'
  ),
  'purge retains 2-day, 72-hour, 4-day and 29-day rows and removes both eligible boundary rows'
);

select is(
  (
    select count(*)
    from private.item_deletion_tombstones as tombstone
    where tombstone.owner_id = '02110000-0000-0000-0000-000000000001'
  ),
  7::bigint,
  'physical purge preserves all content-free deletion tombstones'
);

select is(
  (public.library_operation_status()->>'item_deletions_pending')::bigint,
  (
    select (value->>'item_deletions_pending')::bigint + 5
    from item_retention_status_baseline
  ),
  'completed purge removes eligible rows from pending while sub-30-day retention remains'
);

select is(
  (public.library_operation_status()->>'item_deletions_overdue')::bigint,
  (
    select (value->>'item_deletions_overdue')::bigint
    from item_retention_status_baseline
  ),
  'completed purge clears the item overdue alert'
);

insert into public.items (id, owner_id, created_at, updated_at, deleted_at)
values
  (
    '02120000-0000-0000-0000-000000000009',
    '02110000-0000-0000-0000-000000000001',
    now() - interval '31 days',
    now() - interval '31 days',
    now() - interval '31 days'
  ),
  (
    '02120000-0000-0000-0000-000000000010',
    '02110000-0000-0000-0000-000000000001',
    now() - interval '31 days',
    now() - interval '31 days',
    now() - interval '31 days'
  ),
  (
    '02120000-0000-0000-0000-000000000011',
    '02110000-0000-0000-0000-000000000001',
    now() - interval '31 days',
    now() - interval '31 days',
    now() - interval '31 days'
  );

insert into private.item_deletion_tombstones (owner_id, item_id, deleted_at)
select item.owner_id, item.id, item.deleted_at
from public.items as item
where item.id in (
  '02120000-0000-0000-0000-000000000009',
  '02120000-0000-0000-0000-000000000010',
  '02120000-0000-0000-0000-000000000011'
);

insert into public.api_requests (
  owner_id, request_id, method_path, request_hash, response_code, response_body
)
values (
  '02110000-0000-0000-0000-000000000001',
  '02140000-0000-0000-0000-000000000002',
  'DELETE /items/02120000-0000-0000-0000-000000000009',
  'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
  202,
  '{"http_status":202,"item_id":"02120000-0000-0000-0000-000000000009","state":"deleting"}'::jsonb
);

insert into storage.objects (id, bucket_id, name, metadata)
values (
  '02150000-0000-0000-0000-000000000001',
  'library-images',
  '02110000-0000-0000-0000-000000000001/02120000-0000-0000-0000-000000000010/orphaned-object',
  '{"size":1000,"mimetype":"image/jpeg"}'::jsonb
);

insert into public.assets (
  id, owner_id, item_id, object_path, state, reserved_mime_type,
  reservation_expires_at, cleanup_reason, cleanup_next_run_at,
  created_at, updated_at, deleted_at
)
values (
  '02130000-0000-0000-0000-000000000002',
  '02110000-0000-0000-0000-000000000001',
  '02120000-0000-0000-0000-000000000011',
  '02110000-0000-0000-0000-000000000001/02120000-0000-0000-0000-000000000011/02130000-0000-0000-0000-000000000002',
  'deleting',
  'image/jpeg',
  now() - interval '31 days',
  'item_delete',
  now(),
  now() - interval '31 days',
  now(),
  now() - interval '31 days'
);

update public.library_usage
set reserved_image_bytes = 2000000
where owner_id = '02110000-0000-0000-0000-000000000001';

select is(
  (public.library_purge_deleted_items(10)->>'purged_items')::integer,
  0,
  'each independent receipt, Storage object or asset dependency blocks its 31-day row'
);

select is(
  (
    select count(*) from public.items
    where id in (
      '02120000-0000-0000-0000-000000000009',
      '02120000-0000-0000-0000-000000000010',
      '02120000-0000-0000-0000-000000000011'
    )
  ),
  3::bigint,
  'all three dependency-blocked rows remain physically present after attempted purge'
);

select is(
  (public.library_operation_status()->>'item_deletions_pending')::bigint,
  (
    select (value->>'item_deletions_pending')::bigint + 8
    from item_retention_status_baseline
  ),
  'blocked purge retains five within-retention rows and three overdue rows in pending'
);

select is(
  (public.library_operation_status()->>'item_deletions_overdue')::bigint,
  (
    select (value->>'item_deletions_overdue')::bigint + 3
    from item_retention_status_baseline
  ),
  'dependency-blocked 31-day rows remain overdue after attempted purge'
);

delete from public.api_requests
where owner_id = '02110000-0000-0000-0000-000000000001'
  and request_id = '02140000-0000-0000-0000-000000000002';

select is(
  (public.library_purge_deleted_items(10)->>'purged_items')::integer,
  1,
  'removing the receipt dependency allows only its overdue row to be purged'
);

select ok(
  not exists (
    select 1 from public.items
    where id = '02120000-0000-0000-0000-000000000009'
  )
  and exists (
    select 1 from public.items
    where id = '02120000-0000-0000-0000-000000000010'
  )
  and exists (
    select 1 from public.items
    where id = '02120000-0000-0000-0000-000000000011'
  ),
  'receipt-free purge leaves both independently blocked Storage and asset rows'
);

delete from storage.objects
where id = '02150000-0000-0000-0000-000000000001';

select is(
  (public.library_purge_deleted_items(10)->>'purged_items')::integer,
  1,
  'removing the Storage object allows only its overdue row to be purged'
);

select ok(
  not exists (
    select 1 from public.items
    where id = '02120000-0000-0000-0000-000000000010'
  )
  and exists (
    select 1 from public.items
    where id = '02120000-0000-0000-0000-000000000011'
  ),
  'object-free purge still retains the asset-blocked row'
);

delete from public.assets
where id = '02130000-0000-0000-0000-000000000002';

update public.library_usage
set reserved_image_bytes = 0
where owner_id = '02110000-0000-0000-0000-000000000001';

select is(
  (public.library_purge_deleted_items(10)->>'purged_items')::integer,
  1,
  'completed asset cleanup allows the final overdue row to be purged'
);

select ok(
  not exists (
    select 1 from public.items
    where id in (
      '02120000-0000-0000-0000-000000000009',
      '02120000-0000-0000-0000-000000000010',
      '02120000-0000-0000-0000-000000000011'
    )
  ),
  'all formerly blocked rows are physically removed after their dependencies clear'
);

select is(
  (public.library_operation_status()->>'item_deletions_pending')::bigint,
  (
    select (value->>'item_deletions_pending')::bigint + 5
    from item_retention_status_baseline
  ),
  'completed dependency cleanup leaves only the five within-retention rows pending'
);

select is(
  (public.library_operation_status()->>'item_deletions_overdue')::bigint,
  (
    select (value->>'item_deletions_overdue')::bigint
    from item_retention_status_baseline
  ),
  'completed dependency cleanup and physical purge clear all added item overdue alerts'
);

select is(
  (
    select count(*) from private.item_deletion_tombstones
    where owner_id = '02110000-0000-0000-0000-000000000001'
  ),
  10::bigint,
  'both completed purge paths preserve every content-free deletion tombstone'
);

insert into public.profiles (id, state, deletion_requested_at)
values (
  '02110000-0000-0000-0000-000000000002',
  'deleting',
  now() - interval '72 hours 1 second'
);

insert into public.library_usage (
  owner_id,
  active_item_count,
  used_image_bytes,
  reserved_image_bytes
)
values ('02110000-0000-0000-0000-000000000002', 0, 0, 0);

insert into public.account_deletion_jobs (
  owner_id,
  request_id,
  request_hash,
  state,
  requested_at
)
values (
  '02110000-0000-0000-0000-000000000002',
  '02140000-0000-0000-0000-000000000001',
  'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
  'queued',
  now() - interval '72 hours 1 second'
);

select is(
  (public.library_operation_status()->>'account_deletions_pending')::bigint,
  (
    select (value->>'account_deletions_pending')::bigint + 1
    from item_retention_status_baseline
  ),
  'the account fixture is pending'
);

select is(
  (public.library_operation_status()->>'account_deletions_overdue')::bigint,
  (
    select (value->>'account_deletions_overdue')::bigint + 1
    from item_retention_status_baseline
  ),
  'the real account 72-hour overdue branch remains active'
);

insert into public.profiles (id, state)
values ('02110000-0000-0000-0000-000000000003', 'active');

insert into public.library_usage (
  owner_id,
  active_item_count,
  used_image_bytes,
  reserved_image_bytes
)
values ('02110000-0000-0000-0000-000000000003', 1, 0, 2000000);

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
values (
  '02120000-0000-0000-0000-000000000006',
  '02110000-0000-0000-0000-000000000003',
  'https://retention-status.example.test/asset',
  'https://retention-status.example.test/asset',
  'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
  'other',
  'Asset retention status fixture',
  'ready'
);

insert into public.assets (
  id,
  owner_id,
  item_id,
  object_path,
  state,
  reserved_mime_type,
  reservation_expires_at,
  cleanup_reason,
  cleanup_next_run_at,
  created_at,
  updated_at,
  deleted_at
)
values (
  '02130000-0000-0000-0000-000000000001',
  '02110000-0000-0000-0000-000000000003',
  '02120000-0000-0000-0000-000000000006',
  '02110000-0000-0000-0000-000000000003/02120000-0000-0000-0000-000000000006/02130000-0000-0000-0000-000000000001',
  'deleting',
  'image/jpeg',
  now() - interval '72 hours 1 second',
  'user_delete',
  now(),
  now() - interval '72 hours 1 second',
  now(),
  now() - interval '72 hours 1 second'
);

select is(
  (public.library_operation_status()->>'asset_cleanups_pending')::bigint,
  (
    select (value->>'asset_cleanups_pending')::bigint + 1
    from item_retention_status_baseline
  ),
  'the valid deleting asset fixture is pending'
);

select is(
  (public.library_operation_status()->>'asset_cleanups_overdue')::bigint,
  (
    select (value->>'asset_cleanups_overdue')::bigint + 1
    from item_retention_status_baseline
  ),
  'the real asset 72-hour overdue branch remains active'
);

select is(
  (public.library_operation_status()->>'usage_mismatches')::bigint,
  (
    select (value->>'usage_mismatches')::bigint
    from item_retention_status_baseline
  ),
  'retention, account, and asset fixtures keep usage accounting valid'
);

select * from finish();
rollback;
