# MAIN-1 — why CI is red on main (2026-11-15, read from GitHub Actions logs; nothing run or triggered)

Workflow `ci`, branch `main`. Names are the ones before commit B (`io.flottila.*`, `flottila-testkit`).

## The blocker in 10 of the 12 build jobs of runs 189-194
- `io.flottila.testkit.SamplePipelineTest.samplePipelineRunsInTheFakeRuntimeInUnder100Ms` (SamplePipelineTest.java:48):
  `assertTrue(elapsed.toMillis() < 100, "sample pipeline took " + elapsed.toMillis() + " ms")`; `started = System.nanoTime()` is taken
  at :25, before `FakeRuntime.create()` (:27). A wall-clock bound of 100 ms over runtime creation plus the pipeline.
- Observed on the runners: 162, 178, 181, 192, 236, 245, 264, 273, 283, 303, 337 ms. It is the first test class surefire runs in the
  testkit module, so the reactor stops after about 80 s and every later module is SKIPPED.
- Run 194 (id 43676345064, 0481fac), jobs 132697225953 (245 ms) and 132697225978 (283 ms). Same test in 189, 190, 192 (both jobs), 191
  (main-18.6 job), 193 (floor-17.11 job).
- Locally (4 vCPU, Oracle JDK 25.0.5) it passes; the runner is Temurin 25.0.5, `MAVEN_OPTS -Xmx3g`. No JDK change between the last
  passing Verify and the first failing one.

## The two jobs that got past it hit the BASE-1 red set
- Run 191 floor-17.11 job 132669935763: `StepBodiesIT.aRerunOfAStampingStep…:442->rowsOf:324->until:373 not within 20 s: fixture: 5 rows
  of sb-rerun` after 13:26 (FIX-212).
- Run 193 main-18.6 job 132679541252: `PeerProtocolTest.aLinkPausedByAnAbandonedConsumer…:767 … expected: <DIALER> but was: <LISTENER>`
  (FIX-214).

## History
- Last `ci` success on main: run 154 (id 41421959062, schedule, 2026-11-02, head b5d3a23).
- Run 155 (f8a28b9, "chore(workflow): skills adapted from mattpocock/skills…"): only the DCO job failed — the commit has no
  Signed-off-by trailer. The DCO job stayed red for the runs after it while that commit was in the checked range.
- The Verify step: success in run 169 (schedule 2026-11-04, head 7d92aef), SamplePipelineTest failure (192 ms) in run 170 (schedule
  2026-11-05, the same head). Same commit, pass one day and fail the next: the bound is the runner's speed, not a change in the code.
- Runs 156-168 and 171-188 were not opened.
- The stderr annotations "Exception in thread …" (ComponentContractSelfTest "variant-handler-failure-lost", PollingConsumerContractSelfTest
  "variant-round") are expected output of passing self-tests, not failures.

## After FIX-217 (read 2026-11-15 07:12 UTC)
- Run 201 (id 43688088414, push, head a686bcf6 - the FIX-217 commit): DCO job success (first-parent, D-150).
- Build job "Postgres floor 17.11" (132732684892): testkit SUCCESS (1:00 min) - FIX-217's runner half is observed. The job then fails in
  `rail8-runtime`'s unit tests after 3:17 min: `ReplicaStartTest.theStartRoomCheckCountsTheReplicas:159 ... expected: <3465> but was:
  <3514>`, Tests run: 1563, Failures: 1 (FIX-213: 49 bytes = one segment header). Modules after runtime are skipped.
- Build job "Postgres main 18.6" (132732684930): passed the runtime unit tests (the race of FIX-213 fell the other way) and failed in
  the runtime ITs after 13:46 min: `StepBodiesIT.aRerunOfAStampingStep...:442->rowsOf:324->until:373 not within 20 s: fixture: 5 rows
  of sb-rerun`, Tests run: 709, Failures: 1 (FIX-212). Modules after runtime are skipped.
- What stops the build next, in this order: FIX-213 (runtime unit, a race), FIX-212 (runtime IT, every run seen so far). Nothing after
  the runtime module has been reached on a runner since 2026-11-05, so cli, sim, archtest and bench are unobserved there.
- Reading a job's log: `get_job_logs` with `tail_lines` 112-120 reaches the "[ERROR] Failures" block; 60 does not.

## Run 202 (read 2026-11-15 09:30 UTC) — the first run with `-f platform/pom.xml`

Run 202 (id 43695734886, head 29379f9c, BRAND-3 step 2): DCO success. The reactor summary lists the platform's modules only — the
root aggregator's `mapping` is not built by this workflow, as intended. Both build jobs fail where run 201 did:
- job PG 17.11 (132755722551), 2 min 44 s: `ReplicaStartTest.theStartRoomCheckCountsTheReplicas:159 … expected: <3465> but was: <3514>`
  (FIX-213, fixed in 02ef7bf8 — run 203 is its proof).
- job PG 18.6 (132755722764), 17 min: `StepBodiesIT…:442->rowsOf:324->until:373 not within 20 s: fixture: 5 rows of sb-rerun`
  (FIX-212), 709 ITs run in the runtime module.
Nothing after the runtime module has been observed on a runner since 2026-11-05.

## `ci` run 203 (id 43699072882, head 02ef7bf8, push to main, 2026-11-15) — read 2026-11-15

- DCO job: success.
- Both build jobs (Postgres main 18.6, job 132765949771; Postgres floor 17.11, job 132765949788) fail in the Verify step, in
  `Rail8 :: runtime`, with the same single failure of 709 integration tests:
  `StepBodiesIT.aRerunOfAStampingStepKeepsEachAttemptsRowAndAReplayAfterARestartReadsAttemptTwosBytes:442->rowsOf:324->until:373
  not within 20 s: fixture: 5 rows of sb-rerun` (FIX-212). Red on both jobs of one commit: a wrong expectation, not timing.
- FIX-213's proof on the runner: the runtime module's unit tests (surefire) passed on both jobs — `ReplicaStartTest` did not fail;
  the failsafe phase was reached and the coverage floors of `rail8-runtime` were met ("All coverage checks have been met").
- Not run on the runner because the reactor stopped at runtime: component mock, cli, sim, archtest, bench; the jobs `mutation`,
  `stack`, `bench` were skipped. Times: 15:35 and 16:59 min.

## `mapping-ci` run 1 (id 43700177973, head 6779670a, push to main, 2026-11-15) — the first run from the root (MAP-6)

- `mapping · ui`: success (npm ci, licences, generated types, typecheck, lint, test, test:browser, e2e, build) — 2.5 min.
- `mapping · backend`: success (script tests, "Build, test, floors, licences, bans" 4 min, `contract/` up to date, the LF check) — 7 min.
- `mapping · windows`: success ("Whole reactor compiles; Spotless, enforcer, licences") — 2.5 min.
- `mapping · stack`: success (jar with the UI inside, Playwright, the `@stack` suite against the jar) — 1.5 min.
- `mapping-bench` and `mapping-nightly` have no run yet (dispatch and cron); MAP-6 stays open for those two.

## `ci` run 207 (id 43701701542, head df76c64e, FIX-212) — read 2026-11-15

- Postgres floor 17.11 (job 132774043232): the Verify step SUCCEEDS — the whole platform reactor with `-Pit` is green on a
  runner for the first time since 2026-11-05. (The launcher, guard and coverage steps are skipped in this job by design of the matrix.)
- Postgres main 18.6 (job 132774043195): every module up to archtest SUCCESS (runtime 11:51 min, cli, sim, archtest reached for
  the first time since 2026-11-05); bench fails on one of its 42 ITs:
  `LedgerIT.aKilledNodeLosesNoAckedMessage:132 only what was unfinished at the kill is finished after it: 57 vs 56` — the old
  check, FIX-215 (fixed in ed48e723; run 208 is its proof). Bench's coverage floor was met. Total 20:37 min.
- The jobs `stack`, `mutation`, `bench` were skipped (they need the build job of main 18.6).

## `ci` run 208 (id 43702451971, head ed48e723, FIX-215) — read 2026-11-15 10:33 UTC

- Both build jobs SUCCEED — the first green build of main on a runner since 2026-11-05:
  Postgres main 18.6 (job 132776319111): Verify 23 min, then every later step green for the first time — the demo package
  validates and its flow tests pass through the launcher (D-079, D-080), the coverage gate's self-test, the name guard, coverage
  no-decrease against the previous main commit, the committed baseline describes the commit.
  Postgres floor 17.11 (job 132776319339): Verify 24.5 min, success.
- DCO: success. `stack` (job 132782427887): started after the build, in progress when read. `mutation` and `bench`: skipped.

## 2026-11-15, after BRAND-5 (627afa86)

- `ci` run 208 (ed48e723): the whole run completed `success` (10:32Z), the `stack` job included.
- `mapping-ci` run 2 (id 43706558942, 627afa86): `success`, 6 min — the mapping builds under `dev.rail8.mapping` on a runner.
- `ci` run 209 (id 43706558940, 627afa86): DCO success. Postgres floor 17.11 (job 132788812396): Verify FAILED after 68 s,
  in testkit: `ComponentContractSelfTest.eachBrokenPropertyFailsExactlyItsCases:337` — the variant "acknowledged before the
  handler answered (the envelope sent comes back)" failed ten contract cases where nine are expected; the extra one is
  `aSendCarriesTheMessageIdToThePartner`. Tests run 278, Failures 1; the reactor stopped at testkit. This is row FIX-221
  (the same fault as the older row FIX-170), seen here for the first time on a runner: a timing-dependent self-test, not the
  rename (the verification of BRAND-5 ran the same test green, and run 208 passed it in both jobs).
  Postgres main 18.6 (job 132788812499): Verify in progress when read (11:07Z).
- `ci` run 210 (id 43707641617, 11108edf, FIX-216): DCO success. Postgres main 18.6 (job 132792075611): `success` — Verify
  21.5 min, then the launcher steps (D-079, D-080), the coverage self-test, the name guard, no-decrease and the baseline, all
  green: the namespace `dev.rail8` builds and passes on a runner. Postgres floor 17.11 (job 132792075884): in progress at
  11:33Z; the whole run completed `success` at 11:36Z. Run 209 completed `failure` (the 17.11 job above; its 18.6 job not read).
- `ci` run 211 (id 43709301400, ed8e4a74, FIX-214): `failure`, one failed job of six — Postgres floor 17.11 (job
  132797179732) stopped at testkit after 63 s on `ComponentContractSelfTest.eachBrokenPropertyFailsExactlyItsCases:337`, the
  same extra case `aSendCarriesTheMessageIdToThePartner`: FIX-221 again, the second time on a runner in three runs, both
  times in the 17.11 job. FIX-214's own tests were not reached in that job; the 18.6 job did not fail.
- `ci` run 212 (id 43714561962, 2f45323e, FIX-221): in progress at 12:53Z; its conclusion not read.
- `ci` run 213 (id 43715949051, e69abdd3, FIX-218; the push also carried a1b89ee2, FIX-222): `failure`, read 13:20Z. DCO
  success. Postgres main 18.6 (job 132817394797): `success` — Verify 20 min and every later step green. Postgres floor 17.11
  (job 132817394902): Verify FAILED after 3 min in the runtime module's unit tests, `Tests run: 1578, Failures: 1`:
  `DeploymentsRedeployTest.everyStartWaitsForEveryStop:558 c's held message: not within PT10S` — a message held at the
  partner while its flow's stop was called was not answered within 10 s of its release. New here: no row and no earlier
  sighting in the ledgers (row FIX-229). In that job testkit passed (59.6 s): FIX-221's fix held on the runner where runs
  209 and 211 failed. The modules after runtime were skipped, so FIX-222's and FIX-218's ITs ran in the 18.6 job only.
- `ci` run 214 (id 43720502718, 5365f393, FIX-168): `success`, read 14:27Z (run list only; 13:43Z-14:08Z). The first green
  `ci` run since the fixes of MAIN-1 began to land; FIX-229's fault did not show in it (it is a race, not fixed there yet).
- `ci` run 215 (id 43724011146, 38db0c13, FIX-219): every job `success`, read 15:08Z (job list): Postgres floor 17.11
  (Verify 14:22Z-14:47Z), Postgres main 18.6 (Verify and every later step), DCO, stack; bench and mutation skipped as
  on every push. Two green `ci` runs in a row (214, 215). Runs of 31a4724f (FIX-229) and e4ee8e81 (FIX-226): not read.
