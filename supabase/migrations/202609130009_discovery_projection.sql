-- 005/006 are already applied. Preserve their signatures and privileges while
-- exposing the authoritative manual lock and surfacing missing worker setup.
create or replace function private.library_item_json(
  p_owner_id uuid,
  p_item_id uuid,
  p_include_detail boolean
)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select
    pg_catalog.jsonb_build_object(
      'id', item.id,
      'version', item.version,
      'text_revision', item.text_revision,
      'url', item.original_url,
      'display_title', coalesce(
        nullif(pg_catalog.btrim(item.user_title), ''),
        nullif(pg_catalog.btrim(item.fetched_title), ''),
        item.display_fallback
      ),
      'source', item.source,
      'note_excerpt', case
        when item.note is null then null
        else pg_catalog.left(item.note, 120)
      end,
      'category_refs', coalesce(
        (
          select pg_catalog.jsonb_agg(
            pg_catalog.jsonb_build_object(
              'id', category.id,
              'name', category.name,
              'kind', category.kind,
              'system_code', category.system_code,
              'origin', selected.origin
            )
            order by selected.created_at, category.id
          )
          from public.item_categories as selected
          join public.categories as category
            on category.owner_id = selected.owner_id
            and category.id = selected.category_id
          where selected.owner_id = item.owner_id
            and selected.item_id = item.id
        ),
        '[]'::jsonb
      ),
      'has_attachment', false,
      'metadata_state', item.metadata_state,
      'ocr_state', 'not_requested',
      'classification_state', classification.state,
      'cue_state', search_record.cue_state,
      'cue_flags', search_record.cue_flags,
      'match_type', null,
      'created_at', item.created_at,
      'updated_at', item.updated_at
    )
    || case
      when p_include_detail then pg_catalog.jsonb_build_object(
        'user_title', item.user_title,
        'fetched_title', item.fetched_title,
        'shared_text', item.shared_text,
        'description', item.description,
        'body_text', item.body_text,
        'note', item.note,
        'extraction_meta', item.extraction_meta,
        'active_asset', null,
        'manual_override', controls.manual_override,
        'search_version', case
          when search_record.text_revision = item.text_revision
            then search_record.search_version
          else null
        end,
        'rules_version', case
          when classification.target_revision = item.text_revision
            then classification.rules_version
          else null
        end,
        'classification_reasons', case
          when classification.target_revision = item.text_revision
            then classification.reasons
          else '[]'::jsonb
        end,
        'cue_prompt_dismissed', coalesce(
          controls.cue_dismissed_revision = item.text_revision,
          false
        )
      )
      else '{}'::jsonb
    end
  from public.items as item
  join public.item_search as search_record
    on search_record.owner_id = item.owner_id
    and search_record.item_id = item.id
  join public.item_classification as classification
    on classification.owner_id = item.owner_id
    and classification.item_id = item.id
  join public.item_category_controls as controls
    on controls.owner_id = item.owner_id
    and controls.item_id = item.id
  join public.profiles as profile
    on profile.id = item.owner_id
    and profile.state = 'active'
  join public.beta_members as member
    on member.owner_id = item.owner_id
    and member.enabled
    and member.approved_at is not null
  where item.owner_id = p_owner_id
    and item.id = p_item_id
    and item.deleted_at is null;
$$;

create or replace function private.dispatch_classification_jobs()
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

  select secret.decrypted_secret into worker_url
  from vault.decrypted_secrets as secret
  where secret.name = 'link_vault_worker_url'
  limit 1;
  select secret.decrypted_secret into worker_token
  from vault.decrypted_secrets as secret
  where secret.name = 'link_vault_worker_token'
  limit 1;

  if nullif(pg_catalog.btrim(worker_url), '') is null
    or nullif(pg_catalog.btrim(worker_token), '') is null then
    raise exception using errcode = 'P0001', message = 'CLASSIFICATION_WORKER_NOT_CONFIGURED';
  end if;

  select net.http_post(
    url := worker_url,
    body := pg_catalog.jsonb_build_object('limit', 20),
    headers := pg_catalog.jsonb_build_object(
      'Authorization', 'Bearer ' || worker_token,
      'Content-Type', 'application/json'
    ),
    timeout_milliseconds := 5000
  ) into request_id;
  return request_id;
end;
$$;

revoke all on function private.library_item_json(uuid, uuid, boolean)
from public, anon, authenticated, service_role;
revoke all on function private.dispatch_classification_jobs()
from public, anon, authenticated, service_role;
