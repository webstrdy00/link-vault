begin;

create extension if not exists pgtap with schema extensions;
set local search_path = pg_catalog, extensions, public;
select no_plan();

insert into auth.users (
  instance_id, id, aud, role, email, encrypted_password, email_confirmed_at,
  raw_app_meta_data, raw_user_meta_data, created_at, updated_at,
  confirmation_token, email_change, email_change_token_new, recovery_token
)
select
  '00000000-0000-0000-0000-000000000000', owner_id, 'authenticated', 'authenticated',
  'thumbnail-' || ordinal || '@example.test', '', now(),
  '{"provider":"google","providers":["google"]}'::jsonb, '{}'::jsonb, now(), now(),
  '', '', '', ''
from (values
  (1, '26000000-0000-0000-0000-000000000001'::uuid),
  (2, '26000000-0000-0000-0000-000000000002'::uuid),
  (3, '26000000-0000-0000-0000-000000000003'::uuid),
  (4, '26000000-0000-0000-0000-000000000004'::uuid)
) as owners(ordinal, owner_id);

insert into public.beta_members (owner_id, enabled, approved_at)
select id, id <> '26000000-0000-0000-0000-000000000003'::uuid, now()
from auth.users where email like 'thumbnail-%@example.test';
insert into public.profiles (id, state)
select id, case when id = '26000000-0000-0000-0000-000000000004'::uuid then 'deleting' else 'active' end
from auth.users where email like 'thumbnail-%@example.test';

create temporary table thumbnail_cases (
  ordinal integer primary key,
  label text not null,
  asset_state text,
  owner_id uuid not null default '26000000-0000-0000-0000-000000000001',
  item_id uuid generated always as (
    ('26100000-0000-0000-0000-' || lpad(ordinal::text, 12, '0'))::uuid
  ) stored,
  asset_id uuid generated always as (
    ('26200000-0000-0000-0000-' || lpad(ordinal::text, 12, '0'))::uuid
  ) stored,
  user_title text default '  내 제목  ',
  fetched_title text default '  수집 제목  ',
  body_text text default E'  본문\n\t미리보기  ',
  description text default '설명',
  shared_text text default '공유',
  note text default E'  내\n메모  ' || repeat('한', 130),
  expected_title text not null default '내 제목',
  expected_preview text default '본문 미리보기'
);
insert into thumbnail_cases (ordinal, label, asset_state)
values
  (1, 'owned active image', 'active'),
  (2, 'reserved image', 'reserved'),
  (3, 'deleted image awaiting cleanup', 'deleting'),
  (4, 'missing image', null),
  (5, 'deleted item with an active image', 'active');
insert into thumbnail_cases (ordinal, label, asset_state, owner_id)
values
  (6, 'other owner image', 'active', '26000000-0000-0000-0000-000000000002'),
  (7, 'disabled member image', 'active', '26000000-0000-0000-0000-000000000003'),
  (8, 'deleting profile image', 'active', '26000000-0000-0000-0000-000000000004');
update thumbnail_cases
set user_title = '   ', body_text = 'https://example.test/body',
    description = E'  설명\n대체  ', expected_title = '수집 제목', expected_preview = '설명 대체'
where ordinal = 2;
update thumbnail_cases
set user_title = null, fetched_title = '   ', body_text = E' \n\t ',
    description = 'https://example.test/description', shared_text = E' 공유\n대체 ',
    expected_title = 'example.test', expected_preview = '공유 대체'
where ordinal = 3;
update thumbnail_cases
set user_title = null, fetched_title = null, body_text = 'https://example.test/body',
    description = 'https://example.test/description', shared_text = 'www.example.test/shared',
    note = '사용자 메모만 있음', expected_title = 'example.test', expected_preview = null
where ordinal = 4;

insert into public.items (
  id, owner_id, original_url, normalized_url, url_hash, source, display_fallback,
  user_title, fetched_title, shared_text, description, body_text, note, metadata_state,
  text_revision, version
)
select item_id, owner_id,
  'https://example.test/thumbnail/' || ordinal,
  'https://example.test/thumbnail/' || ordinal,
  encode(extensions.digest(convert_to('https://example.test/thumbnail/' || ordinal, 'UTF8'), 'sha256'), 'hex'),
  'other', 'example.test', user_title, fetched_title, shared_text, description, body_text, note, 'ready',
  case when ordinal = 2 then 2 else 1 end, 3
from thumbnail_cases;
insert into public.item_search (
  owner_id, item_id, text_revision, search_version, normalized_fields,
  alias_concepts, cue_version, cue_state, cue_flags
)
select owner_id, item_id, 1, 'search-v2.0.0',
  '{"user_title":"","fetched_title":"","note":"","ocr":"","shared":"","description":"","body":"","categories":"","url":""}'::jsonb,
  '{"user_title":[],"fetched_title":[],"note":[],"ocr":[],"shared":[],"description":[],"body":[]}'::jsonb,
  'cues-v1.0.0', 'available', '[]'::jsonb
from thumbnail_cases;
insert into public.item_classification (owner_id, item_id, rules_version, target_revision, state, reasons)
select owner_id, item_id, 'rules-v2.0.0', 1,
  case when ordinal = 1 then 'manual' else 'automatic' end,
  '[{"source":"thumbnail-fixture"}]'::jsonb
from thumbnail_cases;
insert into public.item_category_controls (owner_id, item_id, manual_override, cue_dismissed_revision)
select owner_id, item_id, ordinal = 1, case when ordinal = 1 then 1 else null end
from thumbnail_cases;
insert into public.categories (id, owner_id, name, normalized_name, kind)
values ('26300000-0000-0000-0000-000000000001', '26000000-0000-0000-0000-000000000001', '수동 분류', '수동 분류', 'custom');
insert into public.item_categories (owner_id, item_id, category_id, origin)
values ('26000000-0000-0000-0000-000000000001', '26100000-0000-0000-0000-000000000001',
  '26300000-0000-0000-0000-000000000001', 'manual');

insert into storage.objects (id, bucket_id, name, metadata)
select asset_id, 'library-images', owner_id::text || '/' || item_id::text || '/' || asset_id::text,
  jsonb_build_object('size', 1234 + ordinal, 'mimetype', 'image/jpeg')
from thumbnail_cases where asset_state in ('active', 'deleting');
insert into public.assets (
  id, owner_id, item_id, object_path, state, reserved_mime_type,
  actual_bytes, mime_type, width, height, storage_object_id, verified_at,
  ocr_state, ocr_text, reservation_expires_at, deleted_at, cleanup_reason, cleanup_next_run_at
)
select asset_id, owner_id, item_id, owner_id::text || '/' || item_id::text || '/' || asset_id::text,
  asset_state, 'image/jpeg',
  case when asset_state <> 'reserved' then 1234 + ordinal end,
  case when asset_state <> 'reserved' then 'image/jpeg' end,
  case when asset_state <> 'reserved' then 640 + ordinal end,
  case when asset_state <> 'reserved' then 480 + ordinal end,
  case when asset_state <> 'reserved' then asset_id end,
  case when asset_state <> 'reserved' then now() end,
  case when asset_state = 'active' then 'ready' else 'not_requested' end,
  case when asset_state = 'active' then 'thumbnail-private-ocr-' || ordinal end,
  now() + interval '15 minutes',
  case when asset_state = 'deleting' then now() end,
  case when asset_state = 'deleting' then 'user_delete' end,
  case when asset_state = 'deleting' then now() end
from thumbnail_cases where asset_state is not null;

-- Keep related rows to prove the deleted-item fence is independent of the asset join.
update public.items
set deleted_at = now(), original_url = null, normalized_url = null, url_hash = null,
    source = null, display_fallback = null, metadata_state = null,
    user_title = null, fetched_title = null, shared_text = null, description = null,
    body_text = null, note = null, extraction_meta = '{}'::jsonb
where id = '26100000-0000-0000-0000-000000000005';

-- Exact expected objects protect all existing fields, title/preview priorities and revision fences.
create temporary table thumbnail_expected as
select fixture.ordinal, fixture.item_id, fixture.label,
  jsonb_build_object(
    'id', fixture.item_id, 'version', 3, 'text_revision', item.text_revision,
    'url', 'https://example.test/thumbnail/' || fixture.ordinal,
    'display_title', fixture.expected_title, 'source', 'other',
    'note_excerpt', left(fixture.note, 120), 'content_excerpt', fixture.expected_preview,
    'category_refs', case when fixture.ordinal = 1 then
      '[{"id":"26300000-0000-0000-0000-000000000001","name":"수동 분류","kind":"custom","system_code":null,"origin":"manual"}]'::jsonb
      else '[]'::jsonb end,
    'has_attachment', coalesce(fixture.asset_state = 'active', false),
    'thumbnail_asset', case when fixture.asset_state = 'active' then jsonb_build_object(
      'id', fixture.asset_id, 'mime_type', 'image/jpeg', 'byte_size', 1234 + fixture.ordinal,
      'width', 640 + fixture.ordinal, 'height', 480 + fixture.ordinal
    ) else null end,
    'metadata_state', 'ready',
    'ocr_state', case when fixture.asset_state = 'active' then 'ready' else 'not_requested' end,
    'classification_state', case when fixture.ordinal = 1 then 'manual' else 'automatic' end,
    'cue_state', 'available', 'cue_flags', '[]'::jsonb, 'match_type', null,
    'created_at', item.created_at, 'updated_at', item.updated_at
  ) as summary,
  jsonb_build_object(
    'user_title', fixture.user_title, 'fetched_title', fixture.fetched_title,
    'shared_text', fixture.shared_text, 'description', fixture.description, 'body_text', fixture.body_text,
    'note', fixture.note, 'extraction_meta', '{}'::jsonb,
    'active_asset', case when fixture.asset_state = 'active' then jsonb_build_object(
      'id', fixture.asset_id,
      'object_path', fixture.owner_id::text || '/' || fixture.item_id::text || '/' || fixture.asset_id::text,
      'mime_type', 'image/jpeg', 'byte_size', 1234 + fixture.ordinal,
      'width', 640 + fixture.ordinal, 'height', 480 + fixture.ordinal,
      'ocr_text', 'thumbnail-private-ocr-' || fixture.ordinal, 'ocr_truncated', false
    ) else null end,
    'manual_override', fixture.ordinal = 1,
    'search_version', case when fixture.ordinal <> 2 then 'search-v2.0.0' end,
    'rules_version', case when fixture.ordinal <> 2 then 'rules-v2.0.0' end,
    'classification_reasons', case when fixture.ordinal = 2 then '[]'::jsonb
      else '[{"source":"thumbnail-fixture"}]'::jsonb end,
    'cue_prompt_dismissed', fixture.ordinal = 1
  ) as detail_fields
from thumbnail_cases as fixture join public.items as item on item.id = fixture.item_id
where fixture.ordinal between 1 and 4;

select is(
  private.library_item_json('26000000-0000-0000-0000-000000000001', item_id, false),
  summary, 'summary retains every existing field and adds only the descriptor: ' || label
) from thumbnail_expected order by ordinal;
select is(
  private.library_item_json('26000000-0000-0000-0000-000000000001', item_id, true),
  summary || detail_fields, 'detail retains raw sources, active_asset and classification fences: ' || label
) from thumbnail_expected order by ordinal;
select is(
  private.library_item_json('26000000-0000-0000-0000-000000000001', item_id, include_detail)->'thumbnail_asset',
  summary->'thumbnail_asset', 'active-only descriptor: ' || label || ' detail=' || include_detail
)
from thumbnail_expected cross join (values (false), (true)) as modes(include_detail)
order by ordinal, include_detail;
select is(
  (select jsonb_agg(key order by key)
    from jsonb_object_keys(private.library_item_json(
      '26000000-0000-0000-0000-000000000001', '26100000-0000-0000-0000-000000000001', include_detail
    )->'thumbnail_asset') as keys(key)),
  '["byte_size","height","id","mime_type","width"]'::jsonb,
  'descriptor has exactly five safe keys detail=' || include_detail
) from (values (false), (true)) as modes(include_detail);
select ok(
  not (projection ?| array['active_asset', 'object_path', 'storage_path', 'ocr_text', 'ocr_truncated', 'image_bytes', 'signed_url', 'signedURL'])
    and strpos(projection::text, 'thumbnail-private-ocr-') = 0
    and strpos(projection::text, '26000000-0000-0000-0000-000000000001/26100000-') = 0,
  'summary contains neither detail-only keys nor private storage/OCR values: ' || label
)
from thumbnail_expected cross join lateral (
  select private.library_item_json('26000000-0000-0000-0000-000000000001', item_id, false) as projection
) as projected order by ordinal;

select is(
  private.library_item_json(
    case when ordinal = 6 then '26000000-0000-0000-0000-000000000001'::uuid else owner_id end,
    item_id, include_detail
  ), null::jsonb, 'private projection remains inaccessible: ' || label || ' detail=' || include_detail
)
from thumbnail_cases cross join (values (false), (true)) as modes(include_detail)
where ordinal >= 5 order by ordinal, include_detail;
select is(
  private.library_item_json('26000000-0000-0000-0000-000000000001', '26100000-0000-0000-0000-000000000999', include_detail),
  null::jsonb, 'missing item has no projection detail=' || include_detail
) from (values (false), (true)) as modes(include_detail);

-- Both public RPCs resolve the replacement function and enforce caller access.
grant select on thumbnail_cases, thumbnail_expected to authenticated;
set local role authenticated;
select set_config('request.jwt.claim.sub', '26000000-0000-0000-0000-000000000001', true);
select is(
  (select jsonb_agg(entry->'id' order by entry->>'id')
    from jsonb_array_elements(public.library_list_items(50, 0)->'items') as listed(entry)),
  '["26100000-0000-0000-0000-000000000001","26100000-0000-0000-0000-000000000002","26100000-0000-0000-0000-000000000003","26100000-0000-0000-0000-000000000004"]'::jsonb,
  'list includes exactly the caller-owned nondeleted items'
);
select is(entry, summary, 'public summary exposes only safe fields: ' || expected.label)
from jsonb_array_elements(public.library_list_items(50, 0)->'items') as listed(entry)
join thumbnail_expected as expected on expected.item_id = (entry->>'id')::uuid
order by expected.ordinal;
select is(public.library_get_item(item_id), summary || detail_fields,
  'public detail exposes the same descriptor without changing active_asset: ' || label)
from thumbnail_expected order by ordinal;
select throws_ok(
  format('select public.library_get_item(%L::uuid)', item_id), 'P0001', 'ITEM_NOT_FOUND',
  'detail rejects inaccessible item: ' || label
) from thumbnail_cases where ordinal >= 5 order by ordinal;
select throws_ok(
  $$select public.library_get_item('26100000-0000-0000-0000-000000000999')$$,
  'P0001', 'ITEM_NOT_FOUND', 'detail rejects a missing item'
);
select set_config('request.jwt.claim.sub', '26000000-0000-0000-0000-000000000003', true);
select throws_ok($$select public.library_list_items(50, 0)$$, 'P0001', 'BETA_ACCESS_REQUIRED',
  'disabled member cannot list thumbnails');
select throws_ok($$select public.library_get_item('26100000-0000-0000-0000-000000000007')$$,
  'P0001', 'BETA_ACCESS_REQUIRED', 'disabled member cannot read an owned thumbnail');
select set_config('request.jwt.claim.sub', '26000000-0000-0000-0000-000000000004', true);
select throws_ok($$select public.library_list_items(50, 0)$$, 'P0001', 'ACCOUNT_DELETING',
  'deleting profile cannot list thumbnails');
select throws_ok($$select public.library_get_item('26100000-0000-0000-0000-000000000008')$$,
  'P0001', 'ACCOUNT_DELETING', 'deleting profile cannot read an owned thumbnail');
select set_config('request.jwt.claim.sub', '', true);
select throws_ok($$select public.library_list_items(50, 0)$$, 'P0001', 'UNAUTHENTICATED',
  'unauthenticated caller cannot list thumbnails');
select throws_ok($$select public.library_get_item('26100000-0000-0000-0000-000000000001')$$,
  'P0001', 'UNAUTHENTICATED', 'unauthenticated caller cannot read a thumbnail');
reset role;

-- A reservation must not displace the old active image before completion.
insert into public.assets (id, owner_id, item_id, object_path, reserved_mime_type, reservation_expires_at)
values (
  '26200000-0000-0000-0000-000000000101', '26000000-0000-0000-0000-000000000001',
  '26100000-0000-0000-0000-000000000001',
  '26000000-0000-0000-0000-000000000001/26100000-0000-0000-0000-000000000001/26200000-0000-0000-0000-000000000101',
  'image/webp', now() + interval '15 minutes'
);
set local role authenticated;
select set_config('request.jwt.claim.sub', '26000000-0000-0000-0000-000000000001', true);
select is(projection->'thumbnail_asset', expected.summary->'thumbnail_asset',
  'pending replacement retains the old active descriptor: ' || mode)
from (
  select 'detail' as mode, public.library_get_item('26100000-0000-0000-0000-000000000001') as projection
  union all
  select 'summary', entry from jsonb_array_elements(public.library_list_items(50, 0)->'items') as listed(entry)
  where entry->>'id' = '26100000-0000-0000-0000-000000000001'
) as projected cross join thumbnail_expected as expected where expected.ordinal = 1;
reset role;

update public.assets
set state = 'deleting', deleted_at = now(), cleanup_reason = 'replaced', cleanup_next_run_at = now(),
    ocr_state = 'not_requested', ocr_text = null, ocr_truncated = false
where id = '26200000-0000-0000-0000-000000000001';
insert into storage.objects (id, bucket_id, name, metadata)
values (
  '26200000-0000-0000-0000-000000000101', 'library-images',
  '26000000-0000-0000-0000-000000000001/26100000-0000-0000-0000-000000000001/26200000-0000-0000-0000-000000000101',
  '{"size":9876,"mimetype":"image/webp"}'::jsonb
);
update public.assets
set state = 'active', actual_bytes = 9876, mime_type = 'image/webp', width = 321, height = 123,
    storage_object_id = id, verified_at = now(), ocr_state = 'ready', ocr_text = 'thumbnail-private-ocr-replacement'
where id = '26200000-0000-0000-0000-000000000101';
set local role authenticated;
select is(projection->'thumbnail_asset',
  '{"id":"26200000-0000-0000-0000-000000000101","mime_type":"image/webp","byte_size":9876,"width":321,"height":123}'::jsonb,
  'completed replacement exposes only the new active descriptor with actual bytes: ' || mode)
from (
  select 'detail' as mode, public.library_get_item('26100000-0000-0000-0000-000000000001') as projection
  union all
  select 'summary', entry from jsonb_array_elements(public.library_list_items(50, 0)->'items') as listed(entry)
  where entry->>'id' = '26100000-0000-0000-0000-000000000001'
) as projected;
select is(entry - 'thumbnail_asset', expected.summary - 'thumbnail_asset',
  'replacement changes no other summary fields or leaks detail data')
from jsonb_array_elements(public.library_list_items(50, 0)->'items') as listed(entry)
join thumbnail_expected as expected on expected.item_id = (entry->>'id')::uuid where expected.ordinal = 1;
select is(public.library_get_item('26100000-0000-0000-0000-000000000001')->'active_asset',
  '{"id":"26200000-0000-0000-0000-000000000101","object_path":"26000000-0000-0000-0000-000000000001/26100000-0000-0000-0000-000000000001/26200000-0000-0000-0000-000000000101","mime_type":"image/webp","byte_size":9876,"width":321,"height":123,"ocr_text":"thumbnail-private-ocr-replacement","ocr_truncated":false}'::jsonb,
  'detail-only active_asset retains its complete original shape after replacement');
reset role;

update public.assets
set state = 'deleting', deleted_at = now(), cleanup_reason = 'user_delete', cleanup_next_run_at = now(),
    ocr_state = 'not_requested', ocr_text = null, ocr_truncated = false
where id = '26200000-0000-0000-0000-000000000101';
set local role authenticated;
select is(jsonb_build_object(
    'thumbnail_asset', projection->'thumbnail_asset', 'has_attachment', projection->'has_attachment',
    'ocr_state', projection->'ocr_state'
  ), '{"thumbnail_asset":null,"has_attachment":false,"ocr_state":"not_requested"}'::jsonb,
  'delete acceptance immediately hides both images without reviving the replaced asset: ' || mode)
from (
  select 'detail' as mode, public.library_get_item('26100000-0000-0000-0000-000000000001') as projection
  union all
  select 'summary', entry from jsonb_array_elements(public.library_list_items(50, 0)->'items') as listed(entry)
  where entry->>'id' = '26100000-0000-0000-0000-000000000001'
) as projected;
select is(public.library_get_item('26100000-0000-0000-0000-000000000001')->'active_asset',
  'null'::jsonb, 'deleted active asset remains absent from detail');
reset role;

select * from finish();
rollback;
