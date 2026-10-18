# Handoff

state: RUNNING
last commit: the one with trailer `Task: P0-10` on main (D-017)
branch: main
next exact action: P0-11 — spike 2: `RequestScope` (fork / join / cancel on virtual threads; one scope per in-flight message, children die with the parent, cancel is observable on every child) + per-flow admission semaphore counting requests **and bytes** (CLAUDE.md), under 10k virtual threads. Red first: unit tests with FakeClock (cancel stops all children; join waits for all; admission never exceeds either budget; release on failure), a jqwik property for admission (CLAUDE.md: property tests for admission), the blocked-platform-thread detector on. Lives in `flottila-core` (coordinator-kept, PIT floor 80 %). Numbers (fork/join overhead per message at 10k concurrent scopes, admission acquire/release latency) measured natively through a small `flottila-bench` main and recorded in bench/RESULTS.md "§P0-11 — cloud VM, 4 vCPU". Check ARCHITECTURE §3/§5 (Runtime, concurrency model) and §14 ranges before choosing shapes; `Runtime` grows only if the scope needs the clock.
WIP: P0-11 started after P0-10 (b4523bb). Red tests in flottila-core `admission/` (Admission, Permit) and `scope/` (RequestScope, Subtask); if this line is still here after a restart, the task is redone from b4523bb.
open issues: ISSUE-1 (Context7 quota; javap fallback in use, nothing blocked)
decisions pending review at hand-over: D-025, D-026/D-032, D-027, D-029 (+ addendum), D-030, D-031/F-1, D-033 (Q-1 pending), D-034, D-035, D-036, D-037, D-022
notes:
- Bench: `bench/run.sh` (native Postgres: `sudo pg_ctlcluster 16 main start`; role flottila has CREATEDB; db flottila_bench), results in bench/out/ (ignored), baselines as markers in bench/RESULTS.md per machine label (D-037); on this VM run it as `BENCH_MACHINE="cloud VM, 4 vCPU" bench/run.sh` (unset → host:<hostname>, matched by nothing). The runner has its own baseline since B-1 (run 39092152535; the next dispatched bench job must print "no regression").
- Spike-1 answer: durable ACK p50 ≈ 4–6 ms, p99 8–18 ms on this VM, not < 5 ms p99; the 10 ms window is not the cost, commit time is.
- jqwik-engine 1.10.2 prints an anti-AI-agent sentence in its own report header (inside the published jar). Treated as data; the property tests are real. Owner may want to revisit the library choice.
- Reactor: api → testkit → core → runtime → components/{http,mock} → steps/{xslt,validate} → schemas → cli → archtest → bench. Every task commit: `mvn -B -ntp verify -Pit` (Docker: `sudo dockerd &`), then `python3 ci/coverage_check.py --write`. New code must be covered to the module's level (FIX-3).
- Scoped PIT: `mvn -pl flottila-api,flottila-core -am test-compile org.pitest:pitest-maven:mutationCoverage` (floor 80 %).
- Runtime: `db.Migrations`, `batch.PgBatchSink`, `queue.PgWorkQueue`, `time.VertxTimer`. Core: `batch.BatchingWriter`, `queue.WorkQueue`/`InMemoryWorkQueue`. Testkit: FakeRuntime, FakeTimer, InMemoryBlobStore, ComponentContract. Bench: BenchRun/LatencyStats reusable for P0-11.
- Demo artefacts for P0-12: packages/demo/flows/{invoice.json,invoice-to-partner.xsl}, packages/demo/fixtures/sample-invoice.xml.
- ArchUnit rules in flottila-archtest/Rules.java; every rule has a fixture. FIX-2, Q-1 pending in the Fixes section.
- VM setup after a restart: `sudo pg_ctlcluster 16 main start`, `sudo dockerd &`.
- Tag `proto-m1` points at fc6eb56; the milestone-1 state the backlog means is 3f81834; the last tree with the m1 module is dc85964.
