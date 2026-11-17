# CORE-4d — segment sweeper, journal disk bound with 503, open-save outcomes, the 4c carries (class C; D-105)

Architect 2026-11-04. Base: 3b54ee6 (CORE-4c3). Design: agent/packets/CORE-3.md items 10 (sweeper), 11 (bound), 14 (KEEP_MAX_AGE),
"Review r2 additions" (CORE-6 pipelined commit note), and the CORE-4d red tests listed under "Red tests added after review r1".
Retention phase 2 on body_refs is already done (4c2). Carries: BACKLOG CORE-4d row and CORE-4c2/4c3 reviews.

## Scope
1. **Sweeper** (item 10): one virtual thread on the injected timer, `FLOTTILA_JOURNAL_SWEEP_INTERVAL` (PT10S) and
   `_SWEEP_GRACE` (PT1M) from the trailer's sealed ms (after a restart: since the start). Order: read the open-save set, then the
   pin query in a later snapshot; segments in the set skipped. pinned = work.body_segment with lost_at null ∪ DLQ ⋈ body_refs not
   archived, lost_at null ∪ body_refs archived_at null, lost_at null, created_at > clock − `_KEEP_MAX_AGE` (PT1H, PT1M..P1D).
   Unpinned segment rows locked `for update skip locked`, pins re-checked under a later snapshot, row deleted, then the file
   unlinked off the hot path; `ReadChannels.drop(segment)` closes a cached channel so the inode goes. Only this node's segments.
   A body whose segment was swept reads as removed (BlobNotFound / REMOVED_BY_RETENTION), never IOException — the index hit on a
   swept segment included.
2. **Open-save outcomes**: a save whose commit reply was lost stays in the open-save set; its outcome is asked by
   `pg_xact_status(xid)` (the xid taken inside the tx before COMMIT); unknown → its segment stays pinned until known or restart.
3. **Bound** (item 11): `FLOTTILA_JOURNAL_MAX_BYTES` counts segments, spill and reservations. An intake is taken while journal
   bytes + its record ≤ max − max/16 and refused one byte above with retryable JournalFull → HTTP 503 + Retry-After; step-body
   persists and parks use the reserve up to max, beyond it a step body is metadata-only and a park holds, with ERROR. WARNING when
   80 % is crossed upward, ERROR when refusals start; each logs again after falling below and crossing again.
4. **Carries**: the intake passes its answer future to `forced(key, abandoned)` (core `Intake.save` takes a `Future<?>` in place of
   the BooleanSupplier; every caller follows); the dir-force generation counter closing the re-check window (Journal.java:1125);
   `giveUp` takes `for update` on its pins; KEEP's conflict branch holds its live row's segment row `for key share`; the Retention
   BODIES scan locks at most `limit` rows per batch; LargeBodyCrashTest.aRowWhoseFileWasNeverMovedIsDroppedAtStart /
   .aMovedFileWithoutARefIsSwept.

## Red tests (test-author)
SegmentSweepIT.graceCountsFromTheSeal / .aSaveCommittingAfterTheOpenSetReadKeepsItsSegment /
.anUnknownCommitOutcomeKeepsItsSegmentUntilItsXactStatusIsKnown / .aWorkRowAlonePins / .aHeldWorkRowAlonePins /
.aLargeStepBodyPersistedPastTheGraceKeepsItsSegment / .aDlqRowAlonePins / .anUnarchivedRefAlonePins / .anArchivedRefNoLongerPins /
.aRefOlderThanKeepMaxAgeNoLongerPins / .anotherNodesSegmentIsUntouched / .aReferenceInsertRacingTheSweepHoldsOrFindsNoBody /
.aSweptBodyReadsAsRemovedEvenFromTheIndex / .aSweptSegmentsChannelIsClosed; JournalBoundIT.theReserveEdgeTakesExactlyAndRefusesOneByteMore
/ .anIntakeAboveTheReserveAnswers503WithRetryAfter / .eightyPercentWarnsOnce / .aSecondCrossingLogsAgain /
.beyondMaxAStepBodyIsMetadataOnlyAndAParkHolds; the intake's answer future ends the append wait (PgIntake level); the carries'
cases; LargeBodyCrashTest (2). Red = compile error or failing assertion, agent/work/CORE-4d-red.log.

## Constraints
CLAUDE.md design rules; never a synchronous delete on the hot path; only runtime touches Postgres; NodeConfig the only env reader;
sizes long, limits finite and range-checked; fake clock/timer, held seams, no sleeps; property test (jqwik) for the bound edge if
it fits; javap / Context7 for the pg client (`pg_xact_status` via SQL). Core signature change limited to `Intake.save`'s abandon
parameter. Floors no decrease.

## Checks
`mvn -B -ntp -q -pl flottila-runtime -am verify`, reactor `verify -Pit`, coverage check; logs agent/work/CORE-4d-*.log.

## Red approved (architect 2026-11-04)
Red observed: test-compile 56 errors on the assumed API (agent/work/CORE-4d-red.log); semantic reds 24/24 new IT cases plus
LargeBodyCrashTest and RetentionIT.aBodiesBatchLocksAtMostItsLimit against a shimmed scratch copy (agent/work/CORE-4d-red-it.log).
Tests: journal/SegmentSweepIT (16), JournalBoundIT (5), IntakeAppendWaitIT, GiveUpRaceIT, LargeBodyCrashTest (2), node/
NodeConfigJournalSweepTest, RetentionIT +1; call-site changes for `Intake.save(…, Future<?> abandoned)` in DeliveryTest,
SequentialIntakeTest, IntakeContract, DeploymentsDedupeTest, PgIntakeIT, PgIntakeJournalIT, JournalIntakeEdgesIT, LostBodyReadIT,
JournalReadIT, SchemaIT, JournalBlobStoreIT, PgBodyRefsIT (abandoned answers are real Promises; "asked N times" removed).
Contract: `SweepConfig(interval, grace, keepMaxAge)`; `NodeConfig.journalSweep()`; FLOTTILA_JOURNAL_SWEEP_INTERVAL PT10S
(PT1S..PT1H), _SWEEP_GRACE PT1M (PT1S..PT1H), _KEEP_MAX_AGE PT1H (PT1M..P1D); `SegmentSweeper(Journal, SegmentRows,
Supplier<? extends Collection<JournalRef>> openSaves, SweepConfig, Clock, Timer)` start/sweep()/close; `SegmentRows.sweep(node,
candidates, keptSince)`; `JournalFullException`; journal bytes = segments/ + spill/ + reservations.
Accepted: the assumptions in the handback; the dir-force counter and LargeBodyCrashTest's start-drop case are mutant pins.
Architect decision (GiveUpRaceIT order B): giveUp marks every work row and body_ref whose ref points into the given-up
segment extents (at or past the cut, or a missing segment), read after it has locked them `for update` in its own transaction,
not only the printed items; a copy made after the refusal is the same lost record.

## Review r1 FAIL (2026-11-04) — architect decisions
(1) The bound counts a spill while it streams: each chunk is counted as written and a spill that would pass max fails
JournalFull, its file unlinked off the hot path; an intake spill is refused at its start when bytes are past the intake edge
(max − max/16), so no body is written just to be answered 503; the footer check stays. Red: a spill started at bytes = max is
refused and bytes stay ≤ max; an intake spill above the edge writes no chunk.
(2) `stored()` gets the swept-segment fallback `indexed()` has: a sweep between find() and the stream reads as removed
(BlobNotFound / REMOVED_BY_RETENTION), never IOException. Red in SegmentSweepIT with the FIND reply held across a pass.
(3) The node's sweeper wiring is tested: a NodeJournalIT case with short SWEEP_INTERVAL/GRACE on the node's fake timer removes
an unpinned sealed segment and keeps the segment of an in-doubt open save; close order sweeper before stores.
(b) giveUp marks from the cut (0 for a missing segment), as decided; a case with an archived ref in [cut, lowest pin).
(e) An unlink IOException is caught per segment; the rest of the pass continues; failed unlinks stay pending and are retried
next pass, and their bytes still count until unlinked. (d) An empty candidate set skips the transaction.
Accepted: (c), (f); the uncovered PgIntake:170 / TieredBlobStore:395; a sealer footer / pre-open header may overshoot max by at
most one footer + header (recorded like CORE-4a's one-record overshoot). Test names that hold only by composition are renamed
or their javadoc says so (anIntakeAboveTheReserveAnswers503WithRetryAfter, beyondMaxAStepBodyIsMetadataOnlyAndAParkHolds).
Red approved r1 (architect): JournalSpillBoundTest.aSpillStartedAtMaxIsRefusedAndTheFilesStayAtMax / .anIntakeSpillPastTheEdgeWritesNoChunk,
SegmentSweepUnlinkTest.aFailedUnlinkSparesTheOthersCountsItsBytesAndIsRetriedNextPass, SegmentSweepIT.aSweepBetweenTheFindAndTheReadReadsAsRemoved,
NodeJournalIT.anAcceptedLossAtACutMarksTheRefsFromTheCutNotFromTheLowestPin (red, agent/work/CORE-4d-r1-red.log); pin
AtLeastOnceIntakeIT.theNodesSweeperRemovesAnUnpinnedSegmentAndKeepsTheSegmentOfAnInDoubtSave (mutants start()/openSaves killed).
Accepted: (3) runs the node's real timer at PT1S with a bounded wait for the condition (the node takes no timer seam; wiring test
only, every timing rule stays pinned on the fake timer in SegmentSweeperTest/IT); the close order has no case; two renames.

## Review r2 FAIL (2026-11-04) — architect decisions
TieredBlobStore.java:442 (held-segment rethrow in `stored()`) has no case; dropping it turns a corrupt record or a shredded
tenant key read on the rows path into BlobNotFound (CORE-3 item 9: corrupt, never BlobNotFound). Red (test-author): a store
without an index entry (`restartedStore()`) reads a kept ref whose record has one flipped byte in a held segment → fails
JournalCorruptException; twin: a deleted tenant key → JournalKeyException. Mutant: delete `if (journal.holds(ref.segment())) throw e;`.
Taken with it (implementer): an unlink meeting NoSuchFileException counts as unlinked (not pending forever); the lock-order javadoc
moves onto the 6-arg `giveUp`. Accepted: SegmentSweeper's IOException-only catch; the intake spill start edge not pinned at the byte.
Red approved r2 (architect): SegmentSweepIT.aCorruptRecordInAHeldSegmentReadByTheRowsIsCorruptNeverNotFound /
.aRecordWhoseTenantKeyIsDeletedReadByTheRowsFailsJournalKeyNeverNotFound (pins, :442 mutant killed);
SegmentSweepUnlinkTest.aSegmentFileAlreadyGoneCountsAsUnlinkedAndIsNotPending (red); fixture TestJournals.Opened.deleteKey
(agent/work/CORE-4d-r2-red.log).
