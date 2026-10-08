create extension if not exists pg_cron;
create extension if not exists pg_net with schema extensions;
create extension if not exists supabase_vault cascade;

create function private.classification_code_rank(p_code text)
returns integer
language sql
immutable
parallel safe
strict
set search_path = ''
as $$
  select case p_code
    when 'travel' then 1
    when 'food' then 2
    when 'work' then 3
    when 'shopping' then 4
    when 'tools' then 5
    when 'life' then 6
    when 'culture' then 7
    else 2147483647
  end;
$$;

create function private.valid_classification_rule_id(
  p_code text,
  p_rule_id text
)
returns boolean
language plpgsql
immutable
parallel safe
strict
set search_path = ''
as $$
declare
  rule_strength text;
  rule_index_text text;
  rule_index integer;
  maximum_index integer;
begin
  rule_strength := pg_catalog.split_part(p_rule_id, ':', 2);
  rule_index_text := pg_catalog.split_part(p_rule_id, ':', 3);

  if p_rule_id <> p_code || ':' || rule_strength || ':' || rule_index_text
    or rule_strength not in ('strong', 'weak')
    or rule_index_text !~ '^(0|[1-9][0-9]*)$'
    or pg_catalog.char_length(rule_index_text) > 2 then
    return false;
  end if;

  rule_index := rule_index_text::integer;
  maximum_index := case p_code
    when 'travel' then case rule_strength when 'strong' then 5 else 2 end
    when 'food' then case rule_strength when 'strong' then 5 else 3 end
    when 'work' then case rule_strength when 'strong' then 7 else 3 end
    when 'shopping' then 4
    when 'tools' then case rule_strength when 'strong' then 6 else 4 end
    when 'life' then case rule_strength when 'strong' then 7 else 2 end
    when 'culture' then case rule_strength when 'strong' then 9 else 2 end
    else -1
  end;

  return rule_index between 0 and maximum_index;
end;
$$;

create function private.valid_classification_result(p_result jsonb)
returns boolean
language plpgsql
immutable
parallel safe
set search_path = ''
as $$
declare
  category_record record;
  rule_record record;
  category_code text;
  category_score integer;
  category_rank integer;
  previous_score integer;
  previous_rank integer;
  calculated_score integer;
  strong_rule_count integer;
  has_qualifying_weak_field boolean;
  rule_id text;
  target_revision_text text;
begin
  if not private.jsonb_has_exact_keys(
    p_result,
    array['rules_version', 'target_revision', 'categories']::text[]
  ) then
    return false;
  end if;

  if pg_catalog.jsonb_typeof(p_result -> 'rules_version') <> 'string'
    or p_result ->> 'rules_version' <> 'rules-v2.0.0'
    or pg_catalog.jsonb_typeof(p_result -> 'target_revision') <> 'number'
    or pg_catalog.jsonb_typeof(p_result -> 'categories') <> 'array' then
    return false;
  end if;

  if pg_catalog.jsonb_array_length(p_result -> 'categories') > 3 then
    return false;
  end if;

  target_revision_text := p_result ->> 'target_revision';
  if target_revision_text !~ '^[1-9][0-9]*$'
    or pg_catalog.char_length(target_revision_text) > 10
    or target_revision_text::numeric > 2147483647 then
    return false;
  end if;

  if (
    select count(*) <> count(distinct category.value ->> 'code')
    from pg_catalog.jsonb_array_elements(p_result -> 'categories') as category(value)
  ) then
    return false;
  end if;

  for category_record in
    select category.value, category.ordinality
    from pg_catalog.jsonb_array_elements(p_result -> 'categories')
      with ordinality as category(value, ordinality)
    order by category.ordinality
  loop
    if not private.jsonb_has_exact_keys(
      category_record.value,
      array['code', 'score', 'rules']::text[]
    ) then
      return false;
    end if;

    if pg_catalog.jsonb_typeof(category_record.value -> 'code') <> 'string'
      or pg_catalog.jsonb_typeof(category_record.value -> 'score') <> 'number'
      or pg_catalog.jsonb_typeof(category_record.value -> 'rules') <> 'array' then
      return false;
    end if;

    if pg_catalog.jsonb_array_length(category_record.value -> 'rules') = 0 then
      return false;
    end if;

    category_code := category_record.value ->> 'code';
    if category_code not in (
      'travel',
      'food',
      'work',
      'shopping',
      'tools',
      'life',
      'culture'
    ) then
      return false;
    end if;

    if category_record.value ->> 'score' !~ '^[1-9][0-9]*$'
      or pg_catalog.char_length(category_record.value ->> 'score') > 2 then
      return false;
    end if;
    category_score := (category_record.value ->> 'score')::integer;
    category_rank := private.classification_code_rank(category_code);

    if previous_score is not null
      and (
        category_score > previous_score
        or (category_score = previous_score and category_rank <= previous_rank)
      ) then
      return false;
    end if;
    previous_score := category_score;
    previous_rank := category_rank;

    if (
      select count(*) <> count(distinct rule.value ->> 'id')
      from pg_catalog.jsonb_array_elements(category_record.value -> 'rules') as rule(value)
    ) then
      return false;
    end if;

    calculated_score := 0;
    strong_rule_count := 0;
    for rule_record in
      select rule.value
      from pg_catalog.jsonb_array_elements(category_record.value -> 'rules') as rule(value)
    loop
      if not private.jsonb_has_exact_keys(
        rule_record.value,
        array['id', 'fields']::text[]
      ) then
        return false;
      end if;

      if pg_catalog.jsonb_typeof(rule_record.value -> 'id') <> 'string'
        or pg_catalog.jsonb_typeof(rule_record.value -> 'fields') <> 'array' then
        return false;
      end if;

      if pg_catalog.jsonb_array_length(rule_record.value -> 'fields') = 0
        or pg_catalog.jsonb_array_length(rule_record.value -> 'fields') > 7 then
        return false;
      end if;

      rule_id := rule_record.value ->> 'id';
      if not private.valid_classification_rule_id(category_code, rule_id) then
        return false;
      end if;

      if exists (
        select 1
        from pg_catalog.jsonb_array_elements(rule_record.value -> 'fields') as field(value)
        where pg_catalog.jsonb_typeof(field.value) <> 'string'
          or field.value #>> '{}' not in (
            'user_title',
            'fetched_title',
            'note',
            'ocr',
            'shared',
            'description',
            'body'
          )
      ) or (
        select count(*) <> count(distinct field.value #>> '{}')
        from pg_catalog.jsonb_array_elements(rule_record.value -> 'fields') as field(value)
      ) then
        return false;
      end if;

      if pg_catalog.split_part(rule_id, ':', 2) = 'strong' then
        calculated_score := calculated_score + 3;
        strong_rule_count := strong_rule_count + 1;
      else
        calculated_score := calculated_score + 1;
      end if;
    end loop;

    if category_score <> calculated_score or category_score < 3 then
      return false;
    end if;

    if strong_rule_count = 0 then
      select exists (
        select 1
        from pg_catalog.jsonb_array_elements(category_record.value -> 'rules') as weak_rule(value)
        cross join lateral pg_catalog.jsonb_array_elements(weak_rule.value -> 'fields') as field(value)
        where pg_catalog.split_part(weak_rule.value ->> 'id', ':', 2) = 'weak'
        group by field.value #>> '{}'
        having count(*) >= 3
      )
      into has_qualifying_weak_field;

      if not has_qualifying_weak_field then
        return false;
      end if;
    end if;
  end loop;

  return true;
end;
$$;

create function public.library_claim_classification_jobs(p_limit integer default 20)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  effective_limit integer;
  claimed_jobs jsonb;
begin
  if p_limit is null or p_limit < 1 then
    raise exception using errcode = 'P0001', message = 'INVALID_LIMIT';
  end if;
  effective_limit := least(p_limit, 20);

  with obsolete as materialized (
    select job.id
    from public.processing_jobs as job
    left join public.items as item
      on item.owner_id = job.owner_id
      and item.id = job.item_id
    left join public.item_search as search_record
      on search_record.owner_id = job.owner_id
      and search_record.item_id = job.item_id
    left join public.item_classification as classification
      on classification.owner_id = job.owner_id
      and classification.item_id = job.item_id
    left join public.item_category_controls as controls
      on controls.owner_id = job.owner_id
      and controls.item_id = job.item_id
    left join public.profiles as profile
      on profile.id = job.owner_id
    left join public.beta_members as member
      on member.owner_id = job.owner_id
    where job.kind = 'classify'
      and (
        (job.state in ('queued', 'retry') and job.next_run_at <= pg_catalog.clock_timestamp())
        or (job.state = 'running' and job.lease_until <= pg_catalog.clock_timestamp())
      )
      and (
        item.id is null
        or item.deleted_at is not null
        or item.text_revision <> job.target_revision
        or search_record.item_id is null
        or search_record.text_revision <> job.target_revision
        or classification.item_id is null
        or classification.target_revision <> job.target_revision
        or classification.state <> 'pending'
        or controls.item_id is null
        or controls.manual_override
        or profile.state is distinct from 'active'
        or member.enabled is distinct from true
        or member.approved_at is null
      )
    for update of job skip locked
  )
  update public.processing_jobs as job
  set state = 'cancelled',
      lease_until = null,
      lease_token = null,
      last_error_code = null,
      updated_at = pg_catalog.clock_timestamp()
  from obsolete
  where job.id = obsolete.id;

  with exhausted as materialized (
    select job.id
    from public.processing_jobs as job
    where job.kind = 'classify'
      and job.attempts >= 3
      and (
        (job.state in ('queued', 'retry') and job.next_run_at <= pg_catalog.clock_timestamp())
        or (job.state = 'running' and job.lease_until <= pg_catalog.clock_timestamp())
      )
    for update of job skip locked
  )
  update public.processing_jobs as job
  set state = 'failed',
      lease_until = null,
      lease_token = null,
      last_error_code = 'WORKER_TIMEOUT',
      updated_at = pg_catalog.clock_timestamp()
  from exhausted
  where job.id = exhausted.id;

  with candidates as materialized (
    select job.id
    from public.processing_jobs as job
    join public.items as item
      on item.owner_id = job.owner_id
      and item.id = job.item_id
      and item.deleted_at is null
      and item.text_revision = job.target_revision
    join public.item_search as search_record
      on search_record.owner_id = job.owner_id
      and search_record.item_id = job.item_id
      and search_record.text_revision = job.target_revision
    join public.item_classification as classification
      on classification.owner_id = job.owner_id
      and classification.item_id = job.item_id
      and classification.target_revision = job.target_revision
      and classification.state = 'pending'
    join public.item_category_controls as controls
      on controls.owner_id = job.owner_id
      and controls.item_id = job.item_id
      and not controls.manual_override
    join public.profiles as profile
      on profile.id = job.owner_id
      and profile.state = 'active'
    join public.beta_members as member
      on member.owner_id = job.owner_id
      and member.enabled
      and member.approved_at is not null
    where job.kind = 'classify'
      and job.attempts < 3
      and (
        (job.state in ('queued', 'retry') and job.next_run_at <= pg_catalog.clock_timestamp())
        or (job.state = 'running' and job.lease_until <= pg_catalog.clock_timestamp())
      )
    order by job.next_run_at, job.created_at, job.id
    for update of job skip locked
    limit effective_limit
  ), leased as (
    update public.processing_jobs as job
    set state = 'running',
        attempts = job.attempts + 1,
        lease_until = pg_catalog.clock_timestamp() + interval '180 seconds',
        lease_token = extensions.gen_random_uuid(),
        last_error_code = null,
        updated_at = pg_catalog.clock_timestamp()
    from candidates
    where job.id = candidates.id
    returning
      job.id,
      job.lease_token,
      job.owner_id,
      job.item_id,
      job.target_revision,
      job.created_at
  )
  select coalesce(
    pg_catalog.jsonb_agg(
      pg_catalog.jsonb_build_object(
        'job_id', leased.id,
        'lease_token', leased.lease_token,
        'owner_id', leased.owner_id,
        'target_revision', leased.target_revision,
        'item', private.library_item_json(leased.owner_id, leased.item_id, true)
      )
      order by leased.created_at, leased.id
    ),
    '[]'::jsonb
  )
  into claimed_jobs
  from leased;

  return pg_catalog.jsonb_build_object('jobs', claimed_jobs);
end;
$$;

create function public.library_complete_classification_job(
  p_job_id uuid,
  p_lease_token uuid,
  p_expected_version integer,
  p_result jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  initial_owner_id uuid;
  initial_item_id uuid;
  current_job public.processing_jobs%rowtype;
  current_item public.items%rowtype;
  current_search public.item_search%rowtype;
  current_classification public.item_classification%rowtype;
  current_controls public.item_category_controls%rowtype;
  profile_state text;
  member_enabled boolean;
  member_approved_at timestamptz;
  item_found boolean := false;
  custom_count integer;
  automatic_limit integer;
  requested_category_count integer;
  owned_system_category_count integer;
  normalized_category_names text;
  stored_reasons jsonb;
  result_item jsonb;
  affected_count integer;
begin
  if p_job_id is null or p_lease_token is null then
    return pg_catalog.jsonb_build_object('state', 'discarded');
  end if;

  select job.owner_id, job.item_id
  into initial_owner_id, initial_item_id
  from public.processing_jobs as job
  where job.id = p_job_id
    and job.kind = 'classify';

  if not found then
    return pg_catalog.jsonb_build_object('state', 'discarded');
  end if;

  select profile.state
  into profile_state
  from public.profiles as profile
  where profile.id = initial_owner_id
  for share;

  select member.enabled, member.approved_at
  into member_enabled, member_approved_at
  from public.beta_members as member
  where member.owner_id = initial_owner_id
  for share;

  select item.*
  into current_item
  from public.items as item
  where item.owner_id = initial_owner_id
    and item.id = initial_item_id
  for update;
  item_found := found;

  if item_found then
    select search_record.*
    into current_search
    from public.item_search as search_record
    where search_record.owner_id = initial_owner_id
      and search_record.item_id = initial_item_id
    for update;

    select classification.*
    into current_classification
    from public.item_classification as classification
    where classification.owner_id = initial_owner_id
      and classification.item_id = initial_item_id
    for update;

    select controls.*
    into current_controls
    from public.item_category_controls as controls
    where controls.owner_id = initial_owner_id
      and controls.item_id = initial_item_id
    for update;
  end if;

  select job.*
  into current_job
  from public.processing_jobs as job
  where job.id = p_job_id
  for update;

  if not found
    or current_job.kind <> 'classify'
    or current_job.owner_id <> initial_owner_id
    or current_job.item_id <> initial_item_id
    or current_job.state <> 'running'
    or current_job.lease_token <> p_lease_token
    or current_job.lease_until <= pg_catalog.clock_timestamp() then
    return pg_catalog.jsonb_build_object('state', 'discarded');
  end if;

  if not item_found
    or current_item.deleted_at is not null
    or profile_state is distinct from 'active'
    or member_enabled is distinct from true
    or member_approved_at is null
    or current_search.item_id is null
    or current_classification.item_id is null
    or current_controls.item_id is null
    or current_controls.manual_override
    or current_item.text_revision <> current_job.target_revision
    or current_search.text_revision <> current_job.target_revision
    or current_classification.target_revision <> current_job.target_revision
    or current_classification.state <> 'pending' then
    update public.processing_jobs
    set state = 'cancelled',
        lease_until = null,
        lease_token = null,
        last_error_code = null,
        updated_at = pg_catalog.clock_timestamp()
    where id = current_job.id;

    return pg_catalog.jsonb_build_object('state', 'discarded');
  end if;

  if not private.valid_classification_result(p_result) then
    raise exception using errcode = 'P0001', message = 'INVALID_CLASSIFICATION_RESULT';
  end if;

  if p_expected_version is null or p_expected_version < 1 then
    raise exception using errcode = 'P0001', message = 'INVALID_EXPECTED_VERSION';
  end if;

  if (p_result ->> 'target_revision')::integer <> current_job.target_revision then
    raise exception using errcode = 'P0001', message = 'INVALID_CLASSIFICATION_RESULT';
  end if;

  if p_expected_version <> current_item.version then
    result_item := private.library_item_json(
      current_job.owner_id,
      current_job.item_id,
      true
    );

    if result_item is null then
      update public.processing_jobs
      set state = 'cancelled',
          lease_until = null,
          lease_token = null,
          last_error_code = null,
          updated_at = pg_catalog.clock_timestamp()
      where id = current_job.id;

      return pg_catalog.jsonb_build_object('state', 'discarded');
    end if;

    return pg_catalog.jsonb_build_object(
      'state', 'version_conflict',
      'item', result_item
    );
  end if;

  requested_category_count := pg_catalog.jsonb_array_length(p_result -> 'categories');
  select count(*)::integer
  into owned_system_category_count
  from pg_catalog.jsonb_array_elements(p_result -> 'categories') as requested(value)
  join public.categories as category
    on category.owner_id = current_job.owner_id
    and category.kind = 'system'
    and category.system_code = requested.value ->> 'code';

  if owned_system_category_count <> requested_category_count then
    raise exception using errcode = 'P0001', message = 'CLASSIFICATION_CATEGORIES_UNAVAILABLE';
  end if;

  select count(*)::integer
  into custom_count
  from public.item_categories as selected
  join public.categories as category
    on category.owner_id = selected.owner_id
    and category.id = selected.category_id
    and category.kind = 'custom'
  where selected.owner_id = current_job.owner_id
    and selected.item_id = current_job.item_id;
  automatic_limit := least(3, greatest(0, 5 - custom_count));

  update public.processing_jobs
  set state = 'succeeded',
      lease_until = null,
      lease_token = null,
      last_error_code = null,
      updated_at = pg_catalog.clock_timestamp()
  where id = current_job.id
    and state = 'running'
    and lease_token = p_lease_token
    and lease_until > pg_catalog.clock_timestamp();
  get diagnostics affected_count = row_count;

  if affected_count <> 1 then
    return pg_catalog.jsonb_build_object('state', 'discarded');
  end if;

  delete from public.item_categories as selected
  using public.categories as category
  where selected.owner_id = current_job.owner_id
    and selected.item_id = current_job.item_id
    and category.owner_id = selected.owner_id
    and category.id = selected.category_id
    and category.kind = 'system';

  insert into public.item_categories (owner_id, item_id, category_id, origin)
  select
    current_job.owner_id,
    current_job.item_id,
    category.id,
    'auto'
  from pg_catalog.jsonb_array_elements(p_result -> 'categories')
    with ordinality as requested(value, ordinality)
  join public.categories as category
    on category.owner_id = current_job.owner_id
    and category.kind = 'system'
    and category.system_code = requested.value ->> 'code'
  where requested.ordinality <= automatic_limit
  order by requested.ordinality;

  select coalesce(
    pg_catalog.jsonb_agg(requested.value order by requested.ordinality),
    '[]'::jsonb
  )
  into stored_reasons
  from pg_catalog.jsonb_array_elements(p_result -> 'categories')
    with ordinality as requested(value, ordinality)
  where requested.ordinality <= automatic_limit;

  select coalesce(
    pg_catalog.string_agg(
      category.normalized_name,
      ' '
      order by selected.created_at, selected.category_id
    ),
    ''
  )
  into normalized_category_names
  from public.item_categories as selected
  join public.categories as category
    on category.owner_id = selected.owner_id
    and category.id = selected.category_id
  where selected.owner_id = current_job.owner_id
    and selected.item_id = current_job.item_id;

  update public.item_classification
  set rules_version = 'rules-v2.0.0',
      target_revision = current_job.target_revision,
      state = case
        when pg_catalog.jsonb_array_length(stored_reasons) > 0 then 'automatic'
        else 'unclassified'
      end,
      reasons = stored_reasons,
      updated_at = pg_catalog.clock_timestamp()
  where owner_id = current_job.owner_id
    and item_id = current_job.item_id;

  update public.item_search
  set text_revision = current_job.target_revision,
      normalized_fields = normalized_fields || pg_catalog.jsonb_build_object(
        'categories', normalized_category_names
      ),
      updated_at = pg_catalog.clock_timestamp()
  where owner_id = current_job.owner_id
    and item_id = current_job.item_id;

  update public.items
  set version = version + 1,
      updated_at = pg_catalog.clock_timestamp()
  where owner_id = current_job.owner_id
    and id = current_job.item_id
    and deleted_at is null
    and text_revision = current_job.target_revision
    and version = current_item.version;
  get diagnostics affected_count = row_count;

  if affected_count <> 1 then
    raise exception using errcode = '40001', message = 'CLASSIFICATION_COMMIT_CONFLICT';
  end if;

  result_item := private.library_item_json(
    current_job.owner_id,
    current_job.item_id,
    true
  );
  if result_item is null then
    raise exception using errcode = '40001', message = 'CLASSIFICATION_COMMIT_CONFLICT';
  end if;

  return pg_catalog.jsonb_build_object(
    'state', 'succeeded',
    'item', result_item
  );
end;
$$;

create function public.library_fail_classification_job(
  p_job_id uuid,
  p_lease_token uuid,
  p_retryable boolean,
  p_error_code text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  failed_job public.processing_jobs%rowtype;
  retry_job boolean;
  next_state text;
  retry_at timestamptz;
begin
  if p_job_id is null or p_lease_token is null then
    return pg_catalog.jsonb_build_object('state', 'discarded');
  end if;

  if p_retryable is null
    or p_error_code is null
    or p_error_code not in (
      'WORKER_BUSY',
      'WORKER_TIMEOUT',
      'INVALID_RESULT',
      'INTERNAL_ERROR'
    ) then
    raise exception using errcode = 'P0001', message = 'INVALID_FAILURE';
  end if;

  select job.*
  into failed_job
  from public.processing_jobs as job
  where job.id = p_job_id
    and job.kind = 'classify'
  for update;

  if not found
    or failed_job.state <> 'running'
    or failed_job.lease_token <> p_lease_token
    or failed_job.lease_until <= pg_catalog.clock_timestamp() then
    return pg_catalog.jsonb_build_object('state', 'discarded');
  end if;

  retry_job := p_retryable
    and p_error_code in ('WORKER_BUSY', 'WORKER_TIMEOUT', 'INTERNAL_ERROR')
    and failed_job.attempts < 3;
  next_state := case when retry_job then 'retry' else 'failed' end;
  retry_at := case
    when not retry_job then failed_job.next_run_at
    when failed_job.attempts <= 1
      then pg_catalog.clock_timestamp() + interval '1 minute'
    else pg_catalog.clock_timestamp() + interval '5 minutes'
  end;

  update public.processing_jobs
  set state = next_state,
      next_run_at = retry_at,
      lease_until = null,
      lease_token = null,
      last_error_code = p_error_code,
      updated_at = pg_catalog.clock_timestamp()
  where id = failed_job.id
    and state = 'running'
    and lease_token = p_lease_token
    and lease_until > pg_catalog.clock_timestamp();

  if not found then
    return pg_catalog.jsonb_build_object('state', 'discarded');
  end if;

  return pg_catalog.jsonb_build_object('state', next_state);
end;
$$;

create function private.dispatch_classification_jobs()
returns bigint
language plpgsql
security definer
set search_path = ''
as $$
declare
  worker_url text;
  worker_token text;
  request_id bigint;
begin
  if not exists (
    select 1
    from public.processing_jobs as job
    where job.kind = 'classify'
      and (
        (job.state in ('queued', 'retry') and job.next_run_at <= pg_catalog.clock_timestamp())
        or (job.state = 'running' and job.lease_until <= pg_catalog.clock_timestamp())
      )
  ) then
    return null;
  end if;

  select secret.decrypted_secret
  into worker_url
  from vault.decrypted_secrets as secret
  where secret.name = 'link_vault_worker_url'
  limit 1;

  select secret.decrypted_secret
  into worker_token
  from vault.decrypted_secrets as secret
  where secret.name = 'link_vault_worker_token'
  limit 1;

  if nullif(worker_url, '') is null or nullif(worker_token, '') is null then
    return null;
  end if;

  select net.http_post(
    url := worker_url,
    body := pg_catalog.jsonb_build_object('limit', 20),
    headers := pg_catalog.jsonb_build_object(
      'Authorization',
      'Bearer ' || worker_token,
      'Content-Type',
      'application/json'
    ),
    timeout_milliseconds := 5000
  )
  into request_id;

  return request_id;
end;
$$;

revoke all on function private.library_item_json(uuid, uuid, boolean)
from public, anon, authenticated, service_role;
revoke all on function private.classification_code_rank(text)
from public, anon, authenticated, service_role;
revoke all on function private.valid_classification_rule_id(text, text)
from public, anon, authenticated, service_role;
revoke all on function private.valid_classification_result(jsonb)
from public, anon, authenticated, service_role;
revoke all on function private.dispatch_classification_jobs()
from public, anon, authenticated, service_role;

revoke all on function public.library_claim_classification_jobs(integer)
from public, anon, authenticated, service_role;
revoke all on function public.library_complete_classification_job(uuid, uuid, integer, jsonb)
from public, anon, authenticated, service_role;
revoke all on function public.library_fail_classification_job(uuid, uuid, boolean, text)
from public, anon, authenticated, service_role;
grant execute on function public.library_claim_classification_jobs(integer) to service_role;
grant execute on function public.library_complete_classification_job(uuid, uuid, integer, jsonb)
to service_role;
grant execute on function public.library_fail_classification_job(uuid, uuid, boolean, text)
to service_role;

revoke all privileges on table vault.secrets from public, anon, authenticated;
revoke all privileges on table vault.decrypted_secrets from public, anon, authenticated;
revoke all privileges on schema vault from public, anon, authenticated;

select cron.schedule(
  'link-vault-classify',
  '* * * * *',
  'select private.dispatch_classification_jobs();'
);
