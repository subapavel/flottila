> Superseded by `docs/ARCHITECTURE.md` (v3.3). Kept because v3.3 §1 refers to this version for the rationale behind rejected options (§2 below) and the worked scaling example (§16). Not binding; do not edit.

# Flottila — Architecture

**Version 3.2 — September 2026.** This document replaces the v2 proposal and the original CONTEXT.md. It records every decision agreed so far and the reasoning behind it. Where a decision is still open, it says so in §22. Added in 3.1: authorization (§12), API management (§13), packages, artifacts and identity (§14), migration from SAP PO (§15). Changed in 3.2: the project is open source (§1, §3.1); vertx-auth-abac is a candidate, not a decision (§12.4, §22).

It is written to be read from the top once, and then used as a reference. Each section states the decision first and the reasoning after.

---

## 1. What Flottila is

A reactive enterprise integration platform where **monitoring is the product**. The runtime is observable by construction: every message, every step, every payload can be recorded, searched and replayed. It also delivers messages reliably and fast — but the reason it exists is that SAP PO monitoring is slow and SAP Integration Suite keeps no payloads, and twenty years of running those systems taught us what the answer should look like.

It is built as a **composition of best-of-breed components** — the Supabase model — delivered as one product: one installer, one version manifest, one health screen, one CLI. The value is that the pieces are the best available for their job and the customer never has to assemble them.

**Flottila is open source.** The whole platform — runtime, components, control plane, UI, CLI, installer — is developed in the open under a permissive license until it works end to end. Commercial add-ons may come later, on top, through the same extension and plugin boundaries any third party can use; the open-source platform is never crippled to sell them (§3.1).

### The six rules that decide everything else

1. **A message is never lost.** Before we tell a sender "received", the message is on disk. Everything else is negotiable; this is not.
2. **Monitoring is the product.** Every step of every message can be recorded and searched. How much is recorded is the customer's choice, per flow.
3. **Use Vert.x as it is.** Verticles, futures, streams, event bus, worker pools — we build on them, we don't wrap them.
4. **Best-of-breed, one supported stack.** Each concern gets the component that is best in the world at it. Customers choose at the edges (which S3, which IdP, which managed broker), never in the core.
5. **Nodes are stateless.** Anything shared lives in a store every node can reach. Adding a node means starting one more copy.
6. **AI-ready from the first line.** Everything is text with a schema, everything the UI can do the API can do, and the feedback loop is fast and honest.

---

## 2. What we rejected, and why

| Rejected | Reason |
|---|---|
| Apache Camel, embedded or otherwise | Blocking `Processor` contract, mutable Exchange, dozens of thread pools, overlapping error-handling mechanisms, no backpressure, no persistence. We keep its *ideas* (URI endpoints, component SPI, EIP vocabulary, mock endpoints, stream caching lessons) and fix its weaknesses. |
| Elixir/BEAM | No enterprise integration ecosystem (JCo, WS-Security, AS4, EDI, XSLT) and weak CPU throughput for transformations. |
| Vert.x cluster manager / clustered event bus | Still doesn't persist anything; a distributed system inside the process; fights Kubernetes' own discovery and scheduling. Nodes share state through Postgres, Kafka and Valkey instead. |
| Event-bus hop between steps | Latency, memory-only hand-over, debugging pain. A flow is one pipeline of method calls. |
| Kubernetes as a requirement | It is one of the environments, never the architecture. |
| Minimal "two containers" stack | Our own earlier idea. It optimised for a solo builder's support burden, not for the product. Replaced by the composed stack in §3. |
| NATS JetStream | Would be a different API from Kafka and therefore a real design fork. The backbone is Kafka-shaped. |
| Redpanda as the *shipped* broker | BSL 1.1 forbids offering it as a service; tiered storage is Enterprise-only. Supported as a customer-supplied broker because it speaks the Kafka protocol. |
| Keycloak | Heavy JVM IdP. We are an OIDC relying party; ZITADEL is the optional bundled IdP. |
| MinIO | AGPL and a stripped community edition. We speak the S3 API; the backend is the customer's choice. |
| PeerDB for Postgres → ClickHouse sync | Elastic License 2.0. ClickHouse's built-in dictionaries read from Postgres directly. |
| Grafana embedded in the product | AGPL. Runs alongside for ops; customer dashboards are our own UI. |
| Custom DSL early | JSON/YAML with a JSON Schema first; a DSL only if real usage patterns demand one. |
| Mutiny as the pipeline contract | Leaning no (§22). Vert.x `Future` plus `ReadStream`/`WriteStream` already give composition and backpressure; a second reactive vocabulary must earn its place. |
| Vert.x 4.5 | The prototype's version. Vert.x 5.1 is a different generation: Future-only, `VerticleBase`, explicit threading models, io_uring, new config layer. |
| JDK 21 | JDK 25 (LTS) — see §4.1. |

---

## 3. The stack

Every component is open source under a license that can be included in an Apache 2 codebase (Apache 2, MIT, BSD, PostgreSQL, MPL, EPL, UPL), or runs strictly *alongside* as an unmodified separate service (AGPL, BSL). SAP JCo is not redistributable at all: the JCo adapter is an optional module with JCo as a `provided` dependency the customer downloads from SAP.

| Component | License | Role | Why this one |
|---|---|---|---|
| **Runtime** — Vert.x 5.1, JDK 25 | Apache 2 / EPL | runs flows | ours |
| **PostgreSQL** | PostgreSQL | transactional state: config, partner directory, routing, wallet, processed ids, aggregator state, ordered XI queues, extension metadata, hot monitoring | the only place with transactions and row locks; the **single source of truth** |
| **pg_clickhouse** | Apache 2 | Postgres extension: foreign data wrapper + remote query execution into ClickHouse | one SQL door for the control plane and UI; joins Postgres state with ClickHouse history in one statement |
| **ClickHouse** | Apache 2 | monitoring history, metrics, **payload storage and search** | columnar, 10–20× compression, sub-second over billions of rows, native JSON type with path access |
| **Apache Kafka** | Apache 2 | message backbone: async hand-over between flows and nodes, burst ingest, replay from the log, customers' streaming integrations | durable partitioned replayable log; KRaft (no ZooKeeper); tiered storage built in; the protocol every enterprise speaks. Customers may substitute Redpanda, Confluent, MSK, Aiven |
| **S3-compatible object store** | customer's | large bodies, cold archive, ClickHouse and Kafka cold tiers | the only sane home for terabytes of payloads. AWS S3, R2, Ceph RGW, SeaweedFS (Apache 2), Garage |
| **Valkey** | BSD | shared cache, global throttles, distributed locks and leases, partner token cache, config-change pub/sub, UI sessions | sub-millisecond shared state; Redis itself is now AGPL |
| **ZITADEL** | Apache 2 | identity provider, bundled for customers without one | small Go service; customers with Entra ID/Okta/Keycloak bring their own |
| **Saxon-HE** | MPL 2 | XSLT 3.0 / XPath / XQuery | Saxon-EE as an optional plugin for customers with a license (streaming XSLT) |
| **Woodstox** | Apache 2 | StAX parsing for splitting large XML | |
| **Caffeine** | Apache 2 | node-local caches | |
| **OpenTelemetry, Micrometer, Prometheus** | Apache 2 | tracing and metrics | |
| **OTel Collector, Grafana, Tempo, Loki** | Apache 2 / AGPL | ops observability, alongside only | |
| **Control plane** — Quarkus + React | Apache 2 / MIT | admin API, UI, installer, alerting engine, MCP server | ours |
| **GraalJS, Groovy** | UPL / Apache 2 | script steps | |

Footprint for a serious install: 16–32 GB RAM, three to five runtime nodes in production, one large box for development. A hosted offering exists for customers who don't want to run it.

### 3.1 Licensing and governance

- **License: Apache 2.0 for everything we write.** Permissive, enterprise-safe, patent grant included. It lets customers write closed-source extensions and adapters against `flottila-api` (a copyleft license there would force their code open), lets integrators embed the platform, and costs nothing in adoption. The name and logo are protected by trademark, not by the code license.
- **Contributions under the DCO** (sign-off), not a CLA. Apache 2 contributions may be shipped inside commercial products anyway, so no relicensing right is needed; a CLA would only add friction.
- **The commercial boundary is architectural.** Add-ons plug in through the extension SPI (§7) and control-plane plugin points that any third party can use. Candidates: a hosted offering, the PO migration tooling (§15), premium B2B packs (AS4/EDI trading-partner management), enterprise identity (SCIM, advanced audit), the Saxon-EE streaming plugin, support and SLAs. What is never commercial: reliability, monitoring, security, the installer, the SPI.
- **Open-source hygiene from day one**, because enterprises ask: public repository, `ARCHITECTURE.md` (this document) and ADRs in the repo, `SECURITY.md` with coordinated disclosure, signed releases (Sigstore), SBOM (CycloneDX) per release, semantic versioning with a public compatibility promise for `flottila-api`, public CI.
- **One supported stack, many possible stacks.** The version manifest is what we test; the clean interfaces (`WorkQueue`, `MonitoringStore`, `SecretBackend`, `AuthorizationProvider`) are where the community will plug in alternatives, and we welcome that rather than fight it.

---

## 4. The runtime core

### 4.1 Platform: Vert.x 5.1 on JDK 25

- **Vert.x 5.1** — Future-only API, `VerticleBase`, explicit `ThreadingModel` (event loop / worker / virtual thread), `Vertx.builder()` with tracer and metrics injected, new transport-agnostic config classes, io_uring transport, Unix domain sockets, Jackson 3 auto-selection.
- **JDK 25 (LTS).** JEP 491 (Java 24) removed pinning of virtual threads inside `synchronized`, which makes "block freely on a virtual thread" safe even in libraries we don't control. JDK 25 adds final Scoped Values (tenant, message id and trace id propagation without ThreadLocal), the AOT cache and compact object headers (faster restarts, less memory), and a Key Derivation Function API (wallet).
- **JNI still pins.** SAP JCo is JNI, so the JCo adapter — and anything else native — runs on a dedicated *platform-thread* `WorkerExecutor`, never on the flow's virtual thread. SAP's supported-JDK list for JCo 3.1 is to be verified before commitment.
- Netty types are internal in Vert.x 5; we never touch them.

### 4.2 Envelope — the message

Immutable. Every step returns a new Envelope; the old one is untouched. This gives free snapshots, safe retries, and safe sharing of parsed views.

| Part | Contents | Persisted |
|---|---|---|
| `id`, `flow`, `flowVersion`, `step` | identity and position | yes |
| `headers` | transport headers (HTTP, JMS, XI) | yes |
| `props` | typed flow state (jsonb), including pinned config versions | yes — needed for replay |
| `keys` | business correlation keys extracted at ingress | yes, indexed for search |
| `attachments` | by reference only | reference |
| `body` | see 4.3 | by reference (content hash) |

A single Camel-style bag was rejected: it makes replay and search impossible to reason about.

Parsed views (`XdmNode`, JSON tree) are lazily memoized on the body. Ten steps that only read parse once; a new body starts with an empty cache. No step is allowed to force a parse "just to look".

### 4.3 Body — one strategy, from the first byte

The body is a **re-readable, size-aware, streamable thing from the first byte to the last**, and every component and every step honours that. One link that quietly materialises the body breaks the whole chain (WSO2's failure mode).

**The contract**

```java
interface Body {
  long size();                     // long, never int; fixed once the upload completes
  ReadStream<Buffer> open();       // fresh cold stream, fetch mode, any number of times
  Future<Buffer> whole();          // only below the tree limit; fails loudly above it
  String hash();                   // computed while bytes arrive, never in a second pass
  Location location();             // MEMORY | SPOOL | OBJECT_STORE — steps don't care
}
```

Shared by reference between envelopes. Content-addressed: same bytes, one copy.

**Ingress — one sink, three tiers.** Every consumer pipes bytes into the same `BodySink`, which hashes as it goes and picks the tier as bytes arrive (chunked uploads without Content-Length are no special case):

1. **memory** up to the in-memory limit — default **1 MB**. Camel, Mule and NGINX all sit between 16 KB and 512 KB; a large buffer times many in-flight messages is the heap gone.
2. **spool** above that — local file, always encrypted with the tenant key.
3. **object store** above a second limit, or whenever the flow is at-least-once and an object store is configured — multipart upload straight from the sink, so the durable copy exists before the ACK and the node stays stateless.

Two more rules on the sink: a **node-wide memory budget** (total in-memory body bytes capped at a share of heap; when exceeded, new bodies spill regardless of size — Camel's heap-pressure rule made explicit) and a **finite per-flow maximum body size** by default (APISIX shipped "unlimited" and spent years adding caps).

**Steps declare how they read.** The step type decides, not the flow author:

- **whole** — needs a tree or the full text: XSLT, XPath, JSON mapping, XSD validation. Works up to the tree limit.
- **stream** — bytes in, bytes out: compression, encryption, signing where the format allows, copy to SFTP/S3, pass-through.
- **split / assemble** — a tokenizer (StAX for XML, event parser for JSON, record parser for CSV/EDI) turns one big body into many small envelopes; a streaming assembler writes header + fragments + footer back without holding the whole.

**Deploy-time check:** a flow whose maximum body size exceeds the tree limit and contains a *whole* step without a *split* before it is rejected at deploy, naming the step. Auto-inserting splits was rejected — it changes the meaning of a mapping.

**Egress — pipe, never buffer.** Producers pipe `open()` into the client (`HttpClientRequest`, SFTP channel, S3 multipart). A retry calls `open()` again. A producer that needs the whole body (a signature over the payload) uses `whole()` under the same tree limit.

**Snapshots and archive are the same bytes.** The spilled object is content-addressed and already in the store; a monitoring snapshot of a large body is a reference. Written once, never twice.

**Backpressure end to end.** Ingress `ReadStream` pauses when the flow's in-flight limit is reached; the sink's `WriteStream` reports a full queue; egress pipes stop pulling when the partner is slow. Nothing has an unbounded queue.

**Flow body modes**, named so users of Mule and CPI recognise them: `spill` (the default above), `memory-only` (tiny sync flows, no temp files, hard limit), `pass-through` (nothing reads the body; ingress pipes to egress; no content snapshots).

Rejected: spill locally then upload in the background (leaves messages on a dead node). Noted for later: Mule-style on-disk indexed random access for huge XML that needs XPath.

### 4.4 Steps and pipelines

```java
interface Step { Future<Envelope> apply(Envelope e); }         // full control
interface XmlStep  { XdmNode apply(XdmNode in, Headers h); }    // convenience wrappers
interface JsonStep { JsonObject apply(JsonObject in, Headers h); }
interface TextStep { String apply(String in, Headers h); }
```

A pipeline is a composition of stages built by `FlowBuilder` from the flow config. Patterns are factory methods: `transform`, `filter`, `split`, `route` (first matching predicate wins), `aggregate` (state in Postgres, deadlines as DB rows), `call` (subflow, §6.4), retry with backoff per step, `dlq.park` on final failure.

State rule: **state whose loss loses messages goes to Postgres** (aggregator, resequencer, idempotent consumer, DLQ); **state whose loss only changes tempo may be in memory or Valkey** (throttles, circuit breakers, caches). Because aggregator state is a table, monitoring can show "batch 4711 waiting for 2 of 5, deadline in 3 minutes".

### 4.5 Component SPI

```java
interface Component {
  String scheme();                                  // "sftp", "xi", "kafka"
  ReadStream<Envelope> consumer(EndpointConfig c);  // ingress, backpressure via fetch(n)
  Producer producer(EndpointConfig c);              // egress: Future<Envelope> send(Envelope)
  JsonSchema configSchema();                        // the UI renders a form from it
  Future<Health> check(EndpointConfig c);           // "can I reach this endpoint?"
}
```

A consumer is a Vert.x `ReadStream` — the same shape as an HTTP request, a Kafka consumer or an SFTP poll. The flow doesn't care what is behind it. Adapter authors with blocking SDKs implement `BlockingConsumer` / `BlockingProducer`; the platform runs them on virtual threads (or platform threads for JNI). Reactive in the core, optional at the edge.

### 4.6 Runtime and dependency injection

```java
record Runtime(Vertx vertx, Map<String,Component> components, Stores stores,
               WorkerPools pools, Clock clock, IdGenerator ids, MonitoringWriter monitor) {}
class FlowBuilder { Pipeline build(FlowConfig cfg); }
```

The `Runtime` record is used at build time to inject dependencies into stages, then steps back. No global lookup, no static singletons, no framework. Time and ids come through it so tests can fake them. `Runtime` per tenant is multitenancy. A test is `new FlowBuilder(fakeRuntime).build(cfg)` and envelopes pushed through.

### 4.7 Verticles and threading

- **One flow = one verticle class**, deployed as `ThreadingModel.VIRTUAL_THREAD`, with **instances = number of cores**. Each instance runs one task at a time, like any verticle.
- **One virtual thread per in-flight message, dynamically.** When a task `await()`s a future, Vert.x lets the verticle take the next event on a fresh virtual thread. Message 1 awaits the Postgres write → message 2 starts → it awaits the HTTP call → message 3 starts. Concurrency grows with the traffic; nothing is forecast.
- **Never block raw in the flow verticle.** I/O goes through Vert.x clients and `await()`. Blocking libraries (JDBC, Saxon, a legacy SDK) go through `executeBlocking` on a named `WorkerExecutor`, and the flow `await()`s the result. The blocked-thread checker is a test failure.
- **A per-flow max in-flight** (default 256) bounds the dynamic pool; ingress backpressure holds the rest at the door.
- **Instances are for CPU**, not for traffic: one instance uses one core; eight instances use eight.
- **Sync flows** (request/reply, XI BE) run the whole pipeline in the endpoint verticle and answer on the same connection. **Async flows** (EO/EOIO, files, queues) hand over from the endpoint verticle to flow verticles through a durable store — Kafka or the Postgres queue — never through the event bus.
- **Flow versions are immutable.** Deploying 2.4.1 leaves 2.4.0 running until its in-flight messages drain; rollback is "deploy 2.4.0 again".
- Undeploy cleans up everything the verticle created (servers, clients, timers, consumers, executors) — Vert.x does this itself.

### 4.8 The event bus — exactly three uses

The bus is an in-memory post office inside one JVM. Delivery is best-effort; a slow or restarting consumer can lose messages. Therefore:

1. Monitoring events → writer verticle (behind a persistent buffer, §10).
2. Admin commands: deploy, undeploy, pause, reload config.
3. Local fan-out of config-change notifications that arrive from Valkey.

The customer's message never travels on it. Vert.x's old polyglot promise (JavaScript/Ruby verticles) no longer exists; scripts run in-process as normal steps (§7.3), and code in other languages is an external service (§7.4).

---

## 5. Messaging, delivery guarantees, ordering

### 5.1 Store-and-forward

For at-least-once flows the message (and its body hash) is written durably **before** the acknowledgement goes back — HTTP 202, SFTP delete, JMS ack, XI transport ack. Sync request/reply and at-most-once flows skip this write. The write is not monitoring; it is the guarantee, and it stays on when recording is off.

Writes are batched (one commit for many messages) because fsync is the real bottleneck; the prototype measured batched persistence at ~80 % of no-persistence throughput. Targets: sync overhead < 2 ms p50, async durable ACK < 5 ms p99, measured on native Postgres.

### 5.2 The two durable hand-overs

| | Kafka | Postgres queue (`SKIP LOCKED`) |
|---|---|---|
| Used for | async hand-over between flows and nodes, burst ingest, replay from the log, customers' streaming integrations | XI-style ordered queues with block-on-failure, aggregation deadlines, anything needing row locks |
| Ordering | per partition | per `partition_key`, one node claims a key and processes serially |
| Failure semantics | consumer offset, redelivery | row state, DLQ, block or park |
| Why not the other | ordered *blockable* per-queue delivery is a row-lock problem; unrelated XI queues would share Kafka partitions and block each other | Postgres can queue but cannot stream or replay weeks of history |

Both sit behind one `WorkQueue` interface; the flow's QoS picks the implementation. The Kafka *protocol* is the contract; Apache Kafka is shipped; Redpanda, Confluent, MSK or Aiven can be pointed at instead.

### 5.3 Quality of service — the XI vocabulary

Per flow: **BE** (best effort, sync, no save), **EO** (exactly once), **EOIO** (exactly once in order).

- **Exactly once** is at-least-once delivery plus refusal of duplicates by message id — the same thing SAP does. A `processed_ids` table in Postgres remembers what was handled; a duplicate is acknowledged, dropped and recorded. On the way out the receiver must dedupe by our id; SAP proxies do. For a receiver that can't, the flow is configured `at-least-once` and the UI says so — we don't pretend.
- **In order:** the XI queue name (or any ordering key from config) is the `partition_key`. When a message in an ordered queue fails: **block** (XI behaviour; the flow screen shows the blocked queue and what waits behind it) or **park and continue** (failed one to the DLQ). Default: block.
- **Acknowledgements** — three states on the message record: **transport** (on disk), **system** (delivered to the receiver), **application** (receiver processed it; arrives later as a separate message matched by id). Producing them in the caller's protocol is the adapter's job; the **XI adapter** speaks XI 3.0 over SOAP, reads `SAP:Main` (message id, QoS, queue id, ack requested) and sends acks back.

### 5.4 Retry, dead letters, replay

Retry with backoff per step. After the last attempt the message is parked in the DLQ with its last saved snapshot and a structured reason. Replay from any recorded step; resend from egress; "replay with pinned config" or "replay with current config" is the operator's explicit choice.

---

## 6. Flows

### 6.1 Flow config

A flow is identified as `tenant / package / id @ version` (§14). It is a JSON/YAML document with a published **JSON Schema** — strict, versioned, documented with examples. Validation errors are structured (step, path, expected, got), because agents and humans both iterate on them. Steps reference mappings, schemas and subflows by name and version.

### 6.2 Git first

Flows, mappings, subflows, tests and extension sources live in git. The UI can edit them, but git is the source of truth. `flottila deploy ./flows/` runs from CI; environments are branches or folders; `flottila test` runs before deploy. Partner directory and routing tables are data (Postgres) but exportable to files for the same treatment.

Repository layout is package-based — see §14 — with an `AGENTS.md` describing the conventions so an agent can navigate it.

### 6.3 Dispatcher flows

One endpoint — `/xi` for every proxy interface from an SAP system — reads a routing key (sender system, interface, namespace from the XI header; path, header or body field for HTTP), looks it up in a **versioned routing table** in Postgres, and forwards:

- **sync target (BE):** the dispatcher calls the target pipeline directly in the same verticle; the reply returns on the same connection.
- **async target (EO/EOIO):** the dispatcher writes the message to the durable queue with `target_flow` and its partition key, acknowledges the sender, and the target flow's instances take it from there.

Unknown routing key: a clear error (sync) or DLQ with reason "no route" (async). Never silently dropped. A dispatcher is a normal flow with a `route` step, so it gets recording, retry and monitoring like any other.

### 6.4 Inter-flow communication — three mechanisms

| Scheme | Behaviour | Survives crash | Crosses nodes |
|---|---|---|---|
| `call: <subflow>` | local process call: nested stage, same verticle, same thread, same message; subflows in a versioned shared library, pinned by version | with the parent | with the parent |
| `flow://<name>` | sync direct call in the caller's verticle (Camel `direct:`, CPI ProcessDirect); looked up at call time through a registry so redeploys are safe; child message with parent id; deploy-time dependency and cycle checks | no — caller sees the error | no |
| `queue://<name>` | async durable hand-over via Kafka or the Postgres queue; target has its own retry, DLQ and ordering; only configured props are persisted | yes | yes |

Headers always cross; the body is shared by reference; props cross fully for `call` and `flow://`, selectively for `queue://`. The guide for flow authors: reuse logic → `call`; need an answer → `flow://`; hand over and forget → `queue://`.

### 6.5 Partner directory

Flows in git are **code**; partner details are **data**. The directory is a table of entries keyed by partner and, optionally, interface: host, folder, format, mapping, ack mode, secret references, free-form parameters. A flow references them as placeholders — `sftp://${partner.host}/${partner.folder}`, `secret://${partner.id}/password`, `xslt: ${partner.mapping}` — resolved when the message is processed. Stored in Postgres, edited in the UI or via API, versioned with who/when, exportable. Environment profiles (`dev`, `test`, `prod`) hold different values for the same keys — this is also how tests redirect every partner to a mock.

### 6.6 Versions pinned per message

At ingress a message resolves its flow version, partner entry version and routing entry version once, and records them in its props. Monitoring shows which config a message ran with; replay uses the same versions unless the operator picks "current". Without this, caches would make the monitoring product lie politely.

### 6.7 Deploy-time checks

Body size versus step kinds (§4.3); dependencies between flows exist and are deployed; no `flow://` cycles; extension version ranges (§7.5); schema validity; secrets referenced exist in the wallet. Errors are structured and name the step.

---

## 7. Components and custom code

### 7.1 Built-in components

HTTP(S) (server and client, HTTP/1.1, HTTP/2), XI 3.0, file, SFTP/FTPS, Kafka, JMS/AMQP, SAP RFC and IDoc via JCo, mail, AS2/AS4, SMB, databases (Postgres, MySQL, MS SQL, DB2, Oracle via Vert.x reactive clients; JDBC on a worker pool for the rest), `mock://` for tests, `flow://` and `queue://` (§6.4).

### 7.2 Packaging

One Maven module = one jar per component, discovered with `ServiceLoader`, shipping its config schema, health check and metrics. Built-in components share one classpath until a conflict appears; custom code does not (§7.5). Steps are pluggable the same way: `xslt`, `xquery`, `map` (declarative JSON mapping), `validate` (XSD, JSON Schema), `groovy`, `js`, `ai` (§19), `cache`, `split`, `aggregate`, `route`.

### 7.3 Scripts

Groovy and JavaScript (GraalJS) inline in the flow config, for small logic. They receive the parsed body, headers and props, plus a platform API: partner directory, cache, HTTP client, named datasources, secrets by reference. Sandboxed: no filesystem, no raw sockets, no reflection into the platform.

### 7.4 Extensions — the replacement for PO's custom EJBs

Four kinds of custom code, in rising weight: scripts (7.3); **in-process Java extensions**; custom components; **out-of-process services** in any language over HTTP/gRPC, called like a partner with the same save-before-send rule — the first-class path for untrusted, native or non-Java code.

An in-process extension implements the same `Step` (or `Component`) interface the built-ins implement:

```java
@Extension(name = "acme-enrich", version = "2.1.0")
public final class EnrichCustomer implements Step {
  private final SqlClient crm;
  public EnrichCustomer(Services s) { this.crm = s.datasource("crm"); }   // injected at build time
  @Override public Future<Envelope> apply(Envelope e) {
    return crm.preparedQuery("select tier from customers where id = $1")
              .execute(Tuple.of(e.keys().get("customerId")))
              .map(rows -> e.withProps("tier", rows.iterator().next().getString("tier")));
  }
}
```

**Platform-managed datasources** replace JNDI: configured in the admin UI (driver, host, wallet reference, pool size), pooled, health-checked, metered, audited. No XA; the documented pattern is idempotent keys plus the outbox, with an SDK helper.

### 7.5 Extension packaging, isolation, versioning

- A jar with `META-INF/flottila-extension.json`: name, version, what it provides, config schemas, supported platform-API range.
- **Per-extension-version class loader** whose parent sees only the platform API jar. The customer's Jackson, JDBC driver or vendor SDK clash with nothing; two versions of one extension run side by side while flows migrate. (PO's single class loader was the first fault; this fixes it.)
- Flows pin the extension version; deploying 2.2.0 changes nothing until a flow says so. The platform API is semantically versioned; the declared range is checked at deploy.
- Git-first: the extension repo builds in CI; `flottila deploy` uploads; the control plane stores metadata in Postgres and bytes in S3; nodes fetch and load. Audit log of who deployed what, with hash; optional artifact signing.
- The extension SDK ships the fake runtime, fake datasources and the contract tests; an extension is tested in IntelliJ against a Maven dependency, without a server.

### 7.6 Rules extensions live under

Same as ours: they run on the flow's virtual thread (plain blocking is fine); native code declares itself and gets a platform thread; they declare a body kind; per-step timeout and memory limits apply; snapshots, durations and errors are recorded **with the extension name and version attached**; they may add business keys and metrics through the API.

### 7.7 When an extension misbehaves

Java has no walls inside a process: an extension can exhaust the heap, spin a core, leak handles, crash natively or call `System.exit()`, and no class loader prevents it — the `SecurityManager` that could was removed in JDK 24. The policy is therefore **survivable, not impossible**:

- Messages are durable before any extension runs, so a dead node loses nothing; `-XX:+ExitOnOutOfMemoryError` turns a sick JVM into a dead one on purpose; the process restarts in seconds.
- Blame is pinned: every span, snapshot and metric carries the extension version; the platform screen shows "memory climbing since acme-enrich 2.1.0 was deployed at 10:03".
- A per-extension circuit breaker pauses its flows on deadline violations, failure counts or CPU consumption, and notifies the operator.
- A deploy-time bytecode scan flags `System.exit`, `Runtime.halt`, thread creation, `Runtime.exec`, `Unsafe`, JNI — warn or refuse, per install policy.

In-process by default for trusted code; out-of-process is a first-class option, not a fallback.

---

## 8. Caching

Postgres is the truth. Every node keeps a hot copy in its heap; Valkey holds what must be identical across nodes at the same moment, or fetched once per cluster. A cache miss falls through to Postgres, never to nothing.

| Data | Node-local (Caffeine) | Valkey | Why |
|---|---|---|---|
| Partner directory, routing tables, flow config | yes | warm copy nodes load from on start | a restarting node is serving in seconds instead of pulling thousands of rows from Postgres |
| Compiled XSLT / XSD / scripts | yes, keyed by content hash | no | JVM objects; same content, same object, thread-safe, reused across instances |
| Decrypted secrets | yes, short TTL | no — never leaves the process that needs them | |
| Partner OAuth/JWT tokens | yes | **primary**, refreshed ahead of expiry by whichever node gets there first | twenty nodes don't hit a partner's token endpoint twenty times |
| Global throttles and rate limits | no | yes — atomic counters with expiry | one counter for "100 calls/min to partner X" across the cluster |
| Poller leases, distributed locks | no | yes — locks with TTL (moved here from Postgres) | |
| `cache:` step around external lookups | yes | when the flow asks | shared when a rate limit or exactly-once lookup demands it |
| UI sessions, in-progress search results | no | yes | the control plane runs more than one instance |
| ClickHouse query results | — | — | ClickHouse has its own query cache |

**Never cached:** processed ids, aggregator state, ordered-queue state, the DLQ. Losing a cache costs a reload; losing these loses a message. No read-through cache in front of the dedupe table — it must see the newest write from every node.

**Invalidation.** The control plane commits to Postgres, then publishes `partner-x:v42` on a Valkey channel. Every node reloads that row from Postgres (the message is a hint, the database is the truth), swaps it into its local cache and republishes on its event bus. Sub-10 ms across the cluster. Because pub/sub isn't durable: full reload after any reconnect, and row versions on every cached entry for cheap comparison.

**Operations.** Valkey persistence off or minimal; memory bound with eviction; every cache has a byte budget and exports hit ratio, size and age; the platform screen shows "config v42 active on A, B, C; D still on v41". HA via Sentinel or Valkey cluster; single instance for development; the Vert.x Redis client speaks the protocol.

---

## 9. Data stores

### 9.1 Postgres — the source of truth

Tenants, users, roles; flows and versions; partner directory and routing tables with history; wallet; processed ids; aggregator and resequencer state; ordered queues; DLQ; extension metadata; monitoring "now" (in-flight messages, the last N hours); the persistent monitoring buffer. Primary + streaming replica with Patroni (VMs) or CloudNativePG (Kubernetes); NVMe.

### 9.2 pg_clickhouse — one SQL door

An Apache 2 Postgres extension by ClickHouse (July 2026; Postgres 13–18, ClickHouse 22–25): a foreign data wrapper plus remote query execution, with predicate, aggregate and join pushdown, binary protocol with compression, TLS. Rules:

1. **Reads through the FDW only.** Ingest goes runtime → batches → ClickHouse, never through a foreign table.
2. **Pushdown or nothing.** Standard filters, aggregates and joins push down. ClickHouse-specific queries — JSON subcolumn paths, ngram indexes, the payload-search functions — go through the extension's raw remote-query path as native ClickHouse SQL, wrapped in a Postgres function so the UI still only talks to Postgres. Every search query is `EXPLAIN`-tested for pushdown in CI.
3. **Dimensions flow the other way.** ClickHouse dictionaries read partner, flow and tenant tables directly from Postgres for fast joins inside ClickHouse. No PeerDB.

The control plane and UI have one datasource. Permissions are Postgres roles. This pattern was already proven in Qamelo.

### 9.3 ClickHouse — history, metrics, payloads

- Tables partitioned by day and tenant; hot NVMe tier, cold S3 tier by storage policy; TTL per flow (invoices ten years, heartbeats one day). Never row-level `DELETE`.
- Payloads zstd-compressed, encrypted per tenant, inline up to ~256 KB, larger as S3 references under `tenant/flow/date/messageId/step`.
- Ingest: the runtime writes batch files (Parquet / JSONEachRow) to S3; ClickHouse `S3Queue` consumes. The runtime never depends on ClickHouse availability; S3 doubles as the raw archive for rebuilds. Batch files, never one object per message.
- Two replicas + three Keeper nodes in production.

### 9.4 Object store

Large bodies at ingress (multipart from the sink), the archive, ClickHouse and Kafka cold tiers, extension artifacts. Any S3-compatible backend; lifecycle rules per retention class.

### 9.5 Valkey

Cache, locks, counters, pub/sub, sessions — §8. Nothing whose loss loses a message.

---

## 10. Monitoring — the product

### 10.1 What can be recorded

After a step: message id, flow and version, step name, timestamp, duration, headers, business keys, body hash, error (structured), acknowledgement state, pinned config versions, extension version if any, the calling consumer or application (§13) — and the body itself, once per distinct content.

Business keys — invoice number, order id, meter point — are extracted at ingress by XPath/JSONPath from the flow config into indexed columns. "Where is invoice 4711, what happened, what came back" answers in under a second over a year of data.

### 10.2 The recording setting

Per flow, overridable per step:

| Knob | Values |
|---|---|
| level | `all` (after every step) / `ends` (in and out) / `errors` / `off` |
| payload | `keep` / `metadata-only` |

Defaults: `all` + `keep` in development, `ends` + `keep` suggested in production. Counters and metrics work at every level. What lower levels give up, and the UI says so: replay from the middle needs that step's snapshot; "what did the message look like before the mapping" needs `all`. The at-least-once save at ingress is independent of this setting.

### 10.3 The pipeline

```
flow verticle ──(event bus)──▶ writer verticle ──▶ Postgres buffer table
                                                        │
                                          batch files ──▶ S3 ──▶ ClickHouse (S3Queue)
```

Processing never waits for monitoring; monitoring is not best-effort either — the persistent buffer sits between them, and the event-bus consumer's buffer bound is where "monitoring can't keep up" is detected and spilled.

### 10.4 Payload search — the feature that sells

Three layers behind one search box:

1. **Configured keys** — extracted at ingest, indexed columns. Exact and instant; covers most of what an operator types.
2. **Ad-hoc JSONPath over JSON payloads** — ClickHouse's native JSON type makes every path a queryable subcolumn. `payload.order.customer.id = '4711'` works over a year of data without anyone having configured the path.
3. **Ad-hoc XPath over XML payloads** — no columnar store has an XPath engine, so two layers together: every XML payload is also stored as a lossless **JSON projection** (elements, attributes, namespaces, order), so simple XPath (child steps, attributes, equality predicates) translates to a JSON path and runs natively; for full XPath 3, ClickHouse *narrows* the candidate set (time, flow, partner, an ngram index on the text) and the runtime evaluates the real XPath with Saxon over the candidates. Cold-tier and unrestricted queries are labelled "archive query — seconds" in the UI.

PO can't do this (transactional DB); Integration Suite can't (no payloads). Search also matches on **origin names** (§14.3): a message can be found by the PO interface name it used to have.

### 10.5 Three screens

1. **Message:** timeline of steps with latencies, payload at each recorded point, structured error, ack status, parent/child tree across flows, pinned config versions, calling application, "replay from here", "resend", "save as test case".
2. **Flow:** throughput, error rate, p95, backlog, DLQ size, blocked ordered queues, over time; the version currently active; usage per consumer.
3. **Platform:** per node — event-loop blocking, worker pools, in-flight counts, queue depth, Kafka lag, Postgres, ClickHouse ingest, Valkey, disk; config versions active per node; extension health.

**Silence is an alert:** a baseline per flow learned from history; "usually 50/h, last hour 0" alerts before the partner calls.

---

## 11. Tracing, metrics, alerting

- **Tracing** is the technical cousin of monitoring: short retention, shows that the HTTP call to SAP took 800 ms of which 700 was TLS. `Vertx.builder().withTracer(new OpenTelemetryTracingFactory(otel))` traces every HTTP server request, client call, SQL query and event-bus message automatically; we add one span per flow step. Trace ids follow W3C `traceparent` into partners' systems and back; the trace id is on the message record, so the monitoring UI links to Jaeger/Tempo, or to traces we keep in ClickHouse ourselves.
- **Metrics** via Micrometer on `/metrics` (Prometheus format) through `withMetrics(...)`: Vert.x gives HTTP, pools, event bus and event-loop-blocked for free; we add per-flow throughput, errors, latency, backlog, DLQ size, blocked queues, per-extension CPU and failures, cache hit ratios, per-application API usage.
- **Platform alerts** — for the ops team: the customer's Prometheus + Alertmanager over our metrics. Nothing to build.
- **Business alerts** — for the integration team, inside the product, evaluated by the control plane over the monitoring store: silence, error rate, per-message SLA ("not delivered within X"), system ack without application ack for X hours, DLQ not empty, ordered queue blocked longer than X, backlog growing, quota exhaustion for an application. Notifications: email, webhook, Slack/Teams, PagerDuty; each links to the flow screen.

---

## 12. Security and authorization

### 12.1 The wallet

- Flow config never contains a secret; it contains `secret://partner-x/sftp-password`.
- Secrets live in the wallet table in Postgres, AES-GCM encrypted; the master key is **not** in Postgres — environment variable, mounted file, or cloud KMS. A DB dump alone reveals nothing.
- Backends behind one interface: the built-in Postgres wallet, HashiCorp Vault, Kubernetes Secrets, AWS/Azure/GCP key vaults.
- Certificates and keystores are wallet entries too: TLS server certs, client certs for mTLS, signing keys for WS-Security, AS4, PGP. Rotation without restart via Vert.x `updateSSLOptions`.
- Every read is audited: who, when, which flow. Secrets never reach a script, an extension log, an AI prompt or a monitoring snapshot — only references do.

### 12.2 Identity

- Users: the platform is an OIDC relying party; customers bring Entra ID, Okta, Keycloak; claims map to our roles and tenants. Local users (argon2 + TOTP) as fallback; ZITADEL bundled for installs without an IdP.
- Machines and partners — **consumers**: API keys, OAuth client credentials (our token endpoint or the customer's IdP), mTLS certificates, basic authentication for legacy senders.
- Every action — human, agent, CI, consumer — in one audit log with actor, and for agents the model.

### 12.3 Authorization — three planes

"May this happen?" has three different answers in an integration platform, and they are kept apart on purpose.

**Plane 1 — control plane: who may manage what.** Roles are bound to a **scope**: tenant → package → flow. A team owns packages (§14), so a developer role on `eon.eam.billing` deploys those flows and nobody else's. Permissions are fine-grained: view flows, **view payloads** (its own permission — payloads are where the PII is), replay, resend, deploy flows, deploy extensions, manage partners, manage wallet, manage API products, manage users. Standard roles bundle them: viewer, operator, developer, admin, **agent** (§19). Break-glass access with a reason, audited.

**Plane 2 — runtime ingress: who may call what.** PO's sender agreement, done properly. A consumer has credentials (12.2) and **entitlements**: which endpoints, interfaces or API products it may use. The endpoint or dispatcher checks the entitlement before routing: sender system X may send interface Y and nothing else; partner Z may call `/orders` and not `/invoices`; application A may use product P within its quota. Denied attempts are recorded in monitoring like any message, so "who has been knocking on the door" is a search. The reverse direction — which receivers a flow may reach — is the partner directory plus the wallet: a flow can only reach partners whose secrets it is entitled to.

**Plane 3 — data: who may see what.** Payload visibility per package and per flow classification (public / internal / confidential / PII), masking per role through the same masking policy that protects AI prompts (§19), retention per flow. Operators without payload rights see masked payloads; auditors see the audit log of who unmasked what.

### 12.4 Mechanism — the model in Postgres; enforcement library still open

- **The model** — roles, scopes, entitlements, products, classifications — lives in Postgres tables and is edited through the control plane. It is the source of truth and the thing the UI shows.
- **Enforcement in the runtime** uses the Vert.x auth stack for *authentication* of consumers (`vertx-auth-jwt`, `vertx-auth-oauth2`, mTLS, htpasswd). For *authorization* the candidate is **`vertx-auth-abac`** (§22) — not yet decided; the alternative is a small evaluator of our own over pre-resolved grants. Its `PolicyBasedAuthorizationProvider` matches attributes of the authenticated `User` — principal claims plus the attributes we load from the entitlement tables — and grants permissions; the check itself is the standard Vert.x `WildcardPermissionBasedAuthorization` with variables, e.g. `interface:send:{namespace}:{name}` or `api:call:{product}:{operation}`. Policies are **generated from the tables** and reloaded on the same Valkey notification as every other config change (`setPolicies`).
- **Enforcement in the control plane** (Quarkus) uses Quarkus security with the same permission strings and the same tables. One model, two enforcement libraries, identical vocabulary.
- **If vertx-auth-abac:** it is Vert.x-native, small, and exactly enough for the last mile. It is *not* a policy engine — its matchers are equality, negation and set membership; it is grant-only (deny is expressed by not granting); it has no time or relational conditions. Anything that needs expressing — "partner X may send interface Y", "team A owns package B" — is a row in our tables, and the provider only turns rows into grants. Customers with a central policy service (OPA, Cedar) plug it in as an alternative `AuthorizationProvider`; the Vert.x interface allows it and nothing else changes.

### 12.5 Data and network

TLS on every listener and client, hostname verification on by default; payloads encrypted per tenant at rest; retention per flow; deleting a tenant means deleting its key; the `dmz` pool is the only thing reachable from outside; admin API on a Unix domain socket or an internal interface only.

---

## 13. API management

Modelled on SAP Integration Suite's API-centric integration — API proxies, policies, products, applications, a developer portal, lifecycle states, and since 2026 an MCP gateway — with one deliberate difference: **there is no second runtime.** In SAP, API Management is a separate Apigee-derived engine with its own monitoring. In Flottila an API is a flow with policies on its ingress, and the same timeline shows the policy steps and the integration steps.

### 13.1 Objects

| Object | Meaning |
|---|---|
| **API** | an OpenAPI-described, versioned facade over one or more flows, or a pass-through flow to a backend |
| **Policy** | a pre-step on the HTTP ingress, run in the same verticle, recorded in the same timeline |
| **Product** | a bundle of APIs with a plan: quota, rate, lifecycle state (alpha, beta, active, deprecated with sunset date, decommissioned) |
| **Application** | a consumer that subscribes to products and receives credentials; its identity rides on every snapshot |
| **Subscription** | application ↔ product, approved or self-service per product |

Usage analytics per application and product are a ClickHouse query over data we already keep.

### 13.2 Policies

The policies that matter for integration APIs, done inside the runtime with full monitoring — not Apigee's forty: verify API key; JWT validation; OAuth introspection; mTLS; quota (Valkey counters, cluster-wide); spike arrest (per node); IP allow/deny; payload limits and threat checks (XML bombs, JSON depth, size); CORS; response cache (Valkey); header and payload transformation; request validation against the OpenAPI schema; mock response (for the portal's try-out and for consumers' tests).

A customer who already runs Kong, APISIX or Apigee puts it in front; we trust its identity headers over mTLS and still record who called.

### 13.3 Developer portal and MCP gateway

- Our own React portal: rendered OpenAPI, try-out against the `test` profile, self-service subscription, key management, lifecycle and sunset notices; a catalog export for customers running Backstage.
- **MCP gateway:** an API product published as MCP tools for agents, with the same credentials, quota, entitlements and audit as any consumer. The MCP server of §19 and the API gateway are one mechanism.

---

## 14. Packages, artifacts and identity

### 14.1 Identity

PO identifies objects by software component version + namespace + name, and runtime scenarios by sender system + interface + namespace; Integration Suite uses package + artifact id. Flottila:

```
tenant / package / kind:id @ version        e.g.  ppas / sap-isu / flow:invoices-out @ 1.4.0
sha256:…                                     the content hash — the technical identity of the bytes
```

- The **package** is the namespace: `sap-isu`, `eon.eam.billing`, `common`. It is owned by a team, mapped to a git folder, and is the **unit of authorization scope** (§12.3). Two packages may reuse an id, as PO namespaces allow.
- The **id** is a slug unique within the package for its kind: flow, subflow, schema, wsdl, mapping, script, lookup, api.
- **Versions** are semver and immutable. Package versions pin the set of artifact versions that belong together, in the package's lock file.
- **Content hash:** the same XSLT in two packages is stored once and provably identical.

### 14.2 Layout in git

```
packages/
  common/                                   ← shared library, owned by the platform team
    package.yaml
    scripts/
      date-utils.groovy
      date-utils.meta.yaml
    schemas/ …
    tests/
      date-utils.test.yaml
  sap-isu/                                  ← from PO SWCV sc_sap_isu
    package.yaml
    package.lock
    flows/
      invoices-out.flow.yaml
    schemas/
      urn.ppas.cz.sap-isu/                  ← ESR namespace as a folder (URN → path)
        xsd_note.xsd                        ← byte-for-byte as exported
        xsd_note.meta.yaml
    mappings/ …
    tests/ …
  AGENTS.md
```

`package.yaml` names the package, its owner, its version, what it **exports** to other packages and what it **depends on**:

```yaml
name: sap-isu
owner: team-eam
version: 1.4.0
dependencies:
  common: ^2.3.0            # semver range — the developer's intent
```

`package.lock` records what actually runs: `common: 2.3.0`. Both are in git; a pull request that bumps a dependency shows exactly which flows it touches.

### 14.3 What a migrated artifact looks like — an XSD from PO

PO kept SWCV, ESR namespace and object name in its database; the file was only the payload, and its `targetNamespace` may differ from the ESR namespace. We keep the file untouched and put what PO kept in its database into a meta file beside it:

```yaml
kind: xsd
id: xsd_note
version: 1.0.0                           # ESR objects have no version; assigned at import
targetNamespace: urn:ppas.cz:sap-isu     # read from the file
origin:
  system: PO
  vendor: ppas.cz
  swcv: sc_sap_isu
  swcvVersion: "1.0"
  namespace: urn:ppas.cz:sap-isu
  name: xsd_note.xsd
  type: ExternalDefinition
  importedAt: 2026-09-15
```

The `origin` block is indexed in the registry, so searching by the old name or namespace finds the artifact — and the messages that used it. `xs:import` / `xs:include` are resolved the way the ESR resolved them, by target namespace, first inside the package and then in declared dependencies; `schemaLocation` is rewritten to a registry reference; an unresolvable import fails at import time with a structured error, not on the first message.

### 14.4 Referencing artifacts from flows

```yaml
steps:
  - validate:
      schema: sap-isu/xsd_note             # package/id — version from the lock file
  - script:
      ref: common/date-utils               # a shared script; version from the lock file
      function: toIsoDate
```

Fully pinned forms exist (`schema://ppas/sap-isu/xsd_note@1.0.0`, `script://common/date-utils@2.3.0`); inside the same package a bare id suffices. Scripts in `common` may import each other through the registry, never through the filesystem.

### 14.5 What deploy does with artifacts

- The registry (Postgres) gets one row per artifact version: tenant, package, kind, id, version, content hash, origin block, who and when. Dependencies must already be deployed, or are deployed in the same run; a missing one is a structured error.
- S3 gets the bytes once per content hash; an unchanged redeploy writes nothing.
- Every node compiles a schema, XSLT or script once per hash and caches the compiled object; fifty flows using `date-utils` share one compiled class per node.
- Every message records the artifact versions it ran with (§6.6).

### 14.6 Changing shared things without breaking two hundred flows

`common` 2.3.1 is deployed. Nothing changes for any flow, because every package is locked to 2.3.0. Teams update their lock files when they choose, one pull request per package, tests running against the new version. Two versions of `common` run side by side as long as needed. Removing a script from `common` fails its deploy while any deployed flow still references it — the registry knows the dependents. Only the owner of `common` may change it; everyone may use it.

Differences from CPI's script collection, stated plainly: CPI's has no versioning (an update changes every flow immediately) and cannot be tested outside the tenant; ours is files with tests next to them, versioned, locked, and visible on every message's timeline.

### 14.7 Interfaces keep their PO names

The XI service interface — `urn:ppas.cz:sap-isu` + `InvoiceOut` — is a first-class entry in the **interface registry**, next to its schema, with namespace and name verbatim. The routing table maps (sender system, interface namespace, interface name) → flow. The ABAP side, the WSDLs and the partner contracts never change; PO's key becomes our routing key, not our flow identity.

---

## 15. Migration from SAP PO

1. **Assessment** through the PO Directory API and the ESR export: every ICO, communication channel, mapping, adapter module, value mapping and alert rule. Output: a report per interface — automatic, assisted or manual.
2. **Automatic:** ICO → flow skeleton with the QoS carried over (BE/EO/EOIO map one to one); channels → endpoints and partner directory entries (business systems become partners); XSLT, XSD and WSDL imported as-is with their origin metadata (§14.3); value mappings → lookup tables; XI proxy interfaces → entries in the interface registry served by the `/xi` dispatcher.
3. **Assisted — the differentiator:** graphical message mappings become XSLT generated with AI and **validated by golden tests built from PO's own message-monitor payloads**. PO kept the payloads; they become the proof that each migrated interface behaves identically.
4. **Manual, with help:** Java mappings, UDFs and adapter modules become extensions (§7.4) — the same code behind the new `Step` interface, with SDK shims for the common PO APIs.
5. **Coexistence:** the ABAP side changes one URL (`SXMB_ADM` / `SM59`) to the dispatcher. The routing table sends migrated interfaces to Flottila flows and **unmigrated ones straight through to the old PO**. Cut-over is per interface; rollback is one routing entry; both systems run until the last interface moves. Monitoring searches by the old ICO names throughout.

---

## 16. Scaling and topology — a worked example

### 16.1 How 1,000 flows sit on nodes

Every node in a pool runs **all** flows of that pool. An idle deployed flow costs memory only — roughly 100–500 KB for its built pipeline, compiled XSLT and config — so a thousand flows are under a gigabyte, and CPU goes entirely to the flows with traffic. There is no capacity planning per flow; the 800 quiet flows in a typical landscape are free.

Traffic finds nodes by itself: HTTP and XI endpoints listen everywhere behind the load balancer; Kafka consumer groups spread partitions; pollers hold a Valkey lease per endpoint; XI ordered queues are claimed per key from Postgres.

### 16.2 Pools

Nodes carry a label; flows name a pool.

| Pool | Runs | Why |
|---|---|---|
| `critical` | business-critical flows (billing, invoicing, market communication, bank files) | a bulk batch can never starve a billing message; reserved capacity, tighter alerting, own on-call |
| `core` | everything ordinary | the default |
| `dmz` | partner-facing endpoints and managed APIs (AS4, partner SFTP, B2B HTTP, the API gateway) | network zone; only these nodes are reachable from outside |

Moving a flow between pools is one line. Within a pool nodes are identical.

### 16.3 The customer

A utility: SAP ISU/FLEXI, market communication, meter data, partners over SFTP and AS4, XI proxies. 1,000 flows — ~800 quiet, ~150 steady, ~50 heavy. ~20 million messages a day, peak ~2,000/s at 06:00. Forty flows business-critical. Two data centres.

### 16.4 On-premise (VMs)

| Component | Machines | Notes |
|---|---|---|
| Runtime `critical` | 3 × 8 cores / 32 GB | sized for 3× its peak |
| Runtime `core` | 4 × 16 cores / 64 GB | the peak needs one node; four for headroom and rolling upgrades |
| Runtime `dmz` | 2 × 8 cores | DMZ VLAN |
| Postgres | primary + replica, 8 cores, NVMe | Patroni |
| Kafka | 3 brokers, KRaft | partitions sized for the heavy flows; tiered storage to S3 |
| ClickHouse | 2 replicas + 3 Keeper | NVMe hot, S3 cold |
| Valkey | 3, Sentinel | loss harmless |
| S3 | existing enterprise object store, or 3 × SeaweedFS | |
| Control plane | 2 behind the LB | |
| Observability | OTel Collector, Prometheus, Grafana | ops only |

About 22 VMs, each small, none a single point of failure — versus 3–4 large PO application servers and one very expensive database for the same landscape.

Storage arithmetic: 20 M messages × ~5 KB × 2 snapshots (level `ends`) ≈ 200 GB/day raw → ~20 GB/day after ClickHouse compression → ~7 TB/year, everything older than 30 days on S3.

### 16.5 A day, on-premise

- **06:00** — meter readings, 2,000/s for twenty minutes. Kafka absorbs the burst; four `core` nodes drain it; backlog never exceeds a minute; nobody is paged.
- **06:12** — `core` node 2 dies. The LB drops it in five seconds; its Kafka partitions rebalance in seconds; its SFTP leases expire after 30 s and node 4 takes them; its in-flight messages were durable before any step ran and are processed elsewhere. Zero lost; the platform screen shows "node 2 down, 47 messages re-driven".
- **10:30** — a mapping change merges. CI runs the flow's golden tests, deploys 2.4.1 to test, promotes to prod. 2.4.0 finishes its in-flight messages; new ones take 2.4.1. Rollback is "deploy 2.4.0".
- **14:00** — market communication receives nothing for an hour. Silence alert to the critical on-call, linking the last message and the partner's last successful connection.

### 16.6 Kubernetes and cloud

| Component | How it runs |
|---|---|
| Runtime pools | one Deployment per pool; nodeSelector/taints map pools to node groups; `dmz` behind its own ingress with NetworkPolicies |
| Autoscaling | HPA on custom metrics — Kafka lag, queue backlog, in-flight count; `core` scales from 2 pods at night to 8 at dawn; `critical` keeps a minimum |
| Postgres | CloudNativePG, or managed (RDS, Cloud SQL) |
| Kafka | Strimzi, or managed (MSK, Confluent) |
| ClickHouse | Altinity operator (Apache 2), or ClickHouse Cloud |
| Valkey | Helm, or ElastiCache / Memorystore |
| S3 | native |
| Secrets | master key via External Secrets from the cloud KMS |
| Upgrades | rolling with PodDisruptionBudgets; `SIGTERM` → stop accepting → drain in-flight (`shutdown(Duration)`) → exit |

Only the runtime pods are elastic; Postgres, Kafka partitions and ClickHouse are sized ahead, because state doesn't scale on a five-minute HPA loop. Fast pod start makes elasticity honest: a new pod warm-loads config and compiled artifacts from Valkey and S3 and serves in under 30 seconds. The installer's "bring your own managed services" option lets a cloud customer run only our pods.

### 16.7 Disaster recovery

RPO 0 for messages (durable before acknowledged). Runtime RTO seconds; Postgres RTO under a minute with Patroni or the operator. Second site: Postgres streaming replica, Kafka MirrorMaker or stretch cluster, ClickHouse replica, S3 cross-region replication; the `critical` pool can run active-active because nodes are stateless and ordered queues live in the database.

### 16.8 What doesn't scale

One giant document can only be split, not spread; a single XI queue is one partition on one node at a time by definition; ClickHouse ingest needs disks, not pods. Everything else is "start another copy".

---

## 17. Environments and operations

- **One jar, one image**, configured entirely by environment variables and a config file (Vert.x Config). Plain Linux (systemd), Docker Compose, Kubernetes (Helm), cloud managed — same code, different config.
- **The installer is product code.** One stack, one version manifest tested as a unit, one health screen showing every component. Choice at the edges (S3 backend, IdP, managed broker or database), none in the core.
- **Health:** `/health/live`, `/health/ready` (DB reachable, flows deployed). Graceful shutdown drains in-flight work; anything unfinished is still in the queue for another node.
- **Backups:** Postgres PITR; ClickHouse backups to S3; S3 lifecycle and replication; Kafka retention and tiering. Valkey needs none.
- **Performance flags, not design:** io_uring via `netty-transport-native-io_uring` with epoll fallback (single-digit % gains; some hardened hosts disable io_uring); Unix domain sockets for runtime → Postgres and control plane → admin API on single-box installs. The real gains come from fsync batching, keep-alive connections, lazy parsing and splitting big documents.

---

## 18. Testing and mocking

The rule that makes it possible: nothing in a flow touches the outside world except through the Component SPI; time and ids come through the `Runtime` record; no static singletons.

| Level | Speed | What |
|---|---|---|
| Steps and flows | ms, no I/O | `FlowBuilder(fakeRuntime).build(cfg)`, envelopes in, envelopes and fake-component records out |
| Component contracts | ms–s | a contract suite every real *and* fake component must pass: honours `pause`/`fetch`, re-opens the stream on retry, ACKs only after the durable write, spills above the limit; third-party adapter authors run the same suite |
| Flow and script tests (the product feature) | ms | YAML test cases in git next to the artifact — input, `mock://` replies, expected outputs, keys, monitoring outcome; the `test` partner-directory profile points every partner at its mock; `flottila test` runs them in CI; XML/JSON-aware comparison ignoring declared volatile fields |
| Integration and stack | s–min | Postgres, Kafka, ClickHouse, Valkey, S3 in Testcontainers; the full stack nightly; kill -9 during store-and-forward, duplicate delivery, ordering under failure, replay reproducibility, backpressure against a partner answering once per second, entitlement denials |

- **Recorded messages become tests:** "save as test case" on a real message yields a golden-file test — the monitoring product feeds the test suite, and PO's own payloads feed the migration (§15).
- Protocol doubles: `mock://` component; embedded Apache MINA SSHD, ActiveMQ Artemis, GreenMail, WireMock; our own **XI/IDoc simulator** that accepts the protocol and acks per QoS — nothing off the shelf exists.
- A fake `Clock` makes "1 min, 5 min, 30 min" backoff, leases, aggregator deadlines and silence baselines run in milliseconds.
- The Vert.x blocked-thread warning fails the build.
- YAML tests for flow authors and operators; JUnit 5 with `vertx-junit5` for platform and extension developers; one runner underneath.

---

## 19. AI readiness

Three audiences, one set of properties.

**Agents building integrations on the platform.** Everything is text with a published JSON Schema; structured, actionable validation errors; a complete API and CLI for everything the UI does; an **MCP server** on the control-plane API exposing deploy, test, dry-run, message timeline, payload search, replay, partner directory and the artifact registry as tools; a **dry-run endpoint** that runs a sample message through a flow without deploying and returns the snapshot after every step; the **artifact registry** (§14) as the schema registry with an API; real sample payloads from monitoring as mapping specifications; XSLT 3 for hard mappings and a small declarative JSON mapping format for field-to-field, both under the same golden tests; the package layout of §14.2 with `AGENTS.md`.

**AI inside flows.** An `ai:` step type — classify, extract, summarise, decide — with a pluggable provider (Anthropic, OpenAI, local OpenAI-compatible) chosen per tenant, credentials from the wallet; the platform ships no model. Output is **never trusted blindly**: validated against a schema with a deterministic fallback; prompt, model, latency and cost recorded in the snapshot like any step. Operations assistance on DLQ messages uses the timeline, the structured error and "similar failures" over ClickHouse.

**AI building the platform.** Small modules, contract tests, the decisions in this document kept as ADRs in the repository, tests as the specification, a build that runs in seconds.

**Guardrails from day one.** An `agent` role with scoped permissions (§12.3); no agent deploys to production directly — pull request and CI are the human approval by construction; `secret://` references keep secrets out of prompts; a **per-flow payload masking policy** (which fields are PII) applied before any payload reaches the MCP server, the `ai:` step or an operator without payload rights — this must exist from the first day, because retrofitting it means early customers' data has already been in a prompt; actor (human, agent, model) in the audit log; agents consume managed APIs through the MCP gateway (§13.3) with the same entitlements and quota as any consumer.

---

## 20. Performance expectations

Measured on the prototype and carried forward as targets for the redesign, to be re-measured on native Linux Postgres:

- Batched store-and-forward at ~80 % of no-persistence throughput; sync overhead < 2 ms p50; async durable ACK < 5 ms p99.
- One 8-core node: HTTP pass-through tens of thousands of requests per second; with persistence 5–20k msg/s; with a 100 KB XSLT hundreds to low thousands per second.
- Where a message's time goes, in order: fsync (ms), the partner round trip (ms–s), XSLT and parsing (100 µs–s), TLS, syscalls (µs). io_uring and domain sockets act on the last item only.

---

## 21. Repository structure

| Module | Contents |
|---|---|
| `flottila-api` | the public platform API for extensions: `Step`, `Component`, `Envelope`, `Body`, `Services`; semantically versioned; the only jar an extension class loader sees |
| `flottila-core` | Envelope, Body and BodySink, stages and patterns, FlowBuilder; no Vert.x dependency beyond `Buffer` and streams |
| `flottila-runtime` | verticles, Runtime record, queues (Kafka, Postgres), stores, monitoring writer, caches, extension loader, artifact registry client, auth (Vert.x auth providers, generated ABAC policies), API policies, S3 client |
| `flottila-components/*` | one module per scheme |
| `flottila-monitoring` | ClickHouse schema, S3Queue ingest, search (JSON projection, XPath hybrid), Postgres functions for pg_clickhouse |
| `flottila-control` | Quarkus admin API, authorization model, alerting engine, API products and subscriptions, MCP server and gateway, developer portal, React UI |
| `flottila-sdk` | extension SDK: fake runtime, fake datasources, contract tests, Maven archetype, PO API shims |
| `flottila-cli` | `flottila deploy / test / dry-run / export / migrate` |
| `flottila-migration` | PO assessment (Directory API, ESR export), converters, mapping-to-XSLT assistant, golden-test builder from PO payloads |
| `flottila-simulators` | XI/IDoc simulator and other protocol doubles |
| `flottila-installer` | Compose, Helm, version manifest, health checks |

Commercial add-ons, when they exist, live in separate repositories and use only `flottila-api` and the control-plane plugin points — the same boundary any third party gets.

---

## 22. Open decisions

1. Plain Vert.x `Future` + streams (leaning) versus Mutiny on top — settle after the core mapping to Vert.x primitives is written.
2. Body limits: 1 MB in-memory, tree limit, object-store threshold — defaults above, tuned by measurement.
3. Default failure behaviour for ordered queues: block (leaning) versus park-and-continue.
4. Production recording default: `ends` + `keep` (leaning) versus `all` + `keep`.
5. Final brand name and domain ("Flottila" is the working name).
6. Which add-ons become commercial later, and the exact plugin points they use (§3.1). The platform itself is Apache 2.
7. JCo 3.1 official JDK support list versus JDK 25.
8. Flow config schema — to be designed; it is the most important artifact in the product.
9. Package naming convention for migrated SWCVs (`sap-isu` versus `ppas.sap-isu`) and how vendor is carried.
10. Whether the developer portal is part of the control-plane UI or a separately deployable app for the DMZ.
11. Runtime authorization enforcement: `vertx-auth-abac` with generated policies versus a small evaluator of our own over pre-resolved grants (§12.4). To be settled by a spike with a few thousand generated policies.

---

## Appendix A — Lessons taken from other products

| Product | Kept | Avoided |
|---|---|---|
| Apache Camel | spool encryption; heap-pressure spill rule; `mock:` endpoints; `tokenizeXML` streaming split | stream caching enabled with spooling off by default; deciding lazily when someone reads the stream |
| Apache APISIX | control plane / data plane split; hot reload | body limit defaulting to unlimited, fixed plugin by plugin years later |
| WSO2 Micro Integrator | lazy build — don't parse until a step needs it; binary relay = pass-through | one materialising component breaking end-to-end streaming; `int` Content-Length |
| MuleSoft Mule 4 | repeatable streams by default; the in-memory-buffer-vs-concurrency trade-off stated plainly; named strategies; indexed reading mode | the default strategy only in the paid edition |
| SAP PO | XI QoS vocabulary, acks, sender agreements as entitlements, custom EJBs with server datasources, namespaces as packages | single class loader, no versioning, no testability, no per-object versions |
| SAP Integration Suite | ProcessDirect, local process call, Partner Directory, API products / applications / portal / lifecycle states, MCP gateway | no payload retention; API management as a separate runtime; unversioned script collections |
| Supabase | the composed, best-of-breed, one-installer product model | — |

## Appendix B — Glossary

**Flow** — one integration, a versioned pipeline of steps. **Step** — one unit of work on an Envelope. **Envelope** — the immutable message. **Body** — the re-readable payload. **Component** — an adapter for one protocol. **Extension** — customer or vendor code packaged for the platform. **Package** — the namespace and ownership unit; a folder in git. **Artifact** — any versioned, content-addressed thing in a package (flow, schema, mapping, script, lookup). **Consumer** — an authenticated caller: partner system, application or agent. **Entitlement** — what a consumer may call. **Product** — a bundle of APIs with a plan. **Pool** — a labelled group of identical runtime nodes. **QoS** — BE / EO / EOIO. **Snapshot** — the record of an Envelope after a step. **Partner directory** — environment-specific partner data resolved by placeholder. **Dispatcher** — a flow that routes to other flows by a configured key. **Interface registry** — XI/SOAP interfaces with their PO namespace and name, mapped to flows.
