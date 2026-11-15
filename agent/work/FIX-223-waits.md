# FIX-223 — tests that wait on tempo: the list, first pass (2026-11-15)

A scout's survey by Grep, read-only, of `platform/**/src/test/java` and `bench/**/src/test/java` (not `mapping/`). Capped
lists are marked; "not determined" means it would take more than a local read. Nothing here is fixed or judged yet: the
row stays pending. What the first pass shows is the size of the job, and the entries of part C to read first.

## A. Helpers that spin or poll against a deadline

- 57 definitions of `until(BooleanSupplier …)` in 55 files (two each in `NodeShutdownWaitsForWorkerSettlementIT` :225, :230
  and `StepBodiesIT` :379, :384). Shape: `deadline = nanoTime() + bound; while (!cond) { if past: fail; onSpinWait(); }`;
  some runtime helpers loop with `Thread.yield` (`NodeIT` :2101-2105).
- The bound in one place with its reason: `JournalFixture` :47-48 (`BOUND` 10 s, "a failure detector, never a timing");
  `HeldPipeline` :45-46 (10 s); `MainProcessIT` :73-74 (60 s); `OnVirtualThread` :20-21 (60 s).
- A named bound with no reason seen: `NodeTakeoverLedgerIT` :82 (120 s), `RetentionIT` :75 (20 s),
  `FlowBuilderStepEventTest` :78 (30 s), `FlowCloseTest` :63 (10 s), `CpuSamplerIT` :116 (30 s).
- A literal bound inside the helper: `TargetLimitTest` :79 (5 s), `TrailTest` :239 (10 s),
  `InputStreamReadStreamDemandTest` :230 (10 s), `DeliveryTest` :1303 (5 s), `FlowWorkerTest` :174 (5 s),
  `DeploymentsFenceTest` :91 (10 s), `DeploymentsTest` :243 (20 s), `NodeIT` :2101 (20 s), `PgPromotionLedgerIT` :616 (60 s).
- Call sites, an upper bound (occurrences / files of `until|soon|waitUntil|awaitUntil|eventually|waitFor…(`): runtime
  604 / 108, core 340 / 31, components 47 / 9, cli 19 / 5, bench 18 / 8, api 8 / 2, testkit 4 / 2, sim 0.
- Inline deadline loops outside the helpers: about 90 sites of `long deadline = System.nanoTime() + …` (not counted
  exactly), e.g. `PgBodyRefsIT` :129, :197; `JournalReadIT` :132, :274; `PeerReadIT` :162; `FrameRelay` :126;
  `SegmentSweepIT` :245; `NodeTakeoverLedgerIT` :416, :489, :577.
- Spins with no deadline seen: `RetryTest` :103, :211; `ParkingTest` :438, :440; `DeliveryTest` :1865;
  `SimSchedulerTest` :134, :158, :165.

## B. Sleeps, timeouts, literal bounds

- `Thread.sleep`: one, `NodeTakeoverLedgerIT` :431 (a back-off inside an ack retry bounded at :407, comment FIX-156).
- `assertTimeoutPreemptively`: one, `SimRuns` :28 (`PER_SEED` 30 s, reason at :18).
- No assertion on elapsed real time found by pattern (`Duration.between` not searched exhaustively).
- Literal bounds on `.get(…)`/`.await(…)` with no named constant: at least 60 sites (not counted). The 25 read first:
  `AdmissionTest` :228, `AdmittedTest` :151, `RequestScopeTest` :276 (60 s); `TargetLimitTest` :88, :130, :171, :211, :217
  (5 s), :278 (10 s); `DeliveryTest` :246, :262, :438, :595 (10 s); `QuiescenceTest` :44, `VirtualTimeTest` :63,
  `FlowTestsWorldTest` :157, `MockIngestTest` :83 (10 s as nanos); `PgBodyRefsIT` :129, :197 (20 s); `NodeReplEpochIT` :136
  (30 s); `NodeIT` :768, :921 (30 s); `ReplicaBackpressureTest` :103, :209 (60 s), :362 (120 s); `PeerReadHeapTest` :261,
  :278, :418, :470 (30 s; rows FIX-224, FIX-228); `PgTlsIT` :213 (30 s); `AdminServerTest` :906, `DeploymentsRetriesTest`
  :93, `SftpComponentTest` :112, `HttpComponentTest` :433, `RecordingComponentDeadlineTest` :50 (10 s);
  `BlockedPlatformThreadDetectorTest` :120 (1 h, inside a pool task); bench `StreamHeapNodeIT` :86 (120 s),
  `OpenLoopOffloadedTest` :64 (30 s).

## C. Waits for a count that later writes may pass through — the fault the row names (at least 70 `== n` waits seen)

| # | Where | Condition | Can anything still write after n? |
|---|---|---|---|
| 1 | `FlowWorkerTest` :622, :625, :629, :634 | `claims().size() == c0+k && claimsInFlight() == 0` | ticks only from `clock.advance` (:621, :624, :633) in a local read |
| 2 | `FlowWorkerTest` :385, :415, :451, :456, :478, :485, :503 | `renews().size() == n` | renewal is rescheduled by a timer — not determined |
| 3 | `FlowWorkerTest` :426, :510 | `claimsBy(w).size() == 2` | not determined |
| 4 | `FlowWorkerTest` :707 | `rows.size() == 0 && gate.exited() == 3` | not determined |
| 5 | `TrailTest` :588 | `full.calls() == 2` | no further add after :584-585 in a local read |
| 6 | `TrailTest` :494, :277, :436 | `store.of(id).size() == 2`, `bodies.held() == 3`, `heldRows.handed() == 2` | not determined |
| 7 | `FlowBuilderTargetTest` :452, :377 | `partner.sent.size() == 16 && pending() > 0`, `== 1` | a late 17th send would be a failure — not determined |
| 8 | bench `LedgerMainPartsTest` :103 | `partner.calls() == 3` | no: the next write is the release at :104 |
| 9 | `TargetLimitTest` 17 sites (:109 … :557) | gauge `inFlight()`/`waiting() == 1` | gauges go up and down — not determined |
| 10 | `AdmissionTest` 10 sites (:90 … :196) | `a.waiting() == 1` or `2` | not determined |
| 11 | `FlowBuilderTargetTest` :301, :340, :349, :541, :578 | `gauge().waiting() == 1` | not determined |
| 12 | `RecordingComponentTest` :222 | `handled.size() == 1` | not determined |
| 13 | `FlowWorkerFastPathTest` :540 | `isComplete() \|\| runsOf().size() > 1` | not determined |
| 14 | the converse — a wait for `>= n`, then an exact assertion | `TrailTest` :461, `ReplayTest` :394, `VirtualTimeTest` :124, `ComponentContractSoonTest` :28, `DeploymentsFenceTest` :112, `DeploymentsTest` :884 | the assertions after them not read |

## What is known and owned elsewhere

Fixed: the journal dir-force holds (FIX-168, FIX-227), the comparisons with the disk (FIX-213, FIX-222, FIX-225),
`RecordingComponent.awaitSent` (FIX-229), the bench's query-log count (FIX-219). Rows: FIX-224 (`PeerReadHeapTest` :281),
FIX-228 (four peer flow-control cases pinned to one CPU), FIX-230 (doubles that record and then take), FIX-231 (a teardown
that closes the relay first), FIX-232 (`PreopenSeen`).

## What the row still needs

1. Part C read entry by entry (a test-author, by module: core first — `FlowWorkerTest`, `TargetLimitTest`,
   `AdmissionTest`, `FlowBuilderTargetTest`, `TrailTest`): each "not determined" becomes "final state" or a fault.
2. One bound per module with its reason, in the manner of `JournalFixture.BOUND`, in place of the literal and unnamed
   bounds of parts A and B — a mechanical change, one commit per module.
3. The spins with no deadline (part A, last line) get a bound or a reason.
