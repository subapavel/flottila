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
