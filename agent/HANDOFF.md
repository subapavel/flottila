# Handoff

state: NOT_STARTED
last commit: (bootstrap commit — fill in after the first push)
branch: main
next exact action: P0-01 — root pom with modules, JDK 25, Vert.x 5.1 BOM, old tree as `flottila-m1`; red first: an ArchUnit smoke test and a `flottila-api` placeholder test that fail until the modules exist
open issues: none
decisions pending review at hand-over: none yet
notes:
- Part A (brainstorm with the owner) finished 2026-10-17; its results are in docs/WORKFLOW.md,
  docs/GATE.md, docs/DECISIONS.md and the ARCHITECTURE.md amendments block.
- Tag `proto-m1` = the milestone-1 state. Build at that tag: JDK 21, `mvn -B verify`,
  Postgres tests skip themselves without a reachable Postgres on localhost:5432.
- Phase 0 starts by restructuring in place (docs/PROTOTYPE-VS-ARCHITECTURE.md).
