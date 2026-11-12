# CORE-14 — retries on a schedule, then park (class C; split 14a / 14b)

Row: agent/BACKLOG.md CORE-14. Brief Q-7, O-9; D-101 (4) per D-103; amends D-045, supersedes D-055's maxAttempts.
Designer draft accepted (2026-11-12) with the architect's choices below. D-135, draft — review at hand-over (keys, env, route, V17).

## Decision (designer draft, accepted)
P1 Config (at-least-once only; refused at load in sync / at-most-once naming the key): `delivery.retries {count 0..100,
   interval PT1S..PT24H, multiplier ≥1 default 1, maxInterval interval..PT24H default interval}` → existing RetryPolicy;
   wait before retry k = delayBefore(k). Omitted → node default FLOTTILA_RETRIES_COUNT (default 3, 0..100) and
   FLOTTILA_RETRIES_INTERVAL (default PT5M, PT1S..PT24H) from NodeConfig through the runtime into FlowBuilder.
   WorkerPolicy.maxAttempts goes; bound = count+1 hand-outs, doubts included. In-thread `retry` / `retryable` unchanged.
P2 A failed attempt = any worker-run failure except FencedException (unsettled) and an interrupt (lease). First Fault in the
   cause chain of kind MALFORMED / INVALID, or NackException retryable()==false → park at once.
P3 `WorkQueue.defer(seq, attempts, Duration) → Future<Boolean>` under the token (CURRENT): available_at = DB now() + delay
   (InMemory: its injected Clock), holder cleared; seq, id, attempts, doubts, body ref kept; false = stale. Verdict.defer
   checks the fence first; a failed defer is left to its lease.
P4 Wake: one Timer task at the earliest due this worker set (sorted set ≤ 1024; overflow → reconciliation claim).
P5 In doubt: a takeover hand-out counts as attempt k and runs at once; if it fails it defers by delayBefore(k).
P6 Park after the bound or park-at-once: the worker pipeline is built without the per-step Parking wrapper; the ingress
   envelope parks at step 1 with prop `attempt`. build() and the replay build keep Parking.
P7 Pin: a deferred row keeps body_* → PgSegmentRows.PINNED pins it; regression test only.
P8 (14b) `POST /admin/dlq/{id}/restart` → 202 {id, attempt}: one transaction deletes the DLQ row and inserts a work row,
   same id, step 1, max_attempts = k+1 (V17 `work.max_attempts int`, null = flow bound); 404 / 422. `/retry` stays the new copy.
## Architect's choices on the open points
O1 Defaults 3 × PT5M (draft, owner reviews at hand-over). ITs that expect an immediate DLQ row set `count: 0` or a fake clock.
O2 O-9 conflict: rerun from ingress resends earlier `to` steps (as D-101's in-doubt rerun already does) — documented in D-135
   and §4; resume-from-step filed as CONSIDER-13, not built.
O3 In doubt: run at takeover (P5). O4 No index in V17; claim cost measured at CORE-GATE (note in D-135).
O5 FIX-138 stays its own row, reduced to the `/retry` vs `/restart` naming after 14b.
O6 "on any node" = any node allowed to claim the row (until CORE-5 a node claims only rows whose body its journal holds).
## Split
CORE-14a: P1–P7 (core + PgWorkQueue.defer + NodeConfig + schema), docs §4/§6, D-135, changelog 3.15.
CORE-14b: P8 (V17, restart route, Item.maxAttempts), its contract/IT tests.

## Acceptance CORE-14a (red first, test-author)
Core (InMemoryWorkQueue + FakeClock + FakeTimer, FlowBuilder.worker, mock producer), FlowWorkerRetryTest:
retryRunsAtItsTimeNotBefore; backoffFollowsTheSchedule (1m×2 cap 3m → sends at 0, 1m, 3m, 6m); parkedAfterTheCount (count 2 →
3 sends, DLQ holds the ingress at step 1, row gone, nothing after +1d); noThreadWaitsAcrossAnInterval (permits free, nothing
running between attempts); anotherNodeRunsTheRetry; inDoubtCountsAsAnAttempt; fencedFailureIsNotDeferred;
invalidAndFinalNackParkAtOnce. jqwik RetryScheduleProperties.attemptKNeverBeforeItsSummedDelays.
WorkQueueContract (InMemory + Pg ITs; hook passTime(d)): deferredNotClaimedBeforeItsTime, deferredHeadsItsKey,
staleDeferMatchesNothing, deferredHandOutCountsNoDoubt. PgWorkQueueDeferIT.notBeforeOnTheDatabaseClock.
JournalSweepIT.aDeferredItemAlonePinsItsSegment. FlowDefinitionTest/schema: omitted → node default; refused outside
at-least-once; bounds named by key. NodeConfigTest retries defaults and bounds. Existing tests relying on maxAttempts / an
immediate DLQ: the test-author lists them; changes only to fix an expectation the decision changes, reason per test.

## Red approved 14a (architect, 2026-11-12)
API: `Future<Boolean> WorkQueue.defer(long seq, int attempts, Duration delay)` (abstract; every WorkQueue fixture implements it);
`WorkerPolicy(Duration lease, Duration pollInterval)`; `FlowBuilder.retries(RetryPolicy nodeDefault)` (default 3 × PT5M, ×1);
`Optional<RetryPolicy> FlowDefinition.retries()` (count, interval required; flow-file durations `1m`, `30s`); `NodeConfig.retries()`;
DLQ row prop `attempt` (1 on a park-at-once).
Tests: core FlowWorkerRetryTest :230–:562 (15 cases), RetryScheduleProperties:40, FlowDefinitionRetriesTest :52–:119; runtime
WorkQueueContract :159/:175/:191/:217, PgWorkQueueDeferIT :103/:124, SegmentSweepIT:672, DeploymentsRetriesTest :95/:108,
NodeConfigRetriesTest :33/:39/:66; schemas FlowRetriesSchemaTest :32/:52. Red: CORE-14a-red-{core,runtime,schemas}.log
(compile red core/runtime; schemas 7/11 fail). Accepted unpinned: P4's 1024 bound; DB now() vs host clock.
Existing tests (implementer authorised, reason per test in the commit body — the decision changes these expectations):
(a) compile: the 29 files on `WorkerPolicy(lease, N, poll)` → the 2-arg form, the N moving to `delivery.retries.count` N−1 /
FlowBuilder.retries where the test depends on the bound; (b) exhaustion/in-doubt park cases: bound = count+1; (c) tests expecting an
immediate DLQ row at the failing step: `count: 0` and the row is the ingress at step 1 with prop `attempt` (P6);
(d) the 13 WorkQueue fixtures gain defer. ParkingTest / ReplayTest stay unchanged (witness that build() and replay keep Parking).

## Review r1 FAIL → fix round (architect)
Docs (architect): §4 :186/:200 and the delayBefore(k) wording fixed; D-135 + §4 amended: a JVM Error is left to its lease
(crash-like, in doubt, bounded); the in-flight-renewal tempo note added to D-135.
I1 (implementer) Parking.java:48 fenced ctor and :67-69 branch are dead in main → delete; ParkingTest:488 case goes with it
   (reason: the code it tested is removed by D-135; name it in CORE-14a-test-changes.txt).
I2 (implementer) FlowBuilder.retries and FlowDefinition refuse a RetryPolicy above MAX_RETRIES (100) / outside the D-135 bounds
   — own unit cases (bound() overflow at Integer.MAX_VALUE).
I3 (implementer) CORE-14a-mutants.txt M5: name the site (:683); add a separate mutant for :640 (exhausted-path fence check);
   if nothing kills it, the test-author adds a case (tell the architect).
V (verifier) the reviewer's mutant list (PgWorkQueue:239 ×5, FlowWorker :640/:674 ×2/:705/:512/:752, FlowBuilder :386 ×2/:461,
   InMemoryWorkQueue :178/:117, Deployments wiring) + scoped PIT on FlowWorker, InMemoryWorkQueue, FlowDefinition, FlowBuilder,
   WorkerPolicy. Approved implementer changes to red tests (World wiring, deferredHeadsItsKey, DeploymentsRetriesTest waits) —
   reasons go in the commit body.
## Review r2 FAIL → fix round (architect)
T1 (test-author) FlowWorker :683 sibling: final failure, the fence closes during the run and reopens before the settle → no DLQ
   row, the round succeeds, one WARNING "is not parked: the node's fence is closed", rerun at lease end (kills PIT :685 null, :684).
T2 (test-author) a deferred run logs one WARNING naming the message, "attempt 1 of N" and "its retry waits PT1M" (kills :680).
I4 (implementer, after T1/T2) retriesDue gets a `stopped` check (PIT :259); FlowWorker:97 javadoc "one WARNING per item" made true
   (drop one of the double logs or fix the sentence). Then mutants :680/:684/:685 rerun by hand.
Docs (architect): §4 "before every park and every defer".
I4 done; M21 (retriesDue stopped check, race-only, fake timer cannot reach) accepted unpinned by the architect.
