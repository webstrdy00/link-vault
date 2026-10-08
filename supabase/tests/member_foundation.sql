begin;

create extension if not exists pgtap with schema extensions;
set local search_path = pg_catalog, extensions, public;

select plan(51);

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
  ('10000000-0000-0000-0000-' || lpad(fixture_number::text, 12, '0'))::uuid,
  'authenticated',
  'authenticated',
  'member-' || fixture_number || '@example.test',
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
from generate_series(1, 9) as fixture(fixture_number);

insert into public.beta_members (owner_id, enabled, approved_at)
values
  ('10000000-0000-0000-0000-000000000001', true, now()),
  ('10000000-0000-0000-0000-000000000002', true, now()),
  ('10000000-0000-0000-0000-000000000003', false, null),
  ('10000000-0000-0000-0000-000000000004', false, now()),
  ('10000000-0000-0000-0000-000000000005', true, now()),
  ('10000000-0000-0000-0000-000000000009', false, null);

insert into public.profiles (id, state, deletion_requested_at)
values
  ('10000000-0000-0000-0000-000000000004', 'active', null),
  ('10000000-0000-0000-0000-000000000005', 'deleting', now());

insert into public.library_usage (
  owner_id,
  active_item_count,
  used_image_bytes,
  reserved_image_bytes
)
values (
  '10000000-0000-0000-0000-000000000004',
  7,
  123,
  456
);

select ok(
  (
    select count(*) = 4 and bool_and(class.relrowsecurity)
    from pg_class as class
    join pg_namespace as namespace on namespace.oid = class.relnamespace
    where namespace.nspname = 'public'
      and class.relname in ('beta_members', 'profiles', 'library_usage', 'categories')
  ),
  'all member foundation tables enable RLS'
);

select is(
  (
    select count(*)::integer
    from pg_trigger as installed_trigger
    where installed_trigger.tgrelid = 'public.beta_members'::regclass
      and installed_trigger.tgname = 'beta_members_enforce_enabled_cap'
      and not installed_trigger.tgisinternal
  ),
  1,
  'the beta cap is enforced by a table trigger'
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
    where installed_function.oid = 'public.member_bootstrap(uuid)'::regprocedure
  ),
  'member_bootstrap is SECURITY DEFINER with a fixed search_path'
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
    where installed_function.oid = 'public.member_me()'::regprocedure
  ),
  'member_me is SECURITY DEFINER with a fixed search_path'
);

select ok(
  has_function_privilege('authenticated', 'public.member_bootstrap(uuid)', 'EXECUTE')
  and has_function_privilege('authenticated', 'public.member_me()', 'EXECUTE')
  and not has_function_privilege('anon', 'public.member_bootstrap(uuid)', 'EXECUTE')
  and not has_function_privilege('anon', 'public.member_me()', 'EXECUTE'),
  'only authenticated callers can execute the public member RPCs'
);

select ok(
  not has_table_privilege('authenticated', 'public.beta_members', 'INSERT')
  and not has_table_privilege('authenticated', 'public.beta_members', 'UPDATE')
  and not has_table_privilege('authenticated', 'public.beta_members', 'DELETE')
  and not has_table_privilege('authenticated', 'public.profiles', 'INSERT')
  and not has_table_privilege('authenticated', 'public.profiles', 'UPDATE')
  and not has_table_privilege('authenticated', 'public.profiles', 'DELETE')
  and not has_table_privilege('authenticated', 'public.library_usage', 'INSERT')
  and not has_table_privilege('authenticated', 'public.library_usage', 'UPDATE')
  and not has_table_privilege('authenticated', 'public.library_usage', 'DELETE')
  and not has_table_privilege('authenticated', 'public.categories', 'INSERT')
  and not has_table_privilege('authenticated', 'public.categories', 'UPDATE')
  and not has_table_privilege('authenticated', 'public.categories', 'DELETE'),
  'authenticated members have no direct table write grants'
);

select throws_ok(
  $$
    insert into public.categories (owner_id, name, normalized_name, kind, system_code)
    values (
      '10000000-0000-0000-0000-000000000004',
      '잘못된 시스템 분류',
      '잘못된 시스템 분류',
      'system',
      null
    )
  $$,
  '23514',
  null,
  'a system category requires a non-null documented system code'
);

set local role authenticated;
set local "request.jwt.claim.sub" = '10000000-0000-0000-0000-000000000001';

select is(
  public.member_bootstrap('10000000-0000-0000-0000-000000000002') #>> '{profile,id}',
  '10000000-0000-0000-0000-000000000001',
  'the request ID cannot inject another owner ID'
);

select is(
  public.member_bootstrap('20000000-0000-0000-0000-000000000002') #>> '{profile,state}',
  'active',
  'bootstrap returns an active profile'
);

select is(
  public.member_bootstrap('20000000-0000-0000-0000-000000000003') -> 'limits',
  '{"items":100,"image_bytes":20000000}'::jsonb,
  'bootstrap returns the fixed beta limits'
);

select is(
  public.member_bootstrap('20000000-0000-0000-0000-000000000004') -> 'usage',
  '{"active_item_count":0,"used_image_bytes":0,"reserved_image_bytes":0}'::jsonb,
  'bootstrap returns zeroed usage'
);

select is(
  jsonb_array_length(
    public.member_bootstrap('20000000-0000-0000-0000-000000000005') -> 'categories'
  ),
  8,
  'bootstrap returns eight categories'
);

select ok(
  (
    select
      response -> 'categories' @> '[{"name":"여행","kind":"system","system_code":"travel"}]'::jsonb
      and not exists (
        select 1
        from jsonb_array_elements(response -> 'categories') as category(value)
        where not (category.value ?& array['id', 'name', 'kind', 'system_code'])
      )
    from (
      select public.member_bootstrap(
        '20000000-0000-0000-0000-000000000006'
      ) as response
    ) as bootstrap
  ),
  'every bootstrap category has the contracted shape'
);

select is(
  (
    select jsonb_agg(category.value ->> 'system_code' order by category.ordinality)
    from jsonb_array_elements(
      public.member_bootstrap('20000000-0000-0000-0000-000000000007') -> 'categories'
    ) with ordinality as category(value, ordinality)
  ),
  '["travel","food","work","shopping","tools","life","culture","other"]'::jsonb,
  'bootstrap returns system categories in the documented order'
);

select is(
  (select count(*)::integer from public.categories),
  8,
  'repeated bootstrap calls remain idempotent'
);

select is(
  (
    select jsonb_object_agg(category.system_code, category.name)
    from public.categories as category
  ),
  '{"travel":"여행","food":"음식·맛집","work":"업무·학습","shopping":"쇼핑","tools":"앱·도구","life":"생활·건강","culture":"문화·읽을거리","other":"기타"}'::jsonb,
  'the eight system code and display-name pairs match the contract'
);

reset role;

select ok(
  (select count(*) = 1 from public.profiles where id = '10000000-0000-0000-0000-000000000001')
  and (select count(*) = 1 from public.library_usage where owner_id = '10000000-0000-0000-0000-000000000001')
  and (select count(*) = 8 from public.categories where owner_id = '10000000-0000-0000-0000-000000000001'),
  'bootstrap commits one profile, one usage row, and eight categories'
);

set local role authenticated;
set local "request.jwt.claim.sub" = '10000000-0000-0000-0000-000000000002';

select lives_ok(
  $$ select public.member_bootstrap('20000000-0000-0000-0000-000000000008') $$,
  'a second approved member can bootstrap'
);

select is(
  (select count(*)::integer from public.categories),
  8,
  'member B reads only member B categories'
);

reset role;
set local role authenticated;
set local "request.jwt.claim.sub" = '10000000-0000-0000-0000-000000000001';

select is(
  (select count(*)::integer from public.categories),
  8,
  'member A still reads only member A categories'
);

select is(
  (
    select count(*)::integer
    from public.categories
    where owner_id = '10000000-0000-0000-0000-000000000002'
  ),
  0,
  'member A cannot read member B categories by owner UUID'
);

select is(
  (select count(*)::integer from public.profiles),
  1,
  'member A cannot read other profiles'
);

select is(
  public.member_me() ->> 'state',
  'active',
  'member_me reports an approved bootstrapped member as active'
);

select ok(
  public.member_me() ?& array['state', 'limits', 'usage'],
  'active member_me includes limits and protected usage'
);

select is(
  public.member_access_allowed('10000000-0000-0000-0000-000000000002'),
  false,
  'the RLS helper cannot be used to probe another member ID'
);

reset role;
set local role authenticated;
set local "request.jwt.claim.sub" = '10000000-0000-0000-0000-000000000003';

select is(
  public.member_me(),
  '{"state":"pending_approval"}'::jsonb,
  'an unapproved auth user can call member_me without a profile'
);

select throws_ok(
  $$ select public.member_bootstrap('20000000-0000-0000-0000-000000000009') $$,
  'P0001',
  'BETA_ACCESS_REQUIRED',
  'an unapproved user cannot bootstrap'
);

reset role;

select ok(
  (select count(*) = 0 from public.profiles where id = '10000000-0000-0000-0000-000000000003')
  and (select count(*) = 0 from public.library_usage where owner_id = '10000000-0000-0000-0000-000000000003')
  and (select count(*) = 0 from public.categories where owner_id = '10000000-0000-0000-0000-000000000003'),
  'a rejected bootstrap leaves no partial member data'
);

set local role authenticated;
set local "request.jwt.claim.sub" = '10000000-0000-0000-0000-000000000004';

select is(
  public.member_me(),
  '{"state":"pending_approval"}'::jsonb,
  'a revoked member receives only pending_approval state'
);

select throws_ok(
  $$ select public.member_bootstrap('20000000-0000-0000-0000-000000000010') $$,
  'P0001',
  'BETA_ACCESS_REQUIRED',
  'a revoked member cannot bootstrap again'
);

select is(
  (select count(*)::integer from public.profiles),
  0,
  'RLS hides an existing active profile after beta access is revoked'
);

reset role;

select ok(
  (select count(*) = 1 from public.profiles where id = '10000000-0000-0000-0000-000000000004')
  and (select count(*) = 1 from public.library_usage where owner_id = '10000000-0000-0000-0000-000000000004'),
  'revoked member data still exists while member_me withholds protected usage'
);

set local role authenticated;
set local "request.jwt.claim.sub" = '10000000-0000-0000-0000-000000000005';

select is(
  public.member_me(),
  '{"state":"deleting"}'::jsonb,
  'a deleting member receives only deleting state'
);

select throws_ok(
  $$ select public.member_bootstrap('20000000-0000-0000-0000-000000000011') $$,
  'P0001',
  'ACCOUNT_DELETING',
  'an existing deleting profile blocks bootstrap before recreation'
);

reset role;

select ok(
  (select count(*) = 0 from public.library_usage where owner_id = '10000000-0000-0000-0000-000000000005')
  and (select count(*) = 0 from public.categories where owner_id = '10000000-0000-0000-0000-000000000005'),
  'the deleting guard leaves no bootstrap usage or categories'
);

set local role authenticated;
set local "request.jwt.claim.sub" = '10000000-0000-0000-0000-000000000001';

select throws_ok(
  $$ select public.member_bootstrap(null) $$,
  '22004',
  'REQUEST_ID_REQUIRED',
  'bootstrap rejects a null transported X-Request-Id'
);

reset role;
set local role authenticated;
set local "request.jwt.claim.sub" = '';

select throws_ok(
  $$ select public.member_me() $$,
  'P0001',
  'UNAUTHENTICATED',
  'member_me validates auth.uid even for the authenticated database role'
);

select throws_ok(
  $$ select public.member_bootstrap('20000000-0000-0000-0000-000000000012') $$,
  'P0001',
  'UNAUTHENTICATED',
  'member_bootstrap validates auth.uid even for the authenticated database role'
);

reset role;
set local role anon;
set local "request.jwt.claim.sub" = '';

select throws_ok(
  $$ select public.member_me() $$,
  '42501',
  null,
  'anon cannot execute member_me'
);

select throws_ok(
  $$ select public.member_bootstrap('20000000-0000-0000-0000-000000000013') $$,
  '42501',
  null,
  'anon cannot execute member_bootstrap'
);

select throws_ok(
  $$ select * from public.categories $$,
  '42501',
  null,
  'anon cannot read member categories'
);

reset role;
set local role authenticated;
set local "request.jwt.claim.sub" = '10000000-0000-0000-0000-000000000001';

select throws_ok(
  $$ select * from public.beta_members $$,
  '42501',
  null,
  'a member cannot directly read beta administration rows'
);

select throws_ok(
  $$ select * from public.library_usage $$,
  '42501',
  null,
  'a member receives usage only through the member RPC'
);

select throws_ok(
  $$ update public.profiles set state = 'deleting' where id = '10000000-0000-0000-0000-000000000001' $$,
  '42501',
  null,
  'a member cannot directly update a profile'
);

select throws_ok(
  $$ insert into public.categories (owner_id, name, normalized_name, kind) values ('10000000-0000-0000-0000-000000000001', '직접 쓰기', '직접 쓰기', 'custom') $$,
  '42501',
  null,
  'a member cannot directly insert a category'
);

select throws_ok(
  $$ update public.library_usage set active_item_count = 99 where owner_id = '10000000-0000-0000-0000-000000000001' $$,
  '42501',
  null,
  'a member cannot directly change usage counters'
);

select throws_ok(
  $$ update public.beta_members set enabled = false where owner_id = '10000000-0000-0000-0000-000000000001' $$,
  '42501',
  null,
  'a member cannot directly change beta approval'
);

reset role;

select lives_ok(
  $$
    insert into public.beta_members (owner_id, enabled, approved_at)
    values
      ('10000000-0000-0000-0000-000000000006', true, now()),
      ('10000000-0000-0000-0000-000000000007', true, now()),
      ('10000000-0000-0000-0000-000000000008', true, now())
  $$,
  'administrative writes can enable members through the sixth slot'
);

select is(
  (select count(*)::integer from public.beta_members where enabled),
  6,
  'the enabled beta population reaches exactly six'
);

select throws_ok(
  $$
    update public.beta_members
    set enabled = true, approved_at = now()
    where owner_id = '10000000-0000-0000-0000-000000000009'
  $$,
  '23514',
  'BETA_MEMBER_LIMIT_REACHED',
  'the trigger rejects an administrative write beyond the cap'
);

select is(
  (select count(*)::integer from public.beta_members where enabled),
  6,
  'a rejected administrative write leaves the cap unchanged'
);

select * from finish();
rollback;
