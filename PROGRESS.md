# Progress journal

Append-only. One entry per task, newest at the bottom, in the format of `docs/WORKFLOW.md §2`.
Every check carries its evidence state: `VERIFIED_PASS | EXECUTED_FAIL | NOT_RUN |
ENVIRONMENT_BLOCKED`. Measurements name the machine. Deviations name the finding.

## BOOTSTRAP · 2026-10-17 · (hash of the bootstrap commit)
Test first: none — documentation and configuration only
Changed: CLAUDE.md, AGENTS.md, docs/ (ARCHITECTURE.md with amendments, WORKFLOW.md, GATE.md,
DECISIONS.md, PROTOTYPE-VS-ARCHITECTURE.md, findings/, archive/architecture-v3.2.md), agent/
(BACKLOG.md, HANDOFF.md), .claude/agents/, .mcp.json, .github/workflows/ci.yml,
deploy/claude-cloud/, prompts/, LICENSE, NOTICE, README.md; deleted docs/CONTEXT.md and the
prototype-era README, CLAUDE.md, AGENTS.md
Checks: mvn verify NOT_RUN (no code change; the milestone-1 tree is untouched) · CI NOT_RUN (first push pending)
Measured: nothing
Review: n/a (Part A, with the owner)
Decisions: D-001 … D-013
Deviation: none
Next: P0-01

## P0-01 · 2026-10-17 · commit with trailer `Task: P0-01` (D-017)
Environment (observed on this VM, cloud VM 4 vCPU / 15 GB / 30 GB writable): java 25.0.4.1 LTS at /opt/temurin-25 (JAVA_HOME set), Maven 3.9.11, Docker client 29.8.2 with the daemon down — started `dockerd` by hand, `docker pull postgres:17` OK; Maven Central reachable (`dependency:resolve` on the old pom exit 0; raw curl rate-limited after ~30 requests); Context7 MCP tools present and answering; native PostgreSQL 16.15 cluster was down — started, role/db `flottila` created, `db/init.sql` applied; old tree `mvn -B -ntp verify` on JDK 25 green with 9/9 tests run. Tag `proto-m1` points at fc6eb56 (the first milestone-1 commit), not at 3f81834 (the last one before bootstrap); the tree at 3f81834 is what the backlog calls the milestone-1 state. No ISSUE needed.
Test first: ApiModuleTest.{buildRunsOnJdk25, vertxFutureIsVersion5WithAwait, mutinyIsAbsentFromApiClasspath} (flottila-api); ModuleBoundarySmokeTest.{apiDependsOnNoOtherFlottilaModule, flottilaPackagesAreFreeOfCycles} (flottila-archtest). Red observed: target/red1.log — both ArchUnit rules "failed to check any classes" before the modules had classes; `-Dvertx.version=4.5.10` makes vertxFutureIsVersion5WithAwait fail with NoSuchMethodException Future.await().
Changed: root pom (reactor of 10 new modules + flottila-m1; Vert.x 5.1.10 / JUnit 5.14.4 / Testcontainers 2.0.5 BOMs, jqwik, ArchUnit, slf4j managed; compiler release 25, enforcer Java [25,26), surefire/failsafe 3.6.0 with `-Pit`, JaCoCo floors checked at verify, PIT managed); module poms with the dependency direction of §12; one `package-info.java` per Java module; `src/` → `flottila-m1/src` by `git mv` (37 renames), `flottila-m1/pom.xml` pins Vert.x 4.5.10 through its own BOM import and skips JaCoCo; `.github/workflows/ci.yml` Java 25; README build line; CLAUDE.md "Context hygiene" (owner's interjection, verbatim); `flottila-schemas/README.md`.
Checks: mvn -B -ntp verify VERIFIED_PASS (target/green2.log: api 3, archtest 2, m1 9 tests, 0 skipped, Postgres up) · ArchUnit VERIFIED_PASS (2 smoke rules, non-empty subject) · JaCoCo check VERIFIED_PASS on api ("All coverage checks have been met"; other new modules have no classes yet, m1 skipped) · PIT plugin loads on api VERIFIED_PASS, mutation score NOT_RUN (no production code → "No mutations found", target/pit-api.log) · m1 keeps Vert.x 4.5.10 transitively VERIFIED_PASS (flottila-m1/target/m1-tree.txt) · integration (-Pit) NOT_RUN (no IT classes yet) · CI NOT_RUN here (runs on push)
Measured: nothing
Review: PASS (observations carried into P0-02: JaCoCo check skips a module that has classes but no exec file; the nightly PIT job must be scoped to api + core and must not pass -DskipTests unverified)
Decisions: D-014 … D-017
Deviation: none
Next: P0-02 (CI finalised); P0-03 and P0-04 are ready too
