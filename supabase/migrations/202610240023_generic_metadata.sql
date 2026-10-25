-- Eligibility is a lexical scheduling filter, not a DNS or SSRF oracle.
-- The metadata worker must resolve and pin public addresses for every hop.
create or replace function private.metadata_url_allowed(p_url text)
returns boolean
language sql
immutable
parallel safe
set search_path = ''
as $$
  with parsed as (
    select pg_catalog.regexp_match(
      p_url,
      '^([Hh][Tt][Tt][Pp][Ss]?)://([^/?#]+)([/?#].*)?$'
    ) as parts
  ), authority as (
    select
      pg_catalog.lower(parts[1] collate "C") as scheme,
      parts[2] as raw_authority,
      pg_catalog.lower(parts[2] collate "C") as authority,
      pg_catalog.lower(pg_catalog.split_part(parts[2], ':', 1) collate "C") as hostname
    from parsed
  )
  select coalesce(
    pg_catalog.char_length(p_url) between 1 and 4096
    and p_url !~ '[[:space:][:cntrl:]]'
    and pg_catalog.strpos(p_url, pg_catalog.chr(92)) = 0
    and raw_authority collate "C" ~ '^[A-Za-z0-9.-]+(:[0-9]+)?$'
    and pg_catalog.char_length(hostname) <= 253
    and hostname ~ '^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?[.])+[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
    -- Numeric and hexadecimal final labels can be parsed as IPv4 literals.
    and hostname !~ '(^|[.])([0-9]+|0x[0-9a-f]*)$'
    and hostname !~ '(^|[.])(localhost|local|localdomain|internal|home|lan|test|invalid|example|onion)$'
    and hostname !~ '(^|[.])home[.]arpa$'
    and authority in (
      hostname,
      hostname || case scheme when 'http' then ':80' when 'https' then ':443' end
    ),
    false
  )
  from authority;
$$;

-- Preserve existing states: newly saved eligible links are already queued by
-- library_create_item, and library_claim_metadata_jobs repairs missing current
-- jobs. Previously unsupported links require an explicit library_retry_metadata
-- request; do not enqueue a network backfill during this migration.
