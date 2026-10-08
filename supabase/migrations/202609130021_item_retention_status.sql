create or replace function public.library_operation_status()
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select pg_catalog.jsonb_build_object(
    'checked_at', pg_catalog.now(),
    'account_deletions_pending', (
      select count(*) from public.account_deletion_jobs where state <> 'complete'
    ),
    'account_deletions_retry', (
      select count(*) from public.account_deletion_jobs where state = 'retry'
    ),
    'account_deletions_overdue', (
      select count(*) from public.account_deletion_jobs
      where state <> 'complete' and requested_at < pg_catalog.now() - interval '72 hours'
    ),
    'item_deletions_pending', (
      select count(*) from public.items where deleted_at is not null
    ),
    'item_deletions_overdue', (
      select count(*) from public.items
      where deleted_at < pg_catalog.now() - interval '30 days'
    ),
    'asset_cleanups_pending', (
      select count(*) from public.assets where state = 'deleting'
        or (state = 'reserved' and reservation_expires_at <= pg_catalog.now())
    ),
    'asset_cleanups_overdue', (
      select count(*) from public.assets
      where (state = 'deleting' and deleted_at < pg_catalog.now() - interval '72 hours')
        or (state = 'reserved' and reservation_expires_at < pg_catalog.now() - interval '72 hours')
    ),
    'asset_cleanups_retry', (
      select count(*) from public.assets
      where state = 'deleting' and cleanup_error_code is not null
    ),
    'expired_account_leases', (
      select count(*) from public.account_deletion_jobs
      where state = 'running' and lease_until <= pg_catalog.now()
    ),
    'usage_mismatches', (
      select count(*) from public.library_usage as usage
      where usage.active_item_count <> (
        select count(*) from public.items as item
        where item.owner_id = usage.owner_id and item.deleted_at is null
      ) or usage.used_image_bytes <> (
        select coalesce(sum(asset.actual_bytes), 0) from public.assets as asset
        where asset.owner_id = usage.owner_id
      ) or usage.reserved_image_bytes <> (
        select coalesce(sum(asset.reserved_bytes), 0) from public.assets as asset
        where asset.owner_id = usage.owner_id and asset.actual_bytes is null
      )
    ),
    'untracked_storage_objects', (
      select count(*) from storage.objects as object
      where object.bucket_id = 'library-images' and not exists (
        select 1 from public.assets as asset where asset.object_path = object.name
      )
    ),
    'ledger_export_verified_at', (
      select max(acknowledged_at) from private.deletion_ledger_exports
    ),
    'maintenance_scheduled', exists (
      select 1 from cron.job where jobname = 'link-vault-maintenance' and active
        and schedule = '*/5 * * * *'
        and command = 'select private.dispatch_maintenance_jobs();'
    ),
    'maintenance_last_dispatch_succeeded_at', (
      select max(run.end_time) from cron.job_run_details as run
      join cron.job as job on job.jobid = run.jobid
      where job.jobname = 'link-vault-maintenance' and run.status = 'succeeded'
    )
  );
$$;

revoke all on function public.library_operation_status()
from public, anon, authenticated;
grant execute on function public.library_operation_status() to service_role;
