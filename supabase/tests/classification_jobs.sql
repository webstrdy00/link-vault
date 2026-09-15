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
    '12000000-0000-0000-0000-000000000001',
    'authenticated',
    'authenticated',
    'classification-a@example.test',
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
    '12000000-0000-0000-0000-000000000002',
    'authenticated',
    'authenticated',
    'classification-b@example.test',
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
  ('12000000-0000-0000-0000-000000000001', true, now()),
  ('12000000-0000-0000-0000-000000000002', true, now());

insert into public.profiles (id, state)
values
  ('12000000-0000-0000-0000-000000000001', 'active'),
  ('12000000-0000-0000-0000-000000000002', 'active');

insert into public.library_usage (owner_id)
values
  ('12000000-0000-0000-0000-000000000001'),
  ('12000000-0000-0000-0000-000000000002');

with system_category(name, normalized_name, system_code) as (
  values
    ('여행', '여행', 'travel'),
    ('음식·맛집', '음식·맛집', 'food'),
    ('업무·학습', '업무·학습', 'work'),
    ('쇼핑', '쇼핑', 'shopping'),
    ('앱·도구', '앱·도구', 'tools'),
    ('생활·건강', '생활·건강', 'life'),
    ('문화·읽을거리', '문화·읽을거리', 'culture'),
    ('기타', '기타', 'other')
)
insert into public.categories (owner_id, name, normalized_name, kind, system_code)
select owner.owner_id, category.name, category.normalized_name, 'system', category.system_code
from (
  values
    ('12000000-0000-0000-0000-000000000001'::uuid),
    ('12000000-0000-0000-0000-000000000002'::uuid)
) as owner(owner_id)
cross join system_category as category;

insert into public.categories (id, owner_id, name, normalized_name, kind)
values
  (
    '32000000-0000-0000-0000-000000000001',
    '12000000-0000-0000-0000-000000000001',
    '내분류1',
    '내분류1',
    'custom'
  ),
  (
    '32000000-0000-0000-0000-000000000002',
    '12000000-0000-0000-0000-000000000001',
    '내분류2',
    '내분류2',
    'custom'
  ),
  (
    '32000000-0000-0000-0000-000000000003',
    '12000000-0000-0000-0000-000000000001',
    '내분류3',
    '내분류3',
    'custom'
  ),
  (
    '32000000-0000-0000-0000-000000000004',
    '12000000-0000-0000-0000-000000000001',
    '내분류4',
    '내분류4',
    'custom'
  ),
  (
    '32000000-0000-0000-0000-000000000005',
    '12000000-0000-0000-0000-000000000001',
    '내분류5',
    '내분류5',
    'custom'
  );

create temporary table classification_receipts (
  label text primary key,
  payload jsonb not null
);
grant select, insert, update, delete on table classification_receipts to service_role;

create function pg_temp.classification_result(p_revision integer)
returns jsonb
language sql
immutable
set search_path = ''
as $$
  select pg_catalog.jsonb_build_object(
    'rules_version', 'rules-v2.0.0',
    'target_revision', p_revision,
    'categories', pg_catalog.jsonb_build_array(
      pg_catalog.jsonb_build_object(
        'code', 'work',
        'score', 3,
        'rules', pg_catalog.jsonb_build_array(
          pg_catalog.jsonb_build_object(
            'id', 'work:strong:0',
            'fields', pg_catalog.jsonb_build_array('note')
          )
        )
      )
    )
  );
$$;

create function pg_temp.three_category_result(p_revision integer)
returns jsonb
language sql
immutable
set search_path = ''
as $$
  select pg_catalog.jsonb_build_object(
    'rules_version', 'rules-v2.0.0',
    'target_revision', p_revision,
    'categories', pg_catalog.jsonb_build_array(
      pg_catalog.jsonb_build_object(
        'code', 'travel',
        'score', 3,
        'rules', pg_catalog.jsonb_build_array(
          pg_catalog.jsonb_build_object(
            'id', 'travel:strong:0',
            'fields', pg_catalog.jsonb_build_array('note')
          )
        )
      ),
      pg_catalog.jsonb_build_object(
        'code', 'food',
        'score', 3,
        'rules', pg_catalog.jsonb_build_array(
          pg_catalog.jsonb_build_object(
            'id', 'food:strong:0',
            'fields', pg_catalog.jsonb_build_array('note')
          )
        )
      ),
      pg_catalog.jsonb_build_object(
        'code', 'work',
        'score', 3,
        'rules', pg_catalog.jsonb_build_array(
          pg_catalog.jsonb_build_object(
            'id', 'work:strong:0',
            'fields', pg_catalog.jsonb_build_array('note')
          )
        )
      )
    )
  );
$$;

create function pg_temp.seed_classification_item(
  p_item_id uuid,
  p_job_id uuid,
  p_owner_id uuid,
  p_version integer default 1,
  p_revision integer default 1,
  p_manual boolean default false,
  p_deleted boolean default false,
  p_custom_count integer default 0
)
returns void
language plpgsql
set search_path = ''
as $$
declare
  item_url text := 'https://example.com/classification/' || p_item_id::text;
  normalized_category_names text;
begin
  select coalesce(
    pg_catalog.string_agg(category.normalized_name, ' ' order by category.id),
    ''
  )
  into normalized_category_names
  from public.categories as category
  where category.owner_id = p_owner_id
    and category.id in (
      '32000000-0000-0000-0000-000000000001',
      '32000000-0000-0000-0000-000000000002',
      '32000000-0000-0000-0000-000000000003',
      '32000000-0000-0000-0000-000000000004',
      '32000000-0000-0000-0000-000000000005'
    )
    and pg_catalog.right(category.id::text, 12)::integer <= p_custom_count;

  insert into public.items (
    id,
    owner_id,
    original_url,
    normalized_url,
    url_hash,
    source,
    display_fallback,
    note,
    text_revision,
    version,
    metadata_state,
    deleted_at
  )
  values (
    p_item_id,
    p_owner_id,
    item_url,
    item_url,
    pg_catalog.encode(
      extensions.digest(pg_catalog.convert_to(item_url, 'UTF8'), 'sha256'),
      'hex'
    ),
    'other',
    'example.com',
    '엑셀 자료',
    p_revision,
    p_version,
    'ready',
    case when p_deleted then pg_catalog.clock_timestamp() else null end
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
      'user_title', '',
      'fetched_title', '',
      'note', '엑셀 자료',
      'ocr', '',
      'shared', '',
      'description', '',
      'body', '',
      'categories', normalized_category_names,
      'url', item_url
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

  insert into public.item_category_controls (owner_id, item_id, manual_override)
  values (p_owner_id, p_item_id, p_manual);

  insert into public.item_categories (owner_id, item_id, category_id, origin)
  select p_owner_id, p_item_id, category.id, 'manual'
  from public.categories as category
  where category.owner_id = p_owner_id
    and category.id in (
      '32000000-0000-0000-0000-000000000001',
      '32000000-0000-0000-0000-000000000002',
      '32000000-0000-0000-0000-000000000003',
      '32000000-0000-0000-0000-000000000004',
      '32000000-0000-0000-0000-000000000005'
    )
    and pg_catalog.right(category.id::text, 12)::integer <= p_custom_count
  order by category.id;

  insert into public.processing_jobs (
    id,
    owner_id,
    item_id,
    kind,
    target_revision,
    state
  )
  values (p_job_id, p_owner_id, p_item_id, 'classify', p_revision, 'queued');
end;
$$;

select ok(
  (
    select bool_and(installed_function.prosecdef)
      and bool_and(
        exists (
          select 1
          from pg_catalog.unnest(installed_function.proconfig) as setting
          where setting like 'search_path=%'
        )
      )
    from pg_catalog.pg_proc as installed_function
    where installed_function.oid in (
      'public.library_claim_classification_jobs(integer)'::regprocedure,
      'public.library_complete_classification_job(uuid,uuid,integer,jsonb)'::regprocedure,
      'public.library_fail_classification_job(uuid,uuid,boolean,text)'::regprocedure,
      'private.dispatch_classification_jobs()'::regprocedure
    )
  ),
  'classification worker and dispatcher functions are SECURITY DEFINER with fixed search paths'
);

select ok(
  has_function_privilege(
    'service_role',
    'public.library_claim_classification_jobs(integer)',
    'EXECUTE'
  )
  and has_function_privilege(
    'service_role',
    'public.library_complete_classification_job(uuid,uuid,integer,jsonb)',
    'EXECUTE'
  )
  and has_function_privilege(
    'service_role',
    'public.library_fail_classification_job(uuid,uuid,boolean,text)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated',
    'public.library_claim_classification_jobs(integer)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'public.library_complete_classification_job(uuid,uuid,integer,jsonb)',
    'EXECUTE'
  ),
  'only service_role can execute classification worker RPCs'
);

select ok(
  not has_function_privilege(
    'service_role',
    'private.dispatch_classification_jobs()',
    'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated',
    'private.dispatch_classification_jobs()',
    'EXECUTE'
  )
  and not has_schema_privilege('authenticated', 'vault', 'USAGE')
  and not has_schema_privilege('anon', 'vault', 'USAGE')
  and not has_table_privilege('authenticated', 'vault.decrypted_secrets', 'SELECT')
  and not has_table_privilege('anon', 'vault.decrypted_secrets', 'SELECT'),
  'dispatcher execution and decrypted worker secrets are not exposed'
);

select ok(
  exists (
    select 1
    from cron.job as job
    where job.jobname = 'link-vault-processing'
      and job.schedule = '* * * * *'
      and job.command = 'select private.dispatch_classification_jobs(); select private.dispatch_enrichment_jobs();'
  ),
  'the shared minute scheduler dispatches classification and enrichment together'
);

select ok(
  private.valid_classification_result(pg_temp.classification_result(1)),
  'the sealed strong-rule result contract is accepted'
);

select ok(
  not private.valid_classification_result(
    pg_temp.classification_result(1)
      || '{"categories":[{"code":"other","score":3,"rules":[{"id":"other:strong:0","fields":["note"]}]}]}'::jsonb
  )
  and not private.valid_classification_result(
    '{"rules_version":"rules-v2.0.0","target_revision":1,"categories":[{"code":"work","score":2,"rules":[{"id":"work:strong:0","fields":["note"]}]}]}'::jsonb
  )
  and not private.valid_classification_result(
    '{"rules_version":"rules-v2.0.0","target_revision":1,"categories":[{"code":"work","score":3,"rules":[{"id":"work:strong:8","fields":["note"]}]}]}'::jsonb
  )
  and not private.valid_classification_result(
    '{"rules_version":"rules-v2.0.0","target_revision":1,"categories":[{"code":"work","score":3,"rules":[{"id":"work:strong:0","fields":["url"]}]}]}'::jsonb
  )
  and not private.valid_classification_result(
    '{"rules_version":"rules-v2.0.0","target_revision":1,"categories":[{"code":"work","score":3,"rules":[{"id":"work:strong:0","fields":["note","note"]}]}]}'::jsonb
  ),
  'other, score mismatch, unknown rules, forbidden fields and duplicate fields are rejected'
);

select ok(
  not private.valid_classification_result(
    '{"rules_version":"rules-v2.0.0","target_revision":1,"categories":[{"code":"work","score":3,"rules":[{"id":"work:strong:0","fields":["note"]}]},{"code":"travel","score":3,"rules":[{"id":"travel:strong:0","fields":["note"]}]}]}'::jsonb
  )
  and not private.valid_classification_result(
    '{"rules_version":"rules-v2.0.0","target_revision":1,"categories":[{"code":"work","score":3,"rules":[{"id":"work:weak:0","fields":["note"]},{"id":"work:weak:1","fields":["body"]},{"id":"work:weak:2","fields":["shared"]}]}]}'::jsonb
  ),
  'sealed tie ranking and same-field weak qualification are enforced'
);

set local role service_role;

select pg_temp.seed_classification_item(
  '52000000-0000-0000-0000-000000000090',
  '62000000-0000-0000-0000-000000000090',
  '12000000-0000-0000-0000-000000000001',
  p_manual := true
);
select pg_temp.seed_classification_item(
  '52000000-0000-0000-0000-000000000091',
  '62000000-0000-0000-0000-000000000091',
  '12000000-0000-0000-0000-000000000001',
  p_deleted := true
);

select is(
  pg_catalog.jsonb_array_length(public.library_claim_classification_jobs(20) -> 'jobs'),
  0,
  'claim does not lease manual or deleted classification jobs'
);
select ok(
  (
    select count(*) = 2 and bool_and(state = 'cancelled')
    from public.processing_jobs
    where id in (
      '62000000-0000-0000-0000-000000000090',
      '62000000-0000-0000-0000-000000000091'
    )
  ),
  'claim cancels obsolete and manual jobs instead of applying them'
);

select pg_temp.seed_classification_item(
  '52000000-0000-0000-0000-000000000001',
  '62000000-0000-0000-0000-000000000001',
  '12000000-0000-0000-0000-000000000001',
  p_custom_count := 1
);

insert into classification_receipts (label, payload)
values ('lease-a', public.library_claim_classification_jobs(1));

select is(
  pg_catalog.jsonb_array_length(
    (select payload -> 'jobs' from classification_receipts where label = 'lease-a')
  ),
  1,
  'a queued classification job is leased'
);

select ok(
  (
    select (payload #> '{jobs,0}') ?& array[
      'job_id',
      'lease_token',
      'owner_id',
      'target_revision',
      'item'
    ]
      and (payload #> '{jobs,0,item}') ?& array['version', 'text_revision', 'category_refs']
      and (payload #> '{jobs,0,item,category_refs,0}') ?& array[
        'id',
        'name',
        'kind',
        'system_code',
        'origin'
      ]
    from classification_receipts
    where label = 'lease-a'
  ),
  'claim returns the exact lease envelope and a full current item snapshot'
);

select is(
  (
    select payload #>> '{jobs,0,item,category_refs,0,kind}'
    from classification_receipts
    where label = 'lease-a'
  ),
  'custom',
  'category refs distinguish custom categories from system categories'
);

update public.processing_jobs
set lease_until = pg_catalog.clock_timestamp() - interval '1 second'
where id = '62000000-0000-0000-0000-000000000001';

insert into classification_receipts (label, payload)
values ('lease-b', public.library_claim_classification_jobs(1));

select isnt(
  (
    select payload #>> '{jobs,0,lease_token}'
    from classification_receipts
    where label = 'lease-a'
  ),
  (
    select payload #>> '{jobs,0,lease_token}'
    from classification_receipts
    where label = 'lease-b'
  ),
  'an expired running job receives a fresh lease token'
);

select is(
  public.library_complete_classification_job(
    '62000000-0000-0000-0000-000000000001',
    (
      select (payload #>> '{jobs,0,lease_token}')::uuid
      from classification_receipts
      where label = 'lease-a'
    ),
    1,
    pg_temp.classification_result(1)
  ) ->> 'state',
  'discarded',
  'worker A cannot commit with its stale lease'
);

select is(
  (select version from public.items where id = '52000000-0000-0000-0000-000000000001'),
  1,
  'a stale lease changes no item data'
);

insert into classification_receipts (label, payload)
select
  'lease-b-complete',
  public.library_complete_classification_job(
    '62000000-0000-0000-0000-000000000001',
    (
      select (payload #>> '{jobs,0,lease_token}')::uuid
      from classification_receipts
      where label = 'lease-b'
    ),
    1,
    pg_temp.classification_result(1)
  );

select is(
  (select payload ->> 'state' from classification_receipts where label = 'lease-b-complete'),
  'succeeded',
  'worker B commits the current lease exactly once'
);

select ok(
  (
    select state = 'succeeded'
      and attempts = 2
      and lease_token is null
      and lease_until is null
    from public.processing_jobs
    where id = '62000000-0000-0000-0000-000000000001'
  )
  and (
    select version = 2 and text_revision = 1
    from public.items
    where id = '52000000-0000-0000-0000-000000000001'
  ),
  'queued to leased to re-leased to succeeded preserves revision and increments version once'
);

select is(
  public.library_complete_classification_job(
    '62000000-0000-0000-0000-000000000001',
    (
      select (payload #>> '{jobs,0,lease_token}')::uuid
      from classification_receipts
      where label = 'lease-b'
    ),
    1,
    pg_temp.classification_result(1)
  ) ->> 'state',
  'discarded',
  'completion is idempotent after the lease is consumed'
);

select is(
  (select version from public.items where id = '52000000-0000-0000-0000-000000000001'),
  2,
  'a repeated completion does not increment item version again'
);
select is(
  public.library_fail_classification_job(
    '62000000-0000-0000-0000-000000000001',
    (
      select (payload #>> '{jobs,0,lease_token}')::uuid
      from classification_receipts
      where label = 'lease-b'
    ),
    true,
    'INTERNAL_ERROR'
  ) ->> 'state',
  'discarded',
  'failure reporting also uses the consumed lease as a compare-and-set token'
);

select pg_temp.seed_classification_item(
  '52000000-0000-0000-0000-000000000002',
  '62000000-0000-0000-0000-000000000002',
  '12000000-0000-0000-0000-000000000001',
  p_version := 5
);
insert into classification_receipts (label, payload)
values ('version-lease', public.library_claim_classification_jobs(1));

update public.items
set version = 6,
    updated_at = pg_catalog.clock_timestamp()
where id = '52000000-0000-0000-0000-000000000002';

insert into classification_receipts (label, payload)
select
  'version-conflict',
  public.library_complete_classification_job(
    '62000000-0000-0000-0000-000000000002',
    (
      select (payload #>> '{jobs,0,lease_token}')::uuid
      from classification_receipts
      where label = 'version-lease'
    ),
    5,
    pg_temp.classification_result(1)
  );

select ok(
  (
    select payload ->> 'state' = 'version_conflict'
      and (payload #>> '{item,version}')::integer = 6
      and (payload #>> '{item,text_revision}')::integer = 1
    from classification_receipts
    where label = 'version-conflict'
  )
  and (
    select state = 'running'
    from public.processing_jobs
    where id = '62000000-0000-0000-0000-000000000002'
  ),
  'version-only conflict returns the latest snapshot and retains the valid lease for recomputation'
);

select is(
  public.library_complete_classification_job(
    '62000000-0000-0000-0000-000000000002',
    (
      select (payload #>> '{jobs,0,lease_token}')::uuid
      from classification_receipts
      where label = 'version-lease'
    ),
    6,
    pg_temp.classification_result(1)
  ) ->> 'state',
  'succeeded',
  'the worker can recompute from the version-conflict snapshot without another lease'
);

select pg_temp.seed_classification_item(
  '52000000-0000-0000-0000-000000000003',
  '62000000-0000-0000-0000-000000000003',
  '12000000-0000-0000-0000-000000000001'
);
insert into classification_receipts (label, payload)
values ('stale-revision-lease', public.library_claim_classification_jobs(1));

update public.items
set version = 2,
    text_revision = 2,
    updated_at = pg_catalog.clock_timestamp()
where id = '52000000-0000-0000-0000-000000000003';
update public.item_search
set text_revision = 2
where item_id = '52000000-0000-0000-0000-000000000003';
update public.item_classification
set target_revision = 2,
    state = 'pending'
where item_id = '52000000-0000-0000-0000-000000000003';
insert into public.processing_jobs (
  id,
  owner_id,
  item_id,
  kind,
  target_revision,
  state
)
values (
  '62000000-0000-0000-0000-000000000103',
  '12000000-0000-0000-0000-000000000001',
  '52000000-0000-0000-0000-000000000003',
  'classify',
  2,
  'queued'
);

select is(
  public.library_complete_classification_job(
    '62000000-0000-0000-0000-000000000003',
    (
      select (payload #>> '{jobs,0,lease_token}')::uuid
      from classification_receipts
      where label = 'stale-revision-lease'
    ),
    1,
    pg_temp.classification_result(1)
  ) ->> 'state',
  'discarded',
  'a changed text revision discards instead of reporting a version-only conflict'
);

select ok(
  (
    select state = 'cancelled'
    from public.processing_jobs
    where id = '62000000-0000-0000-0000-000000000003'
  )
  and (
    select state = 'queued'
    from public.processing_jobs
    where id = '62000000-0000-0000-0000-000000000103'
  )
  and not exists (
    select 1
    from public.item_categories as selected
    join public.categories as category
      on category.owner_id = selected.owner_id
      and category.id = selected.category_id
    where selected.item_id = '52000000-0000-0000-0000-000000000003'
      and category.kind = 'system'
  ),
  'revision-stale completion applies nothing and leaves the current revision job intact'
);
update public.processing_jobs
set state = 'cancelled'
where id = '62000000-0000-0000-0000-000000000103';

select pg_temp.seed_classification_item(
  '52000000-0000-0000-0000-000000000004',
  '62000000-0000-0000-0000-000000000004',
  '12000000-0000-0000-0000-000000000001'
);
insert into classification_receipts (label, payload)
values ('manual-lease', public.library_claim_classification_jobs(1));
update public.item_category_controls
set manual_override = true
where item_id = '52000000-0000-0000-0000-000000000004';
update public.item_classification
set state = 'manual'
where item_id = '52000000-0000-0000-0000-000000000004';

select is(
  public.library_complete_classification_job(
    '62000000-0000-0000-0000-000000000004',
    (
      select (payload #>> '{jobs,0,lease_token}')::uuid
      from classification_receipts
      where label = 'manual-lease'
    ),
    1,
    pg_temp.classification_result(1)
  ) ->> 'state',
  'discarded',
  'a late result cannot override a manual classification choice'
);

select ok(
  (select version = 1 from public.items where id = '52000000-0000-0000-0000-000000000004')
  and (
    select state = 'cancelled'
    from public.processing_jobs
    where id = '62000000-0000-0000-0000-000000000004'
  ),
  'manual override cancellation has zero item writes'
);

select pg_temp.seed_classification_item(
  '52000000-0000-0000-0000-000000000005',
  '62000000-0000-0000-0000-000000000005',
  '12000000-0000-0000-0000-000000000001'
);
insert into classification_receipts (label, payload)
values ('delete-lease', public.library_claim_classification_jobs(1));
update public.items
set deleted_at = pg_catalog.clock_timestamp()
where id = '52000000-0000-0000-0000-000000000005';

select is(
  public.library_complete_classification_job(
    '62000000-0000-0000-0000-000000000005',
    (
      select (payload #>> '{jobs,0,lease_token}')::uuid
      from classification_receipts
      where label = 'delete-lease'
    ),
    1,
    pg_temp.classification_result(1)
  ) ->> 'state',
  'discarded',
  'a late result cannot modify a deleted item'
);
select is(
  (select version from public.items where id = '52000000-0000-0000-0000-000000000005'),
  1,
  'deleted-item rejection performs zero classification writes'
);

select pg_temp.seed_classification_item(
  '52000000-0000-0000-0000-000000000006',
  '62000000-0000-0000-0000-000000000006',
  '12000000-0000-0000-0000-000000000002'
);
insert into classification_receipts (label, payload)
values ('revoked-lease', public.library_claim_classification_jobs(1));
reset role;
update public.beta_members
set enabled = false
where owner_id = '12000000-0000-0000-0000-000000000002';
set local role service_role;

select is(
  public.library_complete_classification_job(
    '62000000-0000-0000-0000-000000000006',
    (
      select (payload #>> '{jobs,0,lease_token}')::uuid
      from classification_receipts
      where label = 'revoked-lease'
    ),
    1,
    pg_temp.classification_result(1)
  ) ->> 'state',
  'discarded',
  'a late result cannot modify an item after member revocation'
);
select is(
  (select version from public.items where id = '52000000-0000-0000-0000-000000000006'),
  1,
  'member revocation rejection performs zero item writes'
);

select pg_temp.seed_classification_item(
  '52000000-0000-0000-0000-000000000007',
  '62000000-0000-0000-0000-000000000007',
  '12000000-0000-0000-0000-000000000001',
  p_custom_count := 3
);
update public.items
set note = '여행 맛집 엑셀 자료'
where id = '52000000-0000-0000-0000-000000000007';
update public.item_search
set normalized_fields = normalized_fields || pg_catalog.jsonb_build_object(
  'note',
  '여행 맛집 엑셀 자료'
)
where item_id = '52000000-0000-0000-0000-000000000007';
insert into classification_receipts (label, payload)
values ('capacity-lease', public.library_claim_classification_jobs(1));
select is(
  public.library_complete_classification_job(
    '62000000-0000-0000-0000-000000000007',
    (
      select (payload #>> '{jobs,0,lease_token}')::uuid
      from classification_receipts
      where label = 'capacity-lease'
    ),
    1,
    pg_temp.three_category_result(1)
  ) ->> 'state',
  'succeeded',
  'classification succeeds while respecting remaining category capacity'
);

select ok(
  (
    select count(*) = 5
      and count(*) filter (where category.kind = 'custom') = 3
      and count(*) filter (where category.kind = 'system') = 2
    from public.item_categories as selected
    join public.categories as category
      on category.owner_id = selected.owner_id
      and category.id = selected.category_id
    where selected.item_id = '52000000-0000-0000-0000-000000000007'
  )
  and (
    select pg_catalog.jsonb_array_length(reasons) = 2
      and reasons #>> '{0,code}' = 'travel'
      and reasons #>> '{1,code}' = 'food'
    from public.item_classification
    where item_id = '52000000-0000-0000-0000-000000000007'
  ),
  'three custom links are preserved and only the top two automatic categories and reasons are stored'
);

select is(
  (
    select array_agg(token order by token collate "C")
    from public.item_search,
      lateral unnest(string_to_array(normalized_fields ->> 'categories', ' ')) as token
    where item_id = '52000000-0000-0000-0000-000000000007'
  ),
  array['내분류1', '내분류2', '내분류3', '여행', '음식·맛집']::text[],
  'search categories are rebuilt from canonical normalized category names'
);

select ok(
  (
    select version = 2 and text_revision = 1
    from public.items
    where id = '52000000-0000-0000-0000-000000000007'
  )
  and not exists (
    select 1
    from public.processing_jobs
    where item_id = '52000000-0000-0000-0000-000000000007'
      and state in ('queued', 'running', 'retry')
  ),
  'classification changes item version only and creates no self-classification loop'
);

reset role;
update public.items
set text_revision = 2
where id = '52000000-0000-0000-0000-000000000007';
update public.item_search
set text_revision = 2
where item_id = '52000000-0000-0000-0000-000000000007';
select is(
  private.library_item_json(
    '12000000-0000-0000-0000-000000000001',
    '52000000-0000-0000-0000-000000000007',
    true
  ) -> 'classification_reasons',
  '[]'::jsonb,
  'classification reasons are hidden when their target revision is stale'
);
set local role service_role;

select pg_temp.seed_classification_item(
  '52000000-0000-0000-0000-000000000009',
  '62000000-0000-0000-0000-000000000009',
  '12000000-0000-0000-0000-000000000001'
);
insert into classification_receipts (label, payload)
values ('failure-lease-1', public.library_claim_classification_jobs(1));
select is(
  public.library_fail_classification_job(
    '62000000-0000-0000-0000-000000000009',
    (
      select (payload #>> '{jobs,0,lease_token}')::uuid
      from classification_receipts
      where label = 'failure-lease-1'
    ),
    true,
    'WORKER_TIMEOUT'
  ) ->> 'state',
  'retry',
  'the first retryable failure is delayed for retry'
);

select ok(
  (
    select attempts = 1
      and state = 'retry'
      and next_run_at >= pg_catalog.clock_timestamp() + interval '55 seconds'
      and last_error_code = 'WORKER_TIMEOUT'
      and lease_token is null
    from public.processing_jobs
    where id = '62000000-0000-0000-0000-000000000009'
  ),
  'first failure records only a stable code and a one-minute minimum delay'
);
update public.processing_jobs
set next_run_at = pg_catalog.clock_timestamp() - interval '1 second'
where id = '62000000-0000-0000-0000-000000000009';
insert into classification_receipts (label, payload)
values ('failure-lease-2', public.library_claim_classification_jobs(1));
select is(
  public.library_fail_classification_job(
    '62000000-0000-0000-0000-000000000009',
    (
      select (payload #>> '{jobs,0,lease_token}')::uuid
      from classification_receipts
      where label = 'failure-lease-2'
    ),
    true,
    'INTERNAL_ERROR'
  ) ->> 'state',
  'retry',
  'the second retryable failure remains retryable'
);
select ok(
  (
    select attempts = 2
      and next_run_at >= pg_catalog.clock_timestamp() + interval '4 minutes 55 seconds'
    from public.processing_jobs
    where id = '62000000-0000-0000-0000-000000000009'
  ),
  'the second retry uses the five-minute minimum delay'
);
update public.processing_jobs
set next_run_at = pg_catalog.clock_timestamp() - interval '1 second'
where id = '62000000-0000-0000-0000-000000000009';
insert into classification_receipts (label, payload)
values ('failure-lease-3', public.library_claim_classification_jobs(1));
select is(
  public.library_fail_classification_job(
    '62000000-0000-0000-0000-000000000009',
    (
      select (payload #>> '{jobs,0,lease_token}')::uuid
      from classification_receipts
      where label = 'failure-lease-3'
    ),
    true,
    'WORKER_BUSY'
  ) ->> 'state',
  'failed',
  'the third total claim exhausts the retry budget'
);

update public.processing_jobs
set state = 'retry',
    attempts = 3,
    next_run_at = pg_catalog.clock_timestamp() - interval '1 second',
    lease_until = null,
    lease_token = null
where id = '62000000-0000-0000-0000-000000000009';
select is(
  pg_catalog.jsonb_array_length(public.library_claim_classification_jobs(1) -> 'jobs'),
  0,
  'claim does not create a fourth attempt'
);
select is(
  (select state from public.processing_jobs where id = '62000000-0000-0000-0000-000000000009'),
  'failed',
  'an exhausted eligible job is sealed as failed'
);

select pg_temp.seed_classification_item(
  '52000000-0000-0000-0000-000000000010',
  '62000000-0000-0000-0000-000000000010',
  '12000000-0000-0000-0000-000000000001'
);
insert into classification_receipts (label, payload)
values ('invalid-result-lease', public.library_claim_classification_jobs(1));
select is(
  public.library_fail_classification_job(
    '62000000-0000-0000-0000-000000000010',
    (
      select (payload #>> '{jobs,0,lease_token}')::uuid
      from classification_receipts
      where label = 'invalid-result-lease'
    ),
    true,
    'INVALID_RESULT'
  ) ->> 'state',
  'failed',
  'INVALID_RESULT is never retried even when the caller marks it retryable'
);

select throws_ok(
  $$select public.library_fail_classification_job(
    '62000000-0000-0000-0000-000000000010',
    '00000000-0000-0000-0000-000000000000',
    true,
    'raw user payload must not be logged'
  )$$,
  'P0001',
  'INVALID_FAILURE',
  'failure storage accepts only the sealed short error-code allowlist'
);

select pg_temp.seed_classification_item(
  ('53000000-0000-0000-0000-' || pg_catalog.lpad(batch.number::text, 12, '0'))::uuid,
  ('63000000-0000-0000-0000-' || pg_catalog.lpad(batch.number::text, 12, '0'))::uuid,
  '12000000-0000-0000-0000-000000000001'
)
from pg_catalog.generate_series(1, 21) as batch(number);

insert into classification_receipts (label, payload)
values ('bounded-claim', public.library_claim_classification_jobs(100));
select ok(
  (
    select pg_catalog.jsonb_array_length(payload -> 'jobs') = 20
      and (
        select count(distinct leased.value ->> 'lease_token') = 20
        from pg_catalog.jsonb_array_elements(payload -> 'jobs') as leased(value)
      )
    from classification_receipts
    where label = 'bounded-claim'
  )
  and (
    select count(*) = 1
    from public.processing_jobs
    where id::text like '63000000-0000-0000-0000-%'
      and state = 'queued'
  ),
  'claim clamps oversized batches to twenty and issues a distinct token for every lease'
);

select throws_ok(
  'select public.library_claim_classification_jobs(0)',
  'P0001',
  'INVALID_LIMIT',
  'claim rejects an empty or negative worker batch'
);

reset role;

select is(
  private.library_item_json(
    '12000000-0000-0000-0000-000000000001',
    '52000000-0000-0000-0000-000000000004',
    true
  ) ->> 'manual_override',
  'true',
  'detail exposes the authoritative manual classification lock'
);
select is(
  private.library_item_json(
    '12000000-0000-0000-0000-000000000001',
    '52000000-0000-0000-0000-000000000007',
    true
  ) ->> 'manual_override',
  'false',
  'automatic detail explicitly exposes an unlocked classification state'
);

-- Only secret names change inside this rolled-back test transaction; values
-- are neither read nor printed, and the running scheduler cannot see the edit.
do $hide_fixture$
declare
  entry record;
begin
  for entry in
    select id, name from vault.secrets
    where name in ('link_vault_worker_url', 'link_vault_worker_token')
  loop
    perform vault.update_secret(
      entry.id, null, entry.name || '_fixture_' || gen_random_uuid()::text
    );
  end loop;
end
$hide_fixture$;
select throws_ok(
  'select private.dispatch_classification_jobs()',
  'P0001',
  'CLASSIFICATION_WORKER_NOT_CONFIGURED',
  'due work without worker secrets reports an operational failure'
);
do $fixture$
begin
  perform vault.create_secret(' ', 'link_vault_worker_url');
  perform vault.create_secret('fixture-only-token', 'link_vault_worker_token');
end
$fixture$;
select throws_ok(
  'select private.dispatch_classification_jobs()',
  'P0001',
  'CLASSIFICATION_WORKER_NOT_CONFIGURED',
  'blank configuration is not treated as a usable worker endpoint'
);
update public.processing_jobs
set state = 'cancelled', lease_token = null, lease_until = null
where owner_id in (
  '12000000-0000-0000-0000-000000000001',
  '12000000-0000-0000-0000-000000000002'
);
select is(
  private.dispatch_classification_jobs(),
  null::bigint,
  'an idle queue does not require configuration or produce false failures'
);
select is(
  private.dispatch_enrichment_jobs(),
  '{"metadata_request_id":null,"cleanup_request_id":null}'::jsonb,
  'idle enrichment does not issue network work or require worker secrets'
);
insert into public.processing_jobs (owner_id, item_id, kind, target_revision)
select owner_id, id, 'metadata', text_revision
from public.items where id = '52000000-0000-0000-0000-000000000007';
select throws_ok(
  'select private.dispatch_enrichment_jobs()',
  'P0001',
  'ENRICHMENT_WORKER_NOT_CONFIGURED',
  'due metadata with a blank worker endpoint fails visibly rather than appearing idle'
);
select ok(
  not has_function_privilege('authenticated', 'private.dispatch_enrichment_jobs()', 'EXECUTE')
  and not has_function_privilege('service_role', 'private.dispatch_enrichment_jobs()', 'EXECUTE')
  and not exists (select 1 from cron.job where jobname = 'link-vault-classify'),
  'enrichment scheduling is private and replaces rather than duplicates the old cron job'
);

select * from finish();
rollback;
