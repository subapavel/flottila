# Handoff

state: RUNNING
last commit: the one with trailer `Task: P0-06` on main (D-017)
branch: main
next exact action: P0-07 — import the WorkQueue claim SQL + tests from flottila-m1 (`runtime/WorkQueue.java`, `WorkQueueTest` 3 cases against Postgres) into `flottila-runtime` on the Vert.x 5 client (Testcontainers IT, `-Pit`); add the same-key fast path (ARCHITECTURE §7: a worker finishing a message takes the next one of the same key without a new claim round-trip); two-worker ordering test; jqwik interleaving test (per-key order holds under any claim interleaving — model-based against an in-memory reference). Coordinator-owned (claim path). Red first: the ported IT + the property test fail to compile. Schema: the m1 `work` table from db/init.sql until P0-08 migrates it (the IT creates the table itself).
open issues: ISSUE-1 (Context7 quota; javap fallback in use, nothing blocked)
decisions pending review at hand-over: D-025 (Hash/Hasher SPI), D-026/D-032 (api draft types incl. Timer), D-027 (detector scope), D-029 (2 pp coverage noise band), D-030 (ITs in the build job), D-031 (testkit depends on api only), D-022 (owner's UI stack, informational)
notes:
- Reactor order: api → testkit → core → runtime → components → steps → schemas → cli → archtest → m1.
  Every task commit: `mvn -B -ntp verify -Pit` (Docker must run: `sudo dockerd &`), then
  `python3 ci/coverage_check.py --write`, commit the baseline (D-018/D-029/D-030).
- Scoped PIT for the reviewer: `mvn -pl flottila-api,flottila-core -am test-compile org.pitest:pitest-maven:mutationCoverage`
  (floor 80 % on api/core; both at 100 % now).
- Testkit: FakeRuntime.create() (clock, timer, ids, blobs, hasher), InMemoryBlobStore, ChunkedReadStream,
  ComponentContract, NoBlockedPlatformThreads. Core: `batch.BatchingWriter<T>` over `BatchSink<T>`.
  Runtime: `batch.PgBatchSink<T>` (one batch = one transaction), `time.VertxTimer`.
- ArchUnit rules in flottila-archtest/Rules.java (D-020/D-020a/D-021); every rule has a fixture.
- FIX-2 (deterministic detector coverage) is pending; take it when the band bites again.
- VM setup after a restart: `sudo pg_ctlcluster 16 main start`, `sudo dockerd &`.
- Tag `proto-m1` points at fc6eb56; the milestone-1 state the backlog means is 3f81834 (m1 module).
