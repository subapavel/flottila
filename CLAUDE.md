# Flottila — rules for Claude

Read before any work: this file, `AGENTS.md`, `agent/HANDOFF.md` and the task's row in
`agent/BACKLOG.md`. `docs/WORKFLOW.md` §1 is the loop. `docs/ARCHITECTURE.md` (binding design) is
read by section — the sections the row or the packet names, through the scout — never whole;
`docs/PROTOTYPE-VS-ARCHITECTURE.md`, `docs/GATE.md` and `docs/DECISIONS.md` only where a row or a
packet refers to them. Everything in the repo is in English.

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

- The main session is the architect (`AGENTS.md`): it writes the acceptance spec and the task
  packet, approves the red tests, decides, commits. It never edits `src/`, never writes a test,
  never runs Maven — the test-author, implementer, verifier and reviewer do, on Opus 5.5 and
  Sonnet 5.5. Fable 5.1 is the architect and the gate reviewer, nothing else.
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

## Context hygiene

- The conversation is not memory. Before each task re-read `agent/HANDOFF.md` and the task's
  BACKLOG row. After a context compaction, treat everything older than the last PROGRESS entry as
  unknown and re-read the files; say "context compacted" in one line of the next PROGRESS entry
  and re-verify with `git status` and `git log -3` before changing anything.
- Keep the context small: `mvn -B -ntp -q` by default; send build and test output to a file under
  `target/` and read only its tail or the failing section; never cat a file longer than ~200
  lines — use offsets, grep, or the scout; never paste a diff you do not need to reason about.
- Large reads (surveying modules, finding call sites, reading logs) go to the scout agent; its
  summary comes back, not the files.
- From a delegated task only the handback (at most 40 lines) enters the architect's context. The
  diff goes to `target/<task>.diff` for the reviewer; the architect reads `git diff --stat` and
  the test files it approves, never the whole patch.
- `agent/HANDOFF.md` is at most 20 lines of at most 160 characters: state, last commit, next exact
  action, open issues, pointers. Module notes live in `agent/NOTES.md`, read by section on demand.
  A PROGRESS entry is at most 15 lines; the review-round narrative goes to the commit body.
- One task per context episode: finish, commit, push, rewrite HANDOFF, then start the next. Never
  carry an in-flight task across a compaction without a WIP line in `agent/HANDOFF.md`.
- Turn economy (`docs/WORKFLOW.md` §10, D-053): wait for a single subagent in the foreground;
  `touch target/.batch-in-review` when a batch goes to the reviewer and `rm -f` it in the commit
  command; a Stop-hook reminder mid-task is declined in one line; a fix packet names file, line,
  evidence and sentence, nothing else.

## Compact instructions

When compacting, keep: the HANDOFF state line and next exact action, the id and status of the
task in flight, the acceptance spec and the task packet as written, the names of the red tests
and whether their red was observed, every evidence line (`VERIFIED_PASS` / `EXECUTED_FAIL` with
its log path), the reviewer's last verdict with its reasons, open decisions and findings by id.
Drop: tool output, build logs, diffs, file contents already committed, and the handbacks of
finished agents beyond their last line. After compaction re-read `agent/HANDOFF.md` and run
`git status` and `git log -3` before changing anything.
