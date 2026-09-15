begin;

create extension if not exists pgtap with schema extensions;
set local search_path = pg_catalog, extensions, public;
select no_plan();

select ok(
  (
    with bounds(field_name, maximum_length) as (
      values
        ('user_title'::text, 5400),
        ('fetched_title', 5400),
        ('note', 72000),
        ('ocr', 360000),
        ('shared', 72000),
        ('description', 72000),
        ('body', 360000),
        ('categories', 2704),
        ('url', 73728)
    ),
    base(value) as (
      values (
        '{"user_title":"","fetched_title":"","note":"","ocr":"","shared":"","description":"","body":"","categories":"","url":""}'::jsonb
      )
    )
    select pg_catalog.bool_and(
      private.valid_normalized_fields(
        pg_catalog.jsonb_set(
          base.value,
          array[bounds.field_name],
          pg_catalog.to_jsonb(pg_catalog.repeat('x', bounds.maximum_length)),
          false
        )
      )
      and not private.valid_normalized_fields(
        pg_catalog.jsonb_set(
          base.value,
          array[bounds.field_name],
          pg_catalog.to_jsonb(pg_catalog.repeat('x', bounds.maximum_length + 1)),
          false
        )
      )
    )
    from bounds
    cross join base
  ),
  'every derived search field accepts its 18x ceiling and rejects one more code point'
);

select ok(
  not has_function_privilege(
    'authenticated',
    'private.valid_normalized_fields(jsonb)',
    'EXECUTE'
  )
  and has_function_privilege(
    'service_role',
    'private.valid_normalized_fields(jsonb)',
    'EXECUTE'
  )
  and not has_function_privilege(
    'authenticated',
    'public.library_backfill_category_normalization(uuid,jsonb)',
    'EXECUTE'
  )
  and has_function_privilege(
    'service_role',
    'public.library_backfill_category_normalization(uuid,jsonb)',
    'EXECUTE'
  ),
  'the replacement functions preserve their private and service-only grants'
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
values (
  '00000000-0000-0000-0000-000000000000',
  '91000000-0000-0000-0000-000000000001',
  'authenticated',
  'authenticated',
  'normalized-bounds@example.test',
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
values ('91000000-0000-0000-0000-000000000001', true, now());

insert into public.profiles (id, state)
values ('91000000-0000-0000-0000-000000000001', 'active');

insert into public.library_usage (owner_id)
values ('91000000-0000-0000-0000-000000000001');

create temporary table normalized_bounds_categories (
  ordinal integer primary key,
  category_id uuid not null,
  raw_name text not null,
  normalized_name text not null,
  response jsonb not null
) on commit drop;

grant select, insert, update on normalized_bounds_categories to service_role;

create temporary table normalized_bounds_results (
  label text primary key,
  response jsonb not null
) on commit drop;

grant select, insert on normalized_bounds_results to service_role;

set local role service_role;

with result as (
  select public.library_create_category(
    '91000000-0000-0000-0000-000000000001',
    '92000000-0000-0000-0000-000000000001',
    pg_catalog.jsonb_build_object('name', pg_catalog.repeat('ﷺ', 30)),
    pg_catalog.repeat('صلى الله عليه وسلم', 30)
  ) as response
)
insert into normalized_bounds_categories (
  ordinal,
  category_id,
  raw_name,
  normalized_name,
  response
)
select
  1,
  (result.response #>> '{category,id}')::uuid,
  pg_catalog.repeat('ﷺ', 30),
  pg_catalog.repeat('صلى الله عليه وسلم', 30),
  result.response
from result;

with result as (
  select public.library_create_category(
    '91000000-0000-0000-0000-000000000001',
    '92000000-0000-0000-0000-000000000002',
    '{"name":"rename seed"}'::jsonb,
    'rename seed'
  ) as response
)
insert into normalized_bounds_categories (
  ordinal,
  category_id,
  raw_name,
  normalized_name,
  response
)
select
  2,
  (result.response #>> '{category,id}')::uuid,
  'rename seed',
  'rename seed',
  result.response
from result;

with result as (
  select public.library_rename_category(
    '91000000-0000-0000-0000-000000000001',
    (
      select category_id
      from normalized_bounds_categories
      where ordinal = 2
    ),
    '92000000-0000-0000-0000-000000000003',
    pg_catalog.jsonb_build_object(
      'name',
      pg_catalog.repeat('ﷺ', 29) || 'a'
    ),
    pg_catalog.repeat('صلى الله عليه وسلم', 29) || 'a'
  ) as response
)
insert into normalized_bounds_results (label, response)
select 'rename', result.response
from result;

update normalized_bounds_categories
set raw_name = pg_catalog.repeat('ﷺ', 29) || 'a',
    normalized_name = pg_catalog.repeat('صلى الله عليه وسلم', 29) || 'a'
where ordinal = 2;

with requested(ordinal, suffix, request_id) as (
  values
    (3, 'b'::text, '92000000-0000-0000-0000-000000000004'::uuid),
    (4, 'c', '92000000-0000-0000-0000-000000000005'::uuid),
    (5, 'd', '92000000-0000-0000-0000-000000000006'::uuid)
),
created as (
  select
    requested.ordinal,
    pg_catalog.repeat('ﷺ', 29) || requested.suffix as raw_name,
    pg_catalog.repeat('صلى الله عليه وسلم', 29) || requested.suffix as normalized_name,
    public.library_create_category(
      '91000000-0000-0000-0000-000000000001',
      requested.request_id,
      pg_catalog.jsonb_build_object(
        'name',
        pg_catalog.repeat('ﷺ', 29) || requested.suffix
      ),
      pg_catalog.repeat('صلى الله عليه وسلم', 29) || requested.suffix
    ) as response
  from requested
  order by requested.ordinal
)
insert into normalized_bounds_categories (
  ordinal,
  category_id,
  raw_name,
  normalized_name,
  response
)
select
  created.ordinal,
  (created.response #>> '{category,id}')::uuid,
  created.raw_name,
  created.normalized_name,
  created.response
from created;

reset role;

select is(
  (
    select pg_catalog.jsonb_build_object(
      'status', response -> 'http_status',
      'raw_length', pg_catalog.char_length(raw_name),
      'normalized_length', pg_catalog.char_length(normalized_name)
    )
    from normalized_bounds_categories
    where ordinal = 1
  ),
  '{"status":201,"raw_length":30,"normalized_length":540}'::jsonb,
  'category creation accepts a valid 30-code-point name whose NFKC form expands to 540'
);

select is(
  (
    select pg_catalog.jsonb_build_object(
      'status', result.response -> 'http_status',
      'raw_length', pg_catalog.char_length(category.raw_name),
      'normalized_length', pg_catalog.char_length(category.normalized_name)
    )
    from normalized_bounds_results as result
    cross join normalized_bounds_categories as category
    where result.label = 'rename'
      and category.ordinal = 2
  ),
  '{"status":200,"raw_length":30,"normalized_length":523}'::jsonb,
  'category rename accepts a valid 30-code-point name after normalization expands past 30'
);

set local role service_role;

select throws_ok(
  $$
    select public.library_create_category(
      '91000000-0000-0000-0000-000000000001',
      '92000000-0000-0000-0000-000000000007',
      pg_catalog.jsonb_build_object('name', pg_catalog.repeat('x', 31)),
      pg_catalog.repeat('x', 31)
    )
  $$,
  'P0001',
  'INVALID_BODY',
  'category creation still rejects 31 raw code points even when the normalized value is short'
);

with result as (
  select public.library_create_item(
    '91000000-0000-0000-0000-000000000001',
    '92000000-0000-0000-0000-000000000008',
    pg_catalog.jsonb_build_object(
      'url', 'https://example.com/normalized-bounds',
      'title', pg_catalog.repeat('ﷺ', 300),
      'note', pg_catalog.repeat('İ', 4000),
      'category_ids', (
        select pg_catalog.jsonb_agg(category_id order by ordinal)
        from normalized_bounds_categories
      )
    ),
    pg_catalog.jsonb_build_object(
      'normalized_url', 'https://example.com/normalized-bounds',
      'url_hash', pg_catalog.encode(
        extensions.digest(
          pg_catalog.convert_to(
            'https://example.com/normalized-bounds',
            'UTF8'
          ),
          'sha256'
        ),
        'hex'
      ),
      'source', 'other',
      'display_fallback', 'example.com',
      'normalized_fields', pg_catalog.jsonb_build_object(
        'user_title', pg_catalog.repeat('صلى الله عليه وسلم', 300),
        'fetched_title', '',
        'note', pg_catalog.repeat('i̇', 4000),
        'ocr', '',
        'shared', '',
        'description', '',
        'body', '',
        'categories', '',
        'url', 'https://example.com/normalized-bounds'
      ),
      'alias_concepts', '{"user_title":[],"fetched_title":[],"note":[],"ocr":[],"shared":[],"description":[],"body":[]}'::jsonb,
      'cue_state', 'available',
      'cue_flags', '[]'::jsonb,
      'metadata_allowed', false
    )
  ) as response
)
insert into normalized_bounds_results (label, response)
select 'create-item', result.response
from result;

select throws_ok(
  $$
    select public.library_create_item(
      '91000000-0000-0000-0000-000000000001',
      '92000000-0000-0000-0000-000000000009',
      pg_catalog.jsonb_build_object(
        'url', 'https://example.com/title-too-long',
        'title', pg_catalog.repeat('x', 301)
      ),
      '{}'::jsonb
    )
  $$,
  'P0001',
  'INVALID_BODY',
  'item creation still rejects a 301-code-point raw title'
);

select throws_ok(
  $$
    select public.library_create_item(
      '91000000-0000-0000-0000-000000000001',
      '92000000-0000-0000-0000-000000000010',
      pg_catalog.jsonb_build_object(
        'url', 'https://example.com/note-too-long',
        'note', pg_catalog.repeat('x', 4001)
      ),
      '{}'::jsonb
    )
  $$,
  'P0001',
  'INVALID_BODY',
  'item creation still rejects a 4001-code-point raw note'
);

reset role;

select is(
  (
    select pg_catalog.jsonb_build_object(
      'status', result.response -> 'http_status',
      'raw_title', pg_catalog.char_length(item.user_title),
      'normalized_title', pg_catalog.char_length(
        search_record.normalized_fields ->> 'user_title'
      ),
      'raw_note', pg_catalog.char_length(item.note),
      'normalized_note', pg_catalog.char_length(
        search_record.normalized_fields ->> 'note'
      )
    )
    from normalized_bounds_results as result
    join public.items as item
      on item.id = (result.response #>> '{item,id}')::uuid
    join public.item_search as search_record
      on search_record.owner_id = item.owner_id
      and search_record.item_id = item.id
    where result.label = 'create-item'
  ),
  '{"status":201,"raw_title":300,"normalized_title":5400,"raw_note":4000,"normalized_note":8000}'::jsonb,
  'valid raw maximum title and note values retain their expanded production-normalized fields'
);

select is(
  (
    select search_record.normalized_fields ->> 'categories'
    from normalized_bounds_results as result
    join public.item_search as search_record
      on search_record.item_id = (result.response #>> '{item,id}')::uuid
    where result.label = 'create-item'
  ),
  (
    select pg_catalog.string_agg(
      category.normalized_name,
      ' '
      order by category.ordinal
    )
    from normalized_bounds_categories as category
  ),
  'the five-category derived index stores every full normalized name without truncation'
);

select ok(
  (
    select pg_catalog.char_length(search_record.normalized_fields ->> 'categories') > 154
      and pg_catalog.char_length(search_record.normalized_fields ->> 'categories') <= 2704
    from normalized_bounds_results as result
    join public.item_search as search_record
      on search_record.item_id = (result.response #>> '{item,id}')::uuid
    where result.label = 'create-item'
  ),
  'the five-category aggregate crosses the obsolete ceiling and remains within the derived ceiling'
);

-- Free the exact 540-character canonical key before the independent legacy
-- backfill fixture uses it; otherwise that fixture is a real name collision.
select is(
  public.library_rename_category(
    '91000000-0000-0000-0000-000000000001',
    (select category_id from normalized_bounds_categories where ordinal = 1),
    '92000000-0000-0000-0000-000000000020',
    pg_catalog.jsonb_build_object('name', pg_catalog.repeat('ﷺ', 29) || 'z'),
    pg_catalog.repeat('صلى الله عليه وسلم', 29) || 'z'
  ) ->> 'http_status',
  '200',
  'the legacy normalization fixture has a distinct canonical name'
);
update normalized_bounds_categories
set raw_name = pg_catalog.repeat('ﷺ', 29) || 'z',
    normalized_name = pg_catalog.repeat('صلى الله عليه وسلم', 29) || 'z'
where ordinal = 1;

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
  metadata_state
)
values (
  '93000000-0000-0000-0000-000000000001',
  '91000000-0000-0000-0000-000000000001',
  'https://example.com/backfill-expanded',
  'https://example.com/backfill-expanded',
  pg_catalog.repeat('e', 64),
  'other',
  'Backfill expanded',
  1,
  1,
  'ready'
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
  '91000000-0000-0000-0000-000000000001',
  '93000000-0000-0000-0000-000000000001',
  1,
  'search-v2.0.0',
  '{"user_title":"","fetched_title":"","note":"","ocr":"","shared":"","description":"","body":"","categories":"legacy-key","url":"https://example.com/backfill-expanded"}'::jsonb,
  '{"user_title":[],"fetched_title":[],"note":[],"ocr":[],"shared":[],"description":[],"body":[]}'::jsonb,
  'cues-v1.0.0',
  'missing',
  '[]'::jsonb
);

insert into private.category_normalization_authorizations (
  backend_pid,
  transaction_id,
  owner_id
)
values (
  pg_catalog.pg_backend_pid(),
  pg_catalog.txid_current(),
  '91000000-0000-0000-0000-000000000001'
);

insert into public.categories (
  id,
  owner_id,
  name,
  normalized_name,
  normalization_version,
  kind,
  system_code
)
values (
  '94000000-0000-0000-0000-000000000001',
  '91000000-0000-0000-0000-000000000001',
  pg_catalog.repeat('ﷺ', 30),
  'legacy-key',
  0,
  'custom',
  null
);

delete from private.category_normalization_authorizations
where backend_pid = pg_catalog.pg_backend_pid()
  and transaction_id = pg_catalog.txid_current();

insert into public.item_categories (
  owner_id,
  item_id,
  category_id,
  origin
)
values (
  '91000000-0000-0000-0000-000000000001',
  '93000000-0000-0000-0000-000000000001',
  '94000000-0000-0000-0000-000000000001',
  'manual'
);

create temporary table normalized_bounds_expected (
  category_id uuid primary key,
  normalized_name text not null
) on commit drop;

insert into normalized_bounds_expected (category_id, normalized_name)
select category_id, normalized_name
from normalized_bounds_categories
union all
select
  '94000000-0000-0000-0000-000000000001'::uuid,
  pg_catalog.repeat('صلى الله عليه وسلم', 30);

grant select on normalized_bounds_expected to service_role;

create function pg_temp.normalized_bounds_snapshot(
  p_exclude_id uuid default null,
  p_stale_id uuid default null,
  p_stale_name text default null,
  p_collision_id uuid default null,
  p_collision_name text default null
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
          when category.id = p_stale_id then p_stale_name
          else category.name
        end,
        'normalized_name', case
          when category.id = p_collision_id then p_collision_name
          else expected.normalized_name
        end,
        'normalization_version', category.normalization_version
      )
      order by category.owner_id, category.id
    ),
    '[]'::jsonb
  )
  from public.categories as category
  join pg_temp.normalized_bounds_expected as expected
    on expected.category_id = category.id
  where category.id is distinct from p_exclude_id;
$$;

set local role service_role;

select throws_ok(
  $$
    select public.library_backfill_category_normalization(
      '91000000-0000-0000-0000-000000000001',
      pg_temp.normalized_bounds_snapshot(
        '94000000-0000-0000-0000-000000000001'
      )
    )
  $$,
  'P0001',
  'CATEGORY_NORMALIZATION_SNAPSHOT_INVALID',
  'the expanded backfill remains incomplete-snapshot safe'
);

select throws_ok(
  $$
    select public.library_backfill_category_normalization(
      '91000000-0000-0000-0000-000000000001',
      pg_temp.normalized_bounds_snapshot(
        null,
        '94000000-0000-0000-0000-000000000001',
        'stale raw name'
      )
    )
  $$,
  'P0001',
  'CATEGORY_NORMALIZATION_SNAPSHOT_INVALID',
  'the expanded backfill remains stale-snapshot safe'
);

select throws_ok(
  $$
    select public.library_backfill_category_normalization(
      '91000000-0000-0000-0000-000000000001',
      pg_temp.normalized_bounds_snapshot(
        null,
        null,
        null,
        '94000000-0000-0000-0000-000000000001',
        (
          select normalized_name
          from normalized_bounds_categories
          where ordinal = 1
        )
      )
    )
  $$,
  'P0001',
  'CATEGORY_NORMALIZATION_COLLISION',
  'the expanded backfill still rejects normalized-name collisions'
);

reset role;

select is(
  (
    select pg_catalog.jsonb_build_object(
      'normalized_name', category.normalized_name,
      'normalization_version', category.normalization_version,
      'indexed_categories', search_record.normalized_fields ->> 'categories'
    )
    from public.categories as category
    join public.item_search as search_record
      on search_record.item_id = '93000000-0000-0000-0000-000000000001'
    where category.id = '94000000-0000-0000-0000-000000000001'
  ),
  '{"normalized_name":"legacy-key","normalization_version":0,"indexed_categories":"legacy-key"}'::jsonb,
  'rejected expanded snapshots leave both the category and derived index unchanged'
);

set local role service_role;

select is(
  public.library_backfill_category_normalization(
    '91000000-0000-0000-0000-000000000001',
    pg_temp.normalized_bounds_snapshot()
  ),
  '{"indexed_item_count":1,"normalized_count":1}'::jsonb,
  'the complete snapshot atomically accepts and indexes a 540-code-point normalized name'
);

reset role;

select is(
  (
    select pg_catalog.jsonb_build_object(
      'raw_length', pg_catalog.char_length(category.name),
      'normalized_length', pg_catalog.char_length(category.normalized_name),
      'normalization_version', category.normalization_version,
      'indexed_categories', search_record.normalized_fields ->> 'categories',
      'item_version', item.version,
      'text_revision', item.text_revision
    )
    from public.categories as category
    join public.item_search as search_record
      on search_record.item_id = '93000000-0000-0000-0000-000000000001'
    join public.items as item
      on item.owner_id = search_record.owner_id
      and item.id = search_record.item_id
    where category.id = '94000000-0000-0000-0000-000000000001'
  ),
  pg_catalog.jsonb_build_object(
    'raw_length', 30,
    'normalized_length', 540,
    'normalization_version', 1,
    'indexed_categories', pg_catalog.repeat('صلى الله عليه وسلم', 30),
    'item_version', 2,
    'text_revision', 1
  ),
  'backfill preserves the raw name and writes the complete expanded key without changing text revision'
);

set local role service_role;

select is(
  public.library_backfill_category_normalization(
    '91000000-0000-0000-0000-000000000001',
    pg_temp.normalized_bounds_snapshot()
  ),
  '{"indexed_item_count":0,"normalized_count":0}'::jsonb,
  'expanded normalization backfill remains idempotent'
);

reset role;

select * from finish();
rollback;
