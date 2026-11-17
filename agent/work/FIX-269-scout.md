# FIX-269 - the scout's facts (2026-11-17, HEAD 8d3daf37; read-only, no judgement)

Saved when the session stopped, before FIX-269 had a packet. Paths under `platform/`; main is `src/main/java`.

1. `Recorder.start` - `core/.../core/record/Recorder.java`:49 `public Stage start(RecordingPolicy policy, String name)`,
   the only overload; no step parameter (the step is the envelope's own). `INGRESS` :34. Final class, no interface.
   Main callers: `FlowBuilder.java`:448 only, always with `Recorder.INGRESS`. Tests: `RecorderTest.java`:251 in
   `aStartRowAtALaterStepRecordsItsOwnStepWithCpuUnknownAndTheNextStepIsMeasured` (:245) - the only later-step start.
   Coverage counts in `rail8-core` (90 % floor).
2. `Delivery.admission()` - `core/.../core/flow/Delivery.java`:147, package-private; Javadoc :146 already says
   "nothing in main reads it". Main callers: none. Tests: `DeliveryTest.java`, 12 lines in 9 methods (:1386, :1389,
   :1422, :1551, :1571, :1652, :1700, :1701, :1732, :1799, :1847, :1848). FIX-264's and FIX-262's packets say "nine".
3. `PgWorkQueue.enqueue` (`runtime/.../runtime/queue/PgWorkQueue.java`): :386 the interface's four-argument one;
   :415 `(SqlClient, ..., holder, lease)`; :426 `(SqlClient, ..., bodyNode, JournalRef, holder, lease)`; :440 the
   SHORT one `(SqlClient, messageId, flow, step, partitionKey, BlobKey)`; :449 `(SqlClient, ..., BlobKey, holder,
   lease)`. The short one delegates to :449 (:441); Javadoc :435-439. Main callers of the short one: none
   (`PgIntake` calls :415 at :471, :449 at :473, :426 at :478). Tests that call it: `ReferenceEnqueueIT.java` :177,
   :198, :207, :212, :218, :233, :248; `LostBodyReadIT.java` :197, :242, :279; `GiveUpRaceIT.java` :102.
   `PgIntake.java`:51's Javadoc links to it.
4. `Dlq.remove` (NOT this row's: it goes with cancel, FIX-277) - `core/.../core/dlq/Dlq.java`:31, Javadoc :30 "Nothing
   in main calls it"; `PgDlq.java`:147, `InMemoryDlq.java`:62. Main callers: none. Tests: `InMemoryDlqTest.java` :43,
   :44, :91, :92; `PgDlqIT.java` :136, :137, :164, :187, :188; test doubles `FailingDlq.java`:48, `ParkingTest.java`:364.
   There is no `Dlq` contract suite in `testkit` (the row's acceptance names one: the two test classes stand in).
5. `FlowBuilder.java` (`core/.../core/flow/`), class Javadoc :63-115: one line over 160 characters, :86 (212).
6. `DlqBodiesIT.java` (`runtime/src/test/.../runtime/flow/`): `inputs` declared :122, `record Input` :127, written
   :194, never read.
7. "replay" in main code under `platform/`: six hits, none the removed operation - `sim/.../SimSeed.java`:23,
   `SimConfig.java`:13, `Verdict.java`:52 and :64 (the simulation's seed), `runtime/.../blob/TieredBlobStore.java`:44
   (a spill prefix), `runtime/.../journal/peer/PeerSession.java`:11 (a peer frame). Outside main:
   `agent/patches/P2-10b-wip.patch`:1446 still uses `Envelope.REPLAY_OF`.

No test lists the methods of `Recorder`, `Delivery`, `PgWorkQueue` or `Dlq` by name; `archtest`'s `Rules.java`
:70-127 are package-level rules.
