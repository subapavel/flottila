# Bench results — Phase 2 (P2-08), Phase 1 (gate P1-10, P1-01, P1-02) and Phase 0 (gate, spikes, baselines)

## Harness: open loop (CORE-15) — method note, no numbers

`NodeBenchMain --rate <msg/s>` (1..100 000) runs scenario `ack` or `sync` open loop; `--scenario echo --rate ..` runs it straight
at the raw echo (no node, no Postgres): the harness' own ceiling. `bench/run.sh` scenario `open` (BENCH_RATE 5000, BENCH_RAMP,
BENCH_WINDOW, BENCH_OPEN_SCENARIO ack|sync|echo, BENCH_PG_CPU). Without `--rate` the closed loop is unchanged.

- **Schedule.** Request i is due at an instant fixed in advance: a linear ramp from 0 to the rate over `--ramp` (PT1M; i is due at
  sqrt(2·ramp·i/rate)), then the constant rate for `--window` (PT10M). A request is sent when due whatever the answers to earlier
  ones; sends run on a Vert.x timer rounded up to whole milliseconds (a timer that fires late sends everything due by then).
  Nothing is scheduled at or after the window's end. The closed-loop `--warmup` runs before the ramp.
- **Bound.** At most `--max-outstanding` (rate × 2 s) requests in flight; one due while the bound is full is never sent and counts
  `clientSaturated`. Client connections are `--concurrency`; a request beyond them waits in the client's queue, on the clock.
  A request without a connection or an answer within 30 s counts as a timeout.
- **Window figures** count only the requests scheduled inside the window: `scheduled` (rate × window), `sent`, `achievedRate`
  (window requests whose actual send fell inside the window, per window second), `accepted` (202 for ack, 200 for sync and echo),
  `refused503` (the flow's admission), `refused` (any other status, by code), `timeouts`, `errors` (`firstFailure` the first).
- **Latency** p50/p99/p99.9/max of the accepted answers, each from the request's **scheduled** send time to its answer: a late
  send counts its delay (no coordinated omission). Null when nothing was accepted.
- **Backlog** = accepted − partner receipts (the echo counts each request it answers; ramp included in both), sampled at the
  window's start, each second, and its end (`backlog`); `backlogGrowth` = end − start, `backlogGrowthPercent` over the window's
  `accepted`. `acceptedTotal` is ramp + window. `receipts` is re-read when the settle returns: once the node settled
  (everything accepted landed) or at its deadline (FIX-124).
  In the echo self-test a receipt is counted before its answer reaches the client, so a sample can be slightly negative.
- **CPU over the window** (`cpuMs`, read at the window's start and end): `bench` the bench JVM (client, node and echo together);
  `node` the bench flow's step CPU as the node's sampler attributed it (`--cpu-sampling` on; the sampler reads every 5 s and keeps
  2 s of lateness, so a short window reads low or 0 — meaningful for minutes-long windows only); `postgres` from
  `--pg-cpu-source` (a cgroup v2 directory's `cpu.stat` `usage_usec`, or a pid's `/proc/<pid>/stat` utime + stime at USER_HZ 100).
  A figure that cannot be read is JSON null with its reason in `cpuNotes`, never 0.
- **valid** is false, with `invalidReasons`, when any request was `clientSaturated` or `achievedRate` is more than 1 % off the
  scheduled rate (`scheduled` / window seconds, the offered rate whenever rate × window is whole). An invalid run measured the
  client, not the node. The run exits 3 on window errors or timeouts; refusals and invalidity are recorded, not failed.
- **Settle** (FIX-124): after the window the run waits up to PT10M (`settleDeadline`) until `settleTarget` messages (the warm-up requests that succeeded +
  `acceptedTotal`) are recorded and the work queue is empty. `settled` says whether that landed; `messages` (distinct recorded
  messages) and `work` (work-queue items) are read when the wait returns, landed or at the deadline. The record is written
  either way; a run with `settled: false` exits 4, which takes precedence over 3. The echo self-test does not settle (no
  `settled`).
- **recordingLevel** (record top level, FIX-124): `--recording-level` — `all` (default) records every step, `none` deploys the
  flow with `recording.level: off` (the CORE-GATE cell); `recording` stays the payload setting. Sync refuses `none`.
- Latency samples are kept exactly (8 bytes per window request: 24 MB at 5 000/s × PT10M).

## CORE-2 — ClickHouse payload compression — cloud VM, 4 vCPU

Date: 2026-11-04. Commit: the CORE-2 working tree on d2e2845 (records labelled `d2e2845+dirty`). Machine: Intel Xeon @ 2.80 GHz
× 4 vCPU, 15.7 GiB, kernel 6.18.44-fc-v230, JDK 25.0.5, Docker 29.9.2. ClickHouse `clickhouse/clickhouse-server:25.8.33.6` (the
compose pin) started by `BENCH_SCENARIOS=clickhouse bench/run.sh` with `docker run`, default server settings (only
`CLICKHOUSE_PASSWORD` set), data inside the container on the VM disk, removed with the container after the run. Observed server
settings: max_server_memory_usage 14.1 GiB (0.9 × RAM), index_granularity_bytes 10 MiB, max_compress_block_size 1 MiB.
Harness `ClickHouseCompressionMain` (JDK HttpClient, ClickHouse HTTP interface): per cell a fresh table
`(flow LowCardinality(String), created_at DateTime64(3), id String, payload String CODEC(ZSTD(n)))`, MergeTree `ORDER BY (flow,
created_at, id)`; payloads generated (`InvoiceGenerator`, seed 20261104: the demo invoice shape plus dates, partner, line text,
amounts and total; lines appended to the target size; `mixed` = 200 partners with own catalogs and 8 flows, `similar` = one
partner's template and one flow; JSON = the same content, compact); inserted in RowBinary batches of at most 64 MiB (100 KiB: 4
inserts, 1 MiB: 8, 10 MiB: 17, 50 MiB: one payload per insert, 20), then `OPTIMIZE TABLE … FINAL`; then 50 warm-up and 200 timed
random reads `SELECT payload … WHERE id = {id} FORMAT RawBLOB` (client-side to the last byte; every read checked against the
inserted length and CRC32C: 0 mismatches). Counts: 100 KiB × 2 000, 1 MiB × 500, 10 MiB × 100, 50 MiB × 20 (200 MiB to 1 GiB raw
per cell; 33.9 GB raw per pass of 48 cells). Every run took < 5 min (max 122 s), so every cell ran 3 times; median [min–max].
Raw JSON agent/work/CORE-2-run.json (merged), parts and logs agent/work/CORE-2-run-part{1,2,3}.json, CORE-2-run.log,
CORE-2-resume.log (not committed). Other load on the VM during the first 43 cells was not recorded.

**Harness edits after the run (review, for the IT `ClickHouseCompressionMainIT`):** arguments checked up front (size against the
batch, counts, zstd level 1..22, runs); the batching moved into an inner class (same rule: a row that would overflow 64 MiB
starts the next insert); the read check compares one value (length and CRC32C together); the memory sampler runs until it is
interrupted and reports `serverMemorySamples`; two dead empty/`\N` guards in the TSV parsing removed; the merge fan-in
(`length(merged_from)` in `system.part_log`: `mergeFanInMax`, `mergePeakFanIn`, `finalMergeFanIn`) added. **Recheck with the
edited harness** (2026-11-04, the 2.10 GHz VM; `BENCH_CH_ARGS="--sizes 100,51200 --counts 2000,20 --mixes similar --formats xml
--levels 1 --runs 1"`, agent/work/CORE-2-recheck.log/.json): 100 KiB similar xml zstd 1 ratio 8.53 and merge peak 123 MiB (table:
8.53 / 123), one merge of 4 parts; 50 MiB similar xml zstd 1 ratio 8.62 and merge peak 582 MiB (table: 8.62 / 582), 4 merges, the
peak one (the final OPTIMIZE merge) of 5 parts, the largest of 6. Ratio and merge peak reproduced; times not compared (other CPU).

**Five cells ran after a restart, on another VM (marked †).** The whole VM restarted during 50 MiB similar xml zstd 3 run 3
(runs 1 and 2 were logged, the cell's record was not written); the five missing cells (50 MiB similar: xml zstd 3, 9; json zstd 1,
3, 9) were re-run with the same seed after the restart, on a VM that had changed: kernel 6.18.44-fc-v230 → **v233**, Intel Xeon
@ 2.80 GHz → **2.10 GHz**, while another session's Maven build ran (load 1.8–5.1). As a
control, the completed cell 50 MiB similar xml zstd 1 was run again there: ratio 8.62 (same), merge peak 582 MiB (same), insert
4.1 s (4.0 s before), OPTIMIZE 4.4 s (5.5 s), read p50 137.6 ms (199.0 ms), p99 198.2 ms (350.2 ms). The † rows' ratios and
memory are comparable with the others; their times are not (reads ran 31 % faster in the control, zstd 9 inserts 37–44 % faster
than in the other 50 MiB zstd 9 rows).

Columns: ratio (part) = `system.parts_columns` data_uncompressed_bytes / data_compressed_bytes of the one active part after
OPTIMIZE (the whole part; the payload column alone, column_data_*, differs by ≤ 0.04); insert s = sum of the inserts'
query_duration_ms (`system.query_log`); insert peak = max memory_usage of one insert; OPTIMIZE s = its query_duration_ms;
merge peak = max peak_memory_usage over all merges of the table (`system.part_log`; the OPTIMIZE query's own memory_usage is
~1.2 MiB, the merge is not accounted to it); server peak = `system.metrics` MemoryTracking sampled every 100 ms during inserts
and OPTIMIZE (it includes caches and grows over the session: idle 67 MiB at the start, so it is an upper bound, not the cell's
cost); read bytes = average read_bytes per timed read on the server; failed = insert, merge or OPTIMIZE errors, query
exceptions and read mismatches over all runs.

| size × count | mix | format | zstd | runs | ratio (part) | ratio (payload col) | insert s (server, sum) | insert peak MiB | OPTIMIZE FINAL s | merge peak MiB (part_log) | server peak MiB | read p50 ms | read p99 ms | read server p50 ms | read bytes/read MiB | failed |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 KiB × 2000 | mixed | xml | 1 | 3 | 7.09 [7.08–7.09] | 7.10 [7.10–7.11] | 1.1 [1.0–1.5] | 141 | 1.0 [0.9–1.2] | 145 | 229 [210–236] | 16.0 [15.2–16.9] | 43.3 [42.1–45.8] | 13 [11–14] | 6.4 [6.2–6.5] | 0 |
| 100 KiB × 2000 | mixed | xml | 3 | 3 | 6.89 [6.88–6.89] | 6.91 [6.90–6.91] | 1.1 [1.1–1.3] | 141 | 1.1 [1.1–1.2] | 145 | 247 [238–250] | 16.6 [16.2–17.1] | 35.0 [32.0–35.2] | 14 | 6.4 [6.2–6.5] | 0 |
| 100 KiB × 2000 | mixed | xml | 9 | 3 | 8.15 [8.14–8.15] | 8.17 [8.16–8.17] | 5.1 [4.5–5.1] | 155 | 5.3 [4.7–5.3] | 145 | 291 [284–293] | 14.7 [14.5–14.8] | 38.4 [31.4–43.0] | 13 | 6.4 [6.2–6.5] | 0 |
| 100 KiB × 2000 | mixed | json | 1 | 3 | 5.59 [5.59–5.60] | 5.60 [5.60–5.61] | 1.1 [1.0–1.2] | 141 | 1.2 [1.1–1.3] | 145 | 298 [291–300] | 16.2 [16.1–16.3] | 33.0 [31.4–43.0] | 14 | 6.3 [6.2–6.5] | 0 |
| 100 KiB × 2000 | mixed | json | 3 | 3 | 5.39 [5.38–5.39] | 5.40 [5.39–5.40] | 1.4 [1.3–1.5] | 141 | 1.6 [1.6–1.7] | 145 | 317 [307–325] | 16.9 [16.6–17.7] | 39.2 [35.6–39.5] | 15 [15–16] | 6.3 [6.2–6.5] | 0 |
| 100 KiB × 2000 | mixed | json | 9 | 3 | 6.28 [6.27–6.28] | 6.29 | 5.4 [5.3–6.5] | 155 | 6.2 [5.5–6.8] | 145 | 376 [352–387] | 17.2 [15.9–17.8] | 35.4 [34.0–45.5] | 15 [14–16] | 6.3 [6.2–6.5] | 0 |
| 100 KiB × 2000 | similar | xml | 1 | 3 | 8.53 | 8.56 | 1.0 [0.8–1.3] | 98 | 1.4 [0.8–1.6] | 123 | 356 [352–358] | 14.1 [13.6–15.8] | 34.2 [27.3–61.0] | 12 [12–13] | 6.7 [6.3–6.8] | 0 |
| 100 KiB × 2000 | similar | xml | 3 | 3 | 8.52 | 8.55 | 0.9 [0.9–1.6] | 98 | 0.9 [0.8–0.9] | 123 | 359 [359–366] | 15.2 [12.4–16.0] | 31.4 [28.5–37.8] | 13 [10–14] | 6.7 [6.3–6.8] | 0 |
| 100 KiB × 2000 | similar | xml | 9 | 3 | 9.94 | 9.98 | 4.6 [4.1–4.7] | 98 | 4.4 [4.1–5.4] | 123 | 390 [384–760] | 12.6 [11.8–13.9] | 32.4 [24.5–34.3] | 11 [10–12] | 6.7 [6.3–6.8] | 0 |
| 100 KiB × 2000 | similar | json | 1 | 3 | 6.53 | 6.55 | 1.0 [0.9–1.0] | 98 | 0.9 [0.9–1.2] | 123 | 403 [397–416] | 16.6 [14.5–16.6] | 42.8 [40.2–43.1] | 15 [12–15] | 6.7 [6.3–6.8] | 0 |
| 100 KiB × 2000 | similar | json | 3 | 3 | 6.51 | 6.52 | 1.1 [1.0–1.1] | 98 | 1.1 | 123 | 415 [411–430] | 16.4 [16.1–16.5] | 36.1 [33.2–50.5] | 14 [14–15] | 6.7 [6.3–6.8] | 0 |
| 100 KiB × 2000 | similar | json | 9 | 3 | 7.57 | 7.59 | 5.0 [5.0–5.3] | 98 | 4.8 [4.6–4.9] | 123 | 526 [510–817] | 14.7 [13.5–16.0] | 37.8 [35.3–43.5] | 13 [12–14] | 6.7 [6.3–6.8] | 0 |
| 1 MiB × 500 | mixed | xml | 1 | 3 | 8.32 | 8.32 | 2.9 [2.8–3.0] | 141 | 3.5 [3.2–3.8] | 200 | 706 [685–843] | 18.9 [18.2–19.6] | 43.4 [41.4–45.7] | 16 [15–17] | 6.5 [6.4–6.8] | 0 |
| 1 MiB × 500 | mixed | xml | 3 | 3 | 8.26 | 8.26 | 3.1 [2.8–4.0] | 141 | 4.7 [4.7–4.8] | 200 | 712 [700–727] | 19.1 [19.1–20.1] | 43.5 [41.2–46.8] | 16 [16–17] | 6.5 [6.4–6.8] | 0 |
| 1 MiB × 500 | mixed | xml | 9 | 3 | 9.67 [9.66–9.67] | 9.67 | 12.5 [12.4–13.7] | 155 | 17.4 [17.3–17.9] | 200 | 807 [800–829] | 18.7 [17.4–19.3] | 40.9 [39.3–47.4] | 16 [14–16] | 6.5 [6.4–6.8] | 0 |
| 1 MiB × 500 | mixed | json | 1 | 3 | 6.42 | 6.42 | 2.6 [2.5–2.8] | 141 | 4.5 [3.9–4.6] | 200 | 776 [775–788] | 20.2 [20.0–20.4] | 40.0 [38.7–44.2] | 17 | 6.5 [6.4–6.8] | 0 |
| 1 MiB × 500 | mixed | json | 3 | 3 | 6.35 | 6.35 | 3.2 [3.0–3.4] | 141 | 5.0 [4.9–5.1] | 200 | 767 [759–821] | 21.0 [19.1–21.5] | 54.2 [36.6–54.2] | 18 [16–18] | 6.5 [6.4–6.8] | 0 |
| 1 MiB × 500 | mixed | json | 9 | 3 | 7.40 | 7.40 | 14.3 [13.8–14.5] | 155 | 22.4 [21.9–22.8] | 200 | 802 [774–837] | 19.5 [19.2–19.6] | 39.1 [38.1–43.0] | 16 | 6.5 [6.4–6.8] | 0 |
| 1 MiB × 500 | similar | xml | 1 | 3 | 8.52 | 8.52 | 2.0 [1.9–2.2] | 98 | 2.8 [2.6–3.0] | 178 | 683 [675–692] | 20.2 [19.3–20.9] | 44.2 [37.7–51.9] | 17 [16–17] | 7.4 [6.9–7.6] | 0 |
| 1 MiB × 500 | similar | xml | 3 | 3 | 8.44 | 8.45 | 2.6 [2.5–2.6] | 98 | 3.1 [3.0–3.2] | 178 | 660 [657–679] | 21.3 [20.2–22.1] | 46.1 [45.1–50.8] | 18 [17–18] | 7.4 [6.9–7.6] | 0 |
| 1 MiB × 500 | similar | xml | 9 | 3 | 9.85 | 9.85 | 12.0 [11.4–12.2] | 98 | 17.0 [16.0–18.2] | 178 | 692 [687–693] | 19.0 [18.4–19.3] | 41.3 [35.8–45.0] | 16 [15–16] | 7.4 [6.9–7.6] | 0 |
| 1 MiB × 500 | similar | json | 1 | 3 | 6.55 | 6.55 | 2.5 [2.3–2.5] | 98 | 3.6 [3.5–3.7] | 178 | 657 [643–669] | 20.9 [20.2–22.0] | 48.5 [39.1–48.5] | 18 [17–19] | 7.4 [6.9–7.6] | 0 |
| 1 MiB × 500 | similar | json | 3 | 3 | 6.47 | 6.47 | 2.7 [2.7–2.9] | 98 | 4.7 [4.2–4.8] | 178 | 662 [655–667] | 21.1 [20.1–21.4] | 40.5 [38.5–41.4] | 18 [17–18] | 7.4 [6.9–7.6] | 0 |
| 1 MiB × 500 | similar | json | 9 | 3 | 7.52 | 7.52 | 13.1 [12.7–13.9] | 98 | 21.8 [19.8–22.0] | 178 | 690 [687–701] | 21.0 [20.2–22.5] | 49.8 [39.5–60.2] | 18 [17–19] | 7.4 [6.9–7.6] | 0 |
| 10 MiB × 100 | mixed | xml | 1 | 3 | 8.43 | 8.43 | 4.9 [4.8–5.2] | 141 | 4.4 [4.3–4.4] | 199 | 732 [722–739] | 49.3 [48.2–49.6] | 68.2 [64.9–71.9] | 29 [28–29] | 10.0 | 0 |
| 10 MiB × 100 | mixed | xml | 3 | 3 | 8.38 | 8.38 | 5.8 [5.5–6.5] | 141 | 5.2 [5.2–5.4] | 199 | 726 [714–1091] | 51.0 [50.2–51.7] | 68.9 [67.6–81.3] | 30 | 10.0 | 0 |
| 10 MiB × 100 | mixed | xml | 9 | 3 | 9.82 | 9.82 | 24.3 [24.0–25.3] | 155 | 26.0 [25.8–27.6] | 199 | 748 [736–750] | 48.6 [47.0–49.4] | 73.3 [62.0–80.3] | 29 [28–30] | 10.0 | 0 |
| 10 MiB × 100 | mixed | json | 1 | 3 | 6.52 | 6.52 | 5.9 [5.7–6.0] | 141 | 5.6 [5.5–5.9] | 200 | 707 [703–772] | 48.2 [47.2–48.3] | 71.2 [71.2–71.3] | 30 [29–30] | 10.0 | 0 |
| 10 MiB × 100 | mixed | json | 3 | 3 | 6.44 | 6.44 | 7.0 [6.4–7.1] | 141 | 6.7 [6.1–7.0] | 200 | 735 [734–765] | 49.0 [48.1–49.0] | 69.2 [66.1–69.4] | 31 [29–32] | 10.0 | 0 |
| 10 MiB × 100 | mixed | json | 9 | 3 | 7.53 | 7.53 | 28.7 [26.3–30.3] | 155 | 30.6 [27.8–33.3] | 200 | 724 [723–796] | 49.8 [47.2–50.1] | 67.5 [66.8–73.9] | 31 [29–32] | 10.0 | 0 |
| 10 MiB × 100 | similar | xml | 1 | 3 | 8.57 | 8.57 | 4.7 [4.2–4.8] | 98 | 5.0 [4.3–5.1] | 199 | 622 [615–637] | 51.0 [47.1–52.1] | 74.2 [69.4–75.5] | 31 [29–32] | 10.0 | 0 |
| 10 MiB × 100 | similar | xml | 3 | 3 | 8.47 | 8.47 | 5.2 [4.8–5.4] | 98 | 5.1 [5.1–5.5] | 199 | 617 [606–638] | 48.0 [47.5–49.1] | 68.2 [65.2–75.2] | 30 [29–30] | 10.0 | 0 |
| 10 MiB × 100 | similar | xml | 9 | 3 | 9.92 | 9.92 | 25.4 [25.0–26.2] | 98 | 23.2 [22.9–25.1] | 199 | 668 [654–676] | 49.3 [44.5–50.2] | 80.2 [57.4–96.1] | 31 [28–32] | 10.0 | 0 |
| 10 MiB × 100 | similar | json | 1 | 3 | 6.62 | 6.62 | 4.8 [4.7–4.9] | 98 | 5.3 [5.2–5.3] | 200 | 647 [638–652] | 47.9 [46.4–49.3] | 69.0 [68.0–72.3] | 29 [29–33] | 10.0 | 0 |
| 10 MiB × 100 | similar | json | 3 | 3 | 6.52 | 6.52 | 5.6 [5.5–5.7] | 98 | 6.1 [6.0–6.4] | 200 | 639 [628–641] | 47.9 [47.6–48.5] | 65.3 [62.6–71.4] | 32 [31–32] | 10.0 | 0 |
| 10 MiB × 100 | similar | json | 9 | 3 | 7.60 | 7.60 | 27.0 [25.4–28.0] | 98 | 27.2 [26.5–29.6] | 200 | 955 [853–1037] | 49.7 [46.5–50.1] | 76.0 [70.9–77.2] | 33 [30–33] | 10.0 | 0 |
| 50 MiB × 20 | mixed | xml | 1 | 3 | 8.49 | 8.49 | 5.6 [4.4–5.9] | 79 | 6.7 [4.5–6.8] | 582 [580–582] | 940 [913–1115] | 222.0 [218.5–226.9] | 333.9 [330.8–340.6] | 161 [159–165] | 50.0 | 0 |
| 50 MiB × 20 | mixed | xml | 3 | 3 | 8.42 | 8.42 | 5.7 [4.5–5.8] | 79 | 6.0 [4.9–6.3] | 582 [580–582] | 891 [877–912] | 221.4 [212.7–221.7] | 349.4 [296.9–550.9] | 159 [151–166] | 50.0 | 0 |
| 50 MiB × 20 | mixed | xml | 9 | 3 | 9.87 | 9.87 | 24.8 [24.7–26.3] | 91 | 27.2 [26.4–29.8] | 582 [580–582] | 1037 [891–1190] | 219.4 [219.1–227.7] | 529.0 [356.0–600.6] | 156 [155–161] | 50.0 | 0 |
| 50 MiB × 20 | mixed | json | 1 | 3 | 6.56 | 6.56 | 5.2 [4.4–5.4] | 79 | 5.9 [5.6–7.3] | 582 [580–582] | 897 [888–900] | 216.2 [206.6–216.8] | 307.1 [285.1–333.0] | 160 [154–161] | 50.0 | 0 |
| 50 MiB × 20 | mixed | json | 3 | 3 | 6.50 | 6.50 | 5.2 [5.2–5.4] | 79 | 7.0 [6.8–7.6] | 582 [580–582] | 877 [876–887] | 209.0 [203.8–209.5] | 263.4 [256.4–268.4] | 153 [151–155] | 50.0 | 0 |
| 50 MiB × 20 | mixed | json | 9 | 3 | 7.60 | 7.60 | 25.1 [21.4–25.2] | 91 | 29.7 [26.0–29.8] | 582 [580–582] | 904 [895–962] | 203.0 [195.1–206.1] | 260.4 [249.2–338.8] | 150 [142–150] | 50.0 | 0 |
| 50 MiB × 20 | similar | xml | 1 | 3 | 8.62 | 8.62 | 4.0 [3.4–4.7] | 79 | 5.5 [4.8–5.8] | 582 | 890 [883–891] | 199.0 [195.9–206.8] | 350.2 [254.8–365.9] | 147 [142–152] | 50.0 | 0 |
| 50 MiB × 20 † | similar | xml | 3 | 3 | 8.51 | 8.51 | 3.4 [3.1–3.4] | 79 | 4.7 [4.6–5.0] | 582 | 712 [691–754] | 136.1 [132.7–136.9] | 176.0 [170.1–184.2] | 98 [95–99] | 50.0 | 0 |
| 50 MiB × 20 † | similar | xml | 9 | 3 | 9.97 | 9.97 | 13.9 [12.6–15.1] | 91 | 15.2 [14.4–15.8] | 582 | 806 [767–909] | 129.3 [129.2–135.5] | 173.5 [173.0–245.7] | 92 [92–94] | 50.0 | 0 |
| 50 MiB × 20 † | similar | json | 1 | 3 | 6.66 | 6.66 | 3.4 [3.4–3.5] | 79 | 5.1 [5.0–5.7] | 582 | 757 [716–779] | 146.0 [143.1–147.1] | 186.3 [176.4–192.2] | 112 [108–112] | 50.0 | 0 |
| 50 MiB × 20 † | similar | json | 3 | 3 | 6.57 | 6.57 | 3.8 [3.6–4.2] | 79 | 6.2 [5.0–6.4] | 582 | 828 [805–855] | 146.0 [134.3–155.3] | 213.6 [178.1–233.3] | 108 [101–114] | 50.0 | 0 |
| 50 MiB × 20 † | similar | json | 9 | 3 | 7.67 | 7.67 | 15.7 [15.6–15.9] | 91 | 18.3 [17.4–22.8] | 582 | 928 [888–961] | 143.7 [129.8–144.9] | 192.3 [191.0–220.2] | 105 [98–106] | 50.0 | 0 |

- **Nothing failed.** 144 runs, 0 insert, merge or OPTIMIZE errors, 0 query exceptions, 0 read mismatches; every table ended in
  one Wide part. 50 MiB payloads in one 1 GiB table fit the default server limits on this VM.
- **The ratio depends on the format and the level, hardly on the size.** zstd 1: XML 7.1–8.6 (100 KiB mixed is the low end), JSON
  5.6–6.7. zstd 9 is 12–17 % better than zstd 1 in every cell; zstd 3 is 0.1–3.6 % *worse* than zstd 1 in every cell (observed,
  not explained here). `similar` beats `mixed` by 1–24 %, most at 100 KiB.
- **zstd 9 costs 4.3–6.0× the insert time and 3.0–6.1× the OPTIMIZE time of zstd 1** (non-† cells): e.g. 10 MiB mixed XML ingests
  at 215 MB/s raw with zstd 1 and 43 MB/s with zstd 9 (one insert stream, raw bytes / server insert time).
- **Insert memory follows the batch, not the payload**: 98–155 MiB per 64 MiB batch for 100 KiB–10 MiB payloads, 79–91 MiB for
  a single 50 MiB payload.
- **Merge memory follows the payload from 10 MiB on**: 123–145 MiB (100 KiB), 178–200 MiB (1 MiB), 199–200 MiB (10 MiB),
  **582 MiB (50 MiB)** peak per merge, the same for every level, format and mix. A table here holds one cell's parts only: 4
  inserts (100 KiB), 8 (1 MiB), 17 (10 MiB) or 20 (50 MiB), so a merge combined at most that many parts; the fan-in observed in
  the recheck was 4 (100 KiB) and 5 at the 50 MiB peak (6 the largest). The harness runs one table at a time; how many such
  merges run at once in production (background pool) was not measured.
- **A small payload is not a small read**: a read by id reads its whole granule (index_granularity_bytes 10 MiB): 6.3–7.4 MiB per
  read for 100 KiB and 1 MiB payloads, so p50 is 13–21 ms at both sizes; 10 MiB reads take 48–51 ms p50 (65–80 ms p99), 50 MiB
  reads 199–222 ms p50 (260–529 ms p99; non-† rows). Reads filter on id only (the third key column) over at most 2 000 rows per
  table; a production table with millions of rows was not measured.

**Proposal for the owner (CORE-2):** store payloads inline in ClickHouse up to **10 MiB**, column codec **ZSTD(1)**.
- Size, decided by merge memory: a 50 MiB payload costs **582 MiB per merge** against 199–200 MiB for 10 MiB (part_log
  peak_memory_usage, every cell), and its read takes 199–222 ms p50 against 48–51 ms. Nothing failed at 50 MiB on this VM, so 50 MiB
  is a possible bound if the owner accepts ~0.6 GiB per concurrent merge of such parts.
- **Limit of this evidence**: the merge peaks were measured at the fan-in observed here (4 parts at 100 KiB, 5 at the 50 MiB
  peak, at most 20 parts per table); a long-lived table's merges may combine more parts, and their peak was not measured. The
  10 MiB bound and the ~0.6 GiB per 50 MiB merge hold for the observed fan-in only; concurrent merges were not measured either.
- Level, decided by ingest time: ZSTD(9) gains 12–17 % ratio for 4.3–6.0× the insert time (43 vs 215 MB/s per stream at 10 MiB);
  ZSTD(3) is not better than ZSTD(1) on these payloads.

---

## FIX-108 — CPU sampling overhead (partial: round 1 only) — cloud VM, 4 vCPU

Date: 2026-11-02. Commits: the FIX-108 working tree on 84b47f0 (records labelled `84b47f0+dirty`) and the parent 84b47f0 (a git
worktree, records `node-cpu-<step>-c<c>-parent-r<round>`, n = 3; xslt c64 n = 2). Machine: Intel Xeon @ 2.80 GHz × 4 vCPU, kernel
6.18.44-fc-v185, JDK 25.0.5, native Postgres. `BENCH_SCENARIOS=cpu bench/run.sh`: sync flow, 20 000 messages after 1 000 warm-up,
default maxInFlight 64×c; exit 3 kept only with 0 errors (refusals are FIX-113). Logs: agent/work/FIX-108-bench6.log,
-parent-bench6.log (not committed); records in bench/out/node-cpu-*.json (not committed).

**The `off` rows predate the owner decision of 2026-11-03:** they ran with every step wrapped in a disabled `io.flottila.Step`; the committed code wraps no step with sampling off, so `off` is now the parent's stage list and these rows are not the commit's off configuration.

**Not a complete measurement.** Round 1 only (n = 1 per tree row): the script stopped at `node-cpu-xslt-c64-pt0.004s-r1` with
"writer backlog full … snapshotWriteFailures rose from 0 to 5366" (F-13 / FIX-66; the parent's xslt c64 r1 stopped the same way),
and the first records ran beside a parent `mvn` build. No overhead claim is made from these rows; FIX-112 re-measures (3 rounds)
before it decides default-on.

| step, c | sampling | msg/s | p50 ms | p99 ms | process CPU ms per request sent | refused |
|---|---|---|---|---|---|---|
| passthrough, 1 | off | 668 | 0.78 | 6.9 | 1.39 | 5 350 |
| passthrough, 1 | PT0.004S | 665 | 1.19 | 7.1 | 2.02 | 435 |
| passthrough, 1 | PT0.002S | 660 | 1.38 | 4.4 | 2.16 | 0 |
| passthrough, 1 | parent | 747 [707–769] | 1.23 [1.15–1.29] | 3.9 [3.8–4.0] | – | 108 |
| passthrough, 64 | off | 988 | 18.09 | 62.8 | 0.92 | 13 456 |
| passthrough, 64 | PT0.004S | 851 | 20.15 | 81.5 | 1.11 | 13 283 |
| passthrough, 64 | PT0.002S | 872 | 19.31 | 79.3 | 1.06 | 13 200 |
| passthrough, 64 | parent | 942 [933–1049] | 17.29 [16.77–18.59] | 64.5 [62.5–77.3] | – | 40 649 |
| xslt, 1 | off | 82 | 10.14 | 30.8 | 13.42 | 2 637 |
| xslt, 1 | PT0.004S | 85 | 9.95 | 25.0 | 12.89 | 2 749 |
| xslt, 1 | PT0.002S | 93 | 9.24 | 24.0 | 12.33 | 2 662 |
| xslt, 1 | parent | 86 [86–87] | 9.92 [9.85–10.04] | 21.9 [21.9–22.4] | – | 8 171 |
| xslt, 64 | off | 229 | 249.53 | 611.5 | 10.64 | 1 833 |
| xslt, 64 | PT0.004S / PT0.002S | no record (stopped, see above) | | | | |
| xslt, 64 | parent | 235 [233–238] | 243.16 [240.99–245.33] | 654.4 [649.6–659.2] | – | 2 887 |

Reading: one run per tree row; parent rows median [min–max] of its runs, refused summed over them (per run in the records —
the parent's passthrough c1 runs refused 2, 20 and 86, the tree's sampling-off run 5 350: FIX-113). Process CPU is divided by every
request sent, refusals included. The sampler's own columns (samples, lost) are left out: NodeBenchMain read the endpoint without
waiting out the 5–10 s read lag (passthrough c64 shows the warm-up's 1 000 executions only), and `lost` is JVM-wide, so it must be
divided by virtual plus platform samples (these records give 10.6–16.9 % passthrough, 4.1–4.3 % xslt, over a window that is not the
measured one). FIX-112 waits two read cycles before it reads, then states lost.

## Hand-over gate (GATE) — commit fea6c5c — cloud VM, 4 vCPU

Date: 2026-10-30. Commit: fea6c5c (FIX-107, the last commit before the gate review). Machine: Intel Xeon @ 2.80 GHz × 4 vCPU,
16 GiB, kernel 6.18.44, Java 25.0.5 HotSpot, native PostgreSQL 16.15 (default config, `synchronous_commit` on); load 0.98
before the batch, no stack running (target/GATE2-bench.log :1-5). Harness and settings as in the Phase 1 gate section below
(`NodeBenchMain` via bench/run.sh, `LedgerMain`, `StreamHeapIT`); **one run per cell** for sync and ACK (the gate packet), three
runs for the ledger and the 50 MB body. Raw JSON: the session's target/GATE2-json/ (node-ack-c{1,8,64}.json, sync-c{1,8}-*.json,
ledger2000-run{1,2,3}.json); the node-ack-c*-r{1,2,3}.json files in that folder are copies of the committed bench/out files (earlier runs), not this one.

**Gate verdict (GATE.md check 9):**

| threshold | measured on fea6c5c | verdict |
|---|---|---|
| kill -9 ledger 2000/2000 (sent = final-step rows, DLQ 0, work 0) | 2000/2000/2000 in 3 of 3 runs; missing 0, DLQ 0, work 0; node 1 exit 137 after ~1000 sent | **met** |
| sync platform overhead < 2 ms p50 | c=1: 0.77 ms metadata-only, 1.11 ms keep; c=8: 1.69 metadata-only, 4.66 keep | **met at c=1** (as P1); not under load |
| durable ACK p99 as measured (F-14, F-18: < 5 ms is a tuning target) | c=1 7.91 ms, c=8 35.70 ms, c=64 157.62 ms | recorded; target missed (ISSUE-2 answered, F-18) |
| 50 MB body through a stream step without heap growth | StreamHeapIT at -Xmx56m: −303 336 / −62 400 / −300 184 B, 1/1 green each, no OOM | **met** |
| coverage and mutation of the gate commit | table below (CI run 146) | see table |

**Ledger (`LedgerMain`, 2000 messages, concurrency 8, kill -9 of node 1 mid-run, node 2 drains; target/GATE2-ledger2000.log):**

| run | sent | final-step rows | missing | DLQ | work | duplicates (partner) | re-sent by client | saved, unACKed at kill | held → kill ms |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 2000 | 2000 | 0 | 0 | 0 | 8 | 6 | 1 | −1 |
| 2 | 2000 | 2000 | 0 | 0 | 0 | 4 | 6 | 1 | 15 |
| 3 | 2000 | 2000 | 0 | 0 | 0 | 9 | 7 | 1 | 43 |

Duplicates are outbound resends after the kill (at-least-once, expected), not extra messages. The run used `LedgerMain` directly:
LedgerIT in the tree runs 40 and 200 messages only; the first scripted LedgerIT/StreamHeapIT attempts in GATE2-bench.log :6-15 failed
on a wrong `-Dit.test` reactor invocation and were re-run (GATE2-ledger2000.log, GATE2-StreamHeap{1,2,3}.log).

**Sync request/reply (overhead = p50(flow) − p50(raw), same client; one run each):**

| run | p50 ms | p99 ms | msg/s | overhead p50 ms | raw p50 ms |
|---|---|---|---|---|---|
| metadata-only c=1 | 0.95 | 3.07 | 933 | 0.77 | 0.18 |
| keep c=1 | 1.25 | 4.40 | 717 | 1.11 | 0.14 |
| metadata-only c=8 | 2.73 | 10.86 | 2402 | 1.69 | 1.03 |
| keep c=8 | 6.05 | 18.09 | 1192 | 4.66 | 1.40 |

**Durable ACK (at-least-once HTTP → 202 after the one-transaction save, D-082; 20 000 + 1 000 warm-up; target/GATE2-ack.log :25/:45/:65):**

| run | p50 ms | p95 ms | p99 ms | max ms | msg/s |
|---|---|---|---|---|---|
| c=1 | 3.66 | 5.45 | 7.91 | 73.4 | 259 |
| c=8 | 13.50 | 26.40 | 35.70 | 172.9 | 540 |
| c=64 | 77.08 | 132.10 | 157.62 | 223.3 | 766 |

Against the P2-08 "after (1d5d507)" medians above (p99 8.80 / 41.38 / 163.29 ms at c=1/8/64): every cell is lower or within
that run's range — one run each here, P2-02D's dedupe table and P2-07's V10 indexes are on the path since; no verdict from one run
(the load regression stays F-18 / FIX-95).

### Gate commit fea6c5c — CI evidence (GATE, 2026-10-30)

Push run 145 (success) and dispatched run 146 = 41014601889 (jobs: build 123927964494, stack 123931660052, bench 123931660130,
dco 123927964797, mutation 123931660045). Coverage from `ci/coverage-baseline.json` at fea6c5c, which the build job's "baseline
describes this commit" step checked (±2 pp; the build job log reads "coverage: no decrease" and "baseline: current", target/GATE2-build-job.txt, full log GATE2-build-job-full.txt); PIT from the mutation job log (target/GATE2-pit-job.txt, full log target/GATE2-pit-job-full.txt). All floors met.

| module | line % | branch % | floor | PIT killed / generated | PIT floor |
|---|---|---|---|---|---|
| flottila-api | 99.2 | 100.0 | 90 | 172 / 173 (99 %) | 80 |
| flottila-core | 99.8 | 99.5 | 90 | 859 / 867 (99 %) | 80 |
| flottila-runtime | 99.7 | 99.7 | 75 (ITs counted) | — | — |
| flottila-cli | 98.7 | 100.0 | 75 | — | — |
| flottila-components/http | 99.3 | 93.3 | 75 | — | — |
| flottila-components/mock | 100.0 | 99.2 | 75 | — | — |
| flottila-components/sftp | 100.0 | 98.9 | 75 | — | — |
| flottila-steps/validate | 100.0 | 100.0 | 75 | — | — |
| flottila-steps/xslt | 100.0 | 100.0 | 75 | — | — |
| flottila-testkit | 98.0 | 95.8 | 75 | 577 / 747 (77 %, informational) | — |
| flottila-schemas | 99.0 | 99.5 | 75 | — | — |
| flottila-bench | 96.8 | 95.4 | 75 | — | — |

## P2-07 — the retention indexes (V10) on the durable ACK path — cloud VM, 4 vCPU

Date: 2026-10-30. Commits: before = a39a3ed (HEAD, a git worktree, schema 9) and after = a39a3ed + the P2-07 working tree
(schema 10: `messages(created_at, id, step)`, `bodies(created_at, message_id, step, part)`, `processed_ids(created_at,
scope, key_hash)`, D-088), the same `NodeBenchMain` source on both sides. Machine: this VM: kernel 6.18.44-fc-v185, 4 ×
"Intel(R) Xeon(R) Processor @ 2.80GHz" (model hidden), MemTotal 16 477 352 kB, /dev/vda virtio-blk ext4 (write_cache "write
back"), pg_wal on /dev/vda. Java 25.0.5, native PostgreSQL 16.15 (Ubuntu), default configuration; both records read from
pg_settings `synchronous_commit` on, `commit_delay` 0, `commit_siblings` 5, `wal_sync_method` fdatasync, `fsync` on,
`full_page_writes` on; node Pg pool 16. Harness: `NodeBenchMain --scenario ack --concurrency 8` (HTTP ingress → 202 after
the durable save, the worker and the passthrough → echo partner beside), 20 000 messages after 1 000 warm-up, body 132 B, a
fresh database per side, one run each, back to back (load average 1.08 when before started, 4.05 when after started: the
before run's tail). WAL B/msg = `pg_current_wal_lsn` delta of the cluster / messages; bodies B/msg =
`pg_total_relation_size('bodies')` delta (its indexes included) / messages. The measured cost of the V10 indexes is the bytes:
WAL per message +17 % (3 007 → 3 522 B) and bodies per message +27 % (668 → 846 B, the new bodies index) are structural — every
insert writes one more index entry, whatever the load. The latency and throughput differences (p50 +0.30 ms, p99 +2.2 ms, msg/s
−3 %) are confounded: one run per side, the after run starting at load 4.05, so they are not attributed to the indexes. No
verdict threshold. Raw JSON and the run manifest: the session's target/P2-07-raw/.

| config | c | p50 ms | p95 ms | p99 ms | max ms | msg/s | commits/msg | WAL syncs/msg | WAL B/msg | bodies B/msg |
|---|---|---|---|---|---|---|---|---|---|---|
| before (a39a3ed, V9) | 8 | 11.37 | 22.51 | 30.68 | 90.0 | 643 | 10.83 | 3.13 | 3 007 | 668 |
| after (P2-07, V10) | 8 | 11.67 | 23.50 | 32.85 | 69.3 | 621 | 10.82 | 3.09 | 3 522 | 846 |

## P2-08 — durable ACK, one transaction — cloud VM, 4 vCPU

Date: 2026-10-29. Commits: before = e2c8c53 (parent of P2-08a, a git worktree) and after = 1d5d507 (P2-08a, the ingress
body, ingress row and work item in one transaction, D-082), both with the P2-08b harness (`NodeBenchMain` compiled unchanged
on e2c8c53: `git diff --stat e2c8c53 1d5d507 -- flottila-bench` is empty, target/P2-08b-cleanup.log). Machine: this VM: kernel 6.18.44-fc-v185, 4 × "Intel(R) Xeon(R)
Processor @ 2.80GHz" (model hidden), MemTotal 16 477 352 kB, /dev/vda virtio-blk ext4 (discard, write_cache "write back"),
/dev/shm tmpfs. The host's storage behind /dev/vda is invisible to the guest: neither the device, its cache policy nor what
an fdatasync reaches on the host can be read from inside, so "durable" means what the guest's fdatasync returns. Java 25.0.5,
native PostgreSQL 16.15 (Ubuntu), default configuration; every record reads from pg_settings `synchronous_commit` on
(ALTER DATABASE by the bench), `commit_delay` 0 (214 in the commit_delay rows), `commit_siblings` 5, `wal_sync_method`
fdatasync, `fsync` on, `full_page_writes` on; node Pg pool 16 (`poolSize`). The 5432 cluster's pg_wal is on /dev/vda ext4
(`df -T`, target/P2-08b-cleanup.log). `pg_test_fsync -s 5`, one 8 kB write, rerun after the cells with the command lines and
exit codes logged (target/P2-08b-fsync2.log): `-f /var/lib/postgresql/16/main/pg_wal/p208b_pg_test_fsync.tmp` fdatasync
2628 ops/s (381 µs/op), open_datasync 3104 ops/s (322 µs/op); `-f /dev/shm/p208b_pg_test_fsync.tmp` (the FLOOR's pg_wal
filesystem) fdatasync 963 458 ops/s, open_datasync 1 253 354 ops/s (1 µs/op). These differ from the first run before the
cells (target/P2-08b-raw/pg_test_fsync-{vda,shm}.txt, `-f /var/lib/postgresql/16/pg_test_fsync.tmp` on the same /dev/vda
ext4, outside pg_wal, and a file in the FLOOR's /dev/shm pg_wal dir; those command lines were not logged): fdatasync 2331
ops/s (429 µs/op), open_datasync 2364 ops/s (423 µs/op); /dev/shm fdatasync 779 901, open_datasync 1 277 972 ops/s. The
commit_delay below was chosen from the first run's 429 µs; the rerun's 381 µs would give 190 µs.

Harness: `NodeBenchMain --scenario ack` (HTTP ingress → 202 after the durable save; the worker and the passthrough → echo
partner run beside), 20 000 messages after 1 000 warm-up, c = 1, 8, 64; every cell is the median [min–max] of 3 runs, before
and after alternating (round 1 and 3 before first, round 2 after first), all 35 runs back to back (load average 0.8–8.2 when a
run started: the previous run's tail). The bench body is 132 B (passthrough invoice), far below the spool tier, so P2-08a's
spool-tier body read inside the transaction is not exercised here. commits/msg = Δ pg_stat_database.xact_commit of the
bench database over the window / 20 000 — every transaction: the ingress, the worker, the recording and every poll,
read-only ones included; WAL syncs/msg = Δ pg_stat_wal.wal_sync of the cluster / 20 000 (backends, walwriter, checkpointer).
Both counters are read 11 s after the last request on each side of the window (Postgres publishes a backend's counts at most
once a second while busy and 10 s after it went idle), so the after-read also counts the polls of those 11 s (not separated
out). commit_delay = ½ the first run's fdatasync latency = 214 µs, set with ALTER SYSTEM +
pg_reload_conf() by the role `flottila` (granted for the run by the postgres superuser `GRANT ALTER SYSTEM ON PARAMETER
commit_delay TO flottila` and `GRANT EXECUTE ON FUNCTION pg_reload_conf() TO flottila`), one pilot run per c (r0, not in the
table), then 3 kept. The commit_delay cells ran through a direct invocation (psql as `flottila`, then the cell driver), not
through `bench/run.sh` `BENCH_COMMIT_DELAY` and its EXIT trap. FLOOR: a second cluster on port 5440 (`initdb -X /dev/shm/…`,
default configuration, `synchronous_commit` on, data directory on /dev/vda, pg_wal on tmpfs), the after commit. State after
the runs, observed (target/P2-08b-cleanup.log): postgresql.auto.conf empty, commit_delay 0 (source default),
pg_parameter_acl empty, `flottila` without execute on pg_reload_conf(), the FLOOR data directory and its /dev/shm WAL absent,
one git worktree. Raw JSON: bench/out/p208b-*.json, copied with a manifest (cell, run, tree, load) to the session's
target/P2-08b-raw/.

| config | c | p50 ms | p99 ms | max ms | msg/s | commits/msg | WAL syncs/msg |
|---|---|---|---|---|---|---|---|
| before (e2c8c53) | 1 | 4.84 [4.74–4.88] | 10.58 [10.13–10.85] | 67.6 [61.3–85.2] | 195 [195–198] | 16.32 [16.30–16.32] | 6.99 [6.99–6.99] |
| after (1d5d507) | 1 | 4.06 [3.82–4.28] | 8.80 [8.13–9.25] | 61.2 [59.0–139.1] | 232 [222–248] | 12.33 [12.32–12.35] | 5.00 [5.00–5.00] |
| FLOOR — not durable | 1 | 3.48 [3.46–3.80] | 8.04 [7.71–8.70] | 38.0 [31.9–51.4] | 269 [247–272] | 12.23 [12.22–12.23] | 5.00 [5.00–5.00] |
| before (e2c8c53) | 8 | 14.35 [14.15–14.84] | 31.83 [31.68–33.07] | 63.1 [48.8–447.6] | 519 [507–526] | 13.84 [13.84–13.88] | 3.37 [3.34–3.37] |
| after (1d5d507) | 8 | 14.52 [14.13–14.86] | 41.38 [38.79–41.73] | 95.1 [86.7–395.5] | 505 [490–507] | 10.87 [10.86–10.87] | 3.10 [3.09–3.12] |
| after, commit_delay 214 µs | 8 | 13.32 [12.43–13.76] | 38.59 [34.70–40.29] | 71.4 [70.3–107.0] | 541 [521–586] | 10.82 [10.82–10.85] | 2.82 [2.82–2.83] |
| FLOOR — not durable | 8 | 13.92 [13.81–14.46] | 42.03 [40.75–42.48] | 87.4 [84.6–105.9] | 517 [496–517] | 10.87 [10.86–10.89] | 4.16 [4.15–4.17] |
| before (e2c8c53) | 64 | 69.52 [67.31–70.96] | 146.00 [143.69–164.26] | 237.4 [236.7–255.1] | 833 [810–896] | 12.39 [12.39–12.39] | 2.08 [2.08–2.10] |
| after (1d5d507) | 64 | 82.04 [81.58–87.96] | 163.29 [157.38–166.39] | 252.4 [229.1–282.2] | 733 [687–736] | 9.39 [9.37–9.39] | 1.89 [1.88–1.90] |
| after, commit_delay 214 µs | 64 | 80.46 [74.76–81.88] | 168.09 [156.97–171.62] | 267.9 [255.5–294.7] | 732 [728–789] | 9.39 [9.38–9.40] | 1.58 [1.57–1.58] |
| FLOOR — not durable | 64 | 86.43 [80.98–87.39] | 170.19 [165.22–178.71] | 299.3 [277.1–314.5] | 695 [694–741] | 9.40 [9.40–9.40] | 3.22 [3.20–3.23] |

**Verdict, c=1 against p99 < 5 ms: missed** — after 8.80 ms [8.13–9.25] durable (before 10.58 ms, −17 %); the FLOOR, with
the WAL on tmpfs, is 8.04 ms [7.71–8.70], also above 5 ms.

Reading: one transaction took 4.0 commits and 2.0 WAL syncs a message off the database at c=1 (16.32 → 12.33, 6.99 → 5.00),
3.0 / 0.27 at c=8 and 3.0 / 0.19 at c=64. At c=1 the WAL on tmpfs takes only 0.8 ms more off p99: the remaining 8 ms are
not the guest's fdatasync. Under load after is worse than before on every run at c=8 p99 (38.79–41.73 against 31.68–33.07)
and at c=64 p50 (81.58–87.96 against 67.31–70.96) and msg/s (687–736 against 810–896); the c=64 p99 median is 163.29 against
146.00 with overlapping ranges. The FLOOR is no better than after at c=8 and c=64 (p99 42.03, 170.19): there the WAL flush
does not set the tail either. That the tail is CPU-bound on 4 vCPU rests on the Phase 0 reading below only (c=64 p99 with
synchronous_commit off); this run did not measure CPU. commit_delay 214 µs, per kept run against the after median: c=8 p99
−2.6 %, −6.7 %, −16.1 %; c=64 +5.1 %, +2.9 %, −3.9 % — not ≥ 10 % on all 3 runs at either c, so it is recorded here only and
not put into deploy/compose.

## Phase 1 gate (P1-10) — cloud VM, 4 vCPU

Date: 2026-10-28. Commit: e129c77 (harness as of P1-10c). Machine: 4 vCPU, 15 GiB, no swap, Java 25.0.5 HotSpot, native
PostgreSQL 16.15 (default config, `synchronous_commit` on), Linux 6.18; load 0.2–0.7 before each batch, nothing else running
(the bench itself raises the load to 6–10 on 4 vCPU: client, node, partner and Postgres share the box). Harness:
`flottila-bench` `NodeBenchMain` (in-process Node, generated bench package, raw Vert.x echo partner), `LedgerMain`,
`StreamHeapMain`; 20 000 messages after 1 000 warm-up; every cell is the median [min–max] of 3 runs; raw JSON in the session's
target/P1-10d/. Spread is given where the harness reports per-run values; raw p50, WAL/bodies rates and the
worker latency, ingress and drain columns are medians only; the BatchingWriter pair is one run. Sync runs use maxInFlight
64 × concurrency (4096 at c=64): the sync permit is held until the run's trail closes (F-10), so a tight limit would refuse
what the trail still holds. Database `flottila_bench`, role created locally for the run (no secret in the repo).

**Gate verdict (GATE.md check 9, ARCHITECTURE §7/§13):**

| threshold | measured | verdict |
|---|---|---|
| kill -9 ledger 2000/2000 (sent = final-step rows, DLQ 0, work 0) | 2000/2000/2000, DLQ 0, work 0 in 3 of 3 runs; node 1 exit 137 | **met** |
| sync platform overhead < 2 ms p50 | c=1 (the prototype's definition): 0.74 ms metadata-only, 0.92 ms keep; c=8: 1.34 metadata-only, 3.77 keep; c=64 metadata-only 9.81 | **met at c=1**; not under load on 4 vCPU |
| durable ACK p99 < 5 ms (target) | 8.82 ms at c=1, 27.4 ms at c=8, 135 ms at c=64 | **missed** → ISSUE-2 |
| 50 MB body through a stream step without heap growth | growth +0.05 / −0.02 / +0.04 MiB at -Xmx64m, sha256 equal, no OOM | **met** |

**Sync request/reply (HTTP → passthrough → HTTP partner; overhead = p50(flow) − p50(raw), same client):**

| run | p50 ms | p99 ms | max ms | msg/s | overhead p50 ms | raw p50 ms | WAL MB/s | bodies MB/s | bodies GB/h |
|---|---|---|---|---|---|---|---|---|---|
| metadata-only c=1 | 0.89 [0.88–0.98] | 2.81 [2.81–3.03] | 16.6 [12.0–113] | 1027 [918–1036] | 0.74 [0.72–0.81] | 0.17 | 1.16 | 0 | 0 |
| metadata-only c=8 | 2.43 [2.36–2.46] | 10.48 [9.92–11.33] | 26.5 [20.0–29.0] | 2650 [2637–2658] | 1.34 [1.24–1.44] | 1.09 | 2.89 | 0 | 0 |
| metadata-only c=64 | 17.26 [16.69–19.81] | 51.99 [49.24–56.10] | 89.7 [72.8–101.6] | 3278 [2880–3419] | 9.81 [8.92–10.75] | 8.34 | 3.57 | 0 | 0 |
| keep c=1 | 1.10 [0.95–1.14] | 3.59 [3.59–3.80] | 65.7 [20.4–132] | 823 [793–927] | 0.92 [0.84–0.98] | 0.16 | 1.85 | 0.56 | 2.01 |
| keep c=8 | 4.81 [4.80–5.13] | 16.59 [15.62–18.76] | 115 [27–141] | 1438 [1349–1471] | 3.77 [3.74–4.17] | 1.04 | 3.17 | 0.96 | 3.46 |
| keep c=64 | failed 3/3 at settle — see below and F-13 | | | | | | | | |

**Durable ACK (at-least-once HTTP ingress → 202 after the durable save incl. the `bodies` insert, D-061; the worker runs beside):**

| run | p50 ms | p99 ms | max ms | msg/s | WAL MB/s | bodies MB/s | bodies GB/h |
|---|---|---|---|---|---|---|---|
| c=1 | 4.24 [4.23–4.25] | 8.82 [8.78–9.30] | 175 [39–286] | 223 [220–225] | 0.69 | 0.15 | 0.54 |
| c=8 | 11.89 [11.74–12.56] | 27.44 [27.13–27.94] | 53.1 [45.8–54.9] | 628 [605–635] | 1.83 | 0.42 | 1.52 |
| c=64 | 60.88 [60.09–62.88] | 135.2 [130.3–148.1] | 194 [169–221] | 955 [912–966] | 2.77 | 0.64 | 2.32 |

**Worker (items queued through the at-least-once ingress at c=64, claim → complete; numbers include the bench's claim/complete
triggers and the ingest):**

| run | claim→complete p50 / p99 ms | msg/s | ingress p50 / p99 ms, msg/s | drain tail ms | WAL MB/s | bodies GB/h |
|---|---|---|---|---|---|---|
| passthrough keep | 67.3 / 229.9 | 597 [583–615] | 59.8 / 129.0, 963 | 12 920 | 1.71 | 1.45 |
| passthrough metadata-only | 58.6 / 203.2 | 660 [656–668] | 61.3 / 142.1, 936 | 8 938 | 1.51 | 0.80 |
| 100 KB XSLT keep | 265.6 / 1209.6 | 180 [165–185] | 177.4 / 809.0, 331 | 51 588 | 41.85 | 143.8 |
| 100 KB XSLT metadata-only | 337.3 / 992.8 | 158 [152–158] | 235.8 / 688.9, 246 | 44 932 | 17.60 | 60.1 |

Metadata-only still grows `bodies`: the at-least-once ingress body is always durable (D-058), 100 KB per XSLT item.

**Kill -9 ledger** (`LedgerMain --messages 2000`, node 1 a child JVM SIGKILLed after ~1000 ACKs, node 2 on an empty data dir
restores from the deployment row and a shared archive directory — the P1-09c3b path; S3 is covered by LedgerIT):

| run | sent / acked / final rows | missing | DLQ | work | duplicates | re-sent | saved unACKed | held → kill ms |
|---|---|---|---|---|---|---|---|---|
| 1 | 2000 / 2000 / 2000 | 0 | 0 | 0 | 8 | 5 | 0 | 475 |
| 2 | 2000 / 2000 / 2000 | 0 | 0 | 0 | 10 | 7 | 2 | 377 |
| 3 | 2000 / 2000 / 2000 | 0 | 0 | 0 | 8 | 7 | 0 | 404 |

Held → kill stayed far below the 30 s producer timeout. Duplicates are at-least-once redeliveries, not losses.

**50 MiB through a stream step** (`StreamHeapMain --mb 50 --xmx 64m`, sync flow `stream-copy` → partner, heap after a forced GC):
heap before 12.2 MiB, after 12.3 / 12.2 / 12.3 MiB, growth +0.05 / −0.02 / +0.04 MiB, max heap 64.0 MiB, 4.9–5.2 s, HTTP 200
with 52 428 800 bytes, sha256 equal, no OOM. The verdict rests on the post-GC growth: at 64m one whole-body copy would
still fit beside the live set (12.2 + 50 = 62.2 MiB < 64), so this run does not also prove that buffering would OOM — FIX-65
moves the check to an -Xmx where before + 50 MiB > max. The node's default FLOTTILA_BLOB_MEMORY_BUDGET is 256 MiB against
the 64m child heap; one body only is in flight here. FIX-65: StreamHeapIT runs at -Xmx56m (failsafe live set
14.6 + 50 = 64.6 MiB > 56; reply 200, 52 428 800 bytes, growth +0.05 MiB); the bench default and the recorded run above stay at
64m and do not show that margin.

**BatchingWriter pair (P0-10 harness, comparison):** sync 9733 msg/s, p50 5.89, p99 13.17 ms; async 12 404 msg/s, p50 4.58,
p99 13.11 ms — in P0-10's range (p99 12–18 ms at c=64).

- **The durable ACK misses its target at every concurrency.** Even unloaded (c=1) the p99 is 8.8 ms: two fsync'd commits per
  message (the ingress row with its body and the work item) on default Postgres. P0-10 measured the batcher alone at p99 8 ms
  at c=8; the bodies insert adds the rest. The document's own fallback for a sub-5 ms ACK is the non-durable reply mode (§4).
  ISSUE-2 puts the choice to the owner.
- **Sync overhead is CPU contention above c=1.** At c=1 the platform adds 0.74–0.92 ms; at c=64 the client, node, partner and
  Postgres share 4 vCPU and raw itself is 8 ms. `keep` costs ~0.2 ms unloaded and ~2.4 ms at c=8 (trail persists and rows).
- **c=64 sync keep fails** (F-13, FIX-66): settle targets 7915 / 7178 / 6942, so ≥ 13 085 of the 20 000-request window per run were
  not answered 200 (503, timeout or error — the harness does not split them, FIX-67). A diagnostic run recorded 5 720 messages,
  3 800 snapshot write failures and 3 800 more bodies than rows: rows whose bodies persisted were dropped with no log line. Not a GATE.md number, but monitoring is the product: FIX-66 precedes
  P1-GATE. The harness waited 10 min instead of failing fast (FIX-67).
- **Bodies growth** at sustained load: 1.5–3.5 GB/h for small messages, up to 144 GB/h for 100 KB XSLT with keep — retention
  (P2-07) is what bounds it.
- **c=64 sync rerun after FIX-66/FIX-67** (2026-10-28, f7707a2 + the FIX-67 harness, same machine and arguments, 3 runs each;
  JSON target/FIX-67/, exits target/FIX-67-exits.log). The harness now splits 503 refusals from errors and fails the settle
  the moment `snapshotWriteFailures` rises. Every run settled with the counter flat and no "was not recorded" WARNING, so
  every answered message has its rows: **F-13 closed**. The runs exit 3 because requests were refused (≈ 70 % with keep, 26 % metadata-only) — the closed-loop
  client re-sends at once after a 503, so the refusals measure the overload signal working (F-10), not a fault:

  | case | refused / 20 000 | errors | warm-up refused | p50 ms | p99 ms | answered msg/s | overhead p50 ms |
  |---|---|---|---|---|---|---|---|
  | keep, maxInFlight 4096 (64c) | 14 242 [13 482–14 260] | 0 | 0 | 13.97 [13.01–16.69] | 70.1 [60.5–72.0] | 982 [981–1074] | 6.82 [6.02–8.97] |
  | keep, maxInFlight 128 (2c) | 14 085 [13 757–14 377] | 0 | 402 [188–415] | 16.08 [14.75–17.26] | 70.7 [63.7–85.8] | 909 [888–968] | 7.96 [7.44–8.84] |
  | metadata-only, maxInFlight 128 | 5 249 [4 596–6 440] | 0 | 359 [253–424] | 18.56 [18.33–19.18] | 57.1 [50.9–61.5] | 2301 [2013–2474] | 10.68 [9.81–11.83] |

  With `keep`, sync at c=64 on 4 vCPU is bounded by the trail's persists and row writes (~1000 answered msg/s), and the permit
  limit (64c or 2c) hardly changes it. The p50, p99 and overhead columns count every request, the fast 503s included
  (NodeBenchMain:590), so they are not the latency of admitted requests and are not comparable with the clean runs above.

---

### Gate commit 350f71d — CI evidence (P1-GATE, 2026-10-28)

Gate commit: `350f71d` (FIX-70, the last commit before the gate review). Numbers measured by CI on it:
push run 40661411394 (success) and dispatched run 40661445376 (jobs: build 122725196614, mutation
122727572021, bench 122727572184, dco 122725195857, all success). PIT figures from the mutation job
log (target/P1-GATE-pit-job.txt); coverage from `ci/coverage-baseline.json` at 350f71d, which the
build job's "baseline describes this commit" step checked (±2 pp).

| module | line % | branch % | floor | PIT killed / generated | PIT floor |
|---|---|---|---|---|---|
| flottila-api | 99.0 | 100.0 | 90 | 125 / 125 (100 %) | 80 |
| flottila-core | 99.8 | 99.4 | 90 | 760 / 767 (99 %) | 80 |
| flottila-runtime | 99.6 | 99.7 | 75 (ITs counted) | — | — |
| flottila-components/http | 99.3 | 89.4 | 75 | — | — |
| flottila-components/mock | 100.0 | 98.6 | 75 | — | — |
| flottila-steps/validate | 100.0 | 100.0 | 75 | — | — |
| flottila-steps/xslt | 100.0 | 100.0 | 75 | — | — |
| flottila-testkit | 98.4 | 98.0 | 75 | 403 / 491 (82 %, informational) | — |
| flottila-schemas | 98.8 | 97.8 | 75 | — | — |
| flottila-bench | 96.7 | 95.3 | 75 | — | — |

Gate verdict (gate-reviewer): NOT_MET only on the §13 durable ACK (table above, ISSUE-2); every other
Phase 1 condition met. The ledger, durable-ACK and sync c=1/c=8 rows were measured on e129c77, before
FIX-66 touched Trail/BatchingWriter; only sync c=64 was re-run after it (f7707a2).

Owner's answer to ISSUE-2 (2026-10-28, option 1+2): the durable ACK is recorded as measured with the machine named,
< 5 ms p99 is the Phase 2 tuning target (P2-08); F-14. Gate re-review on that record: **MET** (P1-GATE done).

## P1-02 — the message path: admit → context → scope, 10 000 messages at once — cloud VM, 4 vCPU

Date: 2026-10-18. Commit: the P1-02 commit (hash in PROGRESS.md; `--commit eabaf5b` in the JSON names the base).
Machine as in the Phase 0 baseline. Harness: `flottila-bench` `ScopeBenchMain` (the spike-2 main, now also
running `ScopeBench.admitted`): 10 000 virtual threads start at once, each `Admitted.run(admission, context,
4 KiB, …)` against one flow budget of 256 requests / 64 MiB, body = fork 4 trivial children, join, read the
results; latency is the whole run from before `tryAcquire` to the permit's release; a refusal is the
non-blocking admission saying no (the 503 path) and is counted, never retried. 1 000-message warm-up,
`VertxTimer` deadlines of 30 s (never fired). Two runs back to back; raw JSON in the scratchpad.

| run | msg/s (completed) | completed / refused | p50 | p95 | p99 | max | max in flight observed |
|---|---|---|---|---|---|---|---|
| 1 | 45423 | 8247 / 1753 | 0.91 ms | 6.02 ms | 8.30 ms | 10.1 ms | 256 / 1024 KiB |
| 2 | 47462 | 9607 / 393 | 1.13 ms | 5.61 ms | 7.15 ms | 15.1 ms | 256 / 1024 KiB |

- **Spike-2 numbers hold on the real path (an order-of-magnitude comparison, not a controlled one).** The
  admitted path with 4 children per message runs at 45–47k messages/s, in the range of the ~49k scopes/s the
  bare `RequestScope` gave in P0-11 at 64 workers — 256 permits here vs 64 workers there, and the elapsed
  time includes the refused threads; the inference is that admission, the `ScopedValue` binding and the
  context rebinding in every child add nothing visible at this scale.
- **The budget held under a 10 000-message burst**: 256 / 1 MiB observed as the maxima, exactly the limits.
- **A burst against a non-blocking admission means refusals**: 4–18 % of the 10 000 were told "no" at
  once — correct for HTTP (503 + Retry-After, P1-05), and the reason pollers (SFTP, Kafka) will use the
  blocking `acquire` instead (ARCHITECTURE §4: "SFTP poller pauses, Kafka consumer stops polling"). The
  refused share varies between runs with the scheduler (how many of the 10 000 are runnable while 256 hold
  permits); it is a property of the burst, not of the path.
- Per-message latency (p50 ≈ 1 ms, p99 7–8 ms) is CPU queueing of 10 000 runnable virtual threads on 4
  carriers, as in P0-11's 64-worker row (p50 0.93 ms, p99 6–7 ms); the path adds no tail of its own.

---
## P1-01 — BlobStore tiers: 50 MB pass-through — cloud VM, 4 vCPU

Date: 2026-10-18. Commit: the P1-01 commit (hash in PROGRESS.md; `--commit 0c96758` in the JSON names the base).
Machine as in the Phase 0 baseline (4 vCPU, 15 GB, Java 25.0.4.1, Linux 6.18; spool on the VM's disk).
Harness: `flottila-bench` `BlobBenchMain --mb 50 --rounds 5 --chunk 65536` — a 50 MiB `GeneratedReadStream`
written through `TieredBlobStore` (memory tier: everything in memory under the budget; spool tier: memory
tier 0, segmented AES-256-GCM file, 1 MiB segments) and read back through `open()` with the hash checked
every round; MB/s is the median of 5 rounds (the first spool round carries JIT and cipher warm-up and is
visible in the per-round list). Two runs back to back; raw JSON in the coordinator's scratchpad. Measured
with the segmented cipher before the per-file key derivation was added (two HMAC-SHA256 calls and one
extra read per file since — not re-measured).

| tier | write MB/s (run 1 / run 2) | read MB/s (run 1 / run 2) | write ms per round (run 1) | read ms per round (run 1) |
|---|---|---|---|---|
| memory | 206 / 229 | 764 / 848 | [281.2, 242.8, 248.1, 187.5, 226.6] | [86.0, 70.1, 65.5, 56.6, 62.7] |
| spool (segmented AES-GCM) | 367 / 462 | 305 / 320 | [1040.4, 142.1, 122.0, 136.2, 109.5] | [1019.8, 158.4, 191.2, 164.2, 151.1] |

- **A 50 MiB body passes through either tier without the body in memory at once — proven by heap size,
  not by a GC heuristic**: `SpoolHeapTest` runs the spool round trip in a JVM with `-Xmx48m`, `S3HeapIT`
  the multipart round trip with `-Xmx96m` (runtime pom, executions `heap-bounded` / `heap-bounded-it`);
  a store that holds the body dies with OutOfMemoryError there. Both pass.
- **Spool reads are a stream only because the file is segmented.** The JDK's `CipherInputStream` over
  AES-GCM releases no plaintext before the final tag (measured: the first 64 KiB read of a 64 MiB file
  arrived after 330 MiB of heap growth). Segments of 1 MiB with one tag each give one segment of memory,
  authentication before delivery, and detection of truncation and reordering (`SpoolCipherTest`). The
  per-segment cost shows in the read number (305–320 MB/s vs 387–392 MB/s with the unsegmented cipher
  measured earlier the same day).
- **JIT shape matters more than the cipher (the explanation is inferred from throughput, not from
  `-XX:+PrintCompilation`).** Feeding a segment to the cipher as one `doFinal(1 MiB)` ran at 55 MB/s in
  every round (both thread kinds); the same bytes through 64 KiB `update()` calls run at 1.5–2.3 GB/s once
  warm — consistent with the one-shot path never reaching the compiled, intrinsified GCM code. The spool slices every segment into 64 KiB updates (`SpoolCipher.SLICE`).
- **The hash, not the tier, looks like the write bottleneck (inferred, not isolated).** The memory tier
  writes at ~210–230 MB/s although it only appends to a buffer: the source thread hashes every chunk
  (`Hasher.sha256()`, SHA-256 truncated to 64 bits, JDK) and appends in the same thread; the spool is faster
  to write because hashing runs on the source thread while the cipher and the file write run on the
  sink's virtual thread — two cores. ARCHITECTURE §4 names xxh3; the Hasher SPI exists for that swap (a
  JDK xxh3 is not available; a dependency would be needed — not decided here, D-041). The inference is from
  the thread split alone; the generator's byte loop and the Buffer copies share the source thread, no
  hash-only run was made.
- Reads: memory 760–850 MB/s (64 KiB slices of the buffer), spool 305–320 MB/s (segment decrypt + file
  read on a virtual-thread pump, 64 KiB chunks).
- S3 (LocalStack in Docker on the VM) is exercised by `S3TierIT` / `S3HeapIT` for correctness (contract,
  12 MiB and 50 MiB multipart, abort); no S3 throughput is recorded — a container is not a native store,
  and the number would be LocalStack's, not S3's.

---
## Phase 0 gate — commit 7f3e4e3 (2026-10-18)

Gate commit: `7f3e4e3` (P0-12, the last Phase 0 code commit); numbers measured by CI on it, dispatched run
39095635572 (jobs: build 117581371310, mutation 117581612828, bench 117581612769, all success) and the
push run 39095623949 (build, dco). Conditions (WORKFLOW §5, BACKLOG P0-GATE): every Phase 0 row `done`
with `review: PASS` (P0-01 … P0-12, FIX-1/3/4, B-1 — PROGRESS.md), coverage floors met, PIT ≥ 80 % on
api and core, bench baseline recorded, schemas merged.

| module | line % | branch % | floor | PIT killed / generated | PIT floor |
|---|---|---|---|---|---|
| flottila-api | 98.5 | 100.0 | 90 | 85 / 85 (100 %) | 80 |
| flottila-core | 99.2 | 100.0 | 90 | 171 / 172 (99 %) | 80 |
| flottila-runtime | 100.0 | 100.0 | 75 (ITs counted) | — | — |
| flottila-testkit | 97.4 | 90.2 | 75 | 124 / 142 (87 %, informational) | — |
| flottila-schemas | 98.3 | 94.4 | 75 | — | — |
| flottila-bench | 95.6 | 91.7 | 75 | — | — |

(The VM's last measurement of testkit was 91.3 % branch — the sampler nondeterminism FIX-2 is about,
inside the 2 pp band; every other number is identical on both machines. ci/coverage-baseline.json keeps
high-water marks, so its bench line reads 96.5 where the table says 95.6.)

Bench on the gate commit, github-hosted runner (same job): sync 17 295 msg/s, p50 3.03 ms, p99 9.53 ms;
async 18 348 msg/s, p50 2.94 ms, p99 9.02 ms; `bench: no regression` against the runner markers (B-1,
D-039). The VM baselines (P0-10) and spike-2 numbers (P0-11) are the sections below.

Spike answers carried into Phase 1: (1) durable ACK on native Postgres is p50 ≈ 4–6 ms, p99 8–18 ms at
c=64 — not < 5 ms p99; sub-5 ms needs the non-durable reply mode (P0-10). (2) `RequestScope` costs
≈ 55 µs per message with 4 children and `Admission` never exceeds its budget under 10 000 virtual
threads (P0-11). Schemas: four v1 drafts published in `flottila-schemas`, demo package validated (P0-12).

Open follow-ups carried into Phase 1 (BACKLOG Fixes section, not gate conditions): FIX-2 (deterministic
detector coverage) and Q-1 (WorkQueue `locked_by` guard for stale completes).

**Gate: MET.** Phase 1 rows become `ready` (P1-01, P1-02 first).

---
## P0-11 — RequestScope + admission under 10k virtual threads (spike 2) — cloud VM, 4 vCPU

Date: 2026-10-18. Commit: the P0-11 commit (hash in PROGRESS.md; `--commit 9c18826` in the JSON names
the base). Machine as in the Phase 0 baseline (4 vCPU, Java 25.0.4.1, Linux 6.18). Harness:
`flottila-bench` `ScopeBenchMain` (`java -cp flottila-bench/target/flottila-bench-*.jar:flottila-bench/target/lib/* io.flottila.bench.ScopeBenchMain …`),
1 000 warm-up scopes / acquires per configuration, `VertxTimer` for the deadlines (30 s, never fired).
Raw JSON per run in the coordinator's scratchpad (not retained in the repo; the table is complete).
Command sequence (all with `BENCH_MACHINE="cloud VM, 4 vCPU"`, `--commit 9c18826`, back to back on a quiet VM):
`--scopes 10000 --children 4 --threads 10000 --requests 256 --bytes 67108864 --request-bytes 4096 --hold-ms 1` (twice: "all at once");
`… --scope-concurrency 1 … --requests 10000 --hold-ms 0` ("one after another" + "uncontended");
`… --scope-concurrency 64 … --requests 64 --hold-ms 1` (twice: "64 workers" + "64 slots").

### RequestScope: open → fork 4 trivial children → join → read 4 results → close, per scope

| run | workers | scopes/s | p50 | p95 | p99 | max | mean |
|---|---|---|---|---|---|---|---|
| 10 000 scopes, one after another | 1 | 15038 | 54 µs | 124 µs | 250 µs | 4.0 ms | 66 µs |
| 10 000 scopes, 64 workers | 64 | 48292 | 924 µs | 3.4 ms | 7.1 ms | 16.6 ms | 1.3 ms |
| 10 000 scopes, 64 workers (repeat) | 64 | 49940 | 946 µs | 3.2 ms | 5.6 ms | 11.6 ms | 1.3 ms |
| 10 000 scopes all at once | 10000 | 49413 | 74.5 ms | 131.7 ms | 154.0 ms | 185.3 ms | 75.5 ms |
| 10 000 scopes all at once (repeat) | 10000 | 49428 | 41.3 ms | 68.3 ms | 86.3 ms | 108.7 ms | 39.8 ms |

- **Cost of the scope itself: ~55 µs p50 per message** for open + 4 forks (4 virtual threads
  started) + join + close on one thread (15 000 scopes/s on a single worker = 60 000 child threads/s).
  That is the fork/join overhead the backlog row asks for; a message's own work dwarfs it.
- With 64 workers the 4 vCPU saturate at ~49 000 scopes/s (≈ 200 000 child threads/s); per-scope
  wall time becomes CPU queueing (p50 0.9 ms, p99 6–7 ms), not scope cost.
- 10 000 scopes started at once is the pathological case the row names: the same ~49 000 scopes/s
  throughput, but a per-scope wall time of 40–75 ms p50 and up to 185 ms — that is 50 000 runnable
  virtual threads queueing on 4 carriers. It is exactly what admission exists to prevent (below),
  not a property of the scope.
- 0 failures in every run; every scope dropped its deadline timer (the tests assert `pending() == 0`).

### Admission: 10 000 virtual threads acquire 4 KiB each from one flow's budget, hold, release

| run | slots | hold | acquires/s | acquire p50 | p95 | p99 | max | wall | max in flight observed |
|---|---|---|---|---|---|---|---|---|---|
| uncontended (10 000 slots) | 10000 | 0 ms | 176978 | 0.6 µs | 6.3 ms | 8.6 ms | 9.0 ms | 57 ms | 777 / 3108 KiB |
| 256 slots | 256 | 1 ms | 69447 | 79.270 ms | 112.5 ms | 115.0 ms | 115.5 ms | 144 ms | 256 / 1024 KiB |
| 256 slots (repeat) | 256 | 1 ms | 89571 | 48.807 ms | 73.7 ms | 80.2 ms | 81.5 ms | 112 ms | 256 / 1024 KiB |
| 64 slots | 64 | 1 ms | 42104 | 118.468 ms | 194.6 ms | 200.9 ms | 202.0 ms | 238 ms | 64 / 256 KiB |
| 64 slots (repeat) | 64 | 1 ms | 46907 | 94.925 ms | 163.1 ms | 168.5 ms | 169.6 ms | 213 ms | 64 / 256 KiB |

- **The budget was never exceeded**: 256 / 1 MiB and 64 / 256 KiB observed as the maxima, exactly
  the limits (the unit test does the same with 10k threads and a 64 / 64 KiB budget).
- Uncontended acquire + release costs ~1 µs p50 (the p95/p99 there are 10 000 threads starting at
  once, not the lock: max in flight 777 shows they really ran concurrently).
- Contended, the acquire latency is queue wait by construction (FIFO: the median caller waits for half
  the run). The mechanism's own cost shows in the wall time — derived, not measured: 10 000 holds of
  1 ms through 256 slots need ≥ 39 rounds of hold; the hold is `Thread.sleep(1)`, assumed ≈ 1.1 ms,
  so ≈ 43 ms of pure hold; measured 112–144 ms, i.e. 1.8–2.6 ms of wall time per round beyond the
  hold with 10 000 runnable virtual threads on 4 carriers (release → signal → unpark → acquire returns
  for 256 waiters, plus the scheduler). With 64 slots: 157 rounds in 213–238 ms = 1.4–1.5 ms per round,
  same picture. A per-hand-over CPU figure would need a profiler; none is claimed.
- Consequence for the design (ARCHITECTURE §4): per-flow admission costs nothing measurable when the
  flow is below its budget and keeps memory bounded when it is not; the latency a rejected or queued
  request sees is the budget's choice, not the implementation's. The non-blocking `tryAcquire` path
  (HTTP 503 + Retry-After) is a lock acquire + two comparisons.

### Spike-2 answer

`RequestScope` (fork / join / cancel on plain virtual threads, deadline through the injected Timer)
costs ~55 µs per message with 4 children and holds up at 10 000 concurrent scopes; `Admission`
(requests **and** bytes, FIFO, interrupt-safe) never exceeds its budget under 10 000 contending
virtual threads and costs ~1 µs uncontended. Both stay as designed (D-038); `StructuredTaskScope`
replaces the scope when it leaves preview, with the same tests.

---
## Phase 0 baseline — cloud VM, 4 vCPU

Date: 2026-10-18. Commit: the tree of 663d45d plus the P0-10 change (the P0-10 commit hash is in
PROGRESS.md; `bench/out/*.json` carries `commit`). Machine: Linux 6.18.44-fc-v139, amd64, 4 vCPU, 15 GB,
Java 25.0.4.1, PostgreSQL 16.15 (Ubuntu 16.15-0ubuntu0.24.04.1) running **natively** on the VM (`pg_ctlcluster 16 main`, default
configuration, data dir on the VM's disk), database `flottila_bench` migrated to V1 by Flyway.
Harness: `bench/run.sh` → `flottila-bench` (`BenchMain`): `BatchingWriter.save` →
`PgBatchSink` (one batch = one transaction: `payloads` by hash with ON CONFLICT DO NOTHING, then
`messages` by (id, step), both fixed-shape `unnest` inserts, prepared-statement cache on), batch
100 / 10 ms / commitConcurrency 4 / maxBacklog 6400, 20 000 messages of 158 bytes after a 1 000
message warm-up, tables truncated before the measured run, pool size 6. Latency = `save()` call to
its future completing (the durable ACK: the batch's commit returned). "sync" is
`synchronous_commit=on` (the default), "async" is `off` per connection; both observed on the
server with `show synchronous_commit` and written into the JSON.

This is the prototype's "batched persistence" spike rerun on native Linux (ARCHITECTURE §13
Phase 0, spike 1); the runtime around it (HTTP, pipeline, XSLT) does not exist yet, so the
numbers measure the store path alone and are not comparable with the end-to-end request numbers
of the STEP 1.5 / STEP 2 sections below.

### Baseline (concurrency 64, three runs in a row; the markers below carry the median run)

| run | config | msg/s | p50 | p95 | p99 | max |
|---|---|---|---|---|---|---|
| 1 | sync | 9969 | 5.86 ms | 10.80 ms | 18.10 ms | 37.5 ms |
| 1 | async | 14322 | 3.90 ms | 8.42 ms | 12.33 ms | 17.2 ms |

(Run 1's raw JSON was not retained — overwritten in `bench/out/` by run 2; the row is the harness's printed summary. Runs 2 and 3 and every supplementary run are backed by their JSON.)
| 2 | sync | 11865 | 4.63 ms | 9.96 ms | 16.61 ms | 28.9 ms |
| 2 | async | 14824 | 3.70 ms | 8.50 ms | 12.49 ms | 18.7 ms |
| 3 | sync | 12326 | 4.70 ms | 9.26 ms | 12.05 ms | 20.2 ms |
| 3 | async | 14996 | 3.62 ms | 7.91 ms | 12.81 ms | 18.3 ms |

Run 2 is the median on all four baseline metrics and is the recorded baseline:

These two markers measure the pre-V11 batch shape (payloads + messages, two statements); FIX-114 re-records them after P2-07c (D-097).

<!-- baseline:batched-persistence-sync {"machine": "cloud VM, 4 vCPU", "throughputPerSecond": 11865, "p99Ms": 16.61} -->
<!-- baseline:batched-persistence-async {"machine": "cloud VM, 4 vCPU", "throughputPerSecond": 14824, "p99Ms": 12.49} -->

Run-to-run spread over the three runs: throughput ±10 %, p99 12.0–18.1 ms sync (±25 %) and
12.3–12.8 ms async. The regression gate (`bench/check_regression.py`) therefore fails at > 20 %
less throughput but only at > 50 % higher p99 (D-037). A run is compared only with the baseline
of its own machine label; the GitHub-hosted runner gets its own baseline from its first artifact
(BENCH_MACHINE in ci.yml; follow-up row B-1).

### Phase 0 baseline — github-hosted runner (B-1)

Date: 2026-10-18. Commit b4523bb, CI run 39092152535 (workflow_dispatch), job "bench (native
Postgres on the runner)". Machine: GitHub-hosted `ubuntu-latest` (4 vCPU, shared), PostgreSQL 16.15
from the runner image started natively (`systemctl start postgresql`, default configuration),
Temurin 25.0.4 — both versions as printed in the job log (Flyway's "PostgreSQL 16.15", JAVA_HOME `jdk/25.0.4-1`). Same harness and configuration as the VM baseline above (`bench/run.sh`, batch
100 / 10 ms / 4, c=64, 20 000 messages, 1 000 warm-up). One run — the first artifact, as row B-1
asked; the numbers are read from the job log (`bench-results` artifact 12122908999 holds the JSON,
but its blob storage is not reachable from the coordinator's VM).

| run | config | msg/s | p50 | p95 | p99 | max |
|---|---|---|---|---|---|---|
| 1 | sync | 18212 | 3.03 ms | 6.66 ms | 9.88 ms | 12.8 ms |
| 1 | async | 19032 | 3.00 ms | 6.33 ms | 7.91 ms | 9.7 ms |
| 2 | sync | 20243 | 2.20 ms | 5.94 ms | 19.20 ms | 98.3 ms |
| 2 | async | 22842 | 2.56 ms | 5.00 ms | 6.03 ms | 12.2 ms |
| 3 | sync | 15491 | 3.01 ms | 7.70 ms | 22.28 ms | 102.3 ms |
| 3 | async | 20762 | 2.48 ms | 6.29 ms | 8.92 ms | 12.0 ms |
| 4 | sync | 17295 | 3.03 ms | 7.15 ms | 9.53 ms | 14.9 ms |
| 4 | async | 18348 | 2.94 ms | 7.05 ms | 9.02 ms | 10.8 ms |

Run 2 (CI run 39092705480, the first with the run-1 markers armed) was 11 % faster and went red
on p99: one 98 ms batch (a shared-runner stall) put the sync p99 94 % above run 1. Latency on a
shared runner is not a gate (FIX-4, D-039): the runner's markers carry throughput only, so the job
fails on throughput > 20 % below run 1 and nothing else; p99 stays gated where the marker carries
it, i.e. the coordinator's VM runs above. The throughput baseline keeps run 1, the lower and so the
more lenient of the two (floor 14 570 msg/s). Run 3 (CI run 39093734224, first with the throughput-only
markers) had another ~100 ms stall and 15 % less sync throughput than run 1 — passed, as intended;
the runner's spread (15.5–20.2k msg/s sync over four runs; run 4 is the gate run 39095635572) is the reason the band is as wide as it is. D-037's other remedy, a 3-run median, was not chosen:
a median baseline does not absorb a per-run stall, it only moves the number the stall is compared with.

These two markers measure the pre-V11 batch shape (two statements); FIX-114 re-records them after P2-07c (D-097).

<!-- baseline:batched-persistence-sync {"machine": "github-hosted runner", "throughputPerSecond": 18212} -->
<!-- baseline:batched-persistence-async {"machine": "github-hosted runner", "throughputPerSecond": 19032} -->

The runner is faster than the cloud VM on this path (sync +53 %, async +28 %; p50 3.0 vs 4.6 ms —
presumably disk / fsync, not measured),
which is exactly why a run is only ever compared with the baseline of its own machine (D-037). A
single-run baseline on a shared runner is thin; the very next run showed it (run 2 above, FIX-4).

### Supplementary (same build, same day; not baselines)

| run | config | msg/s | p50 | p95 | p99 | max |
|---|---|---|---|---|---|---|
| c=8, 5 000 msgs | sync | 2293 | 3.17 ms | 5.95 ms | 7.96 ms | 11.5 ms |
| c=8, 5 000 msgs | async | 3901 | 1.79 ms | 4.26 ms | 6.37 ms | 14.5 ms |
| c=64, window 2 ms | sync | 12007 | 4.91 ms | 9.28 ms | 12.85 ms | 35.1 ms |
| c=64, window 2 ms | async | 15221 | 3.59 ms | 8.11 ms | 11.17 ms | 15.1 ms |
| c=256 | sync | 22251 | 10.01 ms | 19.74 ms | 38.57 ms | 43.8 ms |
| c=256 | async | 24208 | 9.50 ms | 18.70 ms | 29.55 ms | 33.8 ms |

### Reading the numbers — the "< 5 ms p99 durable ACK" question (spike 1)

- **Answer: no, not at c=64 on this VM.** The durable-ACK p99 with `synchronous_commit=on` is
  12–18 ms at c=64 (p50 4.6–5.9 ms) and 8.0 ms at c=8 (p50 3.2 ms). `synchronous_commit=off`
  brings p99 to 12.5 ms at c=64 and 6.4 ms at c=8 — a ~35 % win in throughput at c=64 but no
  order of magnitude, as the prototype's native WSL2 run already hinted ((e)−(c) = 14 % there).
- **The 10 ms window is not what costs the latency.** A 2 ms window gives the same distribution
  (p50 4.91 vs 4.63 ms, p99 12.85 vs 16.61 ms, inside the run-to-run spread): at c=64 a batch
  closes as soon as the previous commit of its slot returns, and each commit of ~60 rows takes
  ~4–5 ms on 4 vCPU (two unnest inserts, one of them ON CONFLICT on the hash index, fsync of the
  WAL). Commit time, not the window, bounds the ACK latency.
- **Throughput scales with offered load, latency pays for it.** c=256 fills batches by size
  (100 rows): 22 251 msg/s sync / 24 208 async, with p50 ≈ 10 ms and p99 30–39 ms — queueing in
  the backlog (maxBacklog 6400 was never hit; 0 `OverloadedException`). 20 000 msg/s is well above
  anything Phase 1 needs (ARCHITECTURE §14 targets), so the batcher settings 100 / 10 ms / 4 stay
  the defaults; the admission semaphore (P0-11) is what keeps p99 bounded under load, not a smaller
  batch.
- Consequence for ARCHITECTURE §13: the "< 5 ms p99" figure is not met by durable commit on this
  class of machine; a durable ACK is p50 ≈ 4–6 ms, p99 8–18 ms here. Flows that need a sub-5 ms ACK use the
  non-durable reply mode (ACK before commit; ARCHITECTURE §4 delivery modes), which the HTTP
  component implements in Phase 1. Recorded as a measured fact, not as a deviation: the document
  asked the question, it did not promise the number.

Row-count check: every run ended with exactly `messages` = saved rows (`rowsInMessages` in the
JSON; the harness exits 2 otherwise) and 0 failed saves (exit 3 otherwise, first cause printed).

---
# Bench results — writer v2 + delivery modes (STEP 1.5)

Date: 2026-09-13. Same machine/client as the STEP 2 section below. Shape under test:
delivery-mode split (request/reply `/in` never awaits the store; `/in-async` = durable ACK),
content-addressed payloads, writer v2 (adaptive flush, commitConcurrency 4, fixed-shape unnest
inserts + prepared-statement cache). 20 000 messages @ c=64, 1 000 warmup, tables truncated
between runs.

**Measurement correction**: earlier single-request numbers used one `curl.exe` process per
request — on Windows that costs ~10–15 ms of process spawn + fresh connection per request.
Keep-alive measurements (`bench.py --c 1`) below are the real numbers; the old single-request
table is kept for history but is tool-bound, not server-bound.

## Throughput, Docker Desktop Postgres (same DB as STEP 2 section)

| run | config | req/s | p50 | p95 | p99 | vs old writer |
|---|---|---|---|---|---|---|
| (a) | persistence none | 2164 | 21.9 ms | 69.1 ms | 157 ms | ≈ (2649; ±15 % run variance) |
| (b) | batch 1 (per-message, ×4 concurrent) | 329 | 185.7 ms | 321 ms | 416 ms | 136 → **2.4×** |
| (c) | batch 100 / 10 ms | **1737** | 27.6 ms | 92 ms | 151 ms | 396 → **4.4×** |
| (e) | (c) + `synchronous_commit=off` | 3139 | 16.9 ms | 39.5 ms | 56.1 ms | 482 → 6.5× |

wal_sync for the measured window: (b) 8 198 (PG group-commit already collapses 120 000
single-row commits), (c) 1 020, (e) 72. Writer v2 removed the serialized-commit ceiling:
(e) now *beats* persistence-none run (a) — at this load, persistence costs only the fsync,
and (c) sits at 80 % of the no-persistence throughput.

## Throughput, native Postgres 16 in WSL2 (ext4, data dir on /, not /mnt/c)

| run | config | req/s | p50 | p95 | p99 |
|---|---|---|---|---|---|
| (b) | batch 1 (×4 concurrent) | 488 | 120.5 ms | 208.9 ms | 274.3 ms |
| (c) | batch 100 / 10 ms | 1278 | 45.6 ms | 100.2 ms | 150.7 ms |
| (e) | (c) + `synchronous_commit=off` | 1452 | 41.9 ms | 91.6 ms | 115.2 ms |

(run (a) touches no store — it is the same as the Docker table.) Native beats Docker on
per-message inserts (488 vs 329: real, faster fsync) but trails on batched throughput
(1278 vs 1737): the runtime runs on Windows, so every commit crosses the Windows→WSL2
localhost boundary, and Docker Desktop's port proxy happens to be cheaper per round trip.
Also note (e)−(c) is only 14 % native — ext4 fsync is genuinely cheap here — while the same
delta on Docker is 81 %, another hint that Docker Desktop's I/O virtualization, not the disk,
dominates its numbers.

## Single-request latency, keep-alive client (c=1, 300 requests, batch 100/10)

| path | Docker PG | native PG | target |
|---|---|---|---|
| `/in` (request/reply, persistence on, fire-and-forget) | p50 1.6 ms, p99 4.7 ms | **p50 0.9 ms**, p99 2.9 ms | p50 < 2 ms ✓ **met** |
| `/in-async` (durable ACK) | p50 12.3 ms, p99 23.2 ms | p50 7.4 ms, p99 22.4 ms | p99 < 5 ms ✗ not met here |
| `/in-async`, sync_commit=off | p50 11.0 ms, p99 18.3 ms | p50 5.9 ms, p99 18.1 ms | — |

- **Request/reply target met** on both setups: decoupling the reply from the store (delivery
  redesign) plus adaptive flush removed the whole persistence cost from the reply path.
- **Durable-ACK target not met on this topology — and the topology is the reason.** A raw
  single-row insert+commit measured *inside* WSL (pgbench, c=1) is **2.0 ms**; sync_commit=off
  only moves `/in-async` from 7.4 to 5.9 ms p50. So of the ~7.4 ms, ~2 ms is the actual commit
  and ~5 ms is the Windows-runtime→WSL2-Postgres boundary plus runtime scheduling hops.
  With runtime and Postgres co-located on Linux (the deployment model), p99 < 5 ms is
  plausible but remains **unverified** until measured there.
- The old 66→93 ms single-request numbers were curl-process-spawn artifacts on top of the old
  writer's batch window; both causes are gone.

Caveat as before: Windows 11 + Docker Desktop/WSL2; treat absolutes as machine-relative.

---

# Bench results — store-and-forward persistence (STEP 2)

Date: 2026-09-13
Machine: AMD Ryzen AI 7 350 (8C/16T), 31 GB RAM, Windows 11 Pro.
Postgres 16 in **Docker Desktop (WSL2 backend)** — see caveat below.
Runtime: JDK 21, Vert.x 4.5.10, Mutiny 2.6.2, flow `invoice` (validate → extractKey → route →
XSLT → HTTP echo on the same host), stage concurrency 16, 6 persisted steps per message.

Client: `bench/bench.py` (stdlib threads + keep-alive `http.client`; no hey/wrk on this box).
The client saturates around 8–10k req/s — none of the persisted runs get near that; run (a)
plausibly loses some headroom to it. 1000-request warmup before each measured run;
tables truncated and `pg_stat_wal` reset between runs.

## 20 000 messages, concurrency 64

| run | config | req/s | p50 | p95 | p99 | wal_sync | wal sync time |
|---|---|---|---|---|---|---|---|
| (a) | persistence none | **2649** | 19.1 ms | 51.0 ms | 88.9 ms | — | — |
| (b) | postgres, batch 1 (per-message insert) | **136** | 453.7 ms | 588.2 ms | 691.2 ms | 85 283 | 122.9 s (of 147.5 s wall) |
| (c) | postgres, batch 100 / 10 ms | **396** | 151.1 ms | 227.8 ms | 272.9 ms | 1 327 | 9.6 s (of 50.6 s wall) |
| (e) | (c) + `synchronous_commit=off` | **482** | 129.4 ms | 166.1 ms | 194.0 ms | 210 | 9.1 s* |

\* background walwriter still syncs; commits no longer wait for it.

Run (d) (= (c) without XSLT) was **skipped — XSLT does not dominate**: run (a) does 2649 req/s
with the identical XSLT step in the pipeline, and per-stage monitoring shows ~2 ms in `toPartner`.

Row-count check: every persisted run ended with exactly (20 000 + 1 000 warmup) × 6 = 126 000
rows in `messages`.

## Reading the numbers

- **Batching works on the fsync axis**: (b)→(c) cuts WAL syncs 64× (85 283 → 1 327 for 126 000
  row-writes) and gives 2.9× throughput. Per-message insert is fsync-bound exactly as designed —
  wal sync time ≈ 83 % of wall time in (b).
- **But (c) is not fsync-bound anymore**: sync time is 9.6 s of 50.6 s, and killing sync entirely
  (run (e)) only adds 22 %. The ceiling in (c) is the **serialized batch commit round-trip**:
  ~1 260 batches × ~35–40 ms each ≈ the whole wall clock. Message throughput ≈
  batchSize / commit_latency / 6 saves-per-message ≈ 400/s. The commit round-trip on this setup
  is dominated by Docker-Desktop I/O jitter and client-side statement handling, not the physical
  fsync (`pg_stat_wal` puts the average sync at 1.4 ms).
- Obvious levers, in order: commit batches **in parallel** (the writer currently concatenates;
  ordering per message id is the real requirement, not global ordering), enable
  `setCachePreparedStatements(true)` (each batch re-parses an 800-parameter INSERT today),
  fewer persisted steps per flow, larger batches.
- Expected on native Linux/NVMe: commit round-trip drops an order of magnitude; (b) stays
  fsync-bound, (c)'s ceiling moves accordingly. Numbers here are for machine-relative
  comparison only.

## Single-request ACK latency (low load, sequential, warmed, median of 15)

| config | median | min–max |
|---|---|---|
| persistence none | 14.6 ms | 10–22 ms |
| postgres per-message (1/0) | 66.5 ms | 34–129 ms |
| postgres batch 100/10 | 92.8 ms | 47–143 ms |

### Where the extra ~26 ms of (100/10) vs per-message goes

Timestamped writer trace (debug logs: ingress-emit → batch-flush → commit-done → ACK) for
single requests shows:

1. **emit → flush: 14–18 ms observed** (theoretical avg 5 ms for a 10 ms window). The flush
   timer fires on Mutiny's worker pool and the batch then hops to the PG event loop; on
   Windows each hop adds scheduling jitter on top of the window itself.
2. **flush → commit-done: 13–91 ms**, same cost class as the per-message path (it's the
   commit round-trip, average physical fsync only 1.4 ms — the spread is Docker Desktop).
3. **Trailing-batch serialization**: the previous request's step-2..6 saves form batches that
   commit serially *after* its ACK; when the next request arrives while one is still
   committing, its ingress batch queues behind it (visible in the trace). At single-request
   cadence this intermittently adds ~10–25 ms.

So the gap ≈ (window + scheduling hops ~13 ms) + (intermittent trailing-batch queueing
~10–25 ms); the commit itself costs the same in both configs. The planned **adaptive flush**
(commit immediately when the writer is idle) removes 1. and most of 3. at low load without
giving up batching under load.

## Backpressure / outage behaviour (Postgres paused with `docker pause`)

- Writer stream survives a paused Postgres (the earlier `BackPressureFailure` death is fixed);
  after `docker unpause` everything drains and new requests get 200. No hangs beyond the pause
  window, no lost stream.
- **Structural finding**: with ACK-coupled request/reply ingress, clients that aren't ACKed
  stop sending, and stage concurrency (16) caps in-flight messages — so the writer backlog
  during an outage is ≈ stage concurrency, and the 64-batch bound is never reached at sane
  settings. A paused DB manifests as *waiting clients* (max observed latency = pause length),
  which is backpressure working, not a bug.
- The 503 path itself was verified by raising stage concurrency (512) above writer capacity
  (batch 2 → 128 messages): 200 concurrent clients against a paused Postgres → 791 immediate
  503 + Retry-After, 209 accepted-and-durable requests completing after unpause, DLQ free of
  overload rejections, full recovery. The bound will matter for real once fire-and-forget
  consumers (pollers, queues) exist, where arrivals don't stop with the ACKs.
- Open question for later: during a *long* outage, ACK-waiting clients currently wait
  indefinitely (no server-side ingress-save timeout). Consider failing the awaited ingress
  save after N seconds with the overload signal so clients get 503 instead of a stalled
  connection.

## Docker Desktop / WSL2 caveat

Postgres runs in Docker Desktop on Windows (WSL2). Its virtualized I/O path makes commit
round-trips both slow (tens of ms) and noisy (13–91 ms for identical single-row transactions),
while reported per-sync WAL time stays ~1.4 ms. Absolute numbers in this file are only good
for comparing configurations on this machine; do not read them as Flottila's performance.
