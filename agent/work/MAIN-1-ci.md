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
- `ci` runs 216 (id 43726990454, 31a4724f, FIX-229) and 217 (id 43728153106, e4ee8e81, FIX-226): `success`, read 16:25Z
  (run list only). Four green `ci` runs in a row (214-217).
- `ci` run 218 (id 43731335969, 5d480cf3, FIX-225): `failure`, read 16:25Z. DCO success; Postgres floor 17.11 `success`
  (Verify 23 min). Postgres main 18.6 (job 132864206463): Verify FAILED after 3 min in the runtime module's unit tests,
  `Tests run: 1578, Failures: 1`: `PeerProtocolTest.theOwnerServesAtMostPeerReadsOfALinkAtOnce:270 the second READ waited
  for the first to be served whole ==> expected: <33555020> but was: <0>`. Not FIX-225's change (its tests are the journal's
  disk comparisons). A known product fault: row FIX-183 (pending since CORE-5a2, 1 of 7 there) - `PeerServer` takes the
  READS permit inside the virtual thread it starts per READ, so a later READ can be served first. Its first sighting on a
  runner.
- `ci` run 219 (id 43731738534, c13a39da, FIX-227): `failure`, read 16:25Z. Postgres floor 17.11 (job 132865474378): the
  runtime module's unit tests passed; its ITs `Tests run: 709, Failures: 1`:
  `NodePoolWaitIT.aFullIntakeWaitQueueRefusesTheNextWaitingSaveAtOnce:600->startDeployed:178 fixture: the package deploys:
  {"error":"the deploy record was not written: Connection pool reached max wait queue size of 1"} ==> expected: <200> but
  was: <503>` - the fixture's own deploy, right after `Node.start`, was refused by the queue bound the case configures
  (`RAIL8_PG_POOL_QUEUE` 1). New here: no row and no earlier sighting (row FIX-234). The other job's conclusion: not read.
- `ci` run 220 (id 43733870692, de39cc99, FIX-231): `failure`, read 17:14Z. Postgres floor 17.11 (job 132872062358): every
  module through archtest green (runtime 13:54 min, units and ITs); the bench module's ITs `Tests run: 42, Failures: 0,
  Errors: 1`: `NodeBenchOpenRecordingLevelIT.anAckOpenLoopWithRecordingLevelNoneSettlesAndSaysSo » IllegalState the node
  refused the bench package: 500 {"error":"flow 'bench/bench' did not start: Address already in use"}`. Not FIX-231's
  change. The port the harness probed as free (`NodeBenchMain.freePort()`: bind 0, close, hand the number on) was taken
  before the node bound it. The same fault class as row FIX-149 (`NodeIT` :1425, pending since P2-15b); its first
  sighting on a runner and in the bench module.
- `ci` run 221 (id 43735022796, 32ca85cd, FIX-224): `success`, read 17:14Z (run list only; 16:21Z-16:47Z).
- `ci` runs 222 (id 43737776252, ca06cd73, FIX-195's late review) and 223 (id 43739440685, 6cd8f2ed, FIX-183):
  `success` both, read 17:54Z (run list only; 16:51Z-17:17Z and 17:08Z-17:35Z).
- `ci` run 224 (id 43741079378, 92f4b212, FIX-234): `failure`, read 17:54Z. Postgres floor 17.11 (job 132894164419):
  every module through the mock component green (runtime 13:31 min, units and ITs - NodePoolWaitIT with them); the cli
  module's ITs `Tests run: 7, Failures: 1`: the list of results of the reference package's flow tests, expected
  `PASS sftp-invoice: partner down (flow time 2400ms)` but was `(flow time 2700ms)` - the other five lines and the
  totals line equal. Not FIX-234's change (a fixture of one runtime IT). A flow time that differs by one step of
  300 ms between two runs of the same package: no earlier sighting; new row FIX-254. The failing case, read from the
  code and not from the log: `LauncherIT.binRail8TestDemoExit0`, the only IT that asserts that list. The cause: not
  shown (`agent/work/FIX-254-scout.md`). The other job's conclusion: not read.
- `ci` run 225 (id 43744590835, a61b80cd, FIX-201): `success`, read 18:37Z from the job list. DCO green; both build jobs
  green (Verify 18:03Z-18:29Z; in the 18.6 job the demo's validate and flow-test steps through the launcher green too); the
  stack job green; mutation and bench skipped, as on every push.
- Count of green `ci` runs in a row: 1 (225). Before it 224 failed on the flow time of row FIX-254; 218, 219 and 220 failed
  on FIX-183 (fixed in 6cd8f2ed), FIX-234 (fixed in 92f4b212) and FIX-149 (fixed in the commit after a61b80cd).
- `ci` run 226 (id 43747912076, 750ba7b9, FIX-149): `success`, read 19:07Z from the run's conclusion (18:38Z-19:05Z); its jobs
  were not read one by one. Count of green `ci` runs in a row: 2 (225, 226).
- `ci` run 227 (id 43751547554, 8e13e176, FIX-254 stage 1, documents only): `success`, read 20:16Z from the run's conclusion
  (19:17Z-19:42Z). Count of green `ci` runs in a row: 3 (225, 226, 227). 227 ran the runner as it was before FIX-254's change.
- `ci` run 228 (id 43757346086, 7df0fff0, FIX-254): `success`, read 20:47Z from the job list. DCO green; both build jobs
  green (Verify 20:18Z-20:44Z on 18.6, with the demo's validate and flow-test steps through the launcher and the coverage
  steps green; 20:18Z-20:43Z on 17.11); the stack job green; mutation and bench skipped, as on every push.
  Count of green `ci` runs in a row: 4 (225-228). `mapping-ci` last ran on 627afa86 (run 2): success.
  Read again at 20:50Z (the first reading was one minute after the run's end): `completed`, `success`, last update
  20:46:02Z; the stack job ran 20:44:32Z-20:46:02Z, `success`.
- `ci` run 229 (id 43760576677, efb9d4ae, MAIN-1's close - documents only, the code of run 228): **failure**, read 21:22Z
  job by job. DCO green; build job 17.11 green (Verify 20:53Z-21:13Z); build job 18.6 (132954237299) failed in Verify, in
  `Rail8 :: runtime`, failsafe: 711 integration tests, 1 error -
  `NodePoolWaitIT.aClaimRefusedByTheWorkPoolLeavesTheWorkerRunningAndItClaimsTheQueuedItemAfterTheRelease`, 0.530 s,
  `io.vertx.core.VertxException: Timeout waiting for connection`, no stack trace in the log. stack, mutation and bench
  skipped. The count of green runs in a row ends at 4 (225-228). Extract of the job log: `agent/work/FIX-260-ci-run-229.txt`.
  Row FIX-260. MAIN-1 was closed on runs 225-228 before this run ended; the close is not undone, the new sighting has its row.

- `ci` runs 230 to 233, read 2026-11-15 23:10Z. 230 (1c57bfaf): `success`, read earlier from the run's conclusion. 231
  (id 43767779292, 25484e4f, the owner's commit of the OPS-1 steering patch): `success` (22:10Z-22:36Z). 232 (id
  43768135967, 167c72de, the owner's answers - documents): `success` (22:14Z-22:40Z). 233 (id 43769989439, 616ee25b,
  FIX-235 on top of FIX-260): `success`, read job by job - DCO green; both build jobs green (Verify 22:34Z-22:58Z on 18.6
  with the launcher steps, the guard, the coverage steps; 22:34Z-22:54Z on 17.11); the stack job green (22:59Z-23:00Z);
  mutation and bench skipped, as on every push. 231 and 232 were read from the runs' conclusions only.
  Count of green `ci` runs in a row: 4 (230-233). FIX-260's sighting (run 229, the 18.6 job) did not come back in them.
- `site` run 1 (id 43771131451, baa089c9, BRAND-6): **failure**, as expected and stated in BRAND-6's commit - the build
  job's self-test, build and check steps are green, `actions/configure-pages@v6` fails (Pages is not switched on: the
  owner's step), the upload step and the deploy job are skipped. Read 23:10Z from the job list; the step's log not read.
- `ci` run 234 (id 43771131363, baa089c9, BRAND-6): `success`, read 23:40Z from the run's conclusion (22:46Z-23:12Z); its
  jobs were not read one by one. Count of green `ci` runs in a row: 5 (230-234).

## Whole-reactor runs on this machine (MAIN-1's first condition)

The row says "green on `main` three times in a row". The architect's reading, written here so that MAIN-1's reviewer can
judge it: three consecutive whole-reactor runs (`clean verify -Pit`), no red run between them, each on the head of its time,
the third on the head MAIN-1 closes on. A commit between two runs does not restart the count; a red run does, and gets a row.

- Run 1 (started about 18:05Z, 25:36 min, the verifier): a61b80cd plus FIX-149 as first reviewed. Green: 17 modules, every
  count as expected, coverage no decrease (`agent/work/FIX-149-evidence.txt`). The committed FIX-149 differs from that tree
  by one Javadoc line and one test method of the testkit module; the testkit module ran green after them.
- Run 2 (19:31Z-19:58Z, the verifier): 8e13e176 plus FIX-254's change as reviewed. Green: 17 modules, every count as
  expected (cli 208 unit tests and 7 ITs), coverage no decrease (`agent/work/FIX-254-evidence.txt` section 5). The committed
  change differs from that tree by two comment sentences; the cli module ran green after them.
- Run 3 (20:18Z-20:45Z, the verifier): 7df0fff0, a clean tree - main's head. Green: BUILD SUCCESS, 17 modules, every count
  as expected (cli 208 unit tests and 7 ITs), 0 failures, 0 errors, 0 skipped, no blocked-platform-thread line, coverage no
  decrease (`agent/work/MAIN-1-run3.log`, `MAIN-1-run3-coverage.log`, not committed). The log does not record the commit
  or the tree's state; the commit is the verifier's word, and the run began (20:19:01Z) after the commit was made (20:18:38Z).
- Count: 3 of 3. The conditions of the row, one by one: `agent/work/MAIN-1-evidence.txt`. The close's review [B]: PASS.
- 2026-11-16: `ci` run 235 (id 43774288165) on 8cfd066e (FIX-262, documents only): success, 23:20Z to 23:47Z on
  2026-11-15, read from its conclusion. Green `ci` runs in a row: 6 (230 to 235). The run on 28e7ff9a (FIX-266,
  documents only) was not read when this line was written.
- 2026-11-16, later: `ci` run 238 (id 43850535042) on 25748fb9 (CONSIDER-24: two test classes, one SQL file, documents):
  success, 10:37Z to 11:03Z, read from its conclusion. Run 237 (id 43843463196), a scheduled run on 28e7ff9a (FIX-266,
  documents only), was listed as in progress when read (started 09:50Z, last update 10:16Z); its conclusion and the push
  runs on 28e7ff9a and a57a146f were not read. Green `ci` runs read in a row: 230 to 235, then 238.
- 2026-11-16, 12:10Z: `ci` run 237 (id 43843463196, scheduled, on 28e7ff9a): success. Run 239 (id 43854636165) on
  a57a146f (the owner's answer, documents only): success, 11:05Z to 11:31Z. Run 240 (id 43857063800) on b04e336a (the
  agreed order, documents only): success, 11:21Z to 11:48Z. Each read from its conclusion. Run 236 (the push run on
  28e7ff9a) was not read. Green `ci` runs read in a row: 237 to 240.
- 2026-11-16, 13:40Z: `ci` run 241 (id 43864341902) on ea75ea70 (FIX-270, documents only): success, 12:11Z to 12:38Z.
  Run 242 (id 43869494326) on db41bb92 (FIX-263: the two replay routes leave the admin API - code and tests): success,
  12:45Z to 13:10Z. Each read from its conclusion. Green `ci` runs read in a row: 237 to 242.
- 2026-11-16, 15:19Z: `ci` run 243 (id 43876465429) on e1bd35f8 (FIX-267: cancel designed, documents only): success,
  13:30Z to 13:57Z. Run 244 (id 43881909665) on f1be875c (ISSUE-12: the owner's answers, documents only): success,
  14:04Z to 14:32Z. Each read from its conclusion. Green `ci` runs read in a row: 237 to 244.
- 2026-11-16, 17:02Z: `ci` run 245 (id 43906527330) on ef669a71 (FIX-275: the guard on `/retry`, code and tests):
  success, 16:21Z to 16:47Z. Read from its conclusion. Green `ci` runs read in a row: 237 to 245.
- 2026-11-16, 17:37Z: `ci` run 246 (id 43910833565) on 87b87e18 (MAP-9: the proposal, documents only): success,
  16:44Z to 17:11Z. Read from its conclusion. Green `ci` runs read in a row: 237 to 246.
