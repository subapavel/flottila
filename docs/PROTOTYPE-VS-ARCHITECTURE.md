# Milestone-1 state (tag `proto-m1`) vs. Architecture v3.3 — what stays, what is replaced

Date: 2026-10-17. Companion to `docs/ARCHITECTURE.md`. Supersedes the earlier
"PROTOTYPE-VS-ARCHITECTURE-v3.3" note, which assumed a new repository.

Decision (D-002): **same repository, restructured in place.** Tag `proto-m1` marks the last commit
of the milestone-1 state. Files that stay are moved with `git mv` into their new module so their
history survives; files that are replaced are deleted in the commit that lands their replacement.
During Phase 0 the old single-module tree lived on as module `flottila-m1` so the build stayed green
until the last kept file had moved; task P0-09 removed the module on 2026-10-18. Where things went:
Headers/Props/Key/Keys/Views → `flottila-api` (git mv, P0-05); Envelope/Body/Part rewritten in
api (D-024); the batcher → `flottila-core` + `flottila-runtime` (P0-06); the WorkQueue claim SQL →
`flottila-runtime` (P0-07); `db/init.sql` → `deploy/postgres/V1__m1_schema.sql` (P0-08);
`flows/invoice.json`, `invoice-to-partner.xsl`, `sample-invoice.xml` → `packages/demo/` (P0-09,
converted in P0-12); `bench/bench.py` stays for P0-10 (it drives an HTTP ingress that does not exist yet; P0-10
decides whether it is rewritten or history-only); the root `docker-compose.yml` stays until
P2-05 replaces it. Rows of §2 whose new home does not exist yet (Flow replay numbering → P1-03,
Dlq semantics → P1-05, durableIngress semantics → P1-06, xxh3 Hashing → P1-01) are recovered from
the last tree that held the module, commit `dc85964` (the milestone-1 state itself is 3f81834,
tag `proto-m1` = fc6eb56).

## 1. What the milestone-1 state is

Single Maven module (`flottila-proto` in the pom; becomes `flottila-m1` in P0-01), Vert.x 4.5.10 +
Mutiny 2.6, JDK 21, Saxon-HE 12.4, Postgres via the reactive PG client. The milestone-1 flow
(HTTP → validate → extractKey → route → XSLT → HTTP) with a snapshot per stage, content-addressed
payloads, three delivery modes, durable DLQ, Postgres WorkQueue (SKIP LOCKED, partition keys),
batched persistence writer, replay from any step, kill -9 ledger verified 2000/2000. Bench results
in `bench/RESULTS.md` (Docker Desktop and WSL2 — round-trip-bound; only the native rerun counts).

## 2. Stays (git mv where done; rows without a new home yet are recovered from commit `dc85964`)

| File(s) at `proto-m1` | New home (ARCHITECTURE §12) | Notes |
| --- | --- | --- |
| `core/Envelope`, `Headers`, `Props`, `Key`, `Keys`, `Part`, `Views` | `flottila-api` | ARCHITECTURE §5 is this design. Package rename only. |
| `core/Hashing` | `flottila-api` | xxh3-64 via zero-allocation-hashing, SHA-256 fallback. |
| `runtime/BatchingWriter` + `BatchingWriterTest` | logic in `flottila-core`, Postgres implementation in `flottila-runtime` | Adaptive flush, N commits in flight, per-batch completion, bounded admission with overflow → rejection. Port `Uni` → Vert.x 5 `Future`; keep every test case; add the jqwik property test. |
| `runtime/WorkQueue` claim SQL + `WorkQueueTest` | `flottila-runtime` | `UPDATE … WHERE seq IN (SELECT … FOR UPDATE SKIP LOCKED)` taking the oldest row per partition key without an active lease; lease-expiry redelivery; two-worker ordering test. Add the same-key fast path (§7). |
| `db/init.sql` | `deploy/postgres/` as migration V1 | `messages`, `payloads(hash PK)`, `message_keys`, `work`, `dlq(body_ref, no FK)`. Later migrations add `versions`, `processed_ids`, `aggregates`, `routing_table`, `partner_directory`, `flows`, `wallet`, `audit`. |
| `runtime/Flow` replay numbering | `flottila-core` | Stage k consumes step k, produces step k+1; stage 0 = ingress identity; replay from k = run stages k..n with `REPLAY_OF`. Kept verbatim. |
| `runtime/Dlq*` semantics | `flottila-runtime` | Park by reference; a park never fails on write ordering; overload rejections and at-most-once drops never park. |
| `Component.Consumer.durableIngress` semantics | `flottila-api` consumer contract | The durable save before an irreversible ACK belongs to the consumer, with a save timeout → NACK. Reimplemented on `Function<Envelope, Future<Ack>>`. |
| `bench/bench.py`, `bench/RESULTS.md` | `bench/` | Baseline; rerun natively (cloud VM) in P0-10 and recorded as the Phase 0 baseline. |
| `flows/invoice.json`, `invoice-to-partner.xsl`, `sample-invoice.xml` | `packages/demo/` | First golden flow. P0-12 converted `invoice.json` into `packages/demo/flows/invoice-sync.yaml` (+ `endpoints/*.yaml`, `invoice-sync.test.yaml`, `package.yaml`) and deleted the JSON; the XSL and the fixture stay. |
| `docker-compose.yml` | `deploy/compose/` (P2-05) | Grows from Postgres-only to the full stack. |

## 3. Replaced (deleted when the replacement lands)

| Area | `proto-m1` | v3.3 | Why |
| --- | --- | --- | --- |
| Platform | Vert.x 4.5.10, Mutiny everywhere, JDK 21 | Vert.x 5.1.x, plain `Future` + `ReadStream`/`WriteStream`, JDK 25 | Decided Sept 2026; Mutiny operator chains proved brittle for the writer. |
| Threading | one verticle per flow (`FlowVerticle`, `WorkerVerticle`), Mutiny pipeline per message | one JDK virtual thread per message, verticles do I/O only, semaphore counting requests and bytes, `RequestScope`, `ScopedValue` | §4 review findings; Oct 2026 decisions. |
| Stage contract | `Multi<Envelope> apply(Multi<Envelope>)` in `Stage`/`Stages` | `Envelope apply(Envelope, Pipeline rest)`; cardinality = how often `rest` is called | No reactive stream once each message has its own thread. Patterns reimplemented as plain code. |
| Consumer SPI | `Multi<Envelope> stream()` + `reply()/fail()` + `durableIngress` | `Function<Envelope, Future<Ack>>` handler given to the consumer | A bare stream has no ack path. |
| Body | sealed `Bytes` / `Spilled` / `Empty` | one final `Body` (hash, size, `open()`, `whole()`) over a `BlobStore` with memory / encrypted spool / S3 tiers; objects keyed by message id + step | Storage leaked into the domain type; content-keyed objects cannot be deleted per retention nor streamed with multipart. |
| Body ingress | `ctx.body().buffer()` — whole request in memory | streaming into the BlobStore while hashing | Large files (§5). |
| Steps | `Step` returns `Uni<Envelope>`, XSLT on a CPU pool | `Step` returns `Envelope` on the message's virtual thread; steps declare body kind `whole / stream / split-assemble` | Measured per-step wall and CPU time replaces policing. |
| Monitoring path | local event bus → `MonitorVerticle` | worker → bounded batch queue → Kafka → ClickHouse; Postgres last-N-hours by the same batcher | The event bus drops silently at 1,000 buffered. |
| Queues | Postgres only | Postgres default per flow, Kafka per flow for volume / fan-out / replay; XI EOIO always Postgres | Agreed Oct 17, 2026 (§7). |
| Runtime record | `Runtime(vertx, components, messages, monitoring, cpuPool, blockingPool, xml)` | adds BlobStore, Clock, id generator, caches, wallet, services registry; still build-time only | §6, §11. |
| Modules | one jar, packages `core / runtime / components / demo` | `flottila-api`, `-core`, `-runtime`, `-components/*`, `-steps/*`, `-sap`, `-testkit`, `-cli`, `-control`, `-schemas`; ArchUnit enforces direction | §12. |
| Flow config | ad-hoc JSON (`invoice.json`) | JSON Schema (Phase 0, done in P0-12: `flottila-schemas`, D-040); packages with `package.yaml` / `package.lock`; `*.test.yaml` beside each flow | §9, §11. |
| Components | `HttpComponent` only, no mock | every component with its mock, both passing the contract suite | §11. |
| Tests | 9 tests against the compose Postgres | testkit with fake Runtime, contract suites, flow tests from YAML, Testcontainers, golden tests | §11. |
| Admin API | hand-written routes in `demo/Main.java` | runtime admin API (Phase 1); control plane last | docs/GATE.md. |

## 4. Known warts at `proto-m1` (do not carry)

- Double step-1 write on the non-queued at-least-once path (TODO in `FlowBuilder`), absorbed by
  `ON CONFLICT DO NOTHING`.
- Echo backend ack body made static so dedup is demonstrable — demo-only.
- `Body` implementations as final classes with a `volatile Long hash` cache — the new `Body` is
  one class anyway.
- Bench numbers from Docker Desktop / WSL2 are round-trip-bound; only the native rerun counts.

## 5. Phase 0 order

As rows `P0-01` … `P0-GATE` in `agent/BACKLOG.md`: root pom and modules (old tree becomes
`flottila-m1`), CI, ArchUnit, `api` import, batcher port, WorkQueue import, migrations, removal of
`flottila-m1`, native bench baseline, the two spikes, the JSON Schemas.
