# Handoff

state: RUNNING
last commit: the one with trailer `Task: P0-01` on main (D-017: `git log -1 --grep='^Task: P0-01'`)
branch: main
next exact action: P0-02 — CI finalised on Temurin 25: `build` job adds the JaCoCo no-decrease check against the previous main (floors already halt `verify`), DCO job stays, `integration` runs `-Pit`, `bench` runs `bench/run.sh` once P0-10 adds it, `mutation` runs PIT scoped to `-pl flottila-api,flottila-core` (every empty module fails with "No mutations found"; check that `-DskipTests` does not make pitest skip silently); the JaCoCo check must also fail a module that has classes but no execution data (today `jacoco:check` skips on a missing exec file); red first: a workflow-lint or a script test that fails until the no-decrease step exists. P0-03 (ArchUnit rules) and P0-04 (testkit) are `ready` as well; take P0-02 first (backlog order).
open issues: none
decisions pending review at hand-over: none yet
notes:
- Reactor: flottila-api, -core, -runtime, -components/{http,mock}, -steps/{xslt,validate}, -testkit,
  -schemas, -cli, -archtest, -m1. New modules carry only package-info until P0-05.
- `flottila-m1` = the old tree (Vert.x 4.5 + Mutiny, own BOM import pins 4.5.10, JaCoCo skipped);
  its 9 tests run against Postgres on localhost:5432 and skip themselves without it.
- VM setup after a restart: `sudo pg_ctlcluster 16 main start` (role/db `flottila` exist, init.sql
  applied), `sudo dockerd &` if `docker info` fails. Both were down at session start.
- Tag `proto-m1` points at fc6eb56 (first milestone-1 commit); the milestone-1 state the backlog
  means is 3f81834 (last commit before bootstrap). Porting reads the m1 module in the tree.
- Owner pushed 2671a66 (setup.sh v2) from the laptop; rebased onto it without conflict.
