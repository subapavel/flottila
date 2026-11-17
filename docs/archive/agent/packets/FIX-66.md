# FIX-66 — trail rows wait for room in the writer instead of being dropped (F-13, D-071)

Class C; designer draft approved by the architect 2026-10-28 with the changes marked [A].

## Acceptance
- `BatchingWriter.saveWaiting(T): Future<Void>`: a full backlog makes the item wait FIFO; it enters the moment a slot frees
  (top-up inside `drainLocked`, under the lock). Invariant `waiting() > 0 ⇒ backlog() == maxBacklog`, so `save` (unchanged,
  fail-fast) never overtakes a waiter. One `timer.schedule` per waiter, cancelled on entry; still waiting at `maxWait` → fails
  `OverloadedException`, never handed to the sink. A timer that refuses to schedule fails the save at once. `close()` refuses
  new `saveWaiting` (ISE); existing waiters keep entering and `flush()` waits for them. `int waiting()`.
- `Options` gains a 5th component `Duration maxWait` (positive); the 4-arg constructor and `defaults()` use
  `DEFAULT_MAX_WAIT` = **PT10S** [A: not 30 s — equal to the default FLOTTILA_STOP_DEADLINE so a stopping flow is not held past it].
- `MessageStore.recordWaiting(Snapshot)` default → `record(s)`; `PgMessageStore` overrides with
  `writer.saveWaiting(s)` counting a failure in `failed` (`snapshotWriteFailures`).
- `Trail.write` calls `recordWaiting`. Any failure (ran out, failed commit, closed, throw) logs one WARNING per row:
  `message <id> of flow <flow>: the snapshot of step <n> (<name>) was not recorded (<cause>)[; its body <key> stays durable
  without a row]`, with the throwable. Close still never fails; a failed commit is not retried.
- The at-least-once ingress keeps `store.record` (fail-fast → retryable Nack). Delivery's code does not change.
- Sizing: documentation only (D-071); backlog stays 6400.

## Red tests (test-author)
- BatchingWriterTest: aWaitingSaveEntersWhenACommitFreesRoomAndCommitsOnce · saveStillFailsAtOnceAndNeverOvertakesAWaiter ·
  waitersEnterInArrivalOrder · aWaiterStillWaitingAtMaxWaitFailsOverloadedAndNeverReachesTheSink (FakeTimer: pending at
  maxWait−1 ms, failed at maxWait, sink never sees it after room frees) · aWaiterThatEnteredBeforeMaxWaitIsNotFailedByItsTimer ·
  closeRefusesNewWaitersYetFlushCompletesOnlyAfterTheWaitingCommitted · optionsRefuseAZeroOrNegativeMaxWaitAndTheFourArgFormIs10s
- BatchingWriterPropertyTest.noLossNoDuplicateNoOvertakeWithWaitingSaves (jqwik): any mix of save, saveWaiting, commit success or
  failure, clock advance — each item reaches the sink at most once; a waiter fails only after ≥ maxWait and is never handed on
  after; waiters hand on in arrival order; the invariant holds after every step.
- TrailTest: closeWritesEveryRowThroughRecordWaitingAndWaitsForIt · aRowNotWrittenLogsOneWarningNamingMessageFlowStepAndCause
  (fails Overloaded / fails / throws; close completes) · aWrittenRowLogsNoWarning
- DeliveryTest: aSyncPermitIsHeldWhileItsRowsWaitSoTheNextRequestIsARetryableNack (maxInFlight 1) ·
  anOverloadedAtLeastOnceIngressRecordIsARetryableNackAtOnce (before any timer advance; recordWaiting never called)
- Contract (InMemoryMessageStoreTest + PgMessageStoreIT) recordWaitingPastTheBacklogLandsEveryRow: Options(2, ZERO, 1, 2, PT1M);
  50 concurrent calls all land, failed()==0; record() past a full backlog fails Overloaded and counts.
- DeliveryModesIT.aSyncKeepFlowPastTheWriterBacklogLosesNoRowOfAnAnsweredMessage: backlog 2, maxInFlight 32, 200 requests from
  16 virtual threads; every Reply id has ingress + partner rows; every other answer a retryable Nack with no row; failed()==0;
  no Trail WARNING.

## Allowed paths (implementer)
flottila-core/src/main/java/io/flottila/core/batch/BatchingWriter.java, …/batch/OverloadedException.java (javadoc),
…/core/record/MessageStore.java, …/core/record/Trail.java, flottila-runtime/src/main/java/io/flottila/runtime/store/PgMessageStore.java.

## Mutants for the reviewer
top-up dropped; top-up deferred to the next commit; deadline not cancelled on entry; deadline fails without removing the waiter;
LIFO; flush ignores waiters; close fails waiters; `>` vs `>=` at maxWait; Trail calls record(); WARNING dropped / at INFO /
without the step; recordWaiting not counted; ingress switched to recordWaiting.

## Docs (architect, in the commit)
§8 :263 known-gap sentence replaced by: "A run's trail rows that find the node writer's bounded backlog full wait for room in
arrival order, holding the run's permit, so a writer that cannot keep up shows as admission refusals (503), not lost rows; a row
still waiting after the writer's `maxWait` (10 s), or whose commit fails, is logged at WARNING with its message id, flow, step and
cause and counted in `snapshotWriteFailures`; the at-least-once ingress row alone still fails at once on a full backlog, a
retryable Nack (F-13, D-071)." Changelog :28 row replaced accordingly [A: "F-13 mitigated", closed only when FIX-67's c=64 keep
rerun shows the counter at 0 — the 3 800 lost rows' cause (overload vs. failed commits, 3800 = 38 × batch 100) is not established].

## Rejected (D-071)
retry with backoff in Trail; `save` always waits; a count bound on waiters; bigger backlog or a load-time check; release the
permit at reply (breaks F-10); retrying failed commits.
