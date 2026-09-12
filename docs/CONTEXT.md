# Flottila — Project Context

Working name: **Flottila** (double t, double l). International project, English throughout. Independent of Qamelo — new codebase, no shared code.

## Purpose

A reactive enterprise integration platform where **monitoring is the product**, not an add-on. The runtime is designed to be observable by construction: every message, every step, every payload snapshot is captured, searchable, and replayable. It happens to also deliver messages reliably and fast.

Motivation: 20+ years of SAP PO / Integration Suite experience. PO monitoring is slow (runs on the transactional DB); Integration Suite has no payload retention, so you cannot find out what actually arrived. Flottila fixes exactly that.

## What we explicitly rejected and why

| Rejected | Reason |
|---|---|
| Apache Camel (embedded or otherwise) | Blocking `Processor` contract, mutable Exchange, dozens of thread pools, six overlapping error-handling mechanisms, no backpressure, no persistence, JMX-only monitoring. Bending it to reactive is more expensive than a small custom core. We take its *ideas* (URI endpoints, component SPI, EIP vocabulary, idempotent repository, throttle, circuit breaker) and fix its weaknesses. |
| Elixir/BEAM | Superb concurrency, but no enterprise integration ecosystem (no JCo, weak XML/XSLT, no WS-Security/AS4/EDI) and low raw CPU throughput for transformations. |
| Kafka by default | Heavy to operate for a solo build. Postgres is the queue until measured otherwise; NATS JetStream is the step-up, Kafka only if a customer already runs it. |
| Kubernetes as requirement | Scaling means "run more processes"; Docker Compose / systemd is enough. K8s is an operational choice, never an architectural one. |
| Vert.x cluster event bus | Fights with K8s, stateful; use a shared queue (Postgres/NATS) between nodes instead. |
| Keycloak | Heavy JVM IdP. Platform is an OIDC *relying party*; enterprise customers bring their own IdP. |
| MinIO | AGPL and stripped community edition. Code talks S3 API; backend is customer's choice. |
| Custom DSL early | JSON/YAML flow config first; DSL only once real usage patterns emerge. |
| Event-bus hop per step | Latency and debugging hell. A flow is one pipeline inside one verticle. |

## Technology stack (all OSS, license noted)

- **JDK 21+** — virtual threads for blocking adapters.
- **Vert.x 4.5+** (Apache 2 / EPL) — event loops, HTTP/AMQP clients, verticles as deployment unit.
- **Mutiny** (Apache 2) — `Uni`/`Multi` reactive pipeline with backpressure.
- **PostgreSQL** — runtime state, outbox, work queue (`SELECT … FOR UPDATE SKIP LOCKED`), aggregation/idempotent/resequencer state, flow config, local users.
- **ClickHouse** (Apache 2) — monitoring history, metrics, search. Storage policy hot NVMe → S3 via TTL.
- **S3 API object storage** — large payloads, cold tiers, ingest queue. Backends: AWS S3/R2, SeaweedFS (Apache 2), Ceph RGW, Garage; customer picks.
- **Saxon-HE** (MPL 2) — XSLT 3.0 / XPath. Saxon-EE as optional plugin for streaming.
- **Woodstox** (Apache 2) — StAX for splitting large documents.
- **OpenTelemetry** + **Prometheus** (Apache 2) — platform-level tracing and metrics. Grafana is AGPL: fine for own ops, never embedded in product. Customer dashboards are our own UI over ClickHouse (React + ECharts) or Perses.
- **Quarkus** (Apache 2) + **React** (MIT) — control plane / admin UI, separate process from runtime.
- **NATS JetStream** (Apache 2) — future queue implementation when Postgres is outgrown.
- **ZITADEL** (Apache 2, Go) — optional IdP container for deployments without their own.

Licensing rule: Apache/MIT/PostgreSQL/MPL may be linked into closed-source. AGPL components may only run *alongside* as unmodified separate services, never embedded.

Minimal compose for v1: runtime + Postgres + ClickHouse + S3-compatible store. Four containers, ~3 GB RAM, one VPS.

## Core model

### Envelope — immutable, every step returns a new one
```java
record Envelope(String id, String flow, int step,
                Map<String,String> headers, Body body) {
  Envelope next(Body newBody, Map<String,String> h) { ... }
}
```
No setters. Immutability gives free snapshots for monitoring, safe sharing of parsed views, and safe retries.

### Body — bytes, never a consumable stream
```java
sealed interface Body {
  record Bytes(Buffer buf)     implements Body {}  // in-memory up to limit (~16 MB, configurable)
  record Spilled(Path file)    implements Body {}  // large, temp file
  record Remote(String s3Key)  implements Body {}  // archival / huge
  InputStream open();   // always a fresh stream, any number of times
  long size();
}
```
This fixes the CPI "reader consumed, body gone" problem and Camel's unreliable `StreamCache`. Bytes are decided at ingress; streams are disposable views.

Parsed views (`XdmNode`, `JsonObject`) are lazily memoized on the Envelope. Safe because immutable. A new envelope with the same body shares the cached tree by reference; a new body starts with an empty cache.

Steps declare what they need; the platform wraps them:
```java
interface Step     { Uni<Envelope> apply(Envelope e); }      // full control
interface XmlStep  { XdmNode apply(XdmNode in, Headers h); } // convenience
interface JsonStep { JsonObject apply(JsonObject in, Headers h); }
interface TextStep { String apply(String in, Headers h); }
```
Script steps (Groovy/JS) receive already-parsed `body` by content-type, never a reader.

### Stage — the single pattern contract
```java
@FunctionalInterface
interface Stage { Multi<Envelope> apply(Multi<Envelope> in); }
```
Stream in, stream out. Cardinality is in the type (1→1, 1→0/1, 1→N, N→1). Pipeline = composition of stages via `plug()`, recursive like Camel's `Pipeline`. Patterns as factory methods:

- `transform(f)` → `transformToUniAndConcatenate`
- `filter(p)` → `filter`
- `split(f)` → `transformToMultiAndConcatenate`
- `route(routes, otherwise)` → first matching predicate wins, target branch is itself a Stage receiving `Multi.of(e)`
- `aggregate(key, store, complete)` → state in Postgres, timeouts as DB deadlines
- Ordering: `Concatenate` = sequential/ordered; `Merge().withConcurrency(N)` = parallel
- Retry: `.onFailure(X.class).retry().withBackOff(...).atMost(n)` per step; final failure → `dlq.park(e, t)` (persistent)

Stage is stateless *as an object*: any state is an injected external store. Rule: **state whose loss loses messages goes to DB (aggregator, resequencer, idempotent consumer, DLQ); state whose loss only changes tempo may be in-memory (throttle per node, circuit breaker per node).**

Because aggregator state is a table, monitoring can show "batch 4711 waiting for 2 of 5, deadline in 3 min".

### Component SPI — reactive on both ends
```java
interface Consumer  { Multi<Envelope> stream(); }         // ingress, backpressure via request(n)
interface Producer  { Uni<Envelope> send(Envelope e); }   // egress
interface Component {
  String scheme();                              // "http", "sftp", …
  Consumer consumer(EndpointConfig cfg);
  Producer producer(EndpointConfig cfg);
}
```
Endpoint config as URI-like but with typed schema. Blocking SDKs (JCo, JDBC without async driver) wrapped:
```java
Uni.createFrom().item(() -> blockingCall(e)).runSubscriptionOn(workerPool);
```
Also offer a `BlockingProducer` / `BlockingConsumer` SPI for third-party adapter authors; platform wraps onto virtual threads. **Reactivity in the core, optional at the edges.**

Native Vert.x clients for HTTP/SFTP; own adapters for AS2/AS4, EDI, JMS, mail, SMB as needed.

## Runtime (no CamelContext)

```java
record Runtime(Vertx vertx, Map<String,Component> components,
               Stores stores, WorkerPools pools, MonitoringWriter monitor) {}
class FlowBuilder { Stage build(FlowConfig cfg); }   // JSON tree → plug() chain
```
Runtime is used **only at build time** to inject dependencies into stages, then steps back. At runtime a Stage holds only its own dependencies — no global lookup, no shared mutable state between flows. Plain DI, no framework required. `Runtime` per tenant = multitenancy. Test = `new FlowBuilder(fakeRuntime).build(cfg).apply(Multi.items(...))`.

One flow = one verticle = one pipeline on one event loop. Deploy/undeploy at runtime via admin API; undeploy drains in-flight. Ingress / processing (virtual-thread verticles) / egress; local event bus only for monitoring events.

Control plane (Quarkus admin API, React UI) is a separate process; runtime must keep running if it dies.

## Concurrency & performance

- I/O concurrency via event loops; CPU via `setInstances(N)`; message-level parallelism via `withConcurrency(N)`. Limit *concurrency*, not threads.
- Mutiny overhead is microseconds — irrelevant vs network/XML. Reactive wins on I/O-bound throughput and flat latency under load; CPU-bound (XSLT, signing) is library choice + cores.
- **The real bottleneck is store-and-forward fsync.** Per-message insert ≈ 1–2 ms → batch with `.group().intoLists().of(100, 10ms)` → tens of thousands/s. Persistence configurable per flow (at-most-once flows skip it).
- Expected on one 8-core box: HTTP passthrough tens of thousands req/s; with persistence 5–20k msg/s; with 100 KB XSLT hundreds to low thousands/s.
- Blocked-thread checker on; anything slow moves to worker/virtual thread.

## Persistence & delivery

- Store-and-forward from day one: write, then ACK. Outbox for egress, idempotent keys, persistent retry scheduler.
- Work queue = Postgres table with `SKIP LOCKED`; `WorkQueue` and `MessageStore` are interfaces so NATS can replace Postgres without touching stages.
- Per-partner ordering = `partition_key` column; a worker locks keys and processes them serially; different keys in parallel.

## Monitoring (the product)

- Snapshot after **every** step, not just in/out. Emitted async on event bus to a writer verticle; processing never waits for monitoring, but monitoring is not best-effort: a persistent buffer (Postgres table) sits between runtime and writer.
- Business correlation keys extracted at ingress (XPath/JSONPath from flow config) into indexed metadata. "Invoice 4711 — where is it, what happened, what came back" must answer < 1 s over a year of data.
- Two stores: **Postgres** for processing state + last N hours (operator sees now); **ClickHouse** for history/analytics. `MonitoringStore` interface with both implementations; small installs run Postgres only.
- Payloads: compressed (zstd), per-tenant encryption key. ≤ ~256 KB as ClickHouse column (ages to S3 via storage policy); larger directly in S3 under `tenant/flow/date/messageId/step`, reference in CH.
- Ingest path: runtime writes batches (Parquet/JSONEachRow) to S3 → ClickHouse `S3Queue` consumes. Runtime never depends on CH availability; S3 doubles as raw archive for rebuilds. Batch files, never one object per message.
- Retention per flow (invoices 10 years, heartbeats 1 day); ClickHouse TTL on partitions, S3 lifecycle. Never row-level DELETE in CH.
- Three views: per message (timeline with step latencies, payload at each point, error, replay/resend from any step), per flow (throughput, error rate, p95, backlog), per platform (event-loop blocking, pools, DB, disk).
- **Silence is an alert**: baseline per flow from history; alert on "usually 50/h, last hour 0".
- OpenTelemetry trace ID propagated through HTTP headers into backends.
- Cold-tier queries labeled "archive query" in UI (seconds, not ms).

## Scaling

- Vertical: `setInstances` + `withConcurrency`.
- Horizontal: add a process. Stages are stateless, pattern state is in Postgres → any node handles any message. HTTP behind LB; queue via `SKIP LOCKED`.
- Pollers (SFTP, mail, DB): lease per endpoint in Postgres (`endpoint, owner, expires_at`) — leader election without ZooKeeper.
- Global throttle needs shared counter (Postgres/Redis); per-node throttle (limit/N) usually acceptable.
- One huge document never scales — StAX split at ingress turns it into thousands of envelopes.

## XML / large documents

- Saxon-HE in core. XSLT step gets a tree up to a size limit (~50 MB, configurable).
- Above limit: automatic StAX split (Woodstox) by configured element → small envelopes → parallel transform → streaming aggregator writes header + fragments + footer to file/S3, no tree.
- Saxon-EE streaming (`xsl:mode streamable`, burst mode) as optional plugin with same SPI for customers holding a license. XSLT 3.0 streaming exists only in EE.
- Flat bulk formats (CSV, EDI): dedicated streaming parsers, not XSLT.

## Security / identity

- Platform is an OIDC relying party (Quarkus OIDC / nimbus). Roles and tenants are our tables mapped from claims.
- Local users fallback: table + argon2 + TOTP.
- Service accounts / partner API: API keys, mTLS, client-credentials.
- ZITADEL optional container for installs without an IdP.

## Repository structure (planned)

- `flottila-core` — Envelope, Body, Stage, patterns, SPI interfaces. No Vert.x dependency beyond `Buffer`.
- `flottila-runtime` — Vert.x, Runtime, FlowBuilder, stores (Postgres), queue, monitoring writer, S3 client.
- `flottila-components` — http, sftp, … one module per scheme.
- `flottila-monitoring` — ClickHouse schema, S3Queue ingest, query API.
- `flottila-control` — Quarkus admin API + React UI (later).

## First milestone

End-to-end `HTTP → validate → extract key → XSLT → HTTP` with store-and-forward, snapshots after every step, retry + DLQ, replay from a chosen step. Postgres only, no UI, no ClickHouse. Measure throughput and fsync batching before adding anything else.

## Open items

- Final brand name and domain (Flottila is working name; "Flotilla" is used by several unrelated OSS projects and is a generic word — weak as trademark).
- Project license (core OSS vs commercial control plane / monitoring split not yet decided).
- Size thresholds (in-memory body limit, XSLT tree limit, CH inline payload limit) — defaults proposed above, to be tuned by measurement.
- Flow config schema (JSON/YAML) — not designed yet.
