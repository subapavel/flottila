# Hand-over gate — "Pavel can test the first real iFlow"

Agreed 2026-10-17. Autonomous execution (Part B) stops at `GATE_MET` when every check below holds,
each verified by the command given and recorded in `HANDOVER.md`. The gate sits after Phase 1 and
covers the Phase 2 pieces the two demo flows need; see `agent/BACKLOG.md`.

## Checks

1. **Stack.** `cd deploy/compose && docker compose up -d` starts runtime, Postgres, Kafka, Valkey,
   S3, plus two demo helpers: an SFTP server with an inbox and a partner echo endpoint.
   `GET /health` is green within 60 s. ClickHouse is in the same file under profile `history`
   (present, not wired — see "Postponed").
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
6. **Snapshots.** `GET /admin/messages/{id}` returns the step timeline with wall and CPU time per
   step; `GET /admin/messages/{id}/steps/{n}/body` returns the payload as it was after step n;
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
