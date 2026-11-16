-- CONSIDER-24: the settle gap of each completed message - from the commit of its final-step row in `messages` (attempt 1,
-- LedgerMain.FINAL: step_name 'partner', outcome 'ok') to the commit of its row's delete from `work`. A node that dies in
-- between leaves a recorded message queued, and the next attempt runs it again from the ingress.
--
-- Exact: both ends are commit timestamps, pg_xact_commit_timestamp(xmin) - the final row's batch transaction and the delete's
-- (an AFTER DELETE trigger on `work` copies the deleted rows' ids into the unlogged side table `settle_gap` in the deleting
-- transaction, so a side row's xmin is the delete's). Needs track_commit_timestamp = on, a postmaster setting:
-- `alter system set track_commit_timestamp = on` and a cluster restart; install refuses to run without it.
--
-- psql, as the bench role on the bench database, one step per call (-v step=...):
--   install    after the database is migrated (any bench run migrates it) and before the measured run. The trigger stays through
--              the next run: its migration has nothing pending, and NodeBenchMain.PREPARE truncates every table - settle_gap too,
--              so each run starts empty - and drops only its own triggers (bench_claim, bench_complete).
--   report     once the run's main exited (it exits after its settle: the work queue empty), before the next run truncates:
--              count, p50, p95, p99, max (min, mean) of the settle span and of the record span before it, then the unpaired.
--              -v skip=N leaves the first N deletes by commit time out of the figures: the warm-up's, which settles before
--              the window starts (N = warmup - warmupFailed of the run's record). Unpaired counts cover the whole run.
--   uninstall  drops the trigger, its function and the side table.

\set ON_ERROR_STOP on
\if :{?step}
\else
  \set step none
\endif
select :'step' = 'install' as install, :'step' = 'report' as report, :'step' = 'uninstall' as uninstall \gset
\if :install
\elif :report
\elif :uninstall
\else
  do $$ begin raise exception 'settle-gap.sql: pass -v step=install, report or uninstall'; end $$;
\endif

\if :install
do $$ begin
  if current_setting('track_commit_timestamp') <> 'on' then
    raise exception 'track_commit_timestamp is off: the gap is read from commit timestamps (alter system set track_commit_timestamp = on, then restart the cluster)';
  end if;
end $$;
create unlogged table if not exists settle_gap (id text not null);
create or replace function settle_gap_deleted() returns trigger language plpgsql as $f$ begin
  insert into settle_gap (id) select id from gone;
  return null;
end $f$;
drop trigger if exists settle_gap on work;
create trigger settle_gap after delete on work referencing old table as gone for each statement execute function settle_gap_deleted();
\endif

\if :report
\if :{?skip}
\else
  \set skip 0
\endif
-- Two spans per paired message, the same messages in both: settle = the final row's commit to the work row's delete commit
-- (the window above); record = the final step's end on the node to its row's commit. The step's end is the row's own
-- started_at + wall_ns (Recorder: both from the node's Clock.systemUTC(), the end taken when the step handed its result on);
-- the commit timestamps are Postgres' clock - the same host's wall clock for the bench, else the clocks' offset is in it.
create temporary view settle_gap_paired as
with done as (
  select id, min(pg_xact_commit_timestamp(xmin)) as deleted from settle_gap group by id
), recorded as (
  select id, pg_xact_commit_timestamp(xmin) as recorded, started_at + wall_ns * interval '1 microsecond' / 1000 as ended
  from messages where step_name = 'partner' and outcome = 'ok' and attempt = 1
)
select d.id, d.deleted, r.recorded, r.ended, row_number() over (order by d.deleted) as n
from done d join recorded r using (id) where d.deleted is not null and r.recorded is not null;

select span, count(*) as count,
       round(percentile_disc(0.50) within group (order by ms), 3) as p50_ms,
       round(percentile_disc(0.95) within group (order by ms), 3) as p95_ms,
       round(percentile_disc(0.99) within group (order by ms), 3) as p99_ms,
       round(max(ms), 3) as max_ms, round(min(ms), 3) as min_ms, round(avg(ms), 3) as mean_ms
from (select 1 as k, 'settle' as span, extract(epoch from deleted - recorded) * 1000 as ms from settle_gap_paired where n > :skip
      union all
      select 2, 'record', extract(epoch from recorded - ended) * 1000 from settle_gap_paired where n > :skip) s
group by k, span order by k;

\x on
with done as (
  select id, min(pg_xact_commit_timestamp(xmin)) as deleted from settle_gap group by id
), recorded as (
  select id, pg_xact_commit_timestamp(xmin) as recorded from messages where step_name = 'partner' and outcome = 'ok' and attempt = 1
)
select exists (select 1 from pg_trigger where tgname = 'settle_gap' and tgrelid = 'work'::regclass) as trigger_installed,
       (select count(*) from settle_gap_paired) as pairs,
       :skip::int as skipped,
       (select count(*) from recorded r where not exists (select 1 from done d where d.id = r.id)) as recorded_without_delete,
       (select count(*) from done d where not exists (select 1 from recorded r where r.id = d.id)) as deleted_without_final_row,
       (select count(*) from done where deleted is null) + (select count(*) from recorded where recorded is null) as without_commit_timestamp,
       (select count(*) from work) as work_left;
\x off
\endif

\if :uninstall
drop trigger if exists settle_gap on work;
drop function if exists settle_gap_deleted();
drop table if exists settle_gap;
\endif
