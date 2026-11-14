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

## Acceptance CORE-14b (red first, test-author; after 22d9baa)
P8 as written. Core FlowWorkerRetryTest.restartRunsItOnce: a restarted row sends once with the same id at attempt k+1; failing,
it is parked again and nothing runs after +1d. WorkQueueContract (InMemory + Pg): restartedRowRunsOnceThenExhausted (a row whose
max_attempts = k+1 is handed out once more, then exhausted). DlqRestartIT.restartIsOneTransactionWithTheSameId: an injected failure
leaves the DLQ row and no work row; then a deduping mock partner sees one effect (FIX-138's case). AdminDlqRestartIT: 202 {id,
attempt} / 404 no row / 422 a row the worker did not park (step ≠ 1 or REPLAY_OF); a restarted item runs on a node that may claim it.
V17 migration test (column exists, null = flow bound). `/retry` unchanged (new copy).

## Red approved 14b (architect, 2026-11-12)
API: `int WorkQueue.Item.maxAttempts()` (0 = flow bound); `Future<Boolean> WorkQueue.restart(String messageId, String flow,
String partitionKey, int attempt)` (abstract; every fixture implements it); POST /admin/dlq/{id}/restart → 202 {id, attempt: k+1}
/ 404 / 422; V17. Tests: FlowWorkerRetryTest :679/:716/:745, WorkQueueContract:261, DlqRestartIT:162, AdminDlqRestartIT
:204/:230/:236, MigrationsIT:554; the 16 → 17 version expectations (10 MigrationsIT cases, PgDeploymentStoreIT:101,
PgMessageStoreIT:77) accepted as forced by V17. Fixtures ParkedRows, contract hook parked(id, flow, k).
O7 Partition key (decision): the DLQ row keeps it. V17 also adds `dlq.partition_key text` (null for earlier rows); the worker's
   park writes the item's key; restart enqueues under it (tail of its key, new seq). A row of a keyed flow (`partitionBy`
   set) whose key is null (parked before V17) answers 422 "parked before its key was kept; use /retry"; an unkeyed flow's
   null is fine. Tests to add (test-author): a keyed restart heads behind the key's later rows in seq order and keeps the key;
   the pre-V17 keyed row → 422; the worker's park writes the key (Pg IT); V17 migration covers both columns.
Red approved O7: FlowWorkerRetryTest:780, WorkQueueContract:288, DlqRestartIT:237, AdminDlqRestartIT:316 (fixture now a real
Deployments, accepted), MigrationsIT:555; API `Optional<String> Parked.partitionKey()` (14th component, 13-arg ctor kept).
O8 a restart whose flow is not deployed on this node answers 409 "flow <f> is not deployed" and changes nothing (implementer's own case).

## Review 14b r1 FAIL → fix round (architect)
Docs done by the architect: §7 WorkQueue row names the restart; D-135 [CORE-14b] 404 re-check wording, /retry race, announce-to-every-node (not "as /release").
R1 (test-author, red): PgWorkQueueRestartIT — park at k=3, restart(id, flow, null, 2) → false, DLQ row kept, no work row; also a step ≠ 1 row and a replayOf row → false, unchanged.
R2 (implementer): RESTART `d` adds `and step = 1 and props->>'attempt' = $4::text and props->'replayOf' is null`; false → 404 in the route.
   Move RESTART's javadoc+constant so HAS_ITEMS keeps its javadoc (PgWorkQueue ~:280).
R3 (verifier): mutants M1–M4 (reviewer's) + one per new `d` predicate; scoped PIT on FlowWorker, InMemoryWorkQueue, Parked, Parking;
   per-module surefire/failsafe counts from TEST-*.xml after a full verify -Pit; `git status --short`.
Accepted: (b) in-memory DLQ row left (test wiring only); BenchMainIT 16→17 pin (forced by V17, named in the commit body).
Red approved r1 (14b): PgWorkQueueRestartIT :119 attempt mismatch, :129 step ≠ 1, :140 replayOf (3/3 red, CORE-14b-r1-red.log). Route false→404 already pinned by AdminDlqRestartTest.
R4 (r2 FAIL): the r1 verify ran 13 of 16 modules (archtest, cli, components/mock missing) — whole-reactor verify -Pit by the verifier; WorkQueue.restart javadoc names step 1 / attempt k / no replayOf.

## r2 verify: CORE-14a regression in flottila-cli (architect)
Fact (diagnosis, agent/work/CORE-14b-cli-{9f80ee7,22d9baa}.log): since 22d9baa the CLI flow-test runner's node takes the default 3 × PT5M; the
partner-down cases of async-order (flowtest fixture), demo invoice-async and sftp-invoice reach VirtualTime.HORIZON 10m unsettled; TestCommandTest
6/27 fail; `bin/flottila test packages/demo` 4/6 (gate check 8). CORE-14a's evidence never ran the cli module.
O9 (decision): option 2 — each of the three at-least-once fixture/demo flows names `delivery.retries {count: 1, interval: 1s}`, so the demo shows one
   scheduled retry and then the park; expectations (test yaml + TestCommandTest) move to the observed values (flow time, retries, sent count, dlq 1),
   with the yaml comments. No runner or production code change. Folded into the CORE-14b commit (the branch must be green); the commit body names the
   CORE-14a regression. The runner's 10m horizon against the node default (a package without `delivery.retries` cannot settle a partner-down case,
   and `expect.retries` mixes step retries with ingress reruns) → FIX-174 (filed).
R5 (test-author): the yaml + expectation changes, whole cli module and `bin/flottila test packages/demo` green.
