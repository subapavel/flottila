# CORE-15b — the open-loop record measures the database (class B; F-27 I1)

Architect 2026-11-05. Base: 0e34f4e. Owner 2026-11-05: "začni měřením CORE-15b" — measure before any F-27 change.
Code: flottila-bench NodeBenchMain (open loop :674-713, settle/landed :820-911, Harness.probe), OpenLoop (backlog samples per
second), CpuSource; bench/run.sh open scenario; bench/RESULTS.md open-loop method note.

## Scope
1. **Per-second database series** in the `open` record (`db` object), sampled with the backlog sampler (window start, each second,
   window end), each as deltas per sample: `xactCommit` (pg_stat_database), `walSync`, `walSyncTimeMs`, `walFpi`, `walBytes`
   (pg_stat_wal; wal_sync_time needs track_wal_io_timing — when off, the field is null with a note), `workDeadTup`,
   `workLiveTup`, `workHotUpd`, `workUpd` (pg_stat_user_tables for `work`; absolute for tup counts, delta for upd), the
   `work` last_autovacuum / last_autoanalyze times when they change, and per-statement calls / total_exec_time_ms /
   shared_blks_hit+read deltas from pg_stat_statements for the node's statements (top 12 by time over the window; when the
   extension is absent the field is null with a note — never 0). Sampling uses its own connection, never the node's pool.
2. **Device and Postgres CPU**: `--io-device <name>` reads /proc/diskstats per second (reads, writes, write ms, io ms → util %)
   for the device holding the data directory; Postgres CPU = the sum over every process of the postgres cluster (all backends,
   from /proc/<pid>/stat of the postmaster's children, or the cgroup when given), per second. Unreadable → null + note.
3. **Settle probe fix (F-27)**: check `work` empty first (cheap), then count recorded messages once, pause 1 s between checks;
   no `count(distinct id)` loop every 5 ms.
4. **run.sh**: BENCH_IO_DEVICE, BENCH_PG_STATEMENTS (create extension when allowed); summary prints the window totals
   (commits/s, wal syncs/s, top statement by time).
5. **RESULTS method note**: each new field, its source and when it is null.

## Red tests (test-author)
Unit (fake samplers, no sleeps): each series appears once per backlog sample with deltas; a missing extension / timing off /
unreadable device gives null + note, never 0; the top-12 statement ranking by time; the settle probe issues the work check first
and the message count only when work is empty, one probe per pause (count the probe calls); Postgres CPU sums all cluster
processes (a fake /proc with a postmaster and 3 children). IT (Testcontainers Postgres with pg_stat_statements preloaded): an
ack open loop at a low rate records non-null xactCommit/walSync deltas whose sum ≥ the accepted count / group size, and the
CLAIM and COMPLETE statements among the top statements. BenchRunScriptTest: the new variables. Existing bench tests green.
Red log agent/work/CORE-15b-red.log.

## Constraints
Bench module, run.sh, RESULTS only; no node change; the sampler adds no load to the node's pool; finite bounds; unknown = null.

## Checks
`mvn -B -ntp -q -pl flottila-bench -am verify -Pit`, reactor `verify -Pit`, coverage; then the verifier reruns the r2000 cell
(and r1000) with the series, results to RESULTS as "CORE-15b — r1000/r2000 with database series (preview)".

## Red approved (architect 2026-11-05)
Red: compile red; shim 21/24 unit+script, IT 2/3 (agent/work/CORE-15b-red.log). Tests: OpenLoopSamplerTest, DbSamplerTest,
DiskSamplerTest, CpuSourceClusterTest, NodeBenchMainSettleProbeTest, NodeBenchMainOpenIoOptionsTest, BenchRunScriptOpenDbTest,
NodeBenchOpenDbSeriesIT. API as the test-author named it (OpenLoop.Sampler, DbSampler, DiskSampler, CpuSource.of(String, Path),
CpuSource.sampler, NodeBenchMain.landed / SETTLE_PAUSE, Open.ioDevice).
Decisions: (1) the window-start sample is the baseline: each delta series' first entry is 0; (2) statements as window deltas,
hit + read summed (accepted); (3) Postgres CPU also adds the postmaster's cutime + cstime delta (exited backends) — implementer
pins it; skipping a child that exits mid-read accepted; (4) a refused extension keeps the run, prints a message; (5) the sampler's
own pg_stat_* reads excluded; (6) record keys `pgCpu` and `io` pinned by the implementer in a Harness-level case.
Not testable, accepted: the sampler's own connection (reviewer reads it), the real 1 s pause, Harness wiring of landed().
Architect (implementer handback): accepted — CpuSource.of(String) stays one process (its JDK-figure test); the cluster counts
children's c-times too; the 1 s pause for the open settle only (closed-loop keeps 5 ms, work-first order); xactCommit includes the
sampler's 1 commit per sample (noted). PG 18 wal_sync absence → null with the error (FIX later if needed).
