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

## P0-02 · 2026-10-17 · commit with trailer `Task: P0-02` (D-017)
Test first: ci/test_coverage_check.py, 17 cases — test_measures_line_and_branch_percent_per_module, test_nested_module_is_keyed_by_its_path, test_package_info_only_module_without_report_is_not_a_problem, test_report_without_counters_has_no_number, test_module_without_measurable_lines_has_no_number, test_module_with_classes_but_no_report_is_a_failure, test_ignored_legacy_module_may_lack_a_report, test_decrease_against_baseline_fails, test_equal_or_higher_passes, test_new_module_without_baseline_passes, test_rounds_to_one_decimal, test_rounding_to_one_decimal_absorbs_noise_only, test_write_baseline_round_trips, test_main_exit_code_reports_problems, test_baseline_ref_reads_the_committed_file_not_the_working_tree, test_baseline_ref_where_the_file_does_not_exist_yet_passes, test_unreadable_baseline_ref_is_an_error_not_a_pass. Red observed: `python3 -m unittest ci.test_coverage_check` → ImportError, no `ci/coverage_check.py`. The six cases from test_package_info_only… onward (except the zero-line one) were added after review round 1; their red is the reviewer's two surviving mutants, re-run by the coordinator against the new suite: `returncode != 0` in `_load_baseline` → FAILED (failures=1, errors=2), `_has_real_classes → True` → FAILED (failures=1).
Changed: ci/coverage_check.py (JaCoCo no-decrease gate, D-018), ci/coverage-baseline.json (empty: no module has measurable lines yet), ci/__init__.py; .github/workflows/ci.yml — `build` runs the gate's self-test and the no-decrease check against the previous main commit and uploads surefire + JaCoCo XML; `mutation` scoped to api + core without `-DskipTests` (D-019); `bench` comment; CI run 3 on the P0-01 commit observed green before this change (dco, build, integration).
Checks: unittest ci.test_coverage_check VERIFIED_PASS (17/17) · reviewer's two mutants killed VERIFIED_PASS (the PIT-equivalent for Python tooling) · `--baseline-ref no-such-ref` exits 2 VERIFIED_PASS · `--write` then `git diff --exit-code` clean VERIFIED_PASS · coverage_check against the real tree VERIFIED_PASS ("coverage: no decrease", stale root target/classes from the pre-reactor build correctly flagged first, then removed) · mutation job command locally (`-pl flottila-api,flottila-core -am -DfailWhenNoMutations=false test-compile pitest:mutationCoverage`) VERIFIED_PASS (target/pit-job.log: root and core skipped by PIT for lack of test classes, api "No mutations found" as a warning, exit 0) · `-DskipTests` makes pitest skip silently EXECUTED (target/pit-skiptests.log, that is why the job no longer passes it) · mvn -B -ntp verify VERIFIED_PASS as a reused observation (pom and Java tree untouched since P0-01, green3.log) · CI on this commit: observed after the push, recorded in agent/HANDOFF.md and in the P0-03 entry; the BACKLOG row turns `done` there
Measured: nothing
Review: round 1 FAIL — untested `_load_baseline` git path (every git error fell back to `{}` = pass), workflow fetch failure silently fell back to HEAD, one vacuous test. Fixed: `BaselineError` + exit 2 on anything but "file absent at ref", fetch failure fails the step, a "committed baseline matches the measurement" step (`--write` + `git diff --exit-code`), PR DCO range base..head, the tests above. Round 2 FAIL — the ledger edits of round 1 had not reached the tree (a failed shell command short-circuited them). Round 3: PASS
Decisions: D-018, D-019
Deviation: none
Next: P0-03 (ArchUnit boundary rules in flottila-archtest), then P0-04 (testkit)
