# FIX-264 - fix round 1 after step 3 (the test-author [B]; tests only)

Evidence: `agent/work/FIX-264-evidence.txt` (the verifier's step 3). Packet: `agent/packets/FIX-264.md`, acceptance 1, 8, 9.
Allowed paths: `platform/core/src/test/**`, `platform/runtime/src/test/**`. No production code. Each new case asserts an
observable outcome - a case written only to touch a line is refused by the review.

1. `platform/runtime/src/test/java/dev/rail8/runtime/deploy/DeploymentsSecretsTest.java`:306-314,
   `producerOptionsResolvedToo`. Evidence: `agent/work/FIX-264-3-reactor.log`:56035-56037 - "at least the three flows'
   sends and the worker's: 3 [producer #1 of partner ..., #2 ..., #3 ...]", `produced.size() >= 4` is false. The count
   included replay's producers (the Javadoc says "the replays'"); the packet's acceptance 1 missed this case. Sentence: an
   out endpoint's reference reaches every producer built of it - the deliveries' and the worker's; the count is the exact
   one if the fixture `shop` makes it certain (say which producers they are in the message), and the Javadoc and the
   message name no replay. Only the count, its message and the Javadoc change (a wrong expectation: D-156).

2. `platform/core/src/main/java/dev/rail8/core/flow/Parking.java`:88,
   `persist(e.body()).onSuccess(durable -> run.ifPresent(t -> t.durable(e.body().key())))`. Evidence: scoped PIT after
   (`agent/work/FIX-264-3-pit-after.xml`): `lambda$park$1` VoidMethodCall NO_COVERAGE - before (ef669a71) it was SURVIVED,
   a replay test ran it; `lambda$park$0` VoidMethodCall SURVIVED both times. Sentence: a park made under a current trail
   tells the trail the body is durable once the persist succeeded, and tells it nothing when the persist failed; a case
   for each (in `ParkingTest` or beside it), shown real by a scratch mutant that drops the call (red), then restored.

3. Core's figures may not be lower than before (acceptance 8: line 99.72 %, branch 99.45 %; after step 2: lines
   2367/2374, branches 1223/1231 - the removed code was fully covered, the misses stayed). Needed on every run: at most
   6 missed lines and at most 6 missed branches. Missed today, from `platform/core/target/site/jacoco/jacoco.xml`:
   lines `FlowBuilder.java`:333, :658; `StepCpu.java`:173, :174, :178, :179, :180; branches `FlowWorker.java`:321 (1 of
   6), :503, :505, :516 (1 of 2 each); `FlowBuilder.java`:315 (1 of 4), :545 (1 of 6); `Recorder.java`:88 (1 of 4);
   `StepCpu.java`:233 (1 of 8).
   a. `StepCpu.java`:233 (`innermost`) is covered or not by the run: `StepCpuPropertyTest` draws its intervals at random
      and two earlier samples covered all eight. A case with fixed intervals that takes every branch of that condition
      and asserts which interval is the innermost.
   b. One more missed branch and one missed line of the list, your choice: the ones whose behaviour is worth a case.
      Say in the hand-back which, and what the case asserts. A line or branch that cannot be reached (dead code) is
      reported, not tested.

4. Runtime's figures (before: lines 6576/6611, branches 2830/2847; after: 6568/6603, 2827/2845). Needed on every run: at
   most 34 missed lines and at most 16 missed branches, with `Journal.java`:1013 counted as missed (the `while
   (!dirForcing.isEmpty() && dirForcing.first() < gen)` branch is covered or not by the run; leave it). So: one more
   line and two more branches, from `platform/runtime/target/site/jacoco/jacoco.xml` - lines `Main.java`:65;
   `FlowHash.java`:88-89; `Deployments.java`:279, :281; `AdminServer.java`:478-480; `Node.java`:587-588;
   `PeerSession.java`:35-36; `SpoolCipher.java`:67, :185-186; branches `PeerLink.java`:251; `PeerReads.java`:130, :138,
   :271, :287, :305, :315; `TieredBlobStore.java`:600, :750, :798, :822, :889; `CpuSampler.java`:158, :330. Unit cases
   where one fits; your choice, named in the hand-back with what each asserts.

Runs: core `-pl :rail8-core -am` behind the Maven lock; one runtime class the way your memory names it (`-Dtest=` on
runtime runs out of memory); the whole reactor, the coverage figures and PIT are the verifier's, after you.
Hand back at most 40 lines: per point the file, the case names, the assertion, the red or mutant shown and its log.
