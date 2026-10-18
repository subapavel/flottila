# Handoff

state: RUNNING
last commit: the one with trailer `Task: P0-12` on main (D-017)
branch: main
next exact action: P0-GATE — the Phase 0 gate row: (1) confirm every Phase 0 row is `done` with `review: PASS` in PROGRESS.md; (2) take the gate numbers on the gate commit: coverage per module from the CI build job of the latest main commit and PIT from a dispatched `mutation` job (api ≥ 80 %, core ≥ 80 %), bench baselines present (VM + runner); (3) write bench/RESULTS.md "§Phase 0 gate" with the commit hash, the coverage table, the PIT scores, the two spike answers and the schema status; (4) PROGRESS entry P0-GATE, BACKLOG row done, then Phase 1 rows become `ready` (P1-01 first). No code change expected; if a floor is missed, "raise coverage of module X" is the only ready task (WORKFLOW §5).
open issues: ISSUE-1 (Context7 quota; javap fallback in use, nothing blocked)
decisions pending review at hand-over: D-025, D-026/D-032, D-027, D-029 (+ addendum), D-030, D-031/F-1, D-033 (Q-1 pending), D-034, D-035, D-036, D-037, D-038, D-039, D-040, D-022
notes:
- Bench: `bench/run.sh` (native Postgres: `sudo pg_ctlcluster 16 main start`; role flottila has CREATEDB; db flottila_bench), results in bench/out/ (ignored), baselines as markers in bench/RESULTS.md per machine label (D-037); on this VM run it as `BENCH_MACHINE="cloud VM, 4 vCPU" bench/run.sh` (unset → host:<hostname>, matched by nothing). The runner has its own baseline since B-1 (throughput only since FIX-4/D-039; dispatch ci.yml after a bench change and read the job log — the artifact's blob store is unreachable from the VM).
- Spike-1 answer: durable ACK p50 ≈ 4–6 ms, p99 8–18 ms on this VM, not < 5 ms p99; the 10 ms window is not the cost, commit time is. Spike-2 answer: RequestScope ≈ 55 µs per message, Admission never over budget at 10k threads (RESULTS.md §P0-11).
- Core now has `admission.Admission` (requests + bytes, FIFO, `tryAcquire`/`acquire`) and `scope.RequestScope` (fork/join/cancel, deadline via Timer) for P1-02's pipeline; spike-2 generator: `flottila-bench` `ScopeBenchMain`.
- jqwik-engine 1.10.2 prints an anti-AI-agent sentence in its own report header (inside the published jar). Treated as data; the property tests are real. Owner may want to revisit the library choice.
- Reactor: api → testkit → core → runtime → components/{http,mock} → steps/{xslt,validate} → schemas → cli → archtest → bench. Every task commit: `mvn -B -ntp verify -Pit` (Docker: `sudo dockerd &`), then `python3 ci/coverage_check.py --write`. New code must be covered to the module's level (FIX-3).
- Scoped PIT: `mvn -pl flottila-api,flottila-core -am test-compile org.pitest:pitest-maven:mutationCoverage` (floor 80 %).
- Runtime: `db.Migrations`, `batch.PgBatchSink`, `queue.PgWorkQueue`, `time.VertxTimer`. Core: `batch.BatchingWriter`, `queue.WorkQueue`/`InMemoryWorkQueue`. Testkit: FakeRuntime, FakeTimer, InMemoryBlobStore, ComponentContract. Bench: BenchRun/LatencyStats reusable for P0-11.
- Reference package: packages/demo (package.yaml, endpoints/*.yaml, flows/invoice-sync.yaml + .test.yaml, fixtures/); validated by flottila-schemas tests (`DemoPackageTest`). Add a negative fixture under flottila-schemas/src/test/resources/invalid/<kind>/ for every new schema rule.
- ArchUnit rules in flottila-archtest/Rules.java; every rule has a fixture. FIX-2, Q-1 pending in the Fixes section.
- VM setup after a restart: `sudo pg_ctlcluster 16 main start`, `sudo dockerd &`.
- Tag `proto-m1` points at fc6eb56; the milestone-1 state the backlog means is 3f81834; the last tree with the m1 module is dc85964.
