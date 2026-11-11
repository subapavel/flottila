# CORE-7 — batched completion of unkeyed work items (class C; D-122 draft, D-101, D-116)

Architect 2026-11-11. Base: f47df4f (P2-16b). Design: designer draft 2026-11-11, approved with the decisions below.

## Decision (draft — review at hand-over, D-122)
C1 One completion batcher per node over the existing core BatchingWriter, for UNKEYED rows only. core `WorkQueue.completeUnkeyed(long
   seq, int attempts)` default → completeRow; FlowWorker.settle calls it when `item.partitionKey() == null`, completeRow otherwise;
   fence check, stale count, leftToLease on failure and announce unchanged. Keyed rows and the fast path keep their statements under
   the key lock (EOIO serial per key; a batch would hold every member's key lock; multi-key claim = FIX-130 plan risk).
C2 PgWorkQueue(Pool, String node, TieredBlobStore, Timer) wraps the batcher; 1–3-arg constructors keep the single statement.
   COMPLETE_BATCH, autocommit: `delete from work w using unnest($1::bigint[], $2::int[]) c(seq, attempts) where w.seq = c.seq and
   w.attempts = c.attempts and w.attempts > 0 and w.held_at is null and w.partition_key is null returning w.seq, w.attempts, w.id`
   (plus the `seq = any($1)` probe if the generic plan needs it — EXPLAIN (GENERIC_PLAN) on the CI PG decides; array binding of
   Long[]/Integer[] checked with javap / Context7). Returned → forget the body, Completion.done(empty); absent → STALE; statement
   failure → every member fails with the cause (each left to its lease), no retry (a retry would misreport a stale member as done).
   `completions()` record(statements, members, stale, refused); `close()` refuses new members and flushes; Node.close calls it after
   the flows, before the signals and the pool.
C3 Bounds (constants, no env keys until CORE-GATE): ≤ 256 members per statement; window PT0S (sent at once when no batch is in flight,
   else gathered behind it, no timer); 2 in flight; backlog 8192, a member waits ≤ PT5S for room, then fails, is counted as refused
   and is left to its lease. The settle waits for its batch's answer: the item keeps its room until then.
C4 Fence: checked in FlowWorker before hand-over (unchanged); the batch is fenced per row by its attempts token. A completion handed
   over while the fence was open may commit after the fence closes, at most one in-flight batch (+ ≤ 5 s on overflow) later — safe
   by the token (a TAKE bumps attempts). D-101 (2) and ARCHITECTURE §4 are amended to say so (deviation, finding F-31). Crash with an
   unflushed batch: rows stay leased, the TAKE counts a doubt and an attempt, the item reruns (D-112/D-113), no new state.
C5 BACKLOG done-when is met as "one statement for N unkeyed completions"; a row is still deleted per message (finding F-31 says so).
   CLAUDE.md "never a synchronous delete on the hot path" concerns bytes (blob deletes) — unaffected.
Rejected: done marker (a write + a sweep, every predicate gains `done_at is null`); partitions (per-row write anyway, a held row pins
a partition); batching keyed rows / the fast path; `locked_by = $node` predicate (attempts is the token); settle not waiting;
synchronous_commit off; per-flow batcher; piggybacking on the intake group.

## Red tests (test-author)
IT (Testcontainers, counting Pool decorator): nCompletionsGatheredBehindABatchInFlightAreOneDelete (acceptance: a session holds
`lock table work in share mode`, N=100 gather, release → exactly 2 DELETEs, all done, rows gone); anIdleCompletionIsSentAtOnce;
aBatchHoldsAtMostMaxMembers (2·256+1 → 3 statements); aStaleHeldOrUnknownMemberAnswersStaleAndItsNeighboursComplete;
aKeyedRowIsNeverDeletedByCompleteUnkeyed; aFailedBatchFailsEveryMemberAndEachRerunsAfterItsLease; aBatchLandingAfterATakeover
DeletesNothing; aFullBacklogWaitsAtMostMaxWaitThenLeavesTheItem (fake timer, refused counted); nodeCloseDrainsPendingCompletions
BeforeThePool. Core: anUnkeyedItemSettlesByCompleteUnkeyedAKeyedOneByCompleteRow (fast path unchanged). Contract: completeUnkeyed in
the WorkQueue suite (InMemory + Pg). Property (jqwik on Testcontainers, bounded tries): batchedCompletionsMatchTheSingleRowModel.
Ledger, lease/fence, key-lock and fast-path tests run unchanged.

## Checks
Reactor verify -Pit (no bench) + BenchMainIT, fresh XML; coverage --baseline-ref HEAD; scoped PIT (FlowWorker, WorkQueue,
BatchingWriter if touched); hand mutants: drop the attempts check, drop the keyed guard, drop held_at, answer absent as done, retry
on failure, no flush on close — restored by cp + touch + cmp.

## Red approved (architect 2026-11-11)
FlowWorkerCompleteUnkeyedTest (4), PgWorkQueueBatchedCompletionIT (9; PgWire DELETE count + statement-level AFTER DELETE trigger
sizes, BEFORE DELETE trigger refusing `poison%` = a real statement failure), PgWorkQueueBatchedCompletionPropertyIT (15 tries),
WorkQueueContract.completeUnkeyedCompletesOnlyTheCurrentHoldersUnheldRow + PgBatchedWorkQueueContractIT, JournalBlobStoreIT
.anUnkeyedItemCompletedByTheBatchLeavesNoIndexEntryToReadItsBodyBy, NodeShutdownWaitsForWorkerSettlementIT.nodeCloseDrainsPending
CompletionsBeforeThePool. Red: compile (agent/work/CORE-7-red.log), semantic vs a today-like shim (acceptance 101 ≠ 2, -red-queue.log).
Signatures approved: PgWorkQueue(Pool, String, TieredBlobStore (nullable), Timer); batched completeUnkeyed answers STALE for a keyed
row; completions() accessors statements/members/stale/refused (members = sent; stale includes keyed/unknown; refused = maxWait
expiries); `Future<Void> close()` completes once every handed-over member is answered, later members fail. No existing test changed.
