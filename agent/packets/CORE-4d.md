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
