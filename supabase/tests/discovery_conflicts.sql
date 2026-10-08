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
    '17000000-0000-0000-0000-000000000001',
    'authenticated',
    'authenticated',
    'conflicts-a@example.test',
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
    '17000000-0000-0000-0000-000000000002',
    'authenticated',
    'authenticated',
    'conflicts-b@example.test',
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
    '17000000-0000-0000-0000-000000000003',
    'authenticated',
    'authenticated',
    'conflicts-c@example.test',
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
  ('17000000-0000-0000-0000-000000000001', true, now()),
  ('17000000-0000-0000-0000-000000000002', true, now()),
  ('17000000-0000-0000-0000-000000000003', true, now());

insert into public.profiles (id, state)
values
  ('17000000-0000-0000-0000-000000000001', 'active'),
  ('17000000-0000-0000-0000-000000000002', 'active'),
  ('17000000-0000-0000-0000-000000000003', 'active');

insert into public.library_usage (owner_id)
values
  ('17000000-0000-0000-0000-000000000001'),
  ('17000000-0000-0000-0000-000000000002'),
  ('17000000-0000-0000-0000-000000000003');

insert into public.categories (id, owner_id, name, normalized_name, kind)
values
  (
    '37000000-0000-0000-0000-000000000001',
    '17000000-0000-0000-0000-000000000001',
    'Taken',
    'taken',
    'custom'
  ),
  (
    '37000000-0000-0000-0000-000000000002',
    '17000000-0000-0000-0000-000000000001',
    'Target',
    'target',
    'custom'
  ),
  (
    '37000000-0000-0000-0000-000000000003',
    '17000000-0000-0000-0000-000000000001',
    'Rename Taken',
    'rename taken',
    'custom'
  ),
  (
    '37000000-0000-0000-0000-000000000004',
    '17000000-0000-0000-0000-000000000003',
    '여행',
    '여행',
    'custom'
  );

insert into public.categories (owner_id, name, normalized_name, kind)
select
  '17000000-0000-0000-0000-000000000002',
  'Capacity ' || ordinal,
  'capacity ' || ordinal,
  'custom'
from pg_catalog.generate_series(1, 29) as ordinal;

create function pg_temp.conflict_prepared(
  p_url text,
  p_categories text default ''
)
returns jsonb
language sql
immutable
as $$
  select pg_catalog.jsonb_build_object(
    'normalized_url', p_url,
    'url_hash', pg_catalog.encode(
      extensions.digest(pg_catalog.convert_to(p_url, 'UTF8'), 'sha256'),
      'hex'
    ),
    'source', 'other',
    'display_fallback', 'example.test',
    'normalized_fields', pg_catalog.jsonb_build_object(
      'user_title', '',
      'fetched_title', '',
      'note', '',
      'ocr', '',
      'shared', '',
      'description', '',
      'body', '',
      'categories', p_categories,
      'url', pg_catalog.lower(p_url)
    ),
    'alias_concepts', pg_catalog.jsonb_build_object(
      'user_title', '[]'::jsonb,
      'fetched_title', '[]'::jsonb,
      'note', '[]'::jsonb,
      'ocr', '[]'::jsonb,
      'shared', '[]'::jsonb,
      'description', '[]'::jsonb,
      'body', '[]'::jsonb
    ),
    'cue_state', 'limited',
    'cue_flags', '["short_text"]'::jsonb,
    'metadata_allowed', false
  );
$$;

grant execute on function pg_temp.conflict_prepared(text, text) to service_role;

set local role service_role;

select is(
  public.library_create_category(
    '17000000-0000-0000-0000-000000000001',
    '47000000-0000-0000-0000-000000000001',
    '{"name":"Taken"}'::jsonb,
    'taken'
  ),
  '{"http_status":409,"error_code":"CATEGORY_NAME_EXISTS"}'::jsonb,
  'duplicate category creation returns a committed conflict sentinel'
);

reset role;

select is(
  (
    select pg_catalog.jsonb_build_object(
      'method_path', request.method_path,
      'request_hash', request.request_hash,
      'response_code', request.response_code,
      'response_body', request.response_body
    )
    from public.api_requests as request
    where request.owner_id = '17000000-0000-0000-0000-000000000001'
      and request.request_id = '47000000-0000-0000-0000-000000000001'
  ),
  pg_catalog.jsonb_build_object(
    'method_path', 'POST /categories',
    'request_hash', pg_catalog.encode(
      extensions.digest(
        pg_catalog.convert_to(
          'POST /categories' || pg_catalog.chr(10) || '{"name": "Taken"}'::jsonb::text,
          'UTF8'
        ),
        'sha256'
      ),
      'hex'
    ),
    'response_code', 409,
    'response_body', '{"error_code":"CATEGORY_NAME_EXISTS"}'::jsonb
  ),
  'the duplicate receipt binds method, path, raw body hash, status, and code only'
);

set local role service_role;

select throws_ok(
  $$
    select public.library_create_category(
      '17000000-0000-0000-0000-000000000001',
      '47000000-0000-0000-0000-000000000001',
      '{"name":"Different"}'::jsonb,
      'different'
    )
  $$,
  'P0001',
  'IDEMPOTENCY_MISMATCH',
  'a duplicate-category request id rejects a changed raw body'
);

select throws_ok(
  $$
    select public.library_rename_category(
      '17000000-0000-0000-0000-000000000001',
      '37000000-0000-0000-0000-000000000002',
      '47000000-0000-0000-0000-000000000001',
      '{"name":"Taken"}'::jsonb,
      'taken'
    )
  $$,
  'P0001',
  'IDEMPOTENCY_MISMATCH',
  'the same request id cannot cross method and resource paths'
);

select is(
  public.library_delete_category(
    '17000000-0000-0000-0000-000000000001',
    '37000000-0000-0000-0000-000000000001',
    '47000000-0000-0000-0000-000000000002',
    '{}'::jsonb
  ) ->> 'http_status',
  '204',
  'the category that caused the create conflict can later be deleted'
);

select is(
  public.library_create_category(
    '17000000-0000-0000-0000-000000000001',
    '47000000-0000-0000-0000-000000000001',
    '{"name":"Taken"}'::jsonb,
    'taken'
  ),
  '{"http_status":409,"error_code":"CATEGORY_NAME_EXISTS"}'::jsonb,
  'exact replay preserves the original conflict after its condition disappears'
);

select is(
  public.library_rename_category(
    '17000000-0000-0000-0000-000000000001',
    '37000000-0000-0000-0000-000000000002',
    '47000000-0000-0000-0000-000000000003',
    '{"name":"Rename Taken"}'::jsonb,
    'rename taken'
  ),
  '{"http_status":409,"error_code":"CATEGORY_NAME_EXISTS"}'::jsonb,
  'a conflicting category rename returns a committed conflict sentinel'
);

select throws_ok(
  $$
    select public.library_rename_category(
      '17000000-0000-0000-0000-000000000001',
      '37000000-0000-0000-0000-000000000003',
      '47000000-0000-0000-0000-000000000003',
      '{"name":"Rename Taken"}'::jsonb,
      'rename taken'
    )
  $$,
  'P0001',
  'IDEMPOTENCY_MISMATCH',
  'the same method and raw body cannot reuse a receipt on another resource path'
);

reset role;

select is(
  (
    select request.response_body
    from public.api_requests as request
    where request.owner_id = '17000000-0000-0000-0000-000000000001'
      and request.request_id = '47000000-0000-0000-0000-000000000003'
  ),
  '{"category_id":"37000000-0000-0000-0000-000000000002","error_code":"CATEGORY_NAME_EXISTS"}'::jsonb,
  'the rename receipt identifies only the known target and fixed conflict code'
);

set local role service_role;

select is(
  public.library_delete_category(
    '17000000-0000-0000-0000-000000000001',
    '37000000-0000-0000-0000-000000000003',
    '47000000-0000-0000-0000-000000000004',
    '{}'::jsonb
  ) ->> 'http_status',
  '204',
  'the category that caused the rename conflict can later be deleted'
);

select is(
  public.library_rename_category(
    '17000000-0000-0000-0000-000000000001',
    '37000000-0000-0000-0000-000000000002',
    '47000000-0000-0000-0000-000000000003',
    '{"name":"Rename Taken"}'::jsonb,
    'rename taken'
  ),
  '{"http_status":409,"error_code":"CATEGORY_NAME_EXISTS"}'::jsonb,
  'exact rename replay remains a conflict after the conflicting row is deleted'
);

select is(
  public.library_create_category(
    '17000000-0000-0000-0000-000000000002',
    '47000000-0000-0000-0000-000000000005',
    '{"name":"Capacity Winner"}'::jsonb,
    'capacity winner'
  ) ->> 'http_status',
  '201',
  'the first contender obtains the thirtieth custom-category slot'
);

select is(
  public.library_create_category(
    '17000000-0000-0000-0000-000000000002',
    '47000000-0000-0000-0000-000000000006',
    '{"name":"Capacity Loser"}'::jsonb,
    'capacity loser'
  ),
  '{"http_status":409,"error_code":"CATEGORY_LIMIT_REACHED"}'::jsonb,
  'the owner lock serializes the competing contender to a committed limit conflict'
);

reset role;

select is(
  (
    select pg_catalog.jsonb_build_object(
      'custom_count', count(*)::integer,
      'contender_count', count(*) filter (
        where category.normalized_name in ('capacity winner', 'capacity loser')
      )::integer
    )
    from public.categories as category
    where category.owner_id = '17000000-0000-0000-0000-000000000002'
      and category.kind = 'custom'
  ),
  '{"custom_count":30,"contender_count":1}'::jsonb,
  'a 29-to-30 capacity competition has exactly one winner'
);

set local role service_role;

select is(
  public.library_delete_category(
    '17000000-0000-0000-0000-000000000002',
    (
      select category.id
      from public.categories as category
      where category.owner_id = '17000000-0000-0000-0000-000000000002'
        and category.normalized_name = 'capacity winner'
    ),
    '47000000-0000-0000-0000-000000000007',
    '{}'::jsonb
  ) ->> 'http_status',
  '204',
  'a category slot can be released after the capacity conflict'
);

select is(
  public.library_create_category(
    '17000000-0000-0000-0000-000000000002',
    '47000000-0000-0000-0000-000000000006',
    '{"name":"Capacity Loser"}'::jsonb,
    'capacity loser'
  ),
  '{"http_status":409,"error_code":"CATEGORY_LIMIT_REACHED"}'::jsonb,
  'a capacity receipt replays after category capacity is released'
);

select is(
  public.library_create_item(
    '17000000-0000-0000-0000-000000000003',
    '47000000-0000-0000-0000-000000000008',
    pg_catalog.jsonb_build_object(
      'url', 'https://example.test/manual',
      'category_ids', '["37000000-0000-0000-0000-000000000004"]'::jsonb
    ),
    pg_temp.conflict_prepared(
      'https://example.test/manual',
      'edge supplied value must be ignored'
    )
  ) ->> 'http_status',
  '201',
  'manual item creation does not require an Edge-precomputed category field'
);

reset role;

select is(
  (
    select search_record.normalized_fields ->> 'categories'
    from public.item_search as search_record
    join public.items as item
      on item.owner_id = search_record.owner_id
      and item.id = search_record.item_id
    where item.owner_id = '17000000-0000-0000-0000-000000000003'
      and item.normalized_url = 'https://example.test/manual'
  ),
  '여행',
  'manual create indexes the owner-validated stored canonical category name'
);

update public.library_usage
set active_item_count = 100
where owner_id = '17000000-0000-0000-0000-000000000003';

set local role service_role;

select is(
  public.library_create_item(
    '17000000-0000-0000-0000-000000000003',
    '47000000-0000-0000-0000-000000000009',
    '{"url":"https://example.test/manual"}'::jsonb,
    pg_temp.conflict_prepared('https://example.test/manual')
  ) ->> 'duplicate',
  'true',
  'an existing normalized URL still succeeds before the item quota check'
);

select is(
  public.library_create_item(
    '17000000-0000-0000-0000-000000000003',
    '47000000-0000-0000-0000-000000000010',
    '{"url":"https://example.test/item-limit"}'::jsonb,
    pg_temp.conflict_prepared('https://example.test/item-limit')
  ),
  '{"http_status":409,"error_code":"ITEM_LIMIT_REACHED"}'::jsonb,
  'item capacity returns a committed conflict sentinel'
);

reset role;

select is(
  (
    select request.response_body
    from public.api_requests as request
    where request.owner_id = '17000000-0000-0000-0000-000000000003'
      and request.request_id = '47000000-0000-0000-0000-000000000010'
  ),
  '{"error_code":"ITEM_LIMIT_REACHED"}'::jsonb,
  'the item-capacity receipt stores no raw URL or user text'
);

update public.library_usage
set active_item_count = 99
where owner_id = '17000000-0000-0000-0000-000000000003';

set local role service_role;

select is(
  public.library_create_item(
    '17000000-0000-0000-0000-000000000003',
    '47000000-0000-0000-0000-000000000010',
    '{"url":"https://example.test/item-limit"}'::jsonb,
    pg_temp.conflict_prepared('https://example.test/item-limit')
  ),
  '{"http_status":409,"error_code":"ITEM_LIMIT_REACHED"}'::jsonb,
  'an item-capacity receipt replays after capacity is released'
);

select throws_ok(
  $$
    select public.library_create_item(
      '17000000-0000-0000-0000-000000000003',
      '47000000-0000-0000-0000-000000000010',
      '{"url":"https://example.test/changed"}'::jsonb,
      pg_temp.conflict_prepared('https://example.test/changed')
    )
  $$,
  'P0001',
  'IDEMPOTENCY_MISMATCH',
  'an item-capacity request id rejects a changed raw body'
);

select throws_ok(
  $$
    select public.library_create_item(
      '17000000-0000-0000-0000-000000000099',
      '47000000-0000-0000-0000-000000000011',
      '{"url":"https://example.test/unauthorized"}'::jsonb,
      pg_temp.conflict_prepared('https://example.test/unauthorized')
    )
  $$,
  'P0001',
  'BETA_ACCESS_REQUIRED',
  'an unauthorized owner is rejected before any receipt can be stored'
);

reset role;

select ok(
  not exists (
    select 1
    from public.api_requests as request
    where request.request_id = '47000000-0000-0000-0000-000000000011'
  ),
  'unauthorized owner failures leave no idempotency receipt'
);

insert into public.items (
  owner_id,
  original_url,
  normalized_url,
  url_hash,
  source,
  display_fallback,
  text_revision,
  version,
  metadata_state
)
values (
  '17000000-0000-0000-0000-000000000003',
  'https://example.test/collision-existing',
  'https://example.test/collision-existing',
  pg_catalog.encode(
    extensions.digest(
      pg_catalog.convert_to('https://example.test/collision-request', 'UTF8'),
      'sha256'
    ),
    'hex'
  ),
  'other',
  'example.test',
  1,
  1,
  'unsupported'
);

set local role service_role;

select is(
  public.library_create_item(
    '17000000-0000-0000-0000-000000000003',
    '47000000-0000-0000-0000-000000000012',
    '{"url":"https://example.test/collision-request"}'::jsonb,
    pg_temp.conflict_prepared('https://example.test/collision-request')
  ),
  '{"http_status":409,"error_code":"URL_HASH_COLLISION"}'::jsonb,
  'a deterministic URL hash collision is committed without disclosing either URL'
);

reset role;

insert into public.api_rate_buckets (owner_id, operation, window_start, request_count)
values (
  '17000000-0000-0000-0000-000000000003',
  'create_item',
  pg_catalog.date_trunc('minute', pg_catalog.clock_timestamp()),
  10
)
on conflict (owner_id, operation, window_start)
do update set request_count = excluded.request_count;

set local role service_role;

select throws_ok(
  $$
    select public.library_create_item(
      '17000000-0000-0000-0000-000000000003',
      '47000000-0000-0000-0000-000000000013',
      '{"url":"https://example.test/rate-limited"}'::jsonb,
      pg_temp.conflict_prepared('https://example.test/rate-limited')
    )
  $$,
  'P0001',
  'RATE_LIMITED',
  'rate limiting remains a retryable raised error rather than a committed receipt'
);

reset role;

select ok(
  not exists (
    select 1
    from public.api_requests as request
    where request.owner_id = '17000000-0000-0000-0000-000000000003'
      and request.request_id = '47000000-0000-0000-0000-000000000013'
  ),
  'a rate-limited attempt stores no receipt'
);

select is(
  (
    select count(*)::integer
    from public.api_requests as request
    where request.response_body ?| array['name', 'url', 'title', 'note', 'shared_text']
  ),
  0,
  'conflict and success receipts contain no raw category names or item text'
);

select throws_ok(
  $$
    insert into public.api_requests (
      owner_id,
      request_id,
      method_path,
      request_hash,
      response_code,
      response_body
    )
    values (
      '17000000-0000-0000-0000-000000000001',
      '47000000-0000-0000-0000-000000000014',
      'POST /categories',
      pg_catalog.repeat('0', 64),
      409,
      '{"error_code":"VERSION_CONFLICT"}'::jsonb
    )
  $$,
  '23514',
  null,
  'the receipt constraint rejects a conflict code on the wrong mutation path'
);

select throws_ok(
  $$
    insert into public.api_requests (
      owner_id,
      request_id,
      method_path,
      request_hash,
      response_code,
      response_body
    )
    values (
      '17000000-0000-0000-0000-000000000003',
      '47000000-0000-0000-0000-000000000015',
      'POST /items',
      pg_catalog.repeat('0', 64),
      409,
      '{"error_code":"ITEM_LIMIT_REACHED","url":"https://example.test/private"}'::jsonb
    )
  $$,
  '23514',
  null,
  'the receipt constraint rejects extra raw request data'
);

select * from finish();
rollback;
