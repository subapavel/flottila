# Handoff

state: RUNNING
last commit: the one with trailer `Task: P0-11` on main (D-017); FIX-4 (720d441) and B-1 (9c18826) before it
branch: main
next exact action: P0-12 — JSON Schemas in `flottila-schemas`: flow, package, endpoint, `*.test.yaml`, each with `schemaVersion`, marked `draft — review at hand-over`; convert `packages/demo/flows/invoice.json` into `packages/demo/flows/invoice-sync.yaml` and validate the demo package against the schemas in a test (red first: a schema test that loads every `*.yaml` under packages/ and validates it; a negative fixture per schema). Check ARCHITECTURE §7/§8 (flow and package model, delivery modes) and the prototype's `invoice.json` shape; the schema validator library must be Apache/MIT/BSD/EPL (networknt json-schema-validator is Apache-2.0 — verify the resolved licence in the pom before adding; Context7 is out of quota, use the jar's META-INF). Publishing = the schemas are resources of the module and copied to `docs/schemas/`.
open issues: ISSUE-1 (Context7 quota; javap fallback in use, nothing blocked)
decisions pending review at hand-over: D-025, D-026/D-032, D-027, D-029 (+ addendum), D-030, D-031/F-1, D-033 (Q-1 pending), D-034, D-035, D-036, D-037, D-038, D-039, D-022
notes:
- Bench: `bench/run.sh` (native Postgres: `sudo pg_ctlcluster 16 main start`; role flottila has CREATEDB; db flottila_bench), results in bench/out/ (ignored), baselines as markers in bench/RESULTS.md per machine label (D-037); on this VM run it as `BENCH_MACHINE="cloud VM, 4 vCPU" bench/run.sh` (unset → host:<hostname>, matched by nothing). The runner has its own baseline since B-1 (throughput only since FIX-4/D-039; dispatch ci.yml after a bench change and read the job log — the artifact's blob store is unreachable from the VM).
- Spike-1 answer: durable ACK p50 ≈ 4–6 ms, p99 8–18 ms on this VM, not < 5 ms p99; the 10 ms window is not the cost, commit time is. Spike-2 answer: RequestScope ≈ 55 µs per message, Admission never over budget at 10k threads (RESULTS.md §P0-11).
- Core now has `admission.Admission` (requests + bytes, FIFO, `tryAcquire`/`acquire`) and `scope.RequestScope` (fork/join/cancel, deadline via Timer) for P1-02's pipeline; spike-2 generator: `flottila-bench` `ScopeBenchMain`.
- jqwik-engine 1.10.2 prints an anti-AI-agent sentence in its own report header (inside the published jar). Treated as data; the property tests are real. Owner may want to revisit the library choice.
- Reactor: api → testkit → core → runtime → components/{http,mock} → steps/{xslt,validate} → schemas → cli → archtest → bench. Every task commit: `mvn -B -ntp verify -Pit` (Docker: `sudo dockerd &`), then `python3 ci/coverage_check.py --write`. New code must be covered to the module's level (FIX-3).
- Scoped PIT: `mvn -pl flottila-api,flottila-core -am test-compile org.pitest:pitest-maven:mutationCoverage` (floor 80 %).
- Runtime: `db.Migrations`, `batch.PgBatchSink`, `queue.PgWorkQueue`, `time.VertxTimer`. Core: `batch.BatchingWriter`, `queue.WorkQueue`/`InMemoryWorkQueue`. Testkit: FakeRuntime, FakeTimer, InMemoryBlobStore, ComponentContract. Bench: BenchRun/LatencyStats reusable for P0-11.
- Demo artefacts for P0-12: packages/demo/flows/{invoice.json,invoice-to-partner.xsl}, packages/demo/fixtures/sample-invoice.xml.
- ArchUnit rules in flottila-archtest/Rules.java; every rule has a fixture. FIX-2, Q-1 pending in the Fixes section.
- VM setup after a restart: `sudo pg_ctlcluster 16 main start`, `sudo dockerd &`.
- Tag `proto-m1` points at fc6eb56; the milestone-1 state the backlog means is 3f81834; the last tree with the m1 module is dc85964.
