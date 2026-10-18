# Handoff

state: RUNNING
last commit: the one with trailer `Task: FIX-1` on main (D-017); P0-06 is red-first in progress (see WIP)
branch: main
next exact action: P0-06 — port the persistence batcher: logic + every existing test case from flottila-m1 (`runtime/BatchingWriter` + `BatchingWriterTest`, 6 cases, Mutiny `Uni` → Vert.x 5 `Future`, `Thread.sleep` → `FakeClock`/explicit flush triggers) into `flottila-core`; jqwik property test (no loss, no duplicate under any demand pattern); Postgres implementation in `flottila-runtime` (Testcontainers, `*IT` under `-Pit`). Coordinator-owned (the batcher). Red first: the ported test class + the property test fail to compile in core. Then remove `-DfailWhenNoMutations=false` from the mutation job (core has code, D-019).
open issues: ISSUE-1 (Context7 quota; javap fallback in use, nothing blocked)
decisions pending review at hand-over: D-025 (Hash/Hasher SPI), D-026 (api draft types), D-027 (detector scope), D-029 (2 pp coverage noise band), D-022 (owner's UI stack, informational)
WIP: P0-06 red tests written (api TimerTest + Runtime gains `timer`, testkit FakeTimerTest, core BatchingWriterTest/PropertyTest/FakeSink); after FIX-1 they are restored from `git stash` (p006-wip) and the implementation follows (D-031 planned: testkit depends on api only so core tests may use it).
notes:
- Reactor: api (25 classes, 47 tests), testkit (8 classes, 45 tests), core/runtime/components/steps/cli/schemas
  (package-info only), archtest (24), m1 (depends on api for Headers/Props/Key/Keys/Views, D-024).
- Testkit: FakeRuntime.create() → ingress()/store(); InMemoryBlobStore; ChunkedReadStream; ComponentContract
  (extend it per component); NoBlockedPlatformThreads extension + watchedPlatformPool.
- ArchUnit rules in flottila-archtest/Rules.java (D-020/D-020a allowlist, D-021); every rule has a fixture.
- Every task commit runs `python3 ci/coverage_check.py --write` after `mvn -B -ntp verify` (D-018; ±2 pp band
  and high-water mark, D-029).
  Scoped PIT for the reviewer: `mvn -pl <module> -am test-compile org.pitest:pitest-maven:mutationCoverage`.
- Open before P0-06/P0-08 (BACKLOG P0-08 row): where `-Pit` coverage is measured for the floors.
- VM setup after a restart: `sudo pg_ctlcluster 16 main start`, `sudo dockerd &` if needed.
- Tag `proto-m1` points at fc6eb56; the milestone-1 state the backlog means is 3f81834 (m1 module).
