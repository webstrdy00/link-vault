begin;

create extension if not exists pgtap with schema extensions;
set local search_path = pg_catalog, extensions, public;
select no_plan();

with owners as (
  select
    ordinal,
    (
      'a1000000-0000-0000-0000-' ||
      pg_catalog.lpad(ordinal::text, 12, '0')
    )::uuid as owner_id
  from pg_catalog.generate_series(1, 7) as owner(ordinal)
)
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
select
  '00000000-0000-0000-0000-000000000000'::uuid,
  owner.owner_id,
  'authenticated',
  'authenticated',
  'retained-normalization-' || owner.ordinal || '@example.test',
  '',
  pg_catalog.now(),
  '{"provider":"google","providers":["google"]}'::jsonb,
  '{}'::jsonb,
  pg_catalog.now(),
  pg_catalog.now(),
  '',
  '',
  '',
  ''
from owners as owner;

with owners as (
  select
    ordinal,
    (
      'a1000000-0000-0000-0000-' ||
      pg_catalog.lpad(ordinal::text, 12, '0')
    )::uuid as owner_id
  from pg_catalog.generate_series(1, 7) as owner(ordinal)
)
insert into public.beta_members (owner_id, enabled, approved_at)
select
  owner.owner_id,
  owner.ordinal <> 6,
  pg_catalog.now()
from owners as owner;

insert into public.profiles (id, state)
select
  (
    'a1000000-0000-0000-0000-' ||
    pg_catalog.lpad(owner.ordinal::text, 12, '0')
  )::uuid,
  'active'
from pg_catalog.generate_series(1, 7) as owner(ordinal);

insert into public.library_usage (owner_id)
select (
  'a1000000-0000-0000-0000-' ||
  pg_catalog.lpad(owner.ordinal::text, 12, '0')
)::uuid
from pg_catalog.generate_series(1, 7) as owner(ordinal);

create temporary table retained_owner_categories (
  owner_ordinal integer not null,
  category_ordinal integer not null,
  owner_id uuid not null,
  category_id uuid primary key,
  raw_name text not null,
  legacy_normalized_name text not null,
  expected_normalized_name text not null,
  unique (owner_ordinal, category_ordinal)
) on commit drop;

insert into retained_owner_categories (
  owner_ordinal,
  category_ordinal,
  owner_id,
  category_id,
  raw_name,
  legacy_normalized_name,
  expected_normalized_name
)
select
  owner.ordinal,
  category.ordinal,
  (
    'a1000000-0000-0000-0000-' ||
    pg_catalog.lpad(owner.ordinal::text, 12, '0')
  )::uuid,
  (
    'a3000000-0000-0000-0000-' ||
    pg_catalog.lpad(
      (
        case
          when owner.ordinal <= 6
            then ((owner.ordinal - 1) * 38) + category.ordinal
          else 228 + category.ordinal
        end
      )::text,
      12,
      '0'
    )
  )::uuid,
  'Retained ' || owner.ordinal || ' Category ' || category.ordinal,
  'legacy-' || owner.ordinal || '-' || category.ordinal,
  'retained ' || owner.ordinal || ' category ' || category.ordinal
from pg_catalog.generate_series(1, 7) as owner(ordinal)
cross join lateral pg_catalog.generate_series(
  1,
  case when owner.ordinal <= 6 then 38 else 8 end
) as category(ordinal);

grant select on retained_owner_categories to service_role;

insert into private.category_normalization_authorizations (
  backend_pid,
  transaction_id,
  owner_id
)
select distinct
  pg_catalog.pg_backend_pid(),
  pg_catalog.txid_current(),
  category.owner_id
from retained_owner_categories as category;

insert into public.categories (
  id,
  owner_id,
  name,
  normalized_name,
  normalization_version,
  kind,
  system_code
)
select
  category.category_id,
  category.owner_id,
  category.raw_name,
  category.legacy_normalized_name,
  0,
  'custom',
  null
from retained_owner_categories as category;

delete from private.category_normalization_authorizations
where backend_pid = pg_catalog.pg_backend_pid()
  and transaction_id = pg_catalog.txid_current();

create function pg_temp.retained_owner_snapshot(
  p_source_owner_id uuid,
  p_snapshot_owner_id uuid default null
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
        'owner_id', coalesce(p_snapshot_owner_id, category.owner_id),
        'name', category.name,
        'normalized_name', fixture.expected_normalized_name,
        'normalization_version', category.normalization_version
      )
      order by category.id
    ),
    '[]'::jsonb
  )
  from public.categories as category
  join pg_temp.retained_owner_categories as fixture
    on fixture.category_id = category.id
  where category.owner_id = p_source_owner_id;
$$;

select is(
  (
    select pg_catalog.jsonb_build_object(
      'retained_owners', count(distinct category.owner_id),
      'enabled_owners', count(distinct category.owner_id)
        filter (where member.enabled),
      'retained_categories', count(*),
      'disabled_owner_categories', count(*)
        filter (where not member.enabled)
    )
    from retained_owner_categories as category
    join public.beta_members as member
      on member.owner_id = category.owner_id
  ),
  '{"disabled_owner_categories":38,"enabled_owners":6,"retained_categories":236,"retained_owners":7}'::jsonb,
  'the retained fixture reproduces 236 categories across seven owners with six enabled'
);

set local role service_role;

select throws_ok(
  $$
    select public.library_backfill_category_normalization(
      'a1000000-0000-0000-0000-000000000002',
      pg_temp.retained_owner_snapshot(
        'a1000000-0000-0000-0000-000000000001',
        'a1000000-0000-0000-0000-000000000002'
      )
    )
  $$,
  'P0001',
  'CATEGORY_NORMALIZATION_SNAPSHOT_INVALID',
  'foreign category ids are rejected even when their owner fields are forged'
);

reset role;

select is(
  (
    select count(*)::integer
    from public.categories as category
    join retained_owner_categories as fixture
      on fixture.category_id = category.id
    where category.normalization_version = 0
      and category.normalized_name = fixture.legacy_normalized_name
  ),
  236,
  'a rejected foreign snapshot atomically preserves every retained category'
);

set local role service_role;

select is(
  public.library_backfill_category_normalization(
    'a1000000-0000-0000-0000-000000000001',
    pg_temp.retained_owner_snapshot(
      'a1000000-0000-0000-0000-000000000001'
    )
  ),
  '{"indexed_item_count":0,"normalized_count":38}'::jsonb,
  'the first complete owner snapshot makes bounded progress'
);

reset role;

select is(
  (
    select pg_catalog.jsonb_build_object(
      'current_categories', count(*)
        filter (where category.normalization_version = 1),
      'legacy_categories', count(*)
        filter (where category.normalization_version = 0),
      'unstarted_replacement_categories', count(*) filter (
        where fixture.owner_ordinal = 7
          and category.normalization_version = 0
      )
    )
    from public.categories as category
    join retained_owner_categories as fixture
      on fixture.category_id = category.id
  ),
  '{"current_categories":38,"legacy_categories":198,"unstarted_replacement_categories":8}'::jsonb,
  'owner completion is durable without prematurely marking later owners current'
);

create temporary table retained_owner_results (
  owner_ordinal integer primary key,
  result jsonb not null
) on commit drop;

grant select, insert on retained_owner_results to service_role;

set local role service_role;

insert into pg_temp.retained_owner_results (owner_ordinal, result)
select
  owner.ordinal,
  public.library_backfill_category_normalization(
    owner.owner_id,
    pg_temp.retained_owner_snapshot(owner.owner_id)
  )
from (
  select distinct owner_ordinal as ordinal, owner_id
  from pg_temp.retained_owner_categories
  where owner_ordinal between 2 and 6
) as owner
order by owner.ordinal;

reset role;

select is(
  (
    select pg_catalog.jsonb_build_object(
      'owners_completed', count(*),
      'categories_normalized', sum(
        (result.result ->> 'normalized_count')::integer
      ),
      'item_indexes_updated', sum(
        (result.result ->> 'indexed_item_count')::integer
      )
    )
    from retained_owner_results as result
  ),
  '{"categories_normalized":190,"item_indexes_updated":0,"owners_completed":5}'::jsonb,
  'five more bounded owner snapshots advance the retained total to 228 categories'
);

select is(
  (
    select pg_catalog.jsonb_build_object(
      'enabled', member.enabled,
      'current_categories', count(*) filter (
        where category.normalization_version = 1
      )
    )
    from public.beta_members as member
    join public.categories as category
      on category.owner_id = member.owner_id
    where member.owner_id = 'a1000000-0000-0000-0000-000000000006'
    group by member.enabled
  ),
  '{"current_categories":38,"enabled":false}'::jsonb,
  'the disabled but retained owner is normalized with the enabled owners'
);

select is(
  (
    select count(*)::integer
    from public.categories as category
    join retained_owner_categories as fixture
      on fixture.category_id = category.id
    where fixture.owner_ordinal = 7
      and category.normalization_version = 0
  ),
  8,
  'the replacement owner remains independently eligible after 228 rows complete'
);

set local role service_role;

select is(
  public.library_backfill_category_normalization(
    'a1000000-0000-0000-0000-000000000007',
    pg_temp.retained_owner_snapshot(
      'a1000000-0000-0000-0000-000000000007'
    )
  ),
  '{"indexed_item_count":0,"normalized_count":8}'::jsonb,
  'the replacement owner advances the legitimate retained total beyond 228'
);

select is(
  public.library_backfill_category_normalization(
    'a1000000-0000-0000-0000-000000000006',
    pg_temp.retained_owner_snapshot(
      'a1000000-0000-0000-0000-000000000006'
    )
  ),
  '{"indexed_item_count":0,"normalized_count":0}'::jsonb,
  'rerunning a completed disabled owner is idempotent'
);

reset role;

select is(
  (
    select pg_catalog.jsonb_build_object(
      'current_categories', count(*) filter (
        where category.normalization_version = 1
      ),
      'expected_keys', count(*) filter (
        where category.normalized_name = fixture.expected_normalized_name
      ),
      'display_names_preserved', count(*) filter (
        where category.name = fixture.raw_name
      )
    )
    from public.categories as category
    join retained_owner_categories as fixture
      on fixture.category_id = category.id
  ),
  '{"current_categories":236,"display_names_preserved":236,"expected_keys":236}'::jsonb,
  'all 236 retained rows finish with current keys and unchanged raw names'
);

select * from finish();
rollback;
