# FIX-262 — the scout's list (2026-11-15), as handed back

Written to this file by the architect; the scout writes no file. Tree: 616ee25b. Counts are file counts from a
case-insensitive grep for `replay`. "Inferred" and "not read" are the scout's own marks.

## Counts

- `platform/`: 949 hits in 77 files (api 5, core 20, runtime 38 [main 8 + test 30], sim 10, schemas 1, components/mock 1,
  cli 0).
- Outside `platform/`: 564 hits in 77 files, mostly `agent/packets`, `agent/patches`, archives and findings (history, not
  listed). No hits in `ci/`, `.github/`, `site/`, `bench/` sources, `docs/WORKFLOW.md`, `docs/QUICKSTART.md`, `AGENTS.md`.
  No OpenAPI file and no CLI replay text.
- Groups, by file:
  - A (the replay of a message as an operator operation): api 2 (`Envelope`, `Props`), core 8 main + about 10 test,
    runtime about 6 main + about 25 test, sim 1 enum value.
  - B (a "replay" that is a retry under the same id): no separate code. The route named `/dlq/{id}/replay` mints a NEW
    id, so it is A. The same-id retry is `/retry` (answer 2).
  - C (journal or crash recovery): `PgPromotionLedgerIT:299` ("standby replayed the first five ids", WAL replay), a test.
  - D (another meaning): sim 9 files (the seed "replay command", `SimReplayTest`, `PinnedSeedsTest`), `PeerSession.java:11`,
    `RawPeer.java:172-173`, `PeerProtocolTest`, `TieredBlobStore.java:44`, `MockComponentTest.java:127`,
    `PackageFoldersTest.java:521/526` (a folder named `replay/`).

## Group A, main code

- `platform/runtime/.../admin/AdminServer.java`: routes :411-412; Javadoc :94-163; fields :296-297; handlers `replay` :728,
  `dlqReplay` :745, `from()` :819, `ran()` :837, `refusal()` :854; `REPLAY_OF` read in `retry` :785/:787.
- `platform/core/.../flow/Replay.java` (the whole class, 53 hits): `from()` :81-116, `replayParked()` :119-164,
  `REPLAY_INPUT = "replay"` :53, `replayOf()` :259.
- `platform/core/.../flow/ReplayRefusedException.java` (Reason: STEP_OUT_OF_RANGE, UNKNOWN_MESSAGE, NO_DLQ_ROW, CLOSED,
  REMOVED_BY_RETENTION, BODY_LOST, NOT_RECORDED, METADATA_ONLY, STEP_BEYOND_FLOW, TOO_LARGE, BUSY);
  `RowNotRemovedException.java`.
- `FlowBuilder.java`:444, 455, 460-474 (the `replay()` factory; 23 hits, mostly Javadoc at :75-114, :214, :441-457, :494);
  `Delivery.java:146`; `Dlq.java:30` ("after a successful DLQ replay"); `Parked.java:18, 28`; `Trail.java:34-35`;
  `WorkQueue.java:274`.
- runtime: `Deployments.java` (25 hits: record `Flow.replay` :377-407, `replay(String)` :492, close :666, build :1518-1537);
  `Node.java:110`; `package-info.java:2`; `PgWorkQueue.java:53, 282-292, 420, 598` (comments; `enqueue(client,..,body)` :424
  was written for replay's reference copy, but its main callers are `PgIntake:471-478` - inferred);
  `BodyLostException.java:7-8`.
- api: `Envelope.java:38-39` `REPLAY_OF = new Key<>("replayOf")`, :43; `Props.java:8`; `Body.java:48` (comment "retry,
  replay, snapshot").
- schemas: `flow.schema.json:30` (the timeout's description: "each message replay or DLQ replay").
- sim: `Check.java:5` `REPLAY_YIELDS_RECORDED` (declared only; `SimSelfTest.java:32` says it "comes with P2-10b").
- `deploy/postgres/V1__m1_schema.sql:37`: the comment "Replay loads the step-k envelope from messages".

## Group A, tests

`@Test` count in the class; the count of replay cases is exact only where the methods are named.

- core: `ReplayTest` 22 (11 method names with "replay"); `ReplayEdgeTest` 2.
- runtime, dedicated: `AdminServerReplayTest` 20; `AdminServerReplayRaceTest` 4; `ReplayRetentionIT` 1;
  `InvoiceSyncReplayIT` 2 (:205); `DeploymentsReplayCloseTest` 1; `LostBodyAdminIT` 1 (:214, replay and DLQ replay 409).
- runtime, mixed: `NodeIT` 39 total, 2 replay cases (:1567, :1715); `NodePoolWaitIT` 17 total, 2 (:616, :641);
  `SftpIngressDedupeIT` 2 total, 1 (:231); `StepBodiesIT` 5 total, 2 (:402, :439); `DlqBodiesIT` 5 (not read).
- Part-replay: `AdminServerBrowserGuardTest:118,159` (a POST `/replay` route as a fixture); `AdminDlqRestartTest` 6 total
  (:211 `namesReplay` and four more lines with `/replay`); `AdminDlqRestartIT` 6 total (:276 `namesReplay`);
  `PgWorkQueueRestartIT:140` `aRowOfAReplayIsNotRestarted`; `ReferenceEnqueueIT:165`; `DeploymentsProducersTest` :382,
  :423; `FlowBuilderTest` :397, :442; `FlowBuilderContextTest:222`; `FlowBuilderFenceTest:44`; `FlowBuilderTargetTest:496`;
  `RecorderTest:241`; `EnvelopeTest:88`; `TrailTest`, `ParkedPartitionKeyTest`, `DeploymentsRollback/Restore/Redeploy/
  Edge/SecretsTest`, `AdminServerCpuTest:156`, `GiveUpRaceIT:34/100`, `PgBodyRefsIT:40`, `RetentionIT:586/767/1148`
  (comments, fixtures).
- Dedicated classes together: 22 + 2 + 20 + 4 + 1 + 2 + 1 = 52 `@Test`; the mixed classes need a case-by-case reading.

## Group E, documents

- `docs/ARCHITECTURE.md`:
  - §1: 174 "captured, searchable and replayable".
  - §2: 187 the backbone "hand-over, ingest and replay run on Postgres".
  - §4: 236 the table's column "Replay" (238 sync "from any recorded step", 239 at-least-once "yes", 240 at-most-once
    "metadata only"); 242 (the 422 texts name `POST /admin/dlq/{id}/replay`, "a new copy with a new id"); 250 (503 for a
    replay or DLQ replay run).
  - §5: 277 props "replay-of".
  - §6: 313 "a new copy `POST /admin/dlq/{id}/replay` (D-136)"; 327 Recording (replay from step N needs step N-1's
    output; dry replay; "replay from the latest OK attempt"; a message or DLQ replay runs the flow version deployed now;
    409 removed by retention; "replay from any recorded step works after a restart").
  - §7: 366 (a worker restart is "not a replay"), 367, 372 (Kafka "replay from the log"), 374 (replay of a removed body 409).
  - §8: 386 "replay from any recorded step"; 425 and 430 (replay waits for the last flush; OPS-2); 452 UI "replay or resend
    from any step".
  - §11: 612 (the sim's replay command; group D).
  - §12: 657 "recording, replay, admin API", 663 "Replay from P2-10b".
  - §13: 685, 689 "replay/resend from the UI".
  - §14: 718-719 (S3 "replay window"; Kafka "replay from the log"), 729 dry replay (CONSIDER-22).
  - Changelog rows: 42, 45, 46, 73, 95, 96, 102, 104, 115.
- `README.md:4` "searchable and replayable"; `README.md:10` "DLQ, replay and a Postgres work queue". `CLAUDE.md:12`
  "searchable and replayable" (the owner's file: a patch under `agent/patches/`).
- `docs/GATE.md:27-29` check 7 (`replay?from=3` as a new message marked `REPLAY_OF`, then `/retry`); :98-99 check 6 "sync,
  async, replay and DLQ retry"; `agent/packets/GATE.md` (1 hit).
- `docs/PROTOTYPE-VS-ARCHITECTURE.md:17, 28, 40` ("replay from k = run stages k..n with REPLAY_OF. Kept verbatim"), 59
  (Kafka replay).
- `docs/CORE-REWORK.md:34` (O-1 "replay" not on Kafka), :151. `docs/OPERATIONS-BRIEF.md:181` (dry replay).
  `HANDOVER.md:49-54, 78, 91-92, 108, 126` (curl examples for both `/replay` routes). `agent/NOTES.md:219-224` ("DLQ and
  replay (P1-05, D-045)"). `issues/ISSUE-2.md:16`. `agent/HANDOFF.md:8`.
- `docs/DECISIONS.md`: D-156 :164 (the decision), D-136 :144, D-045 :54, D-066/D-067 :75-76, and other rows with the word.

## Answers

1. **`POST /admin/messages/{id}/replay?from=n` is fully implemented, not a stub.** `AdminServer.replay` (:728): `from`
   must be an int >= 1, else 400. It reads the timeline (`messages.timeline(id)`), 404 if empty, and takes the flow from
   the first entry. `Deployments.replay(flow)`, 409 if the flow does not run here. It mints `replayId = ids.next()` - a new
   id - and calls `Replay.from(id, from, replayId)` (`Replay.java:91-116`) on the route's virtual thread: the step must be
   in 1..n; it takes the last OK snapshot of step `from-1` (:103), otherwise refuses NOT_RECORDED, METADATA_ONLY, or a
   removed or lost body; it takes an admission permit by the body's size; it rebuilds an envelope from the snapshot with
   the prop `replayOf=<old id>` (the attempt dropped) and runs the flow's steps `from`..end through the recorder, whose
   first snapshot is named "replay". New snapshots under the new id; the DLQ is not touched. 200 `{id, replayOf, from,
   outcome:"COMPLETE"}`; 502 PARKED or FAILED; 503 on a pool refusal.
2. **`POST /admin/dlq/{id}/replay`** (:745): `from` present gives 400; `dlq.get(id)`, 404 if absent;
   `Deployments.replay(flow)`, 409 if the flow is not here; 409 if the id is already in the node-local `replaying` set. It
   mints a NEW id and runs `Replay.replayParked` (:130-164): the envelope is built from the Parked row (the step-1 body by
   reference, headers, keys, props + `replayOf`), runs `parked.step()`..end, then `dlq.remove(oldId)`; a failed removal
   throws `RowNotRemovedException` (500). The old id's DLQ row is deleted, the new id has its own snapshots, there is no
   work-table row. A new-copy replay: group A, not B.
   **Every admin route on messages and the DLQ:** GET `/admin/messages` :399; GET `/admin/messages/:id` :400; GET
   `/admin/messages/:id/steps/:n/body` :401; POST `/admin/messages/:id/replay` :411; GET `/admin/dlq` :402; POST
   `/admin/dlq/:id/replay` :412; POST `/admin/dlq/:id/retry` :416 (`retry()` :772: the same id; `WorkQueue.restart` deletes
   the DLQ row and inserts a step-1 work row in one transaction, attempt k+1, 202 `{id, attempt}` - the retry the owner
   keeps). **There is no message cancel route, and none for "cancel" in admin or cli.** Related work routes: GET
   `/admin/work/held` :417, POST `/admin/work/:seq/release` :418, GET `/admin/work/lost` :419, POST
   `/admin/work/:seq/discard` :420 (a lost row's discard, audited); none is a message's cancel.
3. **Public types in `platform/api`:** `Envelope.REPLAY_OF` (public static `Key<String>`, name "replayOf",
   `Envelope.java:38-39`; Javadoc :43 "dropped by a replay"); `Props.java:8` (Javadoc); `BodyLostException.java:7-8`
   (Javadoc); `Body.java:48` (Javadoc). `REPLAY_OF` is also read by the same-id retry - `AdminServer.java:785` and
   `PgWorkQueue.java:292` (`props->'replayOf' is null`) - to refuse restarting a replay's DLQ row. `EnvelopeTest.java:88`.
4. **Migrations:** no replay-only table, column, index or enum value (all `deploy/postgres/V*.sql`: only the comment at
   V1:37). The persisted traces are the jsonb prop "replayOf" and the recorded step name "replay"
   (`Replay.REPLAY_INPUT`), a data value in snapshots. Migrations live in `deploy/postgres/`.
5. **Tests:** the list above.
6. **CLI:** `platform/cli` has zero hits: `Validate`, `FlowTests`, `Expectations`; no replay command, flag or help text.
7. **Dependents without the word:** (a) `FlowWorker.java:646` calls the static `Replay.rebuild(...)` for the same-id
   retry/resume - `Replay.rebuild` is shared with retry. (b) The recorder's snapshot per step with `bodyKey`, the rule
   "retention removes a body, so 409" (D-096, `ReplayRetentionIT`) and the text "skipping a step's body makes replay
   impossible" (ARCHITECTURE:327) exist partly for replay (inferred). (c) Parked rows keep the body "enough to replay
   that step" (`Parked.java:18`). (d) `Deployments` holds a `Replay` per flow and closes it on swap and undeploy (:377-407,
   :666). (e) The 422 texts of `/retry` say "use /replay" (`AdminServer.java:782/787/790`).

## Not read, not sure

- Per-case counts in `NodeIT`, `NodePoolWaitIT`, `StepBodiesIT`, `DlqBodiesIT`, `AdminDlqRestart(IT/Test)`,
  `DeploymentsProducersTest` and the other mixed classes are by method name only; their bodies were not read.
- Group C: only `PgPromotionLedgerIT:299` was found; no journal "replay on restart" in runtime main under that word.
- `docs/findings`, `docs/PROGRESS.md`, `agent/packets`, `agent/archive` and patches were not read (history). Findings F-8,
  F-10, F-11, F-20 mention replay and may be referenced as binding (not checked).
- `docs/DECISIONS.md` rows other than D-156 and D-136 were not read; the other rows with the word are unclassified.
- How `PgDlq` / `MessageStore` expose methods used only by replay was not read (the word does not occur in
  `runtime/store` main).
