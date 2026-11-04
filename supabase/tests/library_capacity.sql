begin;

create extension if not exists pgtap with schema extensions;
set local search_path = pg_catalog, extensions, public;
select no_plan();

select ok(
  (
    select installed.prorettype = 'integer'::regtype
      and language.lanname = 'sql'
      and installed.provolatile = 'i'
      and installed.proparallel = 's'
      and not installed.prosecdef
      and installed.proconfig @> array['search_path=""']::text[]
    from pg_catalog.pg_proc as installed
    join pg_catalog.pg_language as language on language.oid = installed.prolang
    where installed.oid = 'private.library_item_limit()'::regprocedure
  ),
  'the shared quota is a private immutable integer SQL helper with a fixed search_path'
);

select ok(
  has_function_privilege('service_role', 'private.library_item_limit()', 'EXECUTE')
  and not has_function_privilege('anon', 'private.library_item_limit()', 'EXECUTE')
  and not has_function_privilege('authenticated', 'private.library_item_limit()', 'EXECUTE')
  and not exists (
    select 1
    from pg_catalog.pg_proc as installed,
      lateral pg_catalog.aclexplode(installed.proacl) as privilege
    where installed.oid = 'private.library_item_limit()'::regprocedure
      and privilege.grantee = 0
      and privilege.privilege_type = 'EXECUTE'
  ),
  'only the service role, not PUBLIC or client roles, has direct quota-helper execution'
);

select ok(
  has_function_privilege('authenticated', 'public.member_bootstrap(uuid)', 'EXECUTE')
  and has_function_privilege('authenticated', 'public.member_me()', 'EXECUTE')
  and not has_function_privilege('anon', 'public.member_bootstrap(uuid)', 'EXECUTE')
  and not has_function_privilege('anon', 'public.member_me()', 'EXECUTE')
  and has_function_privilege('service_role', 'public.library_create_item(uuid,uuid,jsonb,jsonb)', 'EXECUTE')
  and not has_function_privilege('authenticated', 'public.library_create_item(uuid,uuid,jsonb,jsonb)', 'EXECUTE')
  and not has_function_privilege('anon', 'public.library_create_item(uuid,uuid,jsonb,jsonb)', 'EXECUTE'),
  'member reporting remains authenticated and owner-parameter creation remains service-only'
);

select ok(
  (
    select count(*) = 3 and bool_and(
      installed.prosecdef
      and installed.proconfig @> array['search_path=""']::text[]
    )
    from pg_catalog.pg_proc as installed
    where installed.oid in (
      'public.member_bootstrap(uuid)'::regprocedure,
      'public.member_me()'::regprocedure,
      'public.library_create_item(uuid,uuid,jsonb,jsonb)'::regprocedure
    )
  ),
  'all three replaced RPCs retain SECURITY DEFINER and their fixed search_path'
);

set local role service_role;
select is(private.library_item_limit(), 1000, 'the service role can read the 1000-link quota');
reset role;

insert into auth.users (
  instance_id, id, aud, role, email, encrypted_password, email_confirmed_at,
  raw_app_meta_data, raw_user_meta_data, created_at, updated_at,
  confirmation_token, email_change, email_change_token_new, recovery_token
)
select '00000000-0000-0000-0000-000000000000', owner.id,
  'authenticated', 'authenticated', owner.email, '', now(),
  '{"provider":"google","providers":["google"]}'::jsonb, '{}'::jsonb,
  now(), now(), '', '', '', ''
from (values
  ('1c000000-0000-0000-0000-000000000001'::uuid, 'capacity-a@example.test'),
  ('1c000000-0000-0000-0000-000000000002'::uuid, 'capacity-b@example.test'),
  ('1c000000-0000-0000-0000-000000000003'::uuid, 'capacity-pending@example.test'),
  ('1c000000-0000-0000-0000-000000000004'::uuid, 'capacity-deleting@example.test')
) as owner(id, email);

insert into public.beta_members (owner_id, enabled, approved_at)
values
  ('1c000000-0000-0000-0000-000000000001', true, now()),
  ('1c000000-0000-0000-0000-000000000002', true, now()),
  ('1c000000-0000-0000-0000-000000000003', false, null),
  ('1c000000-0000-0000-0000-000000000004', true, now());

-- Even existing profile/usage rows must not bypass approval or deletion guards.
insert into public.profiles (id, state)
values
  ('1c000000-0000-0000-0000-000000000003', 'active'),
  ('1c000000-0000-0000-0000-000000000004', 'deleting');
insert into public.library_usage (owner_id)
values
  ('1c000000-0000-0000-0000-000000000003'),
  ('1c000000-0000-0000-0000-000000000004');

set local role authenticated;
set local "request.jwt.claim.sub" = '1c000000-0000-0000-0000-000000000001';
select is(
  public.member_bootstrap('4c000000-0000-0000-0000-000000000101') -> 'limits',
  '{"items":1000,"image_bytes":20000000}'::jsonb,
  'bootstrap reports the raised link quota without changing the image-byte quota'
);
select is(
  public.member_me() -> 'usage',
  '{"active_item_count":0,"used_image_bytes":0,"reserved_image_bytes":0}'::jsonb,
  'a newly bootstrapped member has no stored links or image usage'
);

set local "request.jwt.claim.sub" = '1c000000-0000-0000-0000-000000000002';
select is(
  public.member_bootstrap('4c000000-0000-0000-0000-000000000102') #>> '{profile,id}',
  '1c000000-0000-0000-0000-000000000002',
  'bootstrap still derives the owner from the authenticated identity'
);
reset role;

create function pg_temp.capacity_prepared(
  p_url text,
  p_source text default 'other',
  p_metadata_allowed boolean default false
)
returns jsonb
language sql
immutable
as $$
  select jsonb_build_object(
    'normalized_url', p_url,
    'url_hash', encode(extensions.digest(convert_to(p_url, 'UTF8'), 'sha256'), 'hex'),
    'source', p_source,
    'display_fallback', 'capacity fixture',
    'normalized_fields', jsonb_build_object(
      'user_title', '', 'fetched_title', '', 'note', '', 'ocr', '',
      'shared', '', 'description', '', 'body', '', 'categories', '', 'url', lower(p_url)
    ),
    'alias_concepts', jsonb_build_object(
      'user_title', '[]'::jsonb, 'fetched_title', '[]'::jsonb,
      'note', '[]'::jsonb, 'ocr', '[]'::jsonb, 'shared', '[]'::jsonb,
      'description', '[]'::jsonb, 'body', '[]'::jsonb
    ),
    'cue_state', 'limited', 'cue_flags', '["short_text"]'::jsonb,
    'metadata_allowed', p_metadata_allowed
  );
$$;
grant execute on function pg_temp.capacity_prepared(text, text, boolean) to service_role;

-- Real-row prefill, not 1000 API saves or a throughput benchmark. Every seeded
-- item has its projection rows; the counter is derived from actual active rows.
-- No rate buckets are reset or bypassed for the API calls under test.
create temporary table capacity_fixtures as
select ordinal,
  ('5c000000-0000-0000-0000-' || lpad(ordinal::text, 12, '0'))::uuid as id,
  'https://example.test/capacity/' || ordinal as url
from generate_series(1, 999) as ordinal;

create function pg_temp.seed_capacity_items(p_first integer, p_last integer)
returns void
language plpgsql
as $$
begin
  insert into public.items (
    id, owner_id, original_url, normalized_url, url_hash, source,
    display_fallback, metadata_state
  )
  select fixture.id, '1c000000-0000-0000-0000-000000000001',
    fixture.url, fixture.url,
    encode(extensions.digest(convert_to(fixture.url, 'UTF8'), 'sha256'), 'hex'),
    'other', 'capacity fixture', 'unsupported'
  from pg_temp.capacity_fixtures as fixture
  where fixture.ordinal between p_first and p_last;

  insert into public.item_search (
    owner_id, item_id, text_revision, search_version, normalized_fields,
    alias_concepts, cue_version, cue_state, cue_flags
  )
  select '1c000000-0000-0000-0000-000000000001', fixture.id, 1, 'search-v2.0.0',
    pg_temp.capacity_prepared(fixture.url) -> 'normalized_fields',
    pg_temp.capacity_prepared(fixture.url) -> 'alias_concepts',
    'cues-v1.0.0', 'limited', '["short_text"]'::jsonb
  from pg_temp.capacity_fixtures as fixture
  where fixture.ordinal between p_first and p_last;

  insert into public.item_classification (
    owner_id, item_id, rules_version, target_revision, state, reasons
  )
  select '1c000000-0000-0000-0000-000000000001', fixture.id,
    'rules-v2.0.0', 1, 'unclassified', '[]'::jsonb
  from pg_temp.capacity_fixtures as fixture
  where fixture.ordinal between p_first and p_last;

  insert into public.item_category_controls (owner_id, item_id, manual_override)
  select '1c000000-0000-0000-0000-000000000001', fixture.id, false
  from pg_temp.capacity_fixtures as fixture
  where fixture.ordinal between p_first and p_last;

  update public.library_usage
  set active_item_count = (
    select count(*)::integer from public.items
    where owner_id = '1c000000-0000-0000-0000-000000000001' and deleted_at is null
  )
  where owner_id = '1c000000-0000-0000-0000-000000000001';
end;
$$;

select pg_temp.seed_capacity_items(1, 100);

create temporary table capacity_results (label text primary key, response jsonb not null);
grant select, insert on capacity_results to service_role;

set local role service_role;
insert into capacity_results values (
  '101',
  public.library_create_item(
    '1c000000-0000-0000-0000-000000000001',
    '4c000000-0000-0000-0000-000000000001',
    '{"url":"https://example.test/capacity/101"}'::jsonb,
    pg_temp.capacity_prepared('https://example.test/capacity/101')
  )
);
select is(
  (select response ->> 'http_status' from capacity_results where label = '101'),
  '201',
  'a member with 100 actual active rows can save the 101st link'
);
reset role;

select is(
  (select count(*)::integer from public.items
   where owner_id = '1c000000-0000-0000-0000-000000000001' and deleted_at is null),
  101,
  'the above-100 save creates a real active item'
);
select is(
  (select active_item_count from public.library_usage
   where owner_id = '1c000000-0000-0000-0000-000000000001'),
  101,
  'the above-100 save increments the owner counter exactly once'
);

select pg_temp.seed_capacity_items(102, 999);
select ok(
  (select count(*) = 999 from public.items
   where owner_id = '1c000000-0000-0000-0000-000000000001' and deleted_at is null)
  and (select active_item_count = 999 from public.library_usage
       where owner_id = '1c000000-0000-0000-0000-000000000001'),
  'the boundary fixture has 999 real active rows and an equal usage counter'
);

set local role service_role;
insert into capacity_results values (
  '1000',
  public.library_create_item(
    '1c000000-0000-0000-0000-000000000001',
    '4c000000-0000-0000-0000-000000000002',
    '{"url":"https://x.com/capacity/status/1000"}'::jsonb,
    pg_temp.capacity_prepared('https://x.com/capacity/status/1000', 'x', true)
  )
);
select is(
  (select response ->> 'http_status' from capacity_results where label = '1000'),
  '201',
  'the 999-to-1000 boundary accepts one new active link'
);
select is(
  public.library_create_item(
    '1c000000-0000-0000-0000-000000000001',
    '4c000000-0000-0000-0000-000000000001',
    '{"url":"https://example.test/capacity/101"}'::jsonb,
    null
  ) ->> 'http_status',
  '201',
  'an existing successful request replays its original status at capacity'
);
insert into capacity_results values (
  'exact_duplicate',
  public.library_create_item(
    '1c000000-0000-0000-0000-000000000001',
    '4c000000-0000-0000-0000-000000000003',
    '{"url":"https://example.test/capacity/101"}'::jsonb,
    pg_temp.capacity_prepared('https://example.test/capacity/101')
  )
), (
  'social_duplicate',
  public.library_create_item(
    '1c000000-0000-0000-0000-000000000001',
    '4c000000-0000-0000-0000-000000000004',
    '{"url":"https://twitter.com/other_user/status/1000?s=20"}'::jsonb,
    pg_temp.capacity_prepared('https://twitter.com/other_user/status/1000?s=20', 'x', true)
  )
);
select ok(
  (select response ->> 'http_status' = '200' and response ->> 'duplicate' = 'true'
      and response #>> '{item,id}' = (
        select response #>> '{item,id}' from capacity_results where label = '101'
      )
   from capacity_results where label = 'exact_duplicate'),
  'an exact URL duplicate returns the existing item at capacity'
);
select ok(
  (select response ->> 'http_status' = '200' and response ->> 'duplicate' = 'true'
      and response #>> '{item,id}' = (
        select response #>> '{item,id}' from capacity_results where label = '1000'
      )
   from capacity_results where label = 'social_duplicate'),
  'a social-post alias with a different hash returns the existing item at capacity'
);
select is(
  public.library_create_item(
    '1c000000-0000-0000-0000-000000000001',
    '4c000000-0000-0000-0000-000000000005',
    '{"url":"https://example.test/capacity/1001"}'::jsonb,
    pg_temp.capacity_prepared('https://example.test/capacity/1001')
  ),
  '{"http_status":409,"error_code":"ITEM_LIMIT_REACHED"}'::jsonb,
  'the 1001st unique active link returns a committed capacity conflict'
);
reset role;

select ok(
  (select count(*) = 1000 from public.items
   where owner_id = '1c000000-0000-0000-0000-000000000001' and deleted_at is null)
  and (select active_item_count = 1000 from public.library_usage
       where owner_id = '1c000000-0000-0000-0000-000000000001')
  and not exists (
    select 1 from public.items
    where owner_id = '1c000000-0000-0000-0000-000000000001'
      and normalized_url = 'https://example.test/capacity/1001'
  ),
  'duplicates and the rejected 1001st link leave exactly 1000 real active rows'
);
select is(
  (select response_body from public.api_requests
   where owner_id = '1c000000-0000-0000-0000-000000000001'
     and request_id = '4c000000-0000-0000-0000-000000000005' and response_code = 409),
  '{"error_code":"ITEM_LIMIT_REACHED"}'::jsonb,
  'the capacity receipt contains only its minimal conflict code'
);
select is(
  (select sum(request_count)::integer from public.api_rate_buckets
   where owner_id = '1c000000-0000-0000-0000-000000000001' and operation = 'create_item'),
  2,
  'only the two actual API saves consume the unchanged per-minute rate budget'
);
select is(
  (select count(*)::integer from public.processing_jobs as job
   join public.items as item on item.owner_id = job.owner_id and item.id = job.item_id
   where item.owner_id = '1c000000-0000-0000-0000-000000000001'
     and item.original_url = 'https://x.com/capacity/status/1000'
     and item.source = 'x' and item.metadata_state = 'queued'
     and job.kind = 'metadata' and job.state = 'queued'),
  1,
  'the boundary save preserves the 027 source and metadata job contract'
);

set local role authenticated;
set local "request.jwt.claim.sub" = '1c000000-0000-0000-0000-000000000001';
select is(
  public.member_me() -> 'limits',
  '{"items":1000,"image_bytes":20000000}'::jsonb,
  'member_me reports the same raised quota and unchanged image-byte quota'
);
select is(
  public.member_me() #>> '{usage,active_item_count}',
  '1000',
  'member_me reports the actual full owner counter'
);
select is(
  public.member_bootstrap('4c000000-0000-0000-0000-000000000103') #>> '{usage,active_item_count}',
  '1000',
  'repeated bootstrap preserves and reports all 1000 saved links'
);
reset role;

set local role service_role;
select is(
  public.library_delete_item(
    '1c000000-0000-0000-0000-000000000001',
    '4c000000-0000-0000-0000-000000000007',
    '5c000000-0000-0000-0000-000000000001',
    '{"expected_version":1}'::jsonb
  ) ->> 'http_status',
  '202',
  'deleting a real seeded item releases one slot through the normal deletion RPC'
);
select is(
  public.library_create_item(
    '1c000000-0000-0000-0000-000000000001',
    '4c000000-0000-0000-0000-000000000005',
    '{"url":"https://example.test/capacity/1001"}'::jsonb,
    null
  ),
  '{"http_status":409,"error_code":"ITEM_LIMIT_REACHED"}'::jsonb,
  'the original capacity conflict stays sticky after a real slot release'
);
select throws_ok(
  $$ select public.library_create_item(
    '1c000000-0000-0000-0000-000000000001',
    '4c000000-0000-0000-0000-000000000005',
    '{"url":"https://example.test/changed"}'::jsonb, null
  ) $$,
  'P0001', 'IDEMPOTENCY_MISMATCH',
  'a sticky capacity request cannot be reused with another raw body'
);
reset role;

select ok(
  (select count(*) = 999 from public.items
   where owner_id = '1c000000-0000-0000-0000-000000000001' and deleted_at is null)
  and (select active_item_count = 999 from public.library_usage
       where owner_id = '1c000000-0000-0000-0000-000000000001'),
  'deletion and sticky replay leave the released slot genuinely available'
);

set local role service_role;
select is(
  public.library_create_item(
    '1c000000-0000-0000-0000-000000000001',
    '4c000000-0000-0000-0000-000000000006',
    '{"url":"https://example.test/capacity/1001"}'::jsonb,
    pg_temp.capacity_prepared('https://example.test/capacity/1001')
  ) ->> 'http_status',
  '201',
  'a new request ID can save the previously rejected URL after slot release'
);
select is(
  public.library_create_item(
    '1c000000-0000-0000-0000-000000000002',
    '4c000000-0000-0000-0000-000000000002',
    '{"url":"https://x.com/capacity/status/1000"}'::jsonb,
    pg_temp.capacity_prepared('https://x.com/capacity/status/1000', 'x', true)
  ) ->> 'http_status',
  '201',
  'another approved owner has independent capacity, social deduplication and request IDs'
);
select throws_ok(
  $$ select public.library_create_item(
    null, '4c000000-0000-0000-0000-000000000008', '{}'::jsonb, null
  ) $$,
  'P0001', 'UNAUTHENTICATED', 'the service RPC still rejects a missing owner'
);
select throws_ok(
  $$ select public.library_create_item(
    '1c000000-0000-0000-0000-000000000003',
    '4c000000-0000-0000-0000-000000000008', '{}'::jsonb, null
  ) $$,
  'P0001', 'BETA_ACCESS_REQUIRED',
  'an existing profile and usage row do not allow an unapproved owner to save'
);
select throws_ok(
  $$ select public.library_create_item(
    '1c000000-0000-0000-0000-000000000004',
    '4c000000-0000-0000-0000-000000000008', '{}'::jsonb, null
  ) $$,
  'P0001', 'ACCOUNT_DELETING', 'a deleting owner cannot use the raised capacity'
);
reset role;

select ok(
  (select count(*) = 1000 from public.items
   where owner_id = '1c000000-0000-0000-0000-000000000001' and deleted_at is null)
  and (select active_item_count = 1000 from public.library_usage
       where owner_id = '1c000000-0000-0000-0000-000000000001')
  and (select active_item_count = 1 from public.library_usage
       where owner_id = '1c000000-0000-0000-0000-000000000002')
  and not exists (
    select 1 from public.api_requests
    where owner_id in ('1c000000-0000-0000-0000-000000000003', '1c000000-0000-0000-0000-000000000004')
  ),
  'recovery refills only owner A and rejected owners receive no write receipts'
);

set local role authenticated;
set local "request.jwt.claim.sub" = '1c000000-0000-0000-0000-000000000002';
select is(
  public.member_me() #>> '{usage,active_item_count}', '1',
  'member reporting exposes only the current owner usage, not the full other library'
);
select is(
  (select count(*)::integer from public.items
   where owner_id = '1c000000-0000-0000-0000-000000000001'),
  0, 'RLS hides the full library from another approved member'
);
select throws_ok(
  $$ select public.library_get_item('5c000000-0000-0000-0000-000000000002') $$,
  'P0001', 'ITEM_NOT_FOUND', 'another member cannot fetch a known full-library item ID'
);
select throws_ok(
  $$ select public.library_create_item(
    '1c000000-0000-0000-0000-000000000001',
    '4c000000-0000-0000-0000-000000000009', '{}'::jsonb, null
  ) $$,
  '42501', null, 'an authenticated member cannot submit a different owner to creation'
);
select throws_ok(
  $$ select private.library_item_limit() $$,
  '42501', null, 'authenticated members cannot directly call the private quota helper'
);

set local "request.jwt.claim.sub" = '1c000000-0000-0000-0000-000000000003';
select is(
  public.member_me(), '{"state":"pending_approval"}'::jsonb,
  'unapproved member reporting withholds protected quota and usage'
);
select throws_ok(
  $$ select public.member_bootstrap('4c000000-0000-0000-0000-000000000104') $$,
  'P0001', 'BETA_ACCESS_REQUIRED', 'the raised reporting quota does not bypass bootstrap approval'
);

set local "request.jwt.claim.sub" = '1c000000-0000-0000-0000-000000000004';
select is(
  public.member_me(), '{"state":"deleting"}'::jsonb,
  'deleting member reporting still withholds protected quota and usage'
);
select throws_ok(
  $$ select public.member_bootstrap('4c000000-0000-0000-0000-000000000105') $$,
  'P0001', 'ACCOUNT_DELETING', 'bootstrap retains its deleting-profile guard'
);

set local "request.jwt.claim.sub" = '';
select throws_ok(
  $$ select public.member_me() $$,
  'P0001', 'UNAUTHENTICATED', 'member_me rejects an authenticated role without an identity'
);
select throws_ok(
  $$ select public.member_bootstrap('4c000000-0000-0000-0000-000000000106') $$,
  'P0001', 'UNAUTHENTICATED', 'bootstrap rejects an authenticated role without an identity'
);
reset role;

set local role anon;
select throws_ok(
  $$ select private.library_item_limit() $$,
  '42501', null, 'anon cannot directly call the private quota helper'
);
select throws_ok(
  $$ select public.member_me() $$,
  '42501', null, 'anon cannot read the raised member quota or usage'
);
select throws_ok(
  $$ select public.member_bootstrap('4c000000-0000-0000-0000-000000000107') $$,
  '42501', null, 'anon cannot bootstrap a library'
);
select throws_ok(
  $$ select public.library_create_item(
    '1c000000-0000-0000-0000-000000000001',
    '4c000000-0000-0000-0000-000000000009', '{}'::jsonb, null
  ) $$,
  '42501', null, 'anon cannot submit an owner to the service creation RPC'
);
reset role;

select * from finish();
rollback;
