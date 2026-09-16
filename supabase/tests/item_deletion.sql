begin;

create extension if not exists pgtap with schema extensions;
set local search_path = pg_catalog, extensions, public;
-- Rollback-only metadata fixtures; real file removal is tested through Storage HTTP.
set local storage.allow_delete_query = 'true';
select no_plan();

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
    'd1000000-0000-0000-0000-000000000001',
    'authenticated',
    'authenticated',
    'deletion-a@example.test',
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
    'd1000000-0000-0000-0000-000000000002',
    'authenticated',
    'authenticated',
    'deletion-b@example.test',
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

insert into public.beta_members (owner_id, enabled, approved_at)
values
  ('d1000000-0000-0000-0000-000000000001', true, now()),
  ('d1000000-0000-0000-0000-000000000002', true, now());

insert into public.profiles (id, state)
values
  ('d1000000-0000-0000-0000-000000000001', 'active'),
  ('d1000000-0000-0000-0000-000000000002', 'active');

insert into public.library_usage (
  owner_id,
  active_item_count,
  used_image_bytes,
  reserved_image_bytes
)
values
  ('d1000000-0000-0000-0000-000000000001', 2, 1000, 2000000),
  ('d1000000-0000-0000-0000-000000000002', 1, 0, 0);

insert into public.categories (id, owner_id, name, normalized_name, kind)
values (
  'd3000000-0000-0000-0000-000000000001',
  'd1000000-0000-0000-0000-000000000001',
  '삭제 시험',
  '삭제 시험',
  'custom'
);

create function pg_temp.deletion_normalized_fields(p_marker text)
returns jsonb
language sql
stable
as $$
  select pg_catalog.jsonb_build_object(
    'user_title', p_marker,
    'fetched_title', 'fetched private text',
    'note', 'note private text',
    'ocr', 'ocr private text',
    'shared', 'shared private text',
    'description', 'description private text',
    'body', 'body private text',
    'categories', '삭제 시험',
    'url', 'https example test private path'
  );
$$;

create function pg_temp.deletion_alias_concepts()
returns jsonb
language sql
stable
as $$
  select pg_catalog.jsonb_build_object(
    'user_title', '[]'::jsonb,
    'fetched_title', '[]'::jsonb,
    'note', '[]'::jsonb,
    'ocr', '[]'::jsonb,
    'shared', '[]'::jsonb,
    'description', '[]'::jsonb,
    'body', '[]'::jsonb
  );
$$;

create function pg_temp.seed_deletion_item(
  p_item_id uuid,
  p_owner_id uuid,
  p_version integer,
  p_with_category boolean default false
)
returns void
language plpgsql
as $$
declare
  url_value text := 'https://example.test/delete/' || p_item_id::text;
begin
  insert into public.items (
    id,
    owner_id,
    original_url,
    normalized_url,
    url_hash,
    source,
    display_fallback,
    shared_text,
    user_title,
    fetched_title,
    description,
    body_text,
    note,
    extraction_meta,
    text_revision,
    version,
    metadata_state
  ) values (
    p_item_id,
    p_owner_id,
    url_value,
    url_value,
    pg_catalog.encode(
      extensions.digest(pg_catalog.convert_to(url_value, 'UTF8'), 'sha256'),
      'hex'
    ),
    'other',
    'private fallback',
    'shared private text',
    'user private title',
    'fetched private title',
    'description private text',
    'body private text',
    'note private text',
    '{"adapter_version":"private-adapter","final_url":"https://example.test/private-final"}'::jsonb,
    1,
    p_version,
    'running'
  );

  insert into public.item_search (
    owner_id,
    item_id,
    text_revision,
    search_version,
    normalized_fields,
    alias_concepts,
    cue_version,
    cue_state,
    cue_flags
  ) values (
    p_owner_id,
    p_item_id,
    1,
    'search-v2.0.0',
    pg_temp.deletion_normalized_fields(p_item_id::text),
    pg_temp.deletion_alias_concepts(),
    'cues-v1.0.0',
    'available',
    '[]'::jsonb
  );

  insert into public.item_classification (
    owner_id,
    item_id,
    rules_version,
    target_revision,
    state,
    reasons
  ) values (
    p_owner_id,
    p_item_id,
    'rules-v2.0.0',
    1,
    case when p_with_category then 'manual' else 'pending' end,
    '[]'::jsonb
  );

  insert into public.item_category_controls (
    owner_id,
    item_id,
    manual_override,
    cue_dismissed_revision
  ) values (p_owner_id, p_item_id, p_with_category, null);

  if p_with_category then
    insert into public.item_categories (owner_id, item_id, category_id, origin)
    values (
      p_owner_id,
      p_item_id,
      'd3000000-0000-0000-0000-000000000001',
      'manual'
    );
  end if;
end;
$$;

select pg_temp.seed_deletion_item(
  'd2000000-0000-0000-0000-000000000001',
  'd1000000-0000-0000-0000-000000000001',
  5,
  true
);
select pg_temp.seed_deletion_item(
  'd2000000-0000-0000-0000-000000000002',
  'd1000000-0000-0000-0000-000000000001',
  7
);
select pg_temp.seed_deletion_item(
  'd2000000-0000-0000-0000-000000000003',
  'd1000000-0000-0000-0000-000000000002',
  2
);

insert into public.processing_jobs (
  id,
  owner_id,
  item_id,
  kind,
  target_revision,
  state,
  attempts,
  next_run_at,
  lease_until,
  lease_token
)
values
  (
    'd5000000-0000-0000-0000-000000000001',
    'd1000000-0000-0000-0000-000000000001',
    'd2000000-0000-0000-0000-000000000001',
    'metadata',
    1,
    'running',
    1,
    now(),
    now() + interval '3 minutes',
    'd6000000-0000-0000-0000-000000000001'
  ),
  (
    'd5000000-0000-0000-0000-000000000002',
    'd1000000-0000-0000-0000-000000000001',
    'd2000000-0000-0000-0000-000000000001',
    'classify',
    1,
    'running',
    1,
    now(),
    now() + interval '3 minutes',
    'd6000000-0000-0000-0000-000000000002'
  );

insert into public.assets (
  id,
  owner_id,
  item_id,
  object_path,
  state,
  reserved_mime_type,
  actual_bytes,
  mime_type,
  width,
  height,
  storage_object_id,
  verified_at,
  ocr_state,
  ocr_text,
  reservation_expires_at
)
values
  (
    'd7000000-0000-0000-0000-000000000001',
    'd1000000-0000-0000-0000-000000000001',
    'd2000000-0000-0000-0000-000000000001',
    'd1000000-0000-0000-0000-000000000001/d2000000-0000-0000-0000-000000000001/d7000000-0000-0000-0000-000000000001',
    'active',
    'image/jpeg',
    1000,
    'image/jpeg',
    10,
    10,
    'd8000000-0000-0000-0000-000000000001',
    now(),
    'ready',
    'ocr private text',
    now() + interval '15 minutes'
  ),
  (
    'd7000000-0000-0000-0000-000000000002',
    'd1000000-0000-0000-0000-000000000001',
    'd2000000-0000-0000-0000-000000000001',
    'd1000000-0000-0000-0000-000000000001/d2000000-0000-0000-0000-000000000001/d7000000-0000-0000-0000-000000000002',
    'reserved',
    'image/png',
    null,
    null,
    null,
    null,
    null,
    null,
    'not_requested',
    null,
    now() + interval '15 minutes'
  );

insert into storage.objects (id, bucket_id, name, metadata)
values (
  'd8000000-0000-0000-0000-000000000001',
  'library-images',
  'd1000000-0000-0000-0000-000000000001/d2000000-0000-0000-0000-000000000001/d7000000-0000-0000-0000-000000000001',
  '{"size":1000,"mimetype":"image/jpeg"}'::jsonb
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
  'd1000000-0000-0000-0000-000000000001',
  'd4000000-0000-0000-0000-000000000001',
  'POST /items',
  pg_catalog.encode(
    extensions.digest(
      pg_catalog.convert_to(
        'POST /items' || pg_catalog.chr(10)
          || '{"url": "https://example.test/delete/d2000000-0000-0000-0000-000000000001"}'::jsonb::text,
        'UTF8'
      ),
      'sha256'
    ),
    'hex'
  ),
  201,
  '{"item_id":"d2000000-0000-0000-0000-000000000001","duplicate":false,"http_status":201}'::jsonb
);

select ok(
  has_function_privilege(
    'service_role',
    'public.library_delete_item(uuid,uuid,uuid,jsonb)',
    'EXECUTE'
  )
  and has_function_privilege(
    'service_role',
    'public.library_purge_deleted_items(integer)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated',
    'public.library_delete_item(uuid,uuid,uuid,jsonb)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'public.library_purge_deleted_items(integer)',
    'EXECUTE'
  ),
  'item deletion and purge RPCs are service-role-only'
);

select ok(
  (
    select pg_catalog.bool_and(
      installed_function.prosecdef
      and installed_function.proconfig = array['search_path=""']::text[]
    )
    from pg_catalog.pg_proc as installed_function
    where installed_function.oid in (
      'public.library_delete_item(uuid,uuid,uuid,jsonb)'::regprocedure,
      'public.library_purge_deleted_items(integer)'::regprocedure
    )
  ),
  'item deletion and purge are SECURITY DEFINER with an empty search path'
);

select ok(
  not has_table_privilege(
    'service_role',
    'private.item_deletion_tombstones',
    'SELECT'
  )
  and not has_table_privilege(
    'authenticated',
    'private.item_deletion_tombstones',
    'SELECT'
  ),
  'the content-free item tombstone ledger has no direct API role access'
);

update public.beta_members
set enabled = false
where owner_id = 'd1000000-0000-0000-0000-000000000002';

select throws_ok(
  $$
    select public.library_delete_item(
      'd1000000-0000-0000-0000-000000000002',
      'd4000000-0000-0000-0000-000000000010',
      'd2000000-0000-0000-0000-000000000003',
      '{"expected_version":2}'::jsonb
    )
  $$,
  'P0001',
  'BETA_ACCESS_REQUIRED',
  'SQL rechecks active approved membership instead of trusting the supplied owner UUID'
);

update public.beta_members
set enabled = true
where owner_id = 'd1000000-0000-0000-0000-000000000002';

set local role service_role;

select is(
  public.library_delete_item(
    'd1000000-0000-0000-0000-000000000001',
    'd4000000-0000-0000-0000-000000000003',
    'd2000000-0000-0000-0000-000000000002',
    '{"expected_version":6}'::jsonb
  ),
  '{"http_status":409,"error_code":"VERSION_CONFLICT"}'::jsonb,
  'a stale expected version returns a durable conflict'
);

select is(
  public.library_delete_item(
    'd1000000-0000-0000-0000-000000000001',
    'd4000000-0000-0000-0000-000000000003',
    'd2000000-0000-0000-0000-000000000002',
    '{"expected_version":6}'::jsonb
  ),
  '{"http_status":409,"error_code":"VERSION_CONFLICT"}'::jsonb,
  'the exact version-conflict retry stays 409'
);

select is(
  (
    select pg_catalog.jsonb_build_object(
      'version', item.version,
      'deleted', item.deleted_at is not null,
      'request_count', (
        select count(*)::integer
        from public.api_requests as request
        where request.owner_id = item.owner_id
          and request.request_id = 'd4000000-0000-0000-0000-000000000003'
      )
    )
    from public.items as item
    where item.id = 'd2000000-0000-0000-0000-000000000002'
  ),
  '{"version":7,"deleted":false,"request_count":1}'::jsonb,
  'a sticky conflict changes neither item state nor receipt cardinality'
);

select throws_ok(
  $$
    select public.library_delete_item(
      'd1000000-0000-0000-0000-000000000001',
      'd4000000-0000-0000-0000-000000000003',
      'd2000000-0000-0000-0000-000000000002',
      '{"expected_version":7}'::jsonb
    )
  $$,
  'P0001',
  'IDEMPOTENCY_MISMATCH',
  'the conflict request ID cannot change its raw canonical body'
);

select throws_ok(
  $$
    select public.library_delete_item(
      'd1000000-0000-0000-0000-000000000001',
      'd4000000-0000-0000-0000-000000000003',
      'd2000000-0000-0000-0000-000000000001',
      '{"expected_version":6}'::jsonb
    )
  $$,
  'P0001',
  'IDEMPOTENCY_MISMATCH',
  'the conflict request ID cannot change its canonical path'
);

select throws_ok(
  $$
    select public.library_delete_item(
      'd1000000-0000-0000-0000-000000000002',
      'd4000000-0000-0000-0000-000000000004',
      'd2000000-0000-0000-0000-000000000001',
      '{"expected_version":5}'::jsonb
    )
  $$,
  'P0001',
  'ITEM_NOT_FOUND',
  'owner B cannot delete owner A item by UUID'
);

select throws_ok(
  $$
    select public.library_delete_item(
      'd1000000-0000-0000-0000-000000000001',
      'd4000000-0000-0000-0000-000000000005',
      'd2000000-0000-0000-0000-000000000099',
      '{"expected_version":1}'::jsonb
    )
  $$,
  'P0001',
  'ITEM_NOT_FOUND',
  'a foreign UUID remains an owner-scoped 404'
);

select throws_ok(
  $$
    select public.library_delete_item(
      'd1000000-0000-0000-0000-000000000001',
      'd4000000-0000-0000-0000-000000000006',
      'd2000000-0000-0000-0000-000000000001',
      '{"expected_version":5,"note":"not allowed"}'::jsonb
    )
  $$,
  'P0001',
  'INVALID_BODY',
  'delete accepts only the raw expected_version body'
);

select is(
  public.library_delete_item(
    'd1000000-0000-0000-0000-000000000001',
    'd4000000-0000-0000-0000-000000000002',
    'd2000000-0000-0000-0000-000000000001',
    '{"expected_version":5}'::jsonb
  ),
  '{"http_status":202,"item_id":"d2000000-0000-0000-0000-000000000001","state":"deleting"}'::jsonb,
  'the canonical deletion transaction returns the frozen deleting receipt'
);

select is(
  public.library_delete_item(
    'd1000000-0000-0000-0000-000000000001',
    'd4000000-0000-0000-0000-000000000002',
    'd2000000-0000-0000-0000-000000000001',
    '{"expected_version":5}'::jsonb
  ),
  '{"http_status":202,"item_id":"d2000000-0000-0000-0000-000000000001","state":"deleting"}'::jsonb,
  'the exact deletion retry returns 202 without repeating side effects'
);

select throws_ok(
  $$
    select public.library_retry_metadata(
      'd1000000-0000-0000-0000-000000000001',
      'd4000000-0000-0000-0000-000000000002',
      'd2000000-0000-0000-0000-000000000001',
      '{"expected_version":5}'::jsonb
    )
  $$,
  'P0001',
  'IDEMPOTENCY_MISMATCH',
  'the accepted delete request ID cannot be replayed under another method'
);

select throws_ok(
  $$
    select public.library_delete_item(
      'd1000000-0000-0000-0000-000000000001',
      'd4000000-0000-0000-0000-000000000009',
      'd2000000-0000-0000-0000-000000000001',
      '{"expected_version":6}'::jsonb
    )
  $$,
  'P0001',
  'ITEM_NOT_FOUND',
  'a fresh delete request for an already deleted item is a 404'
);

select is(
  (
    select pg_catalog.jsonb_build_object(
      'version', item.version,
      'deleted', item.deleted_at is not null,
      'private_columns_cleared',
        item.original_url is null
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
    )
    from public.items as item
    where item.owner_id = 'd1000000-0000-0000-0000-000000000001'
      and item.id = 'd2000000-0000-0000-0000-000000000001'
  ),
  '{"version":6,"deleted":true,"private_columns_cleared":true}'::jsonb,
  'deletion increments version and leaves only a content-free item tombstone'
);

select is(
  (
    select usage.active_item_count
    from public.library_usage as usage
    where usage.owner_id = 'd1000000-0000-0000-0000-000000000001'
  ),
  1,
  'active_item_count is decremented exactly once across replay'
);

reset role;

select is(
  (
    select count(*)::integer
    from private.item_deletion_tombstones as tombstone
    where tombstone.owner_id = 'd1000000-0000-0000-0000-000000000001'
      and tombstone.item_id = 'd2000000-0000-0000-0000-000000000001'
  ),
  1,
  'one content-free restore-suppression tombstone is recorded'
);

set local role service_role;

select is(
  (
    select pg_catalog.jsonb_build_object(
      'method_path', request.method_path,
      'response_code', request.response_code,
      'response_body', request.response_body,
      'hash_matches', request.request_hash = pg_catalog.encode(
        extensions.digest(
          pg_catalog.convert_to(
            'DELETE /items/d2000000-0000-0000-0000-000000000001'
              || pg_catalog.chr(10)
              || '{"expected_version": 5}'::jsonb::text,
            'UTF8'
          ),
          'sha256'
        ),
        'hex'
      )
    )
    from public.api_requests as request
    where request.owner_id = 'd1000000-0000-0000-0000-000000000001'
      and request.request_id = 'd4000000-0000-0000-0000-000000000002'
  ),
  '{"method_path":"DELETE /items/d2000000-0000-0000-0000-000000000001","response_code":202,"response_body":{"item_id":"d2000000-0000-0000-0000-000000000001","http_status":202,"state":"deleting"},"hash_matches":true}'::jsonb,
  'the receipt stores only canonical identity, hash, status, item UUID, and deletion state'
);

select is(
  (
    select count(*)::integer
    from public.item_search
    where item_id = 'd2000000-0000-0000-0000-000000000001'
  ) + (
    select count(*)::integer
    from public.item_classification
    where item_id = 'd2000000-0000-0000-0000-000000000001'
  ) + (
    select count(*)::integer
    from public.item_category_controls
    where item_id = 'd2000000-0000-0000-0000-000000000001'
  ) + (
    select count(*)::integer
    from public.item_categories
    where item_id = 'd2000000-0000-0000-0000-000000000001'
  ),
  0,
  'search, classification, cue, and category derived rows disappear immediately'
);

select is(
  (
    select count(*)::integer
    from public.processing_jobs as job
    where job.item_id = 'd2000000-0000-0000-0000-000000000001'
      and job.state = 'cancelled'
      and job.lease_until is null
      and job.lease_token is null
  ),
  2,
  'all ordinary queued/running/retry jobs are cancelled and leases invalidated'
);

select is(
  (
    select count(*)::integer
    from public.assets as asset
    where asset.item_id = 'd2000000-0000-0000-0000-000000000001'
      and asset.state = 'deleting'
      and asset.cleanup_reason = 'item_delete'
      and asset.cleanup_next_run_at is not null
      and asset.cleanup_lease_until is null
      and asset.cleanup_lease_token is null
      and asset.ocr_state = 'not_requested'
      and asset.ocr_text is null
      and not asset.ocr_truncated
  ),
  2,
  'reserved and active assets enter M4 cleanup with OCR and leases invalidated'
);

select is(
  (
    select pg_catalog.jsonb_build_object(
      'used', usage.used_image_bytes,
      'reserved', usage.reserved_image_bytes
    )
    from public.library_usage as usage
    where usage.owner_id = 'd1000000-0000-0000-0000-000000000001'
  ),
  '{"used":1000,"reserved":2000000}'::jsonb,
  'asset byte counters remain charged until physical cleanup succeeds'
);

select is(
  public.library_complete_classification_job(
    'd5000000-0000-0000-0000-000000000002',
    'd6000000-0000-0000-0000-000000000002',
    5,
    '{}'::jsonb
  ) ->> 'state',
  'discarded',
  'a stale classification lease cannot resurrect a deleted item'
);

select is(
  public.library_complete_metadata_job(
    'd5000000-0000-0000-0000-000000000001',
    'd6000000-0000-0000-0000-000000000001',
    5,
    '{}'::jsonb,
    '{}'::jsonb
  ) ->> 'state',
  'discarded_stale',
  'a stale metadata lease cannot resurrect deleted private content'
);

select throws_ok(
  $$
    select public.library_create_item(
      'd1000000-0000-0000-0000-000000000001',
      'd4000000-0000-0000-0000-000000000001',
      '{"url":"https://example.test/delete/d2000000-0000-0000-0000-000000000001"}'::jsonb,
      '{}'::jsonb
    )
  $$,
  'P0001',
  'ITEM_DELETED',
  'an old create receipt resolves to 410 while its content-free tombstone row is retained'
);

select throws_ok(
  $$
    select public.library_retry_metadata(
      'd1000000-0000-0000-0000-000000000001',
      'd4000000-0000-0000-0000-000000000007',
      'd2000000-0000-0000-0000-000000000001',
      '{"expected_version":6}'::jsonb
    )
  $$,
  'P0001',
  'ITEM_DELETED',
  'a fresh write to the retained deleted item remains 410'
);

reset role;
set local role authenticated;
set local "request.jwt.claim.sub" = 'd1000000-0000-0000-0000-000000000001';

select is(
  (select count(*)::integer from public.items where id = 'd2000000-0000-0000-0000-000000000001')
  + (select count(*)::integer from public.item_search where item_id = 'd2000000-0000-0000-0000-000000000001')
  + (select count(*)::integer from public.item_classification where item_id = 'd2000000-0000-0000-0000-000000000001')
  + (select count(*)::integer from public.item_categories where item_id = 'd2000000-0000-0000-0000-000000000001')
  + (select count(*)::integer from public.assets where item_id = 'd2000000-0000-0000-0000-000000000001'),
  0,
  'owner A direct RLS reads exclude the deleted item, derived data, and assets immediately'
);

select throws_ok(
  $$select public.library_get_item('d2000000-0000-0000-0000-000000000001')$$,
  'P0001',
  'ITEM_NOT_FOUND',
  'the direct item read RPC excludes the deleted item immediately'
);

select is(
  (
    select count(*)::integer
    from pg_catalog.jsonb_array_elements(
      public.library_search_items(
        '{"query":"","terms":[],"groups":[]}'::jsonb,
        '{}'::jsonb,
        20,
        0
      ) -> 'items'
    ) as summary(value)
    where summary.value ->> 'id' = 'd2000000-0000-0000-0000-000000000001'
  ),
  0,
  'the search RPC excludes the deleted item immediately'
);

select ok(
  not public.library_image_upload_allowed(
    'library-images',
    'd1000000-0000-0000-0000-000000000001/d2000000-0000-0000-0000-000000000001/d7000000-0000-0000-0000-000000000002'
  ),
  'the upload proxy no longer accepts a reservation for the deleted parent'
);

reset role;
set local role authenticated;
set local "request.jwt.claim.sub" = 'd1000000-0000-0000-0000-000000000002';

select is(
  (select count(*)::integer from public.items where id = 'd2000000-0000-0000-0000-000000000001')
  + (select count(*)::integer from public.assets where item_id = 'd2000000-0000-0000-0000-000000000001'),
  0,
  'owner B also sees no trace of owner A deleted item or attachment'
);

reset role;
set local role service_role;
set local "request.jwt.claim.sub" = '';

create temporary table deletion_cleanup_claims (
  payload jsonb not null
) on commit drop;

insert into deletion_cleanup_claims (payload)
values (public.library_claim_asset_cleanup_jobs(10));

select is(
  pg_catalog.jsonb_array_length((select payload -> 'jobs' from deletion_cleanup_claims)),
  2,
  'the existing M4 cleanup worker claims both item-deletion assets'
);

select is(
  public.library_finish_asset_cleanup(
    'd7000000-0000-0000-0000-000000000001',
    (
      select (job.value ->> 'lease_token')::uuid
      from deletion_cleanup_claims as claim
      cross join lateral pg_catalog.jsonb_array_elements(claim.payload -> 'jobs') as job(value)
      where job.value ->> 'asset_id' = 'd7000000-0000-0000-0000-000000000001'
    )
  ) ->> 'error_code',
  'STORAGE_OBJECT_PRESENT',
  'cleanup cannot release charged bytes while the Storage object still exists'
);

delete from storage.objects
where bucket_id = 'library-images'
  and name = 'd1000000-0000-0000-0000-000000000001/d2000000-0000-0000-0000-000000000001/d7000000-0000-0000-0000-000000000001';

select is(
  public.library_finish_asset_cleanup(
    'd7000000-0000-0000-0000-000000000001',
    (
      select (job.value ->> 'lease_token')::uuid
      from deletion_cleanup_claims as claim
      cross join lateral pg_catalog.jsonb_array_elements(claim.payload -> 'jobs') as job(value)
      where job.value ->> 'asset_id' = 'd7000000-0000-0000-0000-000000000001'
    )
  ) ->> 'state',
  'complete',
  'physical active-asset cleanup completes after object removal'
);

select is(
  public.library_finish_asset_cleanup(
    'd7000000-0000-0000-0000-000000000002',
    (
      select (job.value ->> 'lease_token')::uuid
      from deletion_cleanup_claims as claim
      cross join lateral pg_catalog.jsonb_array_elements(claim.payload -> 'jobs') as job(value)
      where job.value ->> 'asset_id' = 'd7000000-0000-0000-0000-000000000002'
    )
  ) ->> 'state',
  'complete',
  'physical reserved-asset cleanup completes without an object'
);

select is(
  (
    select pg_catalog.jsonb_build_object(
      'used', usage.used_image_bytes,
      'reserved', usage.reserved_image_bytes
    )
    from public.library_usage as usage
    where usage.owner_id = 'd1000000-0000-0000-0000-000000000001'
  ),
  '{"used":0,"reserved":0}'::jsonb,
  'M4 cleanup releases each charged byte counter exactly once'
);

reset role;

update public.items
set deleted_at = now() - interval '31 days',
    updated_at = now() - interval '31 days'
where owner_id = 'd1000000-0000-0000-0000-000000000001'
  and id = 'd2000000-0000-0000-0000-000000000001';

update private.item_deletion_tombstones
set deleted_at = now() - interval '31 days'
where owner_id = 'd1000000-0000-0000-0000-000000000001'
  and item_id = 'd2000000-0000-0000-0000-000000000001';

insert into public.items (
  id,
  owner_id,
  extraction_meta,
  text_revision,
  version,
  created_at,
  updated_at,
  deleted_at
) values (
  'd2000000-0000-0000-0000-000000000004',
  'd1000000-0000-0000-0000-000000000001',
  '{}'::jsonb,
  1,
  2,
  now() - interval '31 days',
  now() - interval '31 days',
  now() - interval '31 days'
);

insert into private.item_deletion_tombstones (owner_id, item_id, deleted_at)
values (
  'd1000000-0000-0000-0000-000000000001',
  'd2000000-0000-0000-0000-000000000004',
  now() - interval '31 days'
);

insert into storage.objects (id, bucket_id, name, metadata)
values (
  'd8000000-0000-0000-0000-000000000004',
  'library-images',
  'd1000000-0000-0000-0000-000000000001/d2000000-0000-0000-0000-000000000004/orphaned-object',
  '{"size":1000,"mimetype":"image/jpeg"}'::jsonb
);

select is(
  public.library_purge_deleted_items(10),
  '{"purged_items":0}'::jsonb,
  'purge retains deleted rows while idempotency receipts or Storage objects remain'
);

select is(
  public.library_delete_item(
    'd1000000-0000-0000-0000-000000000001',
    'd4000000-0000-0000-0000-000000000002',
    'd2000000-0000-0000-0000-000000000001',
    '{"expected_version":5}'::jsonb
  ),
  '{"http_status":202,"item_id":"d2000000-0000-0000-0000-000000000001","state":"deleting"}'::jsonb,
  'the accepted delete still replays while purge preserves its receipt'
);

select is(
  (
    select count(*)::integer
    from private.item_deletion_tombstones
    where owner_id = 'd1000000-0000-0000-0000-000000000001'
      and item_id in (
        'd2000000-0000-0000-0000-000000000001',
        'd2000000-0000-0000-0000-000000000004'
      )
  ),
  2,
  'purge preserves the separate content-free 30-day tombstones'
);

delete from public.api_requests
where owner_id = 'd1000000-0000-0000-0000-000000000001'
  and response_body ->> 'item_id' = 'd2000000-0000-0000-0000-000000000001';

select is(
  public.library_purge_deleted_items(10),
  '{"purged_items":1}'::jsonb,
  'after receipt expiry, purge removes only the old asset-free and object-free item row'
);

select ok(
  not exists (
    select 1
    from public.items
    where owner_id = 'd1000000-0000-0000-0000-000000000001'
      and id = 'd2000000-0000-0000-0000-000000000001'
  )
  and exists (
    select 1
    from private.item_deletion_tombstones
    where owner_id = 'd1000000-0000-0000-0000-000000000001'
      and item_id = 'd2000000-0000-0000-0000-000000000001'
  )
  and exists (
    select 1
    from public.items
    where owner_id = 'd1000000-0000-0000-0000-000000000001'
      and id = 'd2000000-0000-0000-0000-000000000004'
  ),
  'physical purge keeps the separate tombstone and refuses the orphaned Storage prefix'
);

select throws_ok(
  $$
    select public.library_delete_item(
      'd1000000-0000-0000-0000-000000000001',
      'd4000000-0000-0000-0000-000000000008',
      'd2000000-0000-0000-0000-000000000001',
      '{"expected_version":6}'::jsonb
    )
  $$,
  'P0001',
  'ITEM_NOT_FOUND',
  'a fresh delete after physical purge remains an owner-scoped 404'
);

select ok(
  exists (
    select 1
    from public.items
    where id = 'd2000000-0000-0000-0000-000000000002'
      and deleted_at is null
  )
  and exists (
    select 1
    from public.items
    where id = 'd2000000-0000-0000-0000-000000000003'
      and deleted_at is null
  ),
  'purge leaves active items for both owners untouched'
);

select * from finish();
rollback;
