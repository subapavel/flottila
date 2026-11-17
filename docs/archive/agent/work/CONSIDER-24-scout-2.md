# CONSIDER-24 — the scout's second pass: what the test code offers for staging the death (2026-11-15)

Written to this file by the architect; the scout writes no file. Tree baa089c9. Paths are from the repository's root.
Only the files named were read; every "not read" is the scout's mark. Nothing was run.

## 1. Core, in memory

- In-memory implementations, all main scope: `platform/core/src/main/java/dev/rail8/core/queue/InMemoryWorkQueue.java:25`
  (constructors `(Clock)` and `(Clock, InMemoryMembership)`),
  `platform/core/src/main/java/dev/rail8/core/record/InMemoryMessageStore.java:25`,
  `platform/testkit/src/main/java/dev/rail8/testkit/InMemoryBlobStore.java`. Not the sim module's `SimStore`.
- The fixture: nested `World` in `platform/core/src/test/java/dev/rail8/core/flow/FlowWorkerTest.java:182`.
  - Parts: `FakeRuntime rt`, `RecordingComponent partner`, `HoldingMessageStore store`, `InMemoryWorkQueue rows`,
    `RecordingWorkQueue queue`, `InMemoryDlq`, `RunGate gate`.
  - Methods: `builder()` :230, `worker(def, id, policy)` :245-264, `accept(Delivery)` :271, `sentIds()` :277,
    `settle()` :282, `drain(worker)` :289, `claimedAttempts()` :305, `wokenAtTheLeaseEnd(worker, id)` :314,
    `close()` :321.
  - Helpers: `policy(lease, maxAttempts)` :140, `flow(maxInFlight, steps)` :145, `until(cond, what)` :173 (a spin wait,
    no sleep).
  - A second `World` with `InMemoryMembership` is in `FlowWorkerFenceTest.java:88`.
- Existing coverage:
  - `FlowWorkerTest.aLeaseNobodyRenewsExpiresAfterOneLease` :401 (takeover, attempts 2).
  - `aStaleCompleteIsAWarningAndLeavesTheRowToItsNewHolder` :759 (STALE complete).
  - `aReclaimedRunRenewsAndCompletesUnderItsNewAttempts` :850.
  - `aCrashLoopCountsAsAttempts` :1128.
  - `aSecondAttemptWritesItsStepBodiesUnderItsAttempt` :1465 (one `messages` row per (step, attempt)).
  - `FlowWorkerFenceTest.aNodeKilledMidSendIsRerunInDoubtWithTheSameIdAndParkedPastMaxAttempts` :271 (doubts 1 then 2,
    the same id, attempts 2 and 3).
  - `FlowWorkerFenceTest.aFencedWorkerCompletesHoldsAndParksNothing` :207 (the node fence at settle).
  - `FlowWorkerCompleteUnkeyedTest.aStaleCompleteUnkeyedIsCountedAsAStaleSettle` :167.
  - `InMemoryWorkQueueTest` :780-911 (doubts).
  - `FlowBuilderSendDeadlineTest:268` (the rerun is in doubt).

## 2. A death in-process

- `FlowWorkerTest.KillableTimer` :159: `kill()` silences the worker's timer tasks, so no more renewals. Used at :405
  and in `FlowWorkerFenceTest` :279-301. `NodeLeaseTest:340` has a similar `kill()`.
- `FlowWorkerTest.Crash extends Error` :121 and the step `crashFirst` :209: an Error leaves the item to its lease. It
  fires on the first run only (no `ATTEMPT` prop).
- `RecordingWorkQueue.java:198` `onComplete(LongConsumer)`: a hook that runs before the delegate on `complete` (:284)
  and `completeAndTakeNext` (:294). It also has `failCompletes(times, cause)` :140.
- `RecordingWorkQueue` does not override `completeUnkeyed`. The default `WorkQueue.java:238` goes through
  `completeRow` (:224). Whether `completeRow` reaches the recording `complete` hook: not read.
- `FlowWorkerCompleteUnkeyedTest.Entries` :72 wraps a queue; `answerNextUnkeyed(Future<Completion>)` :80 stages the
  next `completeUnkeyed` answer.
- `HoldingMessageStore.java:23`: `hold()` :39, `release()` :48, `fail(cause)` :53, `throwNext` :66, `held()` :45,
  `recorded(step)` :84. It holds `record`. `recordWaiting` is the interface default (`MessageStore.java:26`) and
  delegates to `record`.
- A node fence closed, or a worker abandoned without settle: `FlowBuilder.fence(BooleanSupplier)` :249;
  `FlowWorkerFenceTest` closes it after the claim.
- No crash switch exists on the queue between the store write and the settle. The natural seam is the pair
  `onComplete` / `Entries`.
- Signatures:
  - `Future<Completion> completeUnkeyed(long seq, int attempts)` (`WorkQueue.java:238`)
  - `Future<Completion> completeAndTakeNext(long seq, int attempts, String workerId, Duration lease)` (:247)
  - `Future<Void> recordWaiting(Snapshot)` and `Future<Void> record(Snapshot)` in `core/record/MessageStore.java`

## 3. The fake clock and the lease

- `platform/testkit/.../FakeClock.java:18` (`advance(Duration)` :37, `set`, `at`). `FakeTimer.java:18` (`pending()` :40,
  `nextDelay()` :49). `FakeRuntime` has `clock()`, `timer()`, `ids()`, `blobs()`, `runtime()`, `ingress()`.
- Example: `FlowWorkerTest:401` - `kill()` the timer, `rt.clock().advance(LEASE.minusMillis(1))` (still leased), then
  `advance(1 ms)` and the second worker's `poll()` takes the row at attempts 2. `FlowWorkerFenceTest.takeOver` :316
  advances 1 s at a time.

## 4. Runtime and Postgres ITs

- `platform/runtime/src/test/java/dev/rail8/runtime/node/NodeTakeoverLedgerIT.java:78`.
  - Two child JVM nodes against one Postgres (`@Timeout` 15 min).
  - Kill: `a1.process.destroyForcibly()` :181. A SIGSTOP case at :241, `kill -<sig>` at :459.
  - It installs ledger triggers (`TRIGGERS` :87): `ledger_takes` (every hand-out, with `prev_holder`, `doubts`, `at`)
    and `ledger_done` (a completed row's doubts).
  - Reads: `select ... from messages where step_name='partner' and outcome='ok'` :204. It does not read (id, step,
    attempt) per attempt, and never reads `work` rows beyond counts.
- The Postgres fixture: each IT declares its own `static final PostgreSQLContainer pg = new
  PostgreSQLContainer(PostgresImage.name())`; `PostgresImage` is `platform/testkit/.../PostgresImage.java:26`. No shared
  base class. `NodeTakeoverLedgerIT` creates a database per case (:129-137).
- `PgWorkQueueBatchedCompletionIT`: `shareLock()` :234 (`lock table work in share mode`) and `rowLock(seq)` :239 stall
  the DELETE of work; `complete(item)` :245 calls `completeUnkeyed`. A staging seam for the delete in Postgres.
- An in-process worker on Postgres: `runtime/flow/DeliveryModesIT.java:147-168` (`PgMessageStore` + `PgWorkQueue` +
  `builder.worker`); `TwoNodeWorkEventsIT.java:171` (two `EventNode`s). Neither read for rerun or attempt cases.

## 5. What a partner received

- `platform/testkit/.../RecordingComponent.java`: `sent()` :75, `sentTo(name)` :78, `holdNext(n)` :108, `awaitSent(n)`
  :129, `failNext` :90, `replyWith` :84. It returns Envelopes, so a test reads the id from `e.id()`. Example:
  `FlowWorkerFenceTest:300` `assertEquals(List.of(in.id(), in.id()), w.sentIds())`.
- `components/mock/.../MockComponent.java:160-162`: `sent()`, `sentTo(endpoint)`.
- The real HTTP header `x-message-id`: `platform/components/http/src/test/.../HttpComponentTest.java:349-366`.
  `EnvelopeOutboundIdTest` is in api.

## 6. Bench

- The bench is at `bench/`, not under `platform/`: `bench/src/main/java/dev/rail8/bench/LedgerMain.java` (`main` :129,
  `run(args)`), `bench/src/test/java/dev/rail8/bench/LedgerIT.java:66` (tests :103 and :193).
- Launch: node 1 runs as a child JVM and is SIGKILLed after `--kill-after` ACKs; the record is written as pretty JSON
  (:312) to `--out`.
- In `FlowWorker.java` a grep for histogram, trail and settle found no timer or histogram around `trail.close()` or
  settle; only `Timer.Cancellable` fields matched. The bench sources were not searched for timers.
- `bench/RESULTS.md`, the latest entry: :3 "## CORE-17 indicative ... cloud VM, 4 vCPU @ 2.10 GHz (NOT CORE-GATE)",
  :5 "Date 2026-11-11. Same host and PostgreSQL 16.15 cluster ...". Fields: date, host and PG, settings, trees and
  commits, runs, a table (:13-14). The gate's entry: :415 "Hand-over gate (GATE) - commit fea6c5c".

## 7. The worker's construction

- `FlowWorker.java:172` (package-private): `FlowWorker(String flow, String workerId, int max, WorkerPolicy policy,
  RetryPolicy retries, WorkQueue queue, MessageStore store, BlobStore blobs, Pipeline pipeline, Parking parking,
  StepConfig firstStep, Clock clock, Timer timer, Producers producers, BooleanSupplier fence, Workers workers,
  WorkSignals signals)`.
- Tests build it through `FlowBuilder.worker(FlowDefinition flow, Path baseDir, String workerId, WorkerPolicy policy)`
  (`FlowBuilder.java:379`); the builder is set up with `.recording(store, Meter)`, `.queue(q)`, `.dlq(d)`, `.fence(bs)`
  and `.retries(RetryPolicy)`.
- `WorkerPolicy(lease, tick)`; the default lease is 30 s (`WorkerPolicy.java:21`).

## 8. Documents

- `docs/DECISIONS.md:143` D-135: retries on a schedule, then park, at-least-once only ("Each attempt reruns from the
  ingress"). The full text was not read.
- `docs/DECISIONS.md:148` D-140: each attempt keeps its snapshots; `messages` keyed (id, step, attempt) (V19). The full
  text was not read.
- `docs/ARCHITECTURE.md` section 5 (:260-297), line 296: every producer sends the message id as `x-message-id` "so the
  partner can dedupe a rerun". Section 6 (:298-328), line 327: at-least-once keeps the arrived body in the journal.
  The delivery-mode section proper was not located.
