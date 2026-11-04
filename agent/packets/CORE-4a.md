# CORE-4a — journal core, no Postgres (class C; D-105)

Architect 2026-11-04 from agent/packets/CORE-3.md (the approved design; read items 1–3, 9 (file part), 8 (read by ref),
the fail-stop rule, "Red tests" and "Red tests added after review r1" for CORE-4a, "To verify with javap"). Base: d2e2845.

## Scope
Module `flottila-runtime`, new package `io.flottila.runtime.journal` (main and test). Nothing outside it changes in 4a:
no Postgres, no BlobStore/TieredBlobStore wiring, no NodeConfig keys (config is a record the tests build), no api change.
Tenant keys are given to the journal by the caller (a `Map<Integer, SecretKey>`-like lookup); no `journal_keys` table.
Clock and timer come from `flottila-api` `Clock`/`Timer` (FakeRuntime's fakes in tests); no wall-clock sleeps.

## Types the tests define (names may be refined by the test-author, shape not)
- `JournalConfig(Path dir, long segmentBytes, int segmentRecords, Duration segmentAge, long pendingBytes, Duration appendWait, …)`
  — every limit finite and range-checked (IAE naming the key).
- `Journal.open(config, clock, timer, keys, files)` → recovers the directory, returns an open journal; `close()`.
- `Journal.append(JournalRecord, Future<?> abandoned)` → `Appended(JournalRef ref, Future<Void> forced)`; the ref is known
  at once, `forced` completes only when this record and every earlier position are forced.
- `JournalRecord(String messageId, int step, String part, int tenantKeyId, ByteBuffer/bytes body)`;
  `JournalRef(long segment, long position, int length)`.
- `Journal.read(JournalRef)` → the plaintext (CRC then AEAD); a CRC failure → `JournalCorruptException` (never "not found").
- `JournalFailedException` (retryable, fail-stop) and `JournalBusyException` (retryable, pending bound / append wait).
- A file seam `JournalFiles` (real: FileChannel; test double: can hold a write or a force, fail a force, count forces) —
  the second implementation that justifies the interface.

## Behaviours and red tests (all from the packet; one line each)
- JournalRecordProperties.anyRecordSequenceReadsBackByItsRef [jqwik]: any sequence of records of any size ≤ RECORD_MAX reads
  back byte-equal by its ref, across rolls.
- JournalWriterTest.concurrentAppendsShareOneForce: N appends queued while a force is held complete after one more force.
- JournalWriterTest.anIdleAppendIsForcedWithoutATimer: an append on an idle journal is forced with no timer pending.
- JournalWriterTest.noFutureCompletesBeforeItsForce: with the force held, no `forced` future completes.
- JournalWriterTest.noFutureCompletesWhileAnEarlierPositionIsUnwritten: an out-of-order drain staged by the seam never
  completes a later future before every earlier position is forced.
- JournalWriterTest.aRoundSpanningARollForcesBothChannels.
- JournalWriterTest.aFailedForceIsNeverRetriedOnThatFile: after a failed force the seam sees no further force on that file.
- JournalWriterTest.aFailedJournalFailsQueuedAndLaterAppendsRetryable (JournalFailedException); reads still work.
- JournalWriterTest.waitingBytesAboveTheBoundFailAtTheDeadline / .aWaitingAppendEndsWhenItsSaveIsAbandoned /
  .aWaitingAppendEndsAtAppendWaitOnTheFakeTimer: fail JournalBusyException, nothing reserved; asserted on the fake timer's
  pending tasks, never on wall time.
- JournalWriterTest.aBlockingListenerDelaysNoOtherFuture.
- JournalSegmentTest.rollsAtBytesRecordsOrAgeOnAFakeClock / .theFooterListsEveryRecord / .noAcknowledgedRecordWaitsForTheFooter
  / .theSealerStartsOnlyAfterTheLastForceOnN (a held force across a roll).
- JournalRecoveryTest.aTornTailIsCutAtTheFirstBadCrc / .recoveryNeedsNoTenantKey / .aTornSealIsRecoveredAsUnsealed /
  .aBadIndexCrcReadsWithoutTheIndex / .aBadRecordCrcFailsItsReadAsCorrupt.
- JournalCipherTest.aDeletedTenantKeyMakesItsRecordsUnreadable / .aHeaderEditFailsAuthentication.
- JournalKill9IT.everyAcknowledgedRecordSurvives: a child JVM appends and prints each acknowledged ref; it is killed with
  -9 mid-run; reopening reads every printed ref back byte-equal (pattern: MainProcessIT / CpuSamplerIT child JVMs).

## Red
A compile error on the types above, or a failing assertion; observed and logged to agent/work/CORE-4a-red.log.

## Constraints
CLAUDE.md design rules: sizes `long`, every limit finite; one long-lived writer virtual thread, completions on fresh
virtual threads, no platform thread, no third executor; no sleep-based timing (fake clock/timer; a held seam instead of a
race); no `@Disabled`; the reused AEAD construction from `blob/SpoolCipher` (refactor allowed inside runtime, behaviour of
the spool unchanged — its tests stay green). JDK API from `javap` on JDK 25 (FileChannel force/write(ByteBuffer[])/
read(ByteBuffer,long)/truncate/tryLock, java.util.zip.CRC32C), never from memory; Context7 is out of quota (ISSUE-1).
Floors: runtime 75 % line/branch, no decrease. ArchUnit green.

## Checks
`mvn -B -ntp -q -pl flottila-runtime -am verify` (unit) and the JournalKill9IT with `-Pit` scoped to it; output to
agent/work/CORE-4a-*.log.
