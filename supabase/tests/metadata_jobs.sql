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
    '14000000-0000-0000-0000-000000000001',
    'authenticated',
    'authenticated',
    'metadata-a@example.test',
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
    '14000000-0000-0000-0000-000000000002',
    'authenticated',
    'authenticated',
    'metadata-b@example.test',
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
  ('14000000-0000-0000-0000-000000000001', true, now()),
  ('14000000-0000-0000-0000-000000000002', true, now());

insert into public.profiles (id, state)
values
  ('14000000-0000-0000-0000-000000000001', 'active'),
  ('14000000-0000-0000-0000-000000000002', 'active');

insert into public.library_usage (owner_id)
values
  ('14000000-0000-0000-0000-000000000001'),
  ('14000000-0000-0000-0000-000000000002');

create temporary table metadata_receipts (
  label text primary key,
  payload jsonb not null
);

create function pg_temp.seed_metadata_item(
  p_item_id uuid,
  p_job_id uuid,
  p_owner_id uuid default '14000000-0000-0000-0000-000000000001',
  p_url text default null,
  p_version integer default 1,
  p_revision integer default 1,
  p_metadata_state text default 'queued',
  p_manual boolean default false,
  p_fetched_title text default null,
  p_description text default null,
  p_body_text text default null,
  p_create_job boolean default true
)
returns void
language plpgsql
set search_path = ''
as $$
declare
  item_url text := coalesce(
    p_url,
    'https://blog.naver.com/metadata/' || p_item_id::text
  );
begin
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
    fetched_title,
    description,
    body_text,
    note,
    extraction_meta,
    text_revision,
    version,
    metadata_state
  )
  values (
    p_item_id,
    p_owner_id,
    item_url,
    pg_catalog.lower(item_url),
    pg_catalog.encode(
      extensions.digest(pg_catalog.convert_to(pg_catalog.lower(item_url), 'UTF8'), 'sha256'),
      'hex'
    ),
    case when item_url ~* '^https://(blog[.]naver[.]com|m[.]blog[.]naver[.]com)' then 'naver_blog' else 'other' end,
    'metadata.test',
    '공유 원문',
    '사용자 제목',
    p_fetched_title,
    p_description,
    p_body_text,
    '사용자 메모',
    '{}'::jsonb,
    p_revision,
    p_version,
    p_metadata_state
  );

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
  values (
    p_owner_id,
    p_item_id,
    p_revision,
    'search-v2.0.0',
    pg_catalog.jsonb_build_object(
      'user_title', '사용자 제목',
      'fetched_title', coalesce(p_fetched_title, ''),
      'note', '사용자 메모',
      'ocr', '',
      'shared', '공유 원문',
      'description', coalesce(p_description, ''),
      'body', coalesce(p_body_text, ''),
      'categories', '',
      'url', pg_catalog.lower(item_url)
    ),
    pg_catalog.jsonb_build_object(
      'user_title', '[]'::jsonb,
      'fetched_title', '[]'::jsonb,
      'note', '[]'::jsonb,
      'ocr', '[]'::jsonb,
      'shared', '[]'::jsonb,
      'description', '[]'::jsonb,
      'body', '[]'::jsonb
    ),
    'cues-v1.0.0',
    'available',
    '[]'::jsonb
  );

  insert into public.item_classification (
    owner_id,
    item_id,
    rules_version,
    target_revision,
    state,
    reasons
  )
  values (
    p_owner_id,
    p_item_id,
    'rules-v2.0.0',
    p_revision,
    case when p_manual then 'manual' else 'pending' end,
    '[]'::jsonb
  );

  insert into public.item_category_controls (
    owner_id,
    item_id,
    manual_override
  )
  values (p_owner_id, p_item_id, p_manual);

  if p_create_job then
    insert into public.processing_jobs (
      id,
      owner_id,
      item_id,
      kind,
      target_revision,
      state
    )
    values (
      p_job_id,
      p_owner_id,
      p_item_id,
      'metadata',
      p_revision,
      'queued'
    );
  end if;
end;
$$;

create function pg_temp.metadata_result(
  p_title text,
  p_description text,
  p_body text,
  p_state text default 'ready',
  p_error_code text default null
)
returns jsonb
language sql
stable
set search_path = ''
as $$
  select pg_catalog.jsonb_build_object(
    'fetched_title', p_title,
    'description', p_description,
    'body_text', p_body,
    'metadata_state', p_state,
    'extraction_meta', pg_catalog.jsonb_strip_nulls(
      pg_catalog.jsonb_build_object(
        'adapter_version', 'naver-v1',
        'final_url', 'https://blog.naver.com/final',
        'title_truncated', false,
        'description_truncated', false,
        'body_truncated', false,
        'last_checked_at', '2026-09-15T12:00:00Z',
        'error_code', p_error_code
      )
    )
  );
$$;

create function pg_temp.metadata_prepared(
  p_owner_id uuid,
  p_item_id uuid,
  p_snapshot_version integer,
  p_title text,
  p_description text,
  p_body text
)
returns jsonb
language sql
stable
set search_path = ''
as $$
  select pg_catalog.jsonb_build_object(
    'normalized_fields', search_record.normalized_fields || pg_catalog.jsonb_build_object(
      'fetched_title', coalesce(p_title, search_record.normalized_fields ->> 'fetched_title'),
      'description', coalesce(p_description, search_record.normalized_fields ->> 'description'),
      'body', coalesce(p_body, search_record.normalized_fields ->> 'body')
    ),
    'alias_concepts', search_record.alias_concepts || pg_catalog.jsonb_build_object(
      'fetched_title', '[]'::jsonb,
      'description', '[]'::jsonb,
      'body', '[]'::jsonb
    ),
    'cue_state', 'available',
    'cue_flags', '[]'::jsonb,
    'snapshot_version', p_snapshot_version
  )
  from public.item_search as search_record
  where search_record.owner_id = p_owner_id
    and search_record.item_id = p_item_id;
$$;

select ok(
  (
    select bool_and(installed.prosecdef)
      and bool_and(
        exists (
          select 1
          from pg_catalog.unnest(installed.proconfig) as setting
          where setting like 'search_path=%'
        )
      )
    from pg_catalog.pg_proc as installed
    where installed.oid in (
      'public.library_claim_metadata_jobs(integer)'::regprocedure,
      'public.library_complete_metadata_job(uuid,uuid,integer,jsonb,jsonb)'::regprocedure,
      'public.library_fail_metadata_job(uuid,uuid,text,integer)'::regprocedure,
      'public.library_retry_metadata(uuid,uuid,uuid,jsonb)'::regprocedure
    )
  ),
  'metadata RPCs are SECURITY DEFINER functions with fixed search paths'
);

select ok(
  has_function_privilege(
    'service_role',
    'public.library_claim_metadata_jobs(integer)',
    'EXECUTE'
  )
  and has_function_privilege(
    'service_role',
    'public.library_complete_metadata_job(uuid,uuid,integer,jsonb,jsonb)',
    'EXECUTE'
  )
  and has_function_privilege(
    'service_role',
    'public.library_fail_metadata_job(uuid,uuid,text,integer)',
    'EXECUTE'
  )
  and has_function_privilege(
    'service_role',
    'public.library_retry_metadata(uuid,uuid,uuid,jsonb)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated',
    'public.library_claim_metadata_jobs(integer)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'public.library_complete_metadata_job(uuid,uuid,integer,jsonb,jsonb)',
    'EXECUTE'
  )
  and not has_table_privilege('authenticated', 'public.processing_jobs', 'SELECT')
  and not has_table_privilege('anon', 'public.api_rate_buckets', 'SELECT'),
  'only service_role can invoke metadata transactions or inspect their internal rows'
);

select ok(
  private.metadata_url_allowed('https://blog.naver.com/post')
  and private.metadata_url_allowed('HTTPS://M.BLOG.NAVER.COM:443/post')
  and not private.metadata_url_allowed('http://blog.naver.com/post')
  and not private.metadata_url_allowed('https://blog.naver.com:444/post')
  and not private.metadata_url_allowed('https://evil.blog.naver.com/post')
  and not private.metadata_url_allowed('https://www.instagram.com/post'),
  'only exact verified Naver hosts over HTTPS port 443 are metadata eligible'
);

select ok(
  private.valid_metadata_result(
    pg_temp.metadata_result(
      pg_catalog.repeat('t', 300),
      pg_catalog.repeat('d', 4000),
      pg_catalog.repeat('b', 20000)
    )
  ),
  'raw metadata accepts the exact title description and body bounds'
);

select ok(
  not private.valid_metadata_result(
    pg_temp.metadata_result(pg_catalog.repeat('t', 301), null, null)
  )
  and not private.valid_metadata_result(
    pg_temp.metadata_result(null, pg_catalog.repeat('d', 4001), null)
  )
  and not private.valid_metadata_result(
    pg_temp.metadata_result(null, null, pg_catalog.repeat('b', 20001))
  )
  and not private.valid_metadata_result(
    pg_temp.metadata_result(null, null, null, 'ready')
  )
  and not private.valid_metadata_result(
    pg_temp.metadata_result('title', null, null)
      || '{"extraction_meta":{"raw_html":"secret"}}'::jsonb
  ),
  'oversized raw fields empty-ready results and non-whitelisted extraction metadata are rejected'
);

select ok(
  private.metadata_error_retryable('METADATA_TIMEOUT')
  and private.metadata_error_retryable('NETWORK_ERROR')
  and private.metadata_error_retryable('RATE_LIMITED')
  and private.metadata_error_retryable('INTERNAL_ERROR')
  and not private.metadata_error_retryable('ACCESS_DENIED')
  and not private.metadata_error_retryable('INVALID_CONTENT')
  and not private.metadata_error_retryable('RESPONSE_TOO_LARGE')
  and not private.metadata_error_retryable('METADATA_BUDGET_EXHAUSTED')
  and not private.metadata_error_retryable('INVALID_RESULT'),
  'the fixed error taxonomy explicitly separates retryable and permanent failures'
);

select ok(
  private.valid_metadata_result(
    pg_temp.metadata_result(
      null,
      null,
      null,
      'partial',
      'RETRY_AFTER_EXCEEDED'
    )
  )
  and not private.metadata_error_retryable('RETRY_AFTER_EXCEEDED'),
  'RETRY_AFTER_EXCEEDED is accepted by result validation as a permanent failure code'
);

select pg_temp.seed_metadata_item(
  '54000000-0000-0000-0000-000000000001',
  '64000000-0000-0000-0000-000000000001'
);
insert into metadata_receipts (label, payload)
values ('lease-a', public.library_claim_metadata_jobs(1));

select ok(
  (
    select pg_catalog.jsonb_array_length(payload -> 'jobs') = 1
      and payload #>> '{jobs,0,budget_reserved}' = 'true'
      and (payload #>> '{jobs,0,expected_version}')::integer = 2
    from metadata_receipts
    where label = 'lease-a'
  )
  and (
    select state = 'running'
      and attempts = 1
      and lease_until > pg_catalog.clock_timestamp() + interval '175 seconds'
      and lease_until <= pg_catalog.clock_timestamp() + interval '180 seconds'
    from public.processing_jobs
    where id = '64000000-0000-0000-0000-000000000001'
  ),
  'claim reserves the external budget before returning a fenced 180 second lease'
);

update public.processing_jobs
set lease_until = pg_catalog.clock_timestamp() - interval '1 second'
where id = '64000000-0000-0000-0000-000000000001';
insert into metadata_receipts (label, payload)
values ('lease-b', public.library_claim_metadata_jobs(1));

select isnt(
  (select payload #>> '{jobs,0,lease_token}' from metadata_receipts where label = 'lease-a'),
  (select payload #>> '{jobs,0,lease_token}' from metadata_receipts where label = 'lease-b'),
  'a reclaimed job receives a new lease token'
);

select is(
  public.library_complete_metadata_job(
    '64000000-0000-0000-0000-000000000001',
    (
      select (payload #>> '{jobs,0,lease_token}')::uuid
      from metadata_receipts
      where label = 'lease-a'
    ),
    2,
    pg_temp.metadata_result('late title', 'late description', 'late body'),
    pg_temp.metadata_prepared(
      '14000000-0000-0000-0000-000000000001',
      '54000000-0000-0000-0000-000000000001',
      2,
      'late title',
      'late description',
      'late body'
    )
  ) ->> 'state',
  'discarded_stale',
  'the previous worker cannot apply after lease reclamation'
);

select is(
  public.library_fail_metadata_job(
    '64000000-0000-0000-0000-000000000001',
    (
      select (payload #>> '{jobs,0,lease_token}')::uuid
      from metadata_receipts
      where label = 'lease-a'
    ),
    'NETWORK_ERROR',
    null
  ) ->> 'state',
  'discarded_stale',
  'fail metadata uses the same discarded_stale state for a superseded lease'
);

insert into metadata_receipts (label, payload)
select
  'lease-b-complete',
  public.library_complete_metadata_job(
    '64000000-0000-0000-0000-000000000001',
    (select (payload #>> '{jobs,0,lease_token}')::uuid from metadata_receipts where label = 'lease-b'),
    (select (payload #>> '{jobs,0,expected_version}')::integer from metadata_receipts where label = 'lease-b'),
    pg_temp.metadata_result('new title', 'new description', 'new body'),
    pg_temp.metadata_prepared(
      '14000000-0000-0000-0000-000000000001',
      '54000000-0000-0000-0000-000000000001',
      (select (payload #>> '{jobs,0,expected_version}')::integer from metadata_receipts where label = 'lease-b'),
      'new title',
      'new description',
      'new body'
    )
  );

select ok(
  (select payload ->> 'state' = 'succeeded' from metadata_receipts where label = 'lease-b-complete')
  and (
    select fetched_title = 'new title'
      and description = 'new description'
      and body_text = 'new body'
      and metadata_state = 'ready'
      and text_revision = 2
      and version = 3
    from public.items
    where id = '54000000-0000-0000-0000-000000000001'
  )
  and (
    select text_revision = 2
      and normalized_fields ->> 'fetched_title' = 'new title'
      and normalized_fields ->> 'description' = 'new description'
      and normalized_fields ->> 'body' = 'new body'
    from public.item_search
    where item_id = '54000000-0000-0000-0000-000000000001'
  )
  and (
    select state = 'succeeded' and attempts = 2
    from public.processing_jobs
    where id = '64000000-0000-0000-0000-000000000001'
  ),
  'the current lease atomically advances bounded raw fields index revision version and job state'
);

select is(
  (
    select request_count
    from public.api_rate_buckets
    where owner_id = '14000000-0000-0000-0000-000000000001'
      and operation = 'metadata_fetch:54000000-0000-0000-0000-000000000001'
  ),
  2,
  'an expired lease never refunds its already committed external-fetch reservation'
);

select pg_temp.seed_metadata_item(
  '54000000-0000-0000-0000-000000000002',
  '64000000-0000-0000-0000-000000000002'
);
insert into metadata_receipts (label, payload)
values ('stale-lease', public.library_claim_metadata_jobs(1));

update public.items
set note = '사용자가 저장한 최신 메모',
    text_revision = text_revision + 1,
    version = version + 1,
    metadata_state = 'queued'
where id = '54000000-0000-0000-0000-000000000002';
update public.item_search
set text_revision = text_revision + 1,
    normalized_fields = normalized_fields || '{"note":"사용자가 저장한 최신 메모"}'::jsonb
where item_id = '54000000-0000-0000-0000-000000000002';
update public.item_classification
set target_revision = target_revision + 1
where item_id = '54000000-0000-0000-0000-000000000002';

select is(
  public.library_complete_metadata_job(
    '64000000-0000-0000-0000-000000000002',
    (select (payload #>> '{jobs,0,lease_token}')::uuid from metadata_receipts where label = 'stale-lease'),
    (select (payload #>> '{jobs,0,expected_version}')::integer from metadata_receipts where label = 'stale-lease'),
    pg_temp.metadata_result('stale title', 'stale description', 'stale body'),
    '{}'::jsonb
  ) ->> 'state',
  'discarded_stale',
  'a changed text revision discards already fetched metadata'
);

select ok(
  (
    select note = '사용자가 저장한 최신 메모'
      and fetched_title is null
    from public.items
    where id = '54000000-0000-0000-0000-000000000002'
  )
  and (
    select count(*) = 1
    from public.processing_jobs
    where owner_id = '14000000-0000-0000-0000-000000000001'
      and item_id = '54000000-0000-0000-0000-000000000002'
      and kind = 'metadata'
      and target_revision = 2
      and state in ('queued', 'running', 'retry')
  ),
  'stale completion preserves user text and guarantees one current-revision metadata job'
);

update public.processing_jobs
set next_run_at = pg_catalog.clock_timestamp() + interval '1 day'
where item_id = '54000000-0000-0000-0000-000000000002'
  and state in ('queued', 'retry');

select pg_temp.seed_metadata_item(
  '54000000-0000-0000-0000-000000000003',
  '64000000-0000-0000-0000-000000000003'
);
insert into metadata_receipts (label, payload)
values ('version-lease', public.library_claim_metadata_jobs(1));
update public.items
set version = version + 1
where id = '54000000-0000-0000-0000-000000000003';

insert into metadata_receipts (label, payload)
select
  'version-conflict',
  public.library_complete_metadata_job(
    '64000000-0000-0000-0000-000000000003',
    (select (payload #>> '{jobs,0,lease_token}')::uuid from metadata_receipts where label = 'version-lease'),
    (select (payload #>> '{jobs,0,expected_version}')::integer from metadata_receipts where label = 'version-lease'),
    pg_temp.metadata_result('reused title', 'reused description', 'reused body'),
    '{}'::jsonb
  );

select ok(
  (
    select payload ->> 'state' = 'version_conflict'
      and (payload #>> '{item,text_revision}')::integer = 1
    from metadata_receipts
    where label = 'version-conflict'
  )
  and (
    select state = 'running' and attempts = 1
    from public.processing_jobs
    where id = '64000000-0000-0000-0000-000000000003'
  ),
  'a version-only conflict returns the latest item without discarding the valid lease or response'
);

insert into metadata_receipts (label, payload)
select
  'version-retry',
  public.library_complete_metadata_job(
    '64000000-0000-0000-0000-000000000003',
    (select (payload #>> '{jobs,0,lease_token}')::uuid from metadata_receipts where label = 'version-lease'),
    3,
    pg_temp.metadata_result('reused title', 'reused description', 'reused body'),
    pg_temp.metadata_prepared(
      '14000000-0000-0000-0000-000000000001',
      '54000000-0000-0000-0000-000000000003',
      3,
      'reused title',
      'reused description',
      'reused body'
    )
  );

select ok(
  (select payload ->> 'state' = 'succeeded' from metadata_receipts where label = 'version-retry')
  and (
    select request_count = 1
    from public.api_rate_buckets
    where owner_id = '14000000-0000-0000-0000-000000000001'
      and operation = 'metadata_fetch:54000000-0000-0000-0000-000000000003'
  ),
  'the worker can recompute against a newer version with the fetched result and no second external budget use'
);

select pg_temp.seed_metadata_item(
  '54000000-0000-0000-0000-000000000004',
  '64000000-0000-0000-0000-000000000004',
  '14000000-0000-0000-0000-000000000001',
  null,
  1,
  1,
  'queued',
  true
);
insert into metadata_receipts (label, payload)
values ('manual-lease', public.library_claim_metadata_jobs(1));
insert into metadata_receipts (label, payload)
select
  'manual-complete',
  public.library_complete_metadata_job(
    '64000000-0000-0000-0000-000000000004',
    (select (payload #>> '{jobs,0,lease_token}')::uuid from metadata_receipts where label = 'manual-lease'),
    (select (payload #>> '{jobs,0,expected_version}')::integer from metadata_receipts where label = 'manual-lease'),
    pg_temp.metadata_result('manual title', null, null),
    pg_temp.metadata_prepared(
      '14000000-0000-0000-0000-000000000001',
      '54000000-0000-0000-0000-000000000004',
      (select (payload #>> '{jobs,0,expected_version}')::integer from metadata_receipts where label = 'manual-lease'),
      'manual title',
      null,
      null
    )
  );

select ok(
  (
    select controls.manual_override
      and classification.state = 'manual'
      and classification.target_revision = 2
    from public.item_category_controls as controls
    join public.item_classification as classification
      on classification.owner_id = controls.owner_id
      and classification.item_id = controls.item_id
    where controls.item_id = '54000000-0000-0000-0000-000000000004'
  )
  and not exists (
    select 1
    from public.processing_jobs
    where item_id = '54000000-0000-0000-0000-000000000004'
      and kind = 'classify'
      and state in ('queued', 'running', 'retry')
  ),
  'metadata enrichment reindexes while preserving the manual classification override'
);

select pg_temp.seed_metadata_item(
  '54000000-0000-0000-0000-000000000005',
  '64000000-0000-0000-0000-000000000005',
  '14000000-0000-0000-0000-000000000001',
  null,
  1,
  1,
  'queued',
  false,
  'kept title',
  'kept description',
  'kept body'
);
insert into metadata_receipts (label, payload)
values ('partial-lease', public.library_claim_metadata_jobs(1));
insert into metadata_receipts (label, payload)
select
  'partial-complete',
  public.library_complete_metadata_job(
    '64000000-0000-0000-0000-000000000005',
    (select (payload #>> '{jobs,0,lease_token}')::uuid from metadata_receipts where label = 'partial-lease'),
    (select (payload #>> '{jobs,0,expected_version}')::integer from metadata_receipts where label = 'partial-lease'),
    pg_temp.metadata_result(null, null, null, 'partial', 'ACCESS_DENIED'),
    pg_temp.metadata_prepared(
      '14000000-0000-0000-0000-000000000001',
      '54000000-0000-0000-0000-000000000005',
      (select (payload #>> '{jobs,0,expected_version}')::integer from metadata_receipts where label = 'partial-lease'),
      null,
      null,
      null
    )
  );

select ok(
  (
    select fetched_title = 'kept title'
      and description = 'kept description'
      and body_text = 'kept body'
      and user_title = '사용자 제목'
      and note = '사용자 메모'
      and metadata_state = 'partial'
      and text_revision = 1
      and version = 3
    from public.items
    where id = '54000000-0000-0000-0000-000000000005'
  ),
  'partial results preserve unavailable fetched fields and all user-authored text while versioning flags only'
);

select pg_temp.seed_metadata_item(
  '54000000-0000-0000-0000-000000000006',
  '64000000-0000-0000-0000-000000000006',
  '14000000-0000-0000-0000-000000000001',
  'https://www.instagram.com/p/unsupported'
);
select is(
  pg_catalog.jsonb_array_length(public.library_claim_metadata_jobs(1) -> 'jobs'),
  0,
  'an unsupported queued host is not handed to the external fetcher'
);
select ok(
  (
    select job.state = 'failed'
      and job.last_error_code = 'INVALID_CONTENT'
      and item.metadata_state = 'unsupported'
    from public.processing_jobs as job
    join public.items as item
      on item.owner_id = job.owner_id
      and item.id = job.item_id
    where job.id = '64000000-0000-0000-0000-000000000006'
  )
  and not exists (
    select 1
    from public.api_rate_buckets
    where operation = 'metadata_fetch:54000000-0000-0000-0000-000000000006'
  ),
  'unsupported jobs terminate without consuming external-fetch budget or looping'
);

select pg_temp.seed_metadata_item(
  '54000000-0000-0000-0000-000000000007',
  '64000000-0000-0000-0000-000000000007'
);
insert into public.api_rate_buckets (owner_id, operation, window_start, request_count)
values (
  '14000000-0000-0000-0000-000000000001',
  'metadata_fetch:54000000-0000-0000-0000-000000000007',
  pg_catalog.date_trunc('day', pg_catalog.clock_timestamp() at time zone 'UTC') at time zone 'UTC',
  5
);
insert into metadata_receipts (label, payload)
values ('budget-sixth', public.library_claim_metadata_jobs(1));
select is(
  pg_catalog.jsonb_array_length(
    (select payload -> 'jobs' from metadata_receipts where label = 'budget-sixth')
  ),
  1,
  'the sixth total item fetch is still leased'
);
select is(
  public.library_fail_metadata_job(
    '64000000-0000-0000-0000-000000000007',
    (select (payload #>> '{jobs,0,lease_token}')::uuid from metadata_receipts where label = 'budget-sixth'),
    'ACCESS_DENIED',
    null
  ) ->> 'state',
  'failed',
  'permanent access denial is not retried'
);

update public.items
set text_revision = 2,
    version = version + 1,
    metadata_state = 'queued'
where id = '54000000-0000-0000-0000-000000000007';
update public.item_search
set text_revision = 2
where item_id = '54000000-0000-0000-0000-000000000007';
update public.item_classification
set target_revision = 2
where item_id = '54000000-0000-0000-0000-000000000007';
insert into public.processing_jobs (
  id,
  owner_id,
  item_id,
  kind,
  target_revision,
  state
)
values (
  '64000000-0000-0000-0000-000000000107',
  '14000000-0000-0000-0000-000000000001',
  '54000000-0000-0000-0000-000000000007',
  'metadata',
  2,
  'queued'
);

select is(
  pg_catalog.jsonb_array_length(public.library_claim_metadata_jobs(1) -> 'jobs'),
  0,
  'a new revision cannot reset the six-fetch UTC-day budget'
);
select ok(
  (
    select request_count = 6
    from public.api_rate_buckets
    where owner_id = '14000000-0000-0000-0000-000000000001'
      and operation = 'metadata_fetch:54000000-0000-0000-0000-000000000007'
  )
  and (
    select state = 'failed' and last_error_code = 'METADATA_BUDGET_EXHAUSTED'
    from public.processing_jobs
    where id = '64000000-0000-0000-0000-000000000107'
  ),
  'budget exhaustion is terminal without incrementing or rolling back the durable count'
);

select pg_temp.seed_metadata_item(
  '54000000-0000-0000-0000-000000000008',
  '64000000-0000-0000-0000-000000000008',
  '14000000-0000-0000-0000-000000000001',
  null,
  1,
  1,
  'ready',
  false,
  null,
  null,
  null,
  false
);
insert into metadata_receipts (label, payload)
values (
  'manual-one',
  public.library_retry_metadata(
    '14000000-0000-0000-0000-000000000001',
    '74000000-0000-0000-0000-000000000001',
    '54000000-0000-0000-0000-000000000008',
    '{"expected_version":1}'::jsonb
  )
);
insert into metadata_receipts (label, payload)
values (
  'manual-replay',
  public.library_retry_metadata(
    '14000000-0000-0000-0000-000000000001',
    '74000000-0000-0000-0000-000000000001',
    '54000000-0000-0000-0000-000000000008',
    '{"expected_version":1}'::jsonb
  )
);

select ok(
  (select payload ->> 'job_id' from metadata_receipts where label = 'manual-one')
    = (select payload ->> 'job_id' from metadata_receipts where label = 'manual-replay')
  and (
    select count(*) = 1
    from public.processing_jobs
    where item_id = '54000000-0000-0000-0000-000000000008'
      and kind = 'metadata'
      and state in ('queued', 'running', 'retry')
  )
  and (
    select request_count = 1
    from public.api_rate_buckets
    where operation = 'metadata_manual:54000000-0000-0000-0000-000000000008'
  ),
  'same request replay returns the same active job without another job or manual-budget charge'
);

select throws_ok(
  $$select public.library_retry_metadata(
    '14000000-0000-0000-0000-000000000001',
    '74000000-0000-0000-0000-000000000001',
    '54000000-0000-0000-0000-000000000008',
    '{"expected_version":2}'::jsonb
  )$$,
  'P0001',
  'IDEMPOTENCY_MISMATCH',
  'changed retry body under the same request id is rejected'
);

insert into metadata_receipts (label, payload)
values (
  'manual-two',
  public.library_retry_metadata(
    '14000000-0000-0000-0000-000000000001',
    '74000000-0000-0000-0000-000000000002',
    '54000000-0000-0000-0000-000000000008',
    '{"expected_version":2}'::jsonb
  )
),
(
  'manual-three',
  public.library_retry_metadata(
    '14000000-0000-0000-0000-000000000001',
    '74000000-0000-0000-0000-000000000003',
    '54000000-0000-0000-0000-000000000008',
    '{"expected_version":2}'::jsonb
  )
);

select ok(
  (select payload ->> 'job_id' from metadata_receipts where label = 'manual-one')
    = (select payload ->> 'job_id' from metadata_receipts where label = 'manual-two')
  and (select payload ->> 'job_id' from metadata_receipts where label = 'manual-one')
    = (select payload ->> 'job_id' from metadata_receipts where label = 'manual-three')
  and (
    select count(*) = 1
    from public.processing_jobs
    where item_id = '54000000-0000-0000-0000-000000000008'
      and kind = 'metadata'
      and state in ('queued', 'running', 'retry')
  ),
  'different request ids join the one active current-revision metadata job'
);

select throws_ok(
  $$select public.library_retry_metadata(
    '14000000-0000-0000-0000-000000000001',
    '74000000-0000-0000-0000-000000000004',
    '54000000-0000-0000-0000-000000000008',
    '{"expected_version":2}'::jsonb
  )$$,
  'P0001',
  'RATE_LIMITED',
  'manual retry is capped at three accepted requests per item and UTC day'
);
select ok(
  (
    select request_count = 3
    from public.api_rate_buckets
    where operation = 'metadata_manual:54000000-0000-0000-0000-000000000008'
  )
  and not exists (
    select 1
    from public.api_requests
    where request_id = '74000000-0000-0000-0000-000000000004'
  ),
  'the fourth manual attempt is not sticky and does not push the counter above three'
);

select is(
  public.library_retry_metadata(
    '14000000-0000-0000-0000-000000000001',
    '74000000-0000-0000-0000-000000000005',
    '54000000-0000-0000-0000-000000000008',
    '{"expected_version":99}'::jsonb
  ) ->> 'error_code',
  'VERSION_CONFLICT',
  'version conflict is returned before a manual-budget charge'
);
select is(
  public.library_retry_metadata(
    '14000000-0000-0000-0000-000000000001',
    '74000000-0000-0000-0000-000000000005',
    '54000000-0000-0000-0000-000000000008',
    '{"expected_version":99}'::jsonb
  ) ->> 'error_code',
  'VERSION_CONFLICT',
  'deterministic version conflict replays from its minimal receipt'
);

select throws_ok(
  $$select public.library_retry_metadata(
    '14000000-0000-0000-0000-000000000002',
    '74000000-0000-0000-0000-000000000006',
    '54000000-0000-0000-0000-000000000008',
    '{"expected_version":2}'::jsonb
  )$$,
  'P0001',
  'ITEM_NOT_FOUND',
  'an owner cannot retry another owner metadata job'
);

update public.processing_jobs
set state = 'cancelled',
    lease_until = null,
    lease_token = null
where item_id = '54000000-0000-0000-0000-000000000008'
  and state in ('queued', 'running', 'retry');

select pg_temp.seed_metadata_item(
  '54000000-0000-0000-0000-000000000009',
  '64000000-0000-0000-0000-000000000009'
);
insert into metadata_receipts (label, payload)
values ('retry-1', public.library_claim_metadata_jobs(1));
insert into metadata_receipts (label, payload)
values (
  'retry-1-failure',
  public.library_fail_metadata_job(
    '64000000-0000-0000-0000-000000000009',
    (select (payload #>> '{jobs,0,lease_token}')::uuid from metadata_receipts where label = 'retry-1'),
    'NETWORK_ERROR',
    null
  )
);
select ok(
  (
    select private.jsonb_has_exact_keys(
        receipt.payload,
        array['state', 'next_run_at']::text[]
      )
      and receipt.payload ->> 'state' = 'retry'
      and (receipt.payload ->> 'next_run_at')::timestamptz = job.next_run_at
      and job.next_run_at >= job.updated_at + interval '60 seconds'
    from metadata_receipts as receipt
    join public.processing_jobs as job
      on job.id = '64000000-0000-0000-0000-000000000009'
    where receipt.label = 'retry-1-failure'
  ),
  'retry response exposes its exact durable next_run_at after the sixty-second delay'
);

update public.processing_jobs set next_run_at = pg_catalog.clock_timestamp()
where id = '64000000-0000-0000-0000-000000000009';
insert into metadata_receipts (label, payload)
values ('retry-2', public.library_claim_metadata_jobs(1));
select is(
  public.library_fail_metadata_job(
    '64000000-0000-0000-0000-000000000009',
    (select (payload #>> '{jobs,0,lease_token}')::uuid from metadata_receipts where label = 'retry-2'),
    'METADATA_TIMEOUT',
    null
  ) ->> 'state',
  'retry',
  'the second failed fetch remains retryable'
);
select ok(
  (
    select next_run_at >= updated_at + interval '300 seconds'
    from public.processing_jobs
    where id = '64000000-0000-0000-0000-000000000009'
  ),
  'second retry delay is at least five minutes'
);

update public.processing_jobs set next_run_at = pg_catalog.clock_timestamp()
where id = '64000000-0000-0000-0000-000000000009';
insert into metadata_receipts (label, payload)
values ('retry-3', public.library_claim_metadata_jobs(1));
select is(
  public.library_fail_metadata_job(
    '64000000-0000-0000-0000-000000000009',
    (select (payload #>> '{jobs,0,lease_token}')::uuid from metadata_receipts where label = 'retry-3'),
    'RATE_LIMITED',
    2000
  ) ->> 'state',
  'retry',
  'the third failed fetch schedules the final automatic retry'
);
select ok(
  (
    select next_run_at >= updated_at + interval '2000 seconds'
    from public.processing_jobs
    where id = '64000000-0000-0000-0000-000000000009'
  ),
  'Retry-After wins when it exceeds the thirty-minute third delay'
);

update public.processing_jobs set next_run_at = pg_catalog.clock_timestamp()
where id = '64000000-0000-0000-0000-000000000009';
insert into metadata_receipts (label, payload)
values ('retry-4', public.library_claim_metadata_jobs(1));
select is(
  public.library_fail_metadata_job(
    '64000000-0000-0000-0000-000000000009',
    (select (payload #>> '{jobs,0,lease_token}')::uuid from metadata_receipts where label = 'retry-4'),
    'INTERNAL_ERROR',
    null
  ) ->> 'state',
  'failed',
  'the fourth total external attempt is terminal'
);
select ok(
  (
    select attempts = 4 and state = 'failed'
    from public.processing_jobs
    where id = '64000000-0000-0000-0000-000000000009'
  )
  and (
    select request_count = 4
    from public.api_rate_buckets
    where operation = 'metadata_fetch:54000000-0000-0000-0000-000000000009'
  ),
  'first fetch plus three automatic retries is the exact attempt ceiling'
);

select pg_temp.seed_metadata_item(
  '54000000-0000-0000-0000-000000000011',
  '64000000-0000-0000-0000-000000000011'
);
insert into metadata_receipts (label, payload)
values ('retry-after-exceeded-lease', public.library_claim_metadata_jobs(1));
select is(
  public.library_fail_metadata_job(
    '64000000-0000-0000-0000-000000000011',
    (
      select (payload #>> '{jobs,0,lease_token}')::uuid
      from metadata_receipts
      where label = 'retry-after-exceeded-lease'
    ),
    'RETRY_AFTER_EXCEEDED',
    null
  ) ->> 'state',
  'failed',
  'RETRY_AFTER_EXCEEDED is accepted by fail metadata and is immediately permanent'
);
select ok(
  (
    select attempts = 1
      and state = 'failed'
      and last_error_code = 'RETRY_AFTER_EXCEEDED'
    from public.processing_jobs
    where id = '64000000-0000-0000-0000-000000000011'
  ),
  'permanent Retry-After overflow does not consume automatic retries'
);

select pg_temp.seed_metadata_item(
  '54000000-0000-0000-0000-000000000010',
  '64000000-0000-0000-0000-000000000010',
  '14000000-0000-0000-0000-000000000002'
);
update public.beta_members
set enabled = false
where owner_id = '14000000-0000-0000-0000-000000000002';
select is(
  pg_catalog.jsonb_array_length(public.library_claim_metadata_jobs(1) -> 'jobs'),
  0,
  'claim observes beta revocation before external work'
);
select is(
  (select state from public.processing_jobs where id = '64000000-0000-0000-0000-000000000010'),
  'cancelled',
  'revoked owner work is terminally cancelled rather than leased'
);

update public.processing_jobs
set next_run_at = pg_catalog.clock_timestamp() + interval '1 day'
where kind = 'metadata'
  and state in ('queued', 'retry');

select pg_temp.seed_metadata_item(
  ('55000000-0000-0000-0000-' || pg_catalog.lpad(batch.number::text, 12, '0'))::uuid,
  ('65000000-0000-0000-0000-' || pg_catalog.lpad(batch.number::text, 12, '0'))::uuid
)
from pg_catalog.generate_series(1, 11) as batch(number);
insert into metadata_receipts (label, payload)
values ('bounded-claim', public.library_claim_metadata_jobs(100));
select ok(
  (
    select pg_catalog.jsonb_array_length(payload -> 'jobs') = 10
      and (
        select count(distinct leased.value ->> 'lease_token') = 10
        from pg_catalog.jsonb_array_elements(payload -> 'jobs') as leased(value)
      )
    from metadata_receipts
    where label = 'bounded-claim'
  )
  and (
    select count(*) = 1
    from public.processing_jobs
    where id::text like '65000000-0000-0000-0000-%'
      and state = 'queued'
  ),
  'metadata claim clamps oversized batches to ten and fences every lease independently'
);

select throws_ok(
  'select public.library_claim_metadata_jobs(0)',
  'P0001',
  'INVALID_LIMIT',
  'metadata claim rejects an empty worker batch'
);

select throws_ok(
  $$select public.library_fail_metadata_job(
    '65000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000000',
    'raw response body must not be stored',
    null
  )$$,
  'P0001',
  'INVALID_FAILURE',
  'failure storage accepts only the fixed short metadata error taxonomy'
);

select * from finish();
rollback;
