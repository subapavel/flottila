# CORE-4c1 — V12 schema, journal identity and keys, the journal opened by the node (class C; D-105, D-106)

Architect 2026-11-04. CORE-4c is split: **4c1** (this) = schema, master key, tenant keys, node identity, segment rows, the
journal opened and closed by the node, the 4a/4b carries; **4c2** = the store on references (TieredBlobStore spool → journal
spill, PgIntake/PgDlq/reads/claim on refs, ACCEPT_LOSS and the pinned-cut refusal, kill -9 ledger). Base: ddf6154.
Design: agent/packets/CORE-3.md items 5 and 9 and the sections "Added in CORE-4a" / "Added in CORE-4b"; D-106 (master key).
Scout map: migrations deploy/postgres/V1..V11 (packaged as db/migration, Migrations.java:28); MigrationsIT counts 11
(:93, also PgMessageStoreIT:76, PgDeploymentStoreIT:100); Node.start order Node.java:147-220; NodeConfig.from is the only env
reader (NodeConfig.java:271-297), FLOTTILA_DATA_DIR, FLOTTILA_NODE_ID, deployed() :115; Secrets.fromEnv pattern deploy/Secrets.java:28.

## Scope
1. **V12** (`deploy/postgres/V12__journal.sql`, stop-all upgrade like V11): `journal_nodes`, `journal_segments`,
   `journal_keys`, `body_refs` (with `lost_at`, `archived_at`), `work.body_node/body_segment/body_position/body_length` (bigint,
   null = pre-V12), and every index item 5 names. `bodies` unchanged (no more inserts from 4c2 on). Count expectations of
   MigrationsIT/PgMessageStoreIT/PgDeploymentStoreIT move 11 → 12 (test-author, a changed expectation with its reason).
2. **Master key (D-106)**: `MasterKeySource` in runtime: `FLOTTILA_JOURNAL_KEY_FILE` (preferred) or `FLOTTILA_JOURNAL_KEY`
   (base64, exactly 256 bit), both set → refused; neither → start refused (deployed or not), unless
   `FLOTTILA_JOURNAL_KEY_GENERATE=true`, which creates `<journal>/MASTER` once (tmp, force, ATOMIC_MOVE, force dir, 0600) and
   logs a WARNING notice at every start (key beside the data). No random default anywhere, no key value in a log or exception.
3. **Tenant keys**: `journal_keys(key_id, tenant, wrapped, created_at)`; a tenant's key is created on first use, wrapped under
   the master (AES-256-GCM, AAD = key id + tenant), stored, cached in memory; at start every row is unwrapped, one that fails
   refuses naming its key id (never the key). This is the `IntFunction<SecretKey>` the Journal takes.
4. **Node identity (item 9)**: `<journal>/LOCK` tryLock (held → refuse); first start writes `NODE` (journal id, injected id
   source — not UUID.randomUUID() inside the journal) then the `journal_nodes` row; NODE without row → row inserted; row
   without NODE, or NODE with another id → refused (the ACCEPT_LOSS override is 4c2).
5. **Segment rows**: `PgSegmentRows` implements `SegmentRows`; every segment — the writer's pre-opened ones too, not only
   spills — has its `journal_segments` row before its file exists. Start: files without a row are unlinked; rows without a
   file are dropped (no references exist before 4c2; 4c2 adds the pin check); next n = max(files, rows) + 1, so spill numbers
   are never reused.
6. **Node wiring**: `FLOTTILA_JOURNAL_DIR` (default `<dataDir>/journal`) and the journal limits as NodeConfig keys (finite,
   range-checked); Node.start opens the journal after the pool and migrations, closes it in the close path; the intake does not
   use it yet (4c2).
7. **Carries from 4a/4b**: close() waits for in-flight spills and persists (or refuses new ones) before releasing LOCK; a force
   failure in spill or persist (file or directory) fails the journal; a failed journal refuses spill and persist (retryable); a
   failed directory force after the move marks the spill broken; a fail-stop gauge/health accessor the admin health reports.

## Red tests (test-author)
NodeJournalIT (Testcontainers Postgres, real node start): .aDeployedNodeWithoutTheKeyRefusesStart / .aDevNodeWithoutTheKeyRefusesStart
/ .aGeneratedKeyNeedsItsOptInAndWarnsAtEveryStart / .bothKeySourcesSetRefuses / .aKeyThatCannotUnwrapTheJournalKeysRefusesStart
(names the key id, not the key) / .aNodeFileWithoutItsRowIsRegistered / .anotherNodesJournalIsRefused / .aSecondProcessIsRefused
/ .filesWithoutARowAreUnlinkedAndRowsWithoutAFileDropped / .spillNumbersAreNeverReusedAfterARestart / .theAdminHealthReportsAFailedJournal.
MasterKeySourceTest (unit): file, env, both, neither, bad base64, wrong length, file permissions, generated once.
JournalKeysIT: create on first use, unwrap at start, a tampered wrapped row refuses naming its id.
PgSegmentRowsIT + a journal test with the seam: the writer's pre-open inserts the row before the file exists.
Journal carries: close waits for an in-flight persist; a persist force failure fails the journal and later spills are refused.
MigrationsIT V12 tables, columns and indexes; no bytea in the new tables.
Red = compile error or failing assertion, agent/work/CORE-4c1-red.log.

## Constraints
CLAUDE.md design rules; only runtime touches Postgres; secrets never logged; sizes long, limits finite; no sleeps (fake clock,
held seams); javap on JDK 25 and the resolved Vert.x pg client jar for APIs. Floors: runtime 75 %, no decrease.

## Checks
`mvn -B -ntp -q -pl flottila-runtime -am verify`, reactor `verify -Pit`, coverage check; logs agent/work/CORE-4c1-*.log.

## Red approved (architect 2026-11-04)
Red observed: test-compile exit 1 on the missing API (agent/work/CORE-4c1-red.log); semantic reds against scratch stubs.
Tests: journal/MasterKeySourceTest, JournalLifecycleTest, JournalSegmentRowsTest, JournalKeysIT, PgSegmentRowsIT, NodeJournalIT
(+ JournalLockHolderChild, CaseDatabase), node/NodeConfigJournalTest; MigrationsIT V12 cases; counts 11 → 12 (MigrationsIT,
PgMessageStoreIT:76, PgDeploymentStoreIT:100). The signatures and SQL shapes in the handback are the contract:
`MasterKeySource.fromEnv(Map).load(Path journalDir, JournalFiles)`; `JournalKeys.load(Pool, SecretKey)`, `keyOf(tenant)`,
`apply(int)`; `new PgSegmentRows(Pool)`; `Journal.isFailed()`; `Journal.open(…, SegmentRows, Supplier<UUID>)`; `Node.journal()`;
`NodeConfig.journal()`; admin health check "journal"; the V12 column sets pinned by MigrationsIT.
Assumptions accepted: next n counts unlinked files and dropped rows; an in-flight persist that close waits for succeeds; a
refused identity changes nothing on disk or in Postgres; the broken-spill check runs before the failed-journal check.
Architect decisions: FLOTTILA_JOURNAL_KEY_GENERATE=true beside a given key is refused like both sources set. The ripple:
every existing node start in tests, cli, bench and the quickstart/compose dev setup needs a key source — the implementer
adds one centrally (a test fixture helper or the shared test config, FLOTTILA_JOURNAL_KEY_GENERATE=true or a test-only key
generated per run, never a key committed in the repo); the compose dev stack uses GENERATE=true with its notice. Each test
file touched for this is named in the handback and in the commit body as a required configuration change, not an expectation
change. The broken-spill mark after a failed directory force has no killing case (the failed journal refuses first): accepted.
