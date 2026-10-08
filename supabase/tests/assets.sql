begin;

create extension if not exists pgtap with schema extensions;
set local search_path = pg_catalog, extensions, public;
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
    'a1000000-0000-0000-0000-000000000001',
    'authenticated',
    'authenticated',
    'assets-a@example.test',
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
    'a1000000-0000-0000-0000-000000000002',
    'authenticated',
    'authenticated',
    'assets-b@example.test',
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
  ('a1000000-0000-0000-0000-000000000001', true, now()),
  ('a1000000-0000-0000-0000-000000000002', true, now());

insert into public.profiles (id, state)
values
  ('a1000000-0000-0000-0000-000000000001', 'active'),
  ('a1000000-0000-0000-0000-000000000002', 'active');

insert into public.library_usage (owner_id)
values
  ('a1000000-0000-0000-0000-000000000001'),
  ('a1000000-0000-0000-0000-000000000002');

insert into public.categories (
  id,
  owner_id,
  name,
  normalized_name,
  kind
)
values (
  'a3000000-0000-0000-0000-000000000001',
  'a1000000-0000-0000-0000-000000000001',
  '수동 분류',
  '수동 분류',
  'custom'
);

create function pg_temp.asset_empty_normalized_fields(
  p_ocr text default ''
)
returns jsonb
language sql
stable
as $$
  select pg_catalog.jsonb_build_object(
    'user_title', '',
    'fetched_title', '',
    'note', 'seed note',
    'ocr', p_ocr,
    'shared', '',
    'description', '',
    'body', '',
    'categories', '',
    'url', 'https example test'
  );
$$;

create function pg_temp.asset_empty_alias_concepts(
  p_ocr jsonb default '[]'::jsonb
)
returns jsonb
language sql
stable
as $$
  select pg_catalog.jsonb_build_object(
    'user_title', '[]'::jsonb,
    'fetched_title', '[]'::jsonb,
    'note', '[]'::jsonb,
    'ocr', p_ocr,
    'shared', '[]'::jsonb,
    'description', '[]'::jsonb,
    'body', '[]'::jsonb
  );
$$;

create function pg_temp.seed_asset_item(
  p_item_id uuid,
  p_owner_id uuid,
  p_version integer default 1,
  p_manual boolean default false,
  p_metadata_active boolean default false
)
returns void
language plpgsql
as $$
declare
  url_value text := 'https://example.test/assets/' || p_item_id::text;
begin
  insert into public.items (
    id,
    owner_id,
    original_url,
    normalized_url,
    url_hash,
    source,
    display_fallback,
    note,
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
    'example.test',
    'seed note',
    1,
    p_version,
    case when p_metadata_active then 'queued' else 'unsupported' end
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
    pg_temp.asset_empty_normalized_fields(),
    pg_temp.asset_empty_alias_concepts(),
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
    case when p_manual then 'manual' else 'pending' end,
    '[]'::jsonb
  );

  insert into public.item_category_controls (
    owner_id,
    item_id,
    manual_override
  ) values (p_owner_id, p_item_id, p_manual);

  if p_manual then
    update public.item_search
    set normalized_fields = normalized_fields
      || pg_catalog.jsonb_build_object('categories', '수동 분류')
    where owner_id = p_owner_id
      and item_id = p_item_id;

    insert into public.item_categories (
      owner_id,
      item_id,
      category_id,
      origin
    ) values (
      p_owner_id,
      p_item_id,
      'a3000000-0000-0000-0000-000000000001',
      'manual'
    );
  end if;

  if p_metadata_active then
    insert into public.processing_jobs (
      owner_id,
      item_id,
      kind,
      target_revision,
      state
    ) values (p_owner_id, p_item_id, 'metadata', 1, 'queued');
  end if;
end;
$$;

create function pg_temp.asset_prepared_index(
  p_item_id uuid,
  p_ocr_text text
)
returns jsonb
language plpgsql
stable
as $$
declare
  item_version integer;
  current_fields jsonb;
  current_aliases jsonb;
  current_cue_state text;
  current_cue_flags jsonb;
  normalized_ocr text;
begin
  select
    item.version,
    search_record.normalized_fields,
    search_record.alias_concepts,
    search_record.cue_state,
    search_record.cue_flags
  into
    item_version,
    current_fields,
    current_aliases,
    current_cue_state,
    current_cue_flags
  from public.items as item
  join public.item_search as search_record
    on search_record.owner_id = item.owner_id
    and search_record.item_id = item.id
  where item.id = p_item_id;

  normalized_ocr := pg_catalog.btrim(
    pg_catalog.regexp_replace(
      pg_catalog.lower(normalize(coalesce(p_ocr_text, ''), NFKC)),
      '[[:space:]]+',
      ' ',
      'g'
    )
  );

  return pg_catalog.jsonb_build_object(
    'snapshot_version', item_version,
    'normalized_fields', current_fields
      || pg_catalog.jsonb_build_object('ocr', normalized_ocr),
    'alias_concepts', current_aliases
      || pg_catalog.jsonb_build_object('ocr', '[]'::jsonb),
    'cue_state', current_cue_state,
    'cue_flags', current_cue_flags
  );
end;
$$;

create function pg_temp.seed_active_asset(
  p_asset_id uuid,
  p_item_id uuid,
  p_owner_id uuid,
  p_bytes bigint,
  p_ocr_text text
)
returns void
language plpgsql
as $$
declare
  path_value text := p_owner_id::text || '/' || p_item_id::text || '/' || p_asset_id::text;
  object_id_value uuid := extensions.gen_random_uuid();
begin
  insert into storage.objects (id, bucket_id, name, metadata)
  values (
    object_id_value,
    'library-images',
    path_value,
    pg_catalog.jsonb_build_object('size', p_bytes, 'mimetype', 'image/jpeg')
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
  ) values (
    p_asset_id,
    p_owner_id,
    p_item_id,
    path_value,
    'active',
    'image/jpeg',
    p_bytes,
    'image/jpeg',
    100,
    100,
    object_id_value,
    now(),
    'ready',
    p_ocr_text,
    now() + interval '15 minutes'
  );

  update public.item_search
  set normalized_fields = normalized_fields
        || pg_catalog.jsonb_build_object('ocr', coalesce(p_ocr_text, '')),
      alias_concepts = alias_concepts
        || pg_catalog.jsonb_build_object('ocr', '["android"]'::jsonb)
  where owner_id = p_owner_id
    and item_id = p_item_id;

  update public.library_usage
  set used_image_bytes = used_image_bytes + p_bytes
  where owner_id = p_owner_id;
end;
$$;

create temporary table asset_test_results (
  result_name text primary key,
  result jsonb not null
);
grant select, insert, update on asset_test_results to service_role;
grant select on asset_test_results to authenticated;

select is(
  (
    select bucket.public
    from storage.buckets as bucket
    where bucket.id = 'library-images'
  ),
  false,
  'library-images is private'
);

select is(
  (
    select bucket.file_size_limit
    from storage.buckets as bucket
    where bucket.id = 'library-images'
  ),
  2000000::bigint,
  'library-images enforces the decimal 2 MB object limit'
);

select is(
  (
    select bucket.allowed_mime_types
    from storage.buckets as bucket
    where bucket.id = 'library-images'
  ),
  array['image/jpeg', 'image/png', 'image/webp']::text[],
  'library-images only accepts the three frozen image MIME types'
);

select ok(
  not has_table_privilege('authenticated', 'public.assets', 'INSERT')
  and not has_table_privilege('authenticated', 'public.assets', 'UPDATE')
  and not has_table_privilege('authenticated', 'public.assets', 'DELETE'),
  'authenticated callers cannot mutate asset lifecycle rows directly'
);

select ok(
  not has_function_privilege(
    'authenticated',
    'public.library_reserve_asset(uuid,uuid,uuid,jsonb)',
    'EXECUTE'
  )
  and has_function_privilege(
    'service_role',
    'public.library_reserve_asset(uuid,uuid,uuid,jsonb)',
    'EXECUTE'
  ),
  'asset transaction RPCs are service-role-only'
);

select is(
  (
    select count(*)::integer
    from pg_catalog.pg_policies as policy
    where policy.schemaname = 'storage'
      and policy.tablename = 'objects'
      and policy.policyname = 'library_images_insert_reserved_exact'
      and policy.cmd = 'INSERT'
  ),
  1,
  'the bucket has one exact reserved-path insert policy'
);

select is(
  (
    select count(*)::integer
    from pg_catalog.pg_policies as policy
    where policy.schemaname = 'storage'
      and policy.tablename = 'objects'
      and policy.policyname like 'library_images_%'
      and policy.cmd in ('SELECT', 'UPDATE', 'DELETE', 'ALL')
  ),
  0,
  'the bucket exposes no client list/read/upsert/delete policy; downloads use the API proxy'
);

select pg_temp.seed_asset_item(
  'a2000000-0000-0000-0000-000000000001',
  'a1000000-0000-0000-0000-000000000001',
  7,
  true,
  true
);
select pg_temp.seed_asset_item(
  'a2000000-0000-0000-0000-000000000002',
  'a1000000-0000-0000-0000-000000000001'
);
select pg_temp.seed_asset_item(
  'a2000000-0000-0000-0000-000000000003',
  'a1000000-0000-0000-0000-000000000002'
);

update public.library_usage
set used_image_bytes = 17000000
where owner_id = 'a1000000-0000-0000-0000-000000000001';

set local role service_role;

insert into asset_test_results (result_name, result)
values (
  'quota-first-reserve',
  public.library_reserve_asset(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000001',
    'a2000000-0000-0000-0000-000000000001',
    '{"expected_version":7,"mime_type":"image/jpeg"}'::jsonb
  )
);

select is(
  (select (result ->> 'http_status')::integer from asset_test_results where result_name = 'quota-first-reserve'),
  201,
  'the first 2 MB reservation wins with 3 MB remaining'
);

select ok(
  (
    select (result ->> 'expires_at')::timestamptz
    from asset_test_results
    where result_name = 'quota-first-reserve'
  ) between pg_catalog.clock_timestamp() + interval '14 minutes 55 seconds'
    and pg_catalog.clock_timestamp() + interval '15 minutes 5 seconds',
  'reservation expiry is fifteen minutes'
);

select is(
  (
    select item.version
    from public.items as item
    where item.id = 'a2000000-0000-0000-0000-000000000001'
  ),
  7,
  'reserving does not bump the item version'
);

select is(
  (
    select result ->> 'object_path'
    from asset_test_results
    where result_name = 'quota-first-reserve'
  ),
  'a1000000-0000-0000-0000-000000000001/a2000000-0000-0000-0000-000000000001/'
    || (
      select result ->> 'asset_id'
      from asset_test_results
      where result_name = 'quota-first-reserve'
    ),
  'the object path is only owner/item/server asset UUID'
);

select is(
  public.library_reserve_asset(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000018',
    'a2000000-0000-0000-0000-000000000001',
    '{"expected_version":7,"mime_type":"image/png"}'::jsonb
  ) ->> 'error_code',
  'ASSET_RESERVATION_EXISTS',
  'an item can have only one live reservation'
);

insert into asset_test_results (result_name, result)
values (
  'quota-second-reserve',
  public.library_reserve_asset(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000002',
    'a2000000-0000-0000-0000-000000000002',
    '{"expected_version":1,"mime_type":"image/jpeg"}'::jsonb
  )
);

select is(
  (select result ->> 'error_code' from asset_test_results where result_name = 'quota-second-reserve'),
  'STORAGE_LIMIT_REACHED',
  'the competing fixed 2 MB reservation loses when only 1 MB remains'
);

select is(
  (
    select reserved_image_bytes
    from public.library_usage
    where owner_id = 'a1000000-0000-0000-0000-000000000001'
  ),
  2000000::bigint,
  'the losing reservation does not overcount quota'
);

select is(
  public.library_reserve_asset(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000002',
    'a2000000-0000-0000-0000-000000000002',
    '{"expected_version":1,"mime_type":"image/jpeg"}'::jsonb
  ) ->> 'error_code',
  'STORAGE_LIMIT_REACHED',
  'a deterministic quota 409 is sticky on replay'
);

select throws_ok(
  $$
    select public.library_reserve_asset(
      'a1000000-0000-0000-0000-000000000001',
      'aa000000-0000-0000-0000-000000000002',
      'a2000000-0000-0000-0000-000000000002',
      '{"expected_version":1,"mime_type":"image/png"}'::jsonb
    )
  $$,
  'P0001',
  'IDEMPOTENCY_MISMATCH',
  'the same request ID cannot change its canonical body'
);

select throws_ok(
  $$
    select public.library_reserve_asset(
      'a1000000-0000-0000-0000-000000000002',
      'aa000000-0000-0000-0000-000000000003',
      'a2000000-0000-0000-0000-000000000001',
      '{"expected_version":7,"mime_type":"image/jpeg"}'::jsonb
    )
  $$,
  'P0001',
  'ITEM_NOT_FOUND',
  'a service call fenced to owner B cannot reserve against owner A item'
);

reset role;
set local role authenticated;
set local "request.jwt.claim.sub" = 'a1000000-0000-0000-0000-000000000001';

select ok(
  public.library_image_upload_allowed(
    'library-images',
    (
      select result ->> 'object_path'
      from asset_test_results
      where result_name = 'quota-first-reserve'
    )
  ),
  'the approved owner may create the exact unexpired reserved path'
);

select ok(
  not public.library_image_upload_allowed(
    'library-images',
    'a1000000-0000-0000-0000-000000000001/a2000000-0000-0000-0000-000000000001/client-file-name.jpg'
  ),
  'a client-reported file name or arbitrary path is rejected'
);

select is(
  (select count(*)::integer from public.assets),
  0,
  'reserved assets are hidden from direct authenticated reads'
);

reset role;
set local role authenticated;
set local "request.jwt.claim.sub" = 'a1000000-0000-0000-0000-000000000002';

select ok(
  not public.library_image_upload_allowed(
    'library-images',
    (
      select result ->> 'object_path'
      from asset_test_results
      where result_name = 'quota-first-reserve'
    )
  ),
  'another owner cannot upload to a reserved path'
);

reset role;
set local role service_role;
set local "request.jwt.claim.sub" = '';

insert into storage.objects (id, bucket_id, name, metadata)
select
  extensions.gen_random_uuid(),
  'library-images',
  result ->> 'object_path',
  '{"size":1000,"mimetype":"image/jpeg"}'::jsonb
from asset_test_results
where result_name = 'quota-first-reserve';

insert into asset_test_results (result_name, result)
select
  'initial-complete',
  public.library_complete_asset(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000004',
    'a2000000-0000-0000-0000-000000000001',
    (reserved.result ->> 'asset_id')::uuid,
    pg_catalog.jsonb_build_object(
      'expected_version', 7,
      'ocr_state', 'ready',
      'ocr_text', pg_catalog.repeat('가', 20001)
    ),
    pg_catalog.jsonb_build_object(
      'object_path', reserved.result ->> 'object_path',
      'size', 1000,
      'mime_type', 'image/jpeg',
      'width', 100,
      'height', 100,
      'present', true
    ),
    pg_temp.asset_prepared_index(
      'a2000000-0000-0000-0000-000000000001',
      pg_catalog.repeat('가', 20000)
    )
  )
from asset_test_results as reserved
where reserved.result_name = 'quota-first-reserve';

select is(
  (select (result ->> 'http_status')::integer from asset_test_results where result_name = 'initial-complete'),
  200,
  'a server-verified object completes successfully'
);

select is(
  (
    select pg_catalog.char_length(asset.ocr_text)
    from public.assets as asset
    where asset.state = 'active'
      and asset.item_id = 'a2000000-0000-0000-0000-000000000001'
  ),
  20000,
  'OCR is truncated to 20,000 characters'
);

select is(
  (
    select asset.ocr_truncated
    from public.assets as asset
    where asset.state = 'active'
      and asset.item_id = 'a2000000-0000-0000-0000-000000000001'
  ),
  true,
  'OCR truncation provenance is stored'
);

select is(
  (
    select pg_catalog.jsonb_build_array(item.version, item.text_revision)
    from public.items as item
    where item.id = 'a2000000-0000-0000-0000-000000000001'
  ),
  '[8,2]'::jsonb,
  'complete advances item version and text revision once'
);

select is(
  (
    select pg_catalog.jsonb_build_array(usage.used_image_bytes, usage.reserved_image_bytes)
    from public.library_usage as usage
    where usage.owner_id = 'a1000000-0000-0000-0000-000000000001'
  ),
  '[17001000,0]'::jsonb,
  'complete swaps fixed reserved quota for actual bytes'
);

select is(
  (
    select controls.manual_override
    from public.item_category_controls as controls
    where controls.item_id = 'a2000000-0000-0000-0000-000000000001'
  ),
  true,
  'complete preserves the manual category lock'
);

select is(
  (
    select count(*)::integer
    from public.item_categories as selected
    where selected.item_id = 'a2000000-0000-0000-0000-000000000001'
      and selected.category_id = 'a3000000-0000-0000-0000-000000000001'
      and selected.origin = 'manual'
  ),
  1,
  'complete preserves current manual categories'
);

select is(
  (
    select count(*)::integer
    from public.processing_jobs as job
    where job.item_id = 'a2000000-0000-0000-0000-000000000001'
      and job.kind = 'metadata'
      and job.target_revision = 2
      and job.state in ('queued', 'running', 'retry')
  ),
  1,
  'complete revokes stale active metadata and ensures one current metadata job'
);

select is(
  (
    select count(*)::integer
    from public.processing_jobs as job
    where job.item_id = 'a2000000-0000-0000-0000-000000000001'
      and job.kind = 'classify'
      and job.state in ('queued', 'running', 'retry')
  ),
  0,
  'manual classification prevents a replacement classify job'
);

reset role;
set local role authenticated;
set local "request.jwt.claim.sub" = 'a1000000-0000-0000-0000-000000000001';

select is(
  public.library_get_item('a2000000-0000-0000-0000-000000000001')
    ->> 'has_attachment',
  'true',
  'detail projection exposes the current active attachment summary'
);

select is(
  public.library_get_item('a2000000-0000-0000-0000-000000000001')
    #>> '{active_asset,byte_size}',
  '1000',
  'detail projection exposes authoritative actual bytes'
);

select is(
  (
    select pg_catalog.jsonb_build_array(
      summary.value ->> 'has_attachment',
      summary.value ->> 'ocr_state'
    )
    from pg_catalog.jsonb_array_elements(
      public.library_search_items(
        '{"query":"","terms":[],"groups":[]}'::jsonb,
        '{}'::jsonb,
        20,
        0
      ) -> 'items'
    ) as summary(value)
    where summary.value ->> 'id' = 'a2000000-0000-0000-0000-000000000001'
  ),
  '["true","ready"]'::jsonb,
  'public search summaries derive attachment and OCR state from the shared projection'
);

select is(
  (select count(*)::integer from public.assets),
  1,
  'owner A sees only the active asset row through RLS'
);

select ok(
  not public.library_image_upload_allowed(
    'library-images',
    public.library_get_item('a2000000-0000-0000-0000-000000000001')
      #>> '{active_asset,object_path}'
  ),
  'an active path cannot be uploaded again, so upsert is unavailable'
);

reset role;
set local role authenticated;
set local "request.jwt.claim.sub" = 'a1000000-0000-0000-0000-000000000002';

select is(
  (select count(*)::integer from public.assets),
  0,
  'owner B cannot read owner A active asset metadata'
);

reset role;
set local role service_role;
set local "request.jwt.claim.sub" = '';

insert into asset_test_results (result_name, result)
values (
  'failed-replacement-reserve',
  public.library_reserve_asset(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000005',
    'a2000000-0000-0000-0000-000000000001',
    '{"expected_version":8,"mime_type":"image/jpeg"}'::jsonb
  )
);

insert into asset_test_results (result_name, result)
select
  'failed-replacement',
  public.library_reject_asset_upload(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000006',
    'a2000000-0000-0000-0000-000000000001',
    (reserved.result ->> 'asset_id')::uuid,
    '{"expected_version":8,"ocr_state":"failed"}'::jsonb,
    'ASSET_DECODE_FAILED'
  )
from asset_test_results as reserved
where reserved.result_name = 'failed-replacement-reserve';

select is(
  (select result ->> 'error_code' from asset_test_results where result_name = 'failed-replacement'),
  'ASSET_DECODE_FAILED',
  'the Edge verifier records a fixed invalid-file failure code'
);

select is(
  (
    select count(*)::integer
    from public.assets as asset
    where asset.item_id = 'a2000000-0000-0000-0000-000000000001'
      and asset.state = 'active'
      and asset.ocr_state = 'ready'
      and pg_catalog.char_length(asset.ocr_text) = 20000
  ),
  1,
  'failed replacement retains the previous active image and OCR'
);

select is(
  (
    select reserved_image_bytes
    from public.library_usage
    where owner_id = 'a1000000-0000-0000-0000-000000000001'
  ),
  2000000::bigint,
  'failed replacement reservation stays charged until absence is confirmed'
);

select is(
  public.library_reject_asset_upload(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000006',
    'a2000000-0000-0000-0000-000000000001',
    (
      select (result ->> 'asset_id')::uuid
      from asset_test_results
      where result_name = 'failed-replacement-reserve'
    ),
    '{"expected_version":8,"ocr_state":"failed"}'::jsonb,
    'ASSET_DECODE_FAILED'
  ) ->> 'error_code',
  'ASSET_DECODE_FAILED',
  'the invalid-file 409 replays without another lifecycle change'
);

select throws_ok(
  $$
    select public.library_reject_asset_upload(
      'a1000000-0000-0000-0000-000000000001',
      'aa000000-0000-0000-0000-000000000006',
      'a2000000-0000-0000-0000-000000000001',
      (
        select (result ->> 'asset_id')::uuid
        from asset_test_results
        where result_name = 'failed-replacement-reserve'
      ),
      '{"expected_version":8,"ocr_state":"not_requested"}'::jsonb,
      'ASSET_DECODE_FAILED'
    )
  $$,
  'P0001',
  'IDEMPOTENCY_MISMATCH',
  'the complete-path failure receipt rejects a changed raw body'
);

with response as materialized (
  select public.library_claim_asset_cleanup_jobs(10) as value
), matching_job as (
  select job.value
  from response
  cross join lateral pg_catalog.jsonb_array_elements(response.value -> 'jobs') as job(value)
  where job.value ->> 'asset_id' = (
    select result ->> 'asset_id'
    from asset_test_results
    where result_name = 'failed-replacement-reserve'
  )
)
insert into asset_test_results (result_name, result)
select 'failed-replacement-claim', value
from matching_job;

select ok(
  (
    select (result ->> 'lease_until')::timestamptz
    from asset_test_results
    where result_name = 'failed-replacement-claim'
  ) between pg_catalog.clock_timestamp() + interval '175 seconds'
    and pg_catalog.clock_timestamp() + interval '181 seconds',
  'cleanup claim issues a 180 second lease'
);

insert into asset_test_results (result_name, result)
select
  'failed-replacement-finish',
  public.library_finish_asset_cleanup(
    (claim.result ->> 'asset_id')::uuid,
    (claim.result ->> 'lease_token')::uuid
  )
from asset_test_results as claim
where claim.result_name = 'failed-replacement-claim';

select is(
  (
    select pg_catalog.jsonb_build_array(usage.used_image_bytes, usage.reserved_image_bytes)
    from public.library_usage as usage
    where usage.owner_id = 'a1000000-0000-0000-0000-000000000001'
  ),
  '[17001000,0]'::jsonb,
  'absence-confirmed cleanup releases only the failed reservation counter'
);

select is(
  public.library_finish_asset_cleanup(
    (
      select (result ->> 'asset_id')::uuid
      from asset_test_results
      where result_name = 'failed-replacement-claim'
    ),
    (
      select (result ->> 'lease_token')::uuid
      from asset_test_results
      where result_name = 'failed-replacement-claim'
    )
  ),
  (
    select pg_catalog.jsonb_build_object(
      'http_status', 200,
      'state', 'complete',
      'asset_id', (result ->> 'asset_id')::uuid,
      'released_reserved_bytes', 2000000,
      'released_used_bytes', 0
    )
    from asset_test_results
    where result_name = 'failed-replacement-claim'
  ),
  'cleanup finish is idempotent and cannot release counters twice'
);

insert into asset_test_results (result_name, result)
values (
  'conflict-reserve',
  public.library_reserve_asset(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000007',
    'a2000000-0000-0000-0000-000000000001',
    '{"expected_version":8,"mime_type":"image/jpeg"}'::jsonb
  )
);

insert into storage.objects (id, bucket_id, name, metadata)
select
  extensions.gen_random_uuid(),
  'library-images',
  result ->> 'object_path',
  '{"size":900,"mimetype":"image/jpeg"}'::jsonb
from asset_test_results
where result_name = 'conflict-reserve';

update public.items
set version = version + 1
where id = 'a2000000-0000-0000-0000-000000000001';

insert into asset_test_results (result_name, result)
select
  'complete-version-conflict',
  public.library_complete_asset(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000008',
    'a2000000-0000-0000-0000-000000000001',
    (reserved.result ->> 'asset_id')::uuid,
    '{"expected_version":8,"ocr_state":"not_requested"}'::jsonb,
    pg_catalog.jsonb_build_object(
      'object_path', reserved.result ->> 'object_path',
      'size', 900,
      'mime_type', 'image/jpeg',
      'width', 100,
      'height', 100,
      'present', true
    ),
    pg_temp.asset_prepared_index(
      'a2000000-0000-0000-0000-000000000001',
      ''
    )
  )
from asset_test_results as reserved
where reserved.result_name = 'conflict-reserve';

select is(
  (select result ->> 'error_code' from asset_test_results where result_name = 'complete-version-conflict'),
  'VERSION_CONFLICT',
  'complete returns a deterministic version conflict'
);

select is(
  (
    select count(*)::integer
    from public.assets as asset
    where asset.item_id = 'a2000000-0000-0000-0000-000000000001'
      and asset.state = 'active'
  ),
  1,
  'complete conflict keeps the previous active image'
);

select is(
  (
    select count(*)::integer
    from public.assets as asset
    where asset.id = (
      select (result ->> 'asset_id')::uuid
      from asset_test_results
      where result_name = 'conflict-reserve'
    )
      and asset.state = 'deleting'
      and asset.cleanup_reason = 'version_conflict'
  ),
  1,
  'complete conflict invalidates only the new reservation for cleanup'
);

select is(
  public.library_complete_asset(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000008',
    'a2000000-0000-0000-0000-000000000001',
    (
      select (result ->> 'asset_id')::uuid
      from asset_test_results
      where result_name = 'conflict-reserve'
    ),
    '{"expected_version":8,"ocr_state":"not_requested"}'::jsonb,
    '{}'::jsonb,
    '{}'::jsonb
  ) ->> 'error_code',
  'VERSION_CONFLICT',
  'retrying the old complete request remains the same 409 before verifier inputs are reconsidered'
);

-- This rolled-back SQL fixture models a successful Storage API deletion.
-- Real object removal and upload policies are exercised by the HTTP suite.
set local storage.allow_delete_query = 'true';
delete from storage.objects
where bucket_id = 'library-images'
  and name = (
    select result ->> 'object_path'
    from asset_test_results
    where result_name = 'conflict-reserve'
  );

with response as materialized (
  select public.library_claim_asset_cleanup_jobs(10) as value
), matching_job as (
  select job.value
  from response
  cross join lateral pg_catalog.jsonb_array_elements(response.value -> 'jobs') as job(value)
  where job.value ->> 'asset_id' = (
    select result ->> 'asset_id'
    from asset_test_results
    where result_name = 'conflict-reserve'
  )
)
insert into asset_test_results (result_name, result)
select 'conflict-cleanup-claim', value
from matching_job;

select is(
  public.library_finish_asset_cleanup(
    (
      select (result ->> 'asset_id')::uuid
      from asset_test_results
      where result_name = 'conflict-cleanup-claim'
    ),
    (
      select (result ->> 'lease_token')::uuid
      from asset_test_results
      where result_name = 'conflict-cleanup-claim'
    )
  ) ->> 'state',
  'complete',
  'version-conflict reservation is released only after physical absence'
);

select pg_temp.seed_active_asset(
  'ab000000-0000-0000-0000-000000000001',
  'a2000000-0000-0000-0000-000000000002',
  'a1000000-0000-0000-0000-000000000001',
  500,
  'old attachment ocr'
);

insert into asset_test_results (result_name, result)
values (
  'successful-replacement-reserve',
  public.library_reserve_asset(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000019',
    'a2000000-0000-0000-0000-000000000002',
    '{"expected_version":1,"mime_type":"image/jpeg"}'::jsonb
  )
);

insert into storage.objects (id, bucket_id, name, metadata)
select
  extensions.gen_random_uuid(),
  'library-images',
  result ->> 'object_path',
  '{"size":900,"mimetype":"image/jpeg"}'::jsonb
from asset_test_results
where result_name = 'successful-replacement-reserve';

insert into asset_test_results (result_name, result)
select
  'successful-replacement-complete',
  public.library_complete_asset(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000020',
    'a2000000-0000-0000-0000-000000000002',
    (reserved.result ->> 'asset_id')::uuid,
    '{"expected_version":1,"ocr_state":"not_requested"}'::jsonb,
    pg_catalog.jsonb_build_object(
      'object_path', reserved.result ->> 'object_path',
      'size', 900,
      'mime_type', 'image/jpeg',
      'width', 100,
      'height', 100,
      'present', true
    ),
    pg_temp.asset_prepared_index(
      'a2000000-0000-0000-0000-000000000002',
      ''
    )
  )
from asset_test_results as reserved
where reserved.result_name = 'successful-replacement-reserve';

select is(
  (
    select pg_catalog.jsonb_build_array(
      count(*) filter (where asset.state = 'active'),
      count(*) filter (where asset.state = 'deleting')
    )
    from public.assets as asset
    where asset.item_id = 'a2000000-0000-0000-0000-000000000002'
  ),
  '[1,1]'::jsonb,
  'successful replacement atomically makes the new asset active and the old one deleting'
);

select is(
  (
    select pg_catalog.jsonb_build_array(usage.used_image_bytes, usage.reserved_image_bytes)
    from public.library_usage as usage
    where usage.owner_id = 'a1000000-0000-0000-0000-000000000001'
  ),
  '[17002400,0]'::jsonb,
  'successful replacement counts old and new actual bytes until old cleanup'
);

delete from storage.objects
where bucket_id = 'library-images'
  and name = 'a1000000-0000-0000-0000-000000000001/a2000000-0000-0000-0000-000000000002/ab000000-0000-0000-0000-000000000001';

with response as materialized (
  select public.library_claim_asset_cleanup_jobs(10) as value
), matching_job as (
  select job.value
  from response
  cross join lateral pg_catalog.jsonb_array_elements(response.value -> 'jobs') as job(value)
  where job.value ->> 'asset_id' = 'ab000000-0000-0000-0000-000000000001'
)
insert into asset_test_results (result_name, result)
select 'successful-replacement-old-claim', value
from matching_job;

select is(
  public.library_finish_asset_cleanup(
    'ab000000-0000-0000-0000-000000000001',
    (
      select (result ->> 'lease_token')::uuid
      from asset_test_results
      where result_name = 'successful-replacement-old-claim'
    )
  ) ->> 'released_used_bytes',
  '500',
  'old replacement bytes are released only after old object absence'
);

insert into asset_test_results (result_name, result)
values (
  'mime-mismatch-reserve',
  public.library_reserve_asset(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000014',
    'a2000000-0000-0000-0000-000000000002',
    '{"expected_version":2,"mime_type":"image/jpeg"}'::jsonb
  )
);

insert into storage.objects (id, bucket_id, name, metadata)
select
  extensions.gen_random_uuid(),
  'library-images',
  result ->> 'object_path',
  '{"size":700,"mimetype":"image/png"}'::jsonb
from asset_test_results
where result_name = 'mime-mismatch-reserve';

insert into asset_test_results (result_name, result)
select
  'mime-mismatch-complete',
  public.library_complete_asset(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000015',
    'a2000000-0000-0000-0000-000000000002',
    (reserved.result ->> 'asset_id')::uuid,
    '{"expected_version":2,"ocr_state":"not_requested"}'::jsonb,
    pg_catalog.jsonb_build_object(
      'object_path', reserved.result ->> 'object_path',
      'size', 700,
      'mime_type', 'image/jpeg',
      'width', 100,
      'height', 100,
      'present', true
    ),
    pg_temp.asset_prepared_index(
      'a2000000-0000-0000-0000-000000000002',
      ''
    )
  )
from asset_test_results as reserved
where reserved.result_name = 'mime-mismatch-reserve';

select is(
  (
    select result ->> 'error_code'
    from asset_test_results
    where result_name = 'mime-mismatch-complete'
  ),
  'ASSET_MIME_MISMATCH',
  'complete rechecks Storage metadata instead of trusting verified MIME alone'
);

select is(
  (
    select asset.id
    from public.assets as asset
    where asset.item_id = 'a2000000-0000-0000-0000-000000000002'
      and asset.state = 'active'
  ),
  (
    select (result ->> 'asset_id')::uuid
    from asset_test_results
    where result_name = 'successful-replacement-reserve'
  ),
  'a Storage metadata mismatch retains the old active image'
);

delete from storage.objects
where bucket_id = 'library-images'
  and name = (
    select result ->> 'object_path'
    from asset_test_results
    where result_name = 'mime-mismatch-reserve'
  );

with response as materialized (
  select public.library_claim_asset_cleanup_jobs(10) as value
), matching_job as (
  select job.value
  from response
  cross join lateral pg_catalog.jsonb_array_elements(response.value -> 'jobs') as job(value)
  where job.value ->> 'asset_id' = (
    select result ->> 'asset_id'
    from asset_test_results
    where result_name = 'mime-mismatch-reserve'
  )
)
insert into asset_test_results (result_name, result)
select 'mime-mismatch-claim', value
from matching_job;

select is(
  public.library_finish_asset_cleanup(
    (
      select (result ->> 'asset_id')::uuid
      from asset_test_results
      where result_name = 'mime-mismatch-claim'
    ),
    (
      select (result ->> 'lease_token')::uuid
      from asset_test_results
      where result_name = 'mime-mismatch-claim'
    )
  ) ->> 'released_reserved_bytes',
  '2000000',
  'the rejected MIME reservation releases quota only after object absence'
);

insert into asset_test_results (result_name, result)
values (
  'pixel-limit-reserve',
  public.library_reserve_asset(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000016',
    'a2000000-0000-0000-0000-000000000002',
    '{"expected_version":2,"mime_type":"image/jpeg"}'::jsonb
  )
);

insert into storage.objects (id, bucket_id, name, metadata)
select
  extensions.gen_random_uuid(),
  'library-images',
  result ->> 'object_path',
  '{"size":700,"mimetype":"image/jpeg"}'::jsonb
from asset_test_results
where result_name = 'pixel-limit-reserve';

insert into asset_test_results (result_name, result)
select
  'pixel-limit-complete',
  public.library_complete_asset(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000017',
    'a2000000-0000-0000-0000-000000000002',
    (reserved.result ->> 'asset_id')::uuid,
    '{"expected_version":2,"ocr_state":"not_requested"}'::jsonb,
    pg_catalog.jsonb_build_object(
      'object_path', reserved.result ->> 'object_path',
      'size', 700,
      'mime_type', 'image/jpeg',
      'width', 6001,
      'height', 4000,
      'present', true
    ),
    pg_temp.asset_prepared_index(
      'a2000000-0000-0000-0000-000000000002',
      ''
    )
  )
from asset_test_results as reserved
where reserved.result_name = 'pixel-limit-reserve';

select is(
  (
    select result ->> 'error_code'
    from asset_test_results
    where result_name = 'pixel-limit-complete'
  ),
  'ASSET_PIXEL_LIMIT_EXCEEDED',
  'complete rejects a decoded image above 24 megapixels'
);

select is(
  (
    select count(*)::integer
    from public.assets as asset
    where asset.item_id = 'a2000000-0000-0000-0000-000000000002'
      and asset.state = 'active'
      and asset.id = (
        select (result ->> 'asset_id')::uuid
        from asset_test_results
        where result_name = 'successful-replacement-reserve'
      )
  ),
  1,
  'pixel-limit failure cannot replace the old active image'
);

delete from storage.objects
where bucket_id = 'library-images'
  and name = (
    select result ->> 'object_path'
    from asset_test_results
    where result_name = 'pixel-limit-reserve'
  );

with response as materialized (
  select public.library_claim_asset_cleanup_jobs(10) as value
), matching_job as (
  select job.value
  from response
  cross join lateral pg_catalog.jsonb_array_elements(response.value -> 'jobs') as job(value)
  where job.value ->> 'asset_id' = (
    select result ->> 'asset_id'
    from asset_test_results
    where result_name = 'pixel-limit-reserve'
  )
)
insert into asset_test_results (result_name, result)
select 'pixel-limit-cleanup-claim', value
from matching_job;

select is(
  public.library_finish_asset_cleanup(
    (
      select (result ->> 'asset_id')::uuid
      from asset_test_results
      where result_name = 'pixel-limit-cleanup-claim'
    ),
    (
      select (result ->> 'lease_token')::uuid
      from asset_test_results
      where result_name = 'pixel-limit-cleanup-claim'
    )
  ) ->> 'released_reserved_bytes',
  '2000000',
  'pixel-limit cleanup releases its reservation after object absence'
);

insert into asset_test_results (result_name, result)
values (
  'stale-ocr',
  public.library_update_asset_ocr(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000009',
    'a2000000-0000-0000-0000-000000000001',
    (
      select asset.id
      from public.assets as asset
      where asset.item_id = 'a2000000-0000-0000-0000-000000000001'
        and asset.state = 'active'
    ),
    '{"expected_version":8,"ocr_state":"ready","ocr_text":"stale"}'::jsonb,
    pg_temp.asset_prepared_index(
      'a2000000-0000-0000-0000-000000000001',
      'stale'
    )
  )
);

select is(
  (select result ->> 'error_code' from asset_test_results where result_name = 'stale-ocr'),
  'VERSION_CONFLICT',
  'stale OCR update is rejected'
);

select is(
  (
    select pg_catalog.char_length(asset.ocr_text)
    from public.assets as asset
    where asset.item_id = 'a2000000-0000-0000-0000-000000000001'
      and asset.state = 'active'
  ),
  20000,
  'stale OCR does not overwrite the current OCR'
);

insert into asset_test_results (result_name, result)
values (
  'current-ocr',
  public.library_update_asset_ocr(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000010',
    'a2000000-0000-0000-0000-000000000001',
    (
      select asset.id
      from public.assets as asset
      where asset.item_id = 'a2000000-0000-0000-0000-000000000001'
        and asset.state = 'active'
    ),
    '{"expected_version":9,"ocr_state":"ready","ocr_text":"fresh ocr"}'::jsonb,
    pg_temp.asset_prepared_index(
      'a2000000-0000-0000-0000-000000000001',
      'fresh ocr'
    )
  )
);

select is(
  (
    select pg_catalog.jsonb_build_array(item.version, item.text_revision)
    from public.items as item
    where item.id = 'a2000000-0000-0000-0000-000000000001'
  ),
  '[10,3]'::jsonb,
  'current OCR replacement advances both revisions once'
);

select is(
  (
    select search_record.normalized_fields ->> 'ocr'
    from public.item_search as search_record
    where search_record.item_id = 'a2000000-0000-0000-0000-000000000001'
  ),
  'fresh ocr',
  'current OCR fully replaces the prior search text'
);

select is(
  (
    select search_record.alias_concepts -> 'ocr'
    from public.item_search as search_record
    where search_record.item_id = 'a2000000-0000-0000-0000-000000000001'
  ),
  '[]'::jsonb,
  'current OCR fully replaces prior OCR alias concepts'
);

select is(
  (
    select classification.state
    from public.item_classification as classification
    where classification.item_id = 'a2000000-0000-0000-0000-000000000001'
  ),
  'manual',
  'OCR replacement preserves manual classification state'
);

insert into asset_test_results (result_name, result)
values (
  'failed-ocr',
  public.library_update_asset_ocr(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000011',
    'a2000000-0000-0000-0000-000000000001',
    (
      select asset.id
      from public.assets as asset
      where asset.item_id = 'a2000000-0000-0000-0000-000000000001'
        and asset.state = 'active'
    ),
    '{"expected_version":10,"ocr_state":"failed"}'::jsonb,
    pg_temp.asset_prepared_index(
      'a2000000-0000-0000-0000-000000000001',
      ''
    )
  )
);

select is(
  (
    select pg_catalog.jsonb_build_object(
      'state', asset.ocr_state,
      'text', asset.ocr_text,
      'truncated', asset.ocr_truncated
    )
    from public.assets as asset
    where asset.item_id = 'a2000000-0000-0000-0000-000000000001'
      and asset.state = 'active'
  ),
  '{"state":"failed","text":null,"truncated":false}'::jsonb,
  'failed OCR keeps the image while removing prior OCR text'
);

insert into asset_test_results (result_name, result)
values (
  'delete-active',
  public.library_delete_asset(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000012',
    'a2000000-0000-0000-0000-000000000001',
    (
      select asset.id
      from public.assets as asset
      where asset.item_id = 'a2000000-0000-0000-0000-000000000001'
        and asset.state = 'active'
    ),
    '{"expected_version":11}'::jsonb,
    pg_temp.asset_prepared_index(
      'a2000000-0000-0000-0000-000000000001',
      ''
    )
  )
);

select is(
  (select (result ->> 'http_status')::integer from asset_test_results where result_name = 'delete-active'),
  202,
  'asset delete is accepted asynchronously'
);

select is(
  (
    select result ->> 'asset_id'
    from asset_test_results
    where result_name = 'delete-active'
  ),
  (
    select response_body ->> 'asset_id'
    from public.api_requests
    where request_id = 'aa000000-0000-0000-0000-000000000012'
  ),
  'asset delete returns the immutable target asset ID'
);

select is(
  (
    select used_image_bytes
    from public.library_usage
    where owner_id = 'a1000000-0000-0000-0000-000000000001'
  ),
  17001900::bigint,
  'delete acceptance does not release physical bytes early'
);

reset role;
set local role authenticated;
set local "request.jwt.claim.sub" = 'a1000000-0000-0000-0000-000000000001';

select is(
  public.library_get_item('a2000000-0000-0000-0000-000000000001')
    ->> 'has_attachment',
  'false',
  'delete acceptance hides the asset immediately'
);

select is(
  public.library_get_item('a2000000-0000-0000-0000-000000000001')
    ->> 'ocr_state',
  'not_requested',
  'delete acceptance hides OCR immediately'
);

reset role;
set local role service_role;
set local "request.jwt.claim.sub" = '';

select is(
  public.library_update_asset_ocr(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000021',
    'a2000000-0000-0000-0000-000000000001',
    (
      select (result ->> 'asset_id')::uuid
      from asset_test_results
      where result_name = 'delete-active'
    ),
    '{"expected_version":12,"ocr_state":"failed"}'::jsonb,
    pg_temp.asset_prepared_index(
      'a2000000-0000-0000-0000-000000000001',
      ''
    )
  ) ->> 'error_code',
  'ASSET_NOT_ACTIVE',
  'OCR patch cannot target a deleting former active asset'
);

with response as materialized (
  select public.library_claim_asset_cleanup_jobs(1) as value
), matching_job as (
  select job.value
  from response
  cross join lateral pg_catalog.jsonb_array_elements(response.value -> 'jobs') as job(value)
  where job.value ->> 'asset_id' = (
    select response_body ->> 'asset_id'
    from public.api_requests
    where owner_id = 'a1000000-0000-0000-0000-000000000001'
      and request_id = 'aa000000-0000-0000-0000-000000000012'
  )
)
insert into asset_test_results (result_name, result)
select 'delete-cleanup-claim', value
from matching_job;

select is(
  public.library_finish_asset_cleanup(
    (
      select (result ->> 'asset_id')::uuid
      from asset_test_results
      where result_name = 'delete-cleanup-claim'
    ),
    (
      select (result ->> 'lease_token')::uuid
      from asset_test_results
      where result_name = 'delete-cleanup-claim'
    )
  ) ->> 'error_code',
  'STORAGE_OBJECT_PRESENT',
  'cleanup cannot release used bytes while the Storage object exists'
);

delete from storage.objects
where bucket_id = 'library-images'
  and name = (
    select result ->> 'object_path'
    from asset_test_results
    where result_name = 'delete-cleanup-claim'
  );

insert into asset_test_results (result_name, result)
select
  'delete-cleanup-finish',
  public.library_finish_asset_cleanup(
    (claim.result ->> 'asset_id')::uuid,
    (claim.result ->> 'lease_token')::uuid
  )
from asset_test_results as claim
where claim.result_name = 'delete-cleanup-claim';

select is(
  (
    select used_image_bytes
    from public.library_usage
    where owner_id = 'a1000000-0000-0000-0000-000000000001'
  ),
  17000900::bigint,
  'absence-confirmed cleanup releases actual used bytes once'
);

select is(
  public.library_finish_asset_cleanup(
    (
      select (result ->> 'asset_id')::uuid
      from asset_test_results
      where result_name = 'delete-cleanup-claim'
    ),
    (
      select (result ->> 'lease_token')::uuid
      from asset_test_results
      where result_name = 'delete-cleanup-claim'
    )
  ) ->> 'released_used_bytes',
  '1000',
  'repeated physical cleanup returns its receipt without another decrement'
);

insert into asset_test_results (result_name, result)
values (
  'owner-b-reserve',
  public.library_reserve_asset(
    'a1000000-0000-0000-0000-000000000002',
    'aa000000-0000-0000-0000-000000000013',
    'a2000000-0000-0000-0000-000000000003',
    '{"expected_version":1,"mime_type":"image/webp"}'::jsonb
  )
);

update public.assets
set reservation_expires_at = pg_catalog.clock_timestamp() - interval '1 second'
where id = (
  select (result ->> 'asset_id')::uuid
  from asset_test_results
  where result_name = 'owner-b-reserve'
);

reset role;
set local role authenticated;
set local "request.jwt.claim.sub" = 'a1000000-0000-0000-0000-000000000002';

select ok(
  not public.library_image_upload_allowed(
    'library-images',
    (
      select result ->> 'object_path'
      from asset_test_results
      where result_name = 'owner-b-reserve'
    )
  ),
  'an expired reservation cannot admit a last upload before the cleanup scan'
);

reset role;
set local role service_role;
set local "request.jwt.claim.sub" = '';

update public.beta_members
set enabled = false
where owner_id = 'a1000000-0000-0000-0000-000000000002';
update public.profiles
set state = 'deleting', deletion_requested_at = now()
where id = 'a1000000-0000-0000-0000-000000000002';

with response as materialized (
  select public.library_claim_asset_cleanup_jobs(10) as value
), matching_job as (
  select job.value
  from response
  cross join lateral pg_catalog.jsonb_array_elements(response.value -> 'jobs') as job(value)
  where job.value ->> 'asset_id' = (
    select result ->> 'asset_id'
    from asset_test_results
    where result_name = 'owner-b-reserve'
  )
)
insert into asset_test_results (result_name, result)
select 'revoked-owner-claim', value
from matching_job;

select is(
  (
    select result ->> 'cleanup_reason'
    from asset_test_results
    where result_name = 'revoked-owner-claim'
  ),
  'reservation_expired',
  'the cleanup claimer scans expired reservations for a revoked/deleting owner'
);

select is(
  public.library_finish_asset_cleanup(
    (
      select (result ->> 'asset_id')::uuid
      from asset_test_results
      where result_name = 'revoked-owner-claim'
    ),
    (
      select (result ->> 'lease_token')::uuid
      from asset_test_results
      where result_name = 'revoked-owner-claim'
    )
  ) ->> 'released_reserved_bytes',
  '2000000',
  'revoked-owner cleanup releases quota after confirming physical absence'
);

select is(
  (
    select reserved_image_bytes
    from public.library_usage
    where owner_id = 'a1000000-0000-0000-0000-000000000002'
  ),
  0::bigint,
  'revoked-owner cleanup leaves no reserved quota leak'
);

select throws_ok(
  $$
    insert into public.assets (
      id,
      owner_id,
      item_id,
      object_path,
      reserved_mime_type,
      reservation_expires_at
    ) values (
      'af000000-0000-0000-0000-000000000001',
      'a1000000-0000-0000-0000-000000000001',
      'a2000000-0000-0000-0000-000000000002',
      'client/supplied/path.jpg',
      'image/jpeg',
      now() + interval '15 minutes'
    )
  $$,
  '23514',
  null,
  'even the server cannot persist a client-supplied noncanonical path'
);

select is(
  (
    select count(*)::integer
    from public.api_requests as request
    where request.method_path like '%/assets/%'
      and request.response_body ?| array[
        'object_path',
        'mime_type',
        'ocr_text',
        'verified_object',
        'prepared_index'
      ]
  ),
  0,
  'asset idempotency receipts contain identifiers and codes, never paths, OCR, or verifier payloads'
);

select ok(
  has_function_privilege(
    'service_role',
    'public.library_replay_asset_request(uuid,uuid,text,text,jsonb)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated',
    'public.library_replay_asset_request(uuid,uuid,text,text,jsonb)',
    'EXECUTE'
  ),
  'the asset replay lookup is service-role-only'
);

select is(
  public.library_replay_asset_request(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000019',
    'POST',
    '/items/a2000000-0000-0000-0000-000000000002/assets/reserve',
    '{"expected_version":1,"mime_type":"image/jpeg"}'::jsonb
  ) ->> 'object_path',
  'a1000000-0000-0000-0000-000000000001/a2000000-0000-0000-0000-000000000002/'
    || (
      select result ->> 'asset_id'
      from asset_test_results
      where result_name = 'successful-replacement-reserve'
    ),
  'reserve replay reconstructs only the canonical owner/item/asset path'
);

reset role;
set local role authenticated;
set local "request.jwt.claim.sub" = 'a1000000-0000-0000-0000-000000000001';

select is(
  public.library_get_item('a2000000-0000-0000-0000-000000000002')
    #>> '{active_asset,object_path}',
  'a1000000-0000-0000-0000-000000000001/a2000000-0000-0000-0000-000000000002/'
    || (
      select result ->> 'asset_id'
      from asset_test_results
      where result_name = 'successful-replacement-reserve'
    ),
  'the public item projection keeps the canonical active object path'
);

reset role;
set local role service_role;
set local "request.jwt.claim.sub" = '';

select throws_ok(
  $$
    select public.library_replay_asset_request(
      'a1000000-0000-0000-0000-000000000001',
      'aa000000-0000-0000-0000-000000000019',
      'post',
      '/items/a2000000-0000-0000-0000-000000000002/assets/reserve',
      '{"expected_version":1,"mime_type":"image/jpeg"}'::jsonb
    )
  $$,
  'P0001',
  'INVALID_ASSET_REQUEST_IDENTITY',
  'replay requires the uppercase HTTP method used by API 52'
);

select throws_ok(
  $$
    select public.library_replay_asset_request(
      'a1000000-0000-0000-0000-000000000001',
      'aa000000-0000-0000-0000-000000000019',
      'POST',
      '/items/A2000000-0000-0000-0000-000000000002/assets/reserve',
      '{"expected_version":1,"mime_type":"image/jpeg"}'::jsonb
    )
  $$,
  'P0001',
  'INVALID_ASSET_REQUEST_IDENTITY',
  'replay rejects noncanonical UUID casing in an API route'
);

select is(
  public.library_replay_asset_request(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000022',
    'POST',
    '/items/a2000000-0000-0000-0000-000000000002/assets/'
      || (
        select result ->> 'asset_id'
        from asset_test_results
        where result_name = 'successful-replacement-reserve'
      )
      || '/complete',
    '{"expected_version":2,"ocr_state":"not_requested"}'::jsonb
  ),
  '{"found":false}'::jsonb,
  'replay reports a precise miss without reading Storage'
);

select is(
  public.library_replay_asset_request(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000008',
    'POST',
    '/items/a2000000-0000-0000-0000-000000000001/assets/'
      || (
        select result ->> 'asset_id'
        from asset_test_results
        where result_name = 'conflict-reserve'
      )
      || '/complete',
    '{"expected_version":8,"ocr_state":"not_requested"}'::jsonb
  ),
  '{"found":true,"http_status":409,"error_code":"VERSION_CONFLICT"}'::jsonb,
  'replay returns the exact sticky 409 after its cleanup removed the asset row'
);

select is(
  public.library_complete_asset(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000008',
    'a2000000-0000-0000-0000-000000000001',
    (
      select (result ->> 'asset_id')::uuid
      from asset_test_results
      where result_name = 'conflict-reserve'
    ),
    '{"expected_version":8,"ocr_state":"not_requested"}'::jsonb,
    '{}'::jsonb,
    '{}'::jsonb
  ),
  '{"http_status":409,"error_code":"VERSION_CONFLICT"}'::jsonb,
  'complete replays its sticky 409 before reconsidering missing object inputs'
);

select throws_ok(
  $$
    select public.library_replay_asset_request(
      'a1000000-0000-0000-0000-000000000001',
      'aa000000-0000-0000-0000-000000000008',
      'POST',
      '/items/a2000000-0000-0000-0000-000000000001/assets/'
        || (
          select result ->> 'asset_id'
          from asset_test_results
          where result_name = 'conflict-reserve'
        )
        || '/complete',
      '{"expected_version":8,"ocr_state":"not_requested","ocr_truncated":false}'::jsonb
    )
  $$,
  'P0001',
  'IDEMPOTENCY_MISMATCH',
  'adding even a false OCR flag changes the raw complete request identity'
);

select throws_ok(
  $$
    select public.library_replay_asset_request(
      'a1000000-0000-0000-0000-000000000002',
      'aa000000-0000-0000-0000-000000000013',
      'POST',
      '/items/a2000000-0000-0000-0000-000000000003/assets/reserve',
      '{"expected_version":1,"mime_type":"image/webp"}'::jsonb
    )
  $$,
  'P0001',
  'ACCOUNT_DELETING',
  'replay rechecks current owner approval and account state'
);

insert into asset_test_results (result_name, result)
values (
  'client-truncated-ocr',
  public.library_update_asset_ocr(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000023',
    'a2000000-0000-0000-0000-000000000002',
    (
      select (result ->> 'asset_id')::uuid
      from asset_test_results
      where result_name = 'successful-replacement-reserve'
    ),
    pg_catalog.jsonb_build_object(
      'expected_version', 2,
      'ocr_state', 'ready',
      'ocr_text', pg_catalog.repeat('나', 20000),
      'ocr_truncated', true
    ),
    pg_temp.asset_prepared_index(
      'a2000000-0000-0000-0000-000000000002',
      pg_catalog.repeat('나', 20000)
    )
  )
);

select is(
  (
    select pg_catalog.jsonb_build_object(
      'length', pg_catalog.char_length(asset.ocr_text),
      'truncated', asset.ocr_truncated
    )
    from public.assets as asset
    where asset.id = (
      select (result ->> 'asset_id')::uuid
      from asset_test_results
      where result_name = 'successful-replacement-reserve'
    )
      and asset.state = 'active'
  ),
  '{"length":20000,"truncated":true}'::jsonb,
  'a true client truncation flag persists at the 20,000-character cap'
);

select is(
  public.library_update_asset_ocr(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000023',
    'a2000000-0000-0000-0000-000000000002',
    (
      select (result ->> 'asset_id')::uuid
      from asset_test_results
      where result_name = 'successful-replacement-reserve'
    ),
    pg_catalog.jsonb_build_object(
      'expected_version', 2,
      'ocr_state', 'ready',
      'ocr_text', pg_catalog.repeat('나', 20000),
      'ocr_truncated', true
    ),
    '{}'::jsonb
  ) #>> '{item,version}',
  '3',
  'OCR replay succeeds before prepared-index validation and does not bump revisions twice'
);

select throws_ok(
  $$
    select public.library_update_asset_ocr(
      'a1000000-0000-0000-0000-000000000001',
      'aa000000-0000-0000-0000-000000000023',
      'a2000000-0000-0000-0000-000000000002',
      (
        select (result ->> 'asset_id')::uuid
        from asset_test_results
        where result_name = 'successful-replacement-reserve'
      ),
      pg_catalog.jsonb_build_object(
        'expected_version', 2,
        'ocr_state', 'ready',
        'ocr_text', pg_catalog.repeat('나', 20000),
        'ocr_truncated', false
      ),
      '{}'::jsonb
    )
  $$,
  'P0001',
  'IDEMPOTENCY_MISMATCH',
  'the stored OCR request hash includes the client truncation flag'
);

insert into asset_test_results (result_name, result)
values (
  'failed-ocr-explicit-false',
  public.library_update_asset_ocr(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000024',
    'a2000000-0000-0000-0000-000000000002',
    (
      select (result ->> 'asset_id')::uuid
      from asset_test_results
      where result_name = 'successful-replacement-reserve'
    ),
    '{"expected_version":3,"ocr_state":"failed","ocr_truncated":false}'::jsonb,
    pg_temp.asset_prepared_index(
      'a2000000-0000-0000-0000-000000000002',
      ''
    )
  )
);

select is(
  (
    select pg_catalog.jsonb_build_object(
      'state', asset.ocr_state,
      'text', asset.ocr_text,
      'truncated', asset.ocr_truncated
    )
    from public.assets as asset
    where asset.id = (
      select (result ->> 'asset_id')::uuid
      from asset_test_results
      where result_name = 'successful-replacement-reserve'
    )
  ),
  '{"state":"failed","text":null,"truncated":false}'::jsonb,
  'an explicit false truncation flag is accepted for failed OCR'
);

insert into asset_test_results (result_name, result)
values (
  'complete-client-truncated-reserve',
  public.library_reserve_asset(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000029',
    'a2000000-0000-0000-0000-000000000002',
    '{"expected_version":4,"mime_type":"image/jpeg"}'::jsonb
  )
);

insert into storage.objects (id, bucket_id, name, metadata)
select
  extensions.gen_random_uuid(),
  'library-images',
  result ->> 'object_path',
  '{"size":600,"mimetype":"image/jpeg"}'::jsonb
from asset_test_results
where result_name = 'complete-client-truncated-reserve';

insert into asset_test_results (result_name, result)
select
  'complete-client-truncated',
  public.library_complete_asset(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000030',
    'a2000000-0000-0000-0000-000000000002',
    (reserved.result ->> 'asset_id')::uuid,
    pg_catalog.jsonb_build_object(
      'expected_version', 4,
      'ocr_state', 'ready',
      'ocr_text', pg_catalog.repeat('다', 20000),
      'ocr_truncated', true
    ),
    pg_catalog.jsonb_build_object(
      'object_path', reserved.result ->> 'object_path',
      'size', 600,
      'mime_type', 'image/jpeg',
      'width', 100,
      'height', 100,
      'present', true
    ),
    pg_temp.asset_prepared_index(
      'a2000000-0000-0000-0000-000000000002',
      pg_catalog.repeat('다', 20000)
    )
  )
from asset_test_results as reserved
where reserved.result_name = 'complete-client-truncated-reserve';

select is(
  (
    select pg_catalog.jsonb_build_object(
      'length', pg_catalog.char_length(asset.ocr_text),
      'truncated', asset.ocr_truncated
    )
    from public.assets as asset
    where asset.id = (
      select (result ->> 'asset_id')::uuid
      from asset_test_results
      where result_name = 'complete-client-truncated-reserve'
    )
      and asset.state = 'active'
  ),
  '{"length":20000,"truncated":true}'::jsonb,
  'complete persists a true client truncation flag at the OCR cap'
);

select is(
  public.library_complete_asset(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000030',
    'a2000000-0000-0000-0000-000000000002',
    (
      select (result ->> 'asset_id')::uuid
      from asset_test_results
      where result_name = 'complete-client-truncated-reserve'
    ),
    pg_catalog.jsonb_build_object(
      'expected_version', 4,
      'ocr_state', 'ready',
      'ocr_text', pg_catalog.repeat('다', 20000),
      'ocr_truncated', true
    ),
    '{}'::jsonb,
    '{}'::jsonb
  ) #>> '{item,version}',
  '5',
  'complete with a truncation flag replays before object validation'
);

select throws_ok(
  $$
    select public.library_complete_asset(
      'a1000000-0000-0000-0000-000000000001',
      'aa000000-0000-0000-0000-000000000030',
      'a2000000-0000-0000-0000-000000000002',
      (
        select (result ->> 'asset_id')::uuid
        from asset_test_results
        where result_name = 'complete-client-truncated-reserve'
      ),
      pg_catalog.jsonb_build_object(
        'expected_version', 4,
        'ocr_state', 'ready',
        'ocr_text', pg_catalog.repeat('다', 20000),
        'ocr_truncated', false
      ),
      '{}'::jsonb,
      '{}'::jsonb
    )
  $$,
  'P0001',
  'IDEMPOTENCY_MISMATCH',
  'the complete receipt hash includes the client truncation flag'
);

select throws_ok(
  $$
    select public.library_complete_asset(
      'a1000000-0000-0000-0000-000000000001',
      'aa000000-0000-0000-0000-000000000025',
      'a2000000-0000-0000-0000-000000000002',
      (
        select (result ->> 'asset_id')::uuid
        from asset_test_results
        where result_name = 'successful-replacement-reserve'
      ),
      '{"expected_version":4,"ocr_state":"ready","ocr_text":"text","ocr_truncated":"true"}'::jsonb,
      '{}'::jsonb,
      '{}'::jsonb
    )
  $$,
  'P0001',
  'INVALID_BODY',
  'complete rejects a non-boolean ocr_truncated value'
);

select throws_ok(
  $$
    select public.library_complete_asset(
      'a1000000-0000-0000-0000-000000000001',
      'aa000000-0000-0000-0000-000000000026',
      'a2000000-0000-0000-0000-000000000002',
      (
        select (result ->> 'asset_id')::uuid
        from asset_test_results
        where result_name = 'successful-replacement-reserve'
      ),
      '{"expected_version":4,"ocr_state":"not_requested","ocr_truncated":true}'::jsonb,
      '{}'::jsonb,
      '{}'::jsonb
    )
  $$,
  'P0001',
  'INVALID_BODY',
  'complete rejects true ocr_truncated for a non-ready OCR state'
);

select throws_ok(
  $$
    select public.library_update_asset_ocr(
      'a1000000-0000-0000-0000-000000000001',
      'aa000000-0000-0000-0000-000000000027',
      'a2000000-0000-0000-0000-000000000002',
      (
        select (result ->> 'asset_id')::uuid
        from asset_test_results
        where result_name = 'successful-replacement-reserve'
      ),
      '{"expected_version":4,"ocr_state":"ready","ocr_text":"text","ocr_truncated":1}'::jsonb,
      '{}'::jsonb
    )
  $$,
  'P0001',
  'INVALID_BODY',
  'OCR patch rejects a non-boolean ocr_truncated value'
);

select throws_ok(
  $$
    select public.library_update_asset_ocr(
      'a1000000-0000-0000-0000-000000000001',
      'aa000000-0000-0000-0000-000000000028',
      'a2000000-0000-0000-0000-000000000002',
      (
        select (result ->> 'asset_id')::uuid
        from asset_test_results
        where result_name = 'successful-replacement-reserve'
      ),
      '{"expected_version":4,"ocr_state":"failed","ocr_truncated":true}'::jsonb,
      '{}'::jsonb
    )
  $$,
  'P0001',
  'INVALID_BODY',
  'OCR patch rejects true ocr_truncated for failed OCR'
);

delete from storage.objects
where bucket_id = 'library-images'
  and name = (
    select asset.object_path
    from public.assets as asset
    where asset.id = (
      select (result ->> 'asset_id')::uuid
      from asset_test_results
      where result_name = 'successful-replacement-reserve'
    )
  );

select is(
  public.library_replay_asset_request(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000020',
    'POST',
    '/items/a2000000-0000-0000-0000-000000000002/assets/'
      || (
        select result ->> 'asset_id'
        from asset_test_results
        where result_name = 'successful-replacement-reserve'
      )
      || '/complete',
    '{"expected_version":1,"ocr_state":"not_requested"}'::jsonb
  ) #>> '{item,version}',
  '5',
  'successful complete replay returns the current item after the object is gone'
);

select is(
  public.library_complete_asset(
    'a1000000-0000-0000-0000-000000000001',
    'aa000000-0000-0000-0000-000000000020',
    'a2000000-0000-0000-0000-000000000002',
    (
      select (result ->> 'asset_id')::uuid
      from asset_test_results
      where result_name = 'successful-replacement-reserve'
    ),
    '{"expected_version":1,"ocr_state":"not_requested"}'::jsonb,
    '{}'::jsonb,
    '{}'::jsonb
  ) #>> '{item,version}',
  '5',
  'complete itself replays before object and prepared-index checks after object loss'
);

update public.items
set deleted_at = now(),
    original_url = null, normalized_url = null, url_hash = null,
    source = null, display_fallback = null, metadata_state = null,
    user_title = null, fetched_title = null, shared_text = null,
    description = null, body_text = null, note = null, extraction_meta = '{}'::jsonb
where id = 'a2000000-0000-0000-0000-000000000001';

select throws_ok(
  $$
    select public.library_replay_asset_request(
      'a1000000-0000-0000-0000-000000000001',
      'aa000000-0000-0000-0000-000000000011',
      'PATCH',
      '/items/a2000000-0000-0000-0000-000000000001/assets/'
        || (
          select response_body ->> 'asset_id'
          from public.api_requests
          where request_id = 'aa000000-0000-0000-0000-000000000011'
        )
        || '/ocr',
      '{"expected_version":10,"ocr_state":"failed"}'::jsonb
    )
  $$,
  'P0001',
  'ITEM_DELETED',
  'the replay service returns ITEM_DELETED instead of replaying a deleted item'
);

select throws_ok(
  $$
    select public.library_update_asset_ocr(
      'a1000000-0000-0000-0000-000000000001',
      'aa000000-0000-0000-0000-000000000011',
      'a2000000-0000-0000-0000-000000000001',
      (
        select response_body ->> 'asset_id'
        from public.api_requests
        where request_id = 'aa000000-0000-0000-0000-000000000011'
      )::uuid,
      '{"expected_version":10,"ocr_state":"failed"}'::jsonb,
      '{}'::jsonb
    )
  $$,
  'P0001',
  'ITEM_DELETED',
  'an old successful mutation replay returns ITEM_DELETED instead of reviving data'
);

select * from finish();
rollback;
