# CONSIDER-24 step 2 - the implementer's hand-back (2026-11-16): the window made measurable

As delivered, the scratch path replaced by `<worktree>`. One file added, no Java changed. The implementer was stopped
by a usage limit on 2026-11-15 and resumed after the container's restart on 2026-11-16.

- `bench/sql/settle-gap.sql` (97 lines). It takes `-v step=install|report|uninstall`. A missing or unknown step exits 3.
  - install: an unlogged side table `settle_gap(id)` and a statement-level AFTER DELETE trigger on `work` (transition
    table) that copies the deleted rows' ids in the deleting transaction.
  - report: two spans per paired message with count, p50, p95, p99, max, min and mean. `-v skip=N` drops the first N
    deletes by commit time (the warm-up's). It then prints the unpaired counts, the trigger's presence and the rows
    left in `work`.

Method: exact. Both ends are `pg_xact_commit_timestamp(xmin)`. That needed `track_commit_timestamp = on`, a postmaster
setting: `sudo -n -u postgres psql -c "alter system set track_commit_timestamp = on"`, then
`sudo -n setsid pg_ctlcluster 16 main restart`. Install refuses with it off (observed: exit 3). The commit timestamp
is stamped at the commit record, before the WAL flush, the same way at both ends.

The moment to install: there is one, so no flag is needed. It falls between two runs of a main. A rerun's migration
has nothing pending, and `NodeBenchMain.PREPARE` drops only `bench_claim`/`bench_complete`. Its truncate empties
`settle_gap`, so each run starts clean. Observed: trigger present after the run, 21 000 pairs.

Native Postgres: 16.15, the documented cluster (`sudo -n setsid pg_ctlcluster 16 main start`, as `agent/NOTES.md`
says); role `rail8` and database `rail8_bench` created as `agent/NOTES.md` says. Load (a) is `--scenario ack
--concurrency 8`: the ledger's node options (`LedgerMain.bench(o)`) with no kill and the node in-process. Load (b) is
`--concurrency 1`. Both use the main's defaults: 20 000 messages after 1 000 warm-up.

Commands (O = the output directory; CP="bench/target/rail8-bench-0.1.0-SNAPSHOT.jar:bench/target/lib/*"; build as
`bench/run.sh` does, under `flock /tmp/rail8-maven.lock`):
- `sudo -n -u postgres psql -X -q -d rail8_bench -c 'set role rail8' -v step=install -f - < bench/sql/settle-gap.sql`
  Run it as written: the stdin redirect is needed because user postgres cannot read the tree, and `set role` makes
  rail8 own the table. The database must be migrated first; any NodeBenchMain run does it.
- `java -cp "$CP" dev.rail8.bench.NodeBenchMain --scenario ack --concurrency 8 --name ledger-c8-gap --out
  $O/ledger-c8-gap.json --commit <commit> > $O/ledger-c8-gap.log 2>&1` (for (b): `--concurrency 1 --name floor-c1-gap`)
- After each run: the same psql line with `-v step=report -v skip=1000` (skip = warmup - warmupFailed)
  `> $O/<name>.report.txt`. Remove with `-v step=uninstall`.

Dry runs (in `<worktree>/agent/work/CONSIDER-24-s2/`, not committed): all exit 0, 0 failed. In every run
recorded_without_delete, deleted_without_final_row, without_commit_timestamp and work_left were 0.

| run | span | count | p50 | p95 | p99 | max (ms) |
|---|---|---|---|---|---|---|
| ledger-c8-gap | settle | 20 000 | 1.904 | 5.354 | 8.447 | 50.134 |
| ledger-c8-gap | record | 20 000 | 4.067 | 10.204 | 15.059 | 152.867 |
| floor-c1-gap | settle | 20 000 | 0.986 | 1.591 | 2.268 | 699.998 |
| floor-c1-gap | record | 20 000 | 2.225 | 3.398 | 4.775 | 291.953 |
| ledger-c8-gap-2 | settle | 20 000 | 1.936 | 5.124 | 8.313 | 27.944 |
| ledger-c8-gap-2 | record | 20 000 | 4.259 | 9.339 | 13.232 | 35.585 |

- The floor's tail: 8 gaps over 10 ms and 2 over 100 ms, all within one second (one stall episode).

Span "record" (the architect's addition) cost only a change of the report query: the final row's commit minus
(`messages.started_at` + `wall_ns` ns). `Recorder.record` writes both from the node's injected `Clock.systemUTC()`
(`Node.java:224`); the end is taken when the final step hands its result on, after the partner answered. Node and
Postgres share this host's wall clock, so no offset enters. On another host the offset would be in the figure.

Trigger cost, (a) runs in order, ACK throughput msg/s: no trigger 1 003 -> with 936 -> no trigger 964 -> with 972, all
with track_commit_timestamp on. The two configurations overlap (no trigger 964-1 003, with 936-972), so the trigger's
cost is not visible at one or two runs each. A single run with track_commit_timestamp off gave 1 140 msg/s. It was
the first run on the fresh database, so one run cannot separate that setting's cost from noise.

Left as found: the trigger removed and track_commit_timestamp reset to off. The role and database stay.

Not done: `bench/RESULTS.md` untouched, as asked; no upper-bound variant built (not needed); no coverage or PIT check
(no Java module touched).
