# CORE-2 — ClickHouse payload compression and size bound (class B; owner 2026-11-04: run now, beside CORE-4a)

Architect 2026-11-04. Base: d2e2845. Owner's brief docs/CORE-REWORK.md O-3 (ClickHouse holds payloads, its compression is
meant to hold much larger ones than 256 KB; the bound is set by measurement) and Q-3/Q-9 (payloads arrive in batches from
the mover). A measurement row: no production code, no change outside the allowed paths.

## Allowed paths
`flottila-bench/src/main/java/io/flottila/bench/` (a new `ClickHouseCompressionMain` and helpers),
`flottila-bench/src/test/java/io/flottila/bench/` (a unit test for the payload generator only), `bench/run.sh` (a
`BENCH_SCENARIOS=clickhouse` entry), `bench/RESULTS.md` (a new section `## CORE-2 — ClickHouse payload compression`),
`agent/work/CORE-2-*`. No new Maven dependency: talk to ClickHouse over its HTTP interface with the JDK HttpClient.

## What to measure
- ClickHouse: `clickhouse/clickhouse-server:25.8.33.6` (the compose pin), started with `docker run` on this VM, default
  server settings, data on the VM disk; stopped and removed after the run. Record the image, the VM (Xeon 2.80 GHz × 4 vCPU,
  ~16 GiB), date and commit.
- Payloads: generated invoices in the demo's XML shape (packages/demo fixtures) and the same content as JSON; realistic
  variation (ids, dates, partners, amounts, 1..N lines; never one document repeated), seeded and reproducible. Two mixes:
  `mixed` (many partners) and `similar` (one partner's template).
- Sizes: 100 KB, 1 MB, 10 MB, 50 MB; counts finite and stated (e.g. 2 000 / 500 / 100 / 20 per size, at most ~2 GB raw
  per cell).
- Table per cell: MergeTree, `payload String CODEC(ZSTD(n))` for n ∈ {1, 3, 9}, ordered by (flow, created_at, id); inserts
  in batches as a mover would (≤ 64 MiB per insert, stated).
- Per cell: compression ratio (`system.parts_columns` data_uncompressed_bytes / data_compressed_bytes after
  `OPTIMIZE … FINAL`), insert peak memory and duration (`system.query_log`), merge peak memory (`system.part_log` or the
  OPTIMIZE query's memory), single-payload read time by id (p50/p99 over ≥ 200 random reads, client-side, warm), and
  whether any insert or merge failed (memory limit, max size).
- 3 runs per cell where a cell takes < 5 min, else 1 run stated as such; medians [min–max].

## Result
`bench/RESULTS.md` §CORE-2: a table size × mix × format × zstd level with the fields above, machine, date, configuration
and commit; then a short proposal: the largest payload size to store inline in ClickHouse (and the zstd level), with the
number that decides it (memory or read time), for the owner. A number not observed is not written.

## Checks
`mvn -B -ntp -q -pl flottila-bench -am verify` green; the run's raw output under agent/work/CORE-2-*.log and the JSON beside it.
