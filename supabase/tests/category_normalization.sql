begin;

create extension if not exists pgtap with schema extensions;
set local search_path = pg_catalog, extensions, public;
select no_plan();

select ok(
  has_function_privilege(
    'service_role',
    'public.library_backfill_category_normalization(uuid,jsonb)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated',
    'public.library_backfill_category_normalization(uuid,jsonb)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'anon',
    'public.library_backfill_category_normalization(uuid,jsonb)',
    'EXECUTE'
  )
  and pg_catalog.to_regprocedure(
    'public.library_backfill_category_normalization(jsonb)'
  ) is null,
  'only the service role can execute the normalization backfill RPC'
);

select ok(
  not has_table_privilege(
    'service_role',
    'private.category_normalization_authorizations',
    'INSERT'
  )
  and not has_function_privilege(
    'service_role',
    'private.assert_category_normalization_ready(uuid)',
    'EXECUTE'
  ),
  'the controlled bypass and readiness helper are not service-callable'
);

select throws_ok(
  $$
    select public.library_backfill_category_normalization(
      '81000000-0000-0000-0000-000000000001',
      (
        select pg_catalog.jsonb_agg(
          pg_catalog.jsonb_build_object(
            'id', (
              '84000000-0000-0000-0000-' ||
              pg_catalog.lpad(number.value::text, 12, '0')
            ),
            'owner_id', '81000000-0000-0000-0000-000000000001',
            'name', 'bounded ' || number.value,
            'normalized_name', 'bounded ' || number.value,
            'normalization_version', 0
          )
        )
        from pg_catalog.generate_series(1, 39) as number(value)
      )
    )
  $$,
  'P0001',
  'CATEGORY_NORMALIZATION_SNAPSHOT_INVALID',
  'the service RPC rejects a per-owner snapshot larger than thirty-eight categories'
);

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
    '81000000-0000-0000-0000-000000000001',
    'authenticated',
    'authenticated',
    'normalization-a@example.test',
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
    '81000000-0000-0000-0000-000000000002',
    'authenticated',
    'authenticated',
    'normalization-b@example.test',
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
  ('81000000-0000-0000-0000-000000000001', true, now()),
  ('81000000-0000-0000-0000-000000000002', true, now());

insert into public.profiles (id, state)
values
  ('81000000-0000-0000-0000-000000000001', 'active'),
  ('81000000-0000-0000-0000-000000000002', 'active');

insert into public.library_usage (owner_id, active_item_count)
values
  ('81000000-0000-0000-0000-000000000001', 1),
  ('81000000-0000-0000-0000-000000000002', 1);

insert into public.items (
  id,
  owner_id,
  original_url,
  normalized_url,
  url_hash,
  source,
  display_fallback,
  text_revision,
  version,
  metadata_state,
  created_at,
  updated_at
)
values
  (
    '82000000-0000-0000-0000-000000000001',
    '81000000-0000-0000-0000-000000000001',
    'https://example.com/normalization-a',
    'https://example.com/normalization-a',
    pg_catalog.repeat('a', 64),
    'other',
    'Normalization A',
    4,
    7,
    'ready',
    '2026-01-01T00:00:00Z',
    '2026-01-01T00:00:00Z'
  ),
  (
    '82000000-0000-0000-0000-000000000002',
    '81000000-0000-0000-0000-000000000002',
    'https://example.com/normalization-b',
    'https://example.com/normalization-b',
    pg_catalog.repeat('b', 64),
    'other',
    'Normalization B',
    2,
    3,
    'ready',
    '2026-01-02T00:00:00Z',
    '2026-01-02T00:00:00Z'
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
  cue_flags,
  updated_at
)
values
  (
    '81000000-0000-0000-0000-000000000001',
    '82000000-0000-0000-0000-000000000001',
    4,
    'search-v2.0.0',
    '{"user_title":"","fetched_title":"","note":"","ocr":"","shared":"","description":"","body":"","categories":"ｆｏｏ a  b İtest","url":"https://example.com/normalization-a"}'::jsonb,
    '{"user_title":[],"fetched_title":[],"note":[],"ocr":[],"shared":[],"description":[],"body":[]}'::jsonb,
    'cues-v1.0.0',
    'available',
    '[]'::jsonb,
    '2026-01-01T00:00:00Z'
  ),
  (
    '81000000-0000-0000-0000-000000000002',
    '82000000-0000-0000-0000-000000000002',
    2,
    'search-v2.0.0',
    '{"user_title":"","fetched_title":"","note":"","ocr":"","shared":"","description":"","body":"","categories":"ｆｏｏ foo","url":"https://example.com/normalization-b"}'::jsonb,
    '{"user_title":[],"fetched_title":[],"note":[],"ocr":[],"shared":[],"description":[],"body":[]}'::jsonb,
    'cues-v1.0.0',
    'available',
    '[]'::jsonb,
    '2026-01-02T00:00:00Z'
  );

insert into public.item_classification (
  owner_id,
  item_id,
  rules_version,
  target_revision,
  state,
  reasons
)
values
  (
    '81000000-0000-0000-0000-000000000001',
    '82000000-0000-0000-0000-000000000001',
    'rules-v2.0.0',
    4,
    'manual',
    '[]'::jsonb
  ),
  (
    '81000000-0000-0000-0000-000000000002',
    '82000000-0000-0000-0000-000000000002',
    'rules-v2.0.0',
    2,
    'manual',
    '[]'::jsonb
  );

insert into public.item_category_controls (owner_id, item_id, manual_override)
values
  (
    '81000000-0000-0000-0000-000000000001',
    '82000000-0000-0000-0000-000000000001',
    true
  ),
  (
    '81000000-0000-0000-0000-000000000002',
    '82000000-0000-0000-0000-000000000002',
    true
  );

insert into private.category_normalization_authorizations (
  backend_pid,
  transaction_id,
  owner_id
)
values
  (
    pg_catalog.pg_backend_pid(),
    pg_catalog.txid_current(),
    '81000000-0000-0000-0000-000000000001'
  ),
  (
    pg_catalog.pg_backend_pid(),
    pg_catalog.txid_current(),
    '81000000-0000-0000-0000-000000000002'
  );

insert into public.categories (
  id,
  owner_id,
  name,
  normalized_name,
  normalization_version,
  kind,
  system_code,
  created_at,
  updated_at
)
values
  (
    '83000000-0000-0000-0000-000000000001',
    '81000000-0000-0000-0000-000000000001',
    'Ｆｏｏ',
    'ｆｏｏ',
    0,
    'custom',
    null,
    '2026-01-01T00:00:00Z',
    '2026-01-01T00:00:00Z'
  ),
  (
    '83000000-0000-0000-0000-000000000002',
    '81000000-0000-0000-0000-000000000001',
    'A  B',
    'a  b',
    0,
    'custom',
    null,
    '2026-01-02T00:00:00Z',
    '2026-01-02T00:00:00Z'
  ),
  (
    '83000000-0000-0000-0000-000000000003',
    '81000000-0000-0000-0000-000000000001',
    'İTest',
    'İtest',
    0,
    'custom',
    null,
    '2026-01-03T00:00:00Z',
    '2026-01-03T00:00:00Z'
  ),
  (
    '83000000-0000-0000-0000-000000000004',
    '81000000-0000-0000-0000-000000000002',
    'Ｆｏｏ',
    'ｆｏｏ',
    0,
    'custom',
    null,
    '2026-01-04T00:00:00Z',
    '2026-01-04T00:00:00Z'
  ),
  (
    '83000000-0000-0000-0000-000000000005',
    '81000000-0000-0000-0000-000000000002',
    'Foo',
    'foo',
    0,
    'custom',
    null,
    '2026-01-05T00:00:00Z',
    '2026-01-05T00:00:00Z'
  );

delete from private.category_normalization_authorizations
where backend_pid = pg_catalog.pg_backend_pid()
  and transaction_id = pg_catalog.txid_current();

insert into public.item_categories (
  owner_id,
  item_id,
  category_id,
  origin,
  created_at
)
values
  (
    '81000000-0000-0000-0000-000000000001',
    '82000000-0000-0000-0000-000000000001',
    '83000000-0000-0000-0000-000000000001',
    'manual',
    '2026-01-01T00:00:00Z'
  ),
  (
    '81000000-0000-0000-0000-000000000001',
    '82000000-0000-0000-0000-000000000001',
    '83000000-0000-0000-0000-000000000002',
    'manual',
    '2026-01-02T00:00:00Z'
  ),
  (
    '81000000-0000-0000-0000-000000000001',
    '82000000-0000-0000-0000-000000000001',
    '83000000-0000-0000-0000-000000000003',
    'manual',
    '2026-01-03T00:00:00Z'
  ),
  (
    '81000000-0000-0000-0000-000000000002',
    '82000000-0000-0000-0000-000000000002',
    '83000000-0000-0000-0000-000000000004',
    'manual',
    '2026-01-04T00:00:00Z'
  ),
  (
    '81000000-0000-0000-0000-000000000002',
    '82000000-0000-0000-0000-000000000002',
    '83000000-0000-0000-0000-000000000005',
    'manual',
    '2026-01-05T00:00:00Z'
  );

create temporary table category_normalization_expected (
  category_id uuid primary key,
  normalized_name text not null
) on commit drop;

insert into category_normalization_expected (category_id, normalized_name)
values
  ('83000000-0000-0000-0000-000000000001', 'foo'),
  ('83000000-0000-0000-0000-000000000002', 'a b'),
  ('83000000-0000-0000-0000-000000000003', 'i̇test'),
  ('83000000-0000-0000-0000-000000000004', 'foo'),
  ('83000000-0000-0000-0000-000000000005', 'foo');

grant select on category_normalization_expected to service_role;

create function pg_temp.category_normalization_snapshot(
  p_owner_id uuid,
  p_exclude_id uuid default null,
  p_stale_id uuid default null,
  p_stale_name text default null,
  p_stale_version integer default null
)
returns jsonb
language sql
stable
set search_path = ''
as $$
  select coalesce(
    pg_catalog.jsonb_agg(
      pg_catalog.jsonb_build_object(
        'id', category.id,
        'owner_id', category.owner_id,
        'name', case
          when category.id = p_stale_id and p_stale_name is not null
            then p_stale_name
          else category.name
        end,
        'normalized_name', expected.normalized_name,
        'normalization_version', case
          when category.id = p_stale_id and p_stale_version is not null
            then p_stale_version
          else category.normalization_version
        end
      )
      order by category.owner_id, category.id
    ),
    '[]'::jsonb
  )
  from public.categories as category
  join pg_temp.category_normalization_expected as expected
    on expected.category_id = category.id
  where category.owner_id = p_owner_id
    and category.id is distinct from p_exclude_id;
$$;

select throws_ok(
  $$
    insert into public.categories (
      id,
      owner_id,
      name,
      normalized_name,
      kind,
      system_code
    )
    values (
      '83000000-0000-0000-0000-000000000006',
      '81000000-0000-0000-0000-000000000001',
      'Foo',
      'foo',
      'custom',
      null
    )
  $$,
  'P0001',
  'CATEGORY_NORMALIZATION_REQUIRED',
  'an unready owner cannot create a duplicate during the migration-to-script gap'
);

select throws_ok(
  $$
    update public.categories
    set name = name
    where id = '83000000-0000-0000-0000-000000000001'
  $$,
  'P0001',
  'CATEGORY_NORMALIZATION_REQUIRED',
  'ordinary category updates cannot trust legacy normalized keys'
);

select throws_ok(
  $$
    update public.item_search
    set updated_at = pg_catalog.now()
    where item_id = '82000000-0000-0000-0000-000000000001'
  $$,
  'P0001',
  'CATEGORY_NORMALIZATION_REQUIRED',
  'category-dependent item indexing is fenced until normalization completes'
);

set local role authenticated;
set local "request.jwt.claim.sub" = '81000000-0000-0000-0000-000000000001';

select throws_ok(
  $$
    select public.library_search_items(
      '{"query":"foo","terms":["foo"],"groups":[{"kind":"literal","value":"foo"}]}'::jsonb,
      '{}'::jsonb,
      20,
      0
    )
  $$,
  'P0001',
  'CATEGORY_NORMALIZATION_REQUIRED',
  'search never falls back to untrusted category keys'
);

reset role;
set local role service_role;
set local "request.jwt.claim.sub" = '';

select throws_ok(
  $$
    select public.library_backfill_category_normalization(
      '81000000-0000-0000-0000-000000000001',
      pg_temp.category_normalization_snapshot(
        '81000000-0000-0000-0000-000000000001',
        '83000000-0000-0000-0000-000000000003'
      )
    )
  $$,
  'P0001',
  'CATEGORY_NORMALIZATION_SNAPSHOT_INVALID',
  'an incomplete snapshot cannot mark any category ready'
);

select throws_ok(
  $$
    select public.library_backfill_category_normalization(
      '81000000-0000-0000-0000-000000000001',
      pg_temp.category_normalization_snapshot(
        '81000000-0000-0000-0000-000000000001',
        null,
        '83000000-0000-0000-0000-000000000001',
        'stale name',
        null
      )
    )
  $$,
  'P0001',
  'CATEGORY_NORMALIZATION_SNAPSHOT_INVALID',
  'a stale category name cannot mark any category ready'
);

select throws_ok(
  $$
    select public.library_backfill_category_normalization(
      '81000000-0000-0000-0000-000000000001',
      pg_temp.category_normalization_snapshot(
        '81000000-0000-0000-0000-000000000001',
        null,
        '83000000-0000-0000-0000-000000000001',
        null,
        1
      )
    )
  $$,
  'P0001',
  'CATEGORY_NORMALIZATION_SNAPSHOT_INVALID',
  'a stale normalization version cannot mark any category ready'
);

select throws_ok(
  $$
    select public.library_backfill_category_normalization(
      '81000000-0000-0000-0000-000000000001',
      pg_temp.category_normalization_snapshot(
        '81000000-0000-0000-0000-000000000002'
      )
    )
  $$,
  'P0001',
  'CATEGORY_NORMALIZATION_SNAPSHOT_INVALID',
  'a snapshot containing another owner cannot normalize the requested owner'
);

select is(
  (
    select count(*)::integer
    from public.categories
    where normalization_version = 0
  ),
  5,
  'rejected snapshots leave every legacy marker unchanged'
);

select throws_ok(
  $$
    select public.library_backfill_category_normalization(
      '81000000-0000-0000-0000-000000000002',
      pg_temp.category_normalization_snapshot(
        '81000000-0000-0000-0000-000000000002'
      )
    )
  $$,
  'P0001',
  'CATEGORY_NORMALIZATION_COLLISION',
  'canonical collisions abort the requested owner before any update'
);

reset role;

select is(
  (
    select pg_catalog.array_agg(category.id order by category.id)
    from public.categories as category
  ),
  array[
    '83000000-0000-0000-0000-000000000001'::uuid,
    '83000000-0000-0000-0000-000000000002'::uuid,
    '83000000-0000-0000-0000-000000000003'::uuid,
    '83000000-0000-0000-0000-000000000004'::uuid,
    '83000000-0000-0000-0000-000000000005'::uuid
  ],
  'collision failure preserves every category id'
);

select is(
  (
    select pg_catalog.string_agg(category.name, '|' order by category.id)
    from public.categories as category
  ),
  'Ｆｏｏ|A  B|İTest|Ｆｏｏ|Foo',
  'collision failure preserves every category display name'
);

select is(
  (
    select pg_catalog.string_agg(
      category.normalized_name,
      '|'
      order by category.id
    )
    from public.categories as category
  ),
  'ｆｏｏ|a  b|İtest|ｆｏｏ|foo',
  'collision failure preserves every stored legacy key'
);

select is(
  (
    select pg_catalog.array_agg(
      selected.item_id::text || ':' || selected.category_id::text
      order by selected.item_id, selected.category_id
    )
    from public.item_categories as selected
  ),
  array[
    '82000000-0000-0000-0000-000000000001:83000000-0000-0000-0000-000000000001',
    '82000000-0000-0000-0000-000000000001:83000000-0000-0000-0000-000000000002',
    '82000000-0000-0000-0000-000000000001:83000000-0000-0000-0000-000000000003',
    '82000000-0000-0000-0000-000000000002:83000000-0000-0000-0000-000000000004',
    '82000000-0000-0000-0000-000000000002:83000000-0000-0000-0000-000000000005'
  ]::text[],
  'collision failure preserves all item-category links'
);

set local role service_role;

select is(
  public.library_backfill_category_normalization(
    '81000000-0000-0000-0000-000000000001',
    pg_temp.category_normalization_snapshot(
      '81000000-0000-0000-0000-000000000001'
    )
  ),
  '{"indexed_item_count":1,"normalized_count":3}'::jsonb,
  'one collision-free owner normalizes despite another owner having a collision'
);

reset role;

delete from auth.users
where id = '81000000-0000-0000-0000-000000000002';

select is(
  (
    select pg_catalog.string_agg(
      category.normalized_name,
      '|'
      order by category.id
    )
    from public.categories as category
  ),
  'foo|a b|i̇test',
  'NFKC, repeated whitespace, and non-ASCII lowercase results come from the TypeScript snapshot'
);

select is(
  (
    select pg_catalog.string_agg(category.name, '|' order by category.id)
    from public.categories as category
  ),
  'Ｆｏｏ|A  B|İTest',
  'successful normalization preserves display names'
);

select is(
  (
    select search_record.normalized_fields ->> 'categories'
    from public.item_search as search_record
    where search_record.item_id = '82000000-0000-0000-0000-000000000001'
  ),
  'foo a b i̇test',
  'the category search field is rebuilt in the same commit'
);

select is(
  (
    select pg_catalog.jsonb_build_object(
      'version', item.version,
      'text_revision', item.text_revision
    )
    from public.items as item
    where item.id = '82000000-0000-0000-0000-000000000001'
  ),
  '{"version":8,"text_revision":4}'::jsonb,
  'a changed derived category index increments version without changing text revision'
);

select is(
  (
    select count(*)::integer
    from public.processing_jobs as job
    where job.owner_id = '81000000-0000-0000-0000-000000000001'
      and job.kind = 'classify'
  ),
  0,
  'normalization does not enqueue classification work'
);

set local role service_role;

select is(
  public.library_backfill_category_normalization(
    '81000000-0000-0000-0000-000000000001',
    pg_temp.category_normalization_snapshot(
      '81000000-0000-0000-0000-000000000001'
    )
  ),
  '{"indexed_item_count":0,"normalized_count":0}'::jsonb,
  'the service backfill is idempotent after all rows are ready'
);

reset role;
set local role authenticated;
set local "request.jwt.claim.sub" = '81000000-0000-0000-0000-000000000001';

select is(
  public.library_search_items(
    '{"query":"foo","terms":["foo"],"groups":[{"kind":"literal","value":"foo"}]}'::jsonb,
    '{}'::jsonb,
    20,
    0
  ) #>> '{items,0,id}',
  '82000000-0000-0000-0000-000000000001',
  'search resumes against the rebuilt canonical category index'
);

reset role;

select throws_ok(
  $$
    insert into public.categories (
      id,
      owner_id,
      name,
      normalized_name,
      kind,
      system_code
    )
    values (
      '83000000-0000-0000-0000-000000000006',
      '81000000-0000-0000-0000-000000000001',
      'Foo',
      'foo',
      'custom',
      null
    )
  $$,
  '23505',
  'duplicate key value violates unique constraint "categories_owner_normalized_name_key"',
  'future canonical category writes cannot create a normalized duplicate'
);

insert into public.categories (
  id,
  owner_id,
  name,
  normalized_name,
  kind,
  system_code
)
values (
  '83000000-0000-0000-0000-000000000007',
  '81000000-0000-0000-0000-000000000001',
  'Future',
  'future',
  'custom',
  null
);

select is(
  (
    select category.normalization_version
    from public.categories as category
    where category.id = '83000000-0000-0000-0000-000000000007'
  ),
  1::smallint,
  'new canonical category writes default to the current normalization marker'
);

select * from finish();
rollback;
