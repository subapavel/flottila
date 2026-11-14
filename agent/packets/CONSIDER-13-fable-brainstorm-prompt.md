# CONSIDER-13 — brainstorming prompt for a fresh Fable session

Written 2026-11-14 by the architect session, for the owner to paste into a separate Fable
conversation. It is self-contained: assume the other session has never seen this repository.

Everything below the line is the prompt.

---

You are helping me think through one open design decision in my own project. This is a
**brainstorm, not a review and not an implementation**: I decide at the end, and I want the
thinking out loud more than I want a verdict. Write nothing to any repository.

**Answer in Czech.** The material below is in English because the project is; the conversation is
not. Take one thread at a time rather than answering everything at once — I would rather go three
levels deep on the question that matters than one level deep on eight.

## The project, in a paragraph

Flottila is an open-source enterprise integration platform — the category of SAP PI/PO, SAP
Integration Suite (CPI), MuleSoft, Camel. Java 25, Vert.x 5.1, PostgreSQL, Kafka, ClickHouse,
Valkey, S3, Apache 2.0. Its one differentiating claim is that **monitoring is the product**: every
message, every step and every payload is captured, searchable and replayable. A message runs on one
virtual thread through a pipeline of steps; everything touching the outside world goes through a
Component SPI.

Constraints that are settled and are not up for discussion, because they shape what is buildable:

- Nothing may block the message's thread for something whose loss does not lose a message.
- State whose loss loses messages goes to Postgres. State whose loss only changes tempo may live in
  memory or Valkey.
- Every limit is finite. Nothing defaults to unlimited.
- Module dependencies run `api` ← `core` ← `runtime`. `api` may name the JDK and a little of
  Vert.x, nothing else. An interface exists only where a second implementation exists on day 1.
- A step is a pure-ish function from envelope to envelope. It may not reach the runtime, open a
  socket, or see another step's state.

## What already exists, so we are clear what we are adding to

**1. Snapshots ("recording").** One fixed row per message per step, written by the platform, gated
by a per-flow and per-step `recording.level`, holding the payload (or metadata only, if the flow
says so). This is the timeline an operator replays from. It has no severity, and it cannot hold
several sentences about one step.

**2. The node's `System.Logger` output.** Ordinary application logging to the node's stdout. It is
node-local, it is not correlated to a message, it is not retained with the message, and an operator
cannot query it.

## The thing being considered

A **third** mechanism: a severity-ordered stream of short sentences *about* a run — written by the
platform and, possibly, by steps — retrievable per message and per flow. It carries no payload; a
line may name a body hash or key.

My own framing, which the design took up: this is **SAP PO's Staging and Logging pair**. What
Flottila calls `recording` is PO's *staging*; this new thing is PO's *logging*. PO configures both
per ICO per step. CPI collapsed logging into a single *Log Level* per iFlow
(None / Info / Error / Debug / Trace). The draft is essentially the CPI model with PO's per-step
override.

## The draft on the table

A designer produced this; I have approved none of it.

- A level enum `OFF, ERROR, WARN, INFO, DEBUG, TRACE`, a line written iff its level is at or below
  the configured threshold. `off` writes nothing, not even errors.
- A `StepLog` handed to each step through its build-time resources, so a step can write a line and
  reach nothing else.
- A `LogLine` record (message id — nullable, flow, tenant, attempt, step index, step name, ordinal,
  level, timestamp, text) and a `MessageLogStore` with in-memory and Postgres implementations.
- One Postgres table `message_log`. A **null message id** means the line is flow-scoped (deployed,
  started, endpoint bound), so the "flow log" is the same rows filtered, not a second thing.
- Its own batching writer, separate from the snapshot writer, so a trace flood cannot evict
  snapshot rows. A full backlog **drops** lines, counts them, and the run appends one warning at
  close.
- A finite bound per message per attempt (default 1000 lines), then one final "log truncated" line.
- Admin endpoints: `GET /admin/messages/:id/log` and `GET /admin/flows/:flow/log`, paged.
- Levels, concretely: ERROR = failed/parked/cancelled; WARN = retried, degraded, dropped lines;
  INFO = one narrative line per step; DEBUG = decisions inside a step (route branch, extracted
  keys); TRACE = per-part, per-header, per-send detail. Never bodies, at any level.

## What I want from you, in this order

**First, challenge the premise, because nobody has.** The designer rejected folding this into the
existing snapshots (one row per step, no severity, gated by a different knob) and rejected folding
it into `System.Logger` with message correlation. Those rejections are one paragraph each and were
never argued against. A third retrieval path, a third table, a third config knob and two more admin
endpoints is a lot of surface for a platform that already claims to capture everything. So:

- Is a separate message log actually justified, or is it the shape of SAP's product leaking into a
  design that does not have SAP's constraints?
- What does an operator do with it that the snapshot timeline genuinely cannot answer?
- If you would cut it, say what the user loses and whether they would notice.

I would rather you talk me out of it now than have me find out after it is a persisted format and
a public API.

**Second, the one that will hurt most if wrong.** Which single element here is the most expensive to
reverse, and is it being decided on the thinnest evidence? My candidates: the per-step resources
object gaining a component (it is public API, every step factory and call site changes), the
table's columns and its ordering key (a persisted format that appears in the admin JSON), and the
level names (wire format in the flow file and an admin query parameter). There is **no benchmark
number** anywhere for what this write path costs.

**Third, these specific open questions.** My own leaning is in brackets; disagree freely.

1. **Level vocabulary**: reuse the JDK's `System.Logger.Level` (`trace, debug, info, warning,
   error, off`), which is already what the platform logs in — or define a new enum with `warn`,
   which is the slf4j / log4j / CPI spelling an integrator expects in a config file.
   [architect: reuse the JDK's]
2. **Default threshold**: `info`, as PO and CPI default to a narrative — or `warn`, with `info`
   switched on per flow when something is being investigated. [architect: `info`, because a
   platform whose product is monitoring must not ship silent — but there is no throughput number
   to price it]
3. **May a flow author write a log line at all** — a declarative `log` step with an expression for
   the text? [architect: defer it; the expression could render a payload into the log and defeat a
   flow's "metadata only" privacy choice]
4. **`off` writes nothing, not even errors.** The failure text survives on the failed snapshot
   either way. Confirm or reject.
5. **The name.** The architecture already has a table called `audit`, so "audit log" is taken; the
   draft says "message log" throughout.
6. **Retention**: one knob for everything (7 days, as the messages) — or a shorter life for `trace`
   lines. [architect: one knob]
7. **A resend.** The designer wrote "a resend is a new id". That contradicts a decision I already
   made — one id for the life of a message; a resent message cannot get a new id. The correction is
   that a resend is a new *attempt* under the same id, and the log shows every attempt, filterable.
   Confirm the correction holds and nothing else in the draft depends on the wrong version.
8. **Write-path tuning** (batch size, window, backlog): defaults until a benchmark exists.

## How to argue with me

Say plainly when you think I am wrong, and name the evidence you would need to be sure. If a
question cannot be answered without a number that nobody has measured, say that instead of
guessing — "measure this first" is a legitimate answer and I would rather hear it than a confident
preference. Where you propose something the draft does not contain, say what it costs.
