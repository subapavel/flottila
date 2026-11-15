# FIX-201 — the reviewer's pass on the audit, round 1 (2026-11-15): FAIL, both halves

Two reviewers (class B), read-only, main at 6cd8f2ed. Their hand-backs, as received. The claims they judged:
`agent/work/FIX-201-audit.md`. Nothing below is corrected in the ledgers yet unless a later commit says so.
"Branch D-n / branch FIX-n" is an id of the side branch; main uses every one of those ids for something else.

## Code and test commits (19) — review: FAIL

| commit | reviewed verdict | evidence on main | against the claim |
|---|---|---|---|
| 0adcbed | LOST | Delivery.java:176,:218 catch Exception, :321 RuntimeException; SequentialIntake.java:66,:72 RuntimeException; none of the 12 test names anywhere; FIX-204 fits | agrees |
| 19ee0fa | LOST | RedeliveredTimelineTest is gone (only a Javadoc mention, RecorderTest.java:345); FIX-207 ready | agrees; FIX-207's done-when is wrong for case 2 |
| 232ed9d | CARRIED | Producers.java:65; ProducersCloseTest.java:282,:313,:350 | agrees |
| 261c657 | CARRIED | TrailTest.java:402-403, :424-425 | agrees |
| 27d4b9b | LOST | the case name, "stays scheduled", "loss of batching": 0 hits in platform/core/src; FIX-205 ready | agrees |
| 47c9b85 | CARRIED | Recorder.java:89-98; RecorderTest.java:433,:445,:478,:504,:523,:535 | agrees |
| 4cbc121 | LOST | Deployments.java:884, :928 keep 3 arguments, no built.removeAll; both case names 0 hits; FIX-206 ready | agrees |
| 7dc0555 | PARTLY CARRIED | ci/ledger_check.py and ci/test_ledger_check.py:104-237 (19 cases) on main; both ci.yml steps gone (ci.yml has only :83, :88) | DISAGREES (claimed CARRIED); the lost half is FIX-199 |
| 94b6d02 | CARRIED | Producers.java:59 catch Throwable; ProducersCloseTest.java:149-265, 7 cases | agrees |
| 9a9c916 | PARTLY CARRIED | Recorder.java:87; RecorderTest.java:305,:327,:367,:400. Lost: its rewrite of RedeliveredTimelineTest case 2, `aMessageWhoseFirstRunCrashedShowsTheCrashAsAFailedRowStitchedBeforeTheRedeliveredRun` | DISAGREES; no row names this commit |
| 9c13830 | LOST | `aWarmupWhoseFirstSavesAreRefused…`, `it_refuse_first_saves`: 0 hits; NodeBenchMainIT.java:73 asserts warmupFailed==0; FIX-208 ready | agrees |
| 9ce63c7 | LOST | DirArchiveStore.java:20 takes (Path) only; S3ArchiveStore.java:34 only requireNonNull(prefix); 9 test names 0 hits; FIX-196 ready | agrees |
| a31a26d | LOST | DECISIONS.md:49 raw `direction in|out`, :53 raw `OK | FILTERED`; FIX-197 ready | agrees |
| a35e778 | PARTLY CARRIED | FIX-54 half on main (TrailTest.java:400,:420,:423). FIX-57 half lost: the 3 exact-text assertEquals on first.error(), second.error(), second.problems() are gone; DeploymentsRecordTest.java:334/:360 keep contains/assertProblem only | DISAGREES; row FIX-57 is `ready`, its lines stale (:445/:446; today Deployments.java:891/:894), the loss not mentioned |
| bf29933 | CARRIED | TrailTest.java:646, :664 | agrees |
| e65f616 | CARRIED | TrailTest.java:367-373 | agrees |
| e835f43 | LOST, re-applied in part by FIX-195 | catches back (BatchingWriter.java:205,:277,:346,:385) with WindowRefusalTest:196,:217,:252,:269, ErrorContainmentTest:120,:161, LogTest:54, PropertyTest:335. Missing by name and text: aWindowThatFlushDroppedNeverFiresSoANewerPartialBatchWaitsForItsOwnWindow, anErrorFromTheSinkInsideACompletionFailsOnlyThatBatchAndTheRestStillCommits, theConcatenationOfTheBatchesInHandOffOrderIsArrivalOrderAcrossQueueAndWaiters | agrees LOST; "re-applied" overstates |
| e8bc787 | LOST | NodeBenchMain.java:435 keeps warmedUp a local, :438 puts only warmupFailed; FIX-208 ready | agrees |
| eb15a31 | LOST | Parking.java:53/:63 catch Exception, :95 RuntimeException, :65 addSuppressed unguarded, :59 park call outside the try at :61; 8 names 0 hits; FIX-203 ready | agrees |

Totals re-derived: 6 CARRIED, 3 PARTLY CARRIED, 10 LOST (claimed 9 / 10).

What must become true:
1. 7dc0555 recorded PARTLY CARRIED; FIX-199 cites 7dc0555 as the commit whose CI wiring was lost.
2. 9a9c916 recorded PARTLY CARRIED; FIX-207's done-when restores case 2 in 9a9c916's form and cites it - 19ee0fa's case 2
   asserts "no row at step 3", which Recorder.java:87 on main contradicts, so a verbatim restore would be red.
3. a35e778 recorded PARTLY CARRIED; row FIX-57 says it was lost at the 2026-11-14 merge (a35e778), lines re-anchored.
4. e835f43: FIX-205 absorbs the never-fires twin and the other two cases, or a recorded reason says FIX-195 / D-146
   superseded them.
5. The audit file's totals and row FIX-201's "9 CARRIED, 10 LOST" corrected.

Notes, not blocking:
- Borrowed ids: rows FIX-203 (FIX-142), FIX-207 (FIX-131), FIX-208 (FIX-134, FIX-128), FIX-197 (FIX-132) cite branch ids
  that name other tasks on main. Carried code too: Producers.java:59 cites "FIX-141" (on main the ACCEPT_LOSS listing).
- Row FIX-54 reads `ready` although its assertions are on main. [Architect: a35e778 closed FIX-54 (review PASS r1) and
  FIX-56 (no code change - FIX-21 had applied the paused pattern; review PASS r2) on the branch; main lost both closures.
  On main no unpaused `compose(res -> res.body())` remains in AdminServerTest, AdminServerDeployTest, AdminServerWorkTest.]
- FIX-205 names `BatchingWriterErrorTest`; since FIX-195 the class is BatchingWriterErrorContainmentTest. 27d4b9b's case
  asserts "exactly one WARNING" per writer; D-146 logs per episode plus recovery: adapt, not a verbatim restore.
- FIX-203's done-when says the park call "stays inside the try"; on main it is outside (Parking.java:59 vs :61): "moves".
- Not checked: whether the 3 missing e835f43 behaviours are pinned on main under other wording; 9ce63c7 and 19ee0fa were
  judged by test names and production signatures, not line by line.

## Doc-only commits (9 audited, 7 "superseded") — review: FAIL

| commit | reviewed verdict | evidence | row |
|---|---|---|---|
| 45e9dd4 | LOST | its FIX-62 evidence cell is gone; BACKLOG.md:420 says `ready`, matching the reverted code | FIX-206 (fits) |
| 59bbad3 | PARTLY LOST | F-30's third correction (the permit is held until saveTimeout, not leaked) nowhere on main; the "NACK names the Error" amendment returns with 0adcbed's tests; the FIX-141 packet is moot (94b6d02 CARRIED); the FIX-142 packet goes with FIX-203 | FIX-211, FIX-204, FIX-203 (fit) |
| 6a0ee21 | SUPERSEDED | a one-off "write a stub" red instruction for FIX-139, done (7dc0555); main's standing rule says the opposite (.claude/agents/test-author.md:21-23) | none needed |
| 7ccbaad | PARTLY LOST | (a) Parking Javadoc ("throws synchronously", Parked.of) and the citations at Parking.java:53/:63/:95 absent -> FIX-203 fits. (b) its ParkingTest comment fix (a trace names where the Error was constructed) postdates eb15a31, so restoring eb15a31's tests brings back the wrong comment -> FIX-203 does not cover it. (c) branch D-113, the catch-width rule (a TRANSLATION clause catches Throwable, a POLICY clause stays narrow) with ten verdicts: in no file on main, not in the archive extract -> no row. (d) branch FIX-149 and FIX-150, two live defects: Deployments.java:1786 `quietly` (IOException or RuntimeException) and AdminServer.java:516 `answer` (RuntimeException) -> NONE. (e) NOTES and test-author-memory hunks lost, minor | FIX-203 (partly); NONE for (b)-(d) |
| 8105b0f | PARTLY LOST | F-30 itself -> FIX-211, which does not carry the addendum's 165-clause sweep result; the per-mode FIX-136 done-when -> FIX-204; packets FIX-136/137/138 -> FIX-204/205; the "Mutant runs" note partly on main (NOTES.md:302-305; implementer MEMORY.md:113) | FIX-211 (partial), FIX-204, FIX-205 |
| 99d8d04 | LOST | the eight comments absent; sites at the stated widths (Deployments.java:1026/1134/1361/1404/1475/1566, AdminServer.java:846, Retry.java:46; Javadocs Retry.java:26-27, AdminServer.java:835) | FIX-210 (fits; must re-record D-113) |
| c42887a | PARTLY LOST | takeLocked/drainLocked Javadoc and branch D-112 missing (main's takeLocked Javadoc: BatchingWriter.java:300-304) -> FIX-205. Branch FIX-144 lost: the load-dependent flake in PollingConsumerContractSelfTest.aRemovalBeforeTheAnswerIsAwaitedFailsEveryCaseThatKeepsAFile:366 (STOP_WAITS missed 1/20 under load); the test is on main | FIX-205; NONE for FIX-144 |
| cbd563b | SUPERSEDED / CARRIED | the FIX-139 packet's rule is in ci/ledger_check.py:4-5; FIX-140's code (47c9b85) CARRIED; the rest is branch state | none needed |
| e885ed8 | CARRIED | agent/packets/CONSIDER-13-fable-brainstorm-prompt.md on main, same text (:125-135); superseded in purpose by OPERATIONS-BRIEF section 1 | — |
| 07bbac6 | SUPERSEDED | main kept docs/PROGRESS.md; the six entries are at :1071-1118. The note "never pair two lists by their order" is lost (minor) | — |
| 45d4100, 63e2b9c, 7863381 | SUPERSEDED | HANDOFF state only; no fact main lacks | — |
| 585b6f4 | PARTLY | branch D-104 -> main D-146 (DECISIONS.md:154); the section 6 draft, branch D-103, F-27 -> CONSIDER-15 and the archive. Branch FIX-122 (testkit PIT 74 %, 577/777) lost, its id names something else | CONSIDER-15; NONE for FIX-122 |
| a0b3f74 | PARTLY | branch D-102 -> CONSIDER-15. F-26 NOT carried: the live section 5 table puts `http.status` under headers (ARCHITECTURE.md:276) while the code writes a prop (HttpComponent.java:501, :240) - a doc defect, not design input | NONE |
| 94fceab | PARTLY | branch D-105, D-107..D-110 -> CONSIDER-15 as "designer input" only; branch D-108's attempt keying -> main D-140; branch D-106 lost (CONSIDER-15 skips it); branch D-107 ("replay of a successful message goes out", owner, 2026-11-14) is newer than main's D-136 and main still promises "replay or resend from any step" (ARCHITECTURE.md:452): an open conflict, not a supersession | CONSIDER-15 (unfit for owner decisions) |

Totals re-derived for the audited nine: 1 CARRIED (e885ed8), 2 SUPERSEDED (6a0ee21, cbd563b), 6 LOST or PARTLY
(45e9dd4, 59bbad3, 7ccbaad, 8105b0f, 99d8d04, c42887a). The claimed "1 CARRIED, 8 LOST, 0 contradicted" does not hold.

What must become true:
1. A row: AdminServer.java:516 `answer` catches Throwable and Deployments.java:1786 `quietly` takes Throwable (branch
   FIX-150 and FIX-149, live today), under free ids.
2. FIX-210 says its new D-row re-records branch D-113's rule with all ten verdicts, the two WRONG ones included.
3. A row: the PollingConsumerContractSelfTest STOP_WAITS flake (branch FIX-144) and the 39.6 s patience cost (branch
   FIX-148, seen only as context).
4. A row: the section 5 `http.status` table fix (branch F-26) under a free F-id.
5. Branch D-106, and D-107 / D-109 / D-110 as owner decisions, re-recorded under free ids, or a row that settles the
   D-107 against D-136 / ARCHITECTURE.md:452 conflict with the owner.
6. FIX-203 also restores 7ccbaad's ParkingTest comment. FIX-205's line cites (249-253, 213-217) are stale and its invariant
   names only batchSize while main's loop has `inFlight == 0` and `queuedBytes >= batchBytes` (BatchingWriter.java:307-308):
   re-derived, not pasted. FIX-211 carries F-30's sweep result.
7. A row, or an explicit drop, for branch FIX-122 (the testkit PIT 74 %).
8. Row FIX-201 corrects its totals and names the rows above.

Not checked - for the second pass:
- the bodies of branch F-28 and F-29 (94fceab) against main and agent/archive/branch-design/F-29-branch.md;
- 94fceab's packets (FIX-123, FIX-130, FIX-62, FIX-71) and its reviewer-memory hunk;
- 8105b0f diff lines 135-470 (the FIX-136/137/138 packet bodies), judged by headings only;
- 7ccbaad's NOTES fact "no maven-javadoc-plugin" against the poms;
- whether OPERATIONS-BRIEF supersedes branch D-106 or D-110 (grepped only, no hit);
- 585b6f4's PROGRESS hunk, the full branch D-103 text against main, the bodies of the handoff hunks beyond their added lines.

Also seen, outside these commits: Recorder.java:87/:91 cites "D-111" for the recording rule; main's D-111 is the
intake/journal-force decision - a borrowed id in carried code.
