create function private.dispatch_enrichment_jobs()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  metadata_due boolean;
  cleanup_due boolean;
  worker_url text;
  worker_token text;
  worker_base text;
  metadata_request bigint;
  cleanup_request bigint;
begin
  select exists (
    select 1 from public.processing_jobs as job
    where job.kind = 'metadata' and (
      (job.state in ('queued', 'retry') and job.next_run_at <= pg_catalog.clock_timestamp())
      or (job.state = 'running' and job.lease_until <= pg_catalog.clock_timestamp())
    )
  ) into metadata_due;
  select exists (
    select 1 from public.assets as asset
    where (asset.state = 'reserved' and asset.reservation_expires_at <= pg_catalog.clock_timestamp())
      or (asset.state = 'deleting'
        and asset.cleanup_next_run_at <= pg_catalog.clock_timestamp()
        and (asset.cleanup_lease_until is null or asset.cleanup_lease_until <= pg_catalog.clock_timestamp()))
  ) into cleanup_due;

  if metadata_due or cleanup_due then
    select decrypted_secret into worker_url from vault.decrypted_secrets
    where name = 'link_vault_worker_url' limit 1;
    select decrypted_secret into worker_token from vault.decrypted_secrets
    where name = 'link_vault_worker_token' limit 1;
    if nullif(pg_catalog.btrim(worker_token), '') is null
      or worker_url is null or worker_url !~ '/v1/internal/classify$' then
      raise exception using errcode = 'P0001', message = 'ENRICHMENT_WORKER_NOT_CONFIGURED';
    end if;
    worker_base := pg_catalog.left(worker_url, pg_catalog.char_length(worker_url) - 8);
    if metadata_due then
      select net.http_post(
        url := worker_base || 'metadata',
        body := '{"limit":10}'::jsonb,
        headers := pg_catalog.jsonb_build_object('Authorization', 'Bearer ' || worker_token, 'Content-Type', 'application/json'),
        timeout_milliseconds := 120000
      ) into metadata_request;
    end if;
    if cleanup_due then
      select net.http_post(
        url := worker_base || 'assets-cleanup',
        body := '{"limit":10}'::jsonb,
        headers := pg_catalog.jsonb_build_object('Authorization', 'Bearer ' || worker_token, 'Content-Type', 'application/json'),
        timeout_milliseconds := 60000
      ) into cleanup_request;
    end if;
  end if;
  return pg_catalog.jsonb_build_object(
    'metadata_request_id', metadata_request,
    'cleanup_request_id', cleanup_request
  );
end;
$$;

revoke all on function private.dispatch_enrichment_jobs()
from public, anon, authenticated, service_role;

-- Reuse the one project scheduler. net.http_post queues each request; it does
-- not wait for the metadata fetch or the physical object deletion to finish.
do $$
declare
  old_job bigint;
begin
  for old_job in select jobid from cron.job
    where jobname in ('link-vault-classify', 'link-vault-processing')
  loop
    perform cron.unschedule(old_job);
  end loop;
end;
$$;
select cron.schedule(
  'link-vault-processing', '* * * * *',
  'select private.dispatch_classification_jobs(); select private.dispatch_enrichment_jobs();'
);
