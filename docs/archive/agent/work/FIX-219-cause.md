# FIX-219 Step 1 — the cause, shown or not (2026-11-15)

## Documentation (Context7, ClickHouse docs, checked by the architect)
- Entries are buffered before they appear in `system.query_log` (default `flush_interval_milliseconds` 7500).
- `SYSTEM FLUSH LOGS` "flushes buffered log messages to system tables".
- The docs do NOT state whether a query's `QueryFinish` entry is queued before the client has its whole answer.
  The documentation is ambiguous on the order; the throw-away run was to decide.

## Probe design (`agent/work/FIX-219-probe.java.txt`, a bench IT, not committed)
1. One container, image `clickhouse/clickhouse-server:25.8.33.6` as the IT; one table of the harness's shape with 30 rows of 2 KB.
2. Per cell: the harness's `ClickHouseHttp.query(sql, qid + "-read-" + i, {id})` loop, 3 warm-up + 20 reads, same sql
   (`SELECT payload ... WHERE id = {id:String} FORMAT RawBLOB`), same qid scheme `core2-<8 hex>-...-read-<i>`, same client.
3. Then `SYSTEM FLUSH LOGS` once and the harness's count (`type = 'QueryFinish'`, index >= warm-up): expected 20.
4. If short: flush again, count again, print both. 200 cells (it did not stop at a first short; none came).
5. Run: `mvn -Pit verify -pl :rail8-bench -am -Dit.test=ClickHouseFlushProbeIT`, log `agent/work/FIX-219-probe.log`,
   output `agent/work/FIX-219-probe.out`.

## Seen
`SUMMARY cells=200 shortFirst=0 secondWhole=0 wallMs=17440 load=2.85 2.37 1.99 6/935 17823`
- No short-count line. 200 cells x 23 reads = 4600 reads, every first count was 20.
- 200 cells took 17.4 s (the whole Maven call 1 m 50 s). The machine: 4 CPUs, load average about 2-3 (another agent's
  Maven builds run on it now and then), 16 GB RAM, mostly free.

## Conclusion
The suspect (a `QueryFinish` row queued after the client has its answer, so a flush right after the last read runs
before it) is NOT shown: no short count in 200 cells on a moderately loaded 4-CPU machine, and the documentation does
not state the order. The original 19-of-20 was one cell in a whole-reactor run (heavily loaded: whole reactor, JVMs,
other containers) and could not be provoked here. This probe is lighter than the harness cell (no 12 MiB inserts, no OPTIMIZE,
no sampler thread, small payloads, a single table): a race that needs a busy server or a slow client is not excluded.
The packet's fix (a bounded re-flush and re-count) would be a guard against a cause that is not shown.

## Probe 2 — the real cell, without and with load (worktree of e69abdd3; source `FIX-219-probe2.java.txt`, logs `FIX-219-probe2.log`/`probe2b.log`, output `FIX-219-probe2.out`)
Design: `ClickHouseCompressionMain.run` itself, in a loop, one cell per call (`1024 KiB x 12 similar xml zstd1`, `--runs 1 --reads 20 --warmup-reads 3`,
`--batch-bytes 3145728` as the IT): inserts, OPTIMIZE, sampler, warm-up, 20 reads, the harness's flush and count; `readsLogged` read from its JSON.
Nothing copied. Short = readsLogged != 20: then flush again, count again (since run start), dump the run's read rows and flush statements.
The second look and dump come after `run` returned (after the harness's other queries and its DROP), not at once inside it. Same container image as the IT.
Phase B: 4 busy platform threads in the probe JVM (= CPUs) + `docker exec -d ... 4 x while :; do :; done` in the ClickHouse container (4-CPU box, load 11 at the end).
Phase A (no added load), 600 s: `SUMMARY phase A (no added load) cells=961 shortFirst=0 minReadsLogged=20 budgetSeconds=600 load=1.55 1.68 1.73`
Phase B (load both sides), stopped at the first short count: `SUMMARY phase B (load both sides) cells=44 shortFirst=1 minReadsLogged=19 budgetSeconds=600 load=11.34 7.10 3.95` (242 s)
Short cell 44: `readsLogged(first look)=19`, second look after another flush = 20; no mismatch, 20 timed reads, exit 0.
Dump (after the second flush): all 23 reads (0..22) have exactly one QueryStart and one QueryFinish, no exception row, none sent twice, none missing
(the "starts != 1 / finishes != 1 / exceptions != 0" query returned no row). Last read: read-22 QueryFinish event_time 13:21:25.411866 (duration 25 ms).
The harness's `SYSTEM FLUSH LOGS` QueryStart 13:21:25.412994 = 1.1 ms after it, QueryFinish 25.604697 (191 ms, the box was loaded). Every other read finished >= 21 ms before the flush started.
Reading: this is the suspect, in its "row queued late" form. No read failed, was repeated or never started; the 20th row existed and was found by the next flush. The missing row was not
identified by index (the dump is after the second flush); read-22 is the one that finished 1.1 ms before the flush began, and the flush (191 ms long) still did not carry it, so a QueryFinish
is timestamped (`event_time`) before it is queued, and the flush takes the queue as it is at that moment. A flush in the harness right after the last read can therefore run before the last row is queued; it needs load (1 in 44 cells under load, 0 in 961 without).
So the bounded re-flush and re-count of the packet is justified by an observed case: 1 short first count in 44 loaded cells, whole at the second look.
