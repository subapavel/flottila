# CONSIDER-13 design draft — the message log

**Status: NOT DECIDED.** The designer's draft, handed back 2026-11-14. The owner postponed the
decision to the evening of 2026-11-14 and decides it in person ("tohle bych chtěl probrat
důkladněji a rozhodnout sám s tvojí pomocí"). Nothing here is in `docs/DECISIONS.md`, nothing is
in `docs/ARCHITECTURE.md`, and no implementation row exists. Do not implement from this file.

The architect's questions for that conversation are in the last section; the owner has answered
none of them.

## What it is

A severity-ordered stream of short sentences *about* a run, written by the platform and by steps,
retrievable per message and per flow. It is a THIRD thing beside the two that exist:

- not the snapshots (one fixed row per step, gated by `recording.level`, no severity, cannot hold
  several sentences per step),
- not the node's `System.Logger` output (node-local, no message correlation, not retained with the
  message, not queryable by an operator).

It carries **no payload** — bytes live in snapshots; a line may name a body hash or key.

Owner's framing, confirmed 2026-11-14: this is SAP PO's *Staging and Logging* pair. `recording` is
PO's *staging*; this is PO's *logging*. PO configures both per ICO per step; CPI collapsed logging
into one *Log Level* per iFlow (None/Info/Error/Debug/Trace). The draft is the CPI model with PO's
per-step override.

## Scopes

One row shape, `message_id` nullable: null = a flow-scoped line (deployed, started, stopped,
endpoint bound, consumer refused). The "flow log" is then the same rows filtered by flow, not a
second thing.

## api

- `enum LogLevel { OFF, ERROR, WARN, INFO, DEBUG, TRACE }`, ordinal = verbosity; `OFF` is a
  threshold only, a line's level is never `OFF`.
  **The architect disagrees with this item** — see the questions below: `System.Logger.Level`
  already has `TRACE, DEBUG, INFO, WARNING, ERROR, OFF, ALL` with a `getSeverity()` that gives the
  threshold comparison for free, it is in the JDK (so `api` may name it), and it is already the
  vocabulary the platform logs in. The designer's counter-argument is that
  `System.Logger.Level.WARNING` is a different, node-level concept and that `warn` is the
  slf4j/log4j/CPI spelling an integrator expects in a config file. The owner wrote `warning`.
- `interface StepLog { boolean enabled(LogLevel); void log(LogLevel, String); default void info(String) ... }`
  — an interface because `api` may not name core; two implementations on day 1 (`StepLog.NONE`,
  core's `RunStepLog`).
- `StepResources` gains a component `StepLog log`; `FlowBuilder` builds one `StepResources`
  **per step** (today one per flow, `FlowBuilder.java:425`) through a `withLog` deriver that pins
  that step's index, name and threshold. The existing 7-/8-arg constructors default to
  `StepLog.NONE`.

## core

- `record LogPolicy(LogLevel level)` mirroring `RecordingPolicy` (`from(node, base)`,
  `writes(LogLevel)`, `DEFAULT = INFO`).
- `record LogLine(String messageId /*nullable*/, String flow, String tenant, int attempt, int step,
  String stepName, long ordinal, LogLevel level, Instant at, String text)`.
- `final class RunLog` — a `ScopedValue` bound beside `Trail`; assigns `ordinal`, buffers, flushes
  at 256 and at close, **never awaits**.
- `interface MessageLogStore { Future<Void> append(LogLine); Future<Page<LogLine>> read(String messageId, int attempt, Optional<String> after, int limit); Future<Page<LogLine>> readFlow(String flow, LogLevel atLeast, Optional<String> after, int limit); }`
  — in-memory and Postgres on day 1.

## Levels, concretely

| Level | What goes on it |
| --- | --- |
| ERROR | the run or a step failed, parked, cancelled (D-110) |
| WARN | recovered or degraded: a retry attempt (D-106), a handled failure (D-105), metadata-only recording, dropped log lines |
| INFO | the operator's narrative, one line per step (`accepted from <endpoint>, <n> bytes`, `step 3 map ok in 12 ms`, `ended at filter`, `done in 41 ms`) |
| DEBUG | decisions inside a step: route branch chosen, endpoint by prop, extracted key values, split part count, mapping template |
| TRACE | per-part and per-header detail, per-send status — never bodies |

`debug` belongs: without it, DEBUG-volume decisions and payload-scaled detail share one bucket.
A line is written iff `line.level.ordinal() <= threshold.ordinal()`. `off` writes nothing, not even
errors — safe, because the `FAILED` snapshot and its error text stay.

## Who writes

The platform writes from `Recorder.start` (the ingress INFO) and `Recorder.wrap`
(OK → INFO with wall ms, FILTERED → INFO, FAILED → ERROR with the failure text) — the same hook as
the snapshot, so the log and the timeline cannot disagree; `FlowWorker` writes retry, park,
handled, resent, cancelled. A step writes only through its `StepLog`: no `Runtime`, no socket, no
envelope, no other step's channel.

Declaratively, a new `log` step type `{type: log, name, level, message: <expression>}` over the
existing `Expressions`, rendered text capped at 1 KiB — additive, deferrable. **The architect would
defer it**: an expression that renders the body defeats a flow's `recording.payload: metadata-only`
choice, which is a privacy question and not part of what the owner asked for.

## Write path

`PgMessageLogStore` holds **its own** `BatchingWriter<LogLine>` over `PgBatchSink<LogLine>` (one
statement), separate from `PgMessageStore`'s, so a trace flood cannot evict snapshot rows or take
their commit slots; `Options.defaults()` until a bench number exists. `save`, never `saveWaiting`:
a full backlog **drops** the line, counts it, and the run appends one WARN
`N log lines dropped (writer overloaded)` at close — losing a line loses no message, and the
operator never reads a silently short log. D-104 needs nothing: a refused window commits.

## Bound

Per message **per attempt** `maxLogLinesPerMessage`, finite, default 1000. On the bound the run
writes one last WARN `log truncated at <n> lines` and records nothing further. Per flow there is no
second number: the flow's aggregate bound is its writer's finite backlog with drop-and-count — a
per-flow rate needs a window and a clock and buys nothing the backlog does not.

## Schema

Flow level beside `recording` (`flow.schema.json:33`) and step level (`:86`), same merge pattern:

    "log": { "$ref": "#/$defs/log" }
    "log": { "type": "object", "additionalProperties": false,
             "properties": { "level": { "enum": ["off","error","warn","info","debug","trace"] } } }

and in `limits`: `"maxLogLinesPerMessage": { "type": "integer", "minimum": 1 }`, default 1000.

## Table

Migration after D-108's.

    message_log(seq bigserial primary key, message_id text, attempt int not null default 0,
                step int, step_name text, flow text not null, tenant text not null,
                level text not null, text text not null, ordinal bigint not null,
                created_at timestamptz not null)

`created_at` from the injected clock; no FK (as `dlq`); unique `(message_id, attempt, ordinal)
where message_id is not null` with `on conflict ... do nothing`; indexes `(message_id, attempt,
ordinal)`, `(flow, created_at, seq)`, and `message_log_created_at (created_at, seq)` for V10's
retention. Order inside a message is `ordinal` — not `seq` (batches reach the sink unordered,
`BatchingWriter:206`) and not `created_at` (a fake clock does not advance).

## Admin

`GET /admin/messages/:id` gains `"log": {"href": "/admin/messages/<id>/log", "lines": <n>}`.
Lines at `GET /admin/messages/:id/log?attempt&level&after&limit` and
`GET /admin/flows/:flow/log?level&after&limit`, both paged with the existing `Page` cursor.
404 only when the message has no timeline; a message with no lines is 200 and an empty page.

## Tests the design names

- `logPolicy_writesOnlyAtOrBelowTheThreshold`, `offWritesNothingNotEvenError` — ordering semantics.
- `stepLogOverrideReplacesTheFlowLevelKeyByKey` — `recording`'s merge rule (D-044) applied to `log`.
- `recorderWritesInfoPerOkStepAndErrorOnFailure` — log and timeline agree on step and name.
- `aStepWritesOnlyThroughItsStepLogAndSeesNoRuntime` — ArchUnit plus no `Runtime` reachable from
  `StepResources`.
- `runLogNeverBlocksTheMessageThread` — a sink that never completes still lets the run finish.
- property (jqwik) `anyLineSequenceYieldsAtMostMaxPlusOneRowsWithTruncationLast`;
  property `ordinalsAreDenseAndIncreasingPerAttempt`.
- contract suite `MessageLogStoreContract` over `InMemoryMessageLogStore` and `PgMessageLogStore`
  (Testcontainers): append/read order, the paging cursor, the flow read with a level filter.
- `fullBacklogDropsLinesCountsThemAndAppendsOneWarning` — drop accounting, `logLinesDropped` on
  `/admin/health`.

## Rejected by the designer

- Folding it into the snapshots as a `level` + `text` column: one row per step, gated by
  `recording.level`, cannot hold several sentences nor a severity.
- Folding it into `System.Logger` with an MDC-style context from `MessageContext`: not retrievable
  per message, not retained with the message, not queryable.
- Two tables (`message_log` + `flow_log`): same shape, same retention, same screen; a nullable
  `message_id` with a partial unique index is cheaper.
- Reusing `PgMessageStore`'s writer: a trace flood would evict snapshot rows from a shared 6400
  backlog.
- `saveWaiting` for log lines: it holds the run's admission permit for a line whose loss loses no
  message.
- `warning` as the schema value, in favour of `warn` — see the architect's disagreement above.
- A per-flow lines-per-second budget: needs a window and a clock; the writer's backlog bounds it.

## Expensive to reverse

- `StepResources` gaining a component and being built per step: public API, every `StepFactory` and
  every day-1 call site (`SimSteps`, `FlowTests`, `FlowHash`, both step modules).
- `message_log`'s columns and its unique index: a persisted format; `ordinal` is the public
  ordering key and appears in the admin JSON.
- The level names and their order: flow-file wire format (schema v1) and an admin query parameter.
- `GET /admin/messages/:id/log` and `/admin/flows/:flow/log`, and the `log` object added to the
  timeline response.

## Conflicts the design found

- §7's persistence table list already names an `audit` table (`ARCHITECTURE.md:323`), so
  "audit log" is taken; the draft calls this the **message log** throughout. The name needs
  confirming before any edit.
- §8's write path says every snapshot goes "local batch queue → Kafka `monitoring` → ClickHouse".
  Log lines are Postgres-only today (no ClickHouse client exists), so §8 must say the message log
  follows the snapshots to ClickHouse in P2-S1/S2, or the deviation is silent.
- `recording.payload: metadata-only` keeps bodies out of the store; a declarative `log` step whose
  `message` expression renders a payload defeats that per-flow choice.

## Facts the designer verified differently from the architect's brief

- `BatchSink<T>` confirmed only through `PgBatchSink implements BatchSink<T>` with
  `Future<Void> write(List<T>)`; `BatchSink.java` itself not opened.
- `MessageStore` has a fifth member the brief did not name: `default Future<Void> recordWaiting`
  delegating to `record` (`MessageStore.java:25`); `PgMessageStore` also exposes package-private
  `insert(SqlClient, Snapshot)` for the D-082 intake transaction.
- The highest migration on disk is `V11__drop_payloads.sql`, and `messages` is still
  `on conflict (id, step)` (`PgMessageStore.java:55`), so D-107/D-108's `(id, attempt, step)` and
  `message_lifecycle` are unwritten. This table comes after them and assumes `attempt` exists.
- `StepResources`' javadoc says it is what a factory may hold **at build time**, and it is built
  once per flow (`FlowBuilder.java:425`), not per step — hence the `withLog` deriver, because a
  per-step threshold cannot come from the shared record.

## Open — for the owner, evening 2026-11-14

1. **The level vocabulary.** `System.Logger.Level` as it stands (`trace, debug, info, warning,
   error, off`), which is the JDK's, already the platform's, and the owner's own word `warning`;
   or a new `api` enum with `warn`, which is the slf4j/log4j/CPI spelling. Architect recommends the
   first.
2. **The default threshold.** `info`, as CPI and PO default to a narrative; or `warn`, with `info`
   the thing an operator switches on per flow. Architect recommends `info` — a platform whose
   product is monitoring must not ship silent — but no write-throughput number exists in
   `bench/RESULTS.md` to price it.
3. **May a flow author write a line at all** (the declarative `log` step)? Architect recommends
   deferring it: the expression could put a payload in the log and defeat
   `recording.payload: metadata-only`.
4. **`off` writes nothing, not even errors.** Confirm; the error text survives on the `FAILED`
   snapshot either way.
5. **The name** `message log` / table `message_log`, since §7 already has `audit`.
6. **Retention**: one knob (`FLOTTILA_RETENTION_MAX_AGE`, P7D, as the messages) or a shorter age
   for `trace` lines. Architect recommends one knob.
7. **A resend's log.** The designer wrote "a resend is a new id"; **that contradicts D-107** (one
   id for the life of a message, the owner's decision: "přeposlaná zpráva nemůže mít nové id"). The
   correction: a resend is a new *attempt* under the same id, its lines carry `attempt`, and the
   message's log shows every attempt, filterable. Nothing is copied. Confirm the correction.
8. **The log writer's `Options`** (batch, window, concurrency, backlog): `defaults()` until a bench
   row exists.
