# CORE-4b — large bodies in journal format; the S3 blob tier removed (class C; D-105)

Architect 2026-11-04 from agent/packets/CORE-3.md item 4 (large bodies), item 9 (spill cleared at start) and the CORE-4b red
tests named there. Base: 3c001ad (CORE-4a). Scout map: TieredBlobStore spool/S3 tiers (blob/TieredBlobStore.java:64-363),
Node passes `s3 = null, preferS3 = false` (Node.java:156-171), so the S3 tier is unused in production.

## Scope (re-cut by the architect)
1. **Journal large bodies** (package `io.flottila.runtime.journal`, no Postgres): a body above `recordMax` is written from
   the start as a one-record segment of its own under `<journal>/spill/`, streamed in 1 MiB AEAD chunks (ChunkedAead, the
   record key derived as for small records, the LAST flag on the final chunk, the plain size in the AAD), hashed once
   while streaming (the api `Hash` the BlobStore uses — check flottila-api), bounded by a finite `maxSize`. Readable from
   the spill any number of times as a fresh cold stream before persist. **Persist** = write footer (one index entry) and
   trailer (sealed ms = the injected clock) → force(file) → `SegmentRows.insert(node, segment)` → ATOMIC_MOVE into
   `segments/` → force(dir) → the `JournalRef`. A failed insert or move leaves no file in `segments/` and fails persist
   retryable; the spill stays until abandoned. **Abandon** unlinks the spill file. Segment numbers come from the journal's
   one sequence, never colliding with the writer's open segment. `Journal.open` clears `spill/` at start. A sealed large
   segment recovers like any sealed segment. Reading a large ref streams (never `byte[]` of the whole body); a truncated,
   reordered or edited chunk fails authentication (JournalCorruptException or an AEAD failure, never wrong bytes).
2. **`SegmentRows`** seam: `insert(String node, long segment)` called before the move; the test double records calls and
   can fail one; the Postgres implementation is CORE-4c. The second implementation that justifies the interface.
3. **Sizes long**: `JournalRef.length` becomes `long` (a large body may exceed 2 GiB); every large-body limit finite and
   range-checked (IAE naming the key, e.g. `FLOTTILA_JOURNAL_LARGE_MAX`).
4. **The S3 blob tier goes**: `Location.S3`, `S3Target`, `S3Sink`, `preferS3` and their tests (S3TierIT, S3HeapIT, the
   preferS3 cases of TieredBlobStoreFailureTest and TieredBlobStoreHandoverTest) are removed; every `TieredBlobStore.Config`
   call site (runtime tests, BlobBenchMain) follows. The S3 archive store (`deploy/S3ArchiveStore`, `FLOTTILA_S3_*`) stays.

Moved to CORE-4c (needs journal_keys, rows and refs): the spool replaced by the journal spill inside TieredBlobStore and the
per-start spool key removed; NodeStartTest/NodeIT spill at node level; BlobStoreContract on the journal store;
LargeBodyCrashTest.aRowWhoseFileWasNeverMovedIsDroppedAtStart / .aMovedFileWithoutARefIsSwept (4c/4d).

## Red tests (test-author)
- LargeBodyTest.aBodyAboveRecordMaxIsItsOwnSegmentHashedOnce (the hash equals an independent SHA over the input; the input
  stream is read once) / .recordMaxStaysInASegmentAndOneByteMoreIsItsOwnFile / .aTruncatedOrReorderedChunkFailsAuthentication
  (> 1 MiB: drop the last chunk, swap two chunks, flip a tag byte) / .aSpillReadsBackAnyNumberOfTimesBeforePersist /
  .persistInsertsTheRowBeforeTheFileAppearsInSegments (the seam sees the insert while `segments/` has no such file) /
  .aFailedRowInsertLeavesNoSegmentFileAndPersistFailsRetryable / .abandonUnlinksTheSpill / .aBodyAboveMaxSizeIsRefused /
  .aPersistedLargeSegmentReadsBackAfterReopen / .largeSegmentNumbersNeverCollideWithTheOpenSegment.
- LargeBodyHeapTest.persistMovesTheSpillWithoutACopy at `-Xmx48m` (pattern: blob/SpoolHeapTest): a body many times the heap
  is written, persisted and read back streaming; the spill inode is the segment inode (moved, not copied).
- JournalStartTest (or a new class): spillIsClearedAtStart.
- Removal: the test-author deletes the S3-tier tests listed in Scope 4 and adapts call sites of `TieredBlobStore.Config` in
  tests only as far as the removed parameters require (stated in the handback, file by file).
Red = a compile error on the new types or a failing assertion, logged to agent/work/CORE-4b-red.log.

## Constraints
CLAUDE.md design rules: sizes long, every limit finite; blocking I/O on virtual threads only, no platform thread, no third
executor; no sleeps; fake clock/timer; no `@Disabled`. JDK API from javap on JDK 25 (`Files.move` with ATOMIC_MOVE,
FileChannel, `Files.getAttribute(path, "unix:ino")`). CORE-4a behaviour and its tests unchanged. ArchUnit green. Floors:
runtime 75 %, no decrease (baseline runtime 99.5/99.6, bench 97.4/96.6).

## Checks
`mvn -B -ntp -q -pl flottila-runtime -am verify`, the `-Pit` reactor without bench (S3 tests removed, the rest green), and
`mvn -B -ntp -q -pl flottila-bench -am verify` (BlobBenchMain call site); coverage check; output to agent/work/CORE-4b-*.log.

## Red approved (architect 2026-11-04)
Red observed: test-compile exit 1 (agent/work/CORE-4b-red.log), 70 errors on the new types, the 6-arg open, JournalRef.length
long and the 5-arg TieredBlobStore.Config. Tests: journal/LargeBodyTest (12), LargeBodyHeapTest (-Xmx48m fork),
JournalStartTest.spillIsClearedAtStart, fixtures RecordingRows, GeneratedInput, HeldFiles additions; S3TierIT, S3HeapIT and the
S3 cases deleted; Config call sites adapted. The signatures they compile against are the contract:
`Journal.open(config, clock, timer, keys, files, SegmentRows rows)` (5-arg kept); `Journal.Spill spill(BlobKey, int tenantKeyId,
InputStream, long maxSize, Hasher)`; Spill `segment() size() hash() open() persist() abandon()`; `InputStream stream(JournalRef)`;
`SegmentRows { Future<Void> insert(String node, long segment); }`; `JournalRef(long, long, long)`.
Assumptions accepted: two entry points (append ≤ recordMax, spill > recordMax; spill of a small body is CORE-4c's call); maxSize
per call, no FLOTTILA_JOURNAL_LARGE_MAX key (the flow's finite max body size bounds it); AEAD chunks are the record's last bytes,
plain size u64 BE in the header, standard 32-byte trailer; spill files opened through JournalFiles; the dir force at persist on
the calling thread. Architect: a large record's CRC32C covers its header only; its chunks are authenticated by the AEAD (no
whole-record CRC pre-read). Implementer also: Node.java and BlobBenchMain drop `null, false`; the runtime pom's heap-bounded-it
execution with no *HeapIT left is removed or proven to pass with zero tests.
