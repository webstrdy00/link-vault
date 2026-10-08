create table public.beta_members (
  owner_id uuid primary key references auth.users (id) on delete cascade,
  enabled boolean not null default false,
  approved_at timestamptz,
  constraint beta_members_enabled_requires_approval
    check (not enabled or approved_at is not null)
);

create table public.profiles (
  id uuid primary key references auth.users (id) on delete cascade,
  state text not null default 'active',
  created_at timestamptz not null default now(),
  deletion_requested_at timestamptz,
  constraint profiles_state_check check (state in ('active', 'deleting')),
  constraint profiles_deletion_requested_at_check
    check (state = 'deleting' or deletion_requested_at is null)
);

create table public.library_usage (
  owner_id uuid primary key references public.profiles (id) on delete cascade,
  active_item_count integer not null default 0,
  used_image_bytes bigint not null default 0,
  reserved_image_bytes bigint not null default 0,
  updated_at timestamptz not null default now(),
  constraint library_usage_active_item_count_check check (active_item_count >= 0),
  constraint library_usage_used_image_bytes_check check (used_image_bytes >= 0),
  constraint library_usage_reserved_image_bytes_check check (reserved_image_bytes >= 0)
);

create table public.categories (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references public.profiles (id) on delete cascade,
  name text not null,
  normalized_name text generated always as (lower(btrim(name))) stored,
  kind text not null,
  system_code text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint categories_owner_id_id_key unique (owner_id, id),
  constraint categories_owner_normalized_name_key unique (owner_id, normalized_name),
  constraint categories_owner_system_code_key unique (owner_id, system_code),
  constraint categories_name_check check (char_length(btrim(name)) between 1 and 30),
  constraint categories_kind_check check (kind in ('system', 'custom')),
  constraint categories_system_code_check check (
    (kind = 'system' and system_code is not null and system_code in (
      'travel',
      'food',
      'work',
      'shopping',
      'tools',
      'life',
      'culture',
      'other'
    ))
    or (kind = 'custom' and system_code is null)
  )
);

create function public.enforce_beta_member_cap()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  enabled_count bigint;
begin
  perform pg_catalog.pg_advisory_xact_lock(713002609130001::bigint);

  if tg_op = 'DELETE' then
    return old;
  end if;

  if new.enabled then
    if tg_op = 'UPDATE' then
      select count(*)
      into enabled_count
      from public.beta_members as member
      where member.enabled
        and member.owner_id <> old.owner_id;
    else
      select count(*)
      into enabled_count
      from public.beta_members as member
      where member.enabled;
    end if;

    if enabled_count >= 6 then
      raise exception using
        errcode = '23514',
        message = 'BETA_MEMBER_LIMIT_REACHED',
        constraint = 'beta_members_enabled_cap';
    end if;
  end if;

  return new;
end;
$$;

create trigger beta_members_enforce_enabled_cap
before insert or update or delete on public.beta_members
for each row execute function public.enforce_beta_member_cap();

create function public.member_access_allowed(p_owner_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select
    auth.uid() is not null
    and p_owner_id = auth.uid()
    and exists (
      select 1
      from public.profiles as profile
      join public.beta_members as member
        on member.owner_id = profile.id
      where profile.id = p_owner_id
        and profile.state = 'active'
        and member.enabled
        and member.approved_at is not null
    );
$$;

alter table public.beta_members enable row level security;
alter table public.profiles enable row level security;
alter table public.library_usage enable row level security;
alter table public.categories enable row level security;

create policy profiles_select_own_active_member
on public.profiles
for select
to authenticated
using (public.member_access_allowed(id));

create policy categories_select_own_active_member
on public.categories
for select
to authenticated
using (public.member_access_allowed(owner_id));

revoke all privileges on table public.beta_members from public, anon, authenticated;
revoke all privileges on table public.profiles from public, anon, authenticated;
revoke all privileges on table public.library_usage from public, anon, authenticated;
revoke all privileges on table public.categories from public, anon, authenticated;
grant select on table public.profiles to authenticated;
grant select on table public.categories to authenticated;

revoke all on function public.enforce_beta_member_cap() from public, anon, authenticated;
revoke all on function public.member_access_allowed(uuid) from public, anon;
grant execute on function public.member_access_allowed(uuid) to authenticated;

create function public.member_bootstrap(p_request_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller_id uuid := auth.uid();
  profile_state text;
  member_enabled boolean;
  member_approved_at timestamptz;
  result jsonb;
begin
  if caller_id is null then
    raise exception using errcode = 'P0001', message = 'UNAUTHENTICATED';
  end if;

  if p_request_id is null then
    raise exception using errcode = '22004', message = 'REQUEST_ID_REQUIRED';
  end if;

  select profile.state
  into profile_state
  from public.profiles as profile
  where profile.id = caller_id
  for update;

  if found and profile_state = 'deleting' then
    raise exception using errcode = 'P0001', message = 'ACCOUNT_DELETING';
  end if;

  select member.enabled, member.approved_at
  into member_enabled, member_approved_at
  from public.beta_members as member
  where member.owner_id = caller_id
  for share;

  if not found or not member_enabled or member_approved_at is null then
    raise exception using errcode = 'P0001', message = 'BETA_ACCESS_REQUIRED';
  end if;

  insert into public.profiles (id, state)
  values (caller_id, 'active')
  on conflict (id) do nothing;

  select profile.state
  into profile_state
  from public.profiles as profile
  where profile.id = caller_id
  for update;

  if profile_state = 'deleting' then
    raise exception using errcode = 'P0001', message = 'ACCOUNT_DELETING';
  end if;

  insert into public.library_usage (owner_id)
  values (caller_id)
  on conflict (owner_id) do nothing;

  insert into public.categories (owner_id, name, kind, system_code)
  values
    (caller_id, '여행', 'system', 'travel'),
    (caller_id, '음식·맛집', 'system', 'food'),
    (caller_id, '업무·학습', 'system', 'work'),
    (caller_id, '쇼핑', 'system', 'shopping'),
    (caller_id, '앱·도구', 'system', 'tools'),
    (caller_id, '생활·건강', 'system', 'life'),
    (caller_id, '문화·읽을거리', 'system', 'culture'),
    (caller_id, '기타', 'system', 'other')
  on conflict (owner_id, system_code) do nothing;

  select pg_catalog.jsonb_build_object(
    'profile', pg_catalog.jsonb_build_object(
      'id', profile.id,
      'state', profile.state
    ),
    'limits', pg_catalog.jsonb_build_object(
      'items', 100,
      'image_bytes', 20000000
    ),
    'usage', pg_catalog.jsonb_build_object(
      'active_item_count', usage.active_item_count,
      'used_image_bytes', usage.used_image_bytes,
      'reserved_image_bytes', usage.reserved_image_bytes
    ),
    'categories', coalesce(
      (
        select pg_catalog.jsonb_agg(
          pg_catalog.jsonb_build_object(
            'id', category.id,
            'name', category.name,
            'kind', category.kind,
            'system_code', category.system_code
          )
          order by
            case category.system_code
              when 'travel' then 1
              when 'food' then 2
              when 'work' then 3
              when 'shopping' then 4
              when 'tools' then 5
              when 'life' then 6
              when 'culture' then 7
              when 'other' then 8
              else 9
            end,
            category.created_at,
            category.id
        )
        from public.categories as category
        where category.owner_id = caller_id
      ),
      '[]'::jsonb
    )
  )
  into result
  from public.profiles as profile
  join public.library_usage as usage
    on usage.owner_id = profile.id
  where profile.id = caller_id;

  return result;
end;
$$;

create function public.member_me()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller_id uuid := auth.uid();
  profile_state text;
  member_enabled boolean;
  member_approved_at timestamptz;
  result jsonb;
begin
  if caller_id is null then
    raise exception using errcode = 'P0001', message = 'UNAUTHENTICATED';
  end if;

  select profile.state
  into profile_state
  from public.profiles as profile
  where profile.id = caller_id
  for share;

  if found and profile_state = 'deleting' then
    return pg_catalog.jsonb_build_object('state', 'deleting');
  end if;

  if not found or profile_state <> 'active' then
    return pg_catalog.jsonb_build_object('state', 'pending_approval');
  end if;

  select member.enabled, member.approved_at
  into member_enabled, member_approved_at
  from public.beta_members as member
  where member.owner_id = caller_id
  for share;

  if not found or not member_enabled or member_approved_at is null then
    return pg_catalog.jsonb_build_object('state', 'pending_approval');
  end if;

  select pg_catalog.jsonb_build_object(
    'state', 'active',
    'limits', pg_catalog.jsonb_build_object(
      'items', 100,
      'image_bytes', 20000000
    ),
    'usage', pg_catalog.jsonb_build_object(
      'active_item_count', usage.active_item_count,
      'used_image_bytes', usage.used_image_bytes,
      'reserved_image_bytes', usage.reserved_image_bytes
    )
  )
  into result
  from public.library_usage as usage
  where usage.owner_id = caller_id;

  return result;
end;
$$;

revoke all on function public.member_bootstrap(uuid) from public, anon;
revoke all on function public.member_me() from public, anon;
grant execute on function public.member_bootstrap(uuid) to authenticated;
grant execute on function public.member_me() to authenticated;
