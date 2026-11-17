# Advisor recommendations on the owner's open items (2026-11-13)

Source: architecture advisor (Fable 5.2), read-only, asked by the owner on 2026-11-13. Not a decision; the owner decides.

- D-134 Kafka out of the core: accept; CLAUDE.md still names Kafka among the runtime clients (owner's file).
- D-135 retries on a schedule: accept with changes: decide it together with CONSIDER-13 (each retry reruns from the ingress, so
  earlier sends repeat in an ordinary outage); FIX-174 before hand-over (CLI horizon 10 min < default schedule 15 min); document
  and alert that a deferred row pins its journal segment with no age bound.
- D-136 retry keeps the id, replay mints one: accept.
- D-137, D-138: nothing open (D-138 O3/O4/O5 are built in 5b/5e/5c; gate certifies copies 1, copies-2 latency unknown until the two-node run).
- D-139 + D-141: lift the draft on handshake and framing now; keep it on replication frames until CORE-5b's review. Open risks
  without disposition: HMAC on the event loop (measure), no deadline or keepalive on an established link.
- D-140 attempts keep snapshots: accept with one check: reserve the prop name `attempt` at load (a step writing it breaks Snapshot);
  CORE-10's ClickHouse key carries `attempt` from day one.
- F-33 23 Postgres connections per node: accept; state the sizing rule nodes x (POOL_SIZE + 2) + reserve <= max_connections; re-measure at CORE-GATE.
- F-34 point 1 (deploy refusal above FLOTTILA_BLOB_DURABLE_MAX): accept as a named interim exception to D-110 and file a row for
  the streaming design; point 2 (no S3 raw archive): accept if CORE-10/13 document ClickHouse backup and restore; point 3: accept.
- CONSIDER-13 resume a retry from the failed step: accept, build after CORE-GATE and before the first production release; until
  then a deploy notice for at-least-once flows with more than one `to` and retries > 0.
- CONSIDER-14: defer until FIX-138b, then likely close.
- ISSUE-5 managed Postgres: option 2, a third `ha` value `managed` (operator-attested, shown at start and in health), with D-115, after CORE-GATE.
- Order: D-139/D-141; F-33; D-136, D-140, CONSIDER-14; D-135 with CONSIDER-13; F-34; D-134; ISSUE-5.
