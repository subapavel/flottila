# Decisions

One line each: id, date, decision, reason. Reversible decisions the coordinator makes during
autonomous execution go here (WORKFLOW §6). Architecture changes go to `docs/findings/` and the
ARCHITECTURE.md changelog instead.

| Id | Date | Decision | Reason |
| --- | --- | --- | --- |
| D-001 | 2026-10-17 | Repository `github.com/subapavel/flottila`, public from the first push, Apache 2.0 + DCO, no CLA | owner's choice; open source until functional end to end (ARCHITECTURE §2) |
| D-002 | 2026-10-17 | Same repository from the milestone-1 state onward; tag `proto-m1` marks that state; the tree is restructured in place with `git mv` | file history survives; the prototype is a state of the project, not a separate project |
| D-003 | 2026-10-17 | `main` only, direct commits, no `develop`, no PRs while there is one writer | the cloud GitHub proxy rejects tag pushes and `gh pr`; PROGRESS.md is the review surface |
| D-004 | 2026-10-17 | Commit identity = the owner (author, Signed-off-by); `Co-authored-by: Claude` trailer | the DCO sign-off is the owner's legal statement; an agent cannot make it |
| D-005 | 2026-10-17 | Maven groupId `io.flottila`, artifactIds `flottila-*`; working name kept until an MVP exists | no time spent on branding before something works |
| D-006 | 2026-10-17 | Autonomous execution runs in Claude Code Cloud (4 vCPU / 16 GB VM); benchmarks there are labelled "cloud VM, 4 vCPU" | zero extra cost; everything Part B needs is available there; a VPS only for a final bench rerun |
| D-007 | 2026-10-17 | Coordinator Fable 5.1 at `high` effort; Sonnet 5.5 implementer, Haiku 5.5 scout, Fable 5.1 reviewer; two agents active at most, one writer | stretches the allowance; `max` reserved for design rework if PROGRESS shows it |
| D-008 | 2026-10-17 | Context7 is the first source for library APIs (`.mcp.json`, `mcp.context7.com` on the environment allowlist); `javap` on resolved jars is the fallback | the prototype's Context7 outages were a network allowlist problem |
| D-009 | 2026-10-17 | TDD from day 1; reviewer agent checks every task's tests for being real; coverage floors api/core 90 %, runtime/components 75 %, PIT ≥ 80 % on api/core, all phase-gate conditions | owner's requirement |
| D-010 | 2026-10-17 | Hexagonal in substance (SPI + store interfaces + ArchUnit direction), not in ceremony (no domain/application/infrastructure layers, no use-case classes, interfaces only with a second implementation) | the seams the architecture already defines are the ones tests need |
| D-011 | 2026-10-17 | During autonomous execution the coordinator is the architect of record and edits ARCHITECTURE.md on findings, with a changelog; the owner reviews the changes at the gate | owner's decision — no waiting on the owner for architecture corrections |
| D-012 | 2026-10-17 | Control plane (Quarkus API, React UI, git watcher) moves to the end of the plan; the runtime admin API is the test surface until then | owner's decision |
| D-013 | 2026-10-17 | The milestone-1 state of ARCHITECTURE v3.2 is kept at `docs/archive/architecture-v3.2.md` for the rationale behind rejected options (v3.3 §1 refers to it); everything else from the prototype era is deleted | v3.3 does not repeat the rationale |
