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
    '16000000-0000-0000-0000-000000000001',
    'authenticated',
    'authenticated',
    'discovery-a@example.test',
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
    '16000000-0000-0000-0000-000000000002',
    'authenticated',
    'authenticated',
    'discovery-b@example.test',
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
  ('16000000-0000-0000-0000-000000000001', true, now()),
  ('16000000-0000-0000-0000-000000000002', true, now());

insert into public.profiles (id, state)
values
  ('16000000-0000-0000-0000-000000000001', 'active'),
  ('16000000-0000-0000-0000-000000000002', 'active');

insert into public.library_usage (owner_id, active_item_count)
values
  ('16000000-0000-0000-0000-000000000001', 5),
  ('16000000-0000-0000-0000-000000000002', 1);

insert into public.categories (
  id,
  owner_id,
  name,
  normalized_name,
  kind,
  system_code,
  created_at,
  updated_at
)
values
  (
    '36000000-0000-0000-0000-000000000001',
    '16000000-0000-0000-0000-000000000001',
    '여행',
    '여행',
    'system',
    'travel',
    '2026-01-01T00:00:00Z',
    '2026-01-01T00:00:00Z'
  ),
  (
    '36000000-0000-0000-0000-000000000002',
    '16000000-0000-0000-0000-000000000001',
    '내 분류',
    '내 분류',
    'custom',
    null,
    '2026-01-02T00:00:00Z',
    '2026-01-02T00:00:00Z'
  ),
  (
    '36000000-0000-0000-0000-000000000003',
    '16000000-0000-0000-0000-000000000001',
    '정리함',
    '정리함',
    'custom',
    null,
    '2026-01-03T00:00:00Z',
    '2026-01-03T00:00:00Z'
  ),
  (
    '36000000-0000-0000-0000-000000000004',
    '16000000-0000-0000-0000-000000000002',
    '여행',
    '여행',
    'system',
    'travel',
    '2026-01-01T00:00:00Z',
    '2026-01-01T00:00:00Z'
  ),
  (
    '36000000-0000-0000-0000-000000000005',
    '16000000-0000-0000-0000-000000000002',
    '타인 분류',
    '타인 분류',
    'custom',
    null,
    '2026-01-02T00:00:00Z',
    '2026-01-02T00:00:00Z'
  );

insert into public.items (
  id,
  owner_id,
  original_url,
  normalized_url,
  url_hash,
  source,
  display_fallback,
  user_title,
  note,
  text_revision,
  version,
  metadata_state,
  created_at,
  updated_at
)
values
  (
    '56000000-0000-0000-0000-000000000001',
    '16000000-0000-0000-0000-000000000001',
    'https://example.test/a',
    'https://example.test/a',
    pg_catalog.lpad('1', 64, '0'),
    'other',
    'A',
    null,
    '카카오톡 원문',
    1,
    1,
    'unsupported',
    '2026-01-01T00:00:00Z',
    '2026-01-01T00:00:00Z'
  ),
  (
    '56000000-0000-0000-0000-000000000002',
    '16000000-0000-0000-0000-000000000001',
    'https://example.test/b',
    'https://example.test/b',
    pg_catalog.lpad('2', 64, '0'),
    'instagram',
    'B',
    '카톡',
    '특별',
    1,
    1,
    'unsupported',
    '2026-01-02T00:00:00Z',
    '2026-01-02T00:00:00Z'
  ),
  (
    '56000000-0000-0000-0000-000000000003',
    '16000000-0000-0000-0000-000000000001',
    'https://example.test/c',
    'https://example.test/c',
    pg_catalog.lpad('3', 64, '0'),
    'threads',
    'C',
    '기호',
    E'%_\\ 문자 그대로',
    1,
    1,
    'unsupported',
    '2026-01-03T00:00:00Z',
    '2026-01-03T00:00:00Z'
  ),
  (
    '56000000-0000-0000-0000-000000000004',
    '16000000-0000-0000-0000-000000000001',
    'https://example.test/d',
    'https://example.test/d',
    pg_catalog.lpad('4', 64, '0'),
    'naver_blog',
    'D',
    '날짜 필터',
    null,
    1,
    1,
    'unsupported',
    '2026-01-04T00:00:00Z',
    '2026-01-04T00:00:00Z'
  ),
  (
    '56000000-0000-0000-0000-000000000005',
    '16000000-0000-0000-0000-000000000001',
    'https://example.test/e',
    'https://example.test/e',
    pg_catalog.lpad('5', 64, '0'),
    'other',
    'E',
    '여행 맛집',
    null,
    1,
    1,
    'unsupported',
    '2026-01-05T00:00:00Z',
    '2026-01-05T00:00:00Z'
  ),
  (
    '56000000-0000-0000-0000-000000000006',
    '16000000-0000-0000-0000-000000000002',
    'https://example.test/foreign',
    'https://example.test/foreign',
    pg_catalog.lpad('6', 64, '0'),
    'other',
    'foreign',
    '카카오톡 원문',
    null,
    1,
    1,
    'unsupported',
    '2026-01-06T00:00:00Z',
    '2026-01-06T00:00:00Z'
  );

create function pg_temp.discovery_fields(
  p_title text,
  p_note text,
  p_categories text,
  p_url text
)
returns jsonb
language sql
immutable
as $$
  select pg_catalog.jsonb_build_object(
    'user_title', coalesce(p_title, ''),
    'fetched_title', '',
    'note', coalesce(p_note, ''),
    'ocr', '',
    'shared', '',
    'description', '',
    'body', '',
    'categories', coalesce(p_categories, ''),
    'url', p_url
  );
$$;

create function pg_temp.discovery_aliases(
  p_title jsonb,
  p_note jsonb
)
returns jsonb
language sql
immutable
as $$
  select pg_catalog.jsonb_build_object(
    'user_title', p_title,
    'fetched_title', '[]'::jsonb,
    'note', p_note,
    'ocr', '[]'::jsonb,
    'shared', '[]'::jsonb,
    'description', '[]'::jsonb,
    'body', '[]'::jsonb
  );
$$;

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
)
select
  item.owner_id,
  item.id,
  item.text_revision,
  'search-v2.0.0',
  pg_temp.discovery_fields(
    item.user_title,
    item.note,
    case item.id
      when '56000000-0000-0000-0000-000000000001' then '여행'
      when '56000000-0000-0000-0000-000000000003' then '정리함'
      when '56000000-0000-0000-0000-000000000005' then '여행 내 분류'
      when '56000000-0000-0000-0000-000000000006' then '타인 분류'
      else ''
    end,
    item.normalized_url
  ),
  pg_temp.discovery_aliases(
    case when item.id = '56000000-0000-0000-0000-000000000002'
      then '["kakaotalk"]'::jsonb else '[]'::jsonb end,
    case when item.id in (
      '56000000-0000-0000-0000-000000000001',
      '56000000-0000-0000-0000-000000000006'
    ) then '["kakaotalk"]'::jsonb else '[]'::jsonb end
  ),
  'cues-v1.0.0',
  case item.id
    when '56000000-0000-0000-0000-000000000002' then 'limited'
    when '56000000-0000-0000-0000-000000000003' then 'missing'
    else 'available'
  end,
  case item.id
    when '56000000-0000-0000-0000-000000000002'
      then '["short_text"]'::jsonb
    else '[]'::jsonb
  end
from public.items as item;

insert into public.item_classification (
  owner_id,
  item_id,
  rules_version,
  target_revision,
  state,
  reasons
)
select
  item.owner_id,
  item.id,
  'rules-v2.0.0',
  case when item.id = '56000000-0000-0000-0000-000000000004'
    then 2 else item.text_revision end,
  case when item.id = '56000000-0000-0000-0000-000000000005'
    then 'manual' else 'automatic' end,
  '[{"rule_id":"fixture","score":3,"fields":["note"]}]'::jsonb
from public.items as item;

insert into public.item_category_controls (
  owner_id,
  item_id,
  manual_override,
  cue_dismissed_revision
)
select
  item.owner_id,
  item.id,
  item.id = '56000000-0000-0000-0000-000000000005',
  null
from public.items as item;

insert into public.item_categories (owner_id, item_id, category_id, origin)
values
  (
    '16000000-0000-0000-0000-000000000001',
    '56000000-0000-0000-0000-000000000001',
    '36000000-0000-0000-0000-000000000001',
    'auto'
  ),
  (
    '16000000-0000-0000-0000-000000000001',
    '56000000-0000-0000-0000-000000000003',
    '36000000-0000-0000-0000-000000000003',
    'manual'
  ),
  (
    '16000000-0000-0000-0000-000000000001',
    '56000000-0000-0000-0000-000000000005',
    '36000000-0000-0000-0000-000000000001',
    'manual'
  ),
  (
    '16000000-0000-0000-0000-000000000001',
    '56000000-0000-0000-0000-000000000005',
    '36000000-0000-0000-0000-000000000002',
    'manual'
  ),
  (
    '16000000-0000-0000-0000-000000000002',
    '56000000-0000-0000-0000-000000000006',
    '36000000-0000-0000-0000-000000000005',
    'manual'
  );

select ok(
  (
    select installed_function.prosecdef
      and installed_function.proconfig = array['search_path=""']::text[]
    from pg_catalog.pg_proc as installed_function
    where installed_function.oid =
      'public.library_search_items(jsonb,jsonb,integer,integer)'::regprocedure
  ),
  'search is SECURITY DEFINER with an empty search path'
);

select ok(
  has_function_privilege(
    'authenticated',
    'public.library_search_items(jsonb,jsonb,integer,integer)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'public.library_search_items(jsonb,jsonb,integer,integer)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated',
    'public.library_create_category(uuid,uuid,jsonb,text)',
    'EXECUTE'
  )
  and has_function_privilege(
    'service_role',
    'public.library_create_category(uuid,uuid,jsonb,text)',
    'EXECUTE'
  ),
  'reads use caller JWT while mutations remain service-only'
);

set local role authenticated;
set local "request.jwt.claim.sub" = '16000000-0000-0000-0000-000000000001';

select is(
  pg_catalog.jsonb_build_object(
    'id', result #>> '{items,0,id}',
    'match_type', result #>> '{items,0,match_type}',
    'has_more', result -> 'has_more'
  ),
  '{"id":"56000000-0000-0000-0000-000000000001","match_type":"literal","has_more":true}'::jsonb,
  'all direct matches precede newer alias-only matches before paging'
)
from (
  select public.library_search_items(
    '{"query":"카카오톡","terms":["카카오톡"],"groups":[{"kind":"concept","value":"kakaotalk"}]}'::jsonb,
    '{}'::jsonb,
    1,
    0
  ) as result
) as searched;

select is(
  pg_catalog.jsonb_build_object(
    'id', result #>> '{items,0,id}',
    'match_type', result #>> '{items,0,match_type}',
    'has_more', result -> 'has_more'
  ),
  '{"id":"56000000-0000-0000-0000-000000000002","match_type":"alias","has_more":false}'::jsonb,
  'the combined offset reaches the alias bucket without a gap or duplicate'
)
from (
  select public.library_search_items(
    '{"query":"카카오톡","terms":["카카오톡"],"groups":[{"kind":"concept","value":"kakaotalk"}]}'::jsonb,
    '{}'::jsonb,
    1,
    1
  ) as result
) as searched;

select is(
  pg_catalog.jsonb_build_object(
    'count', pg_catalog.jsonb_array_length(result -> 'items'),
    'id', result #>> '{items,0,id}',
    'match_type', result #>> '{items,0,match_type}'
  ),
  '{"count":1,"id":"56000000-0000-0000-0000-000000000001","match_type":"literal"}'::jsonb,
  'aliases=false disables only the alias bucket'
)
from (
  select public.library_search_items(
    '{"query":"카카오톡","terms":["카카오톡"],"groups":[{"kind":"concept","value":"kakaotalk"}]}'::jsonb,
    '{"aliases":false}'::jsonb,
    20,
    0
  ) as result
) as searched;

select is(
  pg_catalog.jsonb_build_object(
    'id', result #>> '{items,0,id}',
    'match_type', result #>> '{items,0,match_type}'
  ),
  '{"id":"56000000-0000-0000-0000-000000000002","match_type":"alias"}'::jsonb,
  'alias groups are ANDed and use the highest matching field per group'
)
from (
  select public.library_search_items(
    '{"query":"카카오톡 특별","terms":["카카오톡","특별"],"groups":[{"kind":"concept","value":"kakaotalk"},{"kind":"literal","value":"특별"}]}'::jsonb,
    '{}'::jsonb,
    20,
    0
  ) as result
) as searched;

select is(
  result #>> '{items,0,id}',
  '56000000-0000-0000-0000-000000000003',
  'percent, underscore, and backslash are literal search characters'
)
from (
  select public.library_search_items(
    pg_catalog.jsonb_build_object(
      'query', E'%_\\',
      'terms', pg_catalog.jsonb_build_array(E'%_\\'),
      'groups', pg_catalog.jsonb_build_array(
        pg_catalog.jsonb_build_object('kind', 'literal', 'value', E'%_\\')
      )
    ),
    '{}'::jsonb,
    20,
    0
  ) as result
) as searched;

select is(
  (
    select pg_catalog.jsonb_agg(entry.value ->> 'id' order by entry.value ->> 'id')
    from pg_catalog.jsonb_array_elements(result -> 'items') as entry(value)
  ),
  '["56000000-0000-0000-0000-000000000001","56000000-0000-0000-0000-000000000005"]'::jsonb,
  'a category filter is owner-scoped and composes with the empty query'
)
from (
  select public.library_search_items(
    '{"query":"","terms":[],"groups":[]}'::jsonb,
    '{"category_id":"36000000-0000-0000-0000-000000000001"}'::jsonb,
    20,
    0
  ) as result
) as searched;

select is(
  (
    select pg_catalog.jsonb_agg(entry.value ->> 'id' order by entry.value ->> 'id')
    from pg_catalog.jsonb_array_elements(result -> 'items') as entry(value)
  ),
  '["56000000-0000-0000-0000-000000000002","56000000-0000-0000-0000-000000000003"]'::jsonb,
  'needs_cues includes limited and missing only'
)
from (
  select public.library_search_items(
    '{"query":"","terms":[],"groups":[]}'::jsonb,
    '{"needs_cues":true}'::jsonb,
    20,
    0
  ) as result
) as searched;

select is(
  result #>> '{items,0,id}',
  '56000000-0000-0000-0000-000000000002',
  'date_from is inclusive and date_to is exclusive'
)
from (
  select public.library_search_items(
    '{"query":"","terms":[],"groups":[]}'::jsonb,
    '{"date_from":"2026-01-02T00:00:00Z","date_to":"2026-01-03T00:00:00Z"}'::jsonb,
    20,
    0
  ) as result
) as searched;

select is(
  pg_catalog.jsonb_build_object(
    'count', pg_catalog.jsonb_array_length(result -> 'items'),
    'id', result #>> '{items,0,id}',
    'match_type_is_null', result #> '{items,0,match_type}' = 'null'::jsonb
  ),
  '{"count":1,"id":"56000000-0000-0000-0000-000000000002","match_type_is_null":true}'::jsonb,
  'source filtering preserves null match_type for an empty query'
)
from (
  select public.library_search_items(
    '{"query":"","terms":[],"groups":[]}'::jsonb,
    '{"source":"instagram"}'::jsonb,
    20,
    0
  ) as result
) as searched;

select throws_ok(
  $$
    select public.library_search_items(
      '{"query":"","terms":[],"groups":[]}'::jsonb,
      '{"category_id":"36000000-0000-0000-0000-000000000001","unclassified":true}'::jsonb
    )
  $$,
  'P0001',
  'INVALID_FILTER',
  'category and unclassified=true cannot be combined'
);

select throws_ok(
  $$
    select public.library_search_items(
      '{"query":"카톡","terms":["카톡"],"groups":[{"kind":"concept","value":"unknown"}]}'::jsonb,
      '{}'::jsonb
    )
  $$,
  'P0001',
  'QUERY_LIMIT',
  'only the eight fixed concept IDs are accepted'
);

select throws_ok(
  $$
    select public.library_search_items(
      '{"query":"x","terms":["x"],"groups":[{"kind":"literal","value":"x","weight":999}]}'::jsonb,
      '{}'::jsonb
    )
  $$,
  'P0001',
  'QUERY_LIMIT',
  'group keys and client-supplied weights are rejected'
);

select throws_ok(
  $$
    select public.library_search_items(
      pg_catalog.jsonb_build_object(
        'query', pg_catalog.repeat('가', 201),
        'terms', pg_catalog.jsonb_build_array('가'),
        'groups', pg_catalog.jsonb_build_array(
          pg_catalog.jsonb_build_object('kind', 'literal', 'value', '가')
        )
      ),
      '{}'::jsonb
    )
  $$,
  'P0001',
  'QUERY_LIMIT',
  'query input over 200 Unicode characters is rejected'
);

select throws_ok(
  $$
    select public.library_search_items(
      '{"query":"a b c d e f g h i j k","terms":["a","b","c","d","e","f","g","h","i","j","k"],"groups":[{"kind":"literal","value":"a"}]}'::jsonb,
      '{}'::jsonb
    )
  $$,
  'P0001',
  'QUERY_LIMIT',
  'more than ten literal terms are rejected rather than truncated'
);

select is(
  pg_catalog.jsonb_build_object(
    'count', categories -> 'count',
    'unclassified_count', categories -> 'unclassified_count',
    'travel_items', categories #> '{categories,0,item_count}'
  ),
  '{"count":3,"unclassified_count":2,"travel_items":2}'::jsonb,
  'category listing returns owner-only active counts and the virtual unclassified count'
)
from (
  select public.library_list_categories() as categories
) as listed;

select is(
  pg_catalog.jsonb_build_object(
    'rules_is_null', detail -> 'rules_version' = 'null'::jsonb,
    'reasons', detail -> 'classification_reasons',
    'search_version', detail -> 'search_version'
  ),
  '{"rules_is_null":true,"reasons":[],"search_version":"search-v2.0.0"}'::jsonb,
  'detail suppresses stale-revision classification evidence'
)
from (
  select public.library_get_item(
    '56000000-0000-0000-0000-000000000004'
  ) as detail
) as fetched;

reset role;
set local role service_role;
set local "request.jwt.claim.sub" = '';

select is(
  pg_catalog.jsonb_build_object(
    'status', response -> 'http_status',
    'name', response #> '{category,name}',
    'kind', response #> '{category,kind}'
  ),
  '{"status":201,"name":"ＡＢＣ","kind":"custom"}'::jsonb,
  'category creation stores the display name and trusted normalized name separately'
)
from (
  select public.library_create_category(
    '16000000-0000-0000-0000-000000000001',
    '66000000-0000-0000-0000-000000000001',
    '{"name":"ＡＢＣ"}'::jsonb,
    'abc'
  ) as response
) as created;

select is(
    public.library_create_category(
      '16000000-0000-0000-0000-000000000001',
      '66000000-0000-0000-0000-000000000002',
      '{"name":"ABC"}'::jsonb,
      'abc'
    ),
  '{"http_status":409,"error_code":"CATEGORY_NAME_EXISTS"}'::jsonb,
  'NFKC-equivalent names collide using the server-supplied normalized value'
);

select is(
    public.library_create_category(
      '16000000-0000-0000-0000-000000000001',
      '66000000-0000-0000-0000-000000000003',
      '{"name":"여행"}'::jsonb,
      '여행'
    ),
  '{"http_status":409,"error_code":"CATEGORY_NAME_EXISTS"}'::jsonb,
  'custom categories cannot duplicate a system category name'
);

reset role;

insert into public.categories (owner_id, name, normalized_name, kind, system_code)
select
  '16000000-0000-0000-0000-000000000001',
  '한도 ' || number.value,
  '한도 ' || number.value,
  'custom',
  null
from pg_catalog.generate_series(1, 27) as number(value);

set local role service_role;
set local "request.jwt.claim.sub" = '';

select is(
    public.library_create_category(
      '16000000-0000-0000-0000-000000000001',
      '66000000-0000-0000-0000-000000000004',
      '{"name":"31번째"}'::jsonb,
      '31번째'
    ),
  '{"http_status":409,"error_code":"CATEGORY_LIMIT_REACHED"}'::jsonb,
  'the owner-scoped custom category limit is thirty'
);

select throws_ok(
  $$
    select public.library_rename_category(
      '16000000-0000-0000-0000-000000000001',
      '36000000-0000-0000-0000-000000000001',
      '66000000-0000-0000-0000-000000000005',
      '{"name":"새 여행"}'::jsonb,
      '새 여행'
    )
  $$,
  'P0001',
  'SYSTEM_CATEGORY_READONLY',
  'system categories cannot be renamed'
);

select throws_ok(
  $$
    select public.library_delete_category(
      '16000000-0000-0000-0000-000000000001',
      '36000000-0000-0000-0000-000000000001',
      '66000000-0000-0000-0000-000000000006',
      '{}'::jsonb
    )
  $$,
  'P0001',
  'SYSTEM_CATEGORY_READONLY',
  'system categories cannot be deleted'
);

select is(
  public.library_rename_category(
    '16000000-0000-0000-0000-000000000001',
    '36000000-0000-0000-0000-000000000003',
    '66000000-0000-0000-0000-000000000007',
    '{"name":"새 정리함"}'::jsonb,
    '새 정리함'
  ) #>> '{category,name}',
  '새 정리함',
  'a custom category keeps its ID when renamed'
);

select is(
  (
    select pg_catalog.jsonb_build_object(
      'version', item.version,
      'text_revision', item.text_revision,
      'categories', search_record.normalized_fields ->> 'categories',
      'job_count', (
        select count(*)::integer
        from public.processing_jobs as job
        where job.owner_id = item.owner_id
          and job.item_id = item.id
      )
    )
    from public.items as item
    join public.item_search as search_record
      on search_record.owner_id = item.owner_id
      and search_record.item_id = item.id
    where item.owner_id = '16000000-0000-0000-0000-000000000001'
      and item.id = '56000000-0000-0000-0000-000000000003'
  ),
  '{"version":2,"text_revision":1,"categories":"새 정리함","job_count":0}'::jsonb,
  'rename refreshes linked search text and item version without text revision or jobs'
);

select is(
  public.library_delete_category(
    '16000000-0000-0000-0000-000000000001',
    '36000000-0000-0000-0000-000000000003',
    '66000000-0000-0000-0000-000000000008',
    '{}'::jsonb
  ),
  '{"http_status":204}'::jsonb,
  'custom category deletion returns 204 without deleting its item'
);

select is(
  (
    select pg_catalog.jsonb_build_object(
      'version', item.version,
      'text_revision', item.text_revision,
      'categories', search_record.normalized_fields ->> 'categories',
      'link_count', (
        select count(*)::integer
        from public.item_categories as selected
        where selected.owner_id = item.owner_id
          and selected.item_id = item.id
      )
    )
    from public.items as item
    join public.item_search as search_record
      on search_record.owner_id = item.owner_id
      and search_record.item_id = item.id
    where item.owner_id = '16000000-0000-0000-0000-000000000001'
      and item.id = '56000000-0000-0000-0000-000000000003'
  ),
  '{"version":3,"text_revision":1,"categories":"","link_count":0}'::jsonb,
  'delete removes only links and category search text while retaining the item'
);

select is(
  public.library_delete_category(
    '16000000-0000-0000-0000-000000000001',
    '36000000-0000-0000-0000-000000000003',
    '66000000-0000-0000-0000-000000000008',
    '{}'::jsonb
  ),
  '{"http_status":204}'::jsonb,
  'an exact delete replay remains 204 and does not recreate the category'
);

select throws_ok(
  $$
    select public.library_rename_category(
      '16000000-0000-0000-0000-000000000001',
      '36000000-0000-0000-0000-000000000005',
      '66000000-0000-0000-0000-000000000009',
      '{"name":"침범"}'::jsonb,
      '침범'
    )
  $$,
  'P0001',
  'CATEGORY_NOT_FOUND',
  'a foreign category is indistinguishable from a missing category'
);

select is(
  pg_catalog.jsonb_build_object(
    'status', response -> 'http_status',
    'version', response #> '{item,version}',
    'text_revision', response #> '{item,text_revision}',
    'dismissed', response #> '{item,cue_prompt_dismissed}'
  ),
  '{"status":200,"version":4,"text_revision":1,"dismissed":true}'::jsonb,
  'cue dismissal records the current revision and increments item version only'
)
from (
  select public.library_cue_dismiss(
    '16000000-0000-0000-0000-000000000001',
    '56000000-0000-0000-0000-000000000003',
    '66000000-0000-0000-0000-000000000010',
    '{"expected_version":3,"text_revision":1}'::jsonb
  ) as response
) as dismissed;

select is(
  public.library_cue_dismiss(
    '16000000-0000-0000-0000-000000000001',
    '56000000-0000-0000-0000-000000000001',
    '66000000-0000-0000-0000-000000000011',
    '{"expected_version":99,"text_revision":1}'::jsonb
  ),
  '{"http_status":409,"error_code":"VERSION_CONFLICT"}'::jsonb,
  'cue version conflicts are returned and committed as identity receipts'
);

update public.items
set version = version + 1
where owner_id = '16000000-0000-0000-0000-000000000001'
  and id = '56000000-0000-0000-0000-000000000001';

select is(
  public.library_cue_dismiss(
    '16000000-0000-0000-0000-000000000001',
    '56000000-0000-0000-0000-000000000001',
    '66000000-0000-0000-0000-000000000011',
    '{"expected_version":99,"text_revision":1}'::jsonb
  ),
  '{"http_status":409,"error_code":"VERSION_CONFLICT"}'::jsonb,
  'an exact cue conflict replay remains a conflict after later changes'
);

select throws_ok(
  $$
    select public.library_cue_dismiss(
      '16000000-0000-0000-0000-000000000001',
      '56000000-0000-0000-0000-000000000006',
      '66000000-0000-0000-0000-000000000012',
      '{"expected_version":1,"text_revision":1}'::jsonb
    )
  $$,
  'P0001',
  'ITEM_NOT_FOUND',
  'cue dismissal does not expose a foreign item'
);

select is(
  public.library_reclassify_item(
    '16000000-0000-0000-0000-000000000001',
    '56000000-0000-0000-0000-000000000005',
    '66000000-0000-0000-0000-000000000013',
    '{"expected_version":1}'::jsonb
  ) ->> 'http_status',
  '202',
  'reclassification is accepted for the current version'
);

select is(
  (
    select pg_catalog.jsonb_build_object(
      'version', item.version,
      'text_revision', item.text_revision,
      'metadata_state', item.metadata_state,
      'manual_override', controls.manual_override,
      'classification_state', classification.state,
      'categories', search_record.normalized_fields ->> 'categories',
      'custom_links', (
        select count(*)::integer
        from public.item_categories as selected
        where selected.owner_id = item.owner_id
          and selected.item_id = item.id
          and selected.category_id = '36000000-0000-0000-0000-000000000002'
      ),
      'system_links', (
        select count(*)::integer
        from public.item_categories as selected
        where selected.owner_id = item.owner_id
          and selected.item_id = item.id
          and selected.category_id = '36000000-0000-0000-0000-000000000001'
      ),
      'active_jobs', (
        select count(*)::integer
        from public.processing_jobs as job
        where job.owner_id = item.owner_id
          and job.item_id = item.id
          and job.kind = 'classify'
          and job.state in ('queued', 'running', 'retry')
      ),
      'metadata_jobs', (
        select count(*)::integer
        from public.processing_jobs as job
        where job.owner_id = item.owner_id
          and job.item_id = item.id
          and job.kind = 'metadata'
      )
    )
    from public.items as item
    join public.item_search as search_record
      on search_record.owner_id = item.owner_id
      and search_record.item_id = item.id
    join public.item_classification as classification
      on classification.owner_id = item.owner_id
      and classification.item_id = item.id
    join public.item_category_controls as controls
      on controls.owner_id = item.owner_id
      and controls.item_id = item.id
    where item.owner_id = '16000000-0000-0000-0000-000000000001'
      and item.id = '56000000-0000-0000-0000-000000000005'
  ),
  '{"version":2,"text_revision":1,"metadata_state":"unsupported","manual_override":false,"classification_state":"pending","categories":"내 분류","custom_links":1,"system_links":0,"active_jobs":1,"metadata_jobs":0}'::jsonb,
  'reclassify preserves custom links, clears systems, unlocks manual state, and queues only classification'
);

select is(
  (
    select count(*)::integer
    from public.processing_jobs as job
    where job.owner_id = '16000000-0000-0000-0000-000000000001'
      and job.item_id = '56000000-0000-0000-0000-000000000005'
      and job.kind = 'classify'
  ),
  1,
  'the same reclassify request ID does not duplicate work'
)
from (
  select public.library_reclassify_item(
    '16000000-0000-0000-0000-000000000001',
    '56000000-0000-0000-0000-000000000005',
    '66000000-0000-0000-0000-000000000013',
    '{"expected_version":1}'::jsonb
  ) as replay
) as replayed;

select is(
  (
    select pg_catalog.jsonb_build_object(
      'same_job', first_job.id::text = response ->> 'job_id',
      'job_count', (
        select count(*)::integer
        from public.processing_jobs as counted
        where counted.owner_id = first_job.owner_id
          and counted.item_id = first_job.item_id
          and counted.kind = 'classify'
      )
    )
    from public.processing_jobs as first_job
    where first_job.owner_id = '16000000-0000-0000-0000-000000000001'
      and first_job.item_id = '56000000-0000-0000-0000-000000000005'
      and first_job.kind = 'classify'
    order by first_job.created_at, first_job.id
    limit 1
  ),
  '{"same_job":true,"job_count":1}'::jsonb,
  'a different request joins a current active classifier job'
)
from (
  select public.library_reclassify_item(
    '16000000-0000-0000-0000-000000000001',
    '56000000-0000-0000-0000-000000000005',
    '66000000-0000-0000-0000-000000000014',
    '{"expected_version":2}'::jsonb
  ) as response
) as joined;

update public.processing_jobs
set state = 'succeeded',
    updated_at = now()
where owner_id = '16000000-0000-0000-0000-000000000001'
  and item_id = '56000000-0000-0000-0000-000000000005'
  and kind = 'classify'
  and state in ('queued', 'retry');

select is(
  public.library_reclassify_item(
    '16000000-0000-0000-0000-000000000001',
    '56000000-0000-0000-0000-000000000005',
    '66000000-0000-0000-0000-000000000015',
    '{"expected_version":3}'::jsonb
  ) ->> 'http_status',
  '202',
  'a new request after terminal completion is accepted'
);

select is(
  (
    select count(*)::integer
    from public.processing_jobs as job
    where job.owner_id = '16000000-0000-0000-0000-000000000001'
      and job.item_id = '56000000-0000-0000-0000-000000000005'
      and job.kind = 'classify'
  ),
  2,
  'a new request after terminal completion creates a new classifier job'
);

select is(
  public.library_reclassify_item(
    '16000000-0000-0000-0000-000000000001',
    '56000000-0000-0000-0000-000000000005',
    '66000000-0000-0000-0000-000000000016',
    '{"expected_version":1}'::jsonb
  ),
  '{"http_status":409,"error_code":"VERSION_CONFLICT"}'::jsonb,
  'reclassify version conflicts are committed without changing links or jobs'
);

select throws_ok(
  $$
    select public.library_reclassify_item(
      '16000000-0000-0000-0000-000000000001',
      '56000000-0000-0000-0000-000000000006',
      '66000000-0000-0000-0000-000000000017',
      '{"expected_version":1}'::jsonb
    )
  $$,
  'P0001',
  'ITEM_NOT_FOUND',
  'reclassify does not expose a foreign item'
);

select is(
  (
    select pg_catalog.jsonb_build_object(
      'method_path', request.method_path,
      'response_code', request.response_code,
      'body', request.response_body,
      'contains_name', request.response_body ? 'name',
      'contains_note', request.response_body ? 'note'
    )
    from public.api_requests as request
    where request.owner_id = '16000000-0000-0000-0000-000000000001'
      and request.request_id = '66000000-0000-0000-0000-000000000008'
  ),
  '{"method_path":"DELETE /categories/36000000-0000-0000-0000-000000000003","response_code":204,"body":{"category_id":"36000000-0000-0000-0000-000000000003","http_status":204},"contains_name":false,"contains_note":false}'::jsonb,
  'mutation receipts contain only result IDs and status, never names or notes'
);

select * from finish();
rollback;
