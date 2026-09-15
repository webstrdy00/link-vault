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
    '11000000-0000-0000-0000-000000000001',
    'authenticated',
    'authenticated',
    'item-a@example.test',
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
    '11000000-0000-0000-0000-000000000002',
    'authenticated',
    'authenticated',
    'item-b@example.test',
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
  ('11000000-0000-0000-0000-000000000001', true, now()),
  ('11000000-0000-0000-0000-000000000002', true, now());

insert into public.profiles (id, state)
values
  ('11000000-0000-0000-0000-000000000001', 'active'),
  ('11000000-0000-0000-0000-000000000002', 'active');

insert into public.library_usage (owner_id)
values
  ('11000000-0000-0000-0000-000000000001'),
  ('11000000-0000-0000-0000-000000000002');

insert into public.categories (id, owner_id, name, normalized_name, kind)
values
  ('31000000-0000-0000-0000-000000000001', '11000000-0000-0000-0000-000000000001', '여행', '여행', 'custom'),
  ('31000000-0000-0000-0000-000000000002', '11000000-0000-0000-0000-000000000001', '음식', '음식', 'custom'),
  ('31000000-0000-0000-0000-000000000003', '11000000-0000-0000-0000-000000000001', '업무', '업무', 'custom'),
  ('31000000-0000-0000-0000-000000000004', '11000000-0000-0000-0000-000000000001', '쇼핑', '쇼핑', 'custom'),
  ('31000000-0000-0000-0000-000000000005', '11000000-0000-0000-0000-000000000001', '도구', '도구', 'custom'),
  ('31000000-0000-0000-0000-000000000006', '11000000-0000-0000-0000-000000000001', '생활', '생활', 'custom'),
  ('32000000-0000-0000-0000-000000000001', '11000000-0000-0000-0000-000000000002', '상대분류', '상대분류', 'custom');

create temporary table item_test_inputs (
  input_name text primary key,
  body jsonb not null,
  prepared jsonb not null,
  item_id uuid
);

with input_specs(
  input_name,
  raw_url,
  normalized_url,
  title,
  note,
  shared_text,
  category_ids,
  normalized_categories,
  metadata_allowed
) as (
  values
    (
      'first',
      'HTTPS://Example.COM/posts/one',
      'https://example.com/posts/one',
      '첫 제목',
      '원본 메모',
      '원본 공유문',
      '[]'::jsonb,
      '',
      true
    ),
    (
      'duplicate',
      'https://example.com:443/posts/one',
      'https://example.com/posts/one',
      '덮어쓰면 안 되는 제목',
      '덮어쓰면 안 되는 메모',
      '덮어쓰면 안 되는 공유문',
      '[]'::jsonb,
      '',
      true
    ),
    (
      'manual',
      'https://example.com/posts/manual',
      'https://example.com/posts/manual',
      '직접 분류한 제목',
      '직접 분류 메모',
      '직접 분류 공유문',
      '["31000000-0000-0000-0000-000000000001"]'::jsonb,
      '',
      false
    ),
    (
      'manual_duplicate',
      'https://example.com/posts/manual',
      'https://example.com/posts/manual',
      '중복 제목',
      '중복 메모',
      '중복 공유문',
      '[]'::jsonb,
      '',
      false
    ),
    (
      'foreign_category',
      'https://example.com/posts/foreign',
      'https://example.com/posts/foreign',
      '타인 분류',
      '타인 분류 메모',
      '타인 분류 공유문',
      '["32000000-0000-0000-0000-000000000001"]'::jsonb,
      '상대분류',
      false
    ),
    (
      'too_many_categories',
      'https://example.com/posts/category-limit',
      'https://example.com/posts/category-limit',
      '분류 초과',
      '분류 초과 메모',
      '분류 초과 공유문',
      '["31000000-0000-0000-0000-000000000001","31000000-0000-0000-0000-000000000002","31000000-0000-0000-0000-000000000003","31000000-0000-0000-0000-000000000004","31000000-0000-0000-0000-000000000005","31000000-0000-0000-0000-000000000006"]'::jsonb,
      '여행 음식 업무 쇼핑 도구 생활',
      false
    ),
    (
      'member_b',
      'https://example.com/posts/member-b',
      'https://example.com/posts/member-b',
      '회원 B 제목',
      '회원 B 메모',
      '회원 B 공유문',
      '[]'::jsonb,
      '',
      false
    ),
    (
      'page_one',
      'https://example.com/posts/page-one',
      'https://example.com/posts/page-one',
      '페이지 하나',
      '페이지 하나 메모',
      '페이지 하나 공유문',
      '[]'::jsonb,
      '',
      false
    ),
    (
      'page_two',
      'https://example.com/posts/page-two',
      'https://example.com/posts/page-two',
      '페이지 둘',
      '페이지 둘 메모',
      '페이지 둘 공유문',
      '[]'::jsonb,
      '',
      false
    ),
    (
      'page_three',
      'https://example.com/posts/page-three',
      'https://example.com/posts/page-three',
      '페이지 셋',
      '페이지 셋 메모',
      '페이지 셋 공유문',
      '[]'::jsonb,
      '',
      false
    ),
    (
      'item_limit',
      'https://example.com/posts/item-limit',
      'https://example.com/posts/item-limit',
      '한도 제목',
      '한도 메모',
      '한도 공유문',
      '[]'::jsonb,
      '',
      false
    ),
    (
      'rate_limit',
      'https://example.com/posts/rate-limit',
      'https://example.com/posts/rate-limit',
      '속도 제목',
      '속도 메모',
      '속도 공유문',
      '[]'::jsonb,
      '',
      false
    )
)
insert into item_test_inputs (input_name, body, prepared)
select
  input.input_name,
  pg_catalog.jsonb_build_object(
    'url', input.raw_url,
    'title', input.title,
    'note', input.note,
    'shared_text', input.shared_text,
    'category_ids', input.category_ids
  ),
  pg_catalog.jsonb_build_object(
    'normalized_url', input.normalized_url,
    'url_hash', pg_catalog.encode(
      extensions.digest(pg_catalog.convert_to(input.normalized_url, 'UTF8'), 'sha256'),
      'hex'
    ),
    'source', 'other',
    'display_fallback', 'example.com',
    'normalized_fields', pg_catalog.jsonb_build_object(
      'user_title', pg_catalog.lower(normalize(input.title, NFKC)),
      'fetched_title', '',
      'note', pg_catalog.lower(normalize(input.note, NFKC)),
      'ocr', '',
      'shared', pg_catalog.lower(normalize(input.shared_text, NFKC)),
      'description', '',
      'body', '',
      'categories', input.normalized_categories,
      'url', pg_catalog.lower(normalize(input.raw_url, NFKC))
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
    'metadata_allowed', input.metadata_allowed
  )
from input_specs as input;

grant select on table item_test_inputs to authenticated, service_role;

select ok(
  (
    select count(*) = 8 and bool_and(class.relrowsecurity)
    from pg_class as class
    join pg_namespace as namespace on namespace.oid = class.relnamespace
    where namespace.nspname = 'public'
      and class.relname in (
        'items',
        'item_search',
        'item_classification',
        'item_category_controls',
        'item_categories',
        'processing_jobs',
        'api_requests',
        'api_rate_buckets'
      )
  ),
  'every M1 item table enables RLS'
);

select ok(
  (
    select installed_function.prosecdef
      and exists (
        select 1
        from unnest(installed_function.proconfig) as setting
        where setting like 'search_path=%'
      )
    from pg_proc as installed_function
    where installed_function.oid = 'public.library_create_item(uuid,uuid,jsonb,jsonb)'::regprocedure
  ),
  'the creation RPC is SECURITY DEFINER with a fixed search_path'
);

select ok(
  has_function_privilege(
    'service_role',
    'public.library_create_item(uuid,uuid,jsonb,jsonb)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated',
    'public.library_create_item(uuid,uuid,jsonb,jsonb)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'public.library_create_item(uuid,uuid,jsonb,jsonb)',
    'EXECUTE'
  ),
  'only service_role can execute item creation'
);

select ok(
  has_function_privilege(
    'authenticated',
    'public.library_list_items(integer,integer)',
    'EXECUTE'
  )
  and has_function_privilege(
    'authenticated',
    'public.library_get_item(uuid)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'public.library_list_items(integer,integer)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'public.library_get_item(uuid)',
    'EXECUTE'
  ),
  'only authenticated callers can execute item read RPCs'
);

select ok(
  not has_schema_privilege('authenticated', 'private', 'USAGE')
  and not has_function_privilege(
    'authenticated',
    'private.library_item_json(uuid,uuid,boolean)',
    'EXECUTE'
  ),
  'the row JSON helper is not callable by authenticated members'
);

select ok(
  not has_table_privilege('authenticated', 'public.items', 'INSERT')
  and not has_table_privilege('authenticated', 'public.items', 'UPDATE')
  and not has_table_privilege('authenticated', 'public.items', 'DELETE')
  and not has_table_privilege('authenticated', 'public.item_search', 'INSERT')
  and not has_table_privilege('authenticated', 'public.item_categories', 'INSERT')
  and not has_table_privilege('authenticated', 'public.processing_jobs', 'SELECT')
  and not has_table_privilege('authenticated', 'public.api_requests', 'SELECT'),
  'members have no direct item writes or internal-record reads'
);

select ok(
  has_table_privilege('service_role', 'public.items', 'INSERT')
  and has_table_privilege('service_role', 'public.item_search', 'INSERT')
  and has_table_privilege('service_role', 'public.processing_jobs', 'INSERT')
  and has_table_privilege('service_role', 'public.api_requests', 'INSERT'),
  'service_role table privileges are explicit'
);

set local role service_role;

select is(
  public.library_create_item(
    '11000000-0000-0000-0000-000000000001',
    '41000000-0000-0000-0000-000000000001',
    (select body from item_test_inputs where input_name = 'first'),
    (select prepared from item_test_inputs where input_name = 'first')
  ) ->> 'http_status',
  '201',
  'a valid server-prepared item is created with status 201'
);

select is(
  public.library_create_item(
    '11000000-0000-0000-0000-000000000001',
    '41000000-0000-0000-0000-000000000001',
    (select body from item_test_inputs where input_name = 'first'),
    (select prepared from item_test_inputs where input_name = 'first')
  ) ->> 'duplicate',
  'false',
  'an exact request replay preserves the original non-duplicate result'
);

select ok(
  (
    public.library_create_item(
      '11000000-0000-0000-0000-000000000001',
      '41000000-0000-0000-0000-000000000001',
      (select body from item_test_inputs where input_name = 'first'),
      (select prepared from item_test_inputs where input_name = 'first')
    ) -> 'item'
  ) ?& array[
    'id',
    'version',
    'url',
    'display_title',
    'source',
    'note_excerpt',
    'category_refs',
    'has_attachment',
    'metadata_state',
    'ocr_state',
    'classification_state',
    'cue_state',
    'cue_flags',
    'created_at',
    'updated_at',
    'user_title',
    'fetched_title',
    'shared_text',
    'description',
    'body_text',
    'note',
    'extraction_meta',
    'active_asset',
    'search_version',
    'rules_version',
    'classification_reasons',
    'cue_prompt_dismissed'
  ],
  'creation returns the contracted detail fields'
);

reset role;

update item_test_inputs
set item_id = (
  select item.id
  from public.items as item
  where item.owner_id = '11000000-0000-0000-0000-000000000001'
    and item.normalized_url = 'https://example.com/posts/one'
)
where input_name in ('first', 'duplicate');

select is(
  (
    select pg_catalog.jsonb_build_object(
      'url', item.original_url,
      'title', item.user_title,
      'note', item.note,
      'shared_text', item.shared_text
    )
    from public.items as item
    where item.id = (select item_id from item_test_inputs where input_name = 'first')
  ),
  '{"url":"HTTPS://Example.COM/posts/one","title":"첫 제목","note":"원본 메모","shared_text":"원본 공유문"}'::jsonb,
  'creation preserves all original client text exactly'
);

select is(
  (
    select pg_catalog.jsonb_build_object(
      'version', item.version,
      'text_revision', item.text_revision,
      'metadata_state', item.metadata_state
    )
    from public.items as item
    where item.id = (select item_id from item_test_inputs where input_name = 'first')
  ),
  '{"version":1,"text_revision":1,"metadata_state":"queued"}'::jsonb,
  'a new metadata-eligible item starts at version and revision one with queued metadata'
);

select is(
  (
    select pg_catalog.jsonb_build_object(
      'search_version', search_record.search_version,
      'cue_version', search_record.cue_version,
      'text_revision', search_record.text_revision,
      'cue_state', search_record.cue_state,
      'cue_flags', search_record.cue_flags
    )
    from public.item_search as search_record
    where search_record.item_id = (select item_id from item_test_inputs where input_name = 'first')
  ),
  '{"search_version":"search-v2.0.0","cue_version":"cues-v1.0.0","text_revision":1,"cue_state":"limited","cue_flags":["short_text"]}'::jsonb,
  'search and cue records use the exact sealed versions and prepared state'
);

select is(
  (
    select pg_catalog.jsonb_build_object(
      'rules_version', classification.rules_version,
      'state', classification.state,
      'target_revision', classification.target_revision,
      'reasons', classification.reasons
    )
    from public.item_classification as classification
    where classification.item_id = (select item_id from item_test_inputs where input_name = 'first')
  ),
  '{"rules_version":"rules-v2.0.0","state":"pending","target_revision":1,"reasons":[]}'::jsonb,
  'an unlocked item with text starts with honest pending classification'
);

select is(
  (
    select pg_catalog.jsonb_build_object(
      'manual_override', controls.manual_override,
      'cue_dismissed_revision', controls.cue_dismissed_revision
    )
    from public.item_category_controls as controls
    where controls.item_id = (select item_id from item_test_inputs where input_name = 'first')
  ),
  '{"manual_override":false,"cue_dismissed_revision":null}'::jsonb,
  'POST category_ids empty starts unlocked with no dismissed cue revision'
);

select is(
  (
    select pg_catalog.jsonb_agg(job.kind order by job.kind)
    from public.processing_jobs as job
    where job.item_id = (select item_id from item_test_inputs where input_name = 'first')
      and job.state = 'queued'
      and job.target_revision = 1
  ),
  '["classify","metadata"]'::jsonb,
  'creation atomically queues metadata and classification when both are needed'
);

select throws_ok(
  $$
    insert into public.processing_jobs (
      owner_id,
      item_id,
      kind,
      target_revision,
      state
    )
    values (
      '11000000-0000-0000-0000-000000000001',
      (select item_id from item_test_inputs where input_name = 'first'),
      'metadata',
      1,
      'queued'
    )
  $$,
  '23505',
  null,
  'the active-job key rejects a second active job for the same item kind and revision'
);

select throws_ok(
  $$
    insert into public.processing_jobs (
      owner_id,
      item_id,
      kind,
      target_revision,
      state
    )
    values (
      '11000000-0000-0000-0000-000000000001',
      (select item_id from item_test_inputs where input_name = 'first'),
      'cleanup',
      1,
      'running'
    )
  $$,
  '23514',
  null,
  'a running job cannot exist without both lease fields'
);

update public.processing_jobs
set state = 'succeeded'
where owner_id = '11000000-0000-0000-0000-000000000001'
  and item_id = (select item_id from item_test_inputs where input_name = 'first')
  and kind = 'metadata';

select lives_ok(
  $$
    insert into public.processing_jobs (
      owner_id,
      item_id,
      kind,
      target_revision,
      state
    )
    values (
      '11000000-0000-0000-0000-000000000001',
      (select item_id from item_test_inputs where input_name = 'first'),
      'metadata',
      1,
      'queued'
    )
  $$,
  'a completed job does not block a later active job for the same revision'
);

select is(
  (
    select usage.active_item_count
    from public.library_usage as usage
    where usage.owner_id = '11000000-0000-0000-0000-000000000001'
  ),
  1,
  'creation increments the owner usage counter once'
);

select is(
  (
    select request.response_body
    from public.api_requests as request
    where request.owner_id = '11000000-0000-0000-0000-000000000001'
      and request.request_id = '41000000-0000-0000-0000-000000000001'
  ),
  pg_catalog.jsonb_build_object(
    'item_id', (select item_id from item_test_inputs where input_name = 'first'),
    'duplicate', false,
    'http_status', 201
  ),
  'the idempotency record stores only the item id, duplicate marker, and status'
);

select is(
  (
    select count(*)::integer
    from public.items as item
    where item.owner_id = '11000000-0000-0000-0000-000000000001'
  ),
  1,
  'exact replay creates no second item'
);

set local role service_role;

select throws_ok(
  $$
    select public.library_create_item(
      '11000000-0000-0000-0000-000000000001',
      '41000000-0000-0000-0000-000000000001',
      (select body || '{"note":"본문이 달라짐"}'::jsonb from item_test_inputs where input_name = 'first'),
      (select prepared from item_test_inputs where input_name = 'first')
    )
  $$,
  'P0001',
  'IDEMPOTENCY_MISMATCH',
  'the same request ID with a different raw body is rejected'
);

select is(
  public.library_create_item(
    '11000000-0000-0000-0000-000000000001',
    '41000000-0000-0000-0000-000000000002',
    (select body from item_test_inputs where input_name = 'duplicate'),
    (select prepared from item_test_inputs where input_name = 'duplicate')
  ) ->> 'http_status',
  '200',
  'a new request for the same normalized URL returns status 200'
);

select is(
  public.library_create_item(
    '11000000-0000-0000-0000-000000000001',
    '41000000-0000-0000-0000-000000000002',
    (select body from item_test_inputs where input_name = 'duplicate'),
    (select prepared from item_test_inputs where input_name = 'duplicate')
  ) ->> 'duplicate',
  'true',
  'the normalized URL match is marked duplicate'
);

reset role;

select is(
  (
    select pg_catalog.jsonb_build_object(
      'url', item.original_url,
      'title', item.user_title,
      'note', item.note,
      'shared_text', item.shared_text
    )
    from public.items as item
    where item.id = (select item_id from item_test_inputs where input_name = 'first')
  ),
  '{"url":"HTTPS://Example.COM/posts/one","title":"첫 제목","note":"원본 메모","shared_text":"원본 공유문"}'::jsonb,
  'a duplicate URL never overwrites the original URL or user content'
);

select is(
  (
    select usage.active_item_count
    from public.library_usage as usage
    where usage.owner_id = '11000000-0000-0000-0000-000000000001'
  ),
  1,
  'a duplicate URL does not consume another item slot'
);

select is(
  (
    select sum(bucket.request_count)::integer
    from public.api_rate_buckets as bucket
    where bucket.owner_id = '11000000-0000-0000-0000-000000000001'
      and bucket.operation = 'create_item'
  ),
  1,
  'replay and normalized URL duplicate checks do not consume creation rate'
);

set local role service_role;

select is(
  public.library_create_item(
    '11000000-0000-0000-0000-000000000001',
    '41000000-0000-0000-0000-000000000003',
    (select body from item_test_inputs where input_name = 'manual'),
    (select prepared from item_test_inputs where input_name = 'manual')
  ) ->> 'http_status',
  '201',
  'a valid manually categorized item is created'
);

reset role;

update item_test_inputs
set item_id = (
  select item.id
  from public.items as item
  where item.owner_id = '11000000-0000-0000-0000-000000000001'
    and item.normalized_url = 'https://example.com/posts/manual'
)
where input_name in ('manual', 'manual_duplicate');

select is(
  (
    select pg_catalog.jsonb_agg(
      pg_catalog.jsonb_build_object(
        'category_id', selected.category_id,
        'origin', selected.origin
      )
    )
    from public.item_categories as selected
    where selected.item_id = (select item_id from item_test_inputs where input_name = 'manual')
  ),
  '[{"category_id":"31000000-0000-0000-0000-000000000001","origin":"manual"}]'::jsonb,
  'selected owned categories are linked with manual origin'
);

select is(
  (
    select search_record.normalized_fields ->> 'categories'
    from public.item_search as search_record
    where search_record.owner_id = '11000000-0000-0000-0000-000000000001'
      and search_record.item_id = (
        select item_id
        from item_test_inputs
        where input_name = 'manual'
      )
  ),
  '여행',
  'manual category search text is built from the validated stored category name'
);

select is(
  (
    select pg_catalog.jsonb_build_object(
      'manual_override', controls.manual_override,
      'classification_state', classification.state
    )
    from public.item_category_controls as controls
    join public.item_classification as classification
      on classification.owner_id = controls.owner_id
      and classification.item_id = controls.item_id
    where controls.item_id = (select item_id from item_test_inputs where input_name = 'manual')
  ),
  '{"manual_override":true,"classification_state":"manual"}'::jsonb,
  'an explicit POST category selection locks automatic classification honestly'
);

select is(
  (
    select count(*)::integer
    from public.processing_jobs as job
    where job.item_id = (select item_id from item_test_inputs where input_name = 'manual')
  ),
  0,
  'a manual item with unsupported metadata queues no background work'
);

set local role service_role;

select throws_ok(
  $$
    select public.library_create_item(
      '11000000-0000-0000-0000-000000000001',
      '41000000-0000-0000-0000-000000000004',
      (select body from item_test_inputs where input_name = 'foreign_category'),
      (select prepared from item_test_inputs where input_name = 'foreign_category')
    )
  $$,
  'P0001',
  'INVALID_CATEGORY_IDS',
  'an otherwise real category owned by another member is rejected'
);

select throws_ok(
  $$
    select public.library_create_item(
      '11000000-0000-0000-0000-000000000001',
      '41000000-0000-0000-0000-000000000005',
      (select body from item_test_inputs where input_name = 'too_many_categories'),
      (select prepared from item_test_inputs where input_name = 'too_many_categories')
    )
  $$,
  'P0001',
  'CATEGORY_LIMIT_REACHED',
  'more than five selected categories is rejected'
);

select throws_ok(
  $$
    select public.library_create_item(
      '11000000-0000-0000-0000-000000000001',
      '41000000-0000-0000-0000-000000000006',
      (select body || pg_catalog.jsonb_build_object('title', pg_catalog.repeat('가', 301)) from item_test_inputs where input_name = 'item_limit'),
      (select prepared from item_test_inputs where input_name = 'item_limit')
    )
  $$,
  'P0001',
  'INVALID_BODY',
  'a title longer than 300 characters is rejected'
);

select throws_ok(
  $$
    select public.library_create_item(
      '11000000-0000-0000-0000-000000000001',
      '41000000-0000-0000-0000-000000000007',
      (select body || pg_catalog.jsonb_build_object('note', pg_catalog.repeat('가', 4001)) from item_test_inputs where input_name = 'item_limit'),
      (select prepared from item_test_inputs where input_name = 'item_limit')
    )
  $$,
  'P0001',
  'INVALID_BODY',
  'a note longer than 4000 characters is rejected'
);

select throws_ok(
  $$
    select public.library_create_item(
      '11000000-0000-0000-0000-000000000001',
      '41000000-0000-0000-0000-000000000008',
      (select body || pg_catalog.jsonb_build_object('shared_text', pg_catalog.repeat('가', 4001)) from item_test_inputs where input_name = 'item_limit'),
      (select prepared from item_test_inputs where input_name = 'item_limit')
    )
  $$,
  'P0001',
  'INVALID_BODY',
  'shared text longer than 4000 characters is rejected'
);

select throws_ok(
  $$
    select public.library_create_item(
      '11000000-0000-0000-0000-000000000001',
      '41000000-0000-0000-0000-000000000009',
      (select body || pg_catalog.jsonb_build_object('url', 'https://' || pg_catalog.repeat('a', 4089)) from item_test_inputs where input_name = 'item_limit'),
      (select prepared from item_test_inputs where input_name = 'item_limit')
    )
  $$,
  'P0001',
  'INVALID_BODY',
  'an original URL longer than 4096 characters is rejected'
);

select throws_ok(
  $$
    select public.library_create_item(
      '11000000-0000-0000-0000-000000000001',
      '41000000-0000-0000-0000-000000000010',
      (select body from item_test_inputs where input_name = 'item_limit'),
      (select prepared || '{"unexpected":true}'::jsonb from item_test_inputs where input_name = 'item_limit')
    )
  $$,
  'P0001',
  'INVALID_PREPARED',
  'prepared server data must have the exact contracted top-level shape'
);

select is(
  public.library_create_item(
    '11000000-0000-0000-0000-000000000002',
    '42000000-0000-0000-0000-000000000001',
    (select body from item_test_inputs where input_name = 'member_b'),
    (select prepared from item_test_inputs where input_name = 'member_b')
  ) ->> 'http_status',
  '201',
  'member B can receive a server-created item independently'
);

reset role;

update item_test_inputs
set item_id = (
  select item.id
  from public.items as item
  where item.owner_id = '11000000-0000-0000-0000-000000000002'
    and item.normalized_url = 'https://example.com/posts/member-b'
)
where input_name = 'member_b';

set local role authenticated;
set local "request.jwt.claim.sub" = '11000000-0000-0000-0000-000000000001';

select is(
  (select count(*)::integer from public.items),
  2,
  'RLS lets member A directly read only member A active items'
);

select is(
  (
    select count(*)::integer
    from public.items
    where owner_id = '11000000-0000-0000-0000-000000000002'
  ),
  0,
  'RLS hides member B item rows from member A even with the owner UUID'
);

select ok(
  (select count(*) = 2 from public.item_search)
  and (
    select count(*) = 0
    from public.item_search
    where owner_id = '11000000-0000-0000-0000-000000000002'
  )
  and (select count(*) = 2 from public.item_classification)
  and (select count(*) = 2 from public.item_category_controls),
  'RLS applies the same A/B owner boundary to every item-derived record'
);

select throws_ok(
  format(
    'select public.library_get_item(%L::uuid)',
    (select item_id from item_test_inputs where input_name = 'member_b')
  ),
  'P0001',
  'ITEM_NOT_FOUND',
  'detail returns the same not-found error for an inaccessible item'
);

select throws_ok(
  $$
    insert into public.items (
      owner_id,
      original_url,
      normalized_url,
      url_hash,
      source,
      display_fallback,
      metadata_state
    )
    values (
      '11000000-0000-0000-0000-000000000001',
      'https://example.com/direct',
      'https://example.com/direct',
      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
      'other',
      'example.com',
      'unsupported'
    )
  $$,
  '42501',
  null,
  'an authenticated member cannot directly insert an item'
);

select throws_ok(
  $$ update public.items set note = '직접 수정' $$,
  '42501',
  null,
  'an authenticated member cannot directly update an item'
);

select throws_ok(
  $$
    select public.library_create_item(
      '11000000-0000-0000-0000-000000000001',
      '41000000-0000-0000-0000-000000000011',
      (select body from item_test_inputs where input_name = 'item_limit'),
      (select prepared from item_test_inputs where input_name = 'item_limit')
    )
  $$,
  '42501',
  null,
  'an authenticated database role cannot invoke service-only creation'
);

reset role;
set local role authenticated;
set local "request.jwt.claim.sub" = '11000000-0000-0000-0000-000000000002';

select is(
  public.library_get_item(
    (select item_id from item_test_inputs where input_name = 'member_b')
  ) ->> 'user_title',
  '회원 B 제목',
  'member B can read member B detail through the caller-JWT RPC'
);

select is(
  (select count(*)::integer from public.items),
  1,
  'RLS gives member B no visibility into member A items'
);

reset role;
set local role anon;
set local "request.jwt.claim.sub" = '';

select throws_ok(
  $$ select public.library_list_items() $$,
  '42501',
  null,
  'anon cannot execute the list RPC'
);

select throws_ok(
  $$ select public.library_get_item('00000000-0000-0000-0000-000000000001') $$,
  '42501',
  null,
  'anon cannot execute the detail RPC'
);

reset role;
set local role service_role;

select lives_ok(
  $$
    select public.library_create_item(
      '11000000-0000-0000-0000-000000000001',
      '43000000-0000-0000-0000-000000000001',
      (select body from item_test_inputs where input_name = 'page_one'),
      (select prepared from item_test_inputs where input_name = 'page_one')
    )
  $$,
  'the first pagination fixture is created'
);

select lives_ok(
  $$
    select public.library_create_item(
      '11000000-0000-0000-0000-000000000001',
      '43000000-0000-0000-0000-000000000002',
      (select body from item_test_inputs where input_name = 'page_two'),
      (select prepared from item_test_inputs where input_name = 'page_two')
    )
  $$,
  'the second pagination fixture is created'
);

select lives_ok(
  $$
    select public.library_create_item(
      '11000000-0000-0000-0000-000000000001',
      '43000000-0000-0000-0000-000000000003',
      (select body from item_test_inputs where input_name = 'page_three'),
      (select prepared from item_test_inputs where input_name = 'page_three')
    )
  $$,
  'the third pagination fixture is created'
);

reset role;

update item_test_inputs as input
set item_id = item.id
from public.items as item
where item.owner_id = '11000000-0000-0000-0000-000000000001'
  and (
    (input.input_name = 'page_one' and item.normalized_url = 'https://example.com/posts/page-one')
    or (input.input_name = 'page_two' and item.normalized_url = 'https://example.com/posts/page-two')
    or (input.input_name = 'page_three' and item.normalized_url = 'https://example.com/posts/page-three')
  );

update public.items
set created_at = case normalized_url
  when 'https://example.com/posts/one' then '2026-09-13 00:00:00+00'::timestamptz
  when 'https://example.com/posts/manual' then '2026-09-13 01:00:00+00'::timestamptz
  when 'https://example.com/posts/page-one' then '2026-09-13 02:00:00+00'::timestamptz
  when 'https://example.com/posts/page-three' then '2026-09-13 03:00:00+00'::timestamptz
  when 'https://example.com/posts/page-two' then '2026-09-13 04:00:00+00'::timestamptz
end
where owner_id = '11000000-0000-0000-0000-000000000001';

set local role authenticated;
set local "request.jwt.claim.sub" = '11000000-0000-0000-0000-000000000001';

select is(
  public.library_list_items(2, 0) #>> '{items,0,id}',
  (select item_id::text from item_test_inputs where input_name = 'page_two'),
  'the first page starts with the newest created_at item'
);

select is(
  public.library_list_items(2, 0) #>> '{items,1,id}',
  (select item_id::text from item_test_inputs where input_name = 'page_three'),
  'the first page preserves newest-first ordering'
);

select is(
  public.library_list_items(2, 0) ->> 'has_more',
  'true',
  'has_more is true when a row exists beyond the page limit'
);

select is(
  public.library_list_items(2, 2) #>> '{items,0,id}',
  (select item_id::text from item_test_inputs where input_name = 'page_one'),
  'offset is applied after the stable newest-first ordering'
);

select is(
  public.library_list_items(2, 4) ->> 'has_more',
  'false',
  'has_more is false on the final page'
);

select ok(
  not (public.library_list_items(1, 0) #> '{items,0}') ? 'note'
  and (public.library_list_items(1, 0) #> '{items,0}') ? 'note_excerpt'
  and (public.library_list_items(1, 0) #> '{items,0}') @> '{"has_attachment":false,"ocr_state":"not_requested","match_type":null}'::jsonb,
  'list summaries omit full note content and report honest M1 attachment/OCR/search values'
);

select ok(
  public.library_get_item(
    (select item_id from item_test_inputs where input_name = 'page_two')
  ) @> '{"active_asset":null,"has_attachment":false,"ocr_state":"not_requested","search_version":"search-v2.0.0","rules_version":"rules-v2.0.0"}'::jsonb,
  'detail reports stored search and classification versions without fake asset state'
);

select throws_ok(
  $$ select public.library_list_items(0, 0) $$,
  'P0001',
  'INVALID_BODY',
  'list rejects a zero limit'
);

select throws_ok(
  $$ select public.library_list_items(51, 0) $$,
  'P0001',
  'INVALID_BODY',
  'list rejects a limit above fifty'
);

select throws_ok(
  $$ select public.library_list_items(20, -1) $$,
  'P0001',
  'INVALID_BODY',
  'list rejects a negative offset'
);

reset role;

update public.items
set deleted_at = now()
where id = (select item_id from item_test_inputs where input_name = 'first');

set local role service_role;

select throws_ok(
  $$
    select public.library_create_item(
      '11000000-0000-0000-0000-000000000001',
      '41000000-0000-0000-0000-000000000001',
      (select body from item_test_inputs where input_name = 'first'),
      (select prepared from item_test_inputs where input_name = 'first')
    )
  $$,
  'P0001',
  'ITEM_DELETED',
  'admin-simulated deletion makes an old create request replay return the 410 marker'
);

reset role;
set local role authenticated;
set local "request.jwt.claim.sub" = '11000000-0000-0000-0000-000000000001';

select is(
  (
    select count(*)::integer
    from public.items
    where id = (select item_id from item_test_inputs where input_name = 'first')
  ),
  0,
  'RLS hides an admin-simulated deleted item immediately'
);

select throws_ok(
  format(
    'select public.library_get_item(%L::uuid)',
    (select item_id from item_test_inputs where input_name = 'first')
  ),
  'P0001',
  'ITEM_NOT_FOUND',
  'detail reports a deleted item as inaccessible'
);

reset role;

update public.library_usage
set active_item_count = 100
where owner_id = '11000000-0000-0000-0000-000000000001';

set local role service_role;

select is(
  public.library_create_item(
    '11000000-0000-0000-0000-000000000001',
    '44000000-0000-0000-0000-000000000001',
    (select body from item_test_inputs where input_name = 'item_limit'),
    (select prepared from item_test_inputs where input_name = 'item_limit')
  ),
  '{"http_status":409,"error_code":"ITEM_LIMIT_REACHED"}'::jsonb,
  'the locked owner counter commits the 100 active-item limit conflict'
);

reset role;

select ok(
  not exists (
    select 1
    from public.items
    where normalized_url = 'https://example.com/posts/item-limit'
  )
  and exists (
    select 1
    from public.api_requests
    where request_id = '44000000-0000-0000-0000-000000000001'
      and response_code = 409
      and response_body = '{"error_code":"ITEM_LIMIT_REACHED"}'::jsonb
  ),
  'an item-limit conflict leaves no item and stores only its minimal receipt'
);

update public.library_usage
set active_item_count = 5
where owner_id = '11000000-0000-0000-0000-000000000001';

insert into public.api_rate_buckets (owner_id, operation, window_start, request_count)
values (
  '11000000-0000-0000-0000-000000000001',
  'create_item',
  date_trunc('minute', clock_timestamp()),
  10
)
on conflict (owner_id, operation, window_start)
do update set request_count = excluded.request_count;

set local role service_role;

select throws_ok(
  $$
    select public.library_create_item(
      '11000000-0000-0000-0000-000000000001',
      '44000000-0000-0000-0000-000000000002',
      (select body from item_test_inputs where input_name = 'rate_limit'),
      (select prepared from item_test_inputs where input_name = 'rate_limit')
    )
  $$,
  'P0001',
  'RATE_LIMITED',
  'an eleventh actual creation in one minute is rejected with the 429 marker'
);

reset role;

select ok(
  not exists (
    select 1
    from public.items
    where normalized_url = 'https://example.com/posts/rate-limit'
  )
  and not exists (
    select 1
    from public.api_requests
    where request_id = '44000000-0000-0000-0000-000000000002'
  ),
  'a rate-limited transaction leaves no item or successful request record'
);

set local role service_role;

select is(
  public.library_create_item(
    '11000000-0000-0000-0000-000000000001',
    '44000000-0000-0000-0000-000000000003',
    (select body from item_test_inputs where input_name = 'manual_duplicate'),
    (select prepared from item_test_inputs where input_name = 'manual_duplicate')
  ) ->> 'duplicate',
  'true',
  'a duplicate check still succeeds after the creation rate bucket is full'
);

reset role;

select is(
  (
    select bucket.request_count
    from public.api_rate_buckets as bucket
    where bucket.owner_id = '11000000-0000-0000-0000-000000000001'
      and bucket.operation = 'create_item'
      and bucket.window_start = date_trunc('minute', clock_timestamp())
  ),
  10,
  'a duplicate does not increment the full creation rate bucket'
);

select is(
  (
    select count(*)::integer
    from public.api_requests as request
    where request.owner_id = '11000000-0000-0000-0000-000000000001'
      and request.response_body ?| array['url', 'title', 'note', 'shared_text']
  ),
  0,
  'no idempotency response body copies raw user text'
);

select * from finish();
rollback;
