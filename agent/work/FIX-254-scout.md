# FIX-254 — the scout's facts on the flow time of `ci` run 224 (2026-11-15), as received

Facts read from the code; the cause of the sighting is not shown by any of them.

Scout report (facts; 28 tool calls used)

1. The asserting IT
- platform/cli/src/test/java/dev/rail8/cli/LauncherIT.java:82-86 `binRail8TestDemoExit0`: `Exited e = launch("test", "examples/demo")`, then `assertEquals(TestCommandTest.DEMO_REPORT, e.out().lines().toList(), e.toString())`. It is a launched process (bin/rail8), not in-process.
- The expected list is TestCommandTest.java:54-61 `DEMO_REPORT`, identical to the sighting's expected string.
- Package dir: examples/demo (flows in examples/demo/flows/).
- invoice-async.yaml:15 and sftp-invoice.yaml:14: `retries: { count: 1, interval: 1s }`. Both: `retry: attempts 3, initial 100ms, max 2s` (invoice-async.yaml:27-30, sftp-invoice.yaml:25-28). Multiplier 2 is only stated in TestCommandTest.java:46 javadoc.
- sftp poll: no per-flow setting. sftp-inbox is examples/demo/endpoints/sftp-inbox.yaml. The mock's consumer polls every ROUND = 1 s (MockComponent.java:68, javadoc :53). invoice-async has no poll (HTTP ingress).
- "partner down" cases: sftp-invoice.test.yaml:17-20 `partner-domestic: { fail: 503 }`, `expect: { dlq: 1, retries: 7 }`. invoice-async.test.yaml:19-23 is the same plus `send` and `status: 202`.

2. VirtualTime loop (VirtualTime.java:61-77)
- Wall guard (l.62-64), then `if (!quiescence.quiet()) { Thread.onSpinWait(); continue; }` (l.65-68). No sleep; it spins.
- Quiet only when settled-predicate is asked on quiet samples (l.69). Then horizon (l.71), then `timer.pending()==0` (l.72).
- Step (l.73-75): `room = HORIZON - flowTime; next = timer.nextDelay(); clock.advance(min(next, room))`. The step is not a constant. 300 ms is not a constant anywhere here.
- Quiescence.quiet() (Quiescence.java:74-85): two samples with Thread.yield() between, idle = mounted==0 && queued==0, equal fingerprints, and the idle stretch lasts `steady` of wall time (nanoTime).
- FlowTests.java:89 `STEADY = Duration.ofMillis(1)`; WALL_GUARD 60 s (l.83). Busy = Watched.inside, which counts only timer calls (FlowTests.java:341-365). Fingerprint = FlowTests.world (l.301-312): watched calls, clock moves, instant, door.delivered/remaining sizes, each mock's sent size, done.
- Settled predicate (FlowTests.java:271): `answered && door.undelivered(from).isEmpty() && queue.size()==0`, read outside the fingerprint.
- Flow time = Duration.between(start, clock.instant()) at return (VirtualTime.java:69, 80-84). Not wall time.
- FakeTimer: fires due tasks inside clock.advance on the rail8-clock thread (FakeTimer.java:56-70); nextDelay = earliest due (l.49-54).

3. Where 2400 comes from and where a 300 ms can enter
- 2400 = 700 (step backoffs 100+200+400) + 1000 (delivery retry, FlowWorker.java:498-524 retriesDue wake) + 700.
- Fixture arithmetic: the only 300 in the policy is 100+200 (first two step backoffs). The 1 s ROUND and 1 s retry are not 300. Which timer produced the extra 300 was not determined.
- Sftp-only real-time entry points (invoice-async has none of these):
  a) STEADY = 1 ms wall (FlowTests.java:89, Quiescence.java:82-84). A thread between hand-overs can be unseen longer than 1 ms under load.
  b) Mock-round thread blocks on Future.await (MockComponent.java:348, 350). Busy counts only timer calls, so it is idle while blocked.
  c) File removal happens after the answer completes (MockComponent.java:352-353, 366-367). `answered` is set by a listener (FlowTests.java:318-321). A quiet sample can see answered=true with the file still remaining, so the predicate is false and the clock advances to the next due task (VirtualTime.java:73-75).
  d) Next poll is scheduled in round()'s finally after drain.release() (MockComponent.java:316-317, 338-341), using the clock instant at call time.
  e) queue.size() is read outside the fingerprint (FlowTests.java:271).
- Not verified: whether the sftp mock's timer goes through Watched.
- Flow poll: FlowWorker.java:240 start offset floorMod(flow.hashCode(), interval). WorkerPolicy.DEFAULT is 30 s lease / 30 s poll (WorkerPolicy.java:21). No WorkerPolicy construction found in cli main. Not verified which policy Deployments uses.

4. Determinism pins and docs
- TestCommandTest.java:42-43 javadoc: "No wall time anywhere: the same package gives the same bytes on every run." DEMO_REPORT pinned at :54-61. Partner-down explanation at :46-49.
- LauncherIT.java:80-86.
- VirtualTimeTest.java:28-38 (class doc), :225 "one round per second", :234 (Quiescence with steady 200 ms).
- Quiescence.java:12-23 javadoc on steady and the JDK 24 hand-over.
- examples/demo sftp-invoice.test.yaml:2-3 and invoice-async.test.yaml:1-3 document 2400 ms.
- I did not find a repeat-run test, and no test pins STEADY = 1 ms.
- BACKLOG rows mentioning VirtualTime: FIX-90 (agent/BACKLOG.md:451), FIX-174 (agent/BACKLOG.md:351).

Not read: MockComponent test files, FakeRuntime wiring of the sftp mock timer, Deployments' WorkerPolicy, the testkit FakeClock.
