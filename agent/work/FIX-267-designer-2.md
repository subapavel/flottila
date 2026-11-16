# FIX-267 - the designer's second pass (2026-11-16), as delivered; wrapped, not reworded

Asked after the advisor's opposition (`agent/work/FIX-267-advisor.md`): its changes 4, 5 and 6 alter the shape. The
other changes were taken by the architect before this pass (the message that asked for it lists them).

Proposal: give `/retry` the same unsettled guard as cancel; keep the scan probe with its bounds rather than storing the
work seq on the DLQ row; and put retry, cancel and events in one new core type, `FailedMessages`, with a Postgres and
an in-memory implementation that pass one contract. Nothing written, nothing run; every code fact was read on disk.

## A. The guard on `/retry` (change 4)

It is the first row, on its own: it fixes an existing defect and needs a finding.

- **Scenario for the test-author** (seams as in `DlqRestartIT`: `PgWorkQueue` and `PgDlq` on one pool, a
  `RecordingComponent` partner, `FakeRuntime`):
  1. Flow `orders`, at-least-once, `delivery.retries` count 1, so the bound is 2. The partner answers attempt 1 with a
     final NACK, so the worker parks M at attempt 1 (FlowWorker.java:687-697).
  2. A queue decorator at the store boundary fails that item's settle once (FlowWorker.java:745). The work row W1 stays
     leased to its node.
  3. `restart(M, orders, null, 1)` answers true today. The DLQ row is gone and M now has two work rows, W1 and W2. The
     red test asserts Unsettled(W1), the DLQ row kept and one work row.
  4. Today's continuation: the partner now accepts, so W2 delivers M. Then `expireLeases()` makes W1 claimable; its
     attempt 2 is within the bound (:637), it runs the pipeline and delivers M a second time.
  5. Variant with count 0 (bound 1): W1 is attempt 2, past its bound, so it re-parks unsent (:648-654) against no
     conflict (PgDlq.java:61-62). M is delivered and back in the failed list.
- **Where the guard sits.** RESTART (PgWorkQueue.java:290-301) gets a CTE `w` right after `d`. It reads seq,
  body_node, locked_by, locked_until, held_at and lost_at of a work row with `id=$1` and `flow=$2`, plus
  `partition_key=$3` when keyed; that probe runs under the key lock already taken at :605. `gone` deletes only when
  `not exists (select 1 from w)`, and `ins` and `aud` are built from it, so a refused retry writes nothing.
- **How the answer is read.** No `d` row means NotParked. A `w` row means Unsettled. One inserted row means Done.
  Otherwise the ingress ref is missing and the failure is as today (:608-612).
- **No existing test pins the old behaviour.** Each asserts no work row of the id before the restart:
  DlqRestartIT:176, FlowWorkerRetryTest:672, WorkQueueContract:260-305, PgWorkQueueRestartIT:91-189. What changes is
  the answer's shape (C).

## B. The probe: (i), the scan with the advisor's bounds

- **The bounds.**
  - `set local statement_timeout` as the act transaction's first statement. Key `RAIL8_ADMIN_ACT_TIMEOUT`,
    PT0.1S..PT1M, default PT5S, refused at start naming it.
  - On expiry (57014) the act fails typed and the route answers 503, nothing written. The timeout also bounds a lock
    wait.
  - The keyed probe uses `work_flow_key_seq` (V15:5) with the DLQ row's key (V17:8); the unkeyed one uses
    `work_flow_seq` (V1:62).
  - A plan IT, and a number at 1M rows in `bench/RESULTS.md`.
- **Why not (ii).** (ii) holds but buys too little:
  - The invariant is true for every writer of `work`: intake inserts are fresh ids (PgWorkQueue.java:117-142); RESTART
    deletes the DLQ row, and W2's own park writes W2's seq from its item (FlowWorker.java:697), so a re-park names it
    without RESTART knowing anything; a keyed same-key take, a defer, a hold, a release and a takeover all keep the
    row's seq; discard only deletes; Split mints no id (the advisor's grep; not checked).
  - The cost: a `dlq.work_seq` column; a component on `Parked`, a public core record (Parked.java:31-32) with every
    full-constructor call site; a `Parking.park` overload (Parking.java:85); `PgDlq` COLUMNS, PARK and `parked()`; the
    in-memory DLQ; and a probe with two paths.
  - It never removes (i): old rows and `FlowBuilder.build()`'s per-step parks (FlowBuilder.java:500-502) carry no seq
    and still need the scan, timeout and plan IT.
  - (ii) is additive later, with (i) as its fallback. Rule: if the unkeyed 1M-row probe exceeds the default timeout,
    (ii) becomes a row.

## C. Where the API lives: option (3)

- **(1) rejected.** `InMemoryDlq` would need the in-memory work rows to answer Unsettled, and `InMemoryWorkQueue`
  would need the DLQ to remove the row on retry. That is a two-way coupling, and the events would still be split across
  two interfaces.
- **(2) rejected.** It adds an operator concern to the worker's interface. The in-memory queue would need a DLQ and an
  event log. About 20 decorators would need a `cancel` override.
- **(3) chosen.** `dev.rail8.core.dlq.FailedMessages`, an interface (two implementations on day 1):
  - `Future<Act> retry(String id, String flow, String key, int attempt, Actor by)`
  - `Future<Act> cancel(String id, Actor by)`, which needs `by.reason()` present
  - `Future<List<Event>> events(String id)`
- **Types:**
  - `record Actor(String name, Optional<String> reason, String remote)`.
  - `sealed Act`: `Done(Event)`; `Unsettled(long seq, String node, WorkState state, Optional<Instant> leasedUntil)`
    with `WorkState` QUEUED, LEASED, HELD or LOST, and the node is the body's node because only that node claims the
    row (PgWorkQueue.java:128); `NotParked(Optional<Event> last)`, where `last` is read by a statement after the act's
    (change 2).
  - `record Event(long id, Instant at, String messageId, String flow, String actor, Kind kind, Optional<String>
    reason, OptionalInt attempt)`, `Kind` RETRY or CANCEL.
- **Postgres: `runtime.queue.PgFailedMessages(Pool, PgWorkQueue, Duration actTimeout)`.** It holds RESTART and
  `restart()`, moved from PgWorkQueue.java:280-301 and :595-615. It calls `lockKeys`, which is already public (:389).
  It also holds CANCEL and the events read.
- **In memory: `core.dlq.InMemoryFailedMessages(InMemoryDlq, InMemoryWorkQueue, Clock)`.**
  - It is synchronized on itself and keeps its own event list.
  - It uses a package-private `InMemoryDlq.take(id)`, which replaces `remove` (InMemoryDlq.java:62-67).
  - It uses new public `InMemoryWorkQueue.row(id)` and `append(...)`; `append` is today's `restart` body
    (InMemoryWorkQueue.java:123-130), no longer an override.
  - Its own lock is enough: once the act has checked that no work row of the id exists, none can appear except through
    `retry`, which takes the same lock.
- **The contract.** `FailedMessagesContract` goes in `platform/runtime/src/test/java/dev/rail8/runtime/queue/`, where
  WorkQueueContract, IntakeContract and MembershipContract already live; testkit holds only api-level contracts. It is
  run by `InMemoryFailedMessagesContractTest` and `PgFailedMessagesContractIT`. Core also needs an
  `InMemoryFailedMessagesTest` for its 90 % and PIT floors.
- **What moves:**
  - `WorkQueue.restart` and its Javadoc leave the interface (WorkQueue.java:268-281).
  - The overrides of `restart` go from WorkQueueEnqueueLeasedDefaultTest:30, FlowWorkerHandoffEdgeTest:338,
    FlowWorkerLifecycleTest:85, FlowWorkerCompleteUnkeyedTest:108, RecordingWorkQueue:306,
    FlowWorkerRerunWindowTest:151, FlowWorkerNextNodeTest:71, FlowWorkerFastPathTest:210, IntakeContract:273,
    IdleNodeStatementsIT:145, DeploymentsRedeployEdgeTest:106, CountingQueue:131, AdminReleaseWakesTest:124,
    PgIntakeHandoffIT:146, PgWorkSignalsIT:175, AdminServerWorkTest:352, sim ClaimCap.java:53-55 (main code),
    OverClaim:38, SimHarnessTest:187 and SimSchedulerTest:213 and :266.
  - WorkQueueContract's restart cases (:253-305, and its `parked` hook at :60) move to the new contract.
  - DlqRestartIT and PgWorkQueueRestartIT retarget to `PgFailedMessages`.
  - AdminDlqRestartTest's `restartAnswer` seam (:97-98, :170-183, :300-304) becomes a `FailedMessages` decorator.
  - FlowWorkerRetryTest:663-673 and FlowWorkerTest:1001 use `InMemoryFailedMessages` instead of `Dlq.remove`.
  - AdminServer takes a `FailedMessages` for both acts.
  - Who edits tests it did not write is the test-author's question, for the packet.

## Rows, in order

- **267a [B], after FIX-265.** Scope: the new type with `retry` only, the guard, the probe's bounds. FIX-265 edits the
  same statement (:292) and WorkQueue.java:274.
  - `FailedMessagesContract.aRetryWhileAWorkRowOfTheIdIsUnsettledAnswersUnsettledAndWritesNothing`: the defect, red on
    both twins.
  - `DlqRestartIT.aRetryBeforeTheParksSettleLandedIsRefused`: steps 1-3 of the scenario.
  - The moved cases: `aRetryOfAWorkersParkAppendsOneRow...`, `aKeyedRetryJoinsTheTailOfItsKey`.
  - `PgFailedMessagesIT.anActPastItsTimeoutAnswersTimedOutAndWritesNothing`: the DLQ row lock is held in another
    transaction until the act fails.
  - `PgFailedMessagesPlanIT.theKeyedProbeUsesWorkFlowKeySeq`.
  - Evidence: the 1M-row number.
- **267b [B], after 267a (FIX-264 is done by then).** Scope: `cancel`, `events`, the audit row of both acts in their
  own statements, the audit index (numbered at implementation), `Dlq.remove` out.
  - `...aCancelEndsTheParkedRowAndRecordsOneCancelEvent`.
  - `...aCancelSucceedsForEachRowARetryRefuses`, parameterised: step other than 1, no attempt and a keyed row without
    its key - on both twins; a lost or gone ingress ref - Postgres only; the two deployment cases are 267c's.
  - `...aRepeatedCancelOrARetryAfterACancelAnswersNotParkedWithTheCancel`.
  - `...aCancelAfterARetryAnswersNotParkedWithTheRetry`.
  - `...eventsListBothActsOldestFirstWithFlowActorReasonAttempt`.
  - `PgFailedMessagesIT.cancelAndRetryOnOneRowOneWinsAndTheLoserNamesTheWinner`, waiting on `pg_stat_activity` as
    PgWorkQueueRestartIT:157-158 does.
  - `...aRefusedAuditInsertLeavesTheDlqRowAndNoWorkRow`, with a trigger as in DlqRestartIT:181-186.
  - `RetentionAfterCancelIT.aCancelledMessagePastTheCutoffLosesItsRowsBodiesRefsAndKey`: the audit row stays.
  - Property, jqwik on the in-memory twin: whatever the interleaving of a park through a live row, a settle, a retry
    and a cancel, a cancelled id never has a DLQ or work row again, and every Done records exactly one event.
- **267c [B], after 267b.** Scope: the admin routes. javap `BodyHandler` first: it would be the admin server's first
  buffered body.
  - `AdminServerCancelTest.cancelledMessageLeavesTheListAndRetryAnswers410NamingIt`.
  - `...cancelWithoutActorOrReasonIs400`.
  - `...unsettledIs409WithSeqNodeAndState`.
  - `...cancelAfterRetryIs409NamingTheRetry`.
  - `...cancelSucceedsForAFlowRedeployedSyncAndForAFlowNotDeployed`.
  - `...theTimelineListsBothEventsInOrder`.
- **None of the three can run before FIX-264 and FIX-265.** 267a could run first only if FIX-265's paths were
  rewritten to the moved statement; not recommended.

## Conflicts

- `set local statement_timeout` is an exception to ARCHITECTURE section 4's "every session carries
  `statement_timeout=0`" (line 252; Node.java:405) and must be named there. The commit's wait for its standby is not
  cut by it, per the same line.
- Moving `restart` changes WorkQueueContract's Javadoc (:33-35).
- Whether ArchUnit allows a dependency between the `core.dlq` and `core.queue` packages is unverified.

## Not read

The ArchUnit rules; PgIntake; Split; FlowWorker's settle decorator seams; the whole of AdminDlqRestartTest;
`PgWorkQueueClaimPlanIT`; any jar. Postgres's `SET LOCAL` and lock-wait semantics are from memory.
