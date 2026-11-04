-- One canonical display-title rule for library projections and portable exports.
create function private.library_display_title(
  p_user_title text,
  p_fetched_title text,
  p_display_fallback text
)
returns text
language sql
immutable
parallel safe
set search_path = ''
as $$
  select coalesce(
    nullif(pg_catalog.btrim(p_user_title), ''),
    nullif(pg_catalog.btrim(p_fetched_title), ''),
    p_display_fallback
  );
$$;

revoke all on function private.library_display_title(text, text, text)
from public, anon, authenticated, service_role;

-- Preserve the complete 026 projection, changing only its display-title expression.
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
      'display_title', private.library_display_title(
        item.user_title, item.fetched_title, item.display_fallback
      ),
      'source', item.source,
      'note_excerpt', case
        when item.note is null then null
        else pg_catalog.left(item.note, 120)
      end,
      'content_excerpt', (
        select pg_catalog.left(candidate.preview, 120)
        from (
          select source.priority,
            pg_catalog.btrim(pg_catalog.regexp_replace(source.raw_text, '[[:space:]]+', ' ', 'g')) as preview
          from (values
            (1, item.body_text),
            (2, item.description),
            (3, item.shared_text)
          ) as source(priority, raw_text)
        ) as candidate
        where candidate.preview <> ''
          and candidate.preview !~* '^(https?://|www[.])[^[:space:]]+( (https?://|www[.])[^[:space:]]+)*$'
        order by candidate.priority
        limit 1
      ),
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
      'has_attachment', active_asset.id is not null,
      'thumbnail_asset', case
        when active_asset.id is null then null
        else pg_catalog.jsonb_build_object(
          'id', active_asset.id,
          'mime_type', active_asset.mime_type,
          'byte_size', active_asset.actual_bytes,
          'width', active_asset.width,
          'height', active_asset.height
        )
      end,
      'metadata_state', item.metadata_state,
      'ocr_state', coalesce(active_asset.ocr_state, 'not_requested'),
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
        'active_asset', case
          when active_asset.id is null then null
          else pg_catalog.jsonb_build_object(
            'id', active_asset.id,
            'object_path', active_asset.object_path,
            'mime_type', active_asset.mime_type,
            'byte_size', active_asset.actual_bytes,
            'width', active_asset.width,
            'height', active_asset.height,
            'ocr_text', active_asset.ocr_text,
            'ocr_truncated', active_asset.ocr_truncated
          )
        end,
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
  left join public.assets as active_asset
    on active_asset.owner_id = item.owner_id
    and active_asset.item_id = item.id
    and active_asset.state = 'active'
  where item.owner_id = p_owner_id
    and item.id = p_item_id
    and item.deleted_at is null;
$$;

-- STABLE keeps the guards and the complete, unpaginated aggregate on one statement snapshot.
create function public.library_export_items()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  caller_id uuid := auth.uid();
  profile_state text;
  result jsonb;
begin
  if caller_id is null then
    raise exception using errcode = 'P0001', message = 'UNAUTHENTICATED';
  end if;

  select profile.state
  into profile_state
  from public.profiles as profile
  where profile.id = caller_id;

  if found and profile_state = 'deleting' then
    raise exception using errcode = 'P0001', message = 'ACCOUNT_DELETING';
  end if;

  if not found or profile_state <> 'active' or not exists (
    select 1
    from public.beta_members as member
    where member.owner_id = caller_id
      and member.enabled
      and member.approved_at is not null
  ) then
    raise exception using errcode = 'P0001', message = 'BETA_ACCESS_REQUIRED';
  end if;

  select pg_catalog.jsonb_build_object(
    'items', coalesce(
      pg_catalog.jsonb_agg(
        pg_catalog.jsonb_build_object(
          'url', item.original_url,
          'title', private.library_display_title(
            item.user_title, item.fetched_title, item.display_fallback
          ),
          'user_title', item.user_title,
          'note', item.note,
          'source', item.source,
          'categories', coalesce(
            (
              select pg_catalog.jsonb_agg(category.name order by selected.created_at, category.id)
              from public.item_categories as selected
              join public.categories as category
                on category.owner_id = selected.owner_id
                and category.id = selected.category_id
              where selected.owner_id = item.owner_id
                and selected.item_id = item.id
            ),
            '[]'::jsonb
          ),
          'saved_at', item.created_at
        )
        order by item.created_at, item.id
      ),
      '[]'::jsonb
    )
  )
  into result
  from public.items as item
  where item.owner_id = caller_id
    and item.deleted_at is null;

  return result;
end;
$$;

revoke all on function public.library_export_items()
from public, anon, authenticated, service_role;
grant execute on function public.library_export_items() to authenticated;
