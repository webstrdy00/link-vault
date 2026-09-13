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
    '15000000-0000-0000-0000-000000000001',
    'authenticated',
    'authenticated',
    'edit-a@example.test',
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
    '15000000-0000-0000-0000-000000000002',
    'authenticated',
    'authenticated',
    'edit-b@example.test',
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
  ('15000000-0000-0000-0000-000000000001', true, now()),
  ('15000000-0000-0000-0000-000000000002', true, now());

insert into public.profiles (id, state)
values
  ('15000000-0000-0000-0000-000000000001', 'active'),
  ('15000000-0000-0000-0000-000000000002', 'active');

insert into public.library_usage (owner_id, active_item_count)
values
  ('15000000-0000-0000-0000-000000000001', 9),
  ('15000000-0000-0000-0000-000000000002', 1);

insert into public.categories (id, owner_id, name, kind)
values
  (
    '35000000-0000-0000-0000-000000000001',
    '15000000-0000-0000-0000-000000000001',
    '여행',
    'custom'
  ),
  (
    '35000000-0000-0000-0000-000000000002',
    '15000000-0000-0000-0000-000000000001',
    '음식',
    'custom'
  ),
  (
    '35000000-0000-0000-0000-000000000003',
    '15000000-0000-0000-0000-000000000002',
    '외부',
    'custom'
  );

with item_specs(
  item_id,
  owner_id,
  slug,
  title,
  note,
  shared_text,
  metadata_state
) as (
  values
    (
      '55000000-0000-0000-0000-000000000001'::uuid,
      '15000000-0000-0000-0000-000000000001'::uuid,
      'pending-replay',
      '기존 youtube 제목',
      '기존 메모',
      '분류 입력',
      'queued'
    ),
    (
      '55000000-0000-0000-0000-000000000002'::uuid,
      '15000000-0000-0000-0000-000000000001'::uuid,
      'clear',
      '지울 youtube 제목',
      '지울 메모',
      null,
      'ready'
    ),
    (
      '55000000-0000-0000-0000-000000000003'::uuid,
      '15000000-0000-0000-0000-000000000001'::uuid,
      'manual-omitted',
      '수동 기존 제목',
      'youtube 보존 메모',
      '수동 분류 입력',
      'ready'
    ),
    (
      '55000000-0000-0000-0000-000000000004'::uuid,
      '15000000-0000-0000-0000-000000000001'::uuid,
      'empty-categories',
      '분류 비우기',
      '분류 메모',
      '분류 입력',
      'queued'
    ),
    (
      '55000000-0000-0000-0000-000000000005'::uuid,
      '15000000-0000-0000-0000-000000000001'::uuid,
      'select-categories',
      '분류 선택',
      '선택 메모',
      '선택 입력',
      'unsupported'
    ),
    (
      '55000000-0000-0000-0000-000000000006'::uuid,
      '15000000-0000-0000-0000-000000000001'::uuid,
      'terminal-metadata',
      '완료 메타 제목',
      '완료 메모',
      '완료 분류 입력',
      'ready'
    ),
    (
      '55000000-0000-0000-0000-000000000007'::uuid,
      '15000000-0000-0000-0000-000000000001'::uuid,
      'no-op',
      '같은 제목',
      '같은 메모',
      '같은 분류 입력',
      'partial'
    ),
    (
      '55000000-0000-0000-0000-000000000008'::uuid,
      '15000000-0000-0000-0000-000000000002'::uuid,
      'other-owner',
      '다른 회원 제목',
      '다른 회원 메모',
      '다른 회원 입력',
      'unsupported'
    ),
    (
      '55000000-0000-0000-0000-000000000009'::uuid,
      '15000000-0000-0000-0000-000000000001'::uuid,
      'deleted-replay',
      '삭제 재생 제목',
      '삭제 재생 메모',
      '삭제 재생 입력',
      'failed'
    ),
    (
      '55000000-0000-0000-0000-000000000010'::uuid,
      '15000000-0000-0000-0000-000000000001'::uuid,
      'stale',
      '충돌 제목',
      '충돌 메모',
      '충돌 입력',
      'unsupported'
    )
)
insert into public.items (
  id,
  owner_id,
  original_url,
  normalized_url,
  url_hash,
  source,
  display_fallback,
  shared_text,
  user_title,
  note,
  text_revision,
  version,
  metadata_state
)
select
  spec.item_id,
  spec.owner_id,
  'https://example.test/' || spec.slug,
  'https://example.test/' || spec.slug,
  pg_catalog.encode(
    extensions.digest(
      pg_catalog.convert_to('https://example.test/' || spec.slug, 'UTF8'),
      'sha256'
    ),
    'hex'
  ),
  'other',
  'example.test',
  spec.shared_text,
  spec.title,
  spec.note,
  1,
  case
    when spec.item_id = '55000000-0000-0000-0000-000000000010' then 2
    else 1
  end,
  spec.metadata_state
from item_specs as spec;

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
  1,
  'search-v2.0.0',
  pg_catalog.jsonb_build_object(
    'user_title', pg_catalog.btrim(
      pg_catalog.regexp_replace(
        pg_catalog.lower(normalize(coalesce(item.user_title, ''), NFKC)),
        '[[:space:]]+',
        ' ',
        'g'
      )
    ),
    'fetched_title', '',
    'note', pg_catalog.btrim(
      pg_catalog.regexp_replace(
        pg_catalog.lower(normalize(coalesce(item.note, ''), NFKC)),
        '[[:space:]]+',
        ' ',
        'g'
      )
    ),
    'ocr', '',
    'shared', pg_catalog.btrim(
      pg_catalog.regexp_replace(
        pg_catalog.lower(normalize(coalesce(item.shared_text, ''), NFKC)),
        '[[:space:]]+',
        ' ',
        'g'
      )
    ),
    'description', '',
    'body', '',
    'categories', case
      when item.id in (
        '55000000-0000-0000-0000-000000000003',
        '55000000-0000-0000-0000-000000000004'
      ) then '여행'
      else ''
    end,
    'url', pg_catalog.lower(normalize(item.normalized_url, NFKC))
  ),
  pg_catalog.jsonb_build_object(
    'user_title', case
      when item.id in (
        '55000000-0000-0000-0000-000000000001',
        '55000000-0000-0000-0000-000000000002'
      ) then '["youtube"]'::jsonb
      else '[]'::jsonb
    end,
    'fetched_title', '[]'::jsonb,
    'note', case
      when item.id = '55000000-0000-0000-0000-000000000003'
        then '["youtube"]'::jsonb
      else '[]'::jsonb
    end,
    'ocr', '[]'::jsonb,
    'shared', '[]'::jsonb,
    'description', '[]'::jsonb,
    'body', '[]'::jsonb
  ),
  'cues-v1.0.0',
  'available',
  '[]'::jsonb
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
  1,
  case
    when item.id = '55000000-0000-0000-0000-000000000003' then 'manual'
    else 'automatic'
  end,
  case
    when item.id = '55000000-0000-0000-0000-000000000003'
      then '[]'::jsonb
    else '[{"rule":"fixture"}]'::jsonb
  end
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
  item.id = '55000000-0000-0000-0000-000000000003',
  null
from public.items as item;

insert into public.item_categories (owner_id, item_id, category_id, origin)
values
  (
    '15000000-0000-0000-0000-000000000001',
    '55000000-0000-0000-0000-000000000003',
    '35000000-0000-0000-0000-000000000001',
    'manual'
  ),
  (
    '15000000-0000-0000-0000-000000000001',
    '55000000-0000-0000-0000-000000000004',
    '35000000-0000-0000-0000-000000000001',
    'auto'
  );

insert into public.processing_jobs (
  owner_id,
  item_id,
  kind,
  target_revision,
  state
)
values
  (
    '15000000-0000-0000-0000-000000000001',
    '55000000-0000-0000-0000-000000000001',
    'metadata',
    1,
    'queued'
  ),
  (
    '15000000-0000-0000-0000-000000000001',
    '55000000-0000-0000-0000-000000000001',
    'classify',
    1,
    'queued'
  ),
  (
    '15000000-0000-0000-0000-000000000001',
    '55000000-0000-0000-0000-000000000004',
    'metadata',
    1,
    'queued'
  ),
  (
    '15000000-0000-0000-0000-000000000001',
    '55000000-0000-0000-0000-000000000004',
    'classify',
    1,
    'queued'
  ),
  (
    '15000000-0000-0000-0000-000000000001',
    '55000000-0000-0000-0000-000000000005',
    'classify',
    1,
    'queued'
  );

create function pg_temp.edit_prepared(
  p_item_id uuid,
  p_snapshot_version integer,
  p_title text,
  p_note text,
  p_normalized_categories text
)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select pg_catalog.jsonb_build_object(
    'normalized_url', item.normalized_url,
    'url_hash', item.url_hash,
    'source', item.source,
    'display_fallback', item.display_fallback,
    'normalized_fields', search_record.normalized_fields
      || pg_catalog.jsonb_build_object(
        'user_title', pg_catalog.btrim(
          pg_catalog.regexp_replace(
            pg_catalog.lower(normalize(coalesce(p_title, ''), NFKC)),
            '[[:space:]]+',
            ' ',
            'g'
          )
        ),
        'note', pg_catalog.btrim(
          pg_catalog.regexp_replace(
            pg_catalog.lower(normalize(coalesce(p_note, ''), NFKC)),
            '[[:space:]]+',
            ' ',
            'g'
          )
        ),
        'categories', p_normalized_categories
      ),
    'alias_concepts', search_record.alias_concepts
      || pg_catalog.jsonb_build_object(
        'user_title', '[]'::jsonb,
        'note', '[]'::jsonb
      ),
    'cue_state', 'available',
    'cue_flags', '[]'::jsonb,
    'metadata_allowed', false,
    'snapshot_version', p_snapshot_version
  )
  from public.items as item
  join public.item_search as search_record
    on search_record.owner_id = item.owner_id
    and search_record.item_id = item.id
  where item.id = p_item_id;
$$;

grant execute on function pg_temp.edit_prepared(uuid, integer, text, text, text)
to service_role;

select ok(
  (
    select installed_function.prosecdef
      and exists (
        select 1
        from pg_catalog.unnest(installed_function.proconfig) as setting
        where setting like 'search_path=%'
      )
    from pg_catalog.pg_proc as installed_function
    where installed_function.oid =
      'public.library_update_item(uuid,uuid,uuid,jsonb,jsonb)'::regprocedure
  ),
  'the edit RPC is SECURITY DEFINER with a fixed search_path'
);

select ok(
  has_function_privilege(
    'service_role',
    'public.library_update_item(uuid,uuid,uuid,jsonb,jsonb)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated',
    'public.library_update_item(uuid,uuid,uuid,jsonb,jsonb)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'public.library_update_item(uuid,uuid,uuid,jsonb,jsonb)',
    'EXECUTE'
  ),
  'only service_role can execute item edits'
);

set local role service_role;

select is(
  pg_catalog.jsonb_build_object(
    'http_status', response ->> 'http_status',
    'title', response #>> '{item,user_title}',
    'version', response #>> '{item,version}'
  ),
  '{"http_status":"200","title":"새 제목","version":"2"}'::jsonb,
  'a valid edit returns the current detail DTO'
)
from (
  select public.library_update_item(
      '15000000-0000-0000-0000-000000000001',
      '55000000-0000-0000-0000-000000000001',
      '65000000-0000-0000-0000-000000000001',
      '{"expected_version":1,"title":"새 제목"}'::jsonb,
      pg_temp.edit_prepared(
      '55000000-0000-0000-0000-000000000001',
      1,
      '새 제목',
      '기존 메모',
      ''
    )
  ) as response
) as edit_result;

select is(
  (
    select pg_catalog.jsonb_build_object(
      'title', item.user_title,
      'note', item.note,
      'version', item.version,
      'text_revision', item.text_revision,
      'indexed_title', search_record.normalized_fields ->> 'user_title',
      'indexed_note', search_record.normalized_fields ->> 'note',
      'title_aliases', search_record.alias_concepts -> 'user_title',
      'search_revision', search_record.text_revision,
      'classification_state', classification.state,
      'classification_revision', classification.target_revision
    )
    from public.items as item
    join public.item_search as search_record
      on search_record.owner_id = item.owner_id
      and search_record.item_id = item.id
    join public.item_classification as classification
      on classification.owner_id = item.owner_id
      and classification.item_id = item.id
    where item.id = '55000000-0000-0000-0000-000000000001'
  ),
  '{"title":"새 제목","note":"기존 메모","version":2,"text_revision":2,"indexed_title":"새 제목","indexed_note":"기존 메모","title_aliases":[],"search_revision":2,"classification_state":"pending","classification_revision":2}'::jsonb,
  'a real title change increments both versions, replaces its index and invalidates classification'
);

select is(
  (
    select pg_catalog.jsonb_agg(
      pg_catalog.jsonb_build_object(
        'kind', job.kind,
        'target_revision', job.target_revision,
        'state', job.state
      )
      order by job.kind, job.target_revision
    )
    from public.processing_jobs as job
    where job.item_id = '55000000-0000-0000-0000-000000000001'
  ),
  '[{"kind":"classify","target_revision":1,"state":"cancelled"},{"kind":"classify","target_revision":2,"state":"queued"},{"kind":"metadata","target_revision":1,"state":"cancelled"},{"kind":"metadata","target_revision":2,"state":"queued"}]'::jsonb,
  'a pending metadata edit cancels old work and leaves exactly one latest metadata and classifier job'
);

select is(
  (
    select pg_catalog.jsonb_build_object(
      'method_path', request.method_path,
      'request_hash_matches', request.request_hash = pg_catalog.encode(
        extensions.digest(
          pg_catalog.convert_to(
            request.method_path || pg_catalog.chr(10)
              || '{"title": "새 제목", "expected_version": 1}'::jsonb::text,
            'UTF8'
          ),
          'sha256'
        ),
        'hex'
      ),
      'response_code', request.response_code,
      'response_body', request.response_body
    )
    from public.api_requests as request
    where request.owner_id = '15000000-0000-0000-0000-000000000001'
      and request.request_id = '65000000-0000-0000-0000-000000000001'
  ),
  '{"method_path":"PATCH /items/55000000-0000-0000-0000-000000000001","request_hash_matches":true,"response_code":200,"response_body":{"item_id":"55000000-0000-0000-0000-000000000001","http_status":200}}'::jsonb,
  'a successful edit stores only its target and status with the canonical raw-body hash'
);

reset role;

update public.items
set version = 3,
    fetched_title = '현재 확보 제목'
where id = '55000000-0000-0000-0000-000000000001';

set local role service_role;

select is(
  pg_catalog.jsonb_build_object(
    'status', replay ->> 'http_status',
    'version', replay #>> '{item,version}',
    'fetched_title', replay #>> '{item,fetched_title}'
  ),
  '{"status":"200","version":"3","fetched_title":"현재 확보 제목"}'::jsonb,
  'an exact replay is checked before stale versions and returns current live detail'
)
from (
  select public.library_update_item(
    '15000000-0000-0000-0000-000000000001',
    '55000000-0000-0000-0000-000000000001',
    '65000000-0000-0000-0000-000000000001',
    '{"expected_version":1,"title":"새 제목"}'::jsonb,
    null
  ) as replay
) as replay_result;

select is(
  (
    select count(*)::integer
    from public.processing_jobs
    where item_id = '55000000-0000-0000-0000-000000000001'
  ),
  4,
  'an exact replay performs no additional job side effects'
);

select throws_ok(
  $$
    select public.library_update_item(
      '15000000-0000-0000-0000-000000000001',
      '55000000-0000-0000-0000-000000000001',
      '65000000-0000-0000-0000-000000000001',
      '{"expected_version":1,"title":"다른 본문"}'::jsonb,
      null
    )
  $$,
  'P0001',
  'IDEMPOTENCY_MISMATCH',
  'the same request ID with a different raw JSON body is rejected before version checks'
);

reset role;

insert into public.api_requests (
  owner_id,
  request_id,
  method_path,
  request_hash,
  response_code,
  response_body
)
values (
  '15000000-0000-0000-0000-000000000001',
  '65000000-0000-0000-0000-000000000002',
  'POST /items',
  pg_catalog.repeat('a', 64),
  200,
  '{"item_id":"55000000-0000-0000-0000-000000000001","duplicate":true,"http_status":200}'::jsonb
);

set local role service_role;

select throws_ok(
  $$
    select public.library_update_item(
      '15000000-0000-0000-0000-000000000001',
      '55000000-0000-0000-0000-000000000001',
      '65000000-0000-0000-0000-000000000002',
      '{"expected_version":3,"note":"경로 충돌"}'::jsonb,
      null
    )
  $$,
  'P0001',
  'IDEMPOTENCY_MISMATCH',
  'the same request ID used by another method and path is rejected first'
);

select is(
  public.library_update_item(
    '15000000-0000-0000-0000-000000000001',
    '55000000-0000-0000-0000-000000000010',
    '65000000-0000-0000-0000-000000000003',
    '{"expected_version":1,"title":"본문 버전이 오래됨"}'::jsonb,
    pg_temp.edit_prepared(
      '55000000-0000-0000-0000-000000000010',
      2,
      '본문 버전이 오래됨',
      '충돌 메모',
      ''
    )
  ),
  '{"http_status":409,"error_code":"VERSION_CONFLICT"}'::jsonb,
  'a stale expected version returns a committed version-conflict result'
);

select is(
  (
    select pg_catalog.jsonb_build_object(
      'title', item.user_title,
      'note', item.note,
      'version', item.version,
      'text_revision', item.text_revision,
      'indexed_title', search_record.normalized_fields ->> 'user_title',
      'indexed_note', search_record.normalized_fields ->> 'note',
      'search_revision', search_record.text_revision,
      'classification_state', classification.state,
      'classification_revision', classification.target_revision,
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
    join public.item_classification as classification
      on classification.owner_id = item.owner_id
      and classification.item_id = item.id
    where item.id = '55000000-0000-0000-0000-000000000010'
  ),
  '{"title":"충돌 제목","note":"충돌 메모","version":2,"text_revision":1,"indexed_title":"충돌 제목","indexed_note":"충돌 메모","search_revision":1,"classification_state":"automatic","classification_revision":1,"job_count":0}'::jsonb,
  'a version conflict changes no item, index, classification, or job state'
);

select is(
  (
    select pg_catalog.jsonb_build_object(
      'method_path', request.method_path,
      'request_hash_matches', request.request_hash = pg_catalog.encode(
        extensions.digest(
          pg_catalog.convert_to(
            request.method_path || pg_catalog.chr(10)
              || '{"expected_version":1,"title":"본문 버전이 오래됨"}'::jsonb::text,
            'UTF8'
          ),
          'sha256'
        ),
        'hex'
      ),
      'response_code', request.response_code,
      'response_body', request.response_body
    )
    from public.api_requests as request
    where request.owner_id = '15000000-0000-0000-0000-000000000001'
      and request.request_id = '65000000-0000-0000-0000-000000000003'
  ),
  '{"method_path":"PATCH /items/55000000-0000-0000-0000-000000000010","request_hash_matches":true,"response_code":409,"response_body":{"item_id":"55000000-0000-0000-0000-000000000010","error_code":"VERSION_CONFLICT"}}'::jsonb,
  'a version conflict stores only canonical identity and its minimal receipt'
);

reset role;

update public.items
set version = 3
where id = '55000000-0000-0000-0000-000000000010';

set local role service_role;

select is(
  public.library_update_item(
    '15000000-0000-0000-0000-000000000001',
    '55000000-0000-0000-0000-000000000010',
    '65000000-0000-0000-0000-000000000003',
    '{"expected_version":1,"title":"본문 버전이 오래됨"}'::jsonb,
    null
  ),
  '{"http_status":409,"error_code":"VERSION_CONFLICT"}'::jsonb,
  'an exact conflict replay remains a conflict after a newer version is available'
);

select throws_ok(
  $$
    select public.library_update_item(
      '15000000-0000-0000-0000-000000000001',
      '55000000-0000-0000-0000-000000000010',
      '65000000-0000-0000-0000-000000000003',
      '{"expected_version":3,"title":"충돌 확인 뒤 저장"}'::jsonb,
      null
    )
  $$,
  'P0001',
  'IDEMPOTENCY_MISMATCH',
  'a conflict request ID cannot be reused with changed content or expected version'
);

select is(
  pg_catalog.jsonb_build_object(
    'http_status', response ->> 'http_status',
    'title', response #>> '{item,user_title}',
    'version', response #>> '{item,version}'
  ),
  '{"http_status":"200","title":"충돌 확인 뒤 저장","version":"4"}'::jsonb,
  'a new request ID with the current version applies exactly once'
)
from (
  select public.library_update_item(
    '15000000-0000-0000-0000-000000000001',
    '55000000-0000-0000-0000-000000000010',
    '65000000-0000-0000-0000-000000000004',
    '{"expected_version":3,"title":"충돌 확인 뒤 저장"}'::jsonb,
    pg_temp.edit_prepared(
      '55000000-0000-0000-0000-000000000010',
      3,
      '충돌 확인 뒤 저장',
      '충돌 메모',
      ''
    )
  ) as response
) as resolved_conflict;

select is(
  public.library_update_item(
    '15000000-0000-0000-0000-000000000001',
    '55000000-0000-0000-0000-000000000010',
    '65000000-0000-0000-0000-000000000020',
    '{"expected_version":4,"note":"준비 버전이 오래됨"}'::jsonb,
    pg_temp.edit_prepared(
      '55000000-0000-0000-0000-000000000010',
      3,
      '충돌 확인 뒤 저장',
      '준비 버전이 오래됨',
      ''
    )
  ),
  '{"http_status":409,"error_code":"VERSION_CONFLICT"}'::jsonb,
  'a stale preparation snapshot also commits a version-conflict result'
);

select throws_ok(
  $$
    select public.library_update_item(
      '15000000-0000-0000-0000-000000000001',
      '55000000-0000-0000-0000-000000000010',
      '65000000-0000-0000-0000-000000000022',
      '{"expected_version":4,"note":"준비 형태 검사"}'::jsonb,
      pg_temp.edit_prepared(
        '55000000-0000-0000-0000-000000000010',
        4,
        '충돌 확인 뒤 저장',
        '준비 형태 검사',
        ''
      ) || '{"unexpected":true}'::jsonb
    )
  $$,
  'P0001',
  'INVALID_PREPARED',
  'prepared edit data rejects keys outside the exact M1 shape plus snapshot_version'
);

select is(
  public.library_update_item(
    '15000000-0000-0000-0000-000000000001',
    '55000000-0000-0000-0000-000000000002',
    '65000000-0000-0000-0000-000000000005',
    '{"expected_version":1,"title":null,"note":null}'::jsonb,
    pg_temp.edit_prepared(
      '55000000-0000-0000-0000-000000000002',
      1,
      null,
      null,
      ''
    )
  ) ->> 'http_status',
  '200',
  'nullable text fields can be explicitly cleared'
);

select is(
  (
    select pg_catalog.jsonb_build_object(
      'title_is_null', item.user_title is null,
      'note_is_null', item.note is null,
      'version', item.version,
      'text_revision', item.text_revision,
      'indexed_title', search_record.normalized_fields ->> 'user_title',
      'indexed_note', search_record.normalized_fields ->> 'note',
      'title_aliases', search_record.alias_concepts -> 'user_title',
      'note_aliases', search_record.alias_concepts -> 'note',
      'classification_state', classification.state,
      'classification_revision', classification.target_revision,
      'classifier_jobs', (
        select count(*)
        from public.processing_jobs as job
        where job.item_id = item.id
          and job.kind = 'classify'
      )
    )
    from public.items as item
    join public.item_search as search_record
      on search_record.owner_id = item.owner_id
      and search_record.item_id = item.id
    join public.item_classification as classification
      on classification.owner_id = item.owner_id
      and classification.item_id = item.id
    where item.id = '55000000-0000-0000-0000-000000000002'
  ),
  '{"title_is_null":true,"note_is_null":true,"version":2,"text_revision":2,"indexed_title":"","indexed_note":"","title_aliases":[],"note_aliases":[],"classification_state":"unclassified","classification_revision":2,"classifier_jobs":0}'::jsonb,
  'clearing all text removes its index and aliases without creating empty-input classifier work'
);

select is(
  public.library_update_item(
    '15000000-0000-0000-0000-000000000001',
    '55000000-0000-0000-0000-000000000003',
    '65000000-0000-0000-0000-000000000006',
    '{"expected_version":1,"title":"수동 새 제목"}'::jsonb,
    pg_catalog.jsonb_set(
      pg_catalog.jsonb_set(
        pg_temp.edit_prepared(
          '55000000-0000-0000-0000-000000000003',
          1,
          '수동 새 제목',
          'youtube 보존 메모',
          '여행'
        ),
        '{normalized_fields,note}',
        '"준비에서 오래된 메모"'::jsonb
      ),
      '{alias_concepts,note}',
      '[]'::jsonb
    )
  ) ->> 'http_status',
  '200',
  'an edit may omit category_ids and note'
);

select is(
  (
    select pg_catalog.jsonb_build_object(
      'note', item.note,
      'indexed_note', search_record.normalized_fields ->> 'note',
      'note_aliases', search_record.alias_concepts -> 'note',
      'category_ids', (
        select pg_catalog.jsonb_agg(selected.category_id order by selected.category_id)
        from public.item_categories as selected
        where selected.owner_id = item.owner_id
          and selected.item_id = item.id
      ),
      'manual_override', controls.manual_override,
      'classification_state', classification.state,
      'classification_revision', classification.target_revision,
      'active_classifier_jobs', (
        select count(*)
        from public.processing_jobs as job
        where job.item_id = item.id
          and job.kind = 'classify'
          and job.state in ('queued', 'running', 'retry')
      )
    )
    from public.items as item
    join public.item_search as search_record
      on search_record.owner_id = item.owner_id
      and search_record.item_id = item.id
    join public.item_category_controls as controls
      on controls.owner_id = item.owner_id
      and controls.item_id = item.id
    join public.item_classification as classification
      on classification.owner_id = item.owner_id
      and classification.item_id = item.id
    where item.id = '55000000-0000-0000-0000-000000000003'
  ),
  '{"note":"youtube 보존 메모","indexed_note":"youtube 보존 메모","note_aliases":["youtube"],"category_ids":["35000000-0000-0000-0000-000000000001"],"manual_override":true,"classification_state":"manual","classification_revision":2,"active_classifier_jobs":0}'::jsonb,
  'omitted fields use current rows rather than stale prepared copies and preserve manual classification'
);

select is(
  public.library_update_item(
    '15000000-0000-0000-0000-000000000001',
    '55000000-0000-0000-0000-000000000004',
    '65000000-0000-0000-0000-000000000007',
    '{"expected_version":1,"category_ids":[]}'::jsonb,
    pg_temp.edit_prepared(
      '55000000-0000-0000-0000-000000000004',
      1,
      '분류 비우기',
      '분류 메모',
      ''
    )
  ) #>> '{item,classification_state}',
  'manual',
  'an explicit empty category array is a manual edit'
);

select is(
  (
    select pg_catalog.jsonb_build_object(
      'version', item.version,
      'text_revision', item.text_revision,
      'search_revision', search_record.text_revision,
      'indexed_categories', search_record.normalized_fields ->> 'categories',
      'selected_count', (
        select count(*)
        from public.item_categories as selected
        where selected.item_id = item.id
      ),
      'manual_override', controls.manual_override,
      'classification_state', classification.state,
      'classification_revision', classification.target_revision,
      'metadata_job_state', (
        select job.state
        from public.processing_jobs as job
        where job.item_id = item.id
          and job.kind = 'metadata'
      ),
      'classifier_job_state', (
        select job.state
        from public.processing_jobs as job
        where job.item_id = item.id
          and job.kind = 'classify'
      )
    )
    from public.items as item
    join public.item_search as search_record
      on search_record.owner_id = item.owner_id
      and search_record.item_id = item.id
    join public.item_category_controls as controls
      on controls.owner_id = item.owner_id
      and controls.item_id = item.id
    join public.item_classification as classification
      on classification.owner_id = item.owner_id
      and classification.item_id = item.id
    where item.id = '55000000-0000-0000-0000-000000000004'
  ),
  '{"version":2,"text_revision":1,"search_revision":1,"indexed_categories":"","selected_count":0,"manual_override":true,"classification_state":"manual","classification_revision":1,"metadata_job_state":"queued","classifier_job_state":"cancelled"}'::jsonb,
  'category-only edits increment item version but not text revision or metadata work'
);

select is(
  public.library_update_item(
    '15000000-0000-0000-0000-000000000001',
    '55000000-0000-0000-0000-000000000005',
    '65000000-0000-0000-0000-000000000008',
    '{"expected_version":1,"category_ids":["35000000-0000-0000-0000-000000000002","35000000-0000-0000-0000-000000000001"]}'::jsonb,
    pg_temp.edit_prepared(
      '55000000-0000-0000-0000-000000000005',
      1,
      '분류 선택',
      '선택 메모',
      '음식 여행'
    )
  ) ->> 'http_status',
  '200',
  'owned non-empty category arrays are accepted'
);

select is(
  (
    select pg_catalog.jsonb_build_object(
      'version', item.version,
      'text_revision', item.text_revision,
      'indexed_categories', search_record.normalized_fields ->> 'categories',
      'selected', (
        select pg_catalog.jsonb_agg(
          pg_catalog.jsonb_build_object(
            'category_id', selected.category_id,
            'origin', selected.origin
          )
          order by selected.category_id
        )
        from public.item_categories as selected
        where selected.owner_id = item.owner_id
          and selected.item_id = item.id
      ),
      'manual_override', controls.manual_override
    )
    from public.items as item
    join public.item_search as search_record
      on search_record.owner_id = item.owner_id
      and search_record.item_id = item.id
    join public.item_category_controls as controls
      on controls.owner_id = item.owner_id
      and controls.item_id = item.id
    where item.id = '55000000-0000-0000-0000-000000000005'
  ),
  '{"version":2,"text_revision":1,"indexed_categories":"음식 여행","selected":[{"category_id":"35000000-0000-0000-0000-000000000001","origin":"manual"},{"category_id":"35000000-0000-0000-0000-000000000002","origin":"manual"}],"manual_override":true}'::jsonb,
  'category selection uses authoritative owned names in the search index and manual links'
);

select throws_ok(
  $$
    select public.library_update_item(
      '15000000-0000-0000-0000-000000000001',
      '55000000-0000-0000-0000-000000000005',
      '65000000-0000-0000-0000-000000000021',
      '{"expected_version":2,"category_ids":["35000000-0000-0000-0000-000000000002","35000000-0000-0000-0000-000000000001"]}'::jsonb,
      pg_temp.edit_prepared(
        '55000000-0000-0000-0000-000000000005',
        2,
        '분류 선택',
        '선택 메모',
        '클라이언트 위조 이름'
      )
    )
  $$,
  'P0001',
  'INVALID_PREPARED',
  'prepared category text cannot replace authoritative current category names'
);

select throws_ok(
  $$
    select public.library_update_item(
      '15000000-0000-0000-0000-000000000001',
      '55000000-0000-0000-0000-000000000005',
      '65000000-0000-0000-0000-000000000009',
      '{"expected_version":2,"category_ids":["35000000-0000-0000-0000-000000000003"]}'::jsonb,
      pg_temp.edit_prepared(
        '55000000-0000-0000-0000-000000000005',
        2,
        '분류 선택',
        '선택 메모',
        '외부'
      )
    )
  $$,
  'P0001',
  'INVALID_CATEGORY_IDS',
  'a foreign category ID is rejected after the item lock'
);

select is(
  (
    select pg_catalog.jsonb_build_object(
      'version', item.version,
      'selected_count', count(selected.category_id)
    )
    from public.items as item
    left join public.item_categories as selected
      on selected.owner_id = item.owner_id
      and selected.item_id = item.id
    where item.id = '55000000-0000-0000-0000-000000000005'
    group by item.version
  ),
  '{"version":2,"selected_count":2}'::jsonb,
  'a rejected foreign-category transaction changes neither version nor links'
);

select throws_ok(
  $$
    select public.library_update_item(
      '15000000-0000-0000-0000-000000000001',
      '55000000-0000-0000-0000-000000000005',
      '65000000-0000-0000-0000-000000000010',
      '{"expected_version":2,"category_ids":null}'::jsonb,
      null
    )
  $$,
  'P0001',
  'INVALID_BODY',
  'category_ids null is distinct from an explicit empty array and is rejected'
);

select is(
  public.library_update_item(
    '15000000-0000-0000-0000-000000000001',
    '55000000-0000-0000-0000-000000000006',
    '65000000-0000-0000-0000-000000000011',
    '{"expected_version":1,"note":"완료 뒤 새 메모"}'::jsonb,
    pg_temp.edit_prepared(
      '55000000-0000-0000-0000-000000000006',
      1,
      '완료 메타 제목',
      '완료 뒤 새 메모',
      ''
    )
  ) ->> 'http_status',
  '200',
  'text can be edited after metadata reaches a terminal state'
);

select is(
  (
    select pg_catalog.jsonb_build_object(
      'metadata_state', item.metadata_state,
      'metadata_jobs', count(job.id),
      'version', item.version,
      'text_revision', item.text_revision
    )
    from public.items as item
    left join public.processing_jobs as job
      on job.owner_id = item.owner_id
      and job.item_id = item.id
      and job.kind = 'metadata'
    where item.id = '55000000-0000-0000-0000-000000000006'
    group by item.metadata_state, item.version, item.text_revision
  ),
  '{"metadata_state":"ready","metadata_jobs":0,"version":2,"text_revision":2}'::jsonb,
  'ready metadata is not reread after a text edit'
);

select is(
  public.library_update_item(
    '15000000-0000-0000-0000-000000000001',
    '55000000-0000-0000-0000-000000000007',
    '65000000-0000-0000-0000-000000000012',
    '{"expected_version":1,"title":"같은 제목"}'::jsonb,
    pg_temp.edit_prepared(
      '55000000-0000-0000-0000-000000000007',
      1,
      '같은 제목',
      '같은 메모',
      ''
    )
  ) #>> '{item,version}',
  '2',
  'a valid no-op text patch still consumes exactly one item version'
);

select is(
  (
    select pg_catalog.jsonb_build_object(
      'text_revision', item.text_revision,
      'classification_state', classification.state,
      'classification_revision', classification.target_revision,
      'job_count', count(job.id)
    )
    from public.items as item
    join public.item_classification as classification
      on classification.owner_id = item.owner_id
      and classification.item_id = item.id
    left join public.processing_jobs as job
      on job.owner_id = item.owner_id
      and job.item_id = item.id
    where item.id = '55000000-0000-0000-0000-000000000007'
    group by item.text_revision, classification.state, classification.target_revision
  ),
  '{"text_revision":1,"classification_state":"automatic","classification_revision":1,"job_count":0}'::jsonb,
  'a no-op text patch does not invalidate classification or create endless jobs'
);

select throws_ok(
  $$
    select public.library_update_item(
      '15000000-0000-0000-0000-000000000001',
      '55000000-0000-0000-0000-000000000008',
      '65000000-0000-0000-0000-000000000013',
      '{"expected_version":1,"title":"타인 수정"}'::jsonb,
      null
    )
  $$,
  'P0001',
  'ITEM_NOT_FOUND',
  'another owner item is indistinguishable from a missing item'
);

select throws_ok(
  $$
    select public.library_update_item(
      '15000000-0000-0000-0000-000000000001',
      '55000000-0000-0000-0000-000000000007',
      '65000000-0000-0000-0000-000000000014',
      '{"expected_version":2}'::jsonb,
      null
    )
  $$,
  'P0001',
  'INVALID_BODY',
  'expected_version without a patch field is rejected'
);

select throws_ok(
  $$
    select public.library_update_item(
      '15000000-0000-0000-0000-000000000001',
      '55000000-0000-0000-0000-000000000007',
      '65000000-0000-0000-0000-000000000015',
      '{"expected_version":0,"note":"잘못된 버전"}'::jsonb,
      null
    )
  $$,
  'P0001',
  'INVALID_BODY',
  'expected_version must be a positive JSON integer'
);

reset role;

update public.beta_members
set enabled = false
where owner_id = '15000000-0000-0000-0000-000000000001';

set local role service_role;

select throws_ok(
  $$
    select public.library_update_item(
      '15000000-0000-0000-0000-000000000001',
      '55000000-0000-0000-0000-000000000007',
      '65000000-0000-0000-0000-000000000016',
      '{"expected_version":2,"note":"철회 뒤 수정"}'::jsonb,
      null
    )
  $$,
  'P0001',
  'BETA_ACCESS_REQUIRED',
  'revoked beta access uses the existing denial code'
);

reset role;

update public.beta_members
set enabled = true
where owner_id = '15000000-0000-0000-0000-000000000001';

update public.profiles
set state = 'deleting',
    deletion_requested_at = now()
where id = '15000000-0000-0000-0000-000000000001';

set local role service_role;

select throws_ok(
  $$
    select public.library_update_item(
      '15000000-0000-0000-0000-000000000001',
      '55000000-0000-0000-0000-000000000007',
      '65000000-0000-0000-0000-000000000017',
      '{"expected_version":2,"note":"삭제 중 수정"}'::jsonb,
      null
    )
  $$,
  'P0001',
  'ACCOUNT_DELETING',
  'a deleting profile uses the existing denial code'
);

reset role;

update public.profiles
set state = 'active',
    deletion_requested_at = null
where id = '15000000-0000-0000-0000-000000000001';

set local role authenticated;

select throws_ok(
  $$
    select public.library_update_item(
      '15000000-0000-0000-0000-000000000001',
      '55000000-0000-0000-0000-000000000007',
      '65000000-0000-0000-0000-000000000018',
      '{"expected_version":2,"note":"직접 수정"}'::jsonb,
      null
    )
  $$,
  '42501',
  null,
  'authenticated clients cannot directly execute the server-write RPC'
);

reset role;
set local role service_role;

select is(
  public.library_update_item(
    '15000000-0000-0000-0000-000000000001',
    '55000000-0000-0000-0000-000000000009',
    '65000000-0000-0000-0000-000000000019',
    '{"expected_version":1,"note":"삭제 전 저장"}'::jsonb,
    pg_temp.edit_prepared(
      '55000000-0000-0000-0000-000000000009',
      1,
      '삭제 재생 제목',
      '삭제 전 저장',
      ''
    )
  ) ->> 'http_status',
  '200',
  'the deleted-replay fixture first records a successful edit'
);

reset role;

update public.items
set deleted_at = now()
where id = '55000000-0000-0000-0000-000000000009';

set local role service_role;

select throws_ok(
  $$
    select public.library_update_item(
      '15000000-0000-0000-0000-000000000001',
      '55000000-0000-0000-0000-000000000009',
      '65000000-0000-0000-0000-000000000019',
      '{"expected_version":1,"note":"삭제 전 저장"}'::jsonb,
      null
    )
  $$,
  'P0001',
  'ITEM_DELETED',
  'an exact replay of a successfully edited target returns the deleted marker after deletion'
);

reset role;
select * from finish();
rollback;
