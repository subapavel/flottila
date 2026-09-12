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
