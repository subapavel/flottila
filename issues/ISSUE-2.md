# ISSUE-2 — Durable ACK p99 < 5 ms is missed at every concurrency on the cloud VM
Task: P1-10d (Phase 1 measurements, GATE.md check 9)
Date: 2026-10-28

## What is needed
A decision on the durable-ACK target. Measured at e129c77 (bench/RESULTS.md §Phase 1 gate, native
Postgres 16, `synchronous_commit` on, the `bodies` insert included per D-061): p99 8.82 ms at c=1,
27.4 ms at c=8, 135 ms at c=64 (p50 4.2 / 11.9 / 60.9 ms). Options:
1. Keep the durable ACK as is and restate the target as measured (p99 ≈ 9 ms unloaded on 4 vCPU,
   default Postgres) — a durable ACK costs the commits it costs.
2. Tune the durable path in a later phase (today the bodies persist and the batched ingress record run
   in parallel, then the work-item enqueue commits — two commits in sequence, Delivery.java:257-272; Postgres `commit_delay`/group commit; faster storage) and re-measure.
3. Point latency-critical producers at the at-most-once mode the architecture already offers
   (ARCHITECTURE §4 delivery modes: ACK immediately, nothing durable before processing) and keep a
   sub-5 ms ACK target only there; the durable ACK keeps option 1's number. Cost: a failure is logged
   and dropped, no DLQ, replay is metadata only (§4 table). Its ACK latency was not measured here.

## Why the agent cannot decide it
The target is a product promise in GATE.md; relaxing a gate threshold or changing what the durable
ACK guarantees is the owner's (WORKFLOW §6, "a missed gate").

## What was tried
Three native runs per concurrency (c=1, 8, 64) with nothing else on the box; the P0-10 batcher pair
re-run in the same session (p99 13 ms at c=64, in P0-10's range). P0-10 had already measured the
batcher alone at p99 ≈ 8 ms at c=8 — the ingress path adds the bodies insert and the enqueue commit.

## Default if the owner does not answer
Option 1 for the Phase 1 gate: the measured numbers stand as the recorded durable-ACK latency and
the gate review sees this issue (ARCHITECTURE §13 names "durable ACK < 5 ms p99 native" for the gate,
so P1-GATE lists ISSUE-2). Option 2 becomes a Phase 2 CONSIDER row; no task is blocked.

## Owner's answer
(left empty; the owner fills it in and the next session reads it)
