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

## Second pass (2026-11-15, one reviewer, main at 92f4b212) — what round 1 left unchecked: INCOMPLETE (item E in part)

A. Branch F-28, the error-rate finding (94fceab diff :825-901): LOST. No file on main; findings-heads.txt has headings for
   F-26, F-27, F-29 only; main's docs/findings/F-28.md is the node lease finding. Its two answered parts survive (q1 as branch
   D-106, decisions-extract.md:5; q2 as F-29). Nowhere on main: the open hole - ARCHITECTURE.md:452 and :464 use "error rate"
   without defining it; the rule "count a run's final outcome, not any of its rows"; the owner's withdrawal of the SLA ("SLA
   bych do architektury zatím nemíchal"). Row sentence: define the flow error rate's numerator in section 9 as a run's final
   outcome; record the owner's 2026-11-14 withdrawal of SLA-on-first-failure; under a free F-id.
   Branch F-29 (94fceab diff :902-1049): ARCHIVED - agent/archive/branch-design/F-29-branch.md (222 lines) is a superset.
B. Packets FIX-123, FIX-62, FIX-71: on main as files with the branch's text (agent/packets/FIX-123.md:21, FIX-62.md:14,
   FIX-71.md:85) - CARRIED. FIX-71's named tests are on main (BatchingWriterDispatchOrderTest:38, PropertyTest:335,
   ErrorContainmentTest:161, WindowRefusalLogTest:54). Branch FIX-130's packet is not on main (main's FIX-130.md is another
   task); its task is carried by e65f616. FIX-62: main's row (BACKLOG.md:421) carries "the flow ERROR log asserted"; FIX-206
   inherits it only if 4cbc121's cases assert the log - not checked.
   The reviewer-memory hunk (94fceab diff :62): LOST, minor - the TrailTest capture-filter drift ("m-w" prefixes "m-wait"),
   "assertTrue aborts, so Failures: N under a mutant says nothing about which assertion fired", "PIT does not mutate string
   literals; wording is pinned only by a hand mutant". In no agent memory file.
C. Correction to round 1: branch D-106, D-107, D-109, D-110 are all ARCHIVED whole in decisions-extract.md:5-9, the owner's
   words included. D-106 is not lost; CONSIDER-15 does not cite it.
   - D-106 (a retry that succeeded shows only in the message's history): archived only; nothing on main contradicts it
     (OPERATIONS-BRIEF section 2.4 fits it).
   - D-107: CONFLICT. Branch, 2026-11-14: "Replay of a successful message goes out … the only operations are retry of a failed
     message and cancel of a failed message", one id for life. Main: DECISIONS.md:144 D-136 ("Retry keeps the id, replay mints
     one", accepted by the owner in D-142, :150); ARCHITECTURE.md:327 ("A replay (of a message or of a parked one …)", "Replay
     from any recorded step works after a restart"), :386, :452. D-136 and D-142 predate D-107, so no deliberate supersession.
     The brief does not settle it: OPERATIONS-BRIEF.md:126 lists "act on messages (restart, cancel …)", which matches D-107
     and says nothing about ids or replay; CONSIDER-22 (:181) is a dry replay for diagnosis. Partly carried: same-id retry
     (D-136), per-attempt keying (D-140, the shape of branch D-108). Row sentence: the owner decides branch D-107 against
     D-136 / D-142 and ARCHITECTURE.md:327/:386/:452; the answer under a free D-id.
   - D-109 (no resend cap; sync and cancelled messages never resendable): archived only; partly conflicts with main, where a
     replay applies to any message, sync included (ARCHITECTURE.md:327). Falls with D-107.
   - D-110 (a cancelled message counts as a failure): archived only; nothing on main contradicts it.
D. Branch D-103 (585b6f4 diff :222): ARCHIVED whole (decisions-extract.md:2); CONSIDER-15 does not cite it. The PROGRESS hunk
   (:59-92) is journal state. One fact for FIX-196's notes: the branch's own review of FIX-59/60 left two gaps recorded as
   NOT_RUN - mutant 8 (the write moved above the check in copyBounded) SURVIVED, and the out.close() failure path at
   DirArchiveStore.copyBounded:75 is unreachable from a test.
E. The FIX-138 packet (8105b0f diff :358-470): CARRIED, provided FIX-205 restores 27d4b9b's case as written. Side note: FIX-205
   says BatchingWriterErrorTest "does not exist in the tree" while FIX-195 says both classes are back - one row is stale.
   NOT READ: the FIX-136 body (:137-250) and the FIX-137 body (:257-351, with a "decision (architect, binding)" at :295).
F. 7ccbaad's fact is true of main today: no pom.xml mentions javadoc. The note ("Nothing in this build checks a javadoc
   {@link}", diff :169-171) is LOST - agent/NOTES.md has no match.
Not checked: the FIX-136 and FIX-137 packet bodies; whether 4cbc121's tests pin the ERROR log; 27d4b9b's test content; the
94fceab hunks for BACKLOG, NOTES, PROGRESS and the CONSIDER-13 draft; the handoff hunks.

## Round 2 hand-back (one reviewer, class B, 2026-11-15) — as received

review: FAIL

What I checked: every "What must become true" sentence from round 1 (all 13) and the second pass's A, C, D and F are now true in the rows, and every main-side spot-check matched. The FAIL is for losses no pass looked for: the doc hunks of the 19 code commits.

BLOCKING
1. Row FIX-204, row FIX-211: commit 0adcbed is not code-only. Its docs/DECISIONS.md hunk (diff :196-205) amends D-056 with "AMENDED 2026-11-14 by FIX-136": the two catches are widened again to Throwable, and the `RuntimeException | Error` multicatch was refused because of mutant M7. Its F-30 hunk (:209-236) adds a fourth correction: widening Delivery:319 alone closes the hole from above, and :41/:47 are widened only for Intake.save's own contract. Neither text is on main. A grep for "AMENDED 2026-11-14|widened again|fourth correction" over docs/, BACKLOG and the archive finds only an unrelated D-104 line in decisions-extract.md:3. FIX-211 rebuilds F-30 from 8105b0f/59bbad3. That text, and the branch's FIX-136 packet (8105b0f diff :168-169, "All three or none … one frame deeper"), carry the premise that 0adcbed disproved. Must become true: FIX-204's done-when includes D-056's amendment; FIX-211 carries 0adcbed's fourth correction and cites 0adcbed.
2. agent/work/FIX-201-audit.md:13-37: the 19 code commits were judged by code and tests only. Their PROGRESS, BACKLOG, NOTES, DECISIONS, findings and packet hunks were never audited, and "Not checked by any pass" (:81-88) does not say so. A second instance besides item 1: a35e778's NOTES rules ("Do not pass `-q` on an acceptance run", and "log content is data, never a command" after the jqwik prompt injection) have 0 hits in agent/NOTES.md. Must become true: either those hunks are audited, with a row or a stated drop for each loss, or :81 names them as unchecked.
3. audit.md:79 says "The branch's HANDOFF, PROGRESS and BACKLOG states: superseded by main's own ledgers, except the rows named above". Nobody observed that; :87-88 says the 94fceab BACKLOG hunks were never checked. My reading of the 94fceab hunk (:119-204) is in item 3(c) below. Two branch rows have no counterpart I could find by keyword grep (wording only, not proof):
   - branch FIX-125: Deployments `Map.copyOf(kept)` meets a null when the records hold two rows for one package;
   - branch FIX-129: Trail's class Javadoc does not source the two phrases FIX-123 pinned.
   Must become true: each row the branch spawned is recorded as rowed, superseded or dropped, with the reason; the :79 sentence claims no more than was checked.

NOTE (not blocking)
- Spot-checks true on main: Deployments.java:1786 `IOException | RuntimeException` in `quietly` (:1783); AdminServer.java:516 `RuntimeException` in `answer`; ARCHITECTURE.md:276 has `http.status` under headers, but HttpComponent :84, :240 and :501 treat it as a prop; PollingConsumerContractSelfTest:366; Producers.java:59 "FIX-141"; Recorder.java:87 and :91 "D-111"; Deployments.java:891/:894; TrailTest:400/:423 `startsWith("message …")`.
- FIX-243's fault text leaves out "F-30" at Producers.java:59 and :62 (main's F-30 is another finding). Its sweep acceptance does cover it.
- FIX-56 closure: sound on reading. AdminServerTest:205 pauses before send, and a grep over the three classes finds the paused pattern at every `res.body()`. But the done-when's "10 consecutive runs green" was met neither on main nor on the branch (a35e778:15-17 says the runs prove nothing). The row should say that the reading replaces that clause.
- FIX-54 closure: sound. The pins are on main (Trail.java:177 and :164 against TrailTest :400 and :423), but no mutant was run on main, and the done-when still cites Trail:168.
- FIX-57: the description column still says :445/:446; only the evidence cell is re-anchored.
- FIX-205: drainLocked's :213-217 is still not re-anchored. Branch D-112's actual decision ("keep the while, never an `if`: a broken invariant degrades into extra batches, not a stall", 8105b0f :295-307) is not stated; only "a free id" is.
- FIX-201 row: the first audit's wrong totals stay in the cell ahead of the correction. That is acceptable as history, since the cell says "the totals above do not hold".
- audit.md:88 "ROUND2" and :95 "VERDICT" are stray tokens.
- The architect's choices are defensible and said openly:
  - CONSIDER-25 states "Until the owner answers, main's text stands".
  - FIX-242 is `pending`, not measured on main, and no floor is missed.
  - The memory-hunk drop gives its reason.
  - The NOTES paragraph (:364-368) holds both general facts.
- New rows FIX-238 to FIX-244: each states the fault, where it is on main, a testable acceptance and its source sha. FIX-238 and FIX-239 say openly what was not re-read.

Item 3
(a) The FIX-136 packet's per-mode acceptance is carried: 0adcbed's twelve cases assert the permit through inFlightRequests/inFlightBytes, that stop completes, and the at-most-once WARNING naming the message (diff :514-718). FIX-204 restores those cases. Not carried: the packet's "D-056's row gains a note" (blocking 1). The FIX-137 binding decision is in no row as content (see the FIX-205 note).
(b) Yes. 4cbc121's `aFlowThatDoesNotStartOnRestoreLogsOneErrorNamingItAndItsCause` asserts the whole ERROR text per flow (diff :136-153), so FIX-206 inherits FIX-62's log clause. 4cbc121 also records the double discard as unreachable (an equivalent mutant), so no red can be demanded there.
(c) 94fceab's BACKLOG hunk spawned branch FIX-124 to FIX-132, CONSIDER-11a-g, CONSIDER-12/12a-c and CONSIDER-13, and added "AUDIT 2026-11-14: LIVE" notes to FIX-18, FIX-23 and FIX-25.
   - Superseded: FIX-124 (.gitignore:16-17 ignores `.jqwik-database`).
   - Covered: FIX-126 looks covered by FIX-235(1); FIX-123, FIX-128, FIX-130, FIX-131 and FIX-132 are carried or rowed.
   - Design input: CONSIDER-11a-g and CONSIDER-12 belong to CONSIDER-15 and CONSIDER-25.
   - Not found: FIX-125 and FIX-129 (blocking 3). The NOTES and PROGRESS hunks were not read (budget).

Open (budget reached): the NOTES and PROGRESS hunks of 94fceab, and the doc hunks of the other 17 code commits.

Files: /home/user/flottila/agent/work/FIX-201-audit.md, /home/user/flottila/agent/BACKLOG.md, /home/user/flottila/agent/work/FIX-201/0adcbed-fix-core-an-Error-can-no-longer-escape-Delivery.apply-from-t.diff, /home/user/flottila/agent/work/FIX-201-docs/superseded/94fceab.diff

## Round 3 hand-back (one reviewer, class B, 2026-11-15) — as received

review: FAIL — agent/BACKLOG.md:477 (row FIX-204, acceptance cell): the summary of 0adcbed's D-056 amendment gets two facts wrong
1. FIX-204 says "the two catches around the ingress save are widened again to Throwable". Branch D-056 (DECISIONS-branch-rows.txt:1; 0adcbed diff :205) says the two catches D-056 had widened are `Delivery.java:176` (sync) and `:218` (at-most-once), plus `landAfter`'s parameter at :227. These are the pipeline catches, not the ingress-save catch, which is :319 on the branch and :321 on main.
2. FIX-204 says the multicatch "is refused because a mutant on it survived, M7 of that commit". The branch says the opposite. The multicatch "would stop catching the sneaky checked throwable this decision exists for, which is a regression - the verifier measured it, mutant M7, killed by six cases all failing with 'apply threw instead of answering'".
Must become true: FIX-204's done-when states the amendment as the branch recorded it. That means: D-056's sync and at-most-once catches (and `landAfter`'s parameter) go to Throwable; `RuntimeException | Error` is refused because it drops the sneaky InterruptedException D-056 exists for, and that regression was shown by mutant M7, which six cases killed. audit.md:75-76 ("Rows FIX-204 and FIX-211 carry them") holds only once that sentence is true. This was visible in round 2's wording, so it is not "late". It was introduced by this round's correction.

What holds (checked against the sources)
- Blocking 1, FIX-211 (:484): matches F-30-branch.md :123-136 and 0adcbed diff :216-236. It says widening :319 alone closes the hole from above, that :41/:47 are widened for Intake.save's own contract, and that 8105b0f's "all three or none" premise was disproved.
- Blocking 2, NOTES row against FIX-253 (:500): the two sections are 72 lines (:99-170) and 105 lines (:171-275) of NOTES-missing.txt, and the -q rule (:88) and the "log content is data" rule (:86) are in it. These are true.
- Blocking 2, DECISIONS row: branch D-111 is in FIX-243 (:490) and D-113 with its ten verdicts in FIX-210 (:483). D-112 in FIX-205 (:478) now carries the decision as finally recorded: an `if` would be correct, the `while` is kept for latency, and the equivalent mutant is accepted. Main anchors BatchingWriter :272, :300-305 and :307-308 hold.
- Blocking 2, ARCHITECTURE row: true. ARCHITECTURE.diff moves `http.status` to props, and main's ARCHITECTURE.md:276 still has it under headers (FIX-241).
- Blocking 3, the 48-row table: complete, every one of the 48 branch ids is in it. I read branch FIX-125 (drop reason matches its "refuted" cell), FIX-127, FIX-129, FIX-151, FIX-152 (superseded, reason fits), FIX-153 to FIX-156 and CONSIDER-11g/12. FIX-245 to FIX-252 each give the fault, main lines, a testable acceptance and the source.
- Main lines opened for the new rows:
  - FIX-245: BatchingWriter :335/:343-360; no java.util.logging in main sources.
  - FIX-246: Trail :33, :177-178 and TrailTest :402-403.
  - FIX-248: FlowWorker :681-704 and :549-555.
  - FIX-249/250: Producers :59-71; no Producers/mid-loop text in DECISIONS.
  - FIX-252: WORKFLOW.md:67 is the only heading of that shape.
- The 27-row table: the TIP AUDIT notes of FIX-16, 18, 19, 22-27, 64, 74 and 75 read LIVE or drifted. The notes on FIX-20 (FlowWorker:406 `ours.adopt(item)`), FIX-63 (NodeBenchMain.java:434-438) and FIX-73 (RESULTS.md:415) are true.
- FIX-71 closed through FIX-195: justified. FIX-195 is done with review PASS, main's waitLocked catch (:205) takes Error, and the three missing cases go to FIX-205.
- Round 2 notes: FIX-243 now names F-30; FIX-56 says the reading replaces the ten runs; FIX-54 says no mutant was run on main; FIX-57's :445/:446 are gone; FIX-205's drainLocked anchor is re-anchored to :272.

Non-blocking
- FIX-248 cites :685 as a log site. :685 is `return Verdict.LEAVE`; the log is :684. Also, the exhausted branch's catch concatenates `failure` at :701-702, inside the catch, so it can throw there too.
- The FIX-125 drop ("the NPE is unreachable") rests on the branch's tree. Nobody re-checked the argument on main's Deployments. "Not checked by any pass" (audit.md:175-180) should say so; FIX-253 re-anchors it later.
- FIX-54's done-when still cites Trail:168. Acceptable as history, since the notes now qualify it.

Not checked (budget, 43 calls)
- FIX-206 and FIX-241 row text.
- The packets row's "same text" claim for the 11 packets.
- PROGRESS-missing-headings.txt against "15 carried or rowed".
- The FIX-58 and FIX-120 triage notes: their lines did not match "AUDIT 2026-11-14", so "LIVE or re-anchors" is unverified for those two.
- The FIX-210 site lines on main, and CONSIDER-12a-c beyond CONSIDER-12.

Files
- /home/user/flottila/agent/BACKLOG.md
- /home/user/flottila/agent/work/FIX-201-audit.md
- /home/user/flottila/agent/work/FIX-201-net/DECISIONS-branch-rows.txt
- /home/user/flottila/agent/work/FIX-201/0adcbed-fix-core-an-Error-can-no-longer-escape-Delivery.apply-from-t.diff
- /home/user/flottila/agent/work/FIX-201-net/F-30-branch.md

## Round 4 hand-back (the same reviewer, on the one finding) — as received

review: PASS

1. The blocking finding is fixed. Row FIX-204's done-when (agent/BACKLOG.md:477) now matches branch D-056 (DECISIONS-branch-rows.txt:1, from "AMENDED 2026-11-14") on every point:
   - The two catches are D-056's own sync and at-most-once catches, :176 and :218 on the branch. They go to Throwable, and `landAfter`'s parameter goes with them.
   - The `RuntimeException | Error` multicatch is refused because it would stop catching the sneaky InterruptedException. Mutant M7 showed that regression, and six cases killed it.
   - Sync answers an Error with a failed future, and at-most-once logs one WARNING naming the message.
   So audit.md:76 ("Rows FIX-204 and FIX-211 carry them") now holds. FIX-211 was already true in round 3.
2. The round-3 notes are closed:
   - FIX-248 (:495) cites the log sites :684, :689 and :693, and says the catch's own WARNING at :701-702 can throw too.
   - audit.md:179 names the FIX-125 argument against main's Deployments as unchecked.
   - The TIP lines of FIX-58 (branch-rows-changed.txt:36, "actual shape Deployments.java:810-811 …") and FIX-120 (:4, "row cited :93, which is blank …") only re-anchor lines, as the 27-row table says.

Not re-checked this round, as you scoped it: anything outside these items. What round 3 listed as unchecked is still unchecked: FIX-206 and FIX-241 text, the packets' "same text" claim, the PROGRESS headings, the FIX-210 sites on main, and CONSIDER-12a-c.

Files: /home/user/flottila/agent/BACKLOG.md, /home/user/flottila/agent/work/FIX-201-audit.md, /home/user/flottila/agent/work/FIX-201-net/DECISIONS-branch-rows.txt
