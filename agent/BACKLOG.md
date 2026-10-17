# Backlog

One row per task. The coordinator takes the first `ready` row (all depends-on `done`), never
invents work outside this table without adding a row with a reason, and sets `done` only with a
`review: PASS` in PROGRESS.md. Status: `pending | ready | in-progress | done | blocked`.
Evidence = commit hash + PROGRESS entry. Phase gates are rows; the next phase stays `pending`
until the gate row is `done`. Hand-over gate definition: `docs/GATE.md`.

## Phase 0 — scaffolding, imports, spikes (ARCHITECTURE §13, PROTOTYPE-VS-ARCHITECTURE §5)

| Id | Title | Depends on | Status | Done when | Evidence |
| --- | --- | --- | --- | --- | --- |
| P0-01 | Root pom with modules `flottila-api`, `-core`, `-runtime`, `-components/http`, `-components/mock`, `-steps/xslt`, `-steps/validate`, `-testkit`, `-schemas`, `-cli`; JDK 25, Vert.x 5.1.x BOM, JUnit 5 + vertx-junit5, jqwik, JaCoCo, PIT, ArchUnit configured; old tree renamed to module `flottila-m1` (still builds) | — | ready | `mvn -B verify` green on JDK 25 with the old tests still running | |
| P0-02 | CI finalised: Temurin 25; `build` (unit, ArchUnit, JaCoCo floors + no-decrease, DCO check), `integration` (`-Pit`, Testcontainers), `bench` (native Postgres on runner, artifact, regression check), `mutation` (nightly PIT) | P0-01 | pending | all jobs green on `main`; floors enforced | |
| P0-03 | ArchUnit boundary tests in `flottila-archtest`: api/core use JDK + Vert.x `Future`/`Buffer`/streams only; components and steps depend on api only; only runtime touches Postgres/Kafka/S3/Valkey/ClickHouse clients; no cycles | P0-01 | pending | rules fail on a deliberate violation, pass on the tree | |
| P0-04 | `flottila-testkit` skeleton: fake `Runtime`, fake clock, in-memory stores, contract-suite base classes, blocked-platform-thread detector | P0-01 | pending | a sample flow test runs in < 100 ms | |
| P0-05 | Import `api`: `git mv` Envelope, Headers, Props, Key, Keys, Part, Views, Hashing; new `Body` (final class) + `BlobStore` interface + `Ack`, `Step`, `Stage`, `Pipeline`, `Component`, `Consumer`, `Producer` types | P0-03, P0-04 | pending | api tests green; ArchUnit green; `@since` on every public type | |
| P0-06 | Port the persistence batcher: logic + every existing test to `flottila-core` on Vert.x 5 `Future`; jqwik property test (no loss, no duplicate under any demand pattern); Postgres implementation in `flottila-runtime` | P0-05 | pending | old test cases pass unchanged in intent; property test green | |
| P0-07 | Import WorkQueue claim SQL + tests into `flottila-runtime`; add the same-key fast path (§7); two-worker ordering test; jqwik interleaving test | P0-06 | pending | per-key order holds under any claim interleaving | |
| P0-08 | Schema migrations: `db/init.sql` → `deploy/postgres/V1__*.sql` (Flyway, Apache 2); runtime applies migrations at start; Testcontainers tests apply them | P0-06 | pending | fresh Postgres → migrated → tests green | |
| P0-09 | Remove `flottila-m1`: delete the Mutiny skeleton (FlowVerticle, WorkerVerticle, Stages, MonitorVerticle, demo Main), drop Mutiny dependencies | P0-05, P0-06, P0-07, P0-08 | pending | no Mutiny on any classpath; build green | |
| P0-10 | Bench harness in `bench/`; native rerun of batched persistence on the cloud VM (Postgres as a process); baseline recorded with machine, date, config, commit — closes the "< 5 ms p99 durable ACK" question (spike 1) | P0-06, P0-08 | pending | `bench/RESULTS.md` baseline section exists with the numbers | |
| P0-11 | Spike 2: `RequestScope` (fork / join / cancel on virtual threads) + per-flow admission semaphore (requests and bytes) under 10k virtual threads; numbers in RESULTS.md | P0-05 | pending | cancel stops all children; admission never exceeds budget; numbers recorded | |
| P0-12 | JSON Schemas in `flottila-schemas`: flow, package, endpoint, `*.test.yaml`; `schemaVersion`; published; `invoice.json` converted into `packages/demo/flows/invoice-sync.yaml` | P0-05 | pending | schemas validate the demo package; marked `draft — review at hand-over` | |
| P0-GATE | Phase 0 gate: schemas merged, bench baseline recorded, coverage floors + PIT ≥ 80 % on api/core on the gate commit, every task `review: PASS` | P0-02 … P0-12 | pending | numbers in `bench/RESULTS.md` §Phase 0 gate | |

## Phase 1 — core runtime (ARCHITECTURE §13)

| Id | Title | Depends on | Status | Done when | Evidence |
| --- | --- | --- | --- | --- | --- |
| P1-01 | `BlobStore`: memory tier (default 1 MB), encrypted local spool, S3 tier (Testcontainers), node-wide byte budget as second spill trigger, per-flow max body; `Body` over it; streaming ingress while hashing | P0-GATE | pending | 50 MB body passes through without heap growth; contract suite for the store | |
| P1-02 | `RequestScope` + `ScopedValue` context (message id, tenant, trace id, deadline) + per-flow admission in `flottila-core`; HTTP 503 + Retry-After on overload | P0-GATE | pending | spike 2 numbers reproduced inside the real runtime | |
| P1-03 | Pipeline: `Step`/`Stage`/`Pipeline`, `FlowBuilder` from the schema config, patterns `transform`, `filter`, `split`, `route`, retry policy per step, step numbering kept from `proto-m1` | P1-02 | pending | milestone-1 pipeline builds from `invoice-sync.yaml` and runs in the fake Runtime | |
| P1-04 | `MessageStore` (Postgres): snapshot after every step with wall and CPU time, `message_keys`, recording levels `all/ends/errors/off`, payload `keep/metadata-only`, versions pinned per message | P1-01, P1-03 | pending | snapshot per step queryable by key; batcher from P0-06 in the path | |
| P1-05 | DLQ + retry + replay from any step (`REPLAY_OF`), DLQ retry | P1-04 | pending | partner failure → DLQ row by reference; replay from step n reproduces the run | |
| P1-06 | `http` component (server + client, sync and `/async` 202) and `mock` component; component contract suite in testkit (backpressure, re-open on retry, ACK only after durable write, spill, cancel); both pass | P1-02, P0-04 | pending | contract suite green for real and mock | |
| P1-07 | Steps: `xslt` (Saxon-HE, compiled by content hash, memoized views), `validate` (XSD), `extractKey`, `route` | P1-03 | pending | each step with unit tests and the body-kind declaration | |
| P1-08 | Delivery modes: sync request/reply, async at-least-once (durable save before ACK, save timeout → NACK/503), at-most-once | P1-04, P1-06 | pending | kill -9 ledger 2000/2000 on the async path | |
| P1-09 | Runtime admin API: `GET /health`, messages, timeline, step body, search by key, replay, DLQ list/retry, flows list, deploy endpoint | P1-05, P1-08 | pending | every docs/GATE.md check 6–7 route exists with tests | |
| P1-10 | Phase 1 measurements on the cloud VM: sync < 2 ms p50 platform overhead, durable ACK p99, 50 MB stream step, ledger; recorded | P1-07, P1-08, P1-09 | pending | `bench/RESULTS.md` §Phase 1 gate | |
| P1-GATE | Phase 1 gate: milestone-1 flow runs on the new core; numbers from P1-10; coverage floors + PIT; reviews PASS | P1-10 | pending | | |

## Phase 2 — the part the hand-over gate needs

| Id | Title | Depends on | Status | Done when | Evidence |
| --- | --- | --- | --- | --- | --- |
| P2-01 | Postgres `WorkQueue` wired to async flows: partition keys, lease expiry, same-key fast path, worker loop on virtual threads, `queue: postgres` in flow config | P1-GATE | pending | `invoice-async` delivered through the queue; ordering test across two workers | |
| P2-02 | `sftp` component (consumer: poll, download streaming into BlobStore, durable save, then delete; lease per endpoint in Valkey) + mock; contract suite with embedded SSHD. Verify first whether Vert.x 5.1 ships an SFTP client; if not, Apache MINA SSHD client as `BlockingConsumer` on virtual threads (record the decision) | P1-GATE | pending | file deleted only after durable save; kill between pick-up and delete loses nothing | |
| P2-03 | CLI `flottila validate`, `flottila deploy` (against the admin API), `flottila test` running `*.test.yaml` in-process with mocks (`reply`, `fail`, `delay`, `files`, `capture`); `package.yaml` | P0-12, P1-09, P1-06 | pending | docs/GATE.md checks 2 and 8 | |
| P2-04 | `secret://` resolver backed by environment variables (wallet table comes later) | P1-GATE | pending | demo flows carry no secrets | |
| P2-05 | Compose stack in `deploy/compose/`: runtime image, Postgres 17, Kafka (KRaft), Valkey, S3 (SeaweedFS, Apache 2, unless measured otherwise), demo SFTP server, partner echo; ClickHouse under profile `history`; `flottila deploy` at start | P2-01 … P2-04 | pending | `docker compose up -d` → health green in 60 s | |
| P2-06 | `packages/demo`: `invoice-sync`, `invoice-async`, `sftp-invoice`, XSLT, XSD, fixtures, `*.test.yaml` for each (happy path, partner 503 → DLQ, SFTP files) | P2-03, P2-05 | pending | docs/GATE.md checks 3–5, 8 | |
| GATE | Hand-over gate: every check in docs/GATE.md verified by command; `HANDOVER.md` written; state `GATE_MET` | P2-06 | pending | HANDOVER.md | |
| P2-S1 | Stretch: snapshots published to the Kafka `monitoring` topic | GATE | pending | visible with a console consumer | |
| P2-S2 | Stretch: ClickHouse Kafka-engine table consuming the topic | P2-S1 | pending | rows visible in ClickHouse | |

## Later (not broken down; after the owner's review at the gate)

Rest of Phase 2 (Kafka hand-over, durable `/xi` door, XI QoS, dispatcher, partner directory,
wallet, Valkey cache, inter-flow `call` / `flow://` / `queue://`), Phase 3 (Kafka → ClickHouse
ingest, pg_clickhouse, OTel, Micrometer, alerting), Phase 4 (components and SAP compatibility),
Phase 5 (extensions, API management, AI), and the control plane last (D-012).
