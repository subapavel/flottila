# FIX-264 - the scout's second pass (2026-11-16, read at ef669a71): what of replay is left, by today's lines

As delivered, wrapped and shortened, not reworded. Facts from grep and file reads; no build was run. Not read by the
scout: test case bodies; `Deployments.drain()`; the simulation's `Action` enum.

## 1. Main code (`platform/**/src/main`; `bench/` has no hit)
- `core/record/Trail.java`:34-35 Javadoc ("a replay's input", "DLQ replay's parked body").
- `core/queue/WorkQueue.java`:276 comment "no replayOf (a replay's row)" - the prop's, FIX-265.
- `core/dlq/Parked.java`:18, :28 Javadoc; `core/dlq/Dlq.java`:30 Javadoc on `remove` ("after a successful DLQ replay").
- `core/flow/Delivery.java`:146-147 Javadoc "a {@link Replay} built beside it"; `admission()` at :147.
- `core/flow/ReplayRefusedException.java`, `core/flow/RowNotRemovedException.java` - whole files.
- `core/flow/Replay.java` - whole file; `REPLAY_INPUT = "replay"` :52-53; `rebuild` :245-253.
- `core/flow/FlowBuilder.java`: class Javadoc :75-76, :87, :100, :102-105, :114, :214; the public overloads :441-458;
  the private one :460-474 (:461 the `store == null` check, :467 `new Replay`, :470 `REPLAY_INPUT`); Javadoc :450-453,
  :494.
- `core/flow/FlowWorker.java`:646 - `Replay.rebuild(...)`, the only hit in the file.
- api (FIX-265's): `BodyLostException.java`:7-8, `Envelope.java`:38-39 and :43, `Body.java`:48, `Props.java`:8.
- `runtime/queue/PgWorkQueue.java`: Javadoc :54, :284, :437, :615; SQL :305 `props->'replayOf' is null` (FIX-265's).
- `runtime/package-info.java`:2 "recording, replay and the admin API".
- `runtime/deploy/Deployments.java`: import :22; class Javadoc :103, :117, :120-121, :190, :196; record `Flow`'s field
  `Replay replay` :374-377; `Flow.with`/`failed` :389, :395-396; `Flow.close` :400-407 (:407 `replay.close()`);
  `replay(String)` :488-494; `close()` :656-671 (the `replays` future, :666, :669-671); Javadoc :1499, :1503; `flow()`
  :1518-1537 (the local `replay`, `builder.replay` :1522, :1528, :1537).
- `runtime/admin/AdminServer.java`: Javadoc :145 ("no replay route"), :689; :710 reads `REPLAY_OF`, :712 the message
  ", a replay" (FIX-265's).
- Not the operation: `runtime/blob/TieredBlobStore.java`:44 (bytes replayed into the spill),
  `runtime/journal/peer/PeerSession.java`:11 (a frame), the simulation's `SimSeed.java`:23, `SimConfig.java`:13,
  `Verdict.java`:52 and :64 (the replay of a seed; the string "SimReplayTest").

## 2. `Replay.rebuild`
`static Envelope rebuild(Snapshot input, String id, String flow, Props props, BlobStore blobs)`, `Replay.java`:250, body
:250-253, Javadoc :245-249. It calls `Body.of`, the `Envelope` constructor, `Views`, `Map.of` and
`bodyKey().orElseThrow()` - no private helper and no constant of `Replay`. Callers: `Replay.java`:112 and
`FlowWorker.java`:646. No test calls it.

## 3. `FlowBuilder`
`replay(FlowDefinition, Path)` :444-447 (takes `admission(flow)`); `replay(FlowDefinition, Path, Delivery)` :455-458
(takes `delivery.admission()`); the private `replay(..., Admission)` :460-474. Only replay's: those three, the
`store == null` check at :461, and - in main - `Delivery.admission()` (its only main caller is `FlowBuilder.java`:457).
Shared and staying: `built()` (:286, :319, :323, :386, :462), `admission(flow)` (:408, also used at :316), the `dlq`
field, the `Built` record, `Producers`.

## 4. `Deployments`
Only replay's: the field `Replay replay` of record `Flow` (:377), `Flow.close()`'s `replay.close()` (:407),
`replay(String)` (:492-494), the `replays` future in `close()` (:666-671), and in `flow()` the local `replay` (:1518,
:1522, :1528, :1537). Test callers of `replay(String)`: `DeploymentsReplayCloseTest`:94, :104; `DeploymentsRestoreTest`
:404, :462, :500; `DeploymentsProducersTest`:387, :407, :427. No main caller.

## 5. The schema
`platform/schemas/src/main/resources/schemas/flow.schema.json`:30, the `timeout` description: "...its deadline is the
run's start plus this (sync after admission, each at-least-once claim, each message replay or DLQ replay)." The only
mention in the file.

## 6. The simulation's check
`Check.REPLAY_YIELDS_RECORDED` is declared at `platform/sim/src/main/java/dev/rail8/sim/Check.java`:5. No other main
reference: the oracle never counts it. Tests: a comment at `SimSelfTest.java`:32. Resources: none. Pinned seeds are
written by name - `PinnedSeedsTest.java`:70 reads `Check.valueOf(c)`. No test pins the enum's count, order or
`values()`; `Check.values()` appears nowhere in the simulation.

## 7. `Dlq.remove`, `Delivery.admission()`
`Dlq.remove`: declared `Dlq.java`:31, `InMemoryDlq.java`:63, `PgDlq.java`:148; its only main caller is `Replay.java`:159.
`Delivery.admission()`: main caller `FlowBuilder.java`:457 only; test callers `DeliveryTest.java`:1488, :1508, :1589,
:1637, :1638, :1669, :1736, :1784, :1785 - none of them replay's.

## 8. Tests that refer to the class, its methods, its constant or its exceptions (the test method's line)
- core `flow/ReplayTest.java` - 22 cases: :77, :108, :141, :167, :202, :224, :261, :291, :313, :367, :510, :560, :658,
  :706, :749, :880, :921, :953, :997, :1036, :1063, :1092; the helper `refusal()` :496.
- core `flow/ReplayEdgeTest.java` - :85, :129.
- core `flow/FlowBuilderTest.java` - `versionsReachEverySnapshotAndDlqRow` :193,
  `aReplayInFlightMakesTheConsumersNextDeliveryARetryableNack` :397, `aTwoArgReplayHasAnAdmission…` :442.
- core `flow/FlowBuilderTargetTest.java` - `replayCountsAgainstTarget` :496.
- core `flow/FlowBuilderContextTest.java` - `aReplayAndARetryBindTheirNewIdWithAFreshDeadline` :222.
- core `flow/FlowBuilderFenceTest.java` - `aBuiltPipelineAndAReplayAreNotFenced` :44.
- core `flow/FlowBuilderStepEventTest.java` - `aDefaultBuilderMarksNoStep` :344, `theStartStageIsNotMarked` :452.
- core `record/RecorderTest.java` - `replayStartRowRecordsCpuUnknown` :241.
- runtime `store/InvoiceSyncReplayIT.java` - :167, :205.
- runtime `flow/SftpIngressDedupeIT.java` - `replayOfADedupedMessageRunsAsANewMessage` :231.
- runtime `flow/DlqBodiesIT.java` - :334, :419.
- runtime `flow/StepBodiesIT.java` - :402 and :502 (parameterized), :439; the fixture's `REPLAY_OF` hook :227.
- runtime `deploy/DeploymentsReplayCloseTest.java` - :89.
- runtime `deploy/DeploymentsRestoreTest.java` - :358, :439, :475.
- runtime `deploy/DeploymentsProducersTest.java` - :382, :423.
- `REPLAY_OF` only (FIX-265's): `AdminDlqRestartTest`, `PgWorkQueueRestartIT`, `AdminDlqRestartIT`, api `EnvelopeTest`.
- The word only, in a name or a comment (24 files): `DlqRestartIT`, `RetentionRetryIT`, `NodePoolWaitIT`, `NodeIT`,
  `AdminServerBrowserGuardTest`, `RawPeer`, `PgPromotionLedgerIT`, core `TrailTest`, `ParkedPartitionKeyTest`,
  `MockComponentTest`, the simulation's `Worlds`, `SimVerdictTest`, `SimHarnessTest`, `PinnedSeedsTest`,
  `SimReplayTest`, schemas' `PackageFoldersTest`, `ReferenceEnqueueIT`, `PgBodyRefsIT`, `GiveUpRaceIT`,
  `DeploymentsSecretsTest`, `DeploymentsRollbackTest`, `DeploymentsRollbackEdgeTest`, `DeploymentsRedeployTest`,
  `DeploymentsProducersEdgeTest`; and the simulation's `pinned-seeds.tsv`. `RetentionIT`:586 is a comment.
  `PeerProtocolTest`:208 `peer.replay()` is a frame call.

## 9. The old list's lines against today
Every file exists. The lines of `agent/work/FIX-263-cases.md` still name a test method's line: `ReplayTest`,
`FlowBuilderTargetTest`:496, `RecorderTest`:241, `SftpIngressDedupeIT`:231, `DlqBodiesIT`:334 and :419 (`REPLAY_OF` at
:364, :449), `StepBodiesIT`:402, :439, :503 (hook :227), `ReferenceEnqueueIT`:165 (twin
`aDlqRetryEnqueueCopiesTheParkedRef` :189), `DeploymentsReplayCloseTest`:89, `DeploymentsProducersTest`:382, :423 and
the counts :154, :188, :216, :246, :302, `DeploymentsRedeployTest`:398, :476, :647, :1355 (":216" is not a method start
there), `DeploymentsRollbackTest`:354, :516, :977, `DeploymentsProducersEdgeTest`:61, :76,
`DeploymentsRollbackEdgeTest`:390, `DeploymentsRestoreTest`:358, :404, :439, :475.

## 10. Rules and documents
ArchUnit (`platform/archtest`): no rule names replay or the flow package. `package-info`: only the runtime's :2.
`docs/ARCHITECTURE.md`: no heading names replay; mentions in the body and the changelog at :42, :46, :73, :102, :104,
:115, :385, :737, :747. `README.md`: none.

## 11. Two facts for the vehicles
`platform/core/src/test/java/dev/rail8/core/flow/ParkingTest.java` exists (its `Dlq` stub's `remove` at :136, :217).
`FlowWorker.java` has no `admission`, `permit` or `Semaphore`: a worker run takes no flow admission permit.
