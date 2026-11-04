begin;

create extension if not exists pgtap with schema extensions;
set local search_path = pg_catalog, extensions, public;
select no_plan();

select ok(
  (
    select installed.prorettype = 'jsonb'::regtype
      and installed.pronargs = 0
      and installed.provolatile = 's'
      and installed.prosecdef
      and installed.proconfig @> array['search_path=""']::text[]
    from pg_catalog.pg_proc as installed
    where installed.oid = 'public.library_export_items()'::regprocedure
  ),
  'export is a no-argument stable JSONB SECURITY DEFINER RPC with a fixed search_path'
);
select is(
  (select count(*)::integer from pg_catalog.pg_proc as installed
   join pg_catalog.pg_namespace as namespace on namespace.oid = installed.pronamespace
   where namespace.nspname = 'public' and installed.proname = 'library_export_items'),
  1, 'export has no owner-parameter or alternate overload'
);
select ok(
  has_function_privilege('authenticated', 'public.library_export_items()', 'EXECUTE')
  and not has_function_privilege('anon', 'public.library_export_items()', 'EXECUTE')
  and not has_function_privilege('service_role', 'public.library_export_items()', 'EXECUTE')
  and not exists (
    select 1 from pg_catalog.pg_proc as installed,
      lateral pg_catalog.aclexplode(installed.proacl) as privilege
    where installed.oid = 'public.library_export_items()'::regprocedure
      and privilege.grantee = 0 and privilege.privilege_type = 'EXECUTE'
  ),
  'only authenticated, not PUBLIC, anon or service_role, may execute export'
);
select ok(
  (
    select installed.prorettype = 'text'::regtype
      and installed.provolatile = 'i' and installed.proparallel = 's'
      and not installed.prosecdef and not installed.proisstrict
      and installed.proconfig @> array['search_path=""']::text[]
    from pg_catalog.pg_proc as installed
    where installed.oid = 'private.library_display_title(text,text,text)'::regprocedure
  ),
  'canonical display-title helper is immutable, nullable and private with a fixed search_path'
);
select ok(
  not has_function_privilege('anon', 'private.library_display_title(text,text,text)', 'EXECUTE')
  and not has_function_privilege('authenticated', 'private.library_display_title(text,text,text)', 'EXECUTE')
  and not has_function_privilege('service_role', 'private.library_display_title(text,text,text)', 'EXECUTE')
  and not exists (
    select 1 from pg_catalog.pg_proc as installed,
      lateral pg_catalog.aclexplode(installed.proacl) as privilege
    where installed.oid = 'private.library_display_title(text,text,text)'::regprocedure
      and privilege.grantee = 0 and privilege.privilege_type = 'EXECUTE'
  ),
  'the display-title helper is not directly executable by any API role or PUBLIC'
);

insert into auth.users (
  instance_id, id, aud, role, email, encrypted_password, email_confirmed_at,
  raw_app_meta_data, raw_user_meta_data, created_at, updated_at,
  confirmation_token, email_change, email_change_token_new, recovery_token
)
select '00000000-0000-0000-0000-000000000000',
  ('29000000-0000-0000-0000-' || lpad(ordinal::text, 12, '0'))::uuid,
  'authenticated', 'authenticated', 'export-' || ordinal || '@example.test', '', now(),
  '{"provider":"google","providers":["google"]}'::jsonb, '{}'::jsonb,
  now(), now(), '', '', '', ''
from generate_series(1, 9) as owners(ordinal);

-- Six approved identities respect the existing beta cap, including one without a profile.
insert into public.beta_members (owner_id, enabled, approved_at)
select id, id < '29000000-0000-0000-0000-000000000007'::uuid,
  case when id <> '29000000-0000-0000-0000-000000000008'::uuid then now() end
from auth.users where email like 'export-%@example.test'
  and id <> '29000000-0000-0000-0000-000000000009'::uuid;
insert into public.profiles (id, state)
select id, case when id = '29000000-0000-0000-0000-000000000006'::uuid then 'deleting' else 'active' end
from auth.users where email like 'export-%@example.test'
  and id <> '29000000-0000-0000-0000-000000000005'::uuid;

create temporary table export_cases (
  ordinal integer primary key,
  label text not null,
  owner_id uuid not null default '29000000-0000-0000-0000-000000000001',
  item_id uuid generated always as (
    ('29100000-0000-0000-0000-' || lpad(ordinal::text, 12, '0'))::uuid
  ) stored,
  url text generated always as ('https://example.test/export/' || ordinal::text) stored,
  source text not null default 'other',
  user_title text,
  fetched_title text,
  fallback text not null default 'fallback.example/한글',
  note text,
  expected_title text not null,
  expected_categories jsonb not null default '[]'::jsonb,
  saved_at timestamptz not null default '2026-10-30T10:00:00Z'
);
insert into export_cases (
  ordinal, label, source, user_title, fetched_title, note, expected_title, expected_categories, saved_at
)
values
  (1, 'raw author text and Unicode', 'other', E'  내 "제목"\n둘째 𠮷  ', 'export-private-fetched-title',
    E'  첫 줄\n"따옴표"와 \\ 경로\r\n마지막 𠮷  ' || repeat('한', 150), E'내 "제목"\n둘째 𠮷',
    '["여행","둘째 \"분류\"","줄\n분류 𠮷"]'::jsonb, '2026-10-31T01:02:03.456789Z'),
  (2, 'blank user title falls through', 'instagram', '   ', E'  수집\n제목 é  ', null,
    E'수집\n제목 é', '[]'::jsonb, '2026-10-30T10:00:00Z'),
  (3, 'null author and blank fetched title', 'threads', null, '   ', E'\n\t한글 é𠮷\n',
    'fallback.example/한글', '[]'::jsonb, '2026-10-30T10:00:00Z'),
  (4, 'null author and fetched title', 'naver_blog', null, null, null,
    'fallback.example/한글', '[]'::jsonb, '2026-10-30T10:00:00Z'),
  (5, 'empty author strings stay empty', 'x', '', '', '',
    'fallback.example/한글', '[]'::jsonb, '2026-10-30T10:00:00Z'),
  (6, 'canonical btrim does not strip tabs or newlines', 'facebook', E'\n\t', 'ignored fetched title', E'\t\n',
    E'\n\t', '[]'::jsonb, '2026-10-30T10:00:00Z'),
  (7, 'link without enrichment rows', 'bluesky', null, '  검색 준비 전 제목  ', '서버에 저장된 전체 메모',
    '검색 준비 전 제목', '[]'::jsonb, '2026-10-29T10:00:00Z'),
  (8, 'soft deleted link', 'other', 'deleted title', 'deleted fetched', 'export-private-deleted-note',
    'deleted title', '[]'::jsonb, '2026-10-28T10:00:00Z');
insert into export_cases (
  ordinal, label, owner_id, source, user_title, note, expected_title, expected_categories
)
values (9, 'other owner', '29000000-0000-0000-0000-000000000002', 'mastodon',
  '타인 제목', 'export-private-other-owner-note', '타인 제목', '["타인 비밀 분류"]'::jsonb);
update export_cases
set fallback = '  fallback.example/한글  ', expected_title = '  fallback.example/한글  '
where ordinal = 4;

insert into public.items (
  id, owner_id, original_url, normalized_url, url_hash, source, display_fallback,
  user_title, fetched_title, shared_text, description, body_text, note,
  extraction_meta, metadata_state, created_at, updated_at
)
select item_id, owner_id, url, url,
  encode(extensions.digest(convert_to(url, 'UTF8'), 'sha256'), 'hex'), source, fallback,
  user_title, fetched_title, 'export-private-shared', 'export-private-description',
  'export-private-body', note, '{"adapter_version":"export-private-adapter"}'::jsonb,
  'ready', saved_at, saved_at + interval '1 day'
from export_cases order by ordinal desc;
insert into public.item_search (
  owner_id, item_id, text_revision, search_version, normalized_fields,
  alias_concepts, cue_version, cue_state, cue_flags
)
select owner_id, item_id, 1, 'search-v2.0.0',
  '{"user_title":"","fetched_title":"","note":"","ocr":"export-private-search-ocr","shared":"","description":"","body":"","categories":"","url":""}'::jsonb,
  '{"user_title":[],"fetched_title":[],"note":[],"ocr":[],"shared":[],"description":[],"body":[]}'::jsonb,
  'cues-v1.0.0', 'available', '[]'::jsonb
from export_cases where ordinal <> 7;
insert into public.item_classification (owner_id, item_id, rules_version, target_revision, state, reasons)
select owner_id, item_id, 'rules-v2.0.0', 1, 'automatic', '[{"private":"export-private-classification"}]'::jsonb
from export_cases where ordinal <> 7;
insert into public.item_category_controls (owner_id, item_id, manual_override)
select owner_id, item_id, false from export_cases where ordinal <> 7;

insert into public.categories (id, owner_id, name, normalized_name, kind, system_code)
values
  ('29200000-0000-0000-0000-000000000001', '29000000-0000-0000-0000-000000000001', '여행', '여행', 'system', 'travel'),
  ('29200000-0000-0000-0000-000000000002', '29000000-0000-0000-0000-000000000001', '둘째 "분류"', '둘째 "분류"', 'custom', null),
  ('29200000-0000-0000-0000-000000000003', '29000000-0000-0000-0000-000000000001', E'줄\n분류 𠮷', E'줄\n분류 𠮷', 'custom', null),
  ('29200000-0000-0000-0000-000000000004', '29000000-0000-0000-0000-000000000001', 'export-private-unassigned', 'export-private-unassigned', 'custom', null),
  ('29200000-0000-0000-0000-000000000098', '29000000-0000-0000-0000-000000000002', 'export-private-wrong-owner', 'export-private-wrong-owner', 'custom', null),
  ('29200000-0000-0000-0000-000000000099', '29000000-0000-0000-0000-000000000002', '타인 비밀 분류', '타인 비밀 분류', 'custom', null);
insert into public.item_categories (owner_id, item_id, category_id, origin, created_at)
values
  ('29000000-0000-0000-0000-000000000001', '29100000-0000-0000-0000-000000000001', '29200000-0000-0000-0000-000000000003', 'auto', '2026-10-31T00:01:00Z'),
  ('29000000-0000-0000-0000-000000000001', '29100000-0000-0000-0000-000000000001', '29200000-0000-0000-0000-000000000002', 'manual', '2026-10-31T00:01:00Z'),
  ('29000000-0000-0000-0000-000000000001', '29100000-0000-0000-0000-000000000001', '29200000-0000-0000-0000-000000000001', 'manual', '2026-10-31T00:00:00Z'),
  ('29000000-0000-0000-0000-000000000002', '29100000-0000-0000-0000-000000000009', '29200000-0000-0000-0000-000000000099', 'manual', '2026-10-31T00:00:00Z');

-- Deliberately corrupt only these transactional fixtures to exercise BOTH owner join fences.
-- Ordinary foreign keys prevent these rows; export must still never trust an unscoped join.
set local session_replication_role = replica;
insert into public.item_categories (owner_id, item_id, category_id, origin)
values
  ('29000000-0000-0000-0000-000000000001', '29100000-0000-0000-0000-000000000001', '29200000-0000-0000-0000-000000000099', 'manual'),
  ('29000000-0000-0000-0000-000000000002', '29100000-0000-0000-0000-000000000001', '29200000-0000-0000-0000-000000000098', 'manual');
set local session_replication_role = origin;

insert into storage.objects (id, bucket_id, name, metadata)
values ('29300000-0000-0000-0000-000000000001', 'library-images',
  '29000000-0000-0000-0000-000000000001/29100000-0000-0000-0000-000000000001/29300000-0000-0000-0000-000000000001',
  '{"size":1234,"mimetype":"image/jpeg"}'::jsonb);
insert into public.assets (
  id, owner_id, item_id, object_path, state, reserved_mime_type,
  reservation_expires_at, actual_bytes, mime_type, width, height, storage_object_id,
  verified_at, ocr_state, ocr_text, ocr_truncated
)
values (
  '29300000-0000-0000-0000-000000000001', '29000000-0000-0000-0000-000000000001',
  '29100000-0000-0000-0000-000000000001',
  '29000000-0000-0000-0000-000000000001/29100000-0000-0000-0000-000000000001/29300000-0000-0000-0000-000000000001',
  'active', 'image/jpeg', now() + interval '15 minutes', 1234, 'image/jpeg', 640, 480,
  '29300000-0000-0000-0000-000000000001', now(), 'ready', 'export-private-asset-ocr', false
);

-- Keep the deleted row and its search/classification rows to test the explicit deletion fence.
update public.items
set deleted_at = now(), original_url = null, normalized_url = null, url_hash = null,
  source = null, display_fallback = null, metadata_state = null,
  user_title = null, fetched_title = null, shared_text = null, description = null,
  body_text = null, note = null, extraction_meta = '{}'::jsonb
where id = '29100000-0000-0000-0000-000000000008';

create temporary table export_expected as
select ordinal, item_id, label, jsonb_build_object(
  'url', url, 'title', expected_title, 'user_title', user_title, 'note', note,
  'source', source, 'categories', expected_categories, 'saved_at', saved_at
) as item
from export_cases where ordinal <> 8;
create temporary table export_results (label text primary key, response jsonb not null);
grant select on export_cases, export_expected to authenticated;
grant select, insert on export_results to authenticated;

set local role authenticated;
set local "request.jwt.claim.sub" = '29000000-0000-0000-0000-000000000001';
insert into export_results values ('owner-a', public.library_export_items());
select is(
  (select jsonb_agg(key order by key) from export_results,
    lateral jsonb_object_keys(response) as keys(key) where label = 'owner-a'),
  '["items"]'::jsonb, 'root contains exactly items, not paging, IDs, counts or status fields'
);
select is(
  (select jsonb_array_length(response->'items') from export_results where label = 'owner-a'),
  7, 'export includes all owned active links, even without enrichment, and excludes soft deletion and other owners'
);
select is(
  (select jsonb_agg(entry->>'url' order by ordinal)
   from export_results, lateral jsonb_array_elements(response->'items') with ordinality as exported(entry, ordinal)
   where label = 'owner-a'),
  '["https://example.test/export/7","https://example.test/export/2","https://example.test/export/3","https://example.test/export/4","https://example.test/export/5","https://example.test/export/6","https://example.test/export/1"]'::jsonb,
  'links are ordered by created_at ascending, then ID ascending, not insertion or update order'
);
select is(
  (select jsonb_agg(key order by key) from jsonb_object_keys(entry) as keys(key)),
  '["categories","note","saved_at","source","title","url","user_title"]'::jsonb,
  'every item has exactly seven portable keys: ' || (entry->>'url')
)
from export_results, lateral jsonb_array_elements(response->'items') as exported(entry)
where label = 'owner-a';
select is(entry, expected.item, 'complete raw fields and canonical title: ' || expected.label)
from export_results as result,
  lateral jsonb_array_elements(result.response->'items') as exported(entry)
join export_expected as expected on expected.item->>'url' = entry->>'url'
where result.label = 'owner-a' order by expected.ordinal;
select ok(
  jsonb_typeof(entry->'url') = 'string' and jsonb_typeof(entry->'title') = 'string'
  and jsonb_typeof(entry->'user_title') in ('string', 'null')
  and jsonb_typeof(entry->'note') in ('string', 'null')
  and jsonb_typeof(entry->'source') = 'string' and jsonb_typeof(entry->'saved_at') = 'string'
  and jsonb_typeof(entry->'categories') = 'array'
  and not exists (
    select 1 from jsonb_array_elements(entry->'categories') as category(value)
    where jsonb_typeof(category.value) <> 'string'
  ),
  'portable field types and category names are stable: ' || (entry->>'url')
)
from export_results, lateral jsonb_array_elements(response->'items') as exported(entry)
where label = 'owner-a';
select ok(
  strpos(response::text, 'export-private-') = 0
  and strpos(response::text, '29000000-') = 0
  and strpos(response::text, '29100000-') = 0
  and strpos(response::text, '29200000-') = 0
  and strpos(response::text, '29300000-') = 0
  and strpos(response::text, 'image/jpeg') = 0,
  'export omits shared text, description, body, OCR, assets, classifications, internal IDs and private values'
) from export_results where label = 'owner-a';
select is(
  entry->'categories', '["여행","둘째 \"분류\"","줄\n분류 𠮷"]'::jsonb,
  'category names include manual and automatic selections in timestamp/ID order without foreign or unassigned names'
)
from export_results, lateral jsonb_array_elements(response->'items') as exported(entry)
where label = 'owner-a' and entry->>'url' = 'https://example.test/export/1';
select is(
  public.library_get_item(expected.item_id)->'display_title', expected.item->'title',
  'detail uses the same canonical display-title precedence: ' || expected.label
) from export_expected as expected where ordinal between 1 and 6 order by ordinal;
select is(
  entry->'display_title', expected.item->'title',
  'summary uses the same canonical display-title precedence: ' || expected.label
)
from jsonb_array_elements(public.library_list_items(50, 0)->'items') as listed(entry)
join export_expected as expected on expected.item_id = (entry->>'id')::uuid
where expected.ordinal between 1 and 6 order by expected.ordinal;
select is(
  public.library_get_item('29100000-0000-0000-0000-000000000001')->'thumbnail_asset',
  '{"id":"29300000-0000-0000-0000-000000000001","mime_type":"image/jpeg","byte_size":1234,"width":640,"height":480}'::jsonb,
  'canonical-title refactoring retains the current safe thumbnail descriptor'
);
select is(
  public.library_get_item('29100000-0000-0000-0000-000000000001')->>'content_excerpt',
  'export-private-body', 'canonical-title refactoring retains the existing body-first content excerpt'
);
select is(
  public.library_get_item('29100000-0000-0000-0000-000000000001')->>'classification_state',
  'automatic', 'canonical-title refactoring retains classification status'
);
select is(
  public.library_get_item('29100000-0000-0000-0000-000000000001')->'category_refs',
  '[{"id":"29200000-0000-0000-0000-000000000001","name":"여행","kind":"system","system_code":"travel","origin":"manual"},{"id":"29200000-0000-0000-0000-000000000002","name":"둘째 \"분류\"","kind":"custom","system_code":null,"origin":"manual"},{"id":"29200000-0000-0000-0000-000000000003","name":"줄\n분류 𠮷","kind":"custom","system_code":null,"origin":"auto"}]'::jsonb,
  'canonical-title refactoring retains complete owner-scoped category references'
);
select is(
  public.library_export_items(), (select response from export_results where label = 'owner-a'),
  'repeated export has the same deterministic snapshot representation'
);
select throws_ok(
  $$select public.library_export_items('29000000-0000-0000-0000-000000000002'::uuid)$$,
  '42883', null, 'caller cannot supply or substitute an export owner'
);
select throws_ok(
  $$select private.library_display_title('mine', 'fetched', 'fallback')$$,
  '42501', null, 'authenticated clients cannot bypass RPC access through the private title helper'
);

set local "request.jwt.claim.sub" = '29000000-0000-0000-0000-000000000002';
select is(
  public.library_export_items(),
  (select jsonb_build_object('items', jsonb_build_array(item)) from export_expected where ordinal = 9),
  'another owner receives only its own link and assigned category names'
);
set local "request.jwt.claim.sub" = '29000000-0000-0000-0000-000000000003';
select is(public.library_export_items(), '{"items":[]}'::jsonb,
  'an approved empty library returns the exact empty root and array');
set local "request.jwt.claim.sub" = '29000000-0000-0000-0000-000000000005';
select throws_ok($$select public.library_export_items()$$, 'P0001', 'BETA_ACCESS_REQUIRED',
  'approval without a profile does not allow export');
set local "request.jwt.claim.sub" = '29000000-0000-0000-0000-000000000006';
select throws_ok($$select public.library_export_items()$$, 'P0001', 'ACCOUNT_DELETING',
  'deleting account cannot export even while approved');
set local "request.jwt.claim.sub" = '29000000-0000-0000-0000-000000000007';
select throws_ok($$select public.library_export_items()$$, 'P0001', 'BETA_ACCESS_REQUIRED',
  'disabled approval cannot export an existing active profile');
set local "request.jwt.claim.sub" = '29000000-0000-0000-0000-000000000008';
select throws_ok($$select public.library_export_items()$$, 'P0001', 'BETA_ACCESS_REQUIRED',
  'unapproved member cannot export an existing active profile');
set local "request.jwt.claim.sub" = '29000000-0000-0000-0000-000000000009';
select throws_ok($$select public.library_export_items()$$, 'P0001', 'BETA_ACCESS_REQUIRED',
  'missing beta membership cannot export an existing active profile');
set local "request.jwt.claim.sub" = '';
select throws_ok($$select public.library_export_items()$$, 'P0001', 'UNAUTHENTICATED',
  'authenticated role without auth.uid cannot export');
reset role;

update public.beta_members set enabled = false
where owner_id = '29000000-0000-0000-0000-000000000006';
set local role authenticated;
set local "request.jwt.claim.sub" = '29000000-0000-0000-0000-000000000006';
select throws_ok($$select public.library_export_items()$$, 'P0001', 'ACCOUNT_DELETING',
  'deletion guard takes precedence even after beta access is revoked');
reset role;
set local role anon;
set local "request.jwt.claim.sub" = '29000000-0000-0000-0000-000000000001';
select throws_ok($$select public.library_export_items()$$, '42501', null,
  'anon cannot export even with a forged approved subject');
reset role;
set local role service_role;
set local "request.jwt.claim.sub" = '29000000-0000-0000-0000-000000000001';
select throws_ok($$select public.library_export_items()$$, '42501', null,
  'service_role cannot execute export even with an approved subject');
reset role;

-- 1000 legal rows with maximum raw URL/title/fetched-title/fallback/note/category lengths.
-- Control characters force JSON escaping; quotes, backslashes, newlines and Unicode stay intact.
create temporary table export_max_fields as
select left(repeat(chr(1) || chr(31) || E'"\\\n\t\r' || '한𠮷', 300), 300) as title,
  left(repeat(chr(1) || chr(31) || E'"\\\n\t\r' || '한𠮷', 4000), 4000) as note;
create temporary table export_max_cases as
select ordinal,
  ('29400000-0000-0000-0000-' || lpad(ordinal::text, 12, '0'))::uuid as item_id,
  prefix || repeat('a', 4096 - char_length(prefix)) as url,
  '2026-10-01T00:00:00Z'::timestamptz + ((ordinal - 1) / 2) * interval '1 second' as saved_at
from generate_series(1, 1000) as fixture(ordinal)
cross join lateral (
  select 'https://example.test/export-max/' || lpad(ordinal::text, 4, '0') || '?q=' as prefix
) as address;
insert into public.items (
  id, owner_id, original_url, normalized_url, url_hash, source, display_fallback,
  user_title, fetched_title, note, metadata_state, created_at, updated_at
)
select fixture.item_id, '29000000-0000-0000-0000-000000000004', fixture.url, fixture.url,
  encode(extensions.digest(convert_to(fixture.url, 'UTF8'), 'sha256'), 'hex'), 'mastodon',
  fields.title, fields.title, fields.title, fields.note, 'ready', fixture.saved_at,
  fixture.saved_at + interval '30 days'
from export_max_cases as fixture cross join export_max_fields as fields order by fixture.ordinal desc;
insert into public.categories (id, owner_id, name, normalized_name, kind)
select ('29500000-0000-0000-0000-' || lpad(ordinal::text, 12, '0'))::uuid,
  '29000000-0000-0000-0000-000000000004',
  lpad(ordinal::text, 2, '0') || left(fields.title, 28), 'export-max-' || ordinal, 'custom'
from generate_series(1, 5) as fixture(ordinal) cross join export_max_fields as fields;
insert into public.item_categories (owner_id, item_id, category_id, origin, created_at)
select '29000000-0000-0000-0000-000000000004', fixture.item_id, category.id,
  case when right(category.id::text, 1)::integer % 2 = 0 then 'auto' else 'manual' end,
  '2026-10-01T00:00:00Z'::timestamptz
from export_max_cases as fixture cross join public.categories as category
where category.owner_id = '29000000-0000-0000-0000-000000000004'
order by fixture.ordinal desc, category.id desc;

grant select on export_max_cases, export_max_fields to authenticated;
set local role authenticated;
set local "request.jwt.claim.sub" = '29000000-0000-0000-0000-000000000004';
insert into export_results values ('maximum', public.library_export_items());
select is(
  (select jsonb_array_length(response->'items') from export_results where label = 'maximum'),
  1000, 'all 1000 maximum-field rows are exported without a page or row limit'
);
select ok(
  (select response from export_results where label = 'maximum') = (
    select jsonb_build_object('items', jsonb_agg(jsonb_build_object(
      'url', fixture.url, 'title', fields.title, 'user_title', fields.title,
      'note', fields.note, 'source', 'mastodon',
      'categories', (
        select jsonb_agg(lpad(ordinal::text, 2, '0') || left(fields.title, 28) order by ordinal)
        from generate_series(1, 5) as category(ordinal)
      ),
      'saved_at', fixture.saved_at
    ) order by fixture.ordinal))
    from export_max_cases as fixture cross join export_max_fields as fields
  ),
  'all maximum-field values, five category names, escaping and deterministic row order are exact'
);
select ok(
  (
    select bool_and(
      char_length(entry->>'url') = 4096 and char_length(entry->>'title') = 300
      and char_length(entry->>'user_title') = 300 and char_length(entry->>'note') = 4000
      and jsonb_array_length(entry->'categories') = 5
      and (select bool_and(char_length(category.value) = 30)
        from jsonb_array_elements_text(entry->'categories') as category(value))
    )
    from export_results, lateral jsonb_array_elements(response->'items') as exported(entry)
    where label = 'maximum'
  ),
  'no legal maximum URL, title, note or category field is truncated anywhere in the 1000-row export'
);
select ok(
  (
    select bool_and(
      (select jsonb_agg(key order by key) from jsonb_object_keys(entry) as keys(key))
        = '["categories","note","saved_at","source","title","url","user_title"]'::jsonb
    )
    from export_results, lateral jsonb_array_elements(response->'items') as exported(entry)
    where label = 'maximum'
  ),
  'every maximum-field row also has only the seven portable keys'
);
select ok(
  strpos(response::text, chr(92) || 'u0001') > 0
  and strpos(response::text, chr(92) || 'u001f') > 0
  and strpos(response::text, chr(92) || 'n') > 0
  and strpos(response::text, chr(92) || '"') > 0
  and strpos(response::text, repeat(chr(92), 2)) > 0
  and strpos(response::text, '한') > 0 and strpos(response::text, '𠮷') > 0,
  'maximum-field JSON escapes control characters, newlines, quotes and backslashes while preserving Unicode'
) from export_results where label = 'maximum';
select ok(
  octet_length(convert_to(response::text, 'UTF8')) > 10 * 1024 * 1024
  and octet_length(convert_to(response::text, 'UTF8')) < 64 * 1024 * 1024,
  'a complete escaped maximum export exceeds small-body limits while staying below the Android 64MiB safety cap'
) from export_results where label = 'maximum';
reset role;

select * from finish();
rollback;
