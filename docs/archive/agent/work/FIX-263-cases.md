# FIX-263 - the per-case list: every test that holds the word "replay" (2026-11-16, HEAD b04e336a)

Two scouts read; nothing was run. Classes: GOES (pins only the withdrawn behaviour), STAYS (another meaning, or the
word is only in a comment or a name), MOVES (replay is the vehicle of a property that survives), MIXED. The row is the
one whose production code the case touches: 263 the admin routes, 264 the core and the deployments, 265 the api prop.
"inferred" and "not read" are the scouts' own marks. The architect's reading and decisions are in section C.

## B. Everything under `platform/` except `platform/runtime` (scout B, as delivered)

Scope: 20 files with hits under test or schemas. No hit in testkit, archtest, cli, steps (also none for `Replay`,
`REPLAY_INPUT`, `replayParked` in testkit, archtest, cli, components, steps). Main sources with hits, not classified:
core (`Replay`, `ReplayRefusedException`, `RowNotRemovedException`, `FlowBuilder` 23 hits, `Trail`, `WorkQueue`,
`Parked`, `Dlq`, `Delivery`, `FlowWorker`), api (`Envelope`, `Props`, `Body`, `BodyLostException`), schemas
(`flow.schema.json`, 1), sim (`Check`, `SimSeed`, `SimConfig`, `Verdict`).

`platform/core/src/test/.../flow/ReplayTest.java` (212 hits, about 14 cases; rows 264 and 265)
- replayFromStepNRunsStepsN...:77 - GOES - 264 - a copy under a new id; asserts the prop "replayOf" at :96 (265 too)
- replayNeedsTheRecordedInput...:108 - GOES - 264 - the refusals of `from` and `replayParked`
- aReplayIsBuiltOnceAndHoldsNothingOfTheBuilderOrTheRuntime:141 - MIXED - 264 - "producers built once, none resolved
  at run time" is a property of the replay's build (counts 2 at :153, :156); the Runtime-capture half is partly
  covered by the worker (inferred)
- aDlqRetryReplaysTheParkedStepAsANewMessage...:167 - GOES - 264 - a new id and the row's removal
- aReplayDropsTheAttempt:202, aDlqRetryDropsTheAttempt:291 - GOES - 264 - the copy drops the attempt
- aReplayFromStepNAfterATwoAttemptRun...:224, ...SkipsALaterAttemptsFailed...:261 - MOVES (inferred, not read) - 264 -
  the start row of a step-N run comes from the right attempt's snapshot; vehicle: the worker run (`FlowWorker:646`
  calls `Replay.rebuild`)
- aReplayPersistsItsStepBodiesBeforeItReturns:313, aRetryPersistsItsStepBodies...:367 - MOVES - 264 - step bodies are
  durable before the run returns; vehicle: the worker run or the delivery pipeline
- fromWithAGivenIdRunsAsThatId:510 - GOES - 264
- everyPreRunRefusalNamesItsReason:560 - GOES - 264 (the reasons of `ReplayRefusedException`)
- aReplayHoldsAFlowPermitUntilItsTrailClosed:658 - MOVES - 264 - the permit is held until the trail is closed;
  vehicle: `Delivery.admission()` or the worker
- noRoomIsBusyAndRunsNothing:706 - GOES - 264 (the BUSY refusal)
- closeRefusesNewRunsAndClosesProducersAfterTheLastRun:749 - GOES - 264 (`Replay.close`)
- aReplayFromARemovedBodyIsRefusedRemovedByRetention...:880, aReplayFromALostBodyIsRefusedBodyLost...:1036 - GOES - 264
- aRetryOfARemovedParkedBodyIsRefused...:921, aRetryOfALostParkedBodyIsRefusedBodyLost...:1063 - MOVES (inferred) -
  264 - the refusal when a parked body is gone, and the row stays; a same-id retry needs the same refusal
- removedByRetentionComesAfterMetadataOnlyAndBeforeTooLarge:953, aStoreFailureCheckingTheBodyIsNotARefusal:997,
  aBodyRemovedAfterTheCheckFailsTheRunAndParksNothing:1092 - MIXED (inferred, not read) - 264 - the order of the
  checks and the failure handling, which may move with the retry's body check
- the helpers (builder, gated, over, recorded, parkedAtC, forgetting, ForgettingBlobStore...) go with the file
- The row's claim "ReplayTest goes whole": wrong in part - the MOVES cases carry properties that survive.

`.../flow/ReplayEdgeTest.java` (11 hits, 2 cases) - the claim "goes whole": mostly right
- aSecondCloseWhileARunIsInFlightStillWaitsForTheRun:85 - GOES - 264 (`Replay.close`)
- aRetryWhoseRowRemovalFailsIsRowNotRemovedNamingBothIds:129 - GOES - 264; its line :147 "the permit came back: the
  next retry runs" is a MOVES slice that `DeliveryTest` (1488-1785, `Delivery.admission()`) already covers

`.../flow/FlowBuilderTest.java` (27 hits)
- versionsReachEverySnapshotAndDlqRow:193 - MIXED - 264 - the replay half :203-213 GOES; the ingress, step, DLQ and
  worker halves STAY
- aReplayInFlightMakesTheConsumersNextDeliveryARetryableNack:397 - MOVES - 264 - a held permit makes the next delivery
  a retryable Nack, admitted once the permit is back; vehicle: a gated Delivery run; the BUSY half :429 GOES;
  `DeliveryTest` may already cover it (inferred)
- aTwoArgReplayHasAnAdmissionOfItsOwnFromTheFlowsLimits:442 - GOES - 264
- helpers gatedFlow, plainFlow, mark, answered, atGate :363-389 - GO if unused elsewhere (not checked)

`.../flow/FlowBuilderStepEventTest.java` (15 hits) - the claim "mixed": right
- aDefaultBuilderMarksNoStep:344 - MIXED - 264 - the replay half :354, :363-393 GOES; the rest STAYS
- theStartStageIsNotMarked:452 - MIXED - 264 - the recorded run's half STAYS; the replay-input half :457-469 GOES or
  MOVES to the worker's start stage (inferred)

`.../flow/FlowBuilderContextTest.java` (17 hits) - the claim "mixed": right
- aReplayAndARetryBindTheirNewIdWithAFreshDeadline:222 - MIXED - 264 - the original run's context STAYS; the halves on
  `replay.from` and `replayParked` GO; "a fresh deadline per claim" (:45) MOVES to a worker run

`.../flow/FlowBuilderFenceTest.java` (3 hits) - the claim "mixed": right
- aBuiltPipelineAndAReplayAreNotFenced:44 - MIXED - 264 - the built pipeline's half STAYS; the replay half :50 GOES

`.../flow/FlowBuilderTargetTest.java` (11 hits; not in the backlog's list)
- replayCountsAgainstTarget:496 - GOES - 264 - a replay waits for the per-target permit; other cases cover the
  pipeline (inferred)

`.../record/RecorderTest.java` (5 hits; not in the backlog's list)
- replayStartRowRecordsCpuUnknown:241 - MOVES - 264 - the start row's CPU is unknown (F-21), its wall is 0, step b is
  measured; vehicle: the worker run's start row (inferred; `FlowBuilder.java:470`)

Comment only, STAYS: `.../record/TrailTest.java:315`, `.../dlq/ParkedPartitionKeyTest.java:13`.

`platform/api/src/test/.../EnvelopeTest.java` (5 hits) - the claim "line 88 goes": right
- replayCarriesTheOriginalIdAsAProp:88 - GOES - 265

STAYS, another meaning: `schemas/.../PackageFoldersTest.java:521,526` (a fixture folder named "replay/");
`components/mock/.../MockComponentTest.java:127` (a message text; the assertion is the endpoint-wide count).

The simulation's tests - all STAYS (a seed run replayed): `pinned-seeds.tsv:1`, `PinnedSeedsTest`, `SimReplayTest`,
`SimVerdictTest`, `SimHarnessTest:81`, `Worlds:19`, `SimSelfTest:32` (a comment naming `REPLAY_YIELDS_RECORDED`).
- `Action` has SEND_SYNC(1), SEND_ALO(2), RESEND(3), POLL(4): there is no replay action in the enum.
- `Check.REPLAY_YIELDS_RECORDED` is declared (`Check.java:5`); no test refers to it. No pinned seed and no enum test
  changes with its removal, unless the order of `Check`'s constants is pinned somewhere (none found).
- "Append-only" is a code comment (`Action.java:3`); no test of the action codes or of `Check`'s order was found.

The queue contract: `platform/runtime/.../queue/WorkQueueContract.java` has restartedRowRunsOnceThenExhausted:261 and
aKeyedRestartJoinsTheTailOfItsKey:288; core has `InMemoryWorkQueueRestartTest:24-33`. No case says "restarted
whatever its other props" or "a row without the worker's attempt is not restarted" - FIX-265's claim is right.

Totals (scout B): 20 files. GOES about 20, MOVES about 8, MIXED 6, STAYS 11 files or cases. Row 264 about 33, row 265
one (`EnvelopeTest`, also `ReplayTest:96`), row 263 none in this scope.
Not read by scout B: the bodies of most `ReplayTest` cases (so :224, :261, :953, :997, :1092 are unclassified),
`FlowBuilderTest` 87-358, `DeliveryTest` beyond its grep hits, sim main beyond grep.

## A. `platform/runtime/src/test` and `bench/` (scout A, as delivered)

`bench/`: no hit in any letter case. `platform/runtime/src/test`: 31 files, 433 hits, all Java. Paths below are under
`platform/runtime/src/test/java/dev/rail8/runtime/`. A line is the test method's unless marked cmt (comment only).

`admin/AdminServerReplayTest.java` (20 cases and helpers)
- replayIs200WithIdReplayOfFromOutcome:479, aFailedRunIs502Failed:499, aParkedRunIs502Parked:517, badFromIs400:537,
  unknownIs404:555, notRunningIs409:576, unrecordedOrMetadataOnlyIs422:605,
  aStepBeyondTheRedeployedFlowOrABodyAboveTheBudgetIs422:635 - GOES - 263/264/265 - the `/replay` route's answers
- aDlqReplayIs200AndTheRowIsGone:704, aDlqReplayWithAFromParameterIs400AndRunsNothing:725,
  aConcurrentDlqReplayOfTheSameIdIs409:747, aDlqReplayWhoseRowRemovalFailsIs500NamingTheNewId:780 - GOES - 263/264
- aReplayFromARemovedBodyIs409RemovedByRetention:836, aDlqReplayOfARemovedBodyIs409RemovedByRetention:879 - GOES -
  263/264; the `/retry` twin of the 409 exists at `AdminDlqRestartTest:245` (inferred)
- aStoreFailureCheckingTheBodyIs502Failed:907, aPoolRefusalInsideTheRunIs503AndNothingRan:929,
  aFailureInsideTheRunThatIsNoPoolRefusalStays502Failed:960 - GOES - 263/264; :944 puts a dlq replay in the 503 case;
  whether `/retry` has a 503 pin was not checked
- noRoomIs429WithRetryAfter:662 - MOVES - 263 - the admission permit comes back; vehicle: a second delivery of the
  flow through `doors.door(..).deliver` while the first is held; the 429 comes from the replay's refusal map
- theRunIsOnAVirtualThread:685 - MOVES - 263 - the run is off the event loop; the Probe (158-199) keys on an envelope
  carrying REPLAY_OF (197) and must key on something else; vehicle: `/retry` (inferred, not checked)
- replayDlqReplayAndRetryNeedTheToken:802 - MIXED - 809-810 (the two replay 401s) GO; 811 (`/retry` 401) survives
- helpers replay:394, dlqReplay:398, dlqReplaying:403, assertComplete:427, assertFailed:436, Probe.replayThreads:164
  GO. The class also holds shared fixtures (shop(), Probe, Hold, until, BOUND, TOKEN, deploy, recorded, parked, send)
  used by `AdminServerReplayRaceTest`, `ReplayRetentionIT`, `LostBodyAdminIT` and `NodePoolWaitIT` (one reference):
  they move out before the class is deleted.

`admin/AdminServerReplayRaceTest.java` (4 cases, GOES, 263/264): aMessageWhoseRowsAreGoneByTheRunIs404:186,
aRowRemovedByTheRunIs404:198, aSecondDlqReplayOfAnIdWhileItsFirstRunsIs409UntilTheFirstAnswered:230,
aFlowReplacedByTheRunIs409:260. Not checked: whether :230 has a `/retry` twin (the same-id guard).

`admin/ReplayRetentionIT.java` - aReplayFromAStepRetentionRemovedIs409AndItsBodyRead404:211 - MIXED - 263/264 - the
replay and its 409 GO; "retention removes an aged message and its body, another message's rows stay" survives.
`admin/LostBodyAdminIT.java` - aLostBodyIsRefused409ForReplayAndDlqReplayAndItsGetIs410:214 - MIXED - 263/264 - the
two replay 409s GO (224, 230, 231, 240, 241); the GET's 410 (237), "no step ran, the DLQ row stays" (235) and the
other message's GET 200 (239) survive.

`admin/AdminDlqRestartIT.java` - aRowTheWorkerDidNotParkIs422:321 - MIXED - 263/265 - :326 builds a row with REPLAY_OF,
332-334 expect a 422 naming the replay row (265); 331 and 334 are text naming `/replay` (263).
aKeyedFlowsRowParkedBeforeItsKeyWasKeptIs422...:345 - STAYS, only its expected text changes (namesReplay:276, at 353).
`admin/AdminDlqRestartTest.java` - aSyncFlowsRowIs422:224, aRowWithoutAnAttemptOfAtLeastOneIs422:230 - STAY, the
expected text only (namesReplay:211, at 226, 232, 234); cmt 67 says "each 422 names /replay".
`admin/AdminServerBrowserGuardTest.java` - aCrossOriginPostIs403WithoutReachingTheRoute:189 (194-200),
aCurlStyleRequestWithoutOriginPasses:224 (235-236), the route list helper 150-159 - MOVES - 263 - the browser guard's
403 and the no-Origin pass on a POST route; vehicle: `POST /admin/dlq/m-404/retry` or another POST route.
`admin/AdminServerCpuTest.java` - cmt 156 - STAYS.

`NodePoolWaitIT.java` - aReplayRefusedByTheWorkPoolAnswers503NotA502:616 - GOES - 263.
aDlqReplayRefusedByTheWorkPoolAnswers503:641 - MOVES (inferred) - 263 - a pool refusal answers 503 on a DLQ route;
vehicle: `/retry`, like aDiscardRefusedByTheWorkPool...:581.
`NodeIT.java` (29 hits) - replayFromStep3IsANewMessageMarkedReplayOf:1567 and
partnerDownParksAndReplayDeliversANewCopyOncePartnerIsBack:1715 - GO - 263/264/265. The two `/retry` cases (:1681,
:1845) STAY; cmt 1506, 1708.

`store/InvoiceSyncReplayIT.java` - partnerDownParksByReferenceAndTheRetryDeliversOnceThePartnerIsBack:167 - MIXED -
264/265 - 168-186 (parked by reference, the durable DLQ row, the FAILED row) survive, 187-201 (`replayParked`,
REPLAY_OF, the replay's timeline) GO. replayFromStepFourOfADeliveredMessage...:205 - GOES. The class name goes.
`store/RetentionIT.java` - cmt 586, 767, 1148 - STAYS (store-level retention against DLQ rows).
`queue/PgWorkQueueRestartIT.java` - aRowOfAReplayIsNotRestarted:140 - GOES - 265. `queue/DlqRestartIT.java` cmt 62.
`flow/SftpIngressDedupeIT.java` - replayOfADedupedMessageRunsAsANewMessage:231 - GOES - 264/265.
`flow/DlqBodiesIT.java` - aMessageParkedOnOneNodeIsRetriedFromAFreshStoreAfterARestart:334 and
theLastAllowedAttemptParksItsIngressWhichAFreshStoreRetries:419 - MIXED - 264/265 - "a parked ingress is durable by
reference and a fresh store reads it" survives; `replayParked` and REPLAY_OF (358-364, 448-449) GO.
`flow/StepBodiesIT.java` (23 hits) - aReplayFromStepThreeAfterARestartReproducesTheStepThreeInputByteForByte:402
(step bodies durable and byte-equal after a restart; vehicle: GET `/steps/{n}/body` or the store),
aRerunOfAStampingStepKeepsEachAttemptsRow...:439 (the latest attempt's row and bytes; vehicle: GET with the attempt
parameter), aBodyAboveTheBoundIsRecordedMetadataOnly:503 (metadata-only recording; vehicle: GET's 404) - MIXED -
264/265. Lines 225-227, a crash hook on `REPLAY_OF.isEmpty()` in a shared fixture - GOES - 265.

`deploy/`
- `DeploymentsReplayCloseTest.java` - one case :89 - GOES - 264. Not verified: whether a stop-deadline case for a held
  delivery exists elsewhere.
- `DeploymentsProducersTest.java` (52 hits) - aSwapClosesTheReplacedReplayOnceItsRunEnded:382,
  closeClosesEveryReplaysProducers:423 - GO - 264 (delivery-side twins likely `DeploymentsRedeployTest:1355` and :216
  here, inferred). The count cases :154 (7/6/5), :188, :216, :246, :302 - MOVES - 264 - only the literal counts and
  messages change.
- `DeploymentsRedeployTest.java` :398 (count 6), :476, :647; `DeploymentsRollbackTest.java` :354, :516, :977;
  `DeploymentsProducersEdgeTest.java` :61, :76; `DeploymentsRollbackEdgeTest.java` :390 - MOVES - 264 (counts).
- `DeploymentsRestoreTest.java` :358 (404 asserts the replay present), :439, :475 - MIXED - 264.
- `DeploymentsSecretsTest.java` cmt 303 - STAYS.

`journal/` - `ReferenceEnqueueIT.java` aReplayEnqueueCopiesItsSourcesRefInOneStatement:165 - MOVES (inferred) - 264 -
an enqueue by reference copies the source's ref in one statement (twin: aDlqRetryEnqueueCopiesTheParkedRef:189); not
checked whether production uses that overload only for the replay. STAYS: `GiveUpRaceIT.java` (a name),
`PgBodyRefsIT.java` cmt 40, `PeerProtocolTest.java` and `peer/RawPeer.java` (a peer link's replayed frame),
`PgPromotionLedgerIT.java:299`.

The row's two claims, checked by scout A:
- `ReplayRetentionIT`: a parked row and its body outlive the retention (`Retention.java:33-36`, :44, :164). But the
  fixture `shop` is a sync flow and delivers nothing: `/retry` needs an at-least-once flow and a parked row with an
  `attempt` of at least 1, otherwise 422. And the GET of an aged body whose rows are gone answers 404 "no step n
  recorded" (`AdminServer:626-628`); "(removed by retention)" appears only when the row stays and the bytes are gone.
- `LostBodyAdminIT`: GET 410 is today's behaviour (`AdminServer:642`). `/retry` maps `BodyLostException` to 409 at
  unit level (`AdminDlqRestartTest:249-251`); no IT pins it over Postgres. The same fixture issue: its rows are
  `Props.empty()`, step 1, a sync flow.
`/retry` cases whose expected text names `/replay`: 4 cases, 6 call sites (listed above).

Totals (scout A): 31 files; GOES 30, MOVES 21 (14 of them producer counts), MIXED 13, STAYS about 15.
Not read by scout A: most method bodies (the notes come from the hit lines, names and Javadoc); the method holding
`StepBodiesIT:225`; whether the virtual-thread, 503 and same-id properties already have `/retry` cases.

## C. The architect's reading (2026-11-16) - the list as approved, by row

The two scouts did not read most method bodies. So the list below fixes which row owns a case and what must survive;
the test-author reads each body before it deletes or moves a case and says in its hand-back where it found otherwise.

### FIX-263 - the admin routes (runtime's admin tests, `NodeIT`, `NodePoolWaitIT`; core's `Replay` stays, tested)

1. Deleted - they pin only the withdrawn routes (named in the commit body with D-156):
   - `AdminServerReplayTest` :479, :499, :517, :537, :555, :576, :605, :635, :704, :725, :747, :780, :836, :879, :907,
     :929, :960 and the replay helpers; the class goes once its survivors and its shared fixtures have moved.
   - `AdminServerReplayRaceTest`, all four cases; the class goes.
   - `NodeIT:1567`, `NodeIT:1715` (their `/retry` twins :1681 and :1845 stay); `NodePoolWaitIT:616`.
2. Moved - the property survives, its vehicle changes:
   - `AdminServerReplayTest:662`, the permit that comes back: name the `DeliveryTest` case that pins it
     (`Delivery.admission()`, scout B: 1488-1785); a new case through the delivery door only if none does.
   - `AdminServerReplayTest:685`, the run is off the event loop: `/retry`'s handler runs on a virtual thread, as a case
     of `AdminDlqRestartTest` - unless one exists, then name it.
   - `AdminServerReplayTest:802`: `/retry` answers 401 without the token - the same.
   - `AdminServerReplayRaceTest:230`, the same-id guard: `/retry`'s twin is "a second `/retry` of an id answers 404 and
     nothing is enqueued twice" - name the existing case or add it (a characterization if green today; say so).
   - `AdminServerBrowserGuardTest` :189, :224 and its route list 150-159: the guarded POST becomes `/retry`.
   - `NodePoolWaitIT:641`: `/retry` refused by the work pool answers 503 - only if that is green on today's code. If it
     is red today it is FIX-182's behaviour fix, not this row's: the case then goes and the hand-back says so.
   - `ReplayRetentionIT:211` becomes the positive case, in a class whose name does not hold the word: an at-least-once
     flow, a message parked by its worker (attempt at least 1) and aged past the retention; after a retention run
     `/retry` answers 202 and the message is delivered; a GET of an aged body of a finished message answers 404.
   - `LostBodyAdminIT:214`: over Postgres, `/retry` of a parked message whose body is lost answers 409 and the DLQ row
     stays; the GET of the lost body answers 410; the other message's GET answers 200. An at-least-once flow and a
     worker's park, as scout A found.
   - The shared fixtures of `AdminServerReplayTest` move to a test-support class of the same package.
3. Expectation changed - a `/retry` refusal says why and names no other route:
   - `AdminDlqRestartTest:224`, `:230` (helper namesReplay:211, the comment at :67); `AdminDlqRestartIT:321`, `:345`
     (helper :276). The row with `REPLAY_OF` at `AdminDlqRestartIT:326` and the words ", a replay" stay: FIX-265's.
4. New, red first: `bothReplayRoutesAnswerAsUnknownRoutes`, `noRetryRefusalNamesAnotherRoute` (the packet).

### FIX-264 - core, deployments, schema, simulation (its packet is written from this list after a second scout pass)

- Core: everything of section B under `platform/core`. `ReplayTest` does not go whole: :224, :261, :313, :367, :658,
  :921, :1063 and perhaps :953, :997, :1092 carry properties that survive and move to the worker run, the delivery
  pipeline or `/retry`. Their bodies are unread: a scout reads them case by case before FIX-264's packet.
  `FlowBuilderTargetTest:496` and `RecorderTest:241` join the row's list.
- Runtime: `InvoiceSyncReplayIT`, `SftpIngressDedupeIT:231`, `DlqBodiesIT` :334 and :419, `StepBodiesIT` :402, :439,
  :503 and the crash hook 225-227, `ReferenceEnqueueIT:165`, `deploy/` - `DeploymentsReplayCloseTest`,
  `DeploymentsProducersTest` :382 and :423 and the counts, the counts of `DeploymentsRedeployTest`,
  `DeploymentsRollbackTest`, `DeploymentsProducersEdgeTest`, `DeploymentsRollbackEdgeTest`, the probe of
  `DeploymentsRestoreTest`.
- The simulation has no replay action (`Action`: SEND_SYNC, SEND_ALO, RESEND, POLL). Only
  `Check.REPLAY_YIELDS_RECORDED` is declared, and no test or pinned seed refers to it: the row's text is corrected.

### FIX-265 - the api prop and the restart statement's clause

- `EnvelopeTest:88`; `PgWorkQueueRestartIT:140`; `AdminDlqRestartIT:321`'s row with `REPLAY_OF` and the words
  ", a replay"; every other use of `REPLAY_OF` left in a test after FIX-264. The contract case the row asks for does
  not exist yet (scout B).

Stays as it is, in every row: the comment-only and other-meaning hits listed in A and B.
