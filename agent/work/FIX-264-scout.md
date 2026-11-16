# FIX-264 - the scout's reading of the replay tests (2026-11-16), as delivered; wrapped, not reworded

Read: ReplayTest 76-1119 except 765-875 (close/producers helpers, fixtures) and 1120+; ReplayEdgeTest 75-150;
RecorderTest 225-254; FlowBuilderTargetTest grep only (496-518 hits, body not read); FlowWorker:615-672; Replay:240-263.
Not read: ReplayTest 1-75, 435-505, 765-875; DeliveryTest/FlowWorkerTest bodies beyond the cases named (those are from
names, except FlowWorkerTest:1517 and FlowWorkerRetryTest:820-867, read).

## Replay.rebuild

Defined Replay.java:250 (static, package-private). Main callers: Replay.java:112 (replay's from-path) and
FlowWorker.java:646 (`verdict`, from the first "ingress" snapshot, same id; :647 then adds ATTEMPT). No test calls it
directly (grep `rebuild(` finds only main plus Deployments.rebuild, an unrelated private method). Tests reach it through
FlowWorker only.

## ReplayTest (all @Test, by line)

- :77 replayFromStepN... - COPY (new id, REPLAY_OF, from step n, REPLAY_INPUT row)
- :108 replayNeedsTheRecordedInput... - COPY (refusals of from/replayParked)
- :141 aReplayIsBuiltOnceAndHolds... - COPY (replay's own producer build count)
- :167 aDlqRetryReplaysTheParkedStep... - COPY (new id, REPLAY_OF, row removal)
- :202 aReplayDropsTheAttempt - COPY (copy drops ATTEMPT; plain keys)
- :224 ...AfterATwoAttemptRun...LatestAttemptsStepNMinusOneRow - COPY. It pins the choice of the step n-1 row for "from
  step n". The worker only ever starts at the first ingress row (FlowWorker:641). Nothing of the per-attempt rows'
  content survives except the stamp/attempt keys, pinned by FlowWorkerRetryTest:820 and FlowWorkerTest:1465.
- :261 ...SkipsALaterAttemptsFailed...RowForAnEarlierAttemptsOkOne - COPY (same selection, OK-row rule)
- :291 aDlqRetryDropsTheAttempt - COPY
- :313 aReplayPersistsItsStepBodiesBeforeItReturns - SURVIVES as a delivery/worker property (step body durable before
  rows, input never persisted again, run returns after trail close). Also pinned by FlowWorkerTest:1517
  (anItemCompletesOnlyAfterItsRunsBodiesPersisted: durable at complete, rows after, seed never again) and
  DeliveryTest:1344 (atMostOnceLands...), :1322. Nothing else in it survives (REPLAY_INPUT row).
- :367 aRetryPersistsItsStepBodies...AndRemovesTheRow - MIXED. Durability half: same as :313, pinned
  FlowWorkerTest:1517. Row removal after the run: COPY.
- :510 fromWithAGivenIdRunsAsThatId - COPY
- :560 everyPreRunRefusalNamesItsReason - COPY
- :658 aReplayHoldsAFlowPermitUntilItsTrailClosed - SURVIVES (admission permit held until trail closed, also for a
  failing run; 1-permit BUSY). Pinned for delivery by DeliveryTest:1471 (aSyncPermitIsHeldWhileItsRowsWait...), :1322
  (permit returns after rows). The failing-run half and the worker's permit: none found by name (natural home
  DeliveryTest or FlowWorkerTest).
- :706 noRoomIsBusyAndRunsNothing - COPY (requests+bytes BUSY for replay; the admission itself is Delivery's,
  DeliveryTest 1488-1785 per section B)
- :749 closeRefusesNewRunsAndClosesProducers... - COPY
- :880 aReplayFromARemovedBody... - COPY
- :921 aRetryOfARemovedParkedBody...RowStays - COPY. It pins Replay's exists() check on the parked body
  (Replay.java:189). FlowWorker has no exists/BodyLost check (grep of core/flow main: only Replay). The same-id /retry
  409 is pinned at unit level at AdminDlqRestartTest:363 (BlobNotFound) and :366 (BodyLost).
- :953 removedByRetentionComesAfter... - COPY (order of Replay's refusal reasons)
- :997 aStoreFailureCheckingTheBodyIsNotARefusal - COPY (exists() failure passes through)
- :1036 aReplayFromALostBody... - COPY
- :1063 aRetryOfALostParkedBody...RowStays - COPY (same as :921; unit twin AdminDlqRestartTest:366)
- :1092 aBodyRemovedAfterTheCheckFailsTheRun... - MIXED. Run fails with the miss signal and is no refusal (from-path:
  COPY). Retry half: a park finds no body to persist, writes no row, the original row stays; this touches Parking, not
  Replay. No test found for that in core FlowWorker/Parking/Delivery tests (grep BlobNotFound|BodyLost|forget: none).
  Natural home FlowWorkerTest or ParkingTest.

Net: none of the cases pins `Replay.rebuild` directly. The rebuild of the worker's envelope is pinned by
FlowWorkerRetryTest:845 (aRerunStartsFromTheFirstAttemptsIngress...: same id, ATTEMPT 2, body key, ingress headers) and
:820, plus FlowWorkerRerunWindowTest:406 (rerun envelope differs only by ATTEMPT).

## ReplayEdgeTest (2 cases)

- :85 aSecondCloseWhileARunIsInFlight... - COPY (Replay.close)
- :129 aRetryWhoseRowRemovalFails... - COPY (RowNotRemovedException naming both ids); its :147 "permit came back" slice
  is covered by DeliveryTest per section B (not re-read).

## The two neighbours

- FlowBuilderTargetTest:496 replayCountsAgainstTarget - COPY: a replay waits for the per-target permit while another
  flow holds it. A delivery-side twin was not read; line 56 of that file's Javadoc says the pipeline is wrapped
  likewise; FlowBuilderTargetTest:368 backoffHoldsNoPermit is a neighbour, not a twin.
- RecorderTest:241 replayStartRowRecordsCpuUnknown - MIXED. Pins the REPLAY_INPUT row (CPU_UNKNOWN, wall 0, step 1, b
  measured as 0). The same property "start row CPU unknown, wall 0, next step measured" is already at RecorderTest:229-231
  (ingress via build) and :234-237 (startSnapshot); so SURVIVES-and-covered, the replay case is a COPY.

## The architect's reading (2026-11-16) - what FIX-264's packet takes from the above

Every `ReplayTest` and `ReplayEdgeTest` case goes with `Replay`. Three properties they carried have no other test and
get a vehicle of their own, written by the test-author green on today's code before anything is removed:

1. From :658 - a run that fails holds its flow permit until its trail is closed, as a run that succeeds does
   (`DeliveryTest:1471` and `:1322` pin the successful half). Home: `DeliveryTest`. The scout found no case of the
   worker's permit by name: the test-author says whether the worker holds a flow permit at all before writing one.
2. From :1092, the retry half - a park that finds no body to persist writes no DLQ row and fails, and a row that was
   there stays. It is `Parking`'s property, not `Replay`'s. Home: `ParkingTest`, or `FlowWorkerTest` if `Parking` has no
   test class of its own.
3. From `FlowBuilderTargetTest:496` - a run waits for the per-target permit another flow holds. The delivery-side twin
   was not read: the test-author names it, or writes it.

Already pinned elsewhere, so the replay case simply goes: :313 and the durability half of :367
(`FlowWorkerTest:1517`, `DeliveryTest:1344`, `:1322`); :921 and :1063 (`AdminDlqRestartTest:363`, `:366` - the same-id
`/retry`'s 409); `RecorderTest:241` (`RecorderTest:229-237`); the rebuilt envelope of a worker run
(`FlowWorkerRetryTest:820`, `:845`, `FlowWorkerRerunWindowTest:406`).
A copy's property with no successor, gone with the operation (D-156): :77, :108, :141, :167, :202, :224, :261, :291,
:510, :560, :706, :749, :880, :953, :997, :1036, the from-path half of :1092, both cases of `ReplayEdgeTest`.

`Replay.rebuild` moves into `FlowWorker` as a private static; no test calls it directly, so no test moves with it.
Coverage "before" for FIX-264 is the whole-reactor run that verifies FIX-275: its verifier copies every module's
`target/site/jacoco/jacoco.csv` to `agent/work/FIX-264-before/`.
