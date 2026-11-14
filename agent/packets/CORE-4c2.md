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

## Red approved (architect 2026-11-04)
Red observed: test-compile 95 errors on the assumed API (agent/work/CORE-4c2-red.log); semantic reds against today's code
(agent/work/CORE-4c2-red-semantic.log, -red-enqueue.log). Tests: journal/JournalBlobStoreIT, PgIntakeJournalIT, JournalReadIT,
PgDlqJournalIT, PgBodyRefsIT, WorkClaimIT, SchemaIT, ReferenceEnqueueIT (+ fixtures PgWire, TestJournals, JournalWorld,
blob/LocalStores); changed: PgIntakeIT, PgIntakeContractIT, PgDlqIT, IntakeContract, SequentialIntakeTest, RetentionIT, DeliveryTest,
DeploymentsDedupeTest, NodeIT, LedgerIT, StepBodiesIT, DlqBodiesIT, DeliveryModesIT, PartnerFailureParkIT, SftpIngressDedupeIT,
AtLeastOnceIntakeIT, InvoiceSyncReplayIT, ReplayRetentionIT, NodeBenchAckCommitsIT, NodeBenchMainIT, NodeBenchMainWarmupIT; blob tests
migrated; retired with reasons: SpoolSinkFailureTest, SpoolHeapTest, SpoolTierContractTest, PgTierContractIT.SpoolAndPg,
PgBodiesReleaseIT, PgBodiesIT insert cases, TieredBlobStoreTest path cases. Contract = the signatures in the test-author's
handback: `TieredBlobStore.Config(treeLimit, memoryTierBytes, memoryBudgetBytes, durableMaxBytes[, indexEntries = 262 144])`,
`new TieredBlobStore(Config, Hasher, Journal, int tenantKeyId[, Pool])`, `Location.SPILL`, `new PgWorkQueue(Pool, String node,
TieredBlobStore)`, `PgWorkQueue.enqueue(SqlClient, messageId, flow, step, partitionKey, BlobKey body)`, `PgIntake.openSaves()`,
`Intake.save(…, boolean keepInput, …)`; SQL shapes work.body_*, body_refs.
Assumptions accepted: the index belongs to the store; a pre-V12 bodies row admits no park; exists() true on another node, its read
fails retryable naming the owner; the work-row lookup matches the input key only; 8 round trips with uncached prepared statements;
LedgerMain restarts node 1 under the same id and journal; a memory tier below RECORD_MAX (0 = spill from the first byte); an open
save leaves the set before its future completes, a failed force too.
Architect decision (ReplayRetentionIT): the location index serves the hot path only (a worker's reads of items it saved or
claimed); admin replay and DLQ retry check the key's ref in Postgres first and answer D-096's 409/404 from there, so retention
needs no hook into any node's index. The connection-lost-mid-commit (`pg_xact_status`) case goes to CORE-4d with its consumer.

## Review r1 FAIL (reviewer 2026-11-04) — fix round
Tests (test-author): (1) FlowBuilder passes `recording().keepsBody()` as keepInput — a core case per level (false and true)
asserting what the intake receives; (2) noCommitBeforeTheForce over the counted PgWire pool: no statement, BEGIN included,
while the force is held; (3) a body above RECORD_MAX whose spill persist fails (refused segment-row insert) → save fails,
openSaves() empty; (4) PgBodyRefsIT: a keepInput key persisted with no local copy whose body_refs row names a swept segment →
BlobNotFound, inserts nothing; the same key's source row held `for update` → the insert waits, then BlobNotFound after the
holder deletes it; (5) JournalBlobStoreIT twins for retired cases: persist at durableMax passes, durableMax+1 refused; a key
rewritten while its persist's force is held stays local and readable with the new bytes; (6) see the decision below.
Architect decision on (6): the decision stands, the code follows it. `exists()` never answers from the location index — it
asks Postgres (body_refs → work row → bodies); the index serves `open()` only. Red: an index entry left stale (its body_refs
row and work row deleted by the test, as after retention with a lost COMPLETE) → exists() false and the admin GET 404; plus
an async item completed over PgWorkQueue drops its index entry (pins forget).
Red approved r1 (architect): reds JournalBlobStoreIT.aStaleIndexEntryDoesNotAnswerExists / .aStaleIndexEntryDoesNotAnswerTheAdminRead
(agent/work/CORE-4c2-r1-red.log); pins with killed mutants for (1)–(5) and the forget pin. Fix: exists() asks Postgres only; the
admin body read (AdminServer.body) checks existence through Postgres before it opens, so the index serves the hot path only.
Architect r1 (conflict): JournalReadIT.indexHitAsksNoPostgres:127 (exists true from the index) contradicts the r1 decision; it is a
wrong expectation now. The test-author changes it: exists() on an index hit waits while the case holds the tables locked and
answers true once they are released; :125-126 (read/open from the index without Postgres) stay.
