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

- `GET /admin/dlq` lists them.
- `POST /admin/dlq/{id}/retry` runs one once more under the same id, as its last attempt.
- `POST /admin/dlq/{id}/cancel` ends one, with an optional reason (`{"reason": "…"}`, at most 1024
  characters). Its records and bodies stay until retention removes them.
- Each retry and cancel writes an audit row in the same transaction, naming the actor the caller gives
  in the `X-Rail8-Actor` header (`unknown` without it), the caller's address and, for a cancel, the
  reason and the error.
- A message that cannot even be parked is **held**: it stays in `work`, is never claimed again and,
  for an ordered flow, stops its key. An operator lists held items and releases them.
- A row whose body was lost with a node's disk can be discarded by an operator; the act is audited.

### A run's time limit, and cancelling a running message

Each run of a message has the flow's `delivery.timeout` (30 s by default, at most 24 h): a sync call
from its admission, an at-most-once run, and each claim of an at-least-once message. When it passes,
the run stops. The step it is in is interrupted, no later step and no new send starts, and a step
retry neither waits nor tries again. A send already started has until the same deadline, when its
producer cuts it, and the run ends only once its sends have settled; a sync or at-most-once run also
ends at the node's stop deadline. A step that returns before it notices the stop keeps its result.

- The history has a failed row at the step where the run stopped: `its time limit of PT30S passed`.
- `at-least-once`: a failed attempt that may get better, so the run retry and the park apply to it as
  to any other failure.
- `sync`: the sender gets 503 with `Retry-After`. `at-most-once`: it is logged and dropped.

`POST /admin/messages/{id}/cancel` cancels an at-least-once message that has not ended. It takes the
same optional reason and `X-Rail8-Actor` as the cancel of a failed message and writes one audit row.

- A message that is queued, waiting for its retry or running is marked in its work row, in the
  audit's transaction. The answer is 202 `{"id", "auditId", "cancelledAt", "state": "stopping",
  "node"}`. A run on the node that took the call stops at once. A run on another node, the one
  `node` names, stops at that node's next lease renewal (within 10 s at the default lease). A message
  that is not running stops at its next claim, before its first step does anything; `node` is null
  then. A second cancel answers with the first one's audit row and writes nothing.
- The history of a cancelled message has a failed row at the step where it stopped: `cancelled by
  alice: <reason>`. The message ends: it is never retried or parked, and the next message of its key
  runs once its run and its sends are done.
- A parked message is ended as by `POST /admin/dlq/{id}/cancel`: 200 with `"state": "ended"`.
- A held message, or one whose body was lost: 409. They keep their own release and discard.
- `sync` and `at-most-once` runs are not cancelled; their time limit ends them. Any other id: 404.

A cancel takes longer:

- for a message whose body is in a dead node's journal: it stops when that node comes back;
- for a message behind an older message of its key: it stops when its turn comes;
- for a message without a key whose last attempt fails just as the cancel commits: it can be parked
  all the same, and a second cancel ends it (200, `"state": "ended"`);
- for a step that ignores the interrupt (a long XSLT or jq evaluation): its message, and the key of
  an ordered flow, stays taken until the step returns. Once the run has gone on for its time limit
  plus the lease, a WARNING names the message, the step and the thread.

With `recording: {level: off}` no failed row is written: the audit row and the log show the stop.

**Upgrading to the cancel.** The migration adds the column `work.cancel_audit`. A node of the earlier
version runs a marked message as if it were not cancelled, so upgrade every node before cancelling.

### Delivery in order

A flow with `partitionBy: <key>` processes the messages of one key one at a time, in the order they
were accepted, like EOIO with a queue name in PO. The key comes from a business key the flow extracts.
A row is claimed only when no older row of the same key exists and no other worker holds the key.

- While a message waits for its next retry, its key waits too.
- When the retries run out, the message is parked and **the key's queue stops behind it**, like a
  stopped EOIO queue in PO: its work row stays, and the key's later messages wait in order. Other keys
  go on. The park and the stop are one write, so a crash cannot leave one without the other.
- `GET /admin/work/stopped` lists the stopped keys, each with its message, the error and how many
  messages wait behind it.
- An operator retries the message, which runs again in its place before the messages behind it, or
  cancels it, which lets the key's next message go on. Nothing else ends a stop; a release of held
  items does not.
- A flow file that still sets `onFailure` is refused: there is no choice to make.
- A package that would stop running a flow at-least-once is refused while that flow has items in the
  work queue, a stopped key's included.

### Duplicates

The platform deduplicates nothing: every arrival it accepts is a new message. `sftp` only makes sure
it does not read the same file twice: once a file is delivered it is deleted or moved. A file whose
delete or move failed is delivered again in a later round, and a file uploaded again is processed
again, each as a new message. HTTP has no deduplication either.

When a node dies after sending but before recording the send, the next worker sends again: partners
see a second delivery with the same `x-message-id`, by which they can drop it.

**Upgrading to the stop.** The migration adds the column `work.stopped_at`. While nodes of the
earlier version still run, they keep parking a failed message of an ordered flow and going on with its
key; they list a stopped key among the held items, and Postgres refuses their release of it. A package
cannot be valid for both versions when it has an ordered flow: the earlier version needs `onFailure`
with `partitionBy`, this one refuses it. Upgrade every node, then deploy such packages again without
`onFailure`; until then this version lists them as failed.

**Planned**
- Deduplication only where the flow's author asks for it, by a key the author chooses.
- What happens when a step fails, defined by the flow's author: an error message to a partner, a
  chosen answer to the caller, or a few steps of its own (like an exception subprocess in CPI).
- What is left in the code from replay is removed.

## 3. Storage

### Postgres

Postgres holds everything whose loss would lose a message, and the message history:

| Table | Holds |
| --- | --- |
| `work` | messages accepted and not yet done: flow, key, attempt, due time, lease, whether it is held, stops its key or is cancelled, and where the body is in the journal |
| `messages`, `message_keys` | one record per step of each message (outcome, error, times, CPU, headers, props, keys, body hash and size, the lines the step's code logged), and the business keys for search |
| `dlq` | parked messages, by reference |
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
- Valkey also serves as a distributed cache, and the cache of custom code (part 5) gets a Valkey
  implementation that the nodes share.

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
modules: `xslt` (Saxon-HE), `xsd`, `jq` and `mapping`. Conditions are XPath by default.

An endpoint names its scheme, direction and options, with an optional overlay per environment
(`environments.<env>.config`), so one flow file is the same in every environment. An option whose
whole value is `secret://<name>` is a secret; a node reads it from the environment variable
`RAIL8_SECRET_<NAME>`, and tests and validation use a placeholder. A step's option is a secret in the
same way; its code takes it through the API for custom code (part 5).

### The command line

`rail8 validate` checks a package against the schemas and builds every flow. `rail8 test` runs the
`*.test.yaml` cases of each flow in process, with mock endpoints and a virtual clock, so a case with
retries five minutes apart finishes in milliseconds. Both load plugins as a node does, from
`--plugins <dir>` or `RAIL8_PLUGINS`. `rail8 lock` writes `package.lock`.
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

A component has a scheme, a description of its endpoint settings, a consumer, a producer and a health
check. It may also say which host and port its consumer listens on (two endpoints may not share one),
whether it polls something other nodes could poll too, what identifies a delivery, and which target
system a producer calls.

- The **settings description** (`Component.settings()`, types in `dev.rail8.api.settings`) lists
  each option an endpoint may set: its name, type (text, secret, whole number, true or false, one of
  a list), whether it is required, its default, a help text, its tab, whether it serves in or out
  endpoints, and the choice or switch it is shown by. A rule may require exactly one of a few
  options. The platform supplies the tabs general, retry, recording and security; a component adds
  only its own. One description serves three readers: the deploy and `rail8 validate` check every
  endpoint file against it (an unknown option, or one of the other direction, is refused), the admin
  API serves it, and the UI draws the endpoint's form from it. A hidden option is never required, and
  the empty string is no value. `http`, `sftp` and a plugin's component describe themselves the same
  way; a description that cannot be built refuses the node's start, naming its owner. A component
  that describes nothing is checked against its `configSchema()`, a JSON Schema.

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

The node wires its shipped components and step modules in code, at start.

### Plugins

A plugin adds components, step factories and expression languages to what the node ships. It is a
jar, or a folder of jars, in the folder that `RAIL8_PLUGINS` names; the node loads every entry, in
name order, at start, and logs each plugin's name, version, api version and the SHA-256 of its jars.
A plugin implements `dev.rail8.api.plugin.Plugin`, named in its own
`META-INF/services/dev.rail8.api.plugin.Plugin`: a name, the api version it was built against, and
what it adds. Its components are built over the node's `ComponentContext` (Vert.x, blob store, ids,
timer, clock), inside the node's start; building them opens nothing.

- **Isolation.** Each plugin has a class loader of its own. It sees the JDK, its own jars, and from
  the node only `dev.rail8.api`, `io.vertx.core`, Netty and SLF4J, so two plugins may bring two
  versions of one library. The thread's context class loader is the plugin's during each call into
  it.
- **Add, never replace.** A scheme, step type or language the node already has (shipped, built in,
  or another plugin's) refuses the start and names both owners; so does the scheme `mock`. A plugin
  brings no mock: `rail8 test` stands the platform's mock in for its schemes.
- **Acknowledgement and back-pressure** are a consumer's `Future<Ack>`: accepted, reply, or refused
  (retryable or for good). A sealed type such as `Ack` never gains a variant a plugin did not opt
  into.
- **Compatibility.** In 0.x a node loads plugins of its own api major.minor only; from 1.0 on, of its
  major and a minor up to its own.
- **Limits.** At most 64 plugins, 512 jars per plugin and 256 contributions of each kind per plugin.

A refused plugin, step type or language stops the node with exit 2 before Vert.x starts; a refused
component, with exit 1. `rail8 validate` and `rail8 test` load the same plugins, from `--plugins` or
`RAIL8_PLUGINS`.

### Writing an adapter plugin

An adapter plugin is built with the development kit alone: `rail8-api` (the `Plugin` and
`Component` contract, `provided` scope: the node has it, and Vert.x core with it) and, for its
tests, `rail8-testkit` (the contract suites `ComponentContract` and `PollingConsumerContract`,
the fakes `FakeRuntime`, `FakeClock` and `FakeTimer`, and `RecordingComponent`, the mock of any
scheme). `examples/webhook-plugin` is a sample: its own Maven build, a component of the scheme
`webhook` (an HTTP consumer and producer), and a package that uses it.

What a component promises the node:

- **Acknowledgement.** The consumer hands each message to the flow's handler and finishes its
  protocol only from the `Ack` the handler's future completes with: `Accepted` once the message is
  durably saved, and only then may the sender let go; `Reply`, the flow's answer, for a sync flow;
  `Nack`, refused, either for good or "try again". A handler that fails, or answers nothing, is
  not an acknowledgement: the sender must not take the message as delivered. The handler runs on a
  virtual thread of its own for each delivery, never on an event loop.
- **Back-pressure.** A flow with no room answers a retryable `Nack` before anything of it runs;
  the consumer turns it into the protocol's "try again later" (503 for the sample). A producer
  names the target system it calls (`target`, `scheme://host:port`), and the node limits the sends
  in flight to one target (`RAIL8_TARGET_*`), shared by every flow that sends there.
- **Sending.** A producer streams the body from a fresh `open()` on every send, so a retry sends
  the same bytes again; it sends the message id as `x-message-id`; a bounded send is cut, failing
  with a retryable `NackException`, when its deadline runs out, and never starts with none left.
- **Stopping.** A consumer's `stop()` takes no new message, waits for the deliveries in flight
  and lets their answers reach the senders.
- **Settings.** `settings()` describes every option an endpoint may set, the same on every call;
  the node checks endpoint files against it and the UI draws the form from it. `flow` and
  `maxBodyBytes` on an in endpoint are the node's and are no field of it.

Build, test and install:

1. **Build.** A plugin's jar holds its classes and a
   `META-INF/services/dev.rail8.api.plugin.Plugin` naming its `Plugin`. A library it needs goes
   beside it: the plugin is then a directory of jars.
2. **Test.** The component's test extends `ComponentContract` (a polling consumer's,
   `PollingConsumerContract` too). Its mock, `RecordingComponent` for the plugin's scheme, passes
   the same suite; code that uses the scheme is tested against the mock, with no socket.
3. **Check a package.** `rail8 validate --plugins <dir> <package>` and
   `rail8 test --plugins <dir> <package>` build and run a package that uses the plugin.
4. **Install.** Put the jar, or the directory, into the folder that `RAIL8_PLUGINS` names and
   restart the node. It logs the plugin's name, version, api version and SHA-256.

For the sample, `mvn -f examples/webhook-plugin/pom.xml verify` (after the platform is installed)
runs its contract tests and writes the jar into `examples/webhook-plugin/target/plugin`, a folder
to give to `--plugins` or `RAIL8_PLUGINS`.

### The API for custom code

A step factory, a plugin's or the node's own, gets `services()` among its resources: the step's
secrets and a cache. With the envelope (read it, return a new one) and standard Java logging (its
lines go to the node's log and to the message's history), that is what custom code works with, as a
script in SAP Integration Suite works with security material.
`HmacSignPlugin` in the runtime's tests is a sample step that uses all of it.

- **Secrets.** `secret(step, key)` takes the step's option `key`, whose whole value must be
  `secret://<name>`, while the flow builds, and returns a `Secret`: its reference, and its value,
  read from the node each time the step asks for it and never kept in the flow. A literal value is
  refused without being shown. A secret that is not set refuses the deploy, naming the step, the
  option and the variable `RAIL8_SECRET_<NAME>`; the deploy also checks every `secret://` value
  anywhere in a step's options. `rail8 validate` and `rail8 test` read every secret as
  `https://secret.invalid/`. In a flow whose steps took secrets, a failure of a step that a factory
  built has every one of those values replaced by its reference in what is recorded, parked, logged
  and answered; its kind and whether it is retried stay as they were.
- **Cache.** `get(key, loader)` returns the value kept under `key`, or runs the loader and keeps what
  it returns for its time to live (at most 24 h): a token fetched an unusual way, a key derived once
  a day. Keys are up to 256 characters, values up to 64 KiB. While a key loads, other callers on the
  node wait for that load instead of starting their own. The steps of one type in one package share
  their keys, and no one else sees them. A node keeps the cache in memory, at most 10,000 entries and
  64 MiB, dropping the least recently used first; entries stay across a redeploy.
- **Logging.** A line logged through `System.Logger`, `java.util.logging` or SLF4J on the message's
  thread while the step runs, at or above the level the node's log shows for its logger, is kept in
  the step's record: its time, level, logger, text, and the failure logged with it (class and
  message, no stack). The flow's `recording.level` keeps the lines where it keeps the step's record.
  One run of a step keeps at most 100 lines and 32 KiB of them, whole lines only, and counts the
  lines it did not keep. A line logged after the step handed its first message on, or on a thread
  the code started itself, reaches only the node's log. In a kept line every value the flow's steps
  took as a secret is replaced by its reference; the node's log gets the line as it was logged.
- **Testing.** The testkit's `FakeServices` gives a step under test its secrets and a cache on a fake
  clock. Every cache passes the testkit's cache contract.

### Mocks and contract suites

Every component ships with a mock that passes the same contract suite as the real one. The testkit
holds the suites: one for every component (delivery, stop, acknowledgement, retry, streaming of large
bodies, deadlines, message id, and the settings description its endpoints pass), one for polling
consumers, and one for blob stores. The `mock`
component stands in for any scheme in `rail8 test`, replying from fixtures with a delay, a failure or
a file to poll; a deployed node refuses it.

**Planned**
- Plugins for custom mapping code and custom services, and a plugin that replaces a shipped
  component or step.
- The admin API lists the loaded plugins.
- A deferred acknowledgement in the adapter contract: system and application acknowledgements as
  in SAP XI.
- Custom code sends through an outbound endpoint of its package.
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

The `mapping` step runs a mapping in a flow, through the engine's embedding API.

### The embedding API

The module `mapping/engine/embed` (`dev.rail8.mapping:rail8-mapping-embed`, JPMS
`dev.rail8.mapping.embed`) is the engine's embedding API: the one engine package with a compatibility
promise. It requires `dev.rail8.mapping.api` without `transitive`, exports only its own package, and
its signatures use JDK types and its own.

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
  record Relative(String path) implements BundleReference {    // ../structures/a.xsd, as written
    public String spelling() { return path; }
  }
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
  unchanged, so the host's own refusal keeps its words. A problem's `at` is a JSON pointer into the
  mapping file (`""` is the file itself), an authoring id, or a schema file's `file:line:column`.
- `run` reads all of `input` (XML without a DOCTYPE, or JSON, as the source structure says) before
  it writes, and returns the whole output or throws, never part of it. `now` is the time every date
  function sees, and its offset is the run's time zone; a run reads no clock and no file. Output
  above `maxOutputBytes` (at least 1) stops the run with `OUTPUT_TOO_LARGE`. An interrupt ends it
  with `InterruptedException`, the thread's interrupt status cleared. Anything else it throws is an
  engine bug.
- Memory: the host holds the input, the output is at most `maxOutputBytes`, and the engine's working
  memory grows with the input and the mapping, with no limit of its own until one is measured.
- A new `Reason` breaks hosts on purpose: a host maps reasons with a `switch` expression that has no
  `default`, so a recompiled host does not compile and an old one fails with `MatchException`, which
  is retried.
- The contract suite is the tests of `Mapping` in `engine/embed`, so a later bytecode tier behind
  `Mapping.compile` passes the same tests. There is no mock: the engine is code in the process, not a
  way out of it.

### The mapping step

The step module `platform/steps/mapping` (`dev.rail8:rail8-step-mapping`) registers the step type
`mapping`. It depends on `rail8-api` and `rail8-mapping-embed`, and it is the only platform module
that depends on the mapping, and only on `dev.rail8.mapping.embed`; an architecture test checks both.
The node and the command line ship it. Every CI job and documented command that builds the platform
first installs the engine's `embed` module from the same commit with `bin/install-mapping`
(`./mvnw -f mapping/pom.xml -pl engine/embed -am install -DskipTests`).

```yaml
steps:
  - { type: mapping, mapping: mappings/order-to-invoice.mapping.json }   # or <dependency>:<path>
```

- Options: `{"type":"object","required":["mapping"],"additionalProperties":false,"properties":
  {"mapping":{"type":"string","minLength":1}}}`. `mapping` is a path from the package root or
  `<dependency>:<path>`, read with `StepResources.file`. The step reads the whole body.
- A mapping is a file of the package that a step names, as a stylesheet is; it has no `kind`. The
  studio's layout is the convention: `mappings/<name>.mapping.json`, its schemas in `structures/`.
- The mapping file keeps the engine's spelling of its references. A `Relative` reference's `path`
  resolves against the mapping file through `StepResources.resolve`, inside the package or
  dependency that holds the mapping. A `Packaged` reference is a build error naming the step and
  the reference.
- The flow's hash covers the mapping file and every schema file it read, since both are read
  through `StepResources`. The engine's version is not in it, as Saxon's is not. A refusal, a
  fingerprint mismatch and a file outside the package are build errors naming the step, at deploy,
  in `rail8 validate` and in `rail8 test` alike.
- A run sees the node's injected clock: its time, and its zone as the run's time zone.
- A run writes its output to the blob store under the flow's body limit, with `content-type` from
  `mediaType()`. Its faults: `MALFORMED_INPUT` is MALFORMED; `OUTPUT_TOO_LARGE` is INVALID, as a body
  over the limit is; `DATA_ERROR` is INTERNAL and retried, because a data error need not come from the
  input (a box with a bad constant fails every message); an interrupt is rethrown with the flag
  restored, so the worker leaves the item to its lease.

**Planned**
- A bytecode tier for speed, later.
- A point where an importer of SAP PO and CPI message mappings plugs in, with a contract of its own
  that is designed first. It runs at design time, before compile, and writes a canonical
  `*.mapping.json` and its schema files into a package. The step and the embedding API do not change
  for it, and nothing is imported at deploy time: the reviewed file in git is what runs.
- `Packaged` references in the step, once their version can be compared and the studio can refresh
  such a mapping.
- UDFs, and what a mapping may later read besides the body (headers, properties, mapping
  parameters), wait for the platform's data model. They come as additions, such as a `run` that
  takes a context.

## 8. jq

The jq engine is in `jq/`, a project of its own: a parser, a compiler to closures and a byte-level
JSON reader and writer, with a command line and tests against the real jq. Embedded use has limits by
default, among them a recursion depth of 10 000, settable through the engine's builder; the command
line uses jq's own limits. Some depth limits (JSON reading, printing, comparing) are fixed at 10 000.

The `jq` step runs it in flows. Its `filter` is a jq program written in the flow, compiled once when
the flow is built and run on each message's body, read whole as one JSON document. It must give
exactly one result, which becomes the next body as compact JSON (`application/json`). A run has the
engine's embedded limits, except two: the bytes it writes are bounded by the flow's `maxBodyBytes`,
and its recursion depth is the step's `maxRecursionDepth`, else the node's
`RAIL8_JQ_MAX_RECURSION_DEPTH` (10 000, at most 4 194 304). `rail8 validate` and `rail8 test` use
10 000. A body that is not JSON is the sender's fault; an error the filter raises, no result, a
second result or a budget run out is the flow's. The platform's build takes the engine's jar from
the local Maven repository, put there by `bin/install-jq`; the engine's own tests run in `jq-ci`.

**Planned**
- Wider jq in the step: the filter from a file, host variables, several results per message.

## 9. UI

The Rail8 web application in `ui/` is React with PatternFly 6, built from PatternFly components
and design tokens only. Its shell has a masthead, a navigation listing every screen and the tabs of
the open screens: choosing a screen opens it in a tab, or shows the tab it already has, and each tab
closes on its own, and a screen may open tabs of its own. The tabs live in the page's memory; a
reload starts again with the Home screen. Screens are registered in one list (`ui/src/screens.tsx`).

The Messages screen finds messages by business key (name and value, a page at a time) or by id, and
opens each one found in a tab of its own. A message's tab shows its flow, the state of its latest
attempt and every attempt step by step: outcome, start, wall and CPU time, payload size and error.
The payload after a step opens in a dialog (its first MiB) when its bytes were kept; when the latest
attempt failed, its error is shown with Restart (`POST /admin/dlq/{id}/retry`).

The Mappings screen lists the mappings of the mapping service's workspace, finds them and creates
them, and opens each one in a tab of its own, so that several mappings can be open at once. A
mapping's tab is the mapping studio: it opens read-only; Edit, then Save (with `If-Match`, and a
dialog when the file changed on disk), Cancel and Check; and its tabs Overview, Signature,
Definition (the message trees, the field mappings and the expression canvas, on React Flow), Test
and Functions. Closing the tab of a mapping with unsaved changes asks first. The studio's types are
generated from the mapping service's contract (`mapping/contract/`, `npm run gen:types`), and CI
fails when they differ from it.

The Endpoints screen lists the endpoint files of a working folder (`RAIL8_WORKSPACE`, a folder of packages; files
are found by their `kind`, hidden folders, `node_modules` and `target` passed over) and opens each one in a tab of its
own: the endpoint's settings form, drawn from its component's settings description (`GET /admin/components`), the same
for a shipped component and a plugin's. It shows the platform's tabs around the component's own, the fields that serve
the endpoint's direction and are visible, with their help texts, defaults and required marks, the environments that
override settings, and the problems the check finds, judged in the browser by the rules the deploy uses (one file of
cases runs against both). A component that describes nothing, and a field type the form does not know, get a raw JSON
editor. The form opens read-only; Edit, Cancel, Check and Save as in the mapping studio. Save sends only `config`, with
`If-Match`, and the development server rewrites only the lines of the settings that changed, keeping comments and
order, through a temporary file, and refuses (writing nothing) a file whose `config` it cannot edit line by line or that
would not read back as sent. With `RAIL8_READ_ONLY=true`, as on a server, the files are shown and every save is refused.
Without the description the form shows the file's settings read-only and says why. The UI never changes the running
node: an edited package is deployed as any other.

No node serves the UI yet; `npm run dev` runs it. In development the Vite server, on localhost:5173,
passes the UI's `/admin` requests on to the node (`RAIL8_ADMIN_URL`, default
`http://127.0.0.1:8081`), as the node answers no browser: it takes only GET and POST from its own
pages (`Sec-Fetch-Site: same-origin`, an `Origin` from its fixed list), drops every header but
`Accept` and `Content-Type`, and adds the token from `RAIL8_ADMIN_TOKEN` itself, so the browser
never holds it. The mapping studio's `/api` requests go to the mapping service on the same machine
(port `QUARKUS_HTTP_PORT`, default 8080). The endpoint forms' `/files/endpoints` requests are answered by the
development server itself, under the same same-origin rule.

The UI's end-to-end scenarios (`ui/e2e/`, Playwright) run nightly: the 20,128-field tree scrolled
over a fake mapping service on a development-only page, and the `@stack` scenarios against the
mapping service's jar. The admin API answers JSON and refuses browsers. `site/` is the project's
public web page.

**Planned**
- Finding messages by flow and time, which the admin API cannot search by yet.
- A server for the UI outside development, holding the token and talking to the node the same way.
- Helper actions on a field of an endpoint's settings form. No screens of a plugin's own.
- Editing endpoint files on a shared server, written to git with an author, with deploy from git and sign-in.
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
  SLF4J, which Netty, the AWS SDK and MINA SSHD use, logs through `java.util.logging` too
  (`slf4j-jdk14`); the node has Vert.x log to `java.util.logging` straight, so that its lines name
  their own logger. The lines a step's code logs are also kept in its record (§5).
- There is no metrics endpoint. Each record has wall and CPU time per step, and an optional CPU
  sampler (`RAIL8_CPU_SAMPLING`) reports CPU by step.
- `/live`, `/ready` and `/health` (Postgres, deployments, journal) answer without a token.

### Admin API

On `127.0.0.1:8081` by default. With `RAIL8_ADMIN_TOKEN` set, every `/admin` route needs it as a
Bearer token; without one the API may only listen on loopback. There are no users or roles.

| Route | Purpose |
| --- | --- |
| `GET /admin/messages?key=…` | find messages by business key |
| `GET /admin/messages/{id}` | a message's steps with their times and the lines their code logged |
| `GET /admin/messages/{id}/steps/{n}/body` | the body after step `n` |
| `GET /admin/dlq`, `POST /admin/dlq/{id}/retry`, `POST /admin/dlq/{id}/cancel` | failed messages, running one again, ending one |
| `POST /admin/messages/{id}/cancel` | cancelling a message that has not ended |
| `GET /admin/work/stopped` | keys of ordered flows stopped behind a failed message |
| `GET /admin/work/held`, `POST /admin/work/{seq}/release` | held items |
| `GET /admin/work/lost`, `POST /admin/work/{seq}/discard` | items whose body was lost |
| `PUT`, `DELETE /admin/packages/{name}`; `GET /admin/packages`, `GET /admin/flows` | deploy, undeploy, what runs |
| `GET /admin/targets`, `GET /admin/flows/{flow}/cpu` | limits per target system, CPU by step |
| `GET /admin/components` | the components the node serves and their settings descriptions |

Configuration is by environment variables `RAIL8_*`; `RAIL8_ENV` is required, and any value other
than `dev` or `ci` is a deployed environment with stricter rules (no manual deploy, no mock
endpoints, and by default a synchronous Postgres standby).

### Running it

`deploy/compose` starts a node with Postgres, Valkey, S3 (SeaweedFS), an SFTP server and a partner
that echoes, and deploys `examples/demo`. The root `docker-compose.yml` is a smaller quickstart
(`docs/QUICKSTART.md`).

**Planned**
- One log of records per message, per interface and per connection, with levels and a threshold that
  can be changed at run time, holding the lines custom code logs.
- Metrics and alerts inside the platform, OpenTelemetry for export, with no required extra product;
  a full queue waits and alerts, it never drops.
- Teams, rights and roles; a separate right to see payloads, with a record of who viewed one.
