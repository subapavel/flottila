# Handoff

state: RUNNING
last commit: the one with trailer `Task: P0-02` on main (D-017: `git log -1 --grep='^Task: P0-02'`)
branch: main
next exact action: P0-03 — ArchUnit boundary tests in `flottila-archtest`: api/core use the JDK plus Vert.x `Future`/`Buffer`/`ReadStream`/`WriteStream` only; components and steps depend on api only; only runtime touches Postgres/Kafka/S3/Valkey/ClickHouse clients; no cycles. Red first: a deliberate-violation fixture (test-scoped classes that break each rule) must make each rule fail, the real tree must pass. Add package-info to components/steps packages' subjects. Then P0-04 (testkit).
open issues: none
decisions pending review at hand-over: none yet
notes:
- Reactor: flottila-api, -core, -runtime, -components/{http,mock}, -steps/{xslt,validate}, -testkit,
  -schemas, -cli, -archtest, -m1. New modules carry only package-info until P0-05.
- Every task commit runs `python3 ci/coverage_check.py --write` after `mvn -B -ntp verify` and
  commits `ci/coverage-baseline.json` (D-018): CI fails on a decrease against the previous main
  commit and on a stale baseline. Open before P0-06/P0-08: the `build` job measures without
  `-Pit`, while CLAUDE.md counts Testcontainers tests in the 75 % floors — decide where the
  IT coverage is measured (merge jacoco.exec of both jobs, or baseline from the `integration`
  job) and record it as a decision.
- `mutation` CI job carries `-DfailWhenNoMutations=false` — remove it in P0-05 (D-019).
- VM setup after a restart: `sudo pg_ctlcluster 16 main start` (role/db `flottila` exist, init.sql
  applied), `sudo dockerd &` if `docker info` fails. Both were down at session start.
- Tag `proto-m1` points at fc6eb56 (first milestone-1 commit); the milestone-1 state the backlog
  means is 3f81834 (last commit before bootstrap). Porting reads the m1 module in the tree.
