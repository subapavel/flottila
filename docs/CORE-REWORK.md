# Core rework — the owner's brief (draft, brainstorm open)

> **Status:** draft of 2026-11-03, written by the architect from the owner's brainstorm (chat,
> 2026-11-03). **Priority 1**: until row CORE-GATE in `agent/BACKLOG.md` is `done`, no other row
> is taken except "fix main" (WORKFLOW §3). **Not started**: every CORE row stays `pending` until
> the owner closes the brainstorm (CORE-0). Where this brief and `docs/ARCHITECTURE.md` differ,
> this brief is the owner's intent; ARCHITECTURE is rewritten to it in CORE-12, with a changelog
> entry per section. The owner may change any architecture decision when performance is not met;
> a rule is not an argument against the owner.

## 1. Why

The platform's core does not deliver the latency and throughput it is meant to. Measured on the
4 vCPU cloud VM (`bench/RESULTS.md` §GATE, §P2-08): durable ACK p99 7.9 ms at c=1, 158 ms at c=64,
766 msg/s at c=64 (closed loop, so ≈ concurrency / latency); ~12 Postgres transactions, 3–5 WAL
syncs and ~3.5 KB WAL per at-least-once message with a 132 B body. ARCHITECTURE §7 sizing
promises 5–20k msg/s with persistence on an 8-core node. A tmpfs WAL (the FLOOR) took only
0.8 ms off the c=1 p99: the cost is statement count, round trips, per-message transactions and
CPU contention, not fsync.

The causes the owner accepts as design holes:

1. Postgres carries everything on the hot path at once — queue rows (insert, update, delete per
   message), message bodies (`bodies`, bytea; a stop-gap "until S3" from D-058 that was never
   removed, then extended to step bodies by D-064), monitoring rows, dedupe, DLQ.
2. One transaction and one fsync per message; statements sent one after another
   (`PgIntake.save`: 4–6 sequential round trips holding one of 16 pooled connections).
3. The node that saved a message claims it back from the database although it holds it.
4. Work is found by a timed poll (1 s) instead of events.

## 2. What the owner wants (decisions so far)

| # | Decision | Record |
| --- | --- | --- |
| O-1 | **Kafka is an optional event broker add-on** (like SAP Integration Suite Advanced Event Mesh), not part of the core. No core path (at-least-once hand-over, monitoring ingest, replay, the `/xi` door) depends on Kafka. | CORE-9 |
| O-2 | **Postgres is the single source of truth and the single query surface.** The UI queries Postgres only. | — |
| O-3 | **ClickHouse holds the big data** (history, payloads) and is queried through **pg_clickhouse**. Its strong compression is meant to hold much larger payloads than the 256 KB in ARCHITECTURE §8 — the bound is set by measurement (CORE-2). | CORE-2, CORE-10 |
| O-4 | **S3 only as ClickHouse's cold tier** (older data moves there automatically by age). S3 is not the body store of the hot path. | — |
| O-5 | **No message bodies in Postgres** — ingress, step and parked bodies alike. | CORE-3..6 |
| O-6 | **A body journal on the node's local disk** (§3) holds the bodies until they are processed and in ClickHouse. Postgres keeps a small reference. | CORE-3..6 |
| O-7 | **Copies per environment, the operator's choice**: e.g. dev 1, test 1, prod HA 2 (local + a peer node). A single-node or single-copy production is not refused — the operator's risk, stated once at start and in the admin view. | D-101 (5), CORE-5 |
| O-8 | **Level A on Postgres**: group commit of concurrent saves, fewer round trips, a smaller transaction (no body), local hand-off, batched completion, step bodies and monitoring rows off the hot path. | FIX-95a/b, CORE-6..8 |
| O-9 | **Takeover by a node lease with self-fencing**, default 30 s, no false takeover; the message id on every outbound send; a send in flight at a crash is in doubt — resent with the same id or parked per flow. Duplicates are unwanted; only a real crash mid-send to a partner that does not dedupe can leave one, visibly. | D-101, P2-15 |
| O-10 | **Work is picked up by events, not a timed poll**; a slow reconciliation stays as the safety net. | D-102, P2-16 |
| O-11 | **Measure before rebuilding**, on a machine that separates client, node, partner and Postgres. | P2-13, P2-14, CORE-1 |
| O-12 | Rules the owner did not decide (e.g. the mock refusal in deployed environments) are reviewed after the rework. | CONSIDER-10 |

## 3. The body journal (the architect's sketch; the designer decides, CORE-3)

- **Segments**: append-only files per node (e.g. 64 MB or 1 s, whichever first), sealed and never
  rewritten. Record = version, length, CRC32C, message id, step, part, tenant key id, encrypted
  bytes (per-tenant AEAD; deleting a tenant key makes its records unreadable). A sealed segment
  ends with an index (message id + step → offset, length). The body key stays message id + step.
- **Write path of an at-least-once message**: body → journal writer → one write + one
  `fdatasync` for every record that arrived in the window (adaptive: immediate when idle) → with
  HA, the same on the peer → Postgres transaction with the record and the work item carrying the
  reference `(segment, offset, length, copies[])` (group-committed) → ACK. Order is kept: no work
  item without a durable body (D-082's invariant). A crash between the two leaves unreferenced
  bytes that disappear with their segment — no delete on the hot path.
- **Large bodies** (above a bound set by the designer): their own journal file, streamed,
  replicated the same way, never interleaved chunk by chunk in a segment.
- **Step bodies** (D-064) go to the same journal; the pipeline does not wait for them.
- **Reads**: the reference names the nodes with a copy; the local copy, else a peer's through an
  internal node-to-node read, else ClickHouse once the body moved there.
- **Retention**: a segment is deleted when nothing references it any more — its bodies processed,
  moved to ClickHouse, and no DLQ row or in-doubt item points into it. No row-level delete.
- **Node identity**: a node has a stable id and its journal directory; on restart it recovers
  its journal (CRC on the tail). In Kubernetes a StatefulSet with a persistent volume.
- **Node down**: with 2 copies, its work is taken over after its lease (D-101) preferably by the
  node holding the copy; with 1 copy, its items wait for it — delayed, never lost, never DLQ.
- **Copies lost**: a survivor re-replicates the segments it shared with the lost node to restore
  the configured count.

## 4. Order of work (rows in `agent/BACKLOG.md` §Core rework)

1. CORE-0 — the owner closes the brainstorm: §5 answered, this brief approved.
2. CORE-1 — the targets and the reference machine (§5 Q-1, Q-2).
3. Measurement baseline: P2-13 (transactions by role), P2-14 (latency phases, sustained
   throughput), CORE-2 (ClickHouse payload compression and size bound).
4. CORE-3 — body journal design [C]; CORE-4 single copy; CORE-5 replication.
5. CORE-6 — intake rework: body out of the transaction, group commit (FIX-95a), fewer round trips
   (FIX-95b), pool sizing.
6. P2-15 (D-101 takeover) and P2-16 (D-102 events, local hand-off); CORE-7 batched completion.
7. CORE-8 — step bodies and monitoring rows off the hot path.
8. CORE-9 — Kafka out of the core; CORE-10 — ClickHouse ingest; CORE-11 — migration off
   `bodies`.
9. CORE-12 — ARCHITECTURE rewritten to this brief (findings, changelog).
10. CORE-GATE — the targets met on the reference machine, recorded in `bench/RESULTS.md`.

Everything else waits until CORE-GATE: P2-10..P2-12, FIX-*, CONSIDER-*, the rest of Phase 2 and
later phases. Exception: "fix main".

## 5. Questions for the brainstorm (answers of 2026-11-03 in **bold**; still open where marked)

- **Q-1 Targets — open (architect's proposal, owner to confirm).** For the 4 vCPU VM, everything on
  one box, small bodies (≤ 10 KB), metadata-only recording: at-least-once durable ACK p99 < 5 ms
  at c=1; sustained at-least-once end to end ≥ 5 000 msg/s (open loop, flat backlog) with ACK
  p99 < 50 ms at that rate; sync platform overhead p50 < 1 ms at c=1 and < 2 ms at c=8. The
  numbers are confirmed or corrected once P2-14 has the baseline; the 8-core figure of
  ARCHITECTURE §7 is checked on a dedicated machine later.
- **Q-2 Reference machine: the 4 vCPU cloud VM for CORE-GATE**, with targets realistic for it; a
  dedicated machine only in a later phase, at the owner's call.
- **Q-3 ClickHouse ingest — open.** The owner asks whether a collector (Vector, Rust, MPL 2.0) or
  an own mover is better than (a) / (b). Architect's view: the body journal already is a durable
  local buffer, so a mover in the node (journal + Postgres references → batched ClickHouse
  inserts, idempotent, marks what ClickHouse holds so segments can go) does what Vector's disk
  buffer would, without a second copy of the data or a second process whose acknowledgement has
  to be fed back. Vector stays a candidate for logs and metrics.
- **Q-4 Hot window: until ClickHouse holds the bodies** (normally minutes to hours); the journal
  disk is sized for the ClickHouse outage it must ride through.
- **Q-5 HA copy: the ACK waits for the peer's receipt, not its fdatasync** (a message is lost only
  if both machines fail before either flushes).
- **Q-6 Sync flows — open**, owner: "probably not" the same journal for sync step bodies; tied to
  Q-9.
- **Q-7 Failed and in-doubt sends: as SAP PO** — retries at configurable intervals, a
  configurable number of times, until a successful answer, then parked (error status, manual
  restart). An in-doubt send (D-101 (4)) counts as a failed attempt and is resent with the same
  message id. Long intervals are scheduled in the work queue (a not-before time), never slept on
  a thread.
- **Q-8 ClickHouse down and the journal full: refusing new messages (503) is allowed** — it is a
  system error, alerted, to be fixed by the operator.
- **Q-9 Retention and recording levels — open, own brainstorm.** Owner: like SAP PO, one global
  setting for sync and one for async, overridable per flow. Because a flow is a free pipeline,
  not fixed stages, the levels are of the kind "inputs only", "inputs + errors", "every change"
  (a body stored only where a step changed it; see CONSIDER-2). It changes what is stored per
  step (today `all | ends | errors | off` × `keep | metadata-only` per flow, D-064 every step
  durable) and so the journal's load — to be settled before CORE-3.
