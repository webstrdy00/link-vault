begin;

create extension if not exists pgtap with schema extensions;
set local search_path = pg_catalog, extensions, public;
select no_plan();

select ok(
  has_function_privilege('service_role', 'public.library_create_item(uuid,uuid,jsonb,jsonb)', 'EXECUTE')
  and not has_function_privilege('anon', 'public.library_create_item(uuid,uuid,jsonb,jsonb)', 'EXECUTE')
  and not has_function_privilege('authenticated', 'public.library_create_item(uuid,uuid,jsonb,jsonb)', 'EXECUTE')
  and has_function_privilege('authenticated', 'public.library_search_items(jsonb,jsonb,integer,integer)', 'EXECUTE')
  and not has_function_privilege('anon', 'public.library_search_items(jsonb,jsonb,integer,integer)', 'EXECUTE')
  and not has_function_privilege('service_role', 'public.library_search_items(jsonb,jsonb,integer,integer)', 'EXECUTE')
  and not has_function_privilege('anon', 'private.library_search_items_unfenced(jsonb,jsonb,integer,integer)', 'EXECUTE')
  and not has_function_privilege('authenticated', 'private.library_search_items_unfenced(jsonb,jsonb,integer,integer)', 'EXECUTE')
  and not has_function_privilege('service_role', 'private.library_search_items_unfenced(jsonb,jsonb,integer,integer)', 'EXECUTE'),
  'source replacements preserve service-only creation, authenticated search and private unfenced search'
);
select ok(
  installed.prosecdef and installed.proconfig = array['search_path=""']::text[],
  installed.oid::regprocedure::text || ' remains SECURITY DEFINER with an empty search path'
)
from pg_catalog.pg_proc as installed
where installed.oid in (
  'public.library_create_item(uuid,uuid,jsonb,jsonb)'::regprocedure,
  'public.library_search_items(jsonb,jsonb,integer,integer)'::regprocedure,
  'private.library_search_items_unfenced(jsonb,jsonb,integer,integer)'::regprocedure
);

insert into auth.users (
  instance_id, id, aud, role, email, encrypted_password, email_confirmed_at,
  raw_app_meta_data, raw_user_meta_data, created_at, updated_at,
  confirmation_token, email_change, email_change_token_new, recovery_token
)
select '00000000-0000-0000-0000-000000000000', owner_id, 'authenticated', 'authenticated',
  'service-source-' || ordinal || '@example.test', '', now(),
  '{"provider":"google","providers":["google"]}'::jsonb, '{}'::jsonb, now(), now(), '', '', '', ''
from (values
  (1, '27000000-0000-0000-0000-000000000001'::uuid),
  (2, '27000000-0000-0000-0000-000000000002'::uuid),
  (3, '27000000-0000-0000-0000-000000000003'::uuid),
  (4, '27000000-0000-0000-0000-000000000004'::uuid)
) as owners(ordinal, owner_id);
insert into public.beta_members (owner_id, enabled, approved_at)
select id, id <> '27000000-0000-0000-0000-000000000003'::uuid, now()
from auth.users where email like 'service-source-%@example.test';
insert into public.profiles (id, state)
select id, case when id = '27000000-0000-0000-0000-000000000004'::uuid then 'deleting' else 'active' end
from auth.users where email like 'service-source-%@example.test';
insert into public.library_usage (owner_id)
select id from auth.users where email like 'service-source-%@example.test';
insert into public.categories (id, owner_id, name, normalized_name, kind)
values ('27300000-0000-0000-0000-000000000001', '27000000-0000-0000-0000-000000000001',
  'Authored category', 'authored category', 'custom');

create function pg_temp.service_prepared(p_body jsonb, p_source text)
returns jsonb
language sql
immutable
as $$
  select jsonb_build_object(
    'normalized_url', p_body->>'url',
    'url_hash', encode(extensions.digest(convert_to(p_body->>'url', 'UTF8'), 'sha256'), 'hex'),
    'source', p_source, 'display_fallback', 'service source fixture',
    'normalized_fields', jsonb_build_object(
      'user_title', lower(btrim(coalesce(p_body->>'title', ''))), 'fetched_title', '',
      'note', lower(btrim(coalesce(p_body->>'note', ''))), 'ocr', '',
      'shared', lower(btrim(coalesce(p_body->>'shared_text', ''))), 'description', '', 'body', '',
      'categories', '', 'url', lower(p_body->>'url')
    ),
    'alias_concepts', '{"user_title":[],"fetched_title":[],"note":[],"ocr":[],"shared":[],"description":[],"body":[]}'::jsonb,
    'cue_state', 'available', 'cue_flags', '[]'::jsonb, 'metadata_allowed', false
  );
$$;
grant execute on function pg_temp.service_prepared(jsonb, text) to service_role;

create temporary table service_cases (ordinal integer primary key, source text not null, url text not null, body jsonb);
insert into service_cases (ordinal, source, url) values
  (1, 'instagram', 'https://www.instagram.com/p/SourceInstagram/'),
  (2, 'threads', 'https://www.threads.com/@source/post/SourceThreads'),
  (3, 'naver_blog', 'https://blog.naver.com/source/1'),
  (4, 'x', 'https://x.com/source/status/27001'),
  (5, 'facebook', 'https://www.facebook.com/source/posts/1'),
  (6, 'bluesky', 'https://bsky.app/profile/source.test/post/one'),
  (7, 'mastodon', 'https://mastodon.social/@source/1'),
  (8, 'other', 'https://other.example.test/source/1');
update service_cases set body = jsonb_build_object(
  'url', url, 'title', '  Source authored ' || source || '  ',
  'note', '  Authored note ' || source || '  ', 'shared_text', '  Authored shared ' || source || '  ',
  'category_ids', jsonb_build_array('27300000-0000-0000-0000-000000000001')
);
create temporary table service_results (ordinal integer primary key, response jsonb not null);
grant select on table service_cases to service_role, authenticated;
grant select, insert on table service_results to service_role;
grant select on table service_results to authenticated;

set local role service_role;
insert into service_results
select ordinal, public.library_create_item(
  '27000000-0000-0000-0000-000000000001',
  ('27400000-0000-0000-0000-' || lpad(ordinal::text, 12, '0'))::uuid,
  body, pg_temp.service_prepared(body, source)
) from service_cases order by ordinal;
select is(response->>'http_status', '201', 'create RPC accepts source ' || source)
from service_cases join service_results using (ordinal) order by ordinal;
select is(
  jsonb_build_object(
    'source', response #> '{item,source}', 'url', response #> '{item,url}',
    'user_title', response #> '{item,user_title}', 'note', response #> '{item,note}',
    'shared_text', response #> '{item,shared_text}', 'display_title', response #> '{item,display_title}',
    'version', response #> '{item,version}', 'text_revision', response #> '{item,text_revision}',
    'manual_override', response #> '{item,manual_override}',
    'category_refs', response #> '{item,category_refs}', 'thumbnail_asset', response #> '{item,thumbnail_asset}'
  ),
  jsonb_build_object(
    'source', source, 'url', url, 'user_title', body->'title', 'note', body->'note',
    'shared_text', body->'shared_text', 'display_title', btrim(body->>'title'),
    'version', 1, 'text_revision', 1, 'manual_override', true,
    'category_refs', '[{"id":"27300000-0000-0000-0000-000000000001","name":"Authored category","kind":"custom","system_code":null,"origin":"manual"}]'::jsonb,
    'thumbnail_asset', null
  ),
  'creation retains authored title/note/shared text/category/revisions and the thumbnail projection for ' || source
) from service_cases join service_results using (ordinal) order by ordinal;

select is(
  public.library_create_item(
    '27000000-0000-0000-0000-000000000001', '27400000-0000-0000-0000-000000000004',
    sample.body, pg_temp.service_prepared(sample.body, sample.source)
  ), expected.response, 'an X source creation replays its existing receipt unchanged'
)
from service_cases as sample join service_results as expected using (ordinal) where ordinal = 4;
select is(
  public.library_create_item(
    '27000000-0000-0000-0000-000000000001', '27400000-0000-0000-0001-000000000004',
    jsonb_set(sample.body, '{url}', '"https://twitter.com/other/status/00027001?s=20"'::jsonb),
    pg_temp.service_prepared(
      jsonb_set(sample.body, '{url}', '"https://twitter.com/other/status/00027001?s=20"'::jsonb), 'x'
    )
  ) #>> '{item,id}', expected.response #>> '{item,id}',
  'new X sources retain 025 cross-host post identity deduplication'
)
from service_cases as sample join service_results as expected using (ordinal) where ordinal = 4;
select is(
  public.library_create_item(
    '27000000-0000-0000-0000-000000000002', '27400000-0000-0000-0002-000000000004',
    sample.body - 'category_ids', pg_temp.service_prepared(sample.body - 'category_ids', 'x')
  )->>'http_status', '201', 'the same X post remains independently creatable by another owner'
) from service_cases as sample where ordinal = 4;

select throws_ok(
  format(
    'select public.library_create_item(%L::uuid, %L::uuid, %L::jsonb, jsonb_set(%L::jsonb, ''{source}'', %L::jsonb))',
    '27000000-0000-0000-0000-000000000001', '27400000-0000-0000-0003-000000000001',
    sample.body::text, pg_temp.service_prepared(sample.body, 'x')::text, invalid.value
  ), 'P0001', 'INVALID_PREPARED', 'creation rejects invalid prepared source ' || invalid.value
)
from service_cases as sample cross join (values
  ('"twitter"'), ('"X"'), ('"facebook.com"'), ('"bluesky.social"'), ('"youtube"'),
  ('""'), ('null'), ('8'), ('{}'), ('[]')
) as invalid(value) where sample.ordinal = 4;
select throws_ok(
  $$select public.library_create_item(
    null, '27400000-0000-0000-0003-000000000002', '{"url":"https://x.com/source/status/2"}'::jsonb,
    pg_temp.service_prepared('{"url":"https://x.com/source/status/2"}'::jsonb, 'x')
  )$$, 'P0001', 'UNAUTHENTICATED', 'service creation rejects an absent owner'
);
select throws_ok(
  $$select public.library_create_item(
    '27000000-0000-0000-0000-000000000003', '27400000-0000-0000-0003-000000000003',
    '{"url":"https://x.com/source/status/3"}'::jsonb,
    pg_temp.service_prepared('{"url":"https://x.com/source/status/3"}'::jsonb, 'x')
  )$$, 'P0001', 'BETA_ACCESS_REQUIRED', 'disabled members cannot create new sources'
);
select throws_ok(
  $$select public.library_create_item(
    '27000000-0000-0000-0000-000000000004', '27400000-0000-0000-0003-000000000004',
    '{"url":"https://x.com/source/status/4"}'::jsonb,
    pg_temp.service_prepared('{"url":"https://x.com/source/status/4"}'::jsonb, 'x')
  )$$, 'P0001', 'ACCOUNT_DELETING', 'deleting profiles cannot create new sources'
);
reset role;
select is((select count(*) from public.items where owner_id = '27000000-0000-0000-0000-000000000001'),
  8::bigint, 'replays, deduplication and invalid sources create no extra owner rows');
select is((select active_item_count from public.library_usage where owner_id = '27000000-0000-0000-0000-000000000001'),
  8, 'duplicate creation does not increment the locked usage count');
select throws_ok(
  $$update public.items set source = 'twitter' where owner_id = '27000000-0000-0000-0000-000000000001'$$,
  '23514', null, 'the storage constraint rejects source identifiers outside the fixed eight'
);

-- Attach a verified active image to X so both empty/text search must retain 026.
insert into storage.objects (id, bucket_id, name, metadata)
select '27200000-0000-0000-0000-000000000001', 'library-images',
  '27000000-0000-0000-0000-000000000001/' || (response #>> '{item,id}') || '/27200000-0000-0000-0000-000000000001',
  '{"size":1234,"mimetype":"image/jpeg"}'::jsonb
from service_results where ordinal = 4;
insert into public.assets (
  id, owner_id, item_id, object_path, state, reserved_mime_type, actual_bytes, mime_type,
  width, height, storage_object_id, verified_at, ocr_state, ocr_text, reservation_expires_at
)
select '27200000-0000-0000-0000-000000000001', '27000000-0000-0000-0000-000000000001',
  (response #>> '{item,id}')::uuid,
  '27000000-0000-0000-0000-000000000001/' || (response #>> '{item,id}') || '/27200000-0000-0000-0000-000000000001',
  'active', 'image/jpeg', 1234, 'image/jpeg', 640, 480,
  '27200000-0000-0000-0000-000000000001', now(), 'ready', 'private source OCR text', now() + interval '15 minutes'
from service_results where ordinal = 4;
create temporary table service_expected as
select sample.ordinal, sample.source,
  private.library_item_json('27000000-0000-0000-0000-000000000001', (response #>> '{item,id}')::uuid, false) as summary
from service_cases as sample join service_results using (ordinal);
grant select on table service_expected to authenticated;

set local role authenticated;
set local "request.jwt.claim.sub" = '27000000-0000-0000-0000-000000000001';
select is(
  public.library_search_items(plan.body, jsonb_build_object('source', expected.source), 50, 0),
  jsonb_build_object('items', jsonb_build_array(expected.summary || jsonb_build_object('match_type', plan.match_type)), 'has_more', false),
  plan.label || ' search filters server results to only ' || expected.source || ' and retains the complete summary'
)
from service_expected as expected cross join (values
  ('empty', '{"query":"","terms":[],"groups":[]}'::jsonb, null::text),
  ('text', '{"query":"authored","terms":["authored"],"groups":[{"kind":"literal","value":"authored"}]}'::jsonb, 'literal')
) as plan(label, body, match_type) order by ordinal, plan.label;
select is(
  public.library_search_items(
    '{"query":"notpresent","terms":["notpresent"],"groups":[{"kind":"literal","value":"notpresent"}]}'::jsonb,
    jsonb_build_object('source', source), 50, 0
  ), '{"items":[],"has_more":false}'::jsonb, 'nonmatching text is empty for source ' || source
) from service_cases order by ordinal;
select is(
  (select jsonb_agg(entry->>'source' order by entry->>'source')
   from jsonb_array_elements(public.library_search_items(plan.body, '{}'::jsonb, 50, 0)->'items') as results(entry)),
  '["bluesky","facebook","instagram","mastodon","naver_blog","other","threads","x"]'::jsonb,
  'omitting the source returns every owned source in ' || plan.label || ' search, excluding the other owner'
)
from (values
  ('empty', '{"query":"","terms":[],"groups":[]}'::jsonb),
  ('text', '{"query":"authored","terms":["authored"],"groups":[{"kind":"literal","value":"authored"}]}'::jsonb)
) as plan(label, body);
select is(
  jsonb_build_object(
    'thumbnail_asset', entry->'thumbnail_asset', 'has_attachment', entry->'has_attachment',
    'display_title', entry->'display_title', 'note_excerpt', entry->'note_excerpt', 'category_refs', entry->'category_refs'
  ),
  jsonb_build_object(
    'thumbnail_asset', '{"id":"27200000-0000-0000-0000-000000000001","mime_type":"image/jpeg","byte_size":1234,"width":640,"height":480}'::jsonb,
    'has_attachment', true, 'display_title', 'Source authored x', 'note_excerpt', '  Authored note x  ',
    'category_refs', '[{"id":"27300000-0000-0000-0000-000000000001","name":"Authored category","kind":"custom","system_code":null,"origin":"manual"}]'::jsonb
  ), 'X source search retains authored presentation, category and the active thumbnail descriptor'
)
from jsonb_array_elements(public.library_search_items(
  '{"query":"authored","terms":["authored"],"groups":[{"kind":"literal","value":"authored"}]}'::jsonb,
  '{"source":"x"}'::jsonb, 50, 0
)->'items') as results(entry);
select ok(
  not (entry ?| array['active_asset', 'object_path', 'ocr_text', 'note', 'user_title', 'shared_text'])
    and strpos(entry::text, 'private source OCR text') = 0,
  'source-filtered search does not leak detail-only text or storage descriptors'
)
from jsonb_array_elements(public.library_search_items(
  '{"query":"","terms":[],"groups":[]}'::jsonb, '{"source":"x"}'::jsonb, 50, 0
)->'items') as results(entry);
select is(
  public.library_search_items('{"query":"","terms":[],"groups":[]}'::jsonb,
    '{"source":"x","category_id":"27300000-0000-0000-0000-000000000001"}'::jsonb, 50, 0)->'items',
  jsonb_build_array(summary), 'the new source filter composes with the existing category filter'
) from service_expected where source = 'x';
select is(
  public.library_search_items('{"query":"","terms":[],"groups":[]}'::jsonb,
    '{"source":"x","unclassified":true}'::jsonb, 50, 0),
  '{"items":[],"has_more":false}'::jsonb, 'the new source filter retains the unclassified predicate'
);
select throws_ok(
  format('select public.library_search_items(%L::jsonb, jsonb_build_object(''source'', %L::jsonb), 50, 0)',
    '{"query":"","terms":[],"groups":[]}', invalid.value),
  'P0001', 'INVALID_FILTER', 'search rejects invalid source ' || invalid.value
)
from (values ('"twitter"'), ('"X"'), ('"all"'), ('"facebook.com"'), ('""'), ('null'), ('8'), ('{}'), ('[]')) as invalid(value);
select throws_ok(
  $$select public.library_create_item('27000000-0000-0000-0000-000000000001',
    '27400000-0000-0000-0003-000000000005', '{}'::jsonb, '{}'::jsonb)$$,
  '42501', null, 'authenticated clients cannot invoke service-only creation for a new source'
);
select set_config('request.jwt.claim.sub', '27000000-0000-0000-0000-000000000002', true);
select is(
  jsonb_array_length(public.library_search_items('{"query":"","terms":[],"groups":[]}'::jsonb, '{}'::jsonb, 50, 0)->'items'),
  1, 'the other owner sees only its separately saved X item in all-source search'
);
select is(
  public.library_search_items('{"query":"","terms":[],"groups":[]}'::jsonb, '{"source":"facebook"}'::jsonb, 50, 0),
  '{"items":[],"has_more":false}'::jsonb, 'source filtering never exposes another owner''s Facebook item'
);
select isnt(
  public.library_search_items('{"query":"","terms":[],"groups":[]}'::jsonb, '{"source":"x"}'::jsonb, 50, 0) #>> '{items,0,id}',
  response #>> '{item,id}', 'the other owner''s X result is not the first owner''s deduplicated item'
) from service_results where ordinal = 4;
select is((select count(*) from public.items where owner_id = '27000000-0000-0000-0000-000000000001'),
  0::bigint, 'direct item RLS still hides every first-owner source');
select set_config('request.jwt.claim.sub', '27000000-0000-0000-0000-000000000003', true);
select throws_ok(
  $$select public.library_search_items('{"query":"","terms":[],"groups":[]}'::jsonb, '{"source":"x"}'::jsonb, 50, 0)$$,
  'P0001', 'BETA_ACCESS_REQUIRED', 'disabled membership cannot search new sources'
);
select set_config('request.jwt.claim.sub', '27000000-0000-0000-0000-000000000004', true);
select throws_ok(
  $$select public.library_search_items('{"query":"","terms":[],"groups":[]}'::jsonb, '{"source":"facebook"}'::jsonb, 50, 0)$$,
  'P0001', 'ACCOUNT_DELETING', 'deleting profiles cannot search new sources'
);
select set_config('request.jwt.claim.sub', '', true);
select throws_ok(
  $$select public.library_search_items('{"query":"","terms":[],"groups":[]}'::jsonb, '{"source":"bluesky"}'::jsonb, 50, 0)$$,
  'P0001', 'UNAUTHENTICATED', 'search with an absent JWT owner remains unauthenticated'
);
reset role;
set local role anon;
select throws_ok(
  $$select public.library_search_items('{"query":"","terms":[],"groups":[]}'::jsonb, '{"source":"mastodon"}'::jsonb, 50, 0)$$,
  '42501', null, 'anonymous clients cannot execute source search'
);
select throws_ok(
  $$select public.library_create_item('27000000-0000-0000-0000-000000000001',
    '27400000-0000-0000-0003-000000000006', '{}'::jsonb, '{}'::jsonb)$$,
  '42501', null, 'anonymous clients cannot execute source creation'
);
reset role;

-- The explicit public replacement must not bypass migration 008's readiness fence.
insert into private.category_normalization_authorizations (backend_pid, transaction_id, owner_id)
values (pg_backend_pid(), txid_current(), '27000000-0000-0000-0000-000000000001');
update public.categories set normalization_version = 0
where id = '27300000-0000-0000-0000-000000000001';
delete from private.category_normalization_authorizations
where backend_pid = pg_backend_pid() and transaction_id = txid_current();
set local role authenticated;
select set_config('request.jwt.claim.sub', '27000000-0000-0000-0000-000000000001', true);
select throws_ok(
  $$select public.library_search_items('{"query":"","terms":[],"groups":[]}'::jsonb, '{"source":"x"}'::jsonb, 50, 0)$$,
  'P0001', 'CATEGORY_NORMALIZATION_REQUIRED', 'new source search retains the category normalization fence'
);
reset role;

-- Replay the migration-style transformation on a transaction-local copy. Legacy
-- malformed credentials/deleted payloads cannot be inserted into today's items
-- table; copying defaults but not checks lets those regression cases be retained.
-- The parent also applies the actual 027 file against pre-migration DB fixtures.
create temporary table service_legacy (like public.items including defaults);
alter table service_legacy add column label text not null, add column expected_source text;
insert into service_legacy (
  id, owner_id, original_url, normalized_url, url_hash, source, display_fallback,
  user_title, fetched_title, shared_text, description, body_text, note, extraction_meta,
  text_revision, version, metadata_state, created_at, updated_at, deleted_at, label, expected_source
)
select ('27500000-0000-0000-0000-' || lpad((row_number() over ())::text, 12, '0'))::uuid,
  '27000000-0000-0000-0000-000000000001', sample.url, sample.url,
  encode(extensions.digest(convert_to(coalesce(sample.url, ''), 'UTF8'), 'sha256'), 'hex'),
  sample.source, 'Authored fallback', '  Authored title  ', 'Fetched title', 'Shared text',
  'Description', 'Body text', '  Authored note  ', '{}'::jsonb, 3, 7, 'ready',
  '2026-01-01T00:00:00Z', '2026-02-01T00:00:00Z',
  case when sample.deleted then '2026-03-01T00:00:00Z'::timestamptz end,
  sample.label, sample.expected_source
from (values
  ('https://x.com/legacy?ref=first', 'other', 'x', false, 'X root'),
  ('https://x.com/legacy?ref=first', 'other', 'x', false, 'preexisting duplicate X URL is retained'),
  ('https://twitter.com/legacy', 'other', 'x', false, 'Twitter alias'),
  ('https://deep.mobile.x.com/legacy', 'other', 'x', false, 'proper nested X subdomain'),
  ('https://mobile.twitter.com/legacy', 'other', 'x', false, 'proper Twitter subdomain'),
  ('HTTPS://WWW.X.COM:8443?next=https://evil.test/', 'other', 'x', false, 'mixed-case authority and a nondefault valid port'),
  ('http://x.com:80/legacy', 'other', 'x', false, 'HTTP authority'),
  ('https://x.com:000443/legacy', 'other', 'x', false, 'numeric port with leading zeroes'),
  ('https://x.com:65535/legacy', 'other', 'x', false, 'maximum valid port'),
  ('https://facebook.com/legacy', 'other', 'facebook', false, 'Facebook root'),
  ('https://m.facebook.com/legacy', 'other', 'facebook', false, 'Facebook subdomain'),
  ('https://fb.com/legacy', 'other', 'facebook', false, 'Facebook short domain'),
  ('https://go.fb.com/legacy', 'other', 'facebook', false, 'Facebook short-domain subdomain'),
  ('https://fb.watch/legacy', 'other', 'facebook', false, 'Facebook watch domain'),
  ('https://go.fb.watch/legacy', 'other', 'facebook', false, 'Facebook watch subdomain'),
  ('https://bsky.app/profile/user/post/1', 'other', 'bluesky', false, 'Bluesky root'),
  ('https://deep.mobile.bsky.app/legacy', 'other', 'bluesky', false, 'Bluesky proper subdomain'),
  ('https://mastodon.social/@user/1', 'other', 'mastodon', false, 'known Mastodon root'),
  ('https://deep.mobile.mastodon.social/legacy', 'other', 'mastodon', false, 'known Mastodon proper subdomain'),
  ('https://mastodon.online/@user/1', 'other', 'other', false, 'arbitrary Mastodon instance is not inferred'),
  ('https://social.example.test/@user/1', 'other', 'other', false, 'Mastodon-shaped path is not a known authority'),
  ('https://example.test/?url=https://x.com/legacy', 'other', 'other', false, 'a known host in the query is not the authority'),
  ('https://instagram.com/p/legacy', 'other', 'other', false, 'backfill does not reclassify Instagram'),
  ('https://threads.com/@user/post/legacy', 'other', 'other', false, 'backfill does not reclassify Threads'),
  ('https://blog.naver.com/legacy', 'other', 'other', false, 'backfill does not reclassify Naver Blog'),
  ('https://evilx.com/legacy', 'other', 'other', false, 'X prefix lookalike'),
  ('https://x.com.evil.test/legacy', 'other', 'other', false, 'X suffix lookalike'),
  ('https://facebook.com.evil.test/legacy', 'other', 'other', false, 'Facebook suffix lookalike'),
  ('https://notfb.com/legacy', 'other', 'other', false, 'Facebook short-domain prefix lookalike'),
  ('https://fb.watch.evil.test/legacy', 'other', 'other', false, 'watch suffix lookalike'),
  ('https://bsky.app.evil.test/legacy', 'other', 'other', false, 'Bluesky suffix lookalike'),
  ('https://mastodon.social.evil.test/legacy', 'other', 'other', false, 'Mastodon suffix lookalike'),
  ('https://mastodonXsocial/legacy', 'other', 'other', false, 'Mastodon domain separator is literal'),
  ('https://mаstodon.social/legacy', 'other', 'other', false, 'Unicode authority lookalike'),
  ('https://user:password@facebook.com/legacy', 'other', 'other', false, 'credentials before known host'),
  ('https://@x.com/legacy', 'other', 'other', false, 'empty credentials'),
  ('https://x.com@evil.test/legacy', 'other', 'other', false, 'known host as userinfo'),
  ('https://evil.test@x.com/legacy', 'other', 'other', false, 'known host after userinfo'),
  ('https://%78.com/legacy', 'other', 'other', false, 'encoded hostname is not inferred'),
  ('https://x.com./legacy', 'other', 'other', false, 'trailing-dot hostname'),
  ('https://-bad.x.com/legacy', 'other', 'other', false, 'leading-hyphen DNS label'),
  ('https://bad-.x.com/legacy', 'other', 'other', false, 'trailing-hyphen DNS label'),
  ('https://bad_label.x.com/legacy', 'other', 'other', false, 'underscore DNS label'),
  ('https://bad..x.com/legacy', 'other', 'other', false, 'empty DNS label'),
  ('https://' || repeat('a', 64) || '.x.com/legacy', 'other', 'other', false, 'oversized DNS label'),
  ('https://' || repeat(repeat('a', 62) || '.', 4) || 'x.com/legacy', 'other', 'other', false, 'oversized complete DNS hostname'),
  ('https://x.com:/legacy', 'other', 'other', false, 'empty explicit port'),
  ('https://x.com:0/legacy', 'other', 'other', false, 'zero port'),
  ('https://x.com:65536/legacy', 'other', 'other', false, 'out-of-range port'),
  ('https://x.com:999999999999999999999999999/legacy', 'other', 'other', false, 'port cannot overflow an integer'),
  ('https://x.com:-1/legacy', 'other', 'other', false, 'negative port'),
  ('https://x.com:abc/legacy', 'other', 'other', false, 'nonnumeric port'),
  ('https://x.com:443:443/legacy', 'other', 'other', false, 'multiple ports'),
  ('https://[::1]/x.com/legacy', 'other', 'other', false, 'IPv6 authority is not a known domain'),
  ('https:////x.com/legacy', 'other', 'other', false, 'invalid authority separator'),
  ('ftp://x.com/legacy', 'other', 'other', false, 'non-HTTP scheme'),
  ('//x.com/legacy', 'other', 'other', false, 'missing scheme'),
  (' https://x.com/legacy', 'other', 'other', false, 'leading whitespace'),
  ('https://x.com/legacy' || chr(10), 'other', 'other', false, 'control character'),
  ('https://x.com/legacy' || chr(92), 'other', 'other', false, 'backslash URL repair'),
  ('https://x.com/legacy?bad=%', 'other', 'other', false, 'incomplete percent escape'),
  ('https://x.com/legacy?bad=%1z', 'other', 'other', false, 'malformed percent escape'),
  ('https://x.com/legacy?good=%2f', 'other', 'x', false, 'valid percent escape outside authority'),
  ('https://x.com/legacy', 'instagram', 'instagram', false, 'existing non-other source is never rewritten'),
  ('https://example.test/legacy', 'x', 'x', false, 'existing new source is never rewritten'),
  ('https://x.com/deleted', 'other', 'other', true, 'deleted legacy payload is never reclassified'),
  (null::text, null::text, null::text, true, 'current scrubbed deletion remains unchanged')
) as sample(url, source, expected_source, deleted, label);
update service_legacy
set url_hash = null, display_fallback = null, user_title = null, fetched_title = null,
    shared_text = null, description = null, body_text = null, note = null, metadata_state = null
where original_url is null and deleted_at is not null;
create temporary table service_legacy_before as select id, to_jsonb(item) as stored from service_legacy as item;

create function pg_temp.backfill_service_sources()
returns bigint
language plpgsql
as $$
declare
  changed_count bigint;
begin
  with parsed as materialized (
    select item.id,
      pg_catalog.regexp_match(
        item.original_url collate "C",
        '^([Hh][Tt][Tt][Pp][Ss]?)://([^/?#]+)(/[^?#]*)?([?][^#]*)?(#.*)?$'
      ) as parts
    from pg_temp.service_legacy as item
    where item.deleted_at is null
      and item.source = 'other'
      and pg_catalog.char_length(item.original_url) between 1 and 4096
      and item.original_url !~ '[[:space:][:cntrl:]]'
      and pg_catalog.strpos(item.original_url, pg_catalog.chr(92)) = 0
      and item.original_url collate "C" !~ '%([^0-9A-Fa-f]|[0-9A-Fa-f]([^0-9A-Fa-f]|$)|$)'
  ), authorities as materialized (
    select parsed.id,
      pg_catalog.regexp_match(
        pg_catalog.lower(parsed.parts[2] collate "C"),
        '^([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:[.][a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*)(?::([0-9]+))?$'
      ) as parts
    from parsed
  ), classified as materialized (
    select authorities.id,
      case
        when authorities.parts[1] ~ '(^|[.])(x[.]com|twitter[.]com)$' then 'x'
        when authorities.parts[1] ~ '(^|[.])(facebook[.]com|fb[.]com|fb[.]watch)$' then 'facebook'
        when authorities.parts[1] ~ '(^|[.])bsky[.]app$' then 'bluesky'
        when authorities.parts[1] ~ '(^|[.])mastodon[.]social$' then 'mastodon'
      end as source
    from authorities
    where pg_catalog.char_length(authorities.parts[1]) <= 253
      and case
        when authorities.parts[2] is null then true
        when pg_catalog.char_length(pg_catalog.ltrim(authorities.parts[2], '0')) between 1 and 5
          then pg_catalog.ltrim(authorities.parts[2], '0')::integer between 1 and 65535
        else false
      end
  )
  update pg_temp.service_legacy as item
  set source = classified.source
  from classified
  where item.id = classified.id
    and item.deleted_at is null
    and item.source = 'other'
    and classified.source is not null;
  get diagnostics changed_count = row_count;
  return changed_count;
end;
$$;
select is(
  pg_temp.backfill_service_sources(),
  (select count(*) from service_legacy_before where stored->>'source' = 'other' and stored->>'expected_source' <> 'other'),
  'migration-style backfill touches exactly the intended active legacy other rows'
);
select is(item.source, item.expected_source, 'backfill authority regression: ' || item.label)
from service_legacy as item order by item.id;
select is(
  to_jsonb(item) - 'source', before.stored - 'source',
  'backfill preserves all non-source columns, including authored fields/timestamps/revisions: ' || item.label
)
from service_legacy as item join service_legacy_before as before using (id) order by item.id;
select is(
  (select jsonb_agg(id order by id) from service_legacy),
  (select jsonb_agg(id order by id) from service_legacy_before),
  'backfill retains every row and duplicate instead of merging or deleting data'
);
create temporary table service_legacy_after as select id, to_jsonb(item) as stored from service_legacy as item;
select is(pg_temp.backfill_service_sources(), 0::bigint, 'reapplying the backfill updates zero already-classified rows');
select is(
  (select jsonb_agg(to_jsonb(item) order by item.id) from service_legacy as item),
  (select jsonb_agg(stored order by id) from service_legacy_after),
  'a second backfill leaves all classified, unknown, credential-bearing and deleted rows byte-for-byte unchanged'
);

select * from finish();
rollback;
