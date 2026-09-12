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
