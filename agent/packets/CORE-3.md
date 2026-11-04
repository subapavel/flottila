# CORE-3 — body journal design (class C; designer draft approved by the architect 2026-11-03, D-105)

Source: the designer's hand-back of 2026-11-03 (CORE-REWORK §3 as input). Status: `draft — review at
hand-over` for: format v1 incl. the trailer's sealed ms; V12 incl. `body_refs` without keep and `work.body_*`; the NODE
write order; the `FLOTTILA_JOURNAL_*` keys incl. `_APPEND_WAIT`, `_KEEP_MAX_AGE`; FIX-35's read-back compare; the fail-stop
rule; core `Intake.save(keepInput, Future abandoned)`; the persist javadoc; the retired per-start spool key. Review r1 FAIL
(12 points) amended by the designer the same day. Runtime package `io.flottila.runtime.journal`.

## Shape
1. Segment `segments/<n>.seg` (per-node u64 n): header {magic FJS1, v1, journal id UUID, node id, n, created ms, CRC32C};
   records; at seal a footer index {(message id, step, part, position u64, length u32)…} + trailer {index position, count, sealed
   ms, CRC32C, magic FJSE}. Rolls at 64 MiB, 65 536 records or 1 s after its first record (injected clock); an empty segment
   never rolls. At a roll the writer moves to the pre-opened next segment at once (row, file, header, force, dir force done on
   a virtual thread off the hot path); n is handed to a sealer virtual thread only after
   the writer's last write and force on n have returned (the writer never touches n afterwards); the sealer writes n's footer and trailer, forces and closes it. No
   acknowledged record waits for a footer; n counts as sealed (sweeper, mover) only after that force.
2. Record: crc32c u32 (over the rest) · length u32 · version u8 · kind u8 · tenant key id u32 · plain size u64 · message id
   (u16 len) · step u32 · part (u16 len) · salt[16] · AEAD chunks. The spool construction per record: HKDF-SHA256(tenant key,
   salt), AES-256-GCM, 1 MiB chunks, nonce counter|last, header as AAD. Recovery needs no key (CRC only).
3. Writer: one long-lived virtual thread per node. The message's virtual thread builds, encrypts and CRCs its record;
   `append` reserves (segment, position) and enqueues in one critical section, so queue order = position order. A round
   drains ≤ 4 MiB / 4 096 records: one gathering write and one `force(false)` per channel it wrote (across a roll, n and
   n+1). A future completes only once every position up to its end, in every segment, is forced (CORE-5: and received).
   Each future completes on a fresh virtual thread of its own, so a blocking listener delays no other. Window = the
   previous force; no timer. Above `_PENDING_BYTES` (16 MiB) an append waits for room until its save is abandoned or
   `_APPEND_WAIT` (PT5S, PT0.1S..PT1M, injected Timer) passes, then fails retryable with nothing reserved. **Fail-stop:**
   any write or force error (EIO, ENOSPC) fails the journal: that channel is never forced again; the round's appends, every
   queued and every later append fail retryable (JournalFailed → 503); reads go on; one ERROR + a gauge; admin health
   unhealthy; only a restart's CRC scan recovers the journal.
4. Large bodies above `FLOTTILA_JOURNAL_RECORD_MAX` (256 KiB, range 4 KiB..memory tier) are a one-record segment of their
   own, written from the start in journal format under `<journal>/spill/`; hashed once while streaming; persist = write the trailer (sealed ms = now
   on the injected clock) → force(file) → segment row → ATOMIC_MOVE into `segments/` → force(dir); the persist is in the
   open-save set from its segment-row insert until its commit or rollback is acknowledged (review r2). The per-start spool key, S3Target, S3Sink and preferS3 go;
   `spill/` is cleared at start.
5. Postgres V12, a stop-all upgrade like V11: `journal_nodes(node pk, journal_id uuid)`; `journal_segments(node, segment,
   copies text[], created_at, pk(node, segment))` written before the file exists; `work.body_node text, body_segment,
   body_position, body_length bigint` (null = pre-V12: read from `bodies`, claimable by any node); `body_refs(message_id,
   step, part, node, segment, position, length bigint, archived_at, created_at, pk(message_id, step, part))` — no `keep`
   column, every row is a kept reference; a partial index `(node, segment, created_at) where archived_at is null` and the
   retention index; `journal_keys(key_id, tenant, wrapped bytea, created_at)`. u32 lengths only inside a segment record
   (RECORD_MAX ≤ memory tier ≤ 2^31−1, checked at config); a large file's footer carries u64. No index on `work(body_node,
   body_segment)` until CORE-GATE measures the need. `bodies` takes no more inserts; its rows go by age as today until
   CORE-11.
6. Intake (D-082 restated): no row naming a body commits before its record is forced (CORE-5: and received by every copy).
   append → forced → one tx [dedupe] + ingress record + work item with the ref (+ body_refs only when the flow keeps its
   input) → abandoned check → commit → index the ref, release the memory/spill copy. Level none = record + work item.
   Core `Intake.save(…, dedupeKey, boolean keepInput, Future<?> abandoned)`: Delivery passes its answer's future;
   `isComplete()` is the pre-commit check and its completion ends an append's wait. keepInput = the flow's
   `RecordingPolicy.keepsBody()` until CORE-13; persistIn takes no keep. The work or body_refs insert returns
   `pg_current_xact_id()`; a save stays in the open-save set from its reservation until its commit or rollback is
   acknowledged, and after a connection lost mid-commit until `pg_xact_status` of that id says committed or aborted (the
   sweeper asks).
7. API: no `flottila-api` signature change. `persist(key)` = forced + body_refs committed (every body_refs row is kept). D-061 holds except "every
   node can read them" → "readable from any node through a live copy holder" (CORE-4: the owner node only; a read elsewhere
   fails retryable, never BlobNotFound). Runtime `persistIn(tx, key)` → (Ref, release). Core `Intake.save` gains
   `boolean keepInput`.
   FIX-35 without bytes in Postgres: a persist that meets the key's body_refs row while it holds a local copy reads the
   stored record by that ref and compares byte for byte, 64 KiB at a time; equal → quiet, different → ISE, nothing changes;
   a row whose segment row is gone is replaced in the same statement (a removed key reads as never written, D-096). No hash
   column.
8. Reads: in-memory location index (≤ 262 144, oldest segment first out, filled at commit and by the claim) → body_refs →
   the work row by id → `bodies` → BlobNotFound; a ref whose segment row is gone reads as removed. pread over a bounded set
   of open channels, CRC then AEAD.
9. Start: `<journal>/LOCK` tryLock (held → refuse). Identity: a first start writes `NODE` (tmp, force, ATOMIC_MOVE, force
   dir), then the `journal_nodes` row; a NODE file without a row inserts the row; a row without NODE, or with another id,
   refuses unless `FLOTTILA_JOURNAL_ACCEPT_LOSS` is exactly the printed `<journal id>:<digest>` of that loss (anything else refuses); if accepted, a new journal
   replaces the old and every pin into it is given up as below. No `FLOTTILA_JOURNAL_KEY` refuses (deployed or not; no
   random default); every `journal_keys` row is unwrapped, one that fails refuses naming its key id. Then: spill cleared;
   files without a row unlinked; rows without a file dropped if unpinned. A segment without a valid trailer (unsealed or a
   torn seal) is scanned (length bounds + CRC32C) and cut at its first bad record. A committed pin (work ref, unarchived
   body_refs, DLQ ⋈ body_refs) at or past the cut, or into a missing segment, refuses the start naming the items; with
   ACCEPT_LOSS = the printed `<journal id>:<digest>` of this loss their work rows are held ('body lost': never run, never DLQ), their body_refs marked `lost_at` (lost rows pin nothing),
   each logged at ERROR. Then footer, trailer, force. A valid trailer with a bad index CRC: WARNING, read without the
   index, never rewritten. A record failing its CRC on read fails as corrupt (ERROR), never BlobNotFound. Next n = max + 1;
   own items (`body_node` = me) are claimed again. Kubernetes: StatefulSet, pod name = node id, PV at
   `FLOTTILA_JOURNAL_DIR` (default `<dataDir>/journal`).
10. Sweeper (virtual thread, injected timer, PT10S). Candidates: this node's segments sealed ≥ `_SWEEP_GRACE` (PT1M) ago by
    the trailer's sealed ms (after a restart: since the start). It reads the open-save set first, then runs the pin query in
    a later snapshot, so a save committing in between shows as a pin; segments in the set are skipped. pinned =
    work.body_segment with lost_at null (held, in-doubt, awaiting retry included) ∪ DLQ ⋈ body_refs not archived and lost_at
    null ∪ body_refs with archived_at null, lost_at null and created_at > (injected clock) − `_KEEP_MAX_AGE` (item 14). Unpinned rows are locked `for update skip locked` and deleted
    with the pins re-checked under a later snapshot (P2-07b2 on the segment row); files unlinked in the background. **Every
    reference insert that adds no record** (a persist of a key already durable — an unchanged body, a CORE-8 step persist
    after its item completed, Replay's input —, a replay or DLQ-retry enqueue copying a ref onto a work row, a park) is one
    statement whose CTE holds its source row (work or body_refs) and that row's segment row `for key share`, inserting from
    it; no source row → BlobNotFound.
11. Bound (Q-8): `FLOTTILA_JOURNAL_MAX_BYTES` (finite, default 16 GiB) counts segments, spill and reservations; above
    max − max/16 an intake fails with retryable JournalFull → http 503 + Retry-After; step-body persists and parks use the
    reserve up to max, beyond it metadata-only / hold + ERROR. Keys `_SEGMENT_BYTES` 64 MiB, `_SEGMENT_AGE` PT1S, `_SWEEP_INTERVAL` PT10S, `_SWEEP_GRACE` PT1M.
    Exact edge: an intake is taken while journal bytes + its record ≤ max − max/16 and refused one byte above; persists and
    parks may go up to max, beyond it metadata-only / hold + ERROR. WARNING when 80 % is crossed upward, ERROR when refusals
    start; each logs again after the bytes fall below and cross again.
12. For CORE-5: copies are byte-identical, addressed by (owner, n); `copies` fixed on the segment row at open; a lost peer
    seals early; the hook is the writer round (`replicate(n, position, bytes)` beside the local write; promises on force ∧
    every receipt); `work.body_node` + the segment's copies pick the takeover node.
13. Sync (Q-6): until CORE-10 a sync flow's recorded body keeps today's path (Trail → persist → journal, off the answer
    path); CORE-8's sync half depends on CORE-10.

14. Interim until CORE-10 (Q-4 against today's defaults ALL + KEEP, P7D): a kept ref pins only for
    `FLOTTILA_JOURNAL_KEEP_MAX_AGE` (PT1H, PT1M..P1D); after that its body leaves the journal and a replay is refused
    REMOVED_BY_RETENTION (D-096) while its rows stay; CORE-10 replaces the age with "archived". Limit: kept bytes/s ×
    KEEP_MAX_AGE + backlog ≤ max − max/16 = 16.1 GB, ≈ 4.5 MB/s kept at the defaults (≈ 220 msg/s with a 10 KB ingress and
    one 10 KB step body); P7D at that rate needs 2.7 TB; at 5 000 msg/s × 20 KB, 16 GiB fills in ≈ 170 s. The CORE-GATE runs
    at level none and is not affected.

## Architect's decisions on the designer's open points (draft — review at hand-over)
- Tenant: every record uses tenant `default` until the tenant source is decided (D-100 context); the key id field is there.
- `FLOTTILA_JOURNAL_KEY` (base64, 256 bit) required in every environment (no random default, never in the repo);
  ISSUE-4 for the owner; CORE-4a/4b need no key source (tests pass keys), CORE-4c implements D-106 (ISSUE-4 answered 2026-11-04).
- A journal gone (id mismatch or a pinned segment missing) refuses the start; the operator override is an explicit
  `FLOTTILA_JOURNAL_ACCEPT_LOSS=<journal id>:<digest>` that logs ERROR with the pinned items it gives up (designed in CORE-4c).
- Defaults: MAX_BYTES 16 GiB; the durable body ceiling stays 64 MiB until CORE-2's bound; kept refs pin for KEEP_MAX_AGE
  (PT1H) until CORE-10 (item 14); a parked body pins its segment until CORE-10.
- `copies` lives on the segment row, not on each ref (brief §3 wording fixed in CORE-12).

## Rows and red tests
- CORE-4a journal core (no Postgres): JournalRecordProperties.anyRecordSequenceReadsBackByItsRef [jqwik];
  JournalWriterTest.concurrentAppendsShareOneForce / .anIdleAppendIsForcedWithoutATimer / .noFutureCompletesBeforeItsForce
  / .waitingBytesAboveTheBoundFailAtTheDeadline; JournalSegmentTest.rollsAtBytesRecordsOrAgeOnAFakeClock /
  .theFooterListsEveryRecord; JournalRecoveryTest.aTornTailIsCutAtTheFirstBadCrc / .recoveryNeedsNoTenantKey;
  JournalKill9IT.everyAcknowledgedRecordSurvives (child JVM); JournalCipherTest.aDeletedTenantKeyMakesItsRecordsUnreadable
  / .aHeaderEditFailsAuthentication.
- CORE-4b large bodies and spill: LargeBodyTest.aBodyAboveRecordMaxIsItsOwnSegmentHashedOnce;
  LargeBodyHeapTest.persistMovesTheSpillWithoutACopy (-Xmx48m); BlobStoreContract on the journal store;
  NodeStartTest.spillIsClearedAtStart.
- CORE-4c store + Postgres: JournalBlobStoreIT extends DurableBlobStoreContract; PgIntakeJournalIT.noCommitBeforeTheForce /
  .levelNoneSavesRecordAndWorkItemOnly / .aRolledBackSaveLeavesOnlyUnreferencedBytes; PgDlqJournalIT.aParkNeedsItsRefAndSegmentRow;
  NodeJournalIT.anotherNodesJournalIsRefused / .aSecondProcessIsRefused / .aPinnedMissingSegmentRefusesStart;
  WorkClaimIT.aNodeClaimsOnlyItemsWhoseBodyItHolds; SchemaIT.noByteaOutsideTheAllowList; kill -9 ledger 2000/2000, no
  `bodies` row, no S3 object.
- CORE-4d sweeper and bound: SegmentSweepIT.aSegmentGoesOnceNoWorkDlqOrKeptRefPointsIntoIt / .anOpenSaveKeepsItsSegment /
  .aParkRacingTheSweepHoldsOrFindsNoBody / .aSweptBodyReadsAsNeverWritten; RetentionIT.bodyRefsGoWithTheirMessage;
  JournalBoundIT.aFullJournalAnswers503AndAlertsOnce / .parksStillFitInTheReserve.

## Red tests added after review r1
- CORE-4a: JournalWriterTest.noFutureCompletesWhileAnEarlierPositionIsUnwritten / .aRoundSpanningARollForcesBothChannels /
  .aFailedForceIsNeverRetriedOnThatFile / .aFailedJournalFailsQueuedAndLaterAppendsRetryable / .aWaitingAppendEndsWhenItsSaveIsAbandoned
  / .aWaitingAppendEndsAtAppendWaitOnTheFakeTimer / .aBlockingListenerDelaysNoOtherFuture; JournalSegmentTest.noAcknowledgedRecordWaitsForTheFooter /
  .theSealerStartsOnlyAfterTheLastForceOnN (a held force across a roll);
  JournalRecoveryTest.aTornSealIsRecoveredAsUnsealed / .aBadIndexCrcReadsWithoutTheIndex / .aBadRecordCrcFailsItsReadAsCorrupt.
- CORE-4b: LargeBodyTest.recordMaxStaysInASegmentAndOneByteMoreIsItsOwnFile / .aTruncatedOrReorderedChunkFailsAuthentication (> 1 MiB);
  LargeBodyCrashTest.aRowWhoseFileWasNeverMovedIsDroppedAtStart / .aMovedFileWithoutARefIsSwept.
- CORE-4c: NodeJournalIT.aCutReachedByACommittedPinRefusesStart / .acceptLossWithThisJournalIdHoldsTheLostItems / .acceptLossWithAnotherIdRefuses
  / .aKeyThatCannotUnwrapTheJournalKeysRefusesStart / .aDeployedNodeWithoutTheKeyRefusesStart / .aNodeFileWithoutItsRowIsRegistered;
  JournalBlobStoreIT.aReadOnAnotherNodeFailsRetryable / .otherBytesAreFoundByReadingTheRecordBack; PgIntakeJournalIT.keepInputWritesABodyRef,
  and .levelNoneSavesRecordAndWorkItemOnly counts statements and round trips sent at the client; JournalReadIT.indexHitAsksNoPostgres /
  .missReadsBodyRefs / .missReadsTheWorkRow / .preV12KeyReadsBodies / .unknownKeyIsBlobNotFound / .sweptSegmentReadsAsRemoved;
  WorkClaimIT.aPreV12ItemIsClaimableByAnyNode; PgBodyRefsIT.aReferenceIntoASweptSegmentFailsBlobNotFoundAndInsertsNothing.
- CORE-4d: SegmentSweepIT.graceCountsFromTheSeal / .aSaveCommittingAfterTheOpenSetReadKeepsItsSegment /
  .anUnknownCommitOutcomeKeepsItsSegmentUntilItsXactStatusIsKnown / .aWorkRowAlonePins / .aHeldWorkRowAlonePins / .aLargeStepBodyPersistedPastTheGraceKeepsItsSegment / .aDlqRowAlonePins / .anUnarchivedRefAlonePins /
  .anArchivedRefNoLongerPins / .aRefOlderThanKeepMaxAgeNoLongerPins / .anotherNodesSegmentIsUntouched / .aReferenceInsertRacingTheSweepHoldsOrFindsNoBody;
  JournalBoundIT.theReserveEdgeTakesExactlyAndRefusesOneByteMore / .eightyPercentWarnsOnce / .aSecondCrossingLogsAgain /
  .beyondMaxAStepBodyIsMetadataOnlyAndAParkHolds.

## To verify with javap before CORE-4a (never from memory)
FileChannel force(boolean), write(ByteBuffer[]), read(ByteBuffer,long), truncate, tryLock; java.util.zip.CRC32C;
`javap -c -p --module java.base sun.nio.ch.FileChannelImpl | grep Blocker` (carrier compensation on a virtual thread);
force(false) = fdatasync (observed in a test); a directory channel's force on Linux; which thread runs Vert.x 5.1 listeners
when a Promise completes off-context; `pg_current_xact_id()` in INSERT … RETURNING and `pg_xact_status(xid8)` on
Postgres 16 (NULL for an id too old = resolved); FOR KEY SHARE in a CTE feeding an INSERT (PgDlq PARK already does this).

## Rejected (one line each)
body_refs for every body (third row at level none); refs only on naming rows (no single index, D-096 park has no row);
a delete per processed ref; low-water-mark retention (one DLQ row pins all later segments); counters in memory/Valkey;
a platform thread for force (third executor); a timed commit window (c=1 latency); encrypt on the writer with nonce =
position (blocks byte-identical copies); HKDF-derived tenant keys (cannot be shredded); one file per body (create + dir
fsync per message); interleaved large-body chunks; copying spill at persist; mmap (carrier blocked, torn tails); RocksDB /
Chronicle Queue (JNI, second store); a Kafka log (O-1); retrying a failed force or rolling on after it (fsyncgate); a SHA-256 column
for FIX-35 (a full hash per body on the hot path); a work index before a measurement; one ordered completer (a blocking
listener stalls the round); P7D in the journal before CORE-10 (2.7 TB at 4.5 MB/s).

## Review r2 additions (architect, 2026-11-03)
- ACCEPT_LOSS is tied to the loss it accepts: the refusal prints `<journal id>:<digest of the given-up items>` and the
  override must name exactly that value; each accepted digest is recorded in `journal_losses(node, journal_id, digest,
  accepted_at)`. A stale value accepts nothing new (WARNING), and a later loss is accepted by its own value. Red test:
  NodeJournalIT.aSecondLossNeedsItsOwnAcceptance.
- The override does not delete the lost refs: body_refs and work rows get `lost_at` (V12) and stop pinning; a read of a
  lost ref fails "body lost by ACCEPT_LOSS", and a DLQ row whose body is lost is refused for replay/retry with that reason —
  an exception to D-062's "a DLQ row implies a durable body", stated in D-105 and D-062. Pinned in
  acceptLossWithThisJournalIdHoldsTheLostItems.
- Start refuses when the journal filesystem's free space + journal bytes < MAX_BYTES (no ENOSPC crash loop).
- The not-before (CORE-14) and in-doubt (P2-15) pin cases are red tests of those rows.
- CORE-6: with a pipelined COMMIT whose xid is unknown when the connection drops, the save stays in the open-save set until
  the node restarts.

## Added in CORE-4a (architect 2026-11-04, review PASS r2)
- JournalConfig: FLOTTILA_JOURNAL_SEGMENT_AGE in (0, PT1H]; FLOTTILA_JOURNAL_PENDING_BYTES ≥ FLOTTILA_JOURNAL_RECORD_MAX (IAE naming
  both). An append that finds nothing pending is always taken, so the bound is overshot by at most one record (≤ RECORD_MAX).
- A missing or unusable tenant key on read fails `JournalKeyException`, not corruption; after close every call throws ISE.
- Carried into CORE-4c: the bounded open-channel cache (item 8, not built in 4a; each read opens its channel); a store read checks
  the record header's message id, step and part against its key, so a misaddressed ref fails; the journal id is injected
  (4a uses UUID.randomUUID()); the fail-stop gauge and admin health.
