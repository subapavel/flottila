# Flottila — rules for Claude

Read in this order before any work: this file, `agent/HANDOFF.md`, `agent/BACKLOG.md`,
`docs/WORKFLOW.md`, `docs/ARCHITECTURE.md` (binding design), `docs/PROTOTYPE-VS-ARCHITECTURE.md`,
`docs/GATE.md`. Everything in the repo is in English.

## What this is

Open-source enterprise integration platform where **monitoring is the product**: every message,
every step and every payload is captured, searchable and replayable. Java 25, Vert.x 5.1,
PostgreSQL, Apache Kafka, ClickHouse, Valkey, S3. Apache 2.0. `docs/ARCHITECTURE.md` says what to
build; this file holds the rules that keep the code and the way of working aligned with it.

## Design rules (non-negotiable)

- `Envelope` is an immutable record; every step returns a new one. Headers / props / keys /
  attachments stay separate fields. Body and parsed views are shared by reference across `with*`.
- `Body` is one final class over a `BlobStore`: `hash()`, `size()`, `open()` returns a fresh cold
  stream any number of times, `whole()` only below the tree limit. Never a consumable stream, never
  a byte array as the domain type. Objects are keyed by message id + step, never by content hash.
- `Step` is `Envelope apply(Envelope)`; `Stage` is `Envelope apply(Envelope, Pipeline rest)`.
  Both run on the message's virtual thread and may block on I/O through Vert.x clients and
  `Future.await()`. No Mutiny, no reactive stream contract in the pipeline.
- One plain JDK virtual thread per in-flight message, bounded per flow by a semaphore that counts
  requests **and bytes**. Verticles own sockets and timers; they never run a pipeline. Two
  executors only: the virtual-thread executor and the small platform-thread pool for JCo/JNI.
- A stage holds only what it was given at build time. `Runtime` is used inside `FlowBuilder` and
  nowhere else; no stage or lambda captures it.
- All contact with the outside world goes through the Component SPI; nothing else opens a socket.
  Consumer contract is `Function<Envelope, Future<Ack>>`; the durable save before an irreversible
  ACK belongs to the consumer, never to a pipeline stage.
- Every component ships with its mock. Real and mock pass the same contract suite. No component is
  merged without its mock. A deployed environment refuses to load a mock binding.
- State whose loss loses messages goes to Postgres (aggregator, resequencer, idempotent ids, DLQ,
  ordered queues). State whose loss only changes tempo may live in memory or Valkey. Never cached:
  idempotent ids, aggregator state, ordered-queue state, DLQ.
- Bytes exist in the system once; everything else is a reference. Never a synchronous delete on
  the hot path.
- Dependency direction, enforced by ArchUnit: `api` ← `core` ← `runtime`. Components and steps
  depend on `api` only. Only `runtime` touches Postgres, Kafka, S3, Valkey or ClickHouse clients.
  `api` and `core` use the JDK plus Vert.x `Future`, `Buffer`, `ReadStream`/`WriteStream` and
  nothing else from Vert.x. Changing an ArchUnit rule is an architecture change: finding first.
- An interface exists where a second implementation exists on day 1 (every store, every component
  with its mock). Nowhere else. No `domain/application/infrastructure` ceremony.
- Clock, timers and id generation are injected through `Runtime`. No static singletons.
- Dependencies: Apache 2.0, MIT, BSD, MPL 2.0, EPL only. AGPL, GPL, BSL, SSPL → `ISSUE`, never
  linked. SAP JCo is `provided` scope and never redistributed.
- Sizes are `long`. Every flow has a finite max body size. Never default a limit to unlimited.

## Tests (TDD from day 1)

- Red first. A task starts with the failing test that defines its acceptance; the PROGRESS entry
  names it. Then the code. Then cleanup.
- The implementer may not change a test it did not write, except to fix a wrong expectation with
  the reason in the commit message.
- A test asserts observable behaviour. Mocks only at the SPI or store boundary, never of the class
  under test. No `@Disabled`, no skipped cases, no swallowed exceptions, no `assertTrue(true)`, no
  sleep-based timing — a fake clock drives backoff, leases and deadlines.
- Coverage floors (JaCoCo, line and branch): `flottila-api` and `flottila-core` 90 %;
  `flottila-runtime`, components and steps 75 % with the Testcontainers tests counted. No decrease
  on any push. Mutation score (PIT) on `api` and `core` ≥ 80 %. Floors are phase-gate conditions.
- Property tests (jqwik) for the batcher, admission and WorkQueue ordering.
- A blocked platform thread in a test is a failure.
- Benchmarks run natively on Linux and are recorded in `bench/RESULTS.md` with machine, date,
  configuration and commit. A number not in that file does not exist.

## Working rules

- Library APIs: Context7 (`.mcp.json`) first — Vert.x 5.1, Saxon, Kafka clients, Testcontainers.
  If unreachable or ambiguous, `javap -cp <jar>` on the resolved jar in `~/.m2` decides. Never
  guess an API from memory.
- One task = one commit, Conventional Commits, direct to `main`, green before push. Format and
  trailers in `docs/WORKFLOW.md`.
- After every task: PROGRESS.md entry, HANDOFF.md rewritten, BACKLOG.md status updated, review by
  the reviewer agent. A task is `done` only with `review: PASS`.
- Decisions: reversible → `docs/DECISIONS.md`; expensive-to-reverse → design it, mark it
  `draft — review at hand-over`; money, credentials, licences, a missed gate → `issues/ISSUE-<n>.md`
  and continue with the next ready task. Rules in `docs/WORKFLOW.md`.
- When practice contradicts `docs/ARCHITECTURE.md`: write the finding (`docs/findings/`), make the
  smallest change that keeps the documented intent, edit ARCHITECTURE.md with a changelog entry,
  flag the PROGRESS entry "deviation". Never work around the document silently.
- Never: weaken or skip a test to go green; skip a gate measurement; force-push or rewrite
  history; claim a build, test, benchmark or push you did not observe; put a secret in the repo.
- Never ask the owner during autonomous execution. If blocked, write the issue and move on. The
  session ends only on `GATE_MET` or `IDLE_NO_READY_TASK` (see `docs/WORKFLOW.md`).
