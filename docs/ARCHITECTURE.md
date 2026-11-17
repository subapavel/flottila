# Rail8 architecture

Rail8 is an integration platform: it receives messages from one system, works on them and sends them
to another. Its goal is "find anything, fast": every message can be found and followed step by step.
What is stored about a message is set for each flow. Rail8 has no replay: a message is never run again
from its stored history.

This document describes what is in the code today. Each part ends with **Planned**: what the owner
has decided will come and is not built yet. Comparisons with SAP Process Orchestration (PO) and SAP
Integration Suite (CPI) are given where they help.

Stack: Java 25 on virtual threads, Vert.x 5.1, PostgreSQL, a body journal on each node's local disk,
Valkey (optional), S3 (package archives). Licence Apache 2.0.

## 1. Overview and terms

| Term | What it is | Closest SAP term |
| --- | --- | --- |
| **Envelope** | One message as it moves through a flow: id, flow, step number, headers, props, keys, body, attachments. Immutable; every step returns a new one. | the message in the pipeline |
| **Body** | The payload. A handle to bytes in a `BlobStore`; `open()` gives a fresh stream each time, so a body can be read any number of times and is never held as a byte array. | payload / main document |
| **Headers** | Transport metadata as it arrived. Nothing is passed on to a partner unless the flow lists it in `forwardHeaders`. | HTTP headers, adapter-specific attributes |
| **Props** | Typed values a flow keeps for itself while it runs (for example the partner a `route` chose). Never sent out. | exchange properties in CPI |
| **Keys** | Business keys (an invoice number), set by `extractKey` and indexed for search. Never sent out. | user-defined message search attributes |
| **Step** | `Envelope apply(Envelope)`: one change to a message. | an iFlow step |
| **Stage** | `Envelope apply(Envelope, Pipeline rest)`: a step that decides how often the rest runs (zero, once, many times). Route, filter, split and retry are stages. | router, filter, splitter |
| **Flow** | One file: where messages come from, how they are delivered, and the steps they pass. | iFlow; integrated configuration in PO |
| **Endpoint** | One file: a named inbound or outbound connection (scheme, direction, options). | communication channel |
| **Component** | The code for one scheme (`http`, `sftp`, `mock`): a consumer for inbound endpoints and a producer for outbound ones. All contact with the outside world goes through a component. | adapter |
| **Package** | A folder of flows, endpoints and their files, deployed together under one name and version. | integration package in CPI; software component version in PO |
| **Node** | One running Rail8 process. Several nodes share one Postgres. | server node of a PO cluster |

Every step and stage runs on the message's own virtual thread and may block (wait for a database,
for a partner) without holding a platform thread. Vert.x verticles own sockets and timers and never
run a pipeline. Clock, timers and id generation are injected, so tests run on a fake clock.

The modules point one way: `rail8-api` ← `rail8-core` ← `rail8-runtime`, checked by an architecture
test. Components and steps depend on `rail8-api` only; only `rail8-runtime` talks to Postgres, S3 or
Valkey. `rail8-cli` sits on top and may use every module.

**Planned**
- Calls between flows (subflows, a hand-over without waiting) are an open question for the owner.

## 2. The path of a message

### Delivery modes

A flow sets `delivery.mode`:

| Mode | What the sender gets | When the steps run | On failure | SAP term |
| --- | --- | --- | --- | --- |
| `sync` | the flow's result as the reply (HTTP 200) | on the sender's request, while it waits | the error reaches the sender | Best Effort (BE) |
| `at-least-once` | 202 after the message is saved | later, by a worker | retried, then parked among failed messages | Exactly Once (EO), with a possible second delivery when a node dies |
| `at-most-once` | 202 at once | at once, on its own thread | logged and dropped | — |

### Receiving and saving (`at-least-once`)

1. The component receives the message. The HTTP consumer streams the body into the blob store and
   refuses a body over `limits.maxBodyBytes` with 413.
2. **Admission** checks the flow's budget of messages and bytes in flight (`limits.maxInFlight`,
   `limits.maxInFlightBytes`; by default twice the CPU cores, and that many times the body limit).
   With no room the answer is "try again": HTTP 503 with `Retry-After`. Nothing waits in memory.
3. **Save.** The body is appended to the node's body journal and forced to disk. Then one Postgres
   transaction writes the arrival record, the business keys and a row in the `work` table that points
   at the body in the journal. Saves of many messages are grouped into one transaction (up to 64
   messages, 1 MiB or 1 ms), and several groups may commit at once.
4. **Acknowledge.** Only after the commit does the sender get 202 with the message id in
   `x-message-id`. A save that does not finish within `delivery.saveTimeout` (5 s) answers 503.
   Postgres runs with `synchronous_commit=on`; in a deployed environment it also needs a synchronous
   standby by default (`RAIL8_PG_DURABILITY=ha`).

HTTP answers: 200 a reply, 202 accepted, 400 malformed or refused for good, 413 too large, 422
invalid, 502 or 503 a partner failed (503 when worth trying again), 500 anything else.

### Processing

A **worker** claims the row and runs the steps. Work is found in three ways: the node that saved the
message hands it straight to a free worker on the same node; other nodes get a Postgres `NOTIFY` after
the commit; and every worker also checks the table every 30 s in case a signal was lost. A claim uses
`FOR UPDATE SKIP LOCKED` and takes a 30 s lease that the worker renews.

### Retry after a failure

There are two levels:

- **Step retry, in place.** A step marked `retryable: true` is retried on the same thread under the
  flow's `retry: {attempts, initial, max, multiplier}` with exponential backoff. Without `retry`
  there is none.
- **Run retry, later.** When the run fails, the work row is put back with a later due time and the
  whole run starts again from the saved arrival. The installation default is 3 retries, 5 minutes
  apart (`RAIL8_RETRIES_COUNT`, `RAIL8_RETRIES_INTERVAL`); a flow overrides it with
  `delivery.retries: {count, interval, multiplier, maxInterval}`. No thread sleeps while it waits.
  Like the retry of a message in error in PO's adapter engine, but automatic.

A fault that cannot get better (malformed or invalid input, a final refusal by the partner) is not
retried.

### Failed messages

When the retries run out, the message is **parked**: a row in the `dlq` table refers to the saved
arrival (the body itself stays where it is; a row is only written once that body is durable). This is
the list of failed messages, like messages in error status in PO's message monitor.

- `GET /admin/dlq` lists them; `POST /admin/dlq/{id}/retry` runs one again as a new attempt.
- A message that cannot even be parked is **held**: it stays in `work`, is never claimed again and,
  for an ordered flow, stops its key. An operator lists held items and releases them.
- A row whose body was lost with a node's disk can be discarded by an operator; the act is audited.

### Delivery in order

A flow with `partitionBy: <key>` processes the messages of one key one at a time, in the order they
were accepted, like EOIO with a queue name in PO. The key comes from a business key the flow extracts.
A row is claimed only when no older row of the same key exists and no other worker holds the key.

- While a message waits for its next retry, its key waits too.
- When the retries run out and the flow says `onFailure: park`, the message is parked and **the next
  message of the key goes on**.
- `onFailure: block` is accepted by the schema but refused when the flow is built.

### Duplicates

A component may name a value that identifies what it delivers. Only `sftp` does (host, path,
modification time and the file's hash). The intake then records that value per flow in
`processed_ids` and answers a second delivery with the first message's id instead of saving it again.
HTTP has no deduplication.

When a node dies after sending but before recording the send, the next worker sends again: partners
see a second delivery with the same `x-message-id`, by which they can drop it.

**Planned**
- In order, an error stops the key's queue until an operator retries or cancels the message, like a
  stopped EOIO queue in PO; parking and going on stops being the behaviour of an ordered flow.
- Cancelling a failed message, and what a cancel covers.
- Deduplication only where the flow's author asks for it, by a key the author chooses; `sftp` only
  avoids reading the same file twice and drops its built-in deduplication.
- What happens when a step fails, defined by the flow's author: an error message to a partner, a
  chosen answer to the caller, or a few steps of its own (like an exception subprocess in CPI).
- A time limit for a whole message, and cancelling a running message.
- What is left in the code from replay is removed.

## 3. Storage

### Postgres

Postgres holds everything whose loss would lose a message, and the message history:

| Table | Holds |
| --- | --- |
| `work` | messages accepted and not yet done: flow, key, attempt, due time, lease, and where the body is in the journal |
| `messages`, `message_keys` | one record per step of each message (outcome, error, times, CPU, headers, props, keys, body hash and size), and the business keys for search |
| `dlq` | parked messages, by reference |
| `processed_ids` | the delivery identities used against duplicates |
| `body_refs`, `journal_segments`, `journal_keys`, `journal_nodes`, `journal_losses` | where each durable body is, the journal files, their wrapped encryption keys, and losses an operator accepted |
| `nodes` | node membership and leases |
| `deployments` | which package revision is deployed, by archive hash |
| `audit` | operator acts |
| `bodies` | old bodies stored in the database; read only, kept as a fallback |

Migrations run at node start (Flyway).

### The body journal

Bodies do not go into Postgres. Each node writes them to an append-only journal on its local disk:

- Files are segments of records; a sealed segment ends with an index. A body larger than one record
  (256 KiB) gets a segment of its own.
- Every record is encrypted with AES-256-GCM under a key derived from a tenant key, which is stored in
  Postgres wrapped by a master key. The master key comes from a file or an environment variable and
  must be the same on every node of an environment. A node without one does not start.
- One writer thread collects every append waiting at that moment and forces the file once for all of
  them, so many messages share one disk sync.
- At start the journal checks every record by its checksum, cuts an unsealed segment at its first
  bad record and seals it. If a body that the database says is durable is missing, the node refuses
  to start until an operator accepts the loss.
- A sweeper deletes sealed segments that nothing needs any more: no open save, no work row, no
  recent kept body.

Small bodies are first held in memory under a node-wide byte budget (256 MiB) and go to the journal
when they must be durable. Reads look in memory, then the local journal, then in the journal of the
node that holds the body (see part 6).

### Valkey, S3, ClickHouse

- **Valkey** is used only for poller leases (part 6). It is optional; a node without it warns that
  two nodes would both poll.
- **S3** stores deployed package archives, by hash. It stores no bodies.
- **ClickHouse** is in the compose file but not used by any code. Kafka likewise: a flow asking for a
  Kafka queue is refused.

### Retention

Message history, kept bodies and delivery identities are deleted after 7 days
(`RAIL8_RETENTION_MAX_AGE`), except for messages still in `work` or `dlq`. Parked messages are never
deleted.

**Planned**
- Message history and payloads move to ClickHouse, off the message's path, kept long and searchable
  including payload content. Each flow sets what is recorded and for how long, with installation
  defaults. The `bodies` table goes away.
- Bodies that wait long (a parked message, a partner down for days) move to S3 in the background, so
  a node's journal stays small.
- Valkey also serves as a distributed cache, and as a cache that a flow's author uses from custom
  code; one API with a Valkey and an in-memory implementation.

## 4. Packages, flows and deployment

### Files

A package is a folder with `package.yaml` (name, version, exact-version dependencies on other
packages, pinned in `package.lock`). Inside it, files are found by their `kind`, not their place:
`flow`, `endpoint`, `test`, and `folder.yaml` for a namespace. Each kind has a published JSON Schema.

A flow, shortened from `examples/demo/flows/invoice-async.yaml`:

```yaml
kind: flow
id: invoice-async
from: { endpoint: http-in-async }
delivery: { mode: at-least-once, retries: { count: 1, interval: 1s } }
recording: { level: all, payload: keep }
limits: { maxBodyBytes: 1048576 }
partitionBy: invoiceId
onFailure: park
steps:
  - { type: xsd, schema: flows/invoice.xsd }
  - { type: extractKey, key: invoiceId, xpath: /Invoice/Id }
  - type: route
    prop: PARTNER
    routes: [{ when: "/Invoice/Currency = 'CZK'", set: domestic }]
    otherwise: foreign
  - { type: xslt, stylesheet: flows/invoice-to-partner.xsl }
  - { type: to, byProp: PARTNER, endpoints: { domestic: partner-domestic, foreign: partner-foreign } }
```

Built-in step types: `to`, `route`, `filter`, `extractKey`, `validate`, `split`. Shipped step
modules: `xslt` (Saxon-HE) and `xsd`. Conditions are XPath by default.

An endpoint names its scheme, direction and options, with an optional overlay per environment
(`environments.<env>.config`), so one flow file is the same in every environment. An option whose
whole value is `secret://<name>` is a secret; a node reads it from the environment variable
`RAIL8_SECRET_<NAME>`, and tests and validation use a placeholder.

### The command line

`rail8 validate` checks a package against the schemas and builds every flow. `rail8 test` runs the
`*.test.yaml` cases of each flow in process, with mock endpoints and a virtual clock, so a case with
retries five minutes apart finishes in milliseconds. `rail8 lock` writes `package.lock`.
`rail8 deploy` zips the package and sends it to a node's admin API.

### Deployment

A node unpacks and checks the archive, builds every flow and computes each flow's hash over its
file, every file it read and its endpoints' options. The archive is stored by its hash (in S3, or a
local folder in development), then the flows whose hash changed are stopped and started again; the
others keep running. The commit point is a row in `deployments`. A failed start rolls back.

Manual deploy is allowed only in the `dev` and `ci` environments. Other nodes pick up the deployed
packages from `deployments` and the archive store when they start; a running node does not learn of a
deploy made on another node.

**Planned**
- Deployment from git: the package's revision in git is what runs. The archive in S3 and the upload by
  command go away.
- A graphical flow editor (React Flow). The flow file and the editor are two views of the same
  thing: an edit in either shows in the other.
- An authentication, authorization and secret store comes in step 2; today secrets are environment
  variables.

## 5. Components, steps and their mocks

### The component contract

A component has a scheme, a JSON Schema for its endpoint options, a consumer, a producer and a health
check. It may also say which host and port its consumer listens on (two endpoints may not share one),
whether it polls something other nodes could poll too, what identifies a delivery, and which target
system a producer calls.

- A **consumer** calls the flow's handler for every message and finishes its protocol from the
  answer: *accepted* (the message is durable; the sender may let go), *reply* (sync), or *refused*,
  either "try again" or for good.
- A **producer** sends an envelope by streaming its body and returns the partner's reply. It sends the
  message id as `x-message-id`. It may be retried; each try opens the body again.
- **Back-pressure**: a flow with no room answers "try again" before anything runs; an HTTP consumer
  turns that into 503, a poller ends its round and tries later. Sends to one target system are
  limited in number (`RAIL8_TARGET_*`).

Components today: `http` (consumer and producer), `sftp` (polling consumer; deletes or moves a file
only after the flow's answer; no producer), and `mock`.

### Steps

A step module registers a `StepFactory`: a type name, a JSON Schema for its options, and whether it
reads the whole body or streams it. It is built once at deploy time from the step's options and the
resources it may keep (clock, timer, ids, blob store, files of its own package). An expression
language (XPath today) is registered the same way.

Components and step modules are wired into the node in code; there is no plugin loading yet.

### Mocks and contract suites

Every component ships with a mock that passes the same contract suite as the real one. The testkit
holds the suites: one for every component (delivery, stop, acknowledgement, retry, streaming of large
bodies, deadlines, message id), one for polling consumers, and one for blob stores. The `mock`
component stands in for any scheme in `rail8 test`, replying from fixtures with a delay, a failure or
a file to poll; a deployed node refuses it.

**Planned**
- Plugins: adapters, steps, custom mapping code and custom services are loaded as plugins.
- A development kit for adapters: the interface, the contract suite and a mock to test against.
- The adapter contract carries back-pressure and acknowledgement explicitly, and later a deferred
  acknowledgement: system and application acknowledgements as in SAP XI.
- An API for custom code: secrets, cache, the envelope and the message's log.
- Priority: the core and the contracts first, then HTTP inbound and outbound.

## 6. Several nodes and failures

Nodes share one Postgres. Each node renews a lease in the `nodes` table every 5 s (30 s long). A
node that cannot renew stops its own sends and completions after half a lease (it fences itself),
so it cannot act after others think it dead. A node replaced by a newer start of the same id stops.

- **Work rows** of a node that died may be claimed by another node, but only rows whose body is in
  the claiming node's own journal. A row whose body is in the dead node's journal waits until that
  node comes back. Its messages are delayed, not lost.
- **Reading another node's body.** Nodes talk over a peer link (TCP, port 8083), authenticated with a
  key derived from the master key. A node reads a record from a live node's journal and checks it
  itself. A body on a dead node cannot be read until the node returns. If the node's disk is lost,
  its bodies are lost, and an operator must accept the loss before the work can move on.
- **Polling.** A polling consumer (SFTP) runs only on the node that holds the endpoint's lease in
  Valkey, so one file is read by one node.
- **Second copy.** The receiving side of a copy of journal segments on another node exists, the
  sending side does not, and the setting `RAIL8_JOURNAL_COPIES` is read but has no effect. Every
  node warns at start that it keeps one copy.

**Planned**
- Level 1, as today: one copy of a body on the node's disk; when the node returns, its messages go on.
- Level 2, only after it is measured: a second copy on another node before the sender gets its answer.
  Without a reachable second node the node keeps accepting and warns. Takeover of a dead node's
  messages, and restoring missing copies.
- What Rail8 promises partners about a second delivery when a node dies.

## 7. Mapping

The mapping engine is in `mapping/`, a project of its own in the same repository. It reads source
and target structures from XSD, WSDL and JSON Schema, and a mapping saved as one JSON file. A
mapping is compiled into a tree of executable nodes and interpreted; it can also be exported as XSLT.
Its semantics follow SAP PO message mappings (queues and contexts, the standard functions). A REST
service (Quarkus) and a studio UI work over a workspace of mappings; a corpus of 250 mappings with
expected outputs, including runs observed on PO and CPI, tests it.

The platform does not use the engine yet: no step runs a mapping.

**Planned**
- A step that runs a mapping in a flow. Its contract is agreed and follows this list.
- A bytecode tier for speed, later.
- A point where an importer of SAP PO and CPI message mappings plugs in, with a contract of its own
  that is designed first. It runs at design time, before compile, and writes a canonical
  `*.mapping.json` and its schema files into a package. The step and the embedding API do not change
  for it, and nothing is imported at deploy time: the reviewed file in git is what runs.

The contract of the mapping step, engine side. A module `mapping/engine/embed`
(`dev.rail8.mapping:rail8-mapping-embed`, JPMS `dev.rail8.mapping.embed`) is the engine's embedding
API: the one engine package with a compatibility promise. It requires `dev.rail8.mapping.api`
without `transitive`, exports only its own package, and its signatures use JDK types and its own.

```java
public final class Mapping {                  // immutable; any number of concurrent runs
  public static Mapping compile(byte[] model, BundleFiles files) throws MappingRefusedException;
  public String mediaType();                  // "application/xml" while the engine writes XML only
  public byte[] run(byte[] input, long maxOutputBytes, OffsetDateTime now)
      throws RunFailure, InterruptedException;
}
@FunctionalInterface public interface BundleFiles {   // the only way compile reads a schema file
  byte[] read(BundleReference reference) throws IOException;
}
public sealed interface BundleReference {     // the engine parses the two spellings of a reference
  String spelling();                          // as the mapping file writes it
  record Relative(String spelling, String path) implements BundleReference {}  // ../structures/a.xsd
  record Packaged(String spelling, String name, String version, String path)   // common@1.4.0:/a.xsd,
      implements BundleReference {}                                            //   path "a.xsd"
}
public final class MappingRefusedException extends Exception {
  public record Problem(String code, String at, String message) {}
  public List<Problem> problems();            // at least one, in the engine's diagnostic order
}
public final class RunFailure extends Exception {
  public enum Reason { MALFORMED_INPUT, DATA_ERROR, OUTPUT_TOO_LARGE }   // reasons may be added
  public Reason reason();
  public Optional<String> code();             // DATA_ERROR: po:MINOCCURS, err:FORG0001, ...
  public Optional<String> authoringId();      // DATA_ERROR: the target field mapping or function box
}
```

- `compile` compiles as a deploy does, optimised and untraced: load, both structures, the
  fingerprint check, lowering. It calls `files` on the calling thread, once for each reference a
  schema bundle lists (the source's first), and for nothing else. It refuses with every problem of
  the first stage that refuses: bytes that are no JSON (`malformed-json`), the model's own checks, a
  missing structure, a schema load error (an `IOException` from `files` is `schema-unreadable` at
  that reference), `fingerprint-mismatch`, the checks against both structures, and every UDF box
  the mapping reaches (`unsupported-box-kind`). An unchecked exception from `files` passes through
  unchanged, so the host's own refusal keeps its words.
- `run` reads all of `input` (XML without a DOCTYPE, or JSON, as the source structure says) before
  it writes, and returns the whole output or throws, never part of it. `now` is the time every date
  function sees, and its offset is the run's time zone; a run reads no clock and no file. Output
  above `maxOutputBytes` (at least 1) stops the run with `OUTPUT_TOO_LARGE`. An interrupt ends it
  with `InterruptedException`. Anything else it throws is an engine bug.
- Memory: the host holds the input, the output is at most `maxOutputBytes`, and the engine's working
  memory grows with the input and the mapping, with no limit of its own until one is measured.
- A new `Reason` breaks hosts on purpose: a host maps reasons with a `switch` that has no `default`,
  so a recompiled host does not compile and an old one fails with `MatchException`, which is retried.
- The module's contract suite is abstract and lives in its test jar, so a later bytecode tier passes
  the same tests. There is no mock: the engine is code in the process, not a way out of it.

The contract of the mapping step, platform side. A step module `platform/steps/mapping`
(`dev.rail8:rail8-step-mapping`) registers the step type `mapping`. It depends on `rail8-api` and
`rail8-mapping-embed`, and it is the only platform module that depends on the mapping, and only on
`dev.rail8.mapping.embed`; an architecture test checks both. The node and the command line ship it.
Every CI job and documented command that builds the platform first installs the engine's `embed`
module from the same commit (`./mvnw -f mapping/pom.xml -pl engine/embed -am install -DskipTests`).

```yaml
steps:
  - { type: mapping, mapping: mappings/order-to-invoice.mapping.json }   # or <dependency>:<path>
```

- Options: `{"type":"object","required":["mapping"],"additionalProperties":false,"properties":
  {"mapping":{"type":"string","minLength":1}}}`. `mapping` is a path from the package root or
  `<dependency>:<path>`, read with `StepResources.file`. The step reads the whole body.
- A mapping is a file of the package that a step names, as a stylesheet is; it has no `kind`. The
  studio's layout is the convention: `mappings/<name>.mapping.json`, its schemas in `structures/`.
- The mapping file keeps the engine's spelling of its references. A `Relative` reference resolves
  against the mapping file through `StepResources.resolve`, inside the package or dependency that
  holds the mapping. A `Packaged` reference is a build error naming the step and the reference,
  until its version can be compared and the studio can refresh such a mapping.
- The flow's hash covers the mapping file and every schema file it read, since both are read
  through `StepResources`. The engine's version is not in it, as Saxon's is not. A refusal, a
  fingerprint mismatch and a file outside the package are build errors naming the step, at deploy,
  in `rail8 validate` and in `rail8 test` alike.
- A run writes its output to the blob store under the flow's body limit, with `content-type` from
  `mediaType()`. Its faults: `MALFORMED_INPUT` is MALFORMED; `OUTPUT_TOO_LARGE` is INVALID, as a body
  over the limit is; `DATA_ERROR` is INTERNAL and retried, because a data error need not come from the
  input (a box with a bad constant fails every message); an interrupt is rethrown with the flag
  restored, so the worker leaves the item to its lease.
- UDFs, and what a mapping may later read besides the body (headers, properties, mapping
  parameters), wait for the platform's data model. They come as additions, such as a `run` that
  takes a context.

## 8. jq

The jq engine is in `jq/`, a project of its own: a parser, a compiler to closures and a byte-level
JSON reader and writer, with a command line and tests against the real jq. Embedded use has limits by
default, among them a recursion depth of 10 000, settable through the engine's builder; the command
line uses jq's own limits. Some depth limits (JSON reading, printing, comparing) are fixed at 10 000.

The platform does not use the engine yet: there is no jq step.

**Planned**
- A thin jq step in flows, with the recursion limit of 10 000 settable.

## 9. UI

The platform has no web UI; the admin API answers JSON and refuses browsers. The only UI is the
mapping studio in `mapping/ui`: React with PatternFly 6, the expression canvas on React Flow. Its
screens are a list of mappings and a studio with overview, definition, test, signature and functions
tabs. `site/` is the project's public web page.

**Planned**
- One Rail8 web application on PatternFly with a shell in which screens open as tabs.
- First screen: find a message (by id, business key, flow, time) and see its progress step by step;
  fast.
- The mapping studio rebuilt inside it.
- Plugins add their screens through a form generated from their JSON Schema.
- Writing a view of a payload (how a body is shown), later and simply.

## 10. Operations

### What is recorded

Each flow sets `recording: {level, payload}`, and a step may override it:

- `level`: `all` (every step), `ends` (arrival, the last step and every step that did not end well),
  `errors`, or `off`.
- `payload`: `keep` (the body after each recorded step) or `metadata-only`.

An `at-least-once` flow always records the arrival with its body, because a retry starts from it. A
run's records are written in groups, after the bodies they refer to are durable.

### Logs, metrics, health

- The code logs through `java.lang.System.Logger`; there is no logging configuration of its own.
- There is no metrics endpoint. Each record has wall and CPU time per step, and an optional CPU
  sampler (`RAIL8_CPU_SAMPLING`) reports CPU by step.
- `/live`, `/ready` and `/health` (Postgres, deployments, journal) answer without a token.

### Admin API

On `127.0.0.1:8081` by default. With `RAIL8_ADMIN_TOKEN` set, every `/admin` route needs it as a
Bearer token; without one the API may only listen on loopback. There are no users or roles.

| Route | Purpose |
| --- | --- |
| `GET /admin/messages?key=…` | find messages by business key |
| `GET /admin/messages/{id}` | a message's steps with their times |
| `GET /admin/messages/{id}/steps/{n}/body` | the body after step `n` |
| `GET /admin/dlq`, `POST /admin/dlq/{id}/retry` | failed messages, and running one again |
| `GET /admin/work/held`, `POST /admin/work/{seq}/release` | held items |
| `GET /admin/work/lost`, `POST /admin/work/{seq}/discard` | items whose body was lost |
| `PUT`, `DELETE /admin/packages/{name}`; `GET /admin/packages`, `GET /admin/flows` | deploy, undeploy, what runs |
| `GET /admin/targets`, `GET /admin/flows/{flow}/cpu` | limits per target system, CPU by step |

Configuration is by environment variables `RAIL8_*`; `RAIL8_ENV` is required, and any value other
than `dev` or `ci` is a deployed environment with stricter rules (no manual deploy, no mock
endpoints, and by default a synchronous Postgres standby).

### Running it

`deploy/compose` starts a node with Postgres, Valkey, S3 (SeaweedFS), an SFTP server and a partner
that echoes, and deploys `examples/demo`. The root `docker-compose.yml` is a smaller quickstart
(`docs/QUICKSTART.md`).

**Planned**
- One log of records per message, per interface and per connection, with levels and a threshold that
  can be changed at run time; custom code logs through standard Java logging into its message's log.
- Metrics and alerts inside the platform, OpenTelemetry for export, with no required extra product;
  a full queue waits and alerts, it never drops.
- Teams, rights and roles; a separate right to see payloads, with a record of who viewed one.
