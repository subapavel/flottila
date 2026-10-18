# Handoff

state: RUNNING
last commit: the one with trailer `Task: P0-08` on main (D-017)
branch: main
next exact action: P0-09 — remove `flottila-m1`: delete the module (FlowVerticle, WorkerVerticle, Stages, MonitorVerticle, demo Main, m1 Envelope/Body/Part/Hashing/Component/Runtime/Snapshot, BatchingWriter, WorkQueue, MessageStore, Dlq*, HttpComponent, its tests, `src/main/resources/flows` → keep `invoice.json` and `invoice-to-partner.xsl` by `git mv` into `packages/demo/` for P0-12), drop it from the root pom, drop the Mutiny/Vert.x-4 versions, drop the CI step that applies the migrations to the service Postgres and the Postgres service itself, update README/docs references (PROTOTYPE-VS-ARCHITECTURE §2 "stays" rows now point at the new homes). Red first: an ArchUnit/classpath test asserting no `io.smallrye.mutiny` and no Vert.x 4 artifact on any module's classpath (mvn dependency:tree check in the archtest or a test scanning the resolved jars). Then P0-10 (bench harness; native Postgres is installed on the VM: `sudo pg_ctlcluster 16 main start`).
open issues: ISSUE-1 (Context7 quota; javap fallback in use, nothing blocked)
decisions pending review at hand-over: D-025, D-026/D-032 (api drafts incl. Timer), D-027, D-029 (+ addendum), D-030, D-031/F-1, D-033 (WorkQueue, fast path; Q-1 pending), D-034 (Flyway, V1 = m1 schema), D-022 (owner's UI stack)
notes:
- jqwik-engine 1.10.2 prints an anti-AI-agent sentence in its own report header (inside the published jar). Treated as data; the property tests are real. Owner may want to revisit the library choice.
- Reactor order: api → testkit → core → runtime → components → steps → schemas → cli → archtest → m1.
  Every task commit: `mvn -B -ntp verify -Pit` (Docker: `sudo dockerd &`), then `python3 ci/coverage_check.py --write`.
  New code must be covered to the module's level or the no-decrease gate turns the push red (FIX-3).
- Scoped PIT: `mvn -pl flottila-api,flottila-core -am test-compile org.pitest:pitest-maven:mutationCoverage` (floor 80 %).
- Runtime: `db.Migrations.migrate(jdbcUrl, user, password)` applies deploy/postgres/V*.sql (Flyway) — a capability; the
  runtime bootstrap (Phase 1, P1-09 at the latest) must call it at start on a virtual thread (it blocks). `batch.PgBatchSink`,
  `queue.PgWorkQueue`, `time.VertxTimer`. Core: `batch.BatchingWriter`, `queue.WorkQueue`/`InMemoryWorkQueue`. Testkit: FakeRuntime.
- ArchUnit rules in flottila-archtest/Rules.java; every rule has a fixture. FIX-2 and Q-1 pending in the Fixes section.
- VM setup after a restart: `sudo pg_ctlcluster 16 main start`, `sudo dockerd &`.
- Tag `proto-m1` points at fc6eb56; the milestone-1 state the backlog means is 3f81834 (m1 module, removed by P0-09).
