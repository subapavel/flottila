# Bench results — Phase 0 spikes, cloud VM (P0-10, P0-11)

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

Run 2 (CI run 39092705480, the first with the run-1 markers armed) was 11 % faster and went red
on p99: one 98 ms batch (a shared-runner stall) put the sync p99 94 % above run 1. Latency on a
shared runner is not a gate (FIX-4, D-039): the runner's markers carry throughput only, so the job
fails on throughput > 20 % below run 1 and nothing else; p99 stays gated where the marker carries
it, i.e. the coordinator's VM runs above. The throughput baseline keeps run 1, the lower and so the
more lenient of the two (floor 14 570 msg/s). Run 3 (CI run 39093734224, first with the throughput-only
markers) had another ~100 ms stall and 15 % less sync throughput than run 1 — passed, as intended;
the runner's spread (15.5–20.2k msg/s sync) is the reason the band is as wide as it is. D-037's other remedy, a 3-run median, was not chosen:
a median baseline does not absorb a per-run stall, it only moves the number the stall is compared with.

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
