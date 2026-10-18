# Handoff

state: RUNNING
last commit: the one with trailer `Task: P0-09` on main (D-017)
branch: main
next exact action: P0-10 — bench harness in `bench/`: `bench/run.sh` (used by the CI `bench` job) starts the runtime pieces that exist (there is no HTTP runtime yet: the harness measures the persistence batcher + PgBatchSink against native Postgres directly — the prototype's "batched persistence" spike, ARCHITECTURE §13 Phase 0: "the prototype's batched persistence rerun on native Linux Postgres (closes the < 5 ms p99 question)"), records throughput and the durable-ACK latency distribution (p50/p95/p99) of `BatchingWriter.save` → commit, with and without `synchronous_commit=off`, batch 100/10 ms/4 like bench/RESULTS.md §STEP 1.5; writes `bench/out/*.json`; a regression check against the baseline section in RESULTS.md (> 20 % slower fails). Native Postgres on this VM: `sudo pg_ctlcluster 16 main start` (role/db `flottila`, V1 applied earlier; apply deploy/postgres/V*.sql to a fresh `flottila_bench` db). Record in bench/RESULTS.md "§Phase 0 baseline — cloud VM, 4 vCPU" with date, config, commit. Red first: a unit test of the harness's statistics/regression logic (Java in a `bench` module? or Python with unittest like ci/) — decide and record. Java load generator in a new `flottila-bench` module (depends on runtime, not shipped) is the cleaner choice: it reuses PgBatchSink and VertxTimer.
open issues: ISSUE-1 (Context7 quota; javap fallback in use, nothing blocked)
decisions pending review at hand-over: D-025, D-026/D-032, D-027, D-029 (+ addendum), D-030, D-031/F-1, D-033 (Q-1 pending), D-034, D-035, D-022
notes:
- jqwik-engine 1.10.2 prints an anti-AI-agent sentence in its own report header (inside the published jar). Treated as data; the property tests are real. Owner may want to revisit the library choice.
- Reactor: api → testkit → core → runtime → components/{http,mock} → steps/{xslt,validate} → schemas → cli → archtest.
  Every task commit: `mvn -B -ntp verify -Pit` (Docker: `sudo dockerd &`), then `python3 ci/coverage_check.py --write`.
  New code must be covered to the module's level or the no-decrease gate turns the push red (FIX-3).
- Scoped PIT: `mvn -pl flottila-api,flottila-core -am test-compile org.pitest:pitest-maven:mutationCoverage` (floor 80 %).
- Runtime: `db.Migrations` (call it from the bootstrap in Phase 1), `batch.PgBatchSink`, `queue.PgWorkQueue`, `time.VertxTimer`.
  Core: `batch.BatchingWriter`, `queue.WorkQueue`/`InMemoryWorkQueue`. Testkit: FakeRuntime, FakeTimer, InMemoryBlobStore, ComponentContract.
- Demo artefacts for P0-12: packages/demo/flows/{invoice.json,invoice-to-partner.xsl}, packages/demo/fixtures/sample-invoice.xml.
- ArchUnit rules in flottila-archtest/Rules.java; every rule has a fixture. FIX-2 and Q-1 pending in the Fixes section.
- VM setup after a restart: `sudo pg_ctlcluster 16 main start`, `sudo dockerd &`.
- Tag `proto-m1` points at fc6eb56; the milestone-1 state the backlog means is 3f81834 (the m1 module is gone since P0-09).
