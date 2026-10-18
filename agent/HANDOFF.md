# Handoff

state: RUNNING
last commit: the one with trailer `Task: P0-03` on main (D-017)
branch: main
next exact action: P0-04 — `flottila-testkit` skeleton: fake `Runtime` (clock, timers, id generator injected; no static singletons), fake clock driving backoff/leases/deadlines, in-memory stores, contract-suite base classes, blocked-platform-thread detector. Red first: a sample flow test that must run in < 100 ms and a detector test that fails on a deliberately blocked platform thread. Note: `Runtime`/`Step`/`Envelope` types arrive in P0-05 (depends on P0-04), so P0-04 builds the testkit pieces that do not need them (clock, detector, in-memory store skeletons against core interfaces it introduces) and leaves the fake `Runtime` wiring to P0-05 if the api record is not there yet — say so in the PROGRESS entry.
open issues: none
decisions pending review at hand-over: none yet
notes:
- Reactor: flottila-api, -core, -runtime, -components/{http,mock}, -steps/{xslt,validate}, -testkit,
  -schemas, -cli, -archtest, -m1. New modules carry only package-info until P0-05.
- ArchUnit rules live in flottila-archtest/Rules.java (D-020 allowlist, D-021); every rule has a
  violation fixture — adding a rule means adding its fixture. The tree rules check only
  package-info today; expect them to bite in P0-05 (e.g. `Hashing` with net.openhft in api).
- Every task commit runs `python3 ci/coverage_check.py --write` after `mvn -B -ntp verify` and
  commits `ci/coverage-baseline.json` (D-018). Open before P0-06/P0-08 (BACKLOG P0-08 row): where
  `-Pit` coverage is measured for the floors.
- `mutation` CI job carries `-DfailWhenNoMutations=false` — remove it in P0-05 (D-019).
- VM setup after a restart: `sudo pg_ctlcluster 16 main start`, `sudo dockerd &` if needed.
- Tag `proto-m1` points at fc6eb56 (first milestone-1 commit); the milestone-1 state the backlog
  means is 3f81834. Porting reads the m1 module in the tree.
