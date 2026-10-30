# GATE — hand-over gate (docs/GATE.md checks 1–9)

Class G. Architect packet 2026-10-30, gate commit 5505b0f (code); CI workflow_dispatch on main queued for build + stack + bench +
mutation. HANDOVER.md and the RESULTS gate section are written by the architect from the evidence below; the gate-reviewer judges.

## Facts (scout, 5505b0f)
Ledger/sync/ACK/50 MB numbers in bench/RESULTS.md are from e129c77 (P1, :93-162) and 1d5d507 (P2-08b ACK) — the hot path changed
since (P2-01 WorkQueue, P2-08a Intake, P2-02D, P2-07) → re-measured on 5505b0f. Producers: LedgerIT (-Pit, kill -9), StreamHeapIT
(-Pit, -Xmx56m), bench/run.sh scenarios sync / ack (c=1/8/64). Package version per message: messages.versions, shown per step in
GET /admin/messages/{id} ("versions"). Routes: AdminServer.java:241-259; key=invoiceId:4711; timeline wallNanos/cpuNanos.

## Evidence run 1 — stack (verifier, this VM, target/GATE-stack.log, every command and its output tail)
deploy/compose/README.md steps verbatim (test token, test S3 keys): `docker compose up -d --build`, seconds to /health green.
1 services listed healthy (runtime, postgres, kafka, valkey, s3, sftp, echo); `--profile history up -d clickhouse` healthy.
2 `bin/flottila validate packages/demo` exit 0; `deploy` service exit 0; GET /admin/packages lists demo + its version; a message's
  timeline shows `versions` with `package: demo@<version>`.
3 POST fixtures/sample-invoice.xml to :8080/in → 200 with the partner's reply body.
4 POST to :8082/invoice/async → 202; the message reaches the echo (timeline final step; work table empty after).
5 SFTP: one file dropped → reaches the echo, gone from the inbox. Kill test: N=50 files with distinct invoiceIds (generated from the
  sample fixture under target/), dropped at once; `docker kill -s KILL` the node mid-run, `docker compose up -d` it again; repeat up
  to 5 rounds of 50 until the node log shows a deduped arrival (the INFO line when the accepted id differs) or report it never did.
  After each round: inbox empty, one ingress message per invoiceId (SQL count group by key), dlq 0, work 0, partner sends per
  invoiceId (count, duplicates named — an outbound resend after a kill is at-least-once, report it, do not judge).
6 GET /admin/messages/{id} (steps, wallNanos, cpuNanos); /steps/3/body; ?key=invoiceId:<the posted id> finds it.
7 POST /admin/messages/{id}/replay?from=3 → new message marked replay-of; stop the echo → async POST → retries → row in GET /admin/dlq;
  start the echo → POST /admin/dlq/{id}/retry → delivered.
8 `bin/flottila test packages/demo` exit 0, cases and wall seconds.
`docker compose down -v`; no volume left.

## Evidence run 2 — numbers (verifier, after run 1, quiet VM: no stack, load < 1 at start, recorded)
Commit 5505b0f, machine line as in RESULTS ("cloud VM, 4 vCPU", lscpu model, kernel, date).
a ledger: LedgerIT 3 runs (sent = final-step rows, DLQ 0, work 0 per run, ledger JSON paths).
b sync overhead p50/p99 at c=1 and c=8 (bench/run.sh sync, as the P1 section did).
c durable ACK p50/p99 at c=1/8/64, 20 000 msgs + 1 000 warm-up, one run each (bench/run.sh ack).
d 50 MB through a stream step: StreamHeapIT, 3 runs, heap growth per run.
Output: target/GATE-bench.log + JSON paths. Same harness and settings as the earlier sections; any deviation named.

## Done when
Every check 1–9 has an observed command line in the logs; CI on 5505b0f green with coverage per module and PIT api/core read from
the job logs; HANDOVER.md written; gate-reviewer MET.
