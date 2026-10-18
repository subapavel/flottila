# Handoff

state: RUNNING
last commit: the one with trailer `Task: P0-05` on main (D-017)
branch: main
next exact action: P0-04 — `flottila-testkit`: fake `Runtime` (fixed/fake `Clock`, deterministic `IdGenerator`, in-memory `BlobStore` with tree limit and max-size enforcement, `Hasher.sha256()`), `FakeClock` driving deadlines, contract-suite base class for `Component` (consumer handler → Ack, producer send, health; backpressure/re-open/ACK-after-durable/spill/cancel grow in P1-06), in-memory `BlobStore` moved out of the api test tree, blocked-platform-thread detector (JUnit extension that fails a test which blocks a platform thread beyond a threshold; fake clock, no sleeps). Red first: a sample pipeline test (Pipeline of Stage.of(steps) over the fake Runtime) asserting < 100 ms wall time, and a detector test with a deliberately blocked platform thread. Implementer may do it (testkit is not coordinator-only) with the api as given; the coordinator writes the packet with the failing tests.
open issues: ISSUE-1 (Context7 quota; javap fallback in use, nothing blocked)
decisions pending review at hand-over: D-025 (Hash/Hasher SPI), D-026 (api draft types), D-022 (owner's UI stack, informational)
notes:
- Reactor: flottila-api (25 classes, 46 tests), -core, -runtime, -components/{http,mock}, -steps/{xslt,validate},
  -testkit, -schemas, -cli, -archtest, -m1 (depends on api for Headers/Props/Key/Keys/Views, D-024).
- ArchUnit rules in flottila-archtest/Rules.java (D-020 allowlist incl. primitive arrays, D-021); every
  rule has a violation fixture. api/core: JDK (jrt:) minus java.sql, api + the Vert.x allowlist only.
- Every task commit runs `python3 ci/coverage_check.py --write` after `mvn -B -ntp verify` (D-018).
  Scoped PIT for the reviewer: `mvn -pl flottila-api test-compile org.pitest:pitest-maven:mutationCoverage`.
- `mutation` CI job keeps `-DfailWhenNoMutations=false` until core has code (P0-06), then remove (D-019).
- Open before P0-06/P0-08 (BACKLOG P0-08 row): where `-Pit` coverage is measured for the floors.
- VM setup after a restart: `sudo pg_ctlcluster 16 main start`, `sudo dockerd &` if needed.
- Tag `proto-m1` points at fc6eb56; the milestone-1 state the backlog means is 3f81834 (m1 module).
