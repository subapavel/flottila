# Hand-over gate — "Pavel can test the first real iFlow"

Agreed 2026-10-17. Autonomous execution (Part B) stops at `GATE_MET` when every check below holds,
each verified by the command given and recorded in `HANDOVER.md`. The gate sits after Phase 1 and
covers the Phase 2 pieces the two demo flows need; see `agent/BACKLOG.md`.

## Checks

1. **Stack.** `cd deploy/compose && docker compose up -d` starts runtime, Postgres, Valkey,
   S3, plus two demo helpers: an SFTP server with an inbox and a partner echo endpoint.
   `GET /health` is green within 60 s. ClickHouse is in the same file under profile `history`
   (present, not wired — see "Postponed"); Kafka, an optional add-on, under profile `kafka` (CORE-9).
2. **Deploy from git.** `packages/demo` in the repo (flows, XSLT, XSD, fixtures, `*.test.yaml`,
   `package.yaml`) is deployed at stack start by `flottila deploy`; `flottila validate` passes on
   it. Every message records the package version it ran with.
3. **Flow A, sync** — `invoice-sync`: HTTP → validate (XSD) → extractKey → route → XSLT → HTTP
   partner → 200 carrying the partner's reply.
4. **Flow A, async** — `invoice-async`: same pipeline behind `/invoice/async` → 202 after the
   durable save → Postgres WorkQueue → delivery to the partner.
5. **Flow B** — `sftp-invoice`: file in the SFTP inbox → poller (lease in Valkey) → durable save →
   file deleted → XSLT → HTTP partner. Killing the runtime between pick-up and delete loses
   nothing and duplicates nothing.
6. **Snapshots.** `GET /admin/messages/{id}` returns the step timeline with wall time per
   step and `cpuNanos` present, null where the JVM cannot measure a virtual thread (F-21; ISSUE-3 option 2,
   2026-10-30 — CPU per flow and step by JFR sampling follows as FIX-108); `GET /admin/messages/{id}/steps/{n}/body` returns the payload as it was after step n;
   `GET /admin/messages?key=invoiceId:4711` finds the message by business key.
7. **Replay and DLQ.** `POST /admin/messages/{id}/replay?from=3` runs steps 3..n as a new message
   marked `REPLAY_OF`. Partner down → retries per flow policy → DLQ row at `GET /admin/dlq`;
   `POST /admin/dlq/{id}/retry` delivers once the partner is back.
8. **Tests.** `flottila test packages/demo` runs the `*.test.yaml` cases next to each flow — happy
   path, partner 503 → DLQ, SFTP fixture files — in-process in seconds; CI runs the same command.
9. **Evidence.** `bench/RESULTS.md` holds, measured on native Linux: kill -9 ledger 2000/2000
   (sent = final-step rows, DLQ 0, work 0); sync request/reply platform overhead < 2 ms p50;
   at-least-once durable ACK p99 as measured, with the machine named (F-14: < 5 ms is the
   Phase 2 tuning target, P2-08, not a Phase 1 condition); a 50 MB body through a stream step without heap
   growth; coverage and mutation numbers of the gate commit. `HANDOVER.md` exists.

## HANDOVER.md contents

How to start the stack; how to run the two flows by hand (curl sequence, SFTP drop); how to run
the tests; every deviation from `docs/ARCHITECTURE.md` with its finding id; decisions marked
`draft — review at hand-over`; open issues; the next three tasks.

## Postponed (not in the gate, not removed)

- Control plane and UI — testing happens through the runtime admin API. The control plane is among
  the last steps of the whole plan (owner's decision, 2026-10-17).
- The git-watching deployment — `flottila deploy` from the CLI against the repo checkout is
  "deployed from git" for now.
- ClickHouse — container present under a profile, nothing feeds it. History and search come from
  Postgres (last-N-hours). Wired in Phase 3.
- Kafka as a flow queue — the demo flows use the Postgres queue (the default anyway).
- XI adapter, dispatcher, partner directory, wallet table — Phase 2. Secrets for the demo (SFTP
  password, partner URL) come from environment variables through the same `secret://` syntax the
  wallet will use.

## Stretch — only after checks 1–9 hold, never blocking

- Snapshots also published to the Kafka `monitoring` topic (ARCHITECTURE §8 write path).
- A ClickHouse Kafka-engine table consuming that topic.

HANDOVER.md states which stretch items were reached.

## CORE-GATE — the core rework (owner 2026-11-03, `docs/CORE-REWORK.md`, CORE-1)

**Machine and settings.** The 4 vCPU cloud VM of `bench/RESULTS.md` §GATE (Intel Xeon @ 2.80 GHz
× 4 vCPU, 15–16 GiB, PostgreSQL 16, Java 25; a reprovisioned VM of another shape is not the
reference), client, node, partner and Postgres on the one box, no CPU pinning, **one node for conditions 1–3
(two nodes for condition 4), journal copies 1**, body level `none` with no switches and no step `keep`. Conditions 1–4 hold
at both body sizes, 132 B and 10 KB. **Pass rule:** each cell is 3 runs on the CORE-GATE commit;
a target is met when the **median** meets it; min–max is reported beside it. Numbers go to
`bench/RESULTS.md` §CORE-GATE with date, configuration and commit. The targets are working targets
(Q-1). No measurement of the old core comes first (D-104): if the CORE-GATE measurement misses
a target, the steps are measured (P2-13, P2-14 (a)) before anything changes; a change of target
is the owner's and goes into this section with its date.

1. **Durable ACK latency.** At-least-once HTTP → 202 after the durable save, closed loop, c=1,
   20 000 messages after 1 000 warm-up (`NodeBenchMain --scenario ack`): p99 < 5 ms.
2. **Sustained throughput.** Open loop (CORE-15 harness), at-least-once end to end (ingress →
   passthrough → partner): offered 5 000 msg/s for 10 min after a 1 min ramp; every request
   answered 202 (no 503, no timeout); backlog = accepted − partner receipts, its growth over the
   10 min < 1 % of the accepted; ACK p99 < 50 ms, timed from each request's scheduled send time.
3. **Sync overhead.** The P1-10 harness, HTTP → passthrough → raw Vert.x echo partner, 20 000
   requests after 1 000 warm-up, overhead = p50(flow) − p50(raw) with the same client: < 1 ms at
   c=1 and < 2 ms at c=8.
4. **No loss.** The kill -9 ledger (2 000 messages, concurrency 8, node 1 killed mid-run and
   restarted, journal copies 1 on each node — node 1's items wait for its restart): sent =
   final-step rows, DLQ 0, work 0, in 3 of 3 runs, with the body journal as the only body store.
5. **No body in Postgres.** A schema test: no `bytea` or large-object column outside a named
   allow-list; and a `pg_dump` taken after a condition-4 run contains no body marker of that run.
6. **No Kafka in the core.** With the compose `kafka` profile off: sync, async, replay and DLQ retry
   pass their end-to-end tests; nothing the `/xi` door will need (hand-over, ingest, replay)
   depends on Kafka; no core module has a Kafka client on its
   classpath (ArchUnit or dependency test).
7. **Floors and rows.** Coverage and PIT floors of CLAUDE.md on the gate commit; CORE-0..CORE-15,
   P2-15 and P2-16 `done` with `review: PASS`.
