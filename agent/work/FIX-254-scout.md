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

## Second pass (2026-11-15) — a hypothesis checked link by link, as received

H, the architect's: 2700 = the second delivery started at 2000 instead of 1700, because the virtual clock was stepped
from 700 to 1000 - to the next pending timer, the sftp mock consumer's poll round - before the failed run's verdict
had computed the defer's due time (or before the worker armed its wake), so the second delivery tries at 2000, 2100,
2300, 2700. The runner steps the clock when Quiescence sees the node idle for 1 ms of wall time. The arithmetic
holds; that the message's thread was unseen for that long at that point is NOT shown.

H verdict: links 1, 3, 4 HOLD; link 2 NOT DETERMINED; link 5 DOES NOT HOLD as a separate cause. The +300 has a same-number alternative (below). Paths are under /home/user/flottila/platform/.

1. HOLDS. Mock round timer: MockComponent.java:316-317 `schedule()` = `timer.schedule(ROUND, this::fire)`, called in round()'s finally at :339-341 after `drain.release()`. Start at :309 `timer.schedule(Duration.ZERO, ...)`. The timer is Watched-wrapped: cli/.../FlowTests.java:237-240 (`Timer timer = watched.timer(rt.timer())` passed to `new MockComponent`). The underlying timer is testkit FakeTimer (FakeTimer.java:29-32, due = `clock.instant().plus(delay)`). A round at t=0 that finishes at t=0 leaves a task due 1000 pending from t=0. At t=700 nextDelay = 300 (FakeTimer.java:49-54). VirtualTime.java:73-75 steps min(next, room). For invoice-async (no poll) I did NOT list the pending timers at t=700. Known candidates: lease end (FlowWorker.java:490), renew at lease/3 (:855), poll tick (:240, :376). None found at 1000.

2. NOT DETERMINED. Hop chain, all on the message's virtual thread as far as read: run thread `rail8-worker-…` (FlowWorker.java:548-556). `failed()` at :671 and :687-690 (`Verdict.defer(wait)`, :832). `ran.complete(verdict)` in finally (:554). `compose` to settleOrTake (:557, :570) to settle (:580, :742) to `queue.defer` (:756). Due: InMemoryWorkQueue.java:113-117 `synchronized` defer, `r.availableAt = clock.instant().plus(delay)`. Queue is `new InMemoryWorkQueue(rt.clock())` (FlowTests.java:256). FlowWorker.java:761 also reads `clock.instant().plus(wait)`, then :499-506 `timer.schedule(delay, retriesDue)`. No runOnContext or executor found in this path. Vert.x Promise callback dispatch without context: not verified (no Context7/javap). Blocking hop: Trail close join at FlowWorker.java:669; Trail.java:105-111 `Future.join(flushes)`. Completers of the flushes: not read. A window of 700 to :117 exists only if the message thread is uncounted for over 1 ms wall.

3. HOLDS as a mechanism. Quiescence.java:65-66 `getPlatformMXBean(VirtualThreadSchedulerMXBean.class)`; mounted = `getMountedVirtualThreadCount() + busy`, queued = `getQueuedVirtualThreadCount`. idle at :88. Javadoc :19-22: "a virtual thread blocked on a monitor leaves its carrier (JDK 24 on, JEP 491), and from the monitor's release until the JDK's unblocker thread queues it again it is neither mounted nor queued... the hand-over ends within it." Platform threads are in neither number nor fingerprint (FlowTests.java:306-309 covers calls, moves, instant, delivered/remaining, sent sizes, done). queue.size() is outside it (FlowTests.java:271, read on rail8-clock, a `synchronized` method at InMemoryWorkQueue). STEADY = 1 ms (FlowTests.java:89, Quiescence.java:82-84). Quiescence.java:12-23 javadoc: "A quiescence with a steady time therefore answers quiet only once its samples have stayed idle... for at least that long."

4. HOLDS arithmetically. FlowWorker.java:506 arms `retryWake` with delay from the call instant. :511-524 `retriesDue` clears dues ≤ now and claims. Due 1700 gives tries 1700, 1800, 2000, 2400 (matches the 2400 timeline). Due 2000 gives 2000, 2100, 2300, 2700 (matches the sighting).

5. DOES NOT HOLD as a separate cause. The sftp round finds no file at 1000 (the file was removed at MockComponent.java:352-353, :366-367), so its own clock step cannot move delivery 2. Its schedule() at :316 reads the clock after drain.release() at :339, which is t=0 on the normal path. Other clock reads on this path, FlowWorker.java:514 and :761, are on the same thread as the queue read.

Alternative +300 (same number, different locus): the clock moves between InMemoryWorkQueue.java:117 (due 1700 correct) and FlowWorker.java:506. The wake's relative delay is taken at 506, so the wake fires at 2000. retriesDue then clears 1700 and claims at 2000. Needs the same uncounted window, between :761 and :499 (`synchronized (lock)`, :499, :582, :607).

Candidate uncounted monitor: the settled predicate `queue.size()` (FlowTests.java:271) holds the queue monitor on rail8-clock. A message thread entering `InMemoryWorkQueue.defer` (:113) in that window would be off-carrier. Whether it is ever in there: NOT DETERMINED.

Not read: InMemoryMessageStore record path (synchronized, InMemoryMessageStore.java:38-50); Trail flush completers; Vert.x Promise dispatch; Retry.java:80-95 in full; WorkerPolicy values in FlowTests; the pending-timer list at t=700 for invoice-async; the sftp ingress Ack timing.

## Third pass (2026-11-15) — where the runner's threads start and what the sampler is given, as received

Scout report for the `rail8 test` flow path (19 of 30 tool calls used). Items 1-5 answered; unread items listed at the end.

1. Thread starts on the flow-test path
- Test command thread: platform/cli/src/main/java/dev/rail8/cli/Cli.java:62 `supplyAsync(..., task -> Thread.ofVirtual().name("rail8").start(task))`. The sender's ingest call runs here (FlowTests.java:267).
- Clock: platform/cli/src/main/java/dev/rail8/cli/VirtualTime.java:58 `Thread.ofPlatform().name("rail8-clock").start(task)`.
- Node close: FlowTests.java:331 `Thread.ofVirtual().name("rail8-test-close").start(task)`.
- FlowWorker: platform/core/src/main/java/dev/rail8/core/flow/FlowWorker.java:548 `Thread.ofVirtual().name("rail8-worker-" + workerId + "-" + item.seq()).start(() -> {`. Inline static call; no executor or factory injected.
- Delivery at-most-once: platform/core/src/main/java/dev/rail8/core/flow/Delivery.java:205 `flight.thread = Thread.ofVirtual().name("rail8-" + e.flow() + "-" + e.id()).start(() -> run(e, flight));`. Inline; no injection.
- Delivery sync: Delivery.java:164-165 `board` does `flight.thread = Thread.currentThread();`. No new thread; the caller's thread runs it.
- Delivery at-least-once: Delivery.java:274-276 builds the Mode and `board`. Body not read.
- Mock poll round: platform/components/mock/src/main/java/dev/rail8/components/mock/MockComponent.java:324-326. `fire()` runs on the timer task, then `Thread.ofVirtual().name("mock-round").start(this::round)`. The timer is the Watched timer (FlowTests.java:238, 240).
- Mock handler per delivery: MockComponent.java:428 `Thread.ofVirtual().name("mock-handler").start(...)`.
- Mock ingest (the http door for invoice-sync/invoice-async): MockComponent.java:176-183. No thread is started; `running.handler().apply(in)` runs on the caller's thread.
- Other main-code hits, not on the mock path: core/.../scope/RequestScope.java:84 (`rail8-child-`); api/.../InputStreamReadStream.java:62 (`rail8-blob-read`); testkit/.../BlockedPlatformThreadDetector.java:64,101-102,114; testkit/.../RecordingComponent.java:195,237; testkit/.../ComponentContract.java:376; testkit/.../PollingConsumerContract.java:142.

2. Runtime and the SPI
- platform/api/src/main/java/dev/rail8/api/Runtime.java:15-16: `record Runtime(Clock clock, Timer timer, IdGenerator ids, BlobStore blobs, Hasher hasher, Map<String, Component> components)`; `component(String scheme)` at :28. No executor or thread factory.
- api/.../Timer.java:16: `Cancellable schedule(Duration delay, Runnable task);`
- api/.../Component.java:26: `Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler);`. Also `producer(cfg)` :28 and `health()` :30. No thread parameter.
- MockComponent.java:88 and :93 constructors take `(scheme, BlobStore, Timer[, IdGenerator])`.
- FlowTests.java:236 `FakeRuntime rt = FakeRuntime.create();`. FakeRuntime is at testkit/.../FakeRuntime.java:25; `FakeClock.at(T0)` :37; `new FakeTimer(clock)` :38.
- FlowTests.java:237-240: `Watched watched = new Watched(); Timer timer = watched.timer(rt.timer());`; each mock gets that timer.
- FlowTests.java:262 builds `new Runtime(rt.clock(), timer, ...)` with the Watched timer.
- Watched (FlowTests.java:341-365): `timer(Timer)` :345, `calls()` :352, `inside()` :354, private `call(Supplier)` :356-363. It increments `inside`, runs the call, then increments `calls` and decrements `inside` in `finally`. It wraps only timer schedule and cancel.

3. Quiescence
- Fields, Quiescence.java:26-31: `IntSupplier mounted`, `LongSupplier queued`, `Supplier<?> fingerprint`, `long steady`, `LongSupplier nanoTime`, plus `Stretch stretch`.
- Constructors: :38 `(mounted, queued, fingerprint)`; :46 `(mounted, queued, fingerprint, Duration steady, LongSupplier nanoTime)`.
- `ofJvm(fingerprint)` :55; `ofJvm(busy, steady, fingerprint)` :64. The latter uses `ManagementFactory.getPlatformMXBean(VirtualThreadSchedulerMXBean.class)` :65; mounted = `getMountedVirtualThreadCount() + busy` :66.
- Construction sites in cli main: FlowTests.java:270 and :332 (both `Quiescence.ofJvm(watched::inside, STEADY, world(...))`).
- Construction sites in tests: VirtualTimeTest.java:59 `new Quiescence(() -> 0, () -> 0L, ...)`, :102, :178, :234. QuiescenceTest constructions not read.

4. Tests
- QuiescenceTest (platform/cli/src/test/java/dev/rail8/cli/): spinningVirtualThreadIsNotQuiet :61; parkedVirtualThreadIsQuiet :86; idleSamplesWithEqualFingerprintsAreQuiet :106; aQueuedVirtualThreadIsNotQuiet :115; aChangingFingerprintIsNotQuiet :127; oneBusySampleOfTwoIsNotQuiet :140; notQuietBeforeTheSteadyTimeHasPassed :160; aChangedFingerprintRestartsTheSteadyWait :178; aBusySampleRestartsTheSteadyWait :197; theRunnersSteadyTimeKeepsTheFirstIdleSamplesFromBeingQuiet :220; aBusyThreadIsNeverQuiet :234.
- VirtualTimeTest: jumpsToTheNextDueTask :75; neverAdvancesWhileAVirtualThreadRuns :99; zeroAdvanceFiresDueTasks :144; stuckWithNothingScheduledFails :163; settledIsTakenOnlyWhenQuiet :176; aTaskBeyondTheHorizonNeverRuns :190; aBusyCaseStopsAtTheHorizon :210; theWallGuardEndsACaseThatIsNeverQuiet :231.
- parkedVirtualThreadIsQuiet (QuiescenceTest.java:88) parks a virtual thread in `gate.future().await()` and reads `parked.getState()` (:90, :95). It is not `synchronized`; the mechanism of `await()` was not checked.
- No `synchronized` in either test file. VirtualTimeTest does not read `getState`.

5. synchronized shared with rail8-clock
- cli main: no synchronized code. Only a javadoc mention at FlowTests.java:316.
- core InMemory*: record/InMemoryMessageStore.java:38,50,58,74; queue/InMemoryWorkQueue.java:53-181 (about 20 methods, summarised); dlq/InMemoryDlq.java:40,47,58,63,69; node/InMemoryMembership.java:39-99 (5 methods).

Not read: FlowWorker lease and chain wiring beyond :548; how Deployments constructs FlowWorker and Delivery; Delivery.atLeastOnce body; RequestScope call sites; FakeTimer and FakeClock sources (the scout file cites FakeTimer.java:56-70 for firing on the clock thread); QuiescenceTest setups; whether the sftp mock timer goes through Watched.
