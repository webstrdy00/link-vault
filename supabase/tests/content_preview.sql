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
  'preview-' || ordinal || '@example.test', '', now(),
  '{"provider":"google","providers":["google"]}'::jsonb, '{}'::jsonb, now(), now(),
  '', '', '', ''
from (values
  (1, '24000000-0000-0000-0000-000000000001'::uuid),
  (2, '24000000-0000-0000-0000-000000000002'::uuid)
) as owners(ordinal, owner_id);

insert into public.beta_members (owner_id, enabled, approved_at)
values
  ('24000000-0000-0000-0000-000000000001', true, now()),
  ('24000000-0000-0000-0000-000000000002', true, now());
insert into public.profiles (id, state)
values
  ('24000000-0000-0000-0000-000000000001', 'active'),
  ('24000000-0000-0000-0000-000000000002', 'active');

create temporary table preview_cases (
  ordinal integer primary key,
  label text not null,
  body_text text,
  description text,
  shared_text text,
  note text,
  expected text,
  owner_id uuid not null default '24000000-0000-0000-0000-000000000001',
  item_id uuid generated always as (
    ('24100000-0000-0000-0000-' || lpad(ordinal::text, 12, '0'))::uuid
  ) stored
);
insert into preview_cases (ordinal, label, body_text, description, shared_text, note, expected)
values
  (1, 'absent memo uses description', null, E'  설명\n\t미리보기  ', null, null, '설명 미리보기'),
  (2, 'body precedes description and shared text', E'  본문\n\n첫째\t둘째  ', '설명', '공유', '내 메모', '본문 첫째 둘째'),
  (3, 'whitespace-only body falls back', E' \n\t\r ', '설명 대체', '공유', null, '설명 대체'),
  (4, 'shared text is the last source fallback', null, E' \t ', E'공유\n원문', null, '공유 원문'),
  (5, 'URL-only candidates yield null not memo', 'https://example.test/body', 'HTTP://example.test/description', 'www.example.test/shared', '사용자 메모만 있음', null),
  (6, 'URL-only body skips to useful description', E' https://example.test/a\nhttps://example.test/b ', '실제 설명', null, null, '실제 설명'),
  (7, 'Unicode preview has 120 characters', repeat('한𐐀', 61), '짧은 설명', null, null, repeat('한𐐀', 60)),
  (8, 'all sources missing never borrows memo', null, null, null, E'  내\n메모  ', null),
  (9, 'memo is not normalized or used for content', '실제 본문', null, null, E'  내\n메모  ' || repeat('한', 130), '실제 본문'),
  (10, 'prose with a URL remains useful', '읽어 보기 https://example.test/article', null, null, null, '읽어 보기 https://example.test/article'),
  (11, 'exactly 120 characters are retained', repeat('가', 120), null, null, null, repeat('가', 120));
insert into preview_cases (ordinal, label, description, expected, owner_id)
values (99, 'other owner private source', '타인 비공개 설명', '타인 비공개 설명', '24000000-0000-0000-0000-000000000002');

insert into public.items (
  id, owner_id, original_url, normalized_url, url_hash, source, display_fallback,
  shared_text, description, body_text, note, metadata_state
)
select item_id, owner_id,
  'https://example.test/preview/' || ordinal,
  'https://example.test/preview/' || ordinal,
  encode(extensions.digest(convert_to('https://example.test/preview/' || ordinal, 'UTF8'), 'sha256'), 'hex'),
  'other', 'example.test', shared_text, description, body_text, note, 'ready'
from preview_cases;

insert into public.item_search (
  owner_id, item_id, text_revision, search_version, normalized_fields,
  alias_concepts, cue_version, cue_state, cue_flags
)
select owner_id, item_id, 1, 'search-v2.0.0',
  '{"user_title":"","fetched_title":"","note":"","ocr":"","shared":"","description":"","body":"","categories":"","url":""}'::jsonb,
  '{"user_title":[],"fetched_title":[],"note":[],"ocr":[],"shared":[],"description":[],"body":[]}'::jsonb,
  'cues-v1.0.0', 'available', '[]'::jsonb
from preview_cases;
insert into public.item_classification (owner_id, item_id, rules_version, target_revision, state)
select owner_id, item_id, 'rules-v2.0.0', 1, 'pending' from preview_cases;
insert into public.item_category_controls (owner_id, item_id)
select owner_id, item_id from preview_cases;

-- Exercise every source-selection branch in both projections.
select is(
  private.library_item_json(owner_id, item_id, include_detail)->>'content_excerpt',
  expected, label || case when include_detail then ' (detail)' else ' (summary)' end
)
from preview_cases cross join (values (false), (true)) as modes(include_detail)
order by ordinal, include_detail;

select is(
  private.library_item_json(owner_id, item_id, include_detail)->>'note_excerpt',
  left(note, 120), 'memo projection remains identical: ' || label || ' detail=' || include_detail
)
from preview_cases cross join (values (false), (true)) as modes(include_detail)
order by ordinal, include_detail;

select is(
  private.library_item_json(owner_id, item_id, true)
    - array['content_excerpt', 'note_excerpt'],
  private.library_item_json(owner_id, item_id, false)
    - array['content_excerpt', 'note_excerpt']
    || jsonb_build_object(
      'user_title', null, 'fetched_title', null,
      'shared_text', shared_text, 'description', description, 'body_text', body_text,
      'note', note, 'extraction_meta', '{}'::jsonb, 'active_asset', null,
      'manual_override', false, 'search_version', 'search-v2.0.0',
      'rules_version', 'rules-v2.0.0', 'classification_reasons', '[]'::jsonb,
      'cue_prompt_dismissed', false
    ),
  'raw detail source and all existing common fields preserved: ' || label
)
from preview_cases order by ordinal;

select is(
  char_length(private.library_item_json(owner_id, item_id, false)->>'content_excerpt'),
  120, 'Unicode truncation counts characters rather than bytes'
) from preview_cases where ordinal = 7;
select is(
  private.library_item_json('24000000-0000-0000-0000-000000000001', item_id, true),
  null::jsonb, 'private projection cannot cross owner boundary'
) from preview_cases where ordinal = 99;

-- Public list/detail RPCs must resolve the replaced projection, not a stale copy.
grant select on preview_cases to authenticated;
set local role authenticated;
select set_config('request.jwt.claim.sub', '24000000-0000-0000-0000-000000000001', true);
select is(
  jsonb_array_length(public.library_list_items(50, 0)->'items'),
  11, 'list includes only caller-owned items'
);
select is(
  entry->>'content_excerpt', fixture.expected,
  'public list exposes content preview: ' || fixture.label
)
from jsonb_array_elements(public.library_list_items(50, 0)->'items') as listed(entry)
join preview_cases as fixture on fixture.item_id = (entry->>'id')::uuid
order by fixture.ordinal;
select is(
  public.library_get_item(item_id)->>'content_excerpt', expected,
  'public detail exposes content preview: ' || label
)
from preview_cases where ordinal <> 99 order by ordinal;
select throws_ok(
  $$select public.library_get_item('24100000-0000-0000-0000-000000000099')$$,
  'P0001', 'ITEM_NOT_FOUND', 'detail RPC rejects another owner item'
);
reset role;

select is(
  jsonb_build_object('body_text', item.body_text, 'description', item.description,
    'shared_text', item.shared_text, 'note', item.note),
  jsonb_build_object('body_text', fixture.body_text, 'description', fixture.description,
    'shared_text', fixture.shared_text, 'note', fixture.note),
  'projection reads never mutate stored raw text: ' || fixture.label
)
from public.items as item join preview_cases as fixture on fixture.item_id = item.id
order by fixture.ordinal;

select * from finish();
rollback;
