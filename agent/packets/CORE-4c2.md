# CORE-4c2 — the body store on journal references: intake, DLQ, reads, claim (class C; D-105)

Architect 2026-11-04. CORE-4c is now three rows: 4c1 done (9bd532c); **4c2** (this) = the hot path on references;
**4c3** = the pinned-cut start refusal, ACCEPT_LOSS, the open-channel cache and the start carries. Design: agent/packets/CORE-3.md
items 6 (intake), 7 (api, FIX-35 by read-back), 8 (reads), item 10's last sentence (every reference insert that adds no record
holds its source row and that row's segment row `for key share`), and the "Added in" sections. Scout map (4c1 scout): PgIntake.save
store/PgIntake.java:73-92; PgBodies.java:37-40; PgDlq PARK store/PgDlq.java:55-59; TieredBlobStore persisting :310-326, spool
:181/:206, Node.spoolKey :369; callers of persist/persistIn: core Trail:174, Parking:85, SequentialIntake:40, PgIntake:78/:80;
PgWorkQueue ENQUEUE :34, CLAIM :54; Retention.java:66/:170; LedgerIT.java:120 joins `bodies`.

## Scope
1. **Store**: TieredBlobStore's spool tier is the journal: a body ≤ RECORD_MAX is appended (memory copy until commit), a body
   above it is a Spill; the per-start spool key, SpoolSink/SpoolCipher-as-tier and `<data>/spool` go (SpoolCipher's AEAD lives on
   as ChunkedAead). `persistIn(tx, key)` → (ref, release): forced first, then the rows in the caller's tx; `persist(key)` = forced
   + body_refs committed. No body bytes are inserted into `bodies` any more (rows there are read only, pre-V12).
2. **Intake (item 6)**: append → forced → one tx [dedupe] + ingress record + work item with the ref (+ body_refs only when
   `keepInput`) → abandoned check → commit → index the ref, release the memory/spill copy. Core `Intake.save` gains
   `boolean keepInput` (= the flow's `RecordingPolicy.keepsBody()` until CORE-13). Level none = record + work item only. No row
   naming a body commits before its record is forced. The open-save set (reservation → commit/rollback acknowledged, `pg_xact_status`
   after a lost commit) exists and is readable; its consumer is the 4d sweeper.
3. **References**: `body_refs` and `work.body_*` written as in item 5; every reference insert that adds no record (an unchanged
   body, a replay or DLQ-retry enqueue, a park) is one statement whose CTE holds its source row and that row's segment row
   `for key share`, inserting from it; no source row → BlobNotFound. PgDlq park on refs (replaces the `bodies` key-share CTE).
4. **FIX-35 without bytes in Postgres**: a persist meeting the key's body_refs row while it holds a local copy reads the stored
   record by its ref and compares byte for byte, 64 KiB at a time; equal → quiet, different → ISE, nothing changes; a row whose
   segment row is gone is replaced in the same statement.
5. **Reads (item 8)**: in-memory location index (≤ 262 144 entries, oldest segment first out, filled at commit and by the claim)
   → body_refs → the work row by id → `bodies` (pre-V12) → BlobNotFound; a ref whose segment row is gone reads as removed; a ref
   on another node fails retryable (never BlobNotFound) — CORE-5 adds peer reads.
6. **Claim**: a node claims only work whose `body_node` is itself or null (pre-V12 rows: any node); its own items are claimed
   again after a restart.
7. **Retention**: phase 2 removes body_refs with their message (RetentionIT); `bodies` rows still go by age (CORE-11 removes the table).
8. **Ledger**: LedgerMain/LedgerIT count by `messages`/work, not `bodies`; the kill -9 ledger 2000/2000 holds with no `bodies` row
   and no S3 object written.

## Red tests (test-author; names from CORE-3 where given)
JournalBlobStoreIT extends DurableBlobStoreContract (+ .aReadOnAnotherNodeFailsRetryable, .otherBytesAreFoundByReadingTheRecordBack);
PgIntakeJournalIT.noCommitBeforeTheForce / .levelNoneSavesRecordAndWorkItemOnly (counts statements and round trips at the client)
/ .keepInputWritesABodyRef / .aRolledBackSaveLeavesOnlyUnreferencedBytes; PgDlqJournalIT.aParkNeedsItsRefAndSegmentRow;
JournalReadIT.indexHitAsksNoPostgres / .missReadsBodyRefs / .missReadsTheWorkRow / .preV12KeyReadsBodies / .unknownKeyIsBlobNotFound
/ .sweptSegmentReadsAsRemoved (segment row deleted by the test); WorkClaimIT.aNodeClaimsOnlyItemsWhoseBodyItHolds /
.aPreV12ItemIsClaimableByAnyNode; PgBodyRefsIT.aReferenceIntoASweptSegmentFailsBlobNotFoundAndInsertsNothing; SchemaIT.noByteaOutsideTheAllowList
(allow-list: `bodies.bytes` until CORE-11, `journal_keys.wrapped`, the existing ones) plus a pg_dump marker check (a payload marker
string sent through the intake is absent from `pg_dump`); NodeIT no `<data>/spool`; LedgerIT with no `bodies` row. Existing tests
that assert `bodies` rows for new messages change expectation, each named with its reason.

## Constraints
CLAUDE.md design rules (only runtime touches Postgres; no synchronous delete on the hot path; bytes once); no flottila-api
signature change; core `Intake.save` gains one parameter only; fake clock/timer, no sleeps; javap for APIs. Floors no decrease.

## Checks
runtime verify, reactor `verify -Pit`, coverage check; logs agent/work/CORE-4c2-*.log.
