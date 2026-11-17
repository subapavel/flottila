# MAIN-1 — scout's facts on the four red tests of BASE-1 (2026-11-15, read-only)

Paths are the ones after BRAND-1 commit A (`bench/`, `platform/runtime/`); packages were still
`io.flottila.*` when this was read. Logs: `agent/work/BASE-1-verify.log` = run 1,
`-fae.log` = run 2, `-all.log` = run 3.

## 1. LedgerIT.aKilledNodeLosesNoAckedMessage (FIX-215) — bench/src/test/java/.../bench/LedgerIT.java:128-132; run 3 record verify-all.log:81433-81464
- 66 = distinct ACKed ids with a final-step row created after killedAt and ingress created before it (LedgerIT:128-130). 60 = LedgerMain.java:280 `missing(ackedAtKill).size()`, read after killedAt (:278-280). Record: workAtKill 66, unfinishedAtKill 60, killedAt 02:58:12.087754Z (log:81437-81439).
- Node 1 is dead before killedAt (LedgerMain:270-278); node 2 starts after the snapshot (:284-287). Node 2's log (log:81419-81432) shows recovery and retention only. Node 1's own log file (LedgerMain:258) was not read.
- The FIX-184 boundary-race hypothesis (BACKLOG row FIX-184) is ruled out by the SQL: a messages row takes created_at from the column default now() (PgMessageStore.java:53; MigrationsIT.java:140), so a node-1 row is before killedAt and cannot satisfy LedgerIT:129.
- What remains: only node 2 writes rows after killedAt. All 60 unfinished ids get one (missing=[] at log:81446), so at least 6 ids that already had a final row before the kill got a second final row from node 2. Partner calls 207 vs 200 distinct ids (log:81449-81450). The test's author knew: LedgerIT.java:150 "a lease re-run's duplicate row shares its id"; LedgerMain.java:63-64 says node 2 re-runs what node 1 left leased.
- The final-step insert and the work-row delete are separate statements (PgWorkQueue.java:205 COMPLETE); a kill between them leaves the work row. Whether they share a transaction was not verified.
- Reading: no message was lost or unaccepted (finalRows distinct = 200 = sent). The assertion at :132 assumes "finished after the kill" equals "unfinished at the kill", which an at-least-once re-run breaks. Whether the design allows that re-run was not read.
- History: LedgerIT:132 failed before (docs/PROGRESS.md entries; FIX-184, "earlier 5 of 5 in tree"). Not new.
- To observe: on failure, print the ids with count(*)>1 of final rows and whether each had a final row at the snapshot.

## 2. ReplicaStartTest.theStartRoomCheckCountsTheReplicas (FIX-213) — platform/runtime/src/test/java/.../journal/ReplicaStartTest.java:142-162
- The failing line is :159 (the byte count), not the room check (:150-155, which passes). Expected 3465 = segment and replica files on disk after open; actual 3514 = Journal.bytes(). verify-fae.log:55169-55177; verify-all.log:53996-54004. Run 1 passed (verify.log:50988).
- The 49 bytes: Journal.java:340 (constructor) calls preopen(upcoming); preopen (:906-908) adds format.headerLength() to bytes before the file exists (javadoc :902-904); the file is created later on a virtual thread (:918-919; Journal.java:79). headerLength for a 6-character node name is 43+6 = 49 (ReplicaStartTest.java:98). So bytes() counts a reserved header whose file may not be on disk yet when the test measures (:158-159). Not verified by a trace.
- Ruled out: the real disk's usable space (the room check uses JournalFreeSpaceTest.SpaceFiles); block size; a shared temp dir (@TempDir per method).
- Surefire: one JVM, sequential (no forkCount or parallel set).
- To observe: log sizes at return and after awaiting the pre-open.

## 3. StepBodiesIT.aRerunOfAStampingStep... (FIX-212) — StepBodiesIT.java:425-442; rowsOf :323-324; until :370-377
- The message names the expected count (5), not the observed one; the observed count is in no log. The assertion is an exact equality (:324 count == n). The 5 (:441-442): ingress, stamp attempts 1 and 2, crash-first and three of attempt 2.
- Code reading says 6: crash-first throws Crash (:226-227), Crash extends Error (:135); Recorder catches Throwable and records FAILED for the throwing step (Recorder.java:87-90) under RecordingPolicy default ALL (RecordingPolicy.java:21, :57; FlowDefinition.java:113). So attempt 1 also writes a crash-first row: ingress 1 + attempt 1 (stamp, crash) 2 + attempt 2 (stamp, crash, three) 3 = 6. Not verified in the database.
- Path: Trail.java:163 recordWaiting -> PgMessageStore.java:87-88 -> BatchingWriter (PgMessageStore.java:77); test options BatchingWriter.Options(100, 10 ms, 2, 1000) with a real VertxTimer (StepBodiesIT.java:256). The rows do go through BatchingWriter (FIX-195 / D-146), but a 10 ms window cannot explain 20 s and the mismatch does not depend on batching.
- The 5 was set by FIX-175 (reviewed PASS r4). "lost at 20.10 s" before FIX-195 landed is the old HANDOFF's statement; no file confirms it.
- To observe: on failure, print `select step, attempt, outcome from messages` for the id. Git history of FlowWorker:668 and Recorder:88 against the FIX-175 review was not read.

## 4. PeerProtocolTest.aLinkPausedByAnAbandonedConsumerIsClosedAfterATimeoutAndItsReadsFail (FIX-214) — PeerProtocolTest.java:741-770
- Failed once (verify-fae.log:45357-45365), passed in runs 1 and 3.
- The reader is node-b dialing node-a, so node-b is the DIALER. Time is fake (bClock advanced twice, :763-764); waits are bounded latches.
- The reported side is decided in the test's FrameRelay.java:136, :157-158, :174-175: closedBy = the side of the first EOF or reset that a pump thread sees. An attribution race between the two pumps, not the initiator. The closure itself is guaranteed (the line-767 wait completed).
- Inference, unverified: "toDialer read 17, forwarded 16" suggests the relay's write to node-b's dead socket failed on the LISTENER-side pump before the DIALER-side pump read EOF.
- Whether the protocol promises which side reports the close was not read (PeerLink, PeerReads).
- To observe: assert closed(0) and node-b's own close reason, or record which pump saw the first event in FrameRelay.

Not read: node 1's log; ARCHITECTURE; PeerLink/PeerReads; SegmentFile.create; git history; the database.
