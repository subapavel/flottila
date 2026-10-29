> **Status:** binding design. The body below is the v3.3 text exported from the design document on
> 2026-10-17, unchanged. The *Amendments* agreed in Part A on 2026-10-17 come first and override the
> body where they differ. During autonomous execution the coordinator edits this file on findings
> (`docs/WORKFLOW.md §7`) and adds a line to the changelog for every change.

## Changelog

| Date | Version | Change | Finding |
| --- | --- | --- | --- |
| 2026-10-17 | 3.3 | Exported from the design document; amendments 1–8 added from the Part A brainstorm | — |
| 2026-10-18 | 3.3 | §12: `flottila-testkit` depends on `api` only (core's tests use it; a testkit → core dependency is a reactor cycle); the golden-test runner moves to `flottila-cli` or a later module | F-1 (minor) |
| 2026-10-18 | 3.3 | §14: "Flow config JSON Schema" ticked as drafted — `flottila-schemas` v1 schemas for flow, package, endpoint, `*.test.yaml` (amendment 4 delegated the design to P0-12; D-040); body text unchanged | — |
| 2026-10-18 | 3.3 | §6: the step SPI (`StepFactory`, `StepConfig`, `StepResources`, `Expressions`) lives in `flottila-api`, as §7 and §12 require for pluggable steps; the built-in `route` sets a prop (`route.prop` / `routes[].when` / `set` / `otherwise`, schema v1) and `to … byProp` acts on it — the form where a branch is itself a pipeline named `route:<branch>` is deferred until a flow needs it; `split` has a stage but no flow-file shape yet (D-043) | — |
| 2026-10-19 | 3.3 | §4: the at-least-once durable save is the ingress snapshot with its payload, written and awaited by the consumer's delivery handler before the ACK whatever the flow's recording level says; the worker runs the pipeline without stage 0 for a message saved that way (D-049) | F-2 (minor) |
| 2026-10-26 | 3.3 | §7 WorkQueue row: the lease is renewed while the item runs (not derived from a producer timeout), every claim counts an attempt that fences the lease, and an item past its attempts bound is parked or, when the park fails, held — held rows block their key and wait for a human (D-055, P1-08b) | — |
| 2026-10-26 | 3.3 | §4: until a `RequestScope` wraps each delivery, a stop's cancel is the interrupt of the message's virtual thread at the stop deadline, which wakes its awaits and cancels the children of any scope opened on it; a cancelled delivery answers a retryable NACK (D-056, P1-08c) | F-3 |
| 2026-10-26 | 3.4 | §7, §12: components and steps are wired explicitly by `runtime.Main`, not discovered through ServiceLoader; runtime depends on core and schemas, and `runtime.Main` wires the shipped components and steps (http, xslt/xpath, xsd); components depend on api only; ArchUnit: only `Main` names components or steps, nothing in runtime names the mock component (D-057, P1-09b) | F-4 |
| 2026-10-26 | 3.4 | §9 item 3: a redeploy validates and builds the whole package before anything stops; each flow then swaps stop-then-start (old worker, old delivery with the stop deadline and old consumer, new consumer, new worker), all flows in parallel, with a brief refusal window per flow; a failed start rebuilds the old version; deployed state is in memory until P1-09c (D-057, P1-09d2) | F-5 |
| 2026-10-26 | 3.4 | §5: until S3 (P2-05), the at-least-once save awaits `BlobStore.persist`, which moves bodies up to `FLOTTILA_BLOB_DURABLE_MAX` (1 MiB) into Postgres `bodies` keyed by message id + step; a flow whose max body is above the bound is refused at deploy (D-058; the refusal lands with P1-09d1b, durability with P1-09h) | F-6 |
| 2026-10-26 | 3.4 | §9: a package folder is structure only (ids unique per package, `folder.yaml` keeps the namespace); a flow is addressed `<package>/<flow>`; a redeploy will swap only flows whose effective hash changed (lands with P1-09d2) (D-060) | — |
| 2026-10-27 | 3.4 | §6 Retry per step: a DLQ row is written only after its body is persisted (`BlobStore.persist`, D-061); a park whose body cannot be persisted fails and the item reruns, past its attempts bound parking the already durable ingress; sync flows never park, they return the error (D-045, D-062) | F-7 (major) |
| 2026-10-27 | 3.4 | §6 replay from step n: noted only — a replay from step n ≥ 2 after a restart reads node-local snapshot bodies; P1-09c answers 409, the text changes with P1-09c if that becomes the documented behaviour | F-8 (major) |
| 2026-10-27 | 3.4 | §9: a flow's effective hash (D-063) covers the files its build reads; XSLT run-time `doc()` / `unparsed-text()` reads are neither confined nor hashed until FIX-40 | F-9 (major) |
| 2026-10-27 | 3.4 | §9 item 3: a redeploy swaps only flows whose effective hash changed (D-063); all changed and removed flows stop before any changed or added flow starts, so the refusal window is up to the slowest stop; removing an at-least-once flow with queued or held items is refused (422); 409 means a deploy is already running | F-5 (major) |
| 2026-10-27 | 3.4 | §8: snapshot rows of a run are buffered in a per-run trail and written at run end (or when more than 256 are buffered) after their bodies persisted; a trail row keeps `body_key` only for a durable body, else it is metadata-only and counted (the at-least-once ingress row stays as D-058: saved beside its persist); only the step whose snapshot crosses 256 buffered rows waits (for that flush); item completion, the sync permit, the at-most-once landing and a replay or retry wait for the run's last flush (persists, then row writes) (D-064, D-066) | F-10 |
| 2026-10-27 | 3.4 | §6 Recording: replay and DLQ retry run the flow version deployed now and pin the versions they ran with; replay from any recorded step works after a restart (D-064, D-066) — F-8 closed; admin replay/retry status order 400 → 404 → 409 (flow not RUNNING here, retry already running) → 422 (step unrecorded or metadata-only, step beyond the flow, too large) → 429 (D-067) | F-11 (major) |
| 2026-10-28 | 3.4 | §9 Deployment mechanics: the deployment record is a Postgres row without bytes, the archive is stored once in S3 by sha256 before the record (the commit point), and a node restores every recorded package before its admin API opens; a package it cannot restore is FAILED and /health is DOWN (owner's option A, D-070) | F-12 (major) |
| 2026-10-28 | 3.4 | §8 write path: trail rows wait FIFO for room in the node writer's backlog up to `maxWait` (10 s), keeping the run's permit, so overload shows as admission refusals; a row not written is logged at WARNING (message id, flow, step, cause) and counted; the at-least-once ingress row still fails fast; the backlog bounds tempo and is not sized against maxInFlight (D-071) — F-13 closed (FIX-67's c=64 rerun: no row lost) | F-13 (major) |
| 2026-10-28 | 3.4 | §7 Component SPI gains `listensOn(EndpointConfig)` (default empty); a deploy (and a restore) refuses in endpoints that would share a host:port with each other, the admin API or another package's running flow — one listener per port on a node (D-072, P1-09g) | — |
| 2026-10-28 | 3.4 | §7 Sizing and §13 Phase 1 gate: the durable ACK < 5 ms p99 is no longer a Phase 1 gate condition; the gate records it as measured (p99 8.8 / 27.4 / 135 ms at c=1/8/64, 4 vCPU, default Postgres 16) and < 5 ms becomes the Phase 2 tuning target (P2-08) — owner's answer to ISSUE-2, option 1+2 | F-14 (major) |
| 2026-10-28 | 3.4 | §7 WorkQueue row: the attempts token fences completion, the same-key fast path and hold as well as renew, so a holder whose lease expired and whose row was claimed again cannot delete or take over the new holder's row; the stale outcome is reported to the caller, never retried (D-074, Q-1) | — |
| 2026-10-28 | 3.4 | §7 Queues: the partition key is evaluated at the durable save from `partitionBy` (the flow's extractKey), the same-key fast path is bounded by `maxChain`, `queue: kafka` refused until the hand-over lands; §14 hot-partition item closed (D-075, P2-01) | — |
| 2026-10-28 | 3.4 | §7: sftp consumer on Apache MINA SSHD in Phase 2, blocking SDKs on virtual threads the component starts (no `BlockingConsumer` yet), poller lease held by the node (P2-02L), duplicates dropped at the save (P2-02D); §13 Phase 4 sentence follows; §4 executors and pollers sentences say transport I/O threads are not executors (D-076, F-15, P2-02) | F-15 |
| 2026-10-28 | 3.4 | §9 Flow configuration: artifacts found by `kind` in any folder (≤ 4 deep, slug names), file references are paths from the package root and stay inside it, a flow's hash no longer includes its file path (D-078, P2-02a) | — |
| 2026-10-28 | 3.4 | §11 capture is the mocks' always-on record, no behaviour key; §12 `flottila-cli` depends on every module and nothing depends on it (ArchUnit NOTHING_DEPENDS_ON_CLI), the flow-test runner lives there; §8 the Component SPI socket rule binds the node, not the CLI's admin client; §13 `validate`, `test`, `deploy` move to Phase 2, `package`/`migrate` stay in Phase 4 (D-079, F-16, P2-03a) | F-16 |
| 2026-10-28 | 3.4 | §11 flow tests: `flottila test` binds a mock per shipped scheme (no `mock://` endpoints, no `test` profile) and runs each case in-process on virtual time advanced only when the node looks quiet — a heuristic, since a monitor-blocked virtual thread is invisible to the scheduler MXBean (D-080, F-17, P2-03b1) | F-17 |
| 2026-10-29 | 3.4 | §9 Security: `secret://` backed by environment variables until the wallet (P2-04, D-081) | — |

## Amendments (Part A, 2026-10-17)

1. **§14 item 1 — closed.** The §4 review replacements are final: one plain JDK virtual thread
   per message bounded by a per-flow semaphore counting requests and bytes; objects keyed by
   message id + step; monitoring path worker → bounded batch queue → Kafka → ClickHouse; consumer
   contract `Function<Envelope, Future<Ack>>`; durable `/xi` door in Kafka.
2. **§14 item 2 — closed, except one.** The body is stored by reference into the BlobStore
   before the consumer's ACK: yes. Stream-step outputs are materialised node-locally: yes. The
   ABAP proxy behaviour on HTTP 503 / timeout stays open until the XI door is built (Phase 2).
3. **§14 item 3 — not a prerequisite.** The §5 and §10 adversarial reviews are not run before
   implementation; the implementing session builds from this document and records findings
   against real code in `docs/findings/`. The owner reviews them at the hand-over gate.
4. **§14 item 4 — delegated.** The flow, package, endpoint and `*.test.yaml` JSON Schemas are
   designed by the implementing session as drafts (`schemaVersion`, `draft — review at
   hand-over`), task P0-12.
5. **§12 — repository.** `github.com/subapavel/flottila`, public, Apache 2.0 + DCO, `main` only.
   The milestone-1 state is tag `proto-m1` in this same repository and the tree is restructured in
   place (`docs/PROTOTYPE-VS-ARCHITECTURE.md`), not copied into a new one. Maven groupId
   `io.flottila`; "Flottila" stays the working name until an MVP exists.
6. **§13 — gates and order.** Every phase gate additionally requires: coverage floors met
   (`api`/`core` 90 % line and branch; runtime, components, steps 75 %), PIT mutation score ≥ 80 %
   on `api`/`core`, and `review: PASS` on every task — measured on the gate commit. A hand-over
   gate ("the owner can test the first real iFlow", `docs/GATE.md`) sits after Phase 1 and
   includes the Phase 2 pieces two demo flows need; autonomous execution stops there. The control
   plane (Quarkus API, React UI, git watcher) moves to the end of the whole plan; until then the
   runtime admin API is the operator surface.
7. **§13 "Working method" — superseded** by `docs/WORKFLOW.md`: autonomous execution with
   coordinator / implementer / scout / reviewer roles, TDD, independent review of every task,
   one commit per task to `main`, no stop-and-report. The `javap` rule stays as the fallback after
   Context7.
8. **§11 — testing additions.** TDD from day 1 (red first, PROGRESS names the test); the reviewer
   agent judges every task's tests for being real; property tests (jqwik) for batcher, admission
   and WorkQueue ordering; hexagonal in substance only — SPI and store interfaces plus the ArchUnit
   dependency direction, no `domain/application/infrastructure` layering, interfaces only where a
   second implementation exists on day 1.

---

# Flottila Architecture v3.3 — Consolidated Summary & Implementation Plan

Oct 17, 2026 · @Pavel

## 1. Purpose

Flottila is an open-source enterprise integration platform where monitoring is the product: every message, every step and every payload is captured, searchable and replayable, and the runtime also delivers messages reliably and fast. This document consolidates the v3.2 architecture (Sept 15, 2026), the 15 accepted findings of the §4 runtime review, and the October 2026 decisions (Java/Vert.x confirmed over Elixir and Rust; virtual-thread-per-message; byte budgets; structured concurrency; policing replaced by measurement). It is the base for v3.3 and for the implementation plan in section 13.

Three things it is not: it does not repeat the rationale behind rejected options (v3.2 §2 keeps that), it does not define the flow config JSON Schema (first deliverable of the plan), and it does not describe the control-plane UI screens in detail.

## 2. Decisions that are final

The language question is closed: Java 25 on Vert.x 5.1, one runtime, no second language in the core. Elixir/BEAM and Rust were examined in October 2026 and rejected because the Java mapping worker is unavoidable (migrated PO/CPI Java and Groovy mappings, JCo, Saxon), which made a two-language platform complexity without a matching gain.

| Decision | Choice | Status |
| --- | --- | --- |
| Runtime language / platform | Java 25 (LTS), Vert.x 5.1.x core, plain `Future` + `ReadStream`/`WriteStream`; Mutiny not required | Final |
| Threading | One plain JDK virtual thread per in-flight message, bounded per flow by a semaphore counting requests and bytes; verticles do I/O only | Final (replaces VIRTUAL\_THREAD verticle per flow) |
| Persistence of truth | PostgreSQL is the single source of truth (state, queues, config, partner directory, wallet) | Final |
| Message backbone | Apache Kafka (KRaft, Apache 2) for async hand-over, ingest and replay; Redpanda only as a customer-supplied broker; NATS dropped | Final |
| Monitoring history & payload search | ClickHouse, mandatory core component, read through pg\_clickhouse so Postgres is the single query surface | Final |
| Object storage | S3 API; backend is the customer's choice (AWS, R2, SeaweedFS, Ceph RGW, Garage) | Final |
| Cache | Node-local Caffeine + Valkey as shared cache/locks/rate limits | Final |
| Control plane | Quarkus admin API + React UI, separate process; runtime survives without it | Final |
| Identity | OIDC relying party, local users fallback (argon2 + TOTP); ZITADEL optional container | Final |
| Licensing | Whole project open source (Apache 2.0 + DCO) until functional end to end; commercial add-ons later behind the extension SPI and control-plane plugin points; JCo adapter as `provided` optional module | Final |
| Kubernetes | An environment, never a requirement; systemd, Compose, K8s, cloud managed all supported from one image | Final |
| Native code | None in-process. Anything native runs as a separate process over HTTP/gRPC or does not exist | Final |

Rejected and not to be reopened: Apache Camel (embedded or otherwise), Elixir/BEAM core, Rust core, Vert.x clustering, Vert.x polyglot verticles, Keycloak, MinIO, custom DSL before real usage, event-bus hop per step, Kubernetes as prerequisite, minimal few-container stack.

## 3. System topology

Two process types and five shared data components; every runtime node is identical, so scaling is "run another one".

&#91;embedded content: system topology · 2 process types, 5 data components\]

Runtime nodes talk to all five stores; the control plane talks only to Postgres (and reads ClickHouse through pg\_clickhouse). Postgres is the single query surface for the UI: ClickHouse is reached through the pg\_clickhouse foreign data wrapper (reads only; ingest goes runtime → batches → Kafka → ClickHouse Kafka engine).

| Component | Role | HA / scale | Mandatory |
| --- | --- | --- | --- |
| Runtime node | endpoints, pipelines, workers, pollers; Micrometer + OTel exporters | N identical nodes, LB in front, SIGTERM drain | yes |
| PostgreSQL | state, work queues (SKIP LOCKED), ordered XI queues, flow/partner config, wallet, processed-ids, DLQ, last-N-hours monitoring | streaming replica + failover | yes |
| Apache Kafka (KRaft) | async hand-over between endpoint and flow, monitoring ingest, replay source, durable `/xi` door during Postgres failover | 3 brokers; tiered storage to S3 | yes |
| ClickHouse | monitoring history, metrics, payload search (XPath/JSONPath via keys-as-columns + JSON projection) | replicated; hot NVMe → S3 by TTL | yes |
| S3-compatible store | bodies above memory tier, archive, Kafka/CH cold tiers | customer backend | yes |
| Valkey | warm config copy, partner OAuth tokens, global throttles, poller leases, distributed locks, UI sessions; persistence off | Sentinel or cluster | yes |
| Control plane | Quarkus admin API + React UI, MCP server, developer portal | stateless, 1–2 instances | yes, but runtime survives without it |
| ZITADEL | optional IdP for installs without one | — | optional |

Runtime pools: flows are grouped into pools (e.g. `critical`, `core`, `dmz`); all flows of a pool are deployed on every node of that pool, so a pool is a scaling and isolation unit. Deployment shapes from one image: plain server (systemd), Docker Compose, Kubernetes (HPA on backlog, health endpoints), cloud managed services for the five stores.

## 4. Runtime core

One message = one plain JDK virtual thread, bounded per flow; verticles exist only to own sockets and timers. This replaces the v3.2 "VIRTUAL\_THREAD verticle per flow" model, which the §4 review showed interleaves only at `await()` and has no in-flight cap.

**Threading**

- Endpoint verticles (HTTP, XI) and pollers (a timer firing a round on a virtual thread, D-076) accept input and hand each message to `Executors.newVirtualThreadPerTaskExecutor()`; they never run a pipeline themselves.
- Admission per flow = a semaphore that counts **requests and bytes** (body size is known from the content hash record). Over the limit: HTTP 503 + Retry-After, SFTP poller pauses, Kafka consumer stops polling. Memory, not thread count, is what backpressure protects.
- Two executors only for work: the virtual-thread executor (default for everything) and a small **platform-thread pool for JCo/JNI**, because JNI still pins carriers on JDK 25; a client library's own transport I/O threads (Vert.x event loops, the Kafka network thread, MINA SSHD's NIO2 pool sized ≤ 2) are not executors and never run a pipeline (D-076). CPU-bound steps need no pool; the per-flow cap (default cores × 2 for CPU-heavy flows, higher for I/O flows) is the limit.
- `ScopedValue` carries message id, tenant, trace id and deadline through every step without threading them through signatures. A thin `RequestScope` (fork / join / cancel on virtual threads) wraps each message so a timeout or cancel stops all of its children; swap in `StructuredTaskScope` when it leaves preview.
- I/O goes through Vert.x clients with `Future.await()`; blocking SDKs run directly on the virtual thread (that is the point). The blocked-thread checker is not relied on: per-step wall and CPU time on the snapshot and a "slowest steps" view replace it.
- One JVM per node. An out-of-process worker (same Step SPI, bodies by reference, HTTP/gRPC) is an installation option for untrusted mapping jars, not the default.

**Message lifecycle, per delivery mode**

| Mode | ACK to sender | Durable before processing | Queue | DLQ | Replay |
| --- | --- | --- | --- | --- | --- |
| Sync request/reply (HTTP 200 with result, XI BE) | the reply itself | no — client owns the retry until it has a reply | none; whole pipeline runs on the endpoint's virtual thread | no, error goes to the caller | from any recorded step |
| Async at-least-once (SFTP, JMS, HTTP 202, XI EO/EOIO, mail) | after the durable save, by the **consumer** before its irreversible ACK (file delete, JMS commit, 202) | yes, with a save timeout (default 5 s → NACK/503) | Postgres work table (SKIP LOCKED) or Kafka topic per flow config | yes | yes |
| At-most-once (telemetry, heartbeats, state updates) | immediately | no | none | no, failure logged and dropped | metadata only |

The durable save is the consumer's responsibility, never a pipeline stage: only the consumer knows what its ACK is and whether it is irreversible. Stage 0 "ingress" is a pure identity whose snapshot shows what arrived. For at-least-once the save *is* the ingress snapshot with its payload, written and awaited by the consumer's delivery handler before the ACK, whatever the flow's recording level says: the recording level governs the steps' snapshots, never the durable save (F-2). The worker then runs the pipeline without stage 0, which was recorded at the save.

**XI quality of service** is a layer on at-least-once: exactly-once = at-least-once + `processed_ids` dedupe table; in-order = XI queue name as partition key, serial per key, parallel across keys; a failed message in an ordered queue blocks the queue by default (XI behaviour) or parks-and-continues per flow config. Three ack states are recorded per message: transport, system, application.

**Event bus** carries only admin commands. The customer's message never travels on it, and after the §4 review neither does monitoring: the writer path is worker → local bounded batch queue → Kafka topic → ClickHouse Kafka engine, with the Postgres last-N-hours copy written by the same batcher. Vert.x clustering is not used; nodes share state only through Postgres, Kafka and Valkey.

**Writer (persistence batcher)**: hand-rolled, not a Mutiny operator chain (which emits on timer regardless of demand and dies on backpressure). Adaptive flush — write immediately when idle, accumulate to batchSize/batchMs under load; up to N commits in flight with per-batch completion; fixed-shape `unnest` inserts with prepared-statement cache; a small bounded admission buffer whose overflow surfaces as ingress rejection, never as a dead stream (trail rows wait FIFO for room up to `maxWait` instead, §8, D-071). The prototype measured batching at ≈ 80 % of no-persistence throughput; the serialized-commit ceiling is gone.

## 5. Message model

An Envelope is an immutable record; every step returns a new one, and bodies are handles to content, never streams. Immutability is what makes snapshots free, retries safe and parsed views shareable.

```java
record Envelope(String id, String flow, int step,
                Headers headers,               // transport metadata, String→String
                Props props,                   // typed flow state, Key<T>, never leaves the runtime
                Keys keys,                     // business correlation keys, indexed for search
                Body body,                     // main payload, by reference
                Map<String, Part> attachments, // Part(name, contentType, Body)
                Views views)                   // memoized parsed views, shared by reference
```

| Field | What goes there | Persisted | Indexed | Reaches producers |
| --- | --- | --- | --- | --- |
| `headers` | HTTP/JMS/mail headers from ingress, `http.status` from egress | jsonb | no | only what the flow allow-lists (`forwardHeaders`); nothing propagates implicitly |
| `props` | split index, router decision, partner, validation result, replay-of — anything a later stage decides on | jsonb, JSON-serialisable values only | no | never |
| `keys` | what operators search by (invoiceId, orderNo, meteringPoint); filled by `extractKey`, declared per flow | own table `message_keys(name, value, message_id, step)` / CH columns with skip index | yes | never |
| `attachments` | multipart parts (mail, SOAP/MTOM, AS2/AS4) | reference only; content kept per retention policy | no | yes, as parts |

Not carried over from Camel: `getIn()/getOut()`, `ExchangePattern` as a property (request/reply is a consumer trait), exception as a property (failure is a stream signal and a DLQ row). Keys are separate from props because "invoice 4711 — where is it" must answer in under 1 s over a year of data, and that needs an index on `(name, value)`, not a GIN index over a general-purpose bag.

**Body**: one final class, no visible variants.

```java
final class Body {
    Hash hash();            // content hash (xxh3), the identity; computed on arrival
    long size();
    ReadStream<Buffer> open();   // fresh cold stream, any number of times
    Buffer whole();         // only below the tree limit, else exception
}
```

Where the bytes live is a `BlobStore` concern, invisible to steps: memory tier (default 1 MB), encrypted local spool, direct multipart to S3 when configured or when the flow is at-least-once (until S3 lands, P2-05, at-least-once bodies up to `FLOTTILA_BLOB_DURABLE_MAX` are made durable in Postgres before the ACK and larger ones are refused at deploy — F-6); a node-wide byte budget is the second spill trigger; every flow declares a finite max body size. Objects are keyed by **message id + step**, not by content hash (streaming multipart cannot know the hash up front, and content-keyed objects cannot be deleted per retention — §4 finding). The hash is still computed and stored so identical bodies across steps are written once in Postgres (`payloads(hash PK)` with `ON CONFLICT DO NOTHING`) and dedup is visible in monitoring.

Steps declare the body kind they need — `whole` (DOM-style, size-limited), `stream` (gzip, PGP, sign, copy; never materialised), `split-assemble` (StAX/CSV/JSON splitter at ingress producing child envelopes with `PARENT_ID / SPLIT_INDEX / SPLIT_TOTAL`, streaming aggregator writing header + fragments + footer). The deploy-time check rejects a `whole` step on a flow whose max body exceeds the tree limit unless a split precedes it. Producers pipe `open()`; they never buffer. Stream-step outputs are materialised node-locally (spool) and uploaded by the same BlobStore rules. Rejected: local-spool-then-background-upload, auto-inserted splits.

## 6. Pipeline

A flow is a composition of stages built once from its config; at runtime a stage holds only the dependencies it was given at build time, and the `Runtime` record is never looked up again. That is what makes a flow unit-testable in milliseconds with a fake `Runtime`.

```java
interface Step  { Envelope apply(Envelope e); }                     // runs on the message's virtual thread; may block
interface Stage { Envelope apply(Envelope e, Pipeline rest); }      // may emit 0..N, call rest for each
interface XmlStep  { XdmNode apply(XdmNode in, Headers h); }        // platform parses/serialises, view memoized
interface TextStep { String apply(String in, Headers h); }
```

With one virtual thread per message the reactive `Multi<Envelope>` stage contract from the prototype is no longer needed; a stage is plain code and cardinality is expressed by how often it calls `rest`. Patterns as built-in stages: `transform`, `filter`, `split`, `route` (first matching predicate wins; a branch is itself a pipeline, named `route:<branch>` in the timeline), `aggregate` (state in Postgres, deadlines as DB rows), `throttle` (per node, or global via Valkey), `circuitBreaker`, `idempotent` (processed\_ids), `resequencer` (Postgres).

State rule: state whose loss loses messages goes to Postgres (aggregator, resequencer, idempotent ids, DLQ, ordered queues); state whose loss only changes tempo may be in memory or Valkey (throttle, circuit breaker, caches).

Retry per step: policy from flow config, exponential backoff, only on steps marked retryable (I/O); final failure of an at-least-once flow → DLQ row by reference to the body, written only after that body is persisted (F-7); a sync flow returns the error to the caller and parks nothing (§4 table, D-045). At-most-once flows log and drop.

**Inter-flow communication**, three mechanisms, all deploy-time checked for dependencies and cycles:

| Mechanism | Runs where | Message identity | Crosses nodes | Use |
| --- | --- | --- | --- | --- |
| `call: <subflow>` | nested stage, same thread, same message | same | no | shared, versioned library logic (what PO called a common mapping) |
| `flow://<name>` | caller's thread, direct call through the registry | child message with parent id | no | sync composition, lookups |
| `queue://<name>` | target flow's own virtual thread, via Postgres queue or Kafka | child message with parent id, only configured props persisted | yes | async hand-over with the target's own retry/DLQ/ordering |

Headers always cross; the body is shared by reference. Never through the event bus.

**Dispatcher flows**: one endpoint (e.g. `/xi`) reads the routing key, looks up a versioned routing table in Postgres (cached per node), and forwards — a sync target is called directly on the same thread, an async target is enqueued with `target_flow`. Unknown route → error or DLQ "no route", never dropped. During a PO migration the dispatcher passes unmigrated interfaces through to the old PO, interface by interface.

**Recording** per flow, overridable per step: level `all | ends | errors | off`, payload `keep | metadata-only`. Replay from step N needs the recorded output of step N−1 (the input step N saw); the at-least-once durable save at ingress is independent of recording. Every message pins the flow, partner-directory and routing-table versions it ran with, so monitoring tells the truth. A replay or a DLQ retry runs the flow version deployed now (a retry exists to rerun a parked message after a fix) and pins the versions it ran with, so each run's configuration stays visible (F-11). Replay from any recorded step works after a restart, because every kept step body is durable (D-064, D-066); 409 is reserved for a body removed by retention (P2-07).

## 7. Components, queues, persistence

All contact with the outside world goes through the Component SPI; nothing else in the platform opens a socket. That is the rule that makes mocking first-class. The rule binds the node; the `flottila` CLI is a tool outside the node, and its admin client (`flottila deploy`, JDK `java.net.http`) talks to a node's admin API, not to a partner (F-16).

```java
interface Component {
    String scheme();                                   // "http", "sftp", "xi", "jms", "mock" …
    JsonSchema configSchema();                         // validated at deploy time
    Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler);
    Producer producer(EndpointConfig cfg);             // Future<Envelope> send(Envelope e)
    Future<Health> health();
    default Optional<InetSocketAddress> listensOn(EndpointConfig cfg) { return Optional.empty(); } // D-072
}
```

A component whose consumer accepts connections answers `listensOn` with the local host:port it binds (from the config alone, no bind, no DNS; port 0 is never compared). A deploy refuses two `in` endpoints whose addresses overlap — same port and equal hosts or a wildcard host — within the package, against the node's admin listener, and against every running flow of every other package; Vert.x would share the port and round-robin it silently (D-072).

The consumer contract is `Function<Envelope, Future<Ack>>` (§4 finding), not a bare `ReadStream`: the handler returns the ack state the consumer needs to finish its own protocol (delete the file, commit the JMS session, send 202). Blocking SDKs run on virtual threads the component starts itself, as the http consumer does; no `BlockingConsumer` wrapper until a second blocking SDK needs one (D-076). One jar per component, wired explicitly by the node's `Main` (components need the node's `Vertx` and `BlobStore`, which `api` may not name — F-4); same classpath until a dependency conflict forces isolation. Steps (xslt, groovy, js, validate, map, ai) are wired the same way.

| Component | Transport | Notes |
| --- | --- | --- |
| http | Vert.x HTTP client/server | sync request/reply and `/in-async` 202; API-management policies as ingress stages |
| xi | XI 3.0 over SOAP, IDoc, RFC via JCo | normal component; JCo module `provided`, platform-thread pool; `/xi` door durable in Kafka so it never answers 503 during Postgres failover |
| sftp / smb / file | Apache MINA SSHD client (sftp, D-076) / own | consumer only until Phase 4; poller lease held by the node, not the component (P2-02L); durable save before delete/move; duplicates dropped at the save by the file key (P2-02D) |
| jms / kafka | vendor client / Vert.x Kafka | ack after durable save |
| mail | own | IMAP poll, SMTP send, attachments by reference |
| as2 / as4 / edi | own | signing, MDN/receipts, EDIFACT and X12 tokenizers as stream steps |
| db | Vert.x reactive clients, JDBC on worker when declared blocking | platform-managed named datasources from the wallet |
| mock | in-process | records what it was sent, replies from fixtures; selected per environment |

**Queues**

| Queue | Backing | Used for |
| --- | --- | --- |
| WorkQueue | Postgres `work` table, `SELECT … FOR UPDATE SKIP LOCKED`, one row per message, `partition_key`, lease with expiry renewed while the item runs, an attempts count that fences the lease, the completion and the hold and bounds the retries, a held state that blocks its key | async at-least-once flows on small installs; all XI ordered (EOIO) queues with block-on-failure |
| Kafka topics | one topic per flow or pool, key = partition key | async hand-over at scale, ingest buffer for monitoring, replay source, the durable `/xi` door |
| Poller leases | Valkey (moved off Postgres) | which node polls which SFTP/mail/DB endpoint |

`WorkQueue` and `MessageStore` are interfaces; both are first-class and the flow config chooses `queue: postgres | kafka`. Rule (agreed Oct 17, 2026): Postgres is the default queue; a flow moves to Kafka when it needs high volume, cross-pool fan-out or replay from the log; XI EOIO ordered queues stay on Postgres always, while the /xi door itself is Kafka for failover. A broker replaces save-before-hand-over, never the monitoring store. Per-key serial processing: a worker that finishes a message takes the next one of the same key without a new claim round-trip (hot-partition optimisation, measured at 60–200 msg/s per key in the prototype without it). The key is evaluated at the durable save from the flow's `partitionBy` (the name of its one `extractKey` step; no value = unordered; over 256 chars = final refusal); after the item it claimed, a worker takes at most `maxChain` further items of the key by the fast path (default 32; 0 = fast path off) before the key goes back to the seq-ordered claim; a key the extractKey cannot read (MALFORMED/INVALID) is saved unkeyed, any other key failure is a retryable refusal; `queue: kafka` is refused at deploy until the Kafka hand-over lands (D-075).

**Persistence tables (Postgres)**: `messages(id, step, flow, headers, props, keys, body_ref, versions…)`, `payloads(hash PK, size, …)`, `message_keys(name, value, message_id, step)`, `work`, `dlq(… body_ref, no FK so a park never fails on ordering)`, `processed_ids`, `aggregates`, `routing_table`, `partner_directory`, `flows`, `wallet`, `audit`. Rule: bytes exist in the system once; everything else is a reference. Never a synchronous delete on the hot path; retention is a background job by partition.

**Sizing**: one 8-core node is expected to carry tens of thousands of HTTP passthrough req/s, 5–20k msg/s with persistence, hundreds to low thousands/s with 100 KB XSLT. Targets to hold on native Linux: sync request/reply platform overhead < 2 ms p50 (met in the prototype), at-least-once durable ACK measured p99 8.8 ms at c=1 on a 4 vCPU cloud VM with default Postgres 16 (two commits in sequence; F-14) — < 5 ms p99 is the tuning target of Phase 2 (P2-08: one commit, group commit, faster storage).

## 8. Monitoring, tracing, alerting

Monitoring is the product: a snapshot after every stage, searchable by business key in under a second over a year of data, with the payload at each point and replay from any step. Processing waits for monitoring only at the points named below, and monitoring is not best-effort either. Each recorded step body is kept durably (D-064) unless it is above `durableMax` or its persist fails — then its row is metadata-only, counted and logged at WARNING; a run's snapshot rows wait in a per-run trail and are written at run end, or when more than 256 are buffered, once their bodies persisted, so a trail row's `body_key` always names a durable body (the at-least-once ingress row is still saved beside its body's persist, D-058). A step waits only when its snapshot is the one past 256 buffered rows (it waits for that flush, and a sync reply behind it waits too); a worker item, a sync permit, an at-most-once landing and a replay or retry wait for the run's last flush (persists, then row writes); the sync reply waits for no persist except that mid-run flush, and the durable ACK is unchanged (F-10, D-066).

**Write path**: step → `Snapshot` (id, flow, step, stage, timestamps, wall and CPU time, headers, props, keys, body reference, error, extension name + version, trace id) → local bounded batch queue on the node → Kafka `monitoring` topic → ClickHouse Kafka engine. The same batcher writes the Postgres last-N-hours copy that the operator screens read for "now". Overload shows as a bounded queue filling, which is itself an alert; nothing is dropped silently (the event bus was removed from this path for exactly that reason). A run's trail rows that find the node writer's bounded backlog full wait for room in arrival order, holding the run's permit, so a writer that cannot keep up shows as admission refusals (503), not lost rows; a row still waiting after the writer's `maxWait` (10 s), or whose commit fails, is logged at WARNING with its message id, flow, step and cause and counted in `snapshotWriteFailures`; the at-least-once ingress row alone still fails at once on a full backlog, a retryable Nack (F-13, D-071).

**Stores and query surface**

| Store | Holds | Read by |
| --- | --- | --- |
| Postgres | in-flight state, last N hours of snapshots, DLQ, ack states | control plane directly |
| ClickHouse | full history, metrics, payloads ≤ \~256 KB inline (zstd, per-tenant key), larger by S3 reference; hot NVMe → S3 by TTL; retention per flow via partition TTL, never row-level delete | control plane through **pg\_clickhouse** (FDW, reads only); payload-search functions wrapped in Postgres functions; every search query EXPLAIN-tested for pushdown |
| S3 | large payloads, cold tier, raw archive for rebuilds | runtime for replay; CH for cold queries, labelled "archive query" in the UI |

Payload search by XPath/JSONPath = configured keys as columns + a JSON projection of the XML stored beside it; ClickHouse narrows the candidate set, Saxon finishes the exact evaluation on the runtime. CH dictionaries read dimension tables (flows, partners) from Postgres.

**Three screens**: per message (timeline with per-step latency, payload at each point, props and keys, ack states transport/system/application, replay or resend from any step, "save as test case"); per flow (throughput, error rate, p95, backlog, blocked ordered queues, slowest steps); per platform (virtual threads in flight per flow, byte budget, batcher queue depth, DB, Kafka lag, disk).

**Tracing and metrics**: OpenTelemetry via `Vertx.builder().withTracer`, one span per step, trace id on the message record and propagated through HTTP headers to backends; Micrometer → Prometheus for platform metrics.

**Alerting, two layers**: platform (Prometheus/Alertmanager: event-loop blocking, pools, DB, disk, Kafka lag) and business (silence detection — "usually 50/h, last hour 0" from the flow's own baseline; error rate; SLA; DLQ growth; blocked queue; backlog) with email, webhook, Slack, Teams and PagerDuty channels. Grafana is AGPL and may run alongside for the operator's own use; customer dashboards are the platform's own React + ECharts over Postgres/pg\_clickhouse.

## 9. Configuration, partner directory, caching, security

Flows are code in git; partner details are data in Postgres; secrets are references. Keeping the three apart is what makes git-first deployment, per-environment mocking and audit possible.

**Flow configuration**: JSON/YAML with a published JSON Schema (the first deliverable of the plan). Identity `tenant / package / kind:id @ version`; the package is the namespace and authorization unit (PO SWCV → package, ESR namespace → folder; artifact meta files carry the PO origin block). A folder is structure only: ids are unique per package and never contain the folder, an optional `folder.yaml` keeps the verbatim namespace, and a flow is addressed as `<package>/<flow>` (D-060). Artifacts are found by their `kind` anywhere in the package, at most four folders deep, every folder name a slug; a file reference in a flow or test file is a path from the package root, an include or import inside an XML file is relative to the including file, and both stay inside the package (D-052, D-078). `package.yaml` declares dependencies, `package.lock` pins them, so shared collections like `common` scripts are versioned per package. Deploy = CLI from CI (`flottila validate`, `flottila deploy`), the runtime pins the commit hash on every message, rollback is a revert. A pre-commit hook validates the schema, compiles XSLT and dry-builds the pipeline without a runtime.

**Partner directory**: keyed by partner and interface, resolved at processing time through `${partner.…}` placeholders, versioned and exportable; the interface registry keeps PO namespace + name verbatim as the routing key. Runtime consumer entitlements (PO sender agreements) are checked by the endpoint or dispatcher.

**Caching, two levels**

| Level | Holds | Invalidation |
| --- | --- | --- |
| Node-local (Caffeine) | partner, routing and flow config; compiled XSLT/XSD/scripts by content hash; decrypted secrets, short TTL, never shared; lookup tables; built pipelines | Valkey pub/sub hint → node reloads the row from Postgres; full reload after reconnect; row versions as backup |
| Valkey | warm copy for node start, partner OAuth tokens (primary, refresh-ahead), global throttles and rate limits, poller leases and distributed locks, shared lookup-cache step, UI sessions | TTL; persistence off; HA via Sentinel or cluster |

Never cached: idempotent ids, aggregator state, ordered-queue state, DLQ.

**Security**

- Wallet: secrets, keys and certificates encrypted in Postgres (AES-GCM; master key from env, file or KMS, never in the DB), pluggable backends (Vault, Kubernetes Secrets, cloud vaults), `secret://` references only in flow config, audit log per read, certificate rotation via `updateSSLOptions` without restart.
- Until the wallet exists, `secret://<name>` resolves from the node's environment (`FLOTTILA_SECRET_<NAME>`); runtime resolves endpoint options before a component sees them, and listings, records, the flow hash and unpacked files carry only the reference (D-081); until FIX-92, text a component or transport builds from a resolved value (an overlap problem's address, a build/start exception, a parked send failure) may show it.
- Identity: OIDC relying party, local users fallback (argon2 + TOTP), API keys and mTLS for machines, client-credentials for service accounts; ZITADEL optional.
- Three authorization planes: control-plane roles scoped tenant → package → flow with a separate view-payloads permission; runtime consumer entitlements; data-level payload visibility and masking. `vertx-auth-abac` remains a candidate, not a commitment.
- TLS with hostname verification everywhere; payloads encrypted per tenant at rest; per-flow PII masking policy applied before any payload reaches an AI tool or an unprivileged viewer.
- Actor (human / agent / model) recorded on every audit entry.

**Deployment mechanics (git-first)**

The control plane watches a git branch (or tag) per environment and pushes to the nodes of the target pool; the CLI can deploy manually in non-production only. Deployed state lives in Postgres and the UI shows git revision versus running revision per node.

1. Commit → `flottila validate` (schema, XSLT compile, dry build of the pipeline) → in-process tests → merge.
2. Control plane detects the new revision for the environment, records the package version in Postgres, notifies nodes through Valkey.
   The archive is stored once in S3 at `<prefix>/archives/<sha256>.zip` before the record is written; the record (package, version, git revision, archive sha256, deployed_at; no bytes) is one row per package, shared by the pool, and its write is the deploy's commit point. In Phase 1 the node's admin PUT stores and records; the control plane must likewise upload before it records. Archives are never deleted in Phase 1 (superseded ones serve an emergency rollback).
3. Each node loads the new flow version beside the old one; new messages go to the new version, the old one drains in-flight work and unloads. No node restart. A failed load leaves the old version running.
4. Every message pins the flow, partner-directory and routing-table versions it ran with.
5. Rollback = revert in git; emergency rollback = CLI pin to a previous recorded version, audited.

At start a node restores before its admin API opens: per recorded package it fetches the archive, checks its sha256 and starts it; a package it cannot fetch, verify, unpack or build is listed FAILED with its problems; a flow that does not start is listed FAILED and the others run; and the `deployments` health check is DOWN while any package or flow is FAILED. S3 is thereby a start dependency of a deployed node, never of the hot path (F-12).

Secrets and partner data never travel through git; the flow carries only `secret://` and `${partner.…}` references resolved per environment.

## 10. Extensions, scripts, migrated SAP mappings, API management

Custom code runs in-process by default on the same Step and Component SPI as the built-ins, isolated by classloader and made survivable rather than impossible to crash. This is what replaces custom EJBs and Java mappings from SAP PO.

| Kind | Packaging | Isolation | Typical use |
| --- | --- | --- | --- |
| Sandboxed scripts (Groovy, GraalJS) | inline in flow config | platform API only, deadline, CPU budget | small transforms, routing logic, CPI script migration (`Message` API facade) |
| Java extension jar | jar + `META-INF/flottila-extension.json`, built in CI, `flottila deploy`, bytes in S3, metadata in Postgres | per extension-version classloader whose parent sees only the platform API jar; flows pin the version; platform API semver range checked at deploy | PO Java mappings (`AbstractTransformation` facade with `MappingTrace`, `DynamicConfiguration`), custom steps |
| Custom component | same packaging, implements Component SPI | same | vendor SDKs, JMS providers, partner APIs |
| Out-of-process service | any language, HTTP/gRPC, bodies by reference | process boundary | untrusted, JNI or non-Java code; also the home for a disposable mapping JVM when a customer's jars are hostile |

Bad-extension policy: messages are durable before any extension runs; `-XX:+ExitOnOutOfMemoryError` with fast restart; extension name and version on every span, snapshot and metric; per-extension circuit breaker on deadline, failure rate and CPU; deploy-time bytecode scan (System.exit, thread creation, Runtime.exec, Unsafe, JNI → warn or refuse per policy). Platform-managed named datasources (`Services.datasource("crm")`: wallet credentials, pooled, metered; reactive clients for PG/MySQL/MSSQL/DB2/Oracle, JDBC on the virtual thread when the step declares blocking). No XA; idempotent-key + outbox pattern with an SDK helper.

**SAP compatibility runtime** (part of the runtime, not a separate process): XSLT 3.0 (Saxon-HE; EE as plugin), XSD validation, WS-Security, JCo/IDoc on the platform-thread pool, PO and CPI mapping facades, XI/IDoc simulator for tests. Its own test harness runs a migrated mapping jar against golden payloads from PO monitoring with no flow around it. **PO migration** = assessment via Directory API/ESR export → automatic ICO/channel/XSLT conversion → AI-generated XSLT for graphical mappings validated by golden tests → Java mappings as extensions → per-interface cut-over through the dispatcher.

**API management** is ingress policies on the same runtime (authentication, quotas, rate limits via Valkey, transformation, logging with the same monitoring) plus API, product, application and subscription objects, an own developer portal and an MCP gateway — no second runtime. Policies are transport-independent functions, so an Envoy data plane calling them through `ext_proc` is an installation option for line-rate requirements. Gateway target: JWT-validated, rate-limited, metadata-recorded passthrough ≥ 20k req/s per 8-core node, p99 < 5 ms.

**AI, three audiences**: agents building integrations (everything as text with published schemas, complete API and CLI, MCP server on the control-plane API, dry-run endpoint returning per-step snapshots, structured errors, schema registry, sample payloads from monitoring as mapping specs, XSLT 3 plus a small declarative JSON mapping format, `AGENTS.md` in the repo layout); an `ai:` step type (pluggable provider per tenant from the wallet, schema-validated output with deterministic fallback, prompt/model/cost on the snapshot); and AI building the platform. Guardrails: agent role with scoped permissions, no direct production deploy (PR + CI only), secrets never in prompts, PII masking before any payload reaches a model.

**Service extensions** (what PO Java proxies + EJBs were for)

A service is a hosted Java component that several flows call, with its own libraries, state and resources — e.g. an ISDOC generator.

```java
@FlottilaService(name = "isdoc", version = "2.1")
public class IsdocService implements Service {
    public void start(ServiceContext ctx) { /* load templates, open datasources */ }
    public Envelope handle(Envelope in) { /* return a new envelope */ }
    public void stop() {}
}
```

Flows call it as a target: `to: service://isdoc` (sync, same thread, body by reference) or `queue://isdoc` (async, durable). Monitoring records the call as a step with latency and service version.

| Host kind | Runs where | Use |
| --- | --- | --- |
| In-process (default) | same JVM, isolated classloader, one singleton per service version per node | what PO ran as EJBs |
| Service pool | a runtime pool that deploys only services; flows reach it via `queue://` or internal HTTP | heavy rendering, OCR, big lookups |
| Out-of-process | any language, HTTP/gRPC, registered with its schema | legacy, JNI, untrusted |

One registry declaration (name, version, request/response schema, host kind) — a flow does not change when a service moves between host kinds.

Packaging is a **bundle**: `isdoc-2.1.flx` = `manifest.json` + the extension jar + `lib/*.jar`. The classloader sees the platform API jar and everything bundled, so a service brings its own JAXB, POI or Jackson versions. Deploy path: CI build → bytecode scan → bytes to S3, metadata to Postgres → `flottila deploy` → target pool loads the classloader, calls `start()`, advertises the service; versions load side by side and flows pin them in `package.lock`.

Resources only through the SDK, so every use is attributed to the service version: `Services.datasource("erp")` (wallet credentials, pooled, metered; a bundled driver allowed), `Services.cache(name, maxBytes)` (node-local Caffeine under the node budget), `Services.sharedCache(name)` (Valkey, cross-node), `Services.http()`, `Services.secret()`, `Services.blob()` (large output straight to the BlobStore), `Services.scheduler()` (leased periodic jobs), `Services.executor()`, `Services.clock()`, `Services.log()`. Local transactions only; outbox helper for write-and-emit. Services are never exposed to external callers directly; external access is always a flow with an endpoint and policies in front.

## 11. Testing and AI-assisted development

Every flow is testable in milliseconds without infrastructure because outside access exists only behind the Component SPI, and Clock, timers and id generation are injected through the `Runtime` record — no static singletons.

| Level | What | Runs in |
| --- | --- | --- |
| Step / flow unit test | `new FlowBuilder(fakeRuntime).build(cfg)` with fake components and in-memory stores | ms, JUnit 5 |
| Component contract suite | one suite every real and fake component must pass: backpressure, re-open on retry, ACK only after durable write, spill behaviour, cancel | seconds, Testcontainers where needed |
| Flow tests in git | a `kind: test` YAML in the package (by convention next to the flow, D-078); `flottila test` binds a mock for every shipped scheme and runs each case in-process on virtual time; same runner the CI uses (D-080) | seconds |
| Golden tests from monitoring | "save as test case" on a monitored message → input = step 1 payload, expected output = last step, partner reply = recorded response; rerun hundreds after an XSLT change | seconds |
| Integration / stack | Testcontainers for Postgres, Kafka, ClickHouse, Valkey, S3; nightly Compose; kill -9 ledger tests (sent = final-step rows, DLQ 0, work 0) | minutes |
| Protocol doubles | embedded MINA SSHD, Artemis, GreenMail, WireMock, own XI/IDoc simulator | — |

Fake clock drives backoff, leases and deadlines; a blocked platform thread in tests is a failure. Benchmarks run natively on Linux (never through Docker Desktop on Windows, which the prototype showed is round-trip-bound) and are recorded in `bench/RESULTS.md` with machine, date and configuration.

AI-assisted development is a design constraint, not a tool choice: the repo carries `CLAUDE.md` / `AGENTS.md` with the architecture rules (immutable Envelope, Body by reference, no capture of `Runtime` in a stage, one commit per step, prefer `javap` on resolved jars over documentation for library APIs, stop and report after each step), and ArchUnit tests enforce module boundaries so an agent cannot quietly violate them.

**Mocking**

Every component ships as a pair — real implementation and mock — with the same scheme, config schema and SPI, and both must pass the same contract suite. No component is merged without its mock. Mocks exist for development and CI only: a deployed environment (test or production) refuses to load any mock binding at deploy time.

The flow file is byte-identical everywhere; mocks are bound in a `kind: test` document of the same package (by convention a `*.test.yaml` next to the flow, D-078):

```yaml
flow: invoice-to-partner
mocks:
  partner:    { reply: fixtures/partner-ack.xml, delay: 20ms }
  erp-lookup: { script: lookups/customer.groovy }
  sftp-in:    { files: fixtures/inbox/*.xml }
cases:
  - name: domestic invoice
    send: fixtures/invoice-4711.xml
    expect:
      sent: { partner: { xpath: "/PartnerInvoice/@number", equals: "4711" } }
      keys: { invoiceId: "4711" }
      steps: 6
  - name: partner down
    send: fixtures/invoice-4711.xml
    mocks: { partner: { fail: 503 } }
    expect: { dlq: 1, retries: 3 }
```

Behaviours, same vocabulary for every component: `reply` (fixture), `recorded` (response captured in monitoring), `script` (Groovy/JS request → response), `files` / `messages` (consumer side, feed fixtures), `fail` (503, timeout, reset, malformed; after N, every Nth), `delay`; capture is no behaviour key: every mock always records what it was sent, and a test's `expect.sent` reads that record (F-16).

Two execution layers run the same YAML: in-process mocks with in-memory stores (milliseconds, on save and as the CI merge gate) and protocol doubles with Testcontainers (embedded SSHD, GreenMail, Artemis, WireMock, XI/IDoc simulator, real Postgres/Kafka/ClickHouse) as the release gate. Fixtures come from hand-writing, from "save as test case" in monitoring (masked by the flow's PII policy), or generated from the schema registry.

## 12. Repository layout and modules

One Maven monorepo (`~/flottila/`, Apache 2.0), dependency direction strictly downward and enforced by ArchUnit; no module named `common`.

| Module | Contains | Depends on |
| --- | --- | --- |
| `flottila-api` | Step, Stage, Component, Envelope, Body, Props/Keys/Headers, BlobStore, Runtime record, extension manifest types — the only jar an extension classloader sees | nothing but the JDK (Vert.x `Buffer`/`ReadStream` as the one exception) |
| `flottila-core` | pipeline builder, patterns, RequestScope, admission control, batcher, MessageStore/WorkQueue/MonitoringStore interfaces with in-memory implementations | api |
| `flottila-runtime` | Vert.x bootstrap, endpoint verticles, Postgres/Kafka/Valkey/S3/ClickHouse implementations, wallet, caching, recording, replay, admin API server, node and deploy | core, schemas; `Main` wires the shipped components and steps (F-4) |
| `flottila-components/*` | one module per scheme: http, xi (JCo `provided`), sftp, jms, kafka, mail, as2, as4, edi, db, mock | api |
| `flottila-steps/*` | xslt, validate, groovy, js, map, ai, split/aggregate formats | api |
| `flottila-sap` | PO/CPI mapping facades, XI/IDoc simulator, migration converters | api, steps |
| `flottila-testkit` | fake Runtime, contract suites, protocol doubles, fake clock and timer, in-memory BlobStore (the flow-test runner lives in `flottila-cli`, F-16) | api (F-1, 2026-10-18; was core) |
| `flottila-cli` | validate, test, deploy, package, migrate | every module (runtime, components incl. the mock, steps); nothing depends on it (F-16) |
| `flottila-control` | Quarkus admin API, React UI, MCP server, developer portal | talks to runtime over HTTP only; no Java dependency on runtime |
| `flottila-schemas` | JSON Schemas for flow, package, partner, endpoint configs; published | — |
| `bench/`, `docs/`, `deploy/` | benchmarks, architecture and ADRs, Compose/Helm/systemd | — |

Extensions and customer packages live in their own git repos with the same layout conventions and `package.yaml`.

## 13. Implementation plan

The order is foundations first, features last, and no phase is called done until its gate is measured — not a V1/MVP staircase but the dependency order of the architecture itself. Durations are deliberately absent; the gates, not the calendar, decide.

&#91;embedded content: implementation roadmap · 6 phases, 6 gates\]

Phases 0–2 build the runtime that everything else sits on; 3 turns it into the product; 4 and 5 widen it. Nothing in 3–5 is designed to be rewritten later.

**Phase 0 — spikes and scaffolding.** Monorepo with the modules of section 12, ArchUnit boundary tests, CI (build, test, native bench job on a Linux runner), `CLAUDE.md`/`AGENTS.md`. Flow, package and endpoint JSON Schemas published. Two spikes with numbers: the prototype's batched persistence rerun on native Linux Postgres (closes the < 5 ms p99 question), and `RequestScope` + admission semaphore under 10k virtual threads. Gate: schemas merged, bench baseline recorded.

**Phase 1 — core runtime.** `flottila-api`, `-core`, `-runtime` with Envelope/Body/BlobStore (memory, spool, S3 tiers, byte budget), RequestScope, per-flow admission, the batcher, Postgres MessageStore/DLQ, recording levels, replay, http and mock components, xslt/validate/extractKey/route steps, the three delivery modes. Gate: the milestone-1 flow (HTTP → validate → key → route → XSLT → HTTP) runs on the new core; kill -9 ledger 2000/2000; sync < 2 ms p50 and the durable ACK measured and recorded native (F-14); 50 MB body passes a stream step without heap growth.

**Phase 2 — durable messaging.** Kafka hand-over and the durable `/xi` door, Postgres WorkQueue with partition keys and the same-key fast path, XI QoS (EO, EOIO block-on-failure, `processed_ids`), dispatcher with versioned routing, partner directory, wallet, Valkey cache and leases, inter-flow `call` / `flow://` / `queue://`, and the CLI `validate`, `test` (in-process, mocks, virtual time) and `deploy` (F-16). Gate: XI simulator end-to-end with ordered queues; Postgres failover during load with no loss or duplicate; node join/leave under load.

**Phase 3 — monitoring product.** Kafka → ClickHouse ingest, schema and TTL policies, pg\_clickhouse query surface with pushdown tests, control plane (Quarkus API, React UI) with the three screens, replay/resend from the UI, "save as test case", OTel spans, Micrometer, both alert layers including silence detection. Gate: "invoice 4711" by key in < 1 s over 100 M synthetic snapshots; XPath search over 10 M payloads in seconds; a dropped node leaves no gap in the timeline.

**Phase 4 — components and SAP compatibility.** sftp producer, smb/file (the sftp consumer moved to Phase 2, D-076), jms, kafka consumer/producer, mail, as2/as4, edi (EDIFACT, X12), db datasources, JCo/IDoc on the platform-thread pool, PO and CPI mapping facades, XSD and WS-Security, the CLI `package` and `migrate` commands (validate, test and deploy moved to Phase 2, F-16), migration converters (ICO, channel, XSLT). Gate: one real PO interface migrated end-to-end with golden tests from PO monitor payloads, both directions.

**Phase 5 — extensions, API management, AI.** Extension SDK with isolated classloaders, bytecode scan and circuit breaker; sandboxed Groovy/GraalJS; API objects, developer portal, MCP server and gateway; `ai:` step; dry-run endpoint and agent tooling. Gate: an extension built in an external repo deploys through CI and shows on every span; gateway bench ≥ 20k req/s per 8-core node at p99 < 5 ms.

**Working method** (unchanged from the prototype, which it served well): Claude Code CLI executes, one commit per step, stops and reports after each step with measurements; design findings that contradict this document are raised explicitly, never worked around; `javap` on resolved jars over documentation; every benchmark native and recorded.

## 14. Open decisions

Eleven items remain open; the first four block Phase 0 and are the ones to close in the next review session.

- [ ] Confirm the §4 review replacements formally: virtual-thread-per-message with per-flow semaphore; objects keyed by message id + step; worker → batch queue → Kafka → ClickHouse monitoring path; consumer contract `Function<Envelope, Future<Ack>>`; durable `/xi` door in Kafka.
- [ ] Three questions left from the §4 session: ABAP proxy behaviour on HTTP 503 and timeout; whether the body is stored inline before ACK (this document says yes, by reference into the BlobStore before the consumer's ACK); stream-step outputs materialised node-locally (this document says yes).
- [ ] §5 and §10 adversarial reviews not yet run; v3.3 is held until they are.
- [x] Flow config JSON Schema: shape of steps, routes, delivery/recording/QoS blocks, `partitionBy`, `forwardHeaders`, mocks per environment — drafted in P0-12 (`flottila-schemas`, D-040), `draft — review at hand-over`; QoS beyond the delivery mode (EO/EOIO) stays for Phase 2.
- [ ] Size thresholds to be fixed by measurement: memory body tier (1 MB proposed), tree limit for `whole` steps (\~50 MB), ClickHouse inline payload limit (\~256 KB), per-flow byte budget defaults.
- [x] Hot-partition fast path semantics: how long a worker keeps a key before yielding it to other nodes — at most `maxChain` further items after the claimed one (D-075).
- [ ] `vertx-auth-abac` for the three authorization planes, or own policy model.
- [ ] Brand name and domain (Flottila is a working name; "Flotilla" is generic and used by other projects).
- [ ] Add-on boundary for later commercial modules: which control-plane plugin points exist from day one.
- [ ] Envoy `ext_proc` integration: designed-for only, or built in Phase 5 as a reference deployment.
- [ ] Saxon-EE streaming plugin: SPI shape so a licensed customer can drop it in without a platform change.
