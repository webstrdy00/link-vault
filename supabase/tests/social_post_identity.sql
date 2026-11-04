begin;

create extension if not exists pgtap with schema extensions;
set local search_path = pg_catalog, extensions, public;
select no_plan();

select ok(
  (
    select installed.provolatile = 'i'
      and installed.proisstrict
      and installed.proparallel = 's'
      and installed.proconfig @> array['search_path=""']::text[]
    from pg_catalog.pg_proc as installed
    where installed.oid = 'private.social_post_identity(text)'::regprocedure
  )
  and not has_function_privilege('anon', 'private.social_post_identity(text)', 'EXECUTE')
  and not has_function_privilege('authenticated', 'private.social_post_identity(text)', 'EXECUTE')
  and has_function_privilege('service_role', 'private.social_post_identity(text)', 'EXECUTE'),
  'post identity is immutable, strict, parallel safe and private with service-only index maintenance access'
);

select ok(
  has_function_privilege('service_role', 'public.library_create_item(uuid,uuid,jsonb,jsonb)', 'EXECUTE')
  and not has_function_privilege('anon', 'public.library_create_item(uuid,uuid,jsonb,jsonb)', 'EXECUTE')
  and not has_function_privilege('authenticated', 'public.library_create_item(uuid,uuid,jsonb,jsonb)', 'EXECUTE')
  and (
    select installed.prosecdef
      and installed.proconfig @> array['search_path=""']::text[]
    from pg_catalog.pg_proc as installed
    where installed.oid = 'public.library_create_item(uuid,uuid,jsonb,jsonb)'::regprocedure
  ),
  'the replacement creation RPC remains service-only and SECURITY DEFINER'
);

select ok(
  (
    select not indexed.indisunique
      and pg_catalog.pg_get_expr(indexed.indpred, indexed.indrelid) = '(deleted_at IS NULL)'
      and pg_catalog.pg_get_indexdef(indexed.indexrelid) like '%(owner_id, %digest(private.social_post_identity(original_url), %sha256%'
    from pg_catalog.pg_index as indexed
    where indexed.indexrelid = 'public.items_owner_social_post_active_idx'::regclass
  ),
  'the owner-scoped fixed-size original-URL identity index covers active rows without requiring uniqueness'
);

select is(private.social_post_identity(sample.url), sample.identity, sample.label)
from (values
  ('https://instagram.com/p/AbC_123-/', 'instagram:AbC_123-', 'Instagram post shortcode'),
  ('https://www.instagram.com/reel/AbC_123-?igsh=other', 'instagram:AbC_123-', 'Instagram reel share parameters'),
  ('http://m.instagram.com:80/tv/AbC_123-/#part', 'instagram:AbC_123-', 'Instagram TV and mobile standard HTTP port'),
  ('HTTPS://WWW.INSTAGRAM.COM:443/user.name/p/AbC_123-/?utm_source=a&x=%2f#part', 'instagram:AbC_123-', 'Instagram username path, uppercase authority and standard HTTPS port'),
  ('https://instagram.com/p/abc_123-/', 'instagram:abc_123-', 'Instagram shortcode case is retained'),
  ('https://threads.net/@user.name/post/ThReAd_1', 'threads:ThReAd_1', 'Threads .net post'),
  ('https://www.threads.com/@different_user/post/ThReAd_1/?xmt=other#part', 'threads:ThReAd_1', 'Threads .com alias and username do not change a post ID'),
  ('http://www.threads.net:80/@user/post/ThReAd_1', 'threads:ThReAd_1', 'Threads standard HTTP port'),
  ('https://threads.com:443/@user/post/thread_1', 'threads:thread_1', 'Threads ID case is retained'),
  ('https://x.com/user/status/12345678901234567890', 'x:12345678901234567890', 'X numeric ID does not depend on bigint range'),
  ('https://www.twitter.com/Other_User/status/12345678901234567890/?s=20', 'x:12345678901234567890', 'Twitter host and username alias'),
  ('https://mobile.twitter.com/i/web/status/12345678901234567890#part', 'x:12345678901234567890', 'Twitter mobile web-status route'),
  ('http://www.x.com:80/i/web/status/12345678901234567890', 'x:12345678901234567890', 'X web-status route and standard HTTP port'),
  ('https://x.com/user/status/00012345678901234567890', 'x:12345678901234567890', 'X numeric identity ignores leading zeroes without integer casts'),
  ('https://x.com/user/status/000', 'x:0', 'an all-zero numeric ID has a stable representation'),
  ('https://x.com:443/user/status/12345678901234567891', 'x:12345678901234567891', 'different numeric post ID')
) as sample(url, identity, label);

select is(private.social_post_identity(sample.url), null::text, sample.label)
from (values
  (null::text, 'null URL'),
  ('', 'empty URL'),
  (' https://instagram.com/p/AbC_123-/', 'leading whitespace'),
  ('https://instagram.com/p/AbC_123-/ ', 'trailing whitespace'),
  ('https://instagram.com/p/AbC_123-/?bad=' || chr(10), 'control characters'),
  ('https://instagram.com/p/AbC_123-/?bad=' || chr(92), 'backslashes even outside authority'),
  ('https://user:password@instagram.com/p/AbC_123-/', 'userinfo'),
  ('https://@instagram.com/p/AbC_123-/', 'empty userinfo'),
  ('https://instagram.com@evil.invalid/p/AbC_123-/', 'host-looking userinfo'),
  ('https://instagram.com.evil.invalid/p/AbC_123-/', 'host suffix impersonation'),
  ('https://evilinstagram.com/p/AbC_123-/', 'host prefix impersonation'),
  ('https://private.instagram.com/p/AbC_123-/', 'unrecognized subdomain'),
  ('https://instagram.com./p/AbC_123-/', 'trailing-dot host is not an alias'),
  ('https://instagrаm.com/p/AbC_123-/', 'Unicode host lookalike'),
  ('https://%69nstagram.com/p/AbC_123-/', 'encoded host'),
  ('https://instagram.com:80/p/AbC_123-/', 'HTTP port on HTTPS'),
  ('http://instagram.com:443/p/AbC_123-/', 'HTTPS port on HTTP'),
  ('https://instagram.com:444/p/AbC_123-/', 'nonstandard port'),
  ('https://instagram.com:0443/p/AbC_123-/', 'noncanonical port spelling'),
  ('https://instagram.com:/p/AbC_123-/', 'empty explicit port'),
  ('https://instagram.com:443:443/p/AbC_123-/', 'multiple ports'),
  ('https:////instagram.com/p/AbC_123-/', 'malformed scheme separator'),
  ('ftp://instagram.com/p/AbC_123-/', 'non-HTTP scheme'),
  ('https://127.0.0.1/p/AbC_123-/', 'local IP is not a social host'),
  ('https://instagram.com/p/', 'missing shortcode'),
  ('https://instagram.com/P/AbC_123-/', 'route names are case-sensitive'),
  ('https://instagram.com//p/AbC_123-/', 'extra path separator'),
  ('https://instagram.com/a/../p/AbC_123-/', 'dot segments'),
  ('https://instagram.com/p/AbC_123-/extra', 'extra Instagram path'),
  ('https://instagram.com/user/reel/AbC_123-/', 'unsupported username reel path'),
  ('https://instagram.com/p/AbC.123/', 'invalid shortcode character'),
  ('https://instagram.com/p/AbC%2f123/', 'encoded path separator'),
  ('https://instagram.com/p/AbC_123-/?bad=%zz', 'malformed percent escape'),
  ('https://instagram.com/p/AbC_123-/?bad=%2', 'incomplete percent escape'),
  ('https://threads.com.evil.invalid/@user/post/ThReAd_1', 'Threads host impersonation'),
  ('https://threads.com/user/post/ThReAd_1', 'Threads missing handle marker'),
  ('https://threads.com/@user/Post/ThReAd_1', 'Threads route case mismatch'),
  ('https://threads.com/@user/post/ThReAd_1/extra', 'extra Threads path'),
  ('https://threads.net/@user/post/', 'missing Threads ID'),
  ('https://x.com.evil.invalid/user/status/123', 'X host impersonation'),
  ('https://x.com/user/status/not-digits', 'nonnumeric X ID'),
  ('https://twitter.com/user/status/123/photo/1', 'extra X photo path'),
  ('https://x.com/i/web/status/123/extra', 'extra X web-status path'),
  ('https://x.com/user/Status/123', 'X route case mismatch'),
  ('https://x.com/user/status/123.0', 'decimal X ID is not digits'),
  ('https://x.com/user/status/-123', 'signed X ID is not digits'),
  ('https://example.com/p/AbC_123-/?utm_source=a', 'arbitrary sites have no post identity'),
  ('https://www.facebook.com/user/posts/123', 'unsupported social sites have no post identity')
) as sample(url, label);

insert into auth.users (
  instance_id, id, aud, role, email, encrypted_password, email_confirmed_at,
  raw_app_meta_data, raw_user_meta_data, created_at, updated_at,
  confirmation_token, email_change, email_change_token_new, recovery_token
)
select '00000000-0000-0000-0000-000000000000'::uuid, owner.id,
  'authenticated', 'authenticated', owner.email, '', now(),
  '{"provider":"google","providers":["google"]}'::jsonb, '{}'::jsonb,
  now(), now(), '', '', '', ''
from (values
  ('1a000000-0000-0000-0000-000000000001'::uuid, 'social-a@example.test'),
  ('1a000000-0000-0000-0000-000000000002'::uuid, 'social-b@example.test')
) as owner(id, email);

insert into public.beta_members (owner_id, enabled, approved_at)
values
  ('1a000000-0000-0000-0000-000000000001', true, now()),
  ('1a000000-0000-0000-0000-000000000002', true, now());
insert into public.profiles (id, state)
values
  ('1a000000-0000-0000-0000-000000000001', 'active'),
  ('1a000000-0000-0000-0000-000000000002', 'active');
insert into public.library_usage (owner_id)
values
  ('1a000000-0000-0000-0000-000000000001'),
  ('1a000000-0000-0000-0000-000000000002');
insert into public.categories (id, owner_id, name, normalized_name, kind)
values
  ('3a000000-0000-0000-0000-000000000001', '1a000000-0000-0000-0000-000000000001', 'Kept category', 'kept category', 'custom'),
  ('3a000000-0000-0000-0000-000000000002', '1a000000-0000-0000-0000-000000000001', 'Incoming category', 'incoming category', 'custom'),
  ('3a000000-0000-0000-0000-000000000003', '1a000000-0000-0000-0000-000000000002', 'Other category', 'other category', 'custom');

set local role service_role;
select lives_ok(
  $$ insert into public.items (
    id, owner_id, original_url, normalized_url, url_hash, source,
    display_fallback, metadata_state
  ) values (
    '5a000000-0000-0000-0000-000000000099',
    '1a000000-0000-0000-0000-000000000002',
    'https://instagram.com/p/MaintenanceOnly/?igsh=fixture',
    'https://instagram.com/p/MaintenanceOnly/?igsh=fixture',
    encode(extensions.digest(convert_to('https://instagram.com/p/MaintenanceOnly/?igsh=fixture', 'UTF8'), 'sha256'), 'hex'),
    'instagram', 'maintenance fixture', 'unsupported'
  ) $$,
  'service-role direct maintenance inserts can evaluate the private identity expression index'
);
reset role;
delete from public.items where id = '5a000000-0000-0000-0000-000000000099';

create function pg_temp.social_prepared(p_url text)
returns jsonb
language sql
immutable
as $$
  select jsonb_build_object(
    'normalized_url', p_url,
    'url_hash', encode(extensions.digest(convert_to(p_url, 'UTF8'), 'sha256'), 'hex'),
    'source', 'other',
    'display_fallback', 'social fixture',
    'normalized_fields', jsonb_build_object(
      'user_title', '', 'fetched_title', '', 'note', '', 'ocr', '',
      'shared', '', 'description', '', 'body', '', 'categories', '',
      'url', lower(p_url)
    ),
    'alias_concepts', jsonb_build_object(
      'user_title', '[]'::jsonb, 'fetched_title', '[]'::jsonb,
      'note', '[]'::jsonb, 'ocr', '[]'::jsonb, 'shared', '[]'::jsonb,
      'description', '[]'::jsonb, 'body', '[]'::jsonb
    ),
    'cue_state', 'limited', 'cue_flags', '["short_text"]'::jsonb,
    'metadata_allowed', false
  );
$$;
grant execute on function pg_temp.social_prepared(text) to service_role;

-- Seed preexisting items directly, including duplicate identities with different
-- hashes. Older timestamps and UUID tie-breaking must beat an exact newer hash.
create temporary table social_legacy (
  id uuid primary key,
  raw_url text not null,
  normalized_url text not null,
  created_at timestamptz not null
);
insert into social_legacy values
  ('5a000000-0000-0000-0000-000000000001',
    'HTTPS://WWW.INSTAGRAM.COM:443/visitjeju.kr/p/AbC_123-/?igsh=legacy&utm_source=old#shared',
    'https://www.instagram.com/visitjeju.kr/p/AbC_123-/?igsh=legacy&utm_source=old#shared', '2026-01-01 00:00:00+00'),
  ('5a000000-0000-0000-0000-000000000002',
    'https://instagram.com/reel/AbC_123-/?igsh=newer',
    'https://instagram.com/reel/AbC_123-/?igsh=newer', '2026-02-01 00:00:00+00'),
  ('5a000000-0000-0000-0000-000000000003',
    'https://instagram.com/tv/AbC_123-/?igsh=tie',
    'https://instagram.com/tv/AbC_123-/?igsh=tie', '2026-01-01 00:00:00+00'),
  ('5a000000-0000-0000-0000-000000000004',
    'https://www.threads.net/@legacy.user/post/ThReAd_1?xmt=old',
    'https://www.threads.net/@legacy.user/post/ThReAd_1?xmt=old', '2026-01-01 00:00:00+00'),
  ('5a000000-0000-0000-0000-000000000005',
    'https://twitter.com/old_user/status/12345678901234567890?s=20',
    'https://twitter.com/old_user/status/12345678901234567890?s=20', '2026-01-01 00:00:00+00');

insert into public.items (
  id, owner_id, original_url, normalized_url, url_hash, source, display_fallback,
  user_title, note, shared_text, text_revision, version, metadata_state, created_at
)
select legacy.id, '1a000000-0000-0000-0000-000000000001',
  legacy.raw_url, legacy.normalized_url,
  encode(extensions.digest(convert_to(legacy.normalized_url, 'UTF8'), 'sha256'), 'hex'),
  'other', 'legacy fixture', 'Edited legacy title', 'Edited legacy note',
  'Original authored shared text', 3, 7, 'unsupported', legacy.created_at
from social_legacy as legacy;

insert into public.item_search (
  owner_id, item_id, text_revision, search_version, normalized_fields,
  alias_concepts, cue_version, cue_state, cue_flags
)
select '1a000000-0000-0000-0000-000000000001', legacy.id, 3, 'search-v2.0.0',
  pg_temp.social_prepared(legacy.normalized_url) -> 'normalized_fields',
  pg_temp.social_prepared(legacy.normalized_url) -> 'alias_concepts',
  'cues-v1.0.0', 'limited', '["short_text"]'::jsonb
from social_legacy as legacy;
insert into public.item_classification (
  owner_id, item_id, rules_version, target_revision, state, reasons
)
select '1a000000-0000-0000-0000-000000000001', legacy.id,
  'rules-v2.0.0', 3, 'manual', '[]'::jsonb
from social_legacy as legacy;
insert into public.item_category_controls (owner_id, item_id, manual_override)
select '1a000000-0000-0000-0000-000000000001', legacy.id, true
from social_legacy as legacy;
insert into public.item_categories (owner_id, item_id, category_id, origin)
select '1a000000-0000-0000-0000-000000000001', legacy.id,
  '3a000000-0000-0000-0000-000000000001', 'manual'
from social_legacy as legacy;
update public.library_usage set active_item_count = 5
where owner_id = '1a000000-0000-0000-0000-000000000001';

create temporary table social_snapshot as
select item.id, to_jsonb(item) as stored_item,
  to_jsonb(search_record) as stored_search,
  to_jsonb(classification) as stored_classification,
  to_jsonb(controls) as stored_controls,
  to_jsonb(selected) as stored_category
from public.items as item
join social_legacy as legacy on legacy.id = item.id
join public.item_search as search_record on search_record.item_id = item.id
join public.item_classification as classification on classification.item_id = item.id
join public.item_category_controls as controls on controls.item_id = item.id
join public.item_categories as selected on selected.item_id = item.id;

create temporary table social_variants (
  ordinal integer primary key,
  url text not null,
  expected_item_id uuid not null,
  request_id uuid,
  body jsonb
);
insert into social_variants (ordinal, url, expected_item_id) values
  (1, 'https://instagram.com/p/AbC_123-/?igsh=fresh', '5a000000-0000-0000-0000-000000000001'),
  (2, 'https://instagram.com/reel/AbC_123-/?igsh=newer', '5a000000-0000-0000-0000-000000000001'),
  (3, 'https://instagram.com/tv/AbC_123-/?igsh=tie', '5a000000-0000-0000-0000-000000000001'),
  (4, 'https://www.instagram.com/another.user/p/AbC_123-#new', '5a000000-0000-0000-0000-000000000001'),
  (5, 'http://instagram.com:80/p/AbC_123-', '5a000000-0000-0000-0000-000000000001'),
  (6, 'https://m.instagram.com/reel/AbC_123-/', '5a000000-0000-0000-0000-000000000001'),
  (7, 'https://threads.com/@new_user/post/ThReAd_1?xmt=changed', '5a000000-0000-0000-0000-000000000004'),
  (8, 'https://www.threads.net/@other/post/ThReAd_1/#new', '5a000000-0000-0000-0000-000000000004'),
  (9, 'http://threads.com:80/@user/post/ThReAd_1', '5a000000-0000-0000-0000-000000000004'),
  (10, 'https://x.com/new_user/status/12345678901234567890?s=other', '5a000000-0000-0000-0000-000000000005'),
  (11, 'https://www.twitter.com/i/web/status/12345678901234567890#new', '5a000000-0000-0000-0000-000000000005'),
  (12, 'http://x.com:80/i/web/status/12345678901234567890/', '5a000000-0000-0000-0000-000000000005'),
  (13, 'https://mobile.twitter.com/third/status/12345678901234567890', '5a000000-0000-0000-0000-000000000005'),
  (14, 'https://x.com/third/status/00012345678901234567890', '5a000000-0000-0000-0000-000000000005');
update social_variants
set request_id = ('4a000000-0000-0000-0000-' || lpad(ordinal::text, 12, '0'))::uuid,
    body = jsonb_build_object(
      'url', url, 'title', 'Do not overwrite title', 'note', 'Do not overwrite note',
      'shared_text', 'Do not overwrite shared text',
      'category_ids', '["3a000000-0000-0000-0000-000000000002"]'::jsonb
    );

create temporary table social_results (input_name text primary key, response jsonb not null);
grant select on table social_variants to service_role;
grant select, insert on table social_results to service_role;

set local role service_role;
insert into social_results
select 'legacy-' || variant.ordinal,
  public.library_create_item(
    '1a000000-0000-0000-0000-000000000001', variant.request_id,
    variant.body, pg_temp.social_prepared(variant.url)
  )
from social_variants as variant
order by variant.ordinal;
reset role;

select ok(
  result.response @> jsonb_build_object('http_status', 200, 'duplicate', true)
    and result.response #>> '{item,id}' = variant.expected_item_id::text,
  'legacy variant ' || variant.ordinal || ' returns the deterministic oldest existing post'
)
from social_results as result
join social_variants as variant on result.input_name = 'legacy-' || variant.ordinal
order by variant.ordinal;

select ok(
  not exists (
    select 1 from social_snapshot as saved
    left join public.items as item on item.id = saved.id
    left join public.item_search as search_record on search_record.item_id = saved.id
    left join public.item_classification as classification on classification.item_id = saved.id
    left join public.item_category_controls as controls on controls.item_id = saved.id
    left join public.item_categories as selected on selected.item_id = saved.id
    where to_jsonb(item) is distinct from saved.stored_item
      or to_jsonb(search_record) is distinct from saved.stored_search
      or to_jsonb(classification) is distinct from saved.stored_classification
      or to_jsonb(controls) is distinct from saved.stored_controls
      or to_jsonb(selected) is distinct from saved.stored_category
  )
  and (select count(*) from public.items where owner_id = '1a000000-0000-0000-0000-000000000001') = 5
  and (select count(*) from public.item_categories where owner_id = '1a000000-0000-0000-0000-000000000001') = 5,
  'all legacy duplicate rows, original URLs, authored edits, revisions and categories survive unchanged'
);
select is(
  (select active_item_count from public.library_usage where owner_id = '1a000000-0000-0000-0000-000000000001'),
  5, 'legacy identity matches do not consume item quota'
);
select is(
  (select count(*) from public.api_rate_buckets where owner_id = '1a000000-0000-0000-0000-000000000001'),
  0::bigint, 'legacy identity matches do not consume the creation rate budget'
);

update public.items
set user_title = 'Newer edited title', note = 'Newer edited note', version = version + 1
where id = '5a000000-0000-0000-0000-000000000001';
set local role service_role;
insert into social_results
select 'duplicate-replay', public.library_create_item(
  '1a000000-0000-0000-0000-000000000001', variant.request_id,
  variant.body, pg_temp.social_prepared(variant.url)
)
from social_variants as variant where variant.ordinal = 1;
select throws_ok(
  $$ select public.library_create_item(
    '1a000000-0000-0000-0000-000000000001', variant.request_id,
    variant.body || '{"url":"https://instagram.com/p/AbC_123-/?igsh=changed-again"}'::jsonb,
    pg_temp.social_prepared(variant.url)
  ) from social_variants as variant where variant.ordinal = 1 $$,
  'P0001', 'IDEMPOTENCY_MISMATCH',
  'the same post identity does not relax raw-body idempotency matching'
);
reset role;
select ok(
  (select response @> '{"http_status":200,"duplicate":true,"item":{"id":"5a000000-0000-0000-0000-000000000001","user_title":"Newer edited title","note":"Newer edited note","version":8}}'::jsonb
   from social_results where input_name = 'duplicate-replay'),
  'duplicate receipt replay returns the same item and its current edits without overwriting them'
);

-- Ten or fewer actual creates per owner; variants and replays must be free.
create temporary table social_new_cases (
  ordinal integer primary key,
  label text not null,
  url text not null
);
insert into social_new_cases values
  (1, 'case-sensitive Instagram shortcode', 'https://instagram.com/p/abc_123-/?igsh=new'),
  (2, 'different Instagram shortcode', 'https://instagram.com/p/AbC_124-/'),
  (3, 'case-sensitive Threads ID', 'https://threads.com/@user/post/thread_1'),
  (4, 'different X numeric ID', 'https://x.com/user/status/12345678901234567891'),
  (5, 'unknown-site first query', 'https://example.com/article?utm_source=one&Keep=A'),
  (6, 'unknown-site second query', 'https://example.com/article?utm_source=two&Keep=A'),
  (7, 'host-impersonation first query', 'https://instagram.com.evil.invalid/p/AbC_123-/?igsh=one'),
  (8, 'host-impersonation second query', 'https://instagram.com.evil.invalid/p/AbC_123-/?igsh=two'),
  (9, 'unsupported-path first query', 'https://instagram.com/p/AbC_123-/extra?igsh=one'),
  (10, 'unsupported-path second query', 'https://instagram.com/p/AbC_123-/extra?igsh=two');
grant select on table social_new_cases to service_role;

set local role service_role;
insert into social_results
select 'new-' || sample.ordinal, public.library_create_item(
  '1a000000-0000-0000-0000-000000000001',
  ('4a000000-0000-0000-0001-' || lpad(sample.ordinal::text, 12, '0'))::uuid,
  jsonb_build_object('url', sample.url), pg_temp.social_prepared(sample.url)
)
from social_new_cases as sample order by sample.ordinal;
insert into social_results
select 'create-replay', public.library_create_item(
  '1a000000-0000-0000-0000-000000000001',
  '4a000000-0000-0000-0001-000000000001',
  jsonb_build_object('url', sample.url), pg_temp.social_prepared(sample.url)
)
from social_new_cases as sample where sample.ordinal = 1;
insert into social_results values (
  'new-post-variant', public.library_create_item(
    '1a000000-0000-0000-0000-000000000001', '4a000000-0000-0000-0001-000000000011',
    '{"url":"https://www.instagram.com/reel/abc_123-/?igsh=created-variant"}'::jsonb,
    pg_temp.social_prepared('https://www.instagram.com/reel/abc_123-/?igsh=created-variant')
  )
);
insert into social_results values (
  'other-owner', public.library_create_item(
    '1a000000-0000-0000-0000-000000000002', '4a000000-0000-0000-0002-000000000001',
    '{"url":"https://instagram.com/p/AbC_123-/?igsh=other-owner"}'::jsonb,
    pg_temp.social_prepared('https://instagram.com/p/AbC_123-/?igsh=other-owner')
  )
);
reset role;

select ok(
  result.response @> '{"http_status":201,"duplicate":false}'::jsonb,
  sample.label || ' is a separate new item'
)
from social_new_cases as sample
join social_results as result on result.input_name = 'new-' || sample.ordinal
order by sample.ordinal;
select is(
  (select count(distinct response #>> '{item,id}') from social_results where input_name ~ '^new-[0-9]+$'),
  10::bigint, 'distinct IDs, unknown queries and invalid identities never collapse together'
);
select ok(
  not exists (
    select 1 from social_new_cases as sample
    join social_results as result on result.input_name = 'new-' || sample.ordinal
    join public.items as item on item.id::text = result.response #>> '{item,id}'
    where item.original_url <> sample.url or item.normalized_url <> sample.url
      or item.url_hash <> encode(extensions.digest(convert_to(sample.url, 'UTF8'), 'sha256'), 'hex')
  ),
  'new saves retain complete original and normalized URLs and exact hashes, including arbitrary queries'
);
select is(
  (select response from social_results where input_name = 'create-replay'),
  (select response from social_results where input_name = 'new-1'),
  'new-item replay preserves its original 201/non-duplicate receipt'
);
select ok(
  (select response @> '{"http_status":200,"duplicate":true}'::jsonb from social_results where input_name = 'new-post-variant')
  and (select response #>> '{item,id}' from social_results where input_name = 'new-post-variant')
    = (select response #>> '{item,id}' from social_results where input_name = 'new-1'),
  'a fresh shared URL variant also deduplicates a newly created post'
);
select ok(
  (select response @> '{"http_status":201,"duplicate":false}'::jsonb
    and response #>> '{item,id}' <> '5a000000-0000-0000-0000-000000000001'
   from social_results where input_name = 'other-owner')
  and exists (
    select 1 from public.items as item
    where item.owner_id = '1a000000-0000-0000-0000-000000000002'
      and item.id::text = (select response #>> '{item,id}' from social_results where input_name = 'other-owner')
  ),
  'another owner saves the same post independently without receiving the first owner item'
);
select is(
  (select active_item_count from public.library_usage where owner_id = '1a000000-0000-0000-0000-000000000001'),
  15, 'only the ten genuinely new owner items increase usage'
);
select is(
  (select sum(request_count) from public.api_rate_buckets where owner_id = '1a000000-0000-0000-0000-000000000001' and operation = 'create_item'),
  10::bigint, 'only actual creates consume rate and replay consumes none'
);

-- Simulate the established privacy scrub to prove active-only identity matching.
update public.items
set deleted_at = now(), original_url = null, normalized_url = null, url_hash = null,
    source = null, display_fallback = null, metadata_state = null,
    user_title = null, fetched_title = null, shared_text = null,
    description = null, body_text = null, note = null, extraction_meta = '{}'::jsonb
where id::text = (select response #>> '{item,id}' from social_results where input_name = 'other-owner');
update public.library_usage set active_item_count = 0
where owner_id = '1a000000-0000-0000-0000-000000000002';
set local role service_role;
select throws_ok(
  $$ select public.library_create_item(
    '1a000000-0000-0000-0000-000000000002', '4a000000-0000-0000-0002-000000000001',
    '{"url":"https://instagram.com/p/AbC_123-/?igsh=other-owner"}'::jsonb,
    pg_temp.social_prepared('https://instagram.com/p/AbC_123-/?igsh=other-owner')
  ) $$,
  'P0001', 'ITEM_DELETED', 'a deleted create receipt still cannot resurrect an item'
);
insert into social_results values (
  'after-delete', public.library_create_item(
    '1a000000-0000-0000-0000-000000000002', '4a000000-0000-0000-0002-000000000002',
    '{"url":"https://www.instagram.com/reel/AbC_123-/?igsh=after-delete"}'::jsonb,
    pg_temp.social_prepared('https://www.instagram.com/reel/AbC_123-/?igsh=after-delete')
  )
);
reset role;
select ok(
  (select response @> '{"http_status":201,"duplicate":false}'::jsonb from social_results where input_name = 'after-delete')
  and (select response #>> '{item,id}' from social_results where input_name = 'after-delete')
    <> (select response #>> '{item,id}' from social_results where input_name = 'other-owner'),
  'a new request can create the post again after deletion without matching a deleted identity'
);

-- A raw text identity index would exceed the btree key limit for this accepted URL.
create temporary table social_long_post as
select string_agg(
  translate(encode(extensions.digest(ordinal::text, 'sha256'), 'base64'), '+/=', '-_'),
  '' order by ordinal
) as shortcode
from generate_series(1, 80) as ordinal;
grant select on table social_long_post to service_role;
set local role service_role;
insert into social_results
select 'long-create', public.library_create_item(
  '1a000000-0000-0000-0000-000000000002', '4a000000-0000-0000-0002-000000000003',
  jsonb_build_object('url', 'https://instagram.com/p/' || shortcode || '/?igsh=one'),
  pg_temp.social_prepared('https://instagram.com/p/' || shortcode || '/?igsh=one')
)
from social_long_post;
insert into social_results
select 'long-duplicate', public.library_create_item(
  '1a000000-0000-0000-0000-000000000002', '4a000000-0000-0000-0002-000000000004',
  jsonb_build_object('url', 'https://www.instagram.com/reel/' || shortcode || '/?igsh=two'),
  pg_temp.social_prepared('https://www.instagram.com/reel/' || shortcode || '/?igsh=two')
)
from social_long_post;
reset role;
select ok(
  (select response @> '{"http_status":201,"duplicate":false}'::jsonb from social_results where input_name = 'long-create')
  and (select response @> '{"http_status":200,"duplicate":true}'::jsonb from social_results where input_name = 'long-duplicate')
  and (select response #>> '{item,id}' from social_results where input_name = 'long-create')
    = (select response #>> '{item,id}' from social_results where input_name = 'long-duplicate'),
  'long post identities remain safely indexable and deduplicate without shortening IDs'
);

-- Inject deterministic collisions without changing any existing collision tests.
create temporary table social_collisions (
  ordinal integer primary key,
  label text not null,
  existing_url text not null,
  requested_url text not null
);
insert into social_collisions values
  (1, 'both unknown identities', 'https://example.com/collision-existing', 'https://example.com/collision-request'),
  (2, 'different recognized identities', 'https://instagram.com/p/CollisionOld/', 'https://instagram.com/p/CollisionNew/'),
  (3, 'unknown existing versus recognized request', 'https://example.com/collision-existing-known-request', 'https://threads.com/@user/post/CollisionKnown'),
  (4, 'recognized existing versus unknown request', 'https://threads.net/@user/post/CollisionExisting', 'https://example.com/collision-unknown-request'),
  (5, 'older valid match cannot hide another colliding hash', 'https://instagram.com/p/NotTheOldPost/', 'https://instagram.com/p/AbC_123-/?igsh=hidden-collision');
insert into public.items (
  owner_id, original_url, normalized_url, url_hash, source, display_fallback, metadata_state
)
select '1a000000-0000-0000-0000-000000000001', sample.existing_url, sample.existing_url,
  encode(extensions.digest(convert_to(sample.requested_url, 'UTF8'), 'sha256'), 'hex'),
  'other', 'collision fixture', 'unsupported'
from social_collisions as sample;
grant select on table social_collisions to service_role;

set local role service_role;
insert into social_results
select 'collision-' || sample.ordinal, public.library_create_item(
  '1a000000-0000-0000-0000-000000000001',
  ('4a000000-0000-0000-0003-' || lpad(sample.ordinal::text, 12, '0'))::uuid,
  jsonb_build_object('url', sample.requested_url), pg_temp.social_prepared(sample.requested_url)
)
from social_collisions as sample order by sample.ordinal;
reset role;
select is(
  result.response, '{"http_status":409,"error_code":"URL_HASH_COLLISION"}'::jsonb,
  sample.label || ' retains the committed hash-collision defense'
)
from social_collisions as sample
join social_results as result on result.input_name = 'collision-' || sample.ordinal
order by sample.ordinal;
select is(
  (select count(*) from public.api_requests
   where owner_id = '1a000000-0000-0000-0000-000000000001'
     and request_id::text like '4a000000-0000-0000-0003-%'
     and response_code = 409 and response_body = '{"error_code":"URL_HASH_COLLISION"}'::jsonb),
  5::bigint, 'all actual collisions retain minimal replayable conflict receipts'
);

insert into public.items (
  owner_id, original_url, normalized_url, url_hash, source, display_fallback, metadata_state
)
values (
  '1a000000-0000-0000-0000-000000000001',
  'https://instagram.com/p/AbC_123-/?igsh=same-identity-existing',
  'https://instagram.com/p/AbC_123-/?igsh=same-identity-existing',
  encode(extensions.digest(convert_to('https://instagram.com/reel/AbC_123-/?igsh=same-identity-request', 'UTF8'), 'sha256'), 'hex'),
  'other', 'same-identity fixture', 'unsupported'
);
set local role service_role;
select is(
  public.library_create_item(
    '1a000000-0000-0000-0000-000000000001', '4a000000-0000-0000-0003-000000000006',
    '{"url":"https://instagram.com/reel/AbC_123-/?igsh=same-identity-request"}'::jsonb,
    pg_temp.social_prepared('https://instagram.com/reel/AbC_123-/?igsh=same-identity-request')
  ) #>> '{item,id}',
  '5a000000-0000-0000-0000-000000000001',
  'a matching nonnull post identity permits differing URLs even when the hash matches a newer duplicate'
);
reset role;

-- Keep the old quota/rate behavior, including free duplicates at both limits.
-- Keep quota/rate behavior, including free duplicates at both current limits.
update public.library_usage set active_item_count = 1000
where owner_id = '1a000000-0000-0000-0000-000000000001';
insert into public.api_rate_buckets (owner_id, operation, window_start, request_count)
values ('1a000000-0000-0000-0000-000000000001', 'create_item', date_trunc('minute', clock_timestamp()), 10)
on conflict (owner_id, operation, window_start)
do update set request_count = excluded.request_count;
set local role service_role;
select is(
  public.library_create_item(
    '1a000000-0000-0000-0000-000000000001', '4a000000-0000-0000-0004-000000000001',
    '{"url":"https://instagram.com/p/AbC_123-/?igsh=at-both-limits"}'::jsonb,
    pg_temp.social_prepared('https://instagram.com/p/AbC_123-/?igsh=at-both-limits')
  ) #>> '{item,id}',
  '5a000000-0000-0000-0000-000000000001',
  'a social identity duplicate succeeds at both the item limit and full creation bucket'
);
select is(
  public.library_create_item(
    '1a000000-0000-0000-0000-000000000001', '4a000000-0000-0000-0004-000000000002',
    '{"url":"https://instagram.com/p/QuotaNew/"}'::jsonb,
    pg_temp.social_prepared('https://instagram.com/p/QuotaNew/')
  ), '{"http_status":409,"error_code":"ITEM_LIMIT_REACHED"}'::jsonb,
  'a different post still reaches the locked item quota check'
);
reset role;
update public.library_usage set active_item_count = 21
where owner_id = '1a000000-0000-0000-0000-000000000001';
set local role service_role;
select throws_ok(
  $$ select public.library_create_item(
    '1a000000-0000-0000-0000-000000000001', '4a000000-0000-0000-0004-000000000003',
    '{"url":"https://instagram.com/p/RateNew/"}'::jsonb,
    pg_temp.social_prepared('https://instagram.com/p/RateNew/')
  ) $$,
  'P0001', 'RATE_LIMITED', 'a different post still reaches the full creation rate check'
);
select throws_ok(
  $$ select public.library_create_item(
    '1a000000-0000-0000-0000-000000000001', '4a000000-0000-0000-0004-000000000004',
    '{"url":"https://instagram.com/p/AbC_123-/","category_ids":["3a000000-0000-0000-0000-000000000003"]}'::jsonb,
    pg_temp.social_prepared('https://instagram.com/p/AbC_123-/')
  ) $$,
  'P0001', 'INVALID_CATEGORY_IDS', 'duplicate identity does not bypass category ownership validation'
);
reset role;
select ok(
  not exists (
    select 1 from public.api_requests
    where request_id in ('4a000000-0000-0000-0004-000000000003', '4a000000-0000-0000-0004-000000000004')
  )
  and not exists (
    select 1 from public.items
    where original_url in ('https://instagram.com/p/QuotaNew/', 'https://instagram.com/p/RateNew/')
  ),
  'rate/ownership failures leave no receipts and quota/rate conflicts leave no new item'
);

set local role authenticated;
set local "request.jwt.claim.sub" = '1a000000-0000-0000-0000-000000000002';
select throws_ok(
  $$ select public.library_get_item('5a000000-0000-0000-0000-000000000001') $$,
  'P0001', 'ITEM_NOT_FOUND', 'another owner cannot retrieve the original deduplicated item'
);
reset role;

select * from finish();
rollback;
