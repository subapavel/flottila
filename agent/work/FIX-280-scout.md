# FIX-280 / FIX-240 - the scout's map of testkit's polling (2026-11-16; haiku; written down by the architect)

Paths under `platform/testkit/src/`. CC = `main/java/dev/rail8/testkit/ComponentContract.java`, PC =
`main/java/dev/rail8/testkit/PollingConsumerContract.java`,
PCS = `test/java/dev/rail8/testkit/PollingConsumerContractSelfTest.java`.

## Part 1 - how the contract suites poll (FIX-280)

- `CC.soon` :109-117: `deadline = nanoTime() + patience()`; `while (!condition.getAsBoolean()) { if (nanoTime() > deadline)
  return false; Thread.onSpinWait(); Thread.yield(); } return true;`. `CC.patience()` :93 is 10 s. `PC.soon` :93-101 is the
  same body; `PC.patience()` :88 is 10 s.
- `patience()` overridden in tests only: PCS :319 (2 s, `VariantPollingContractTest`), `ComponentContractSelfTest` :326 (1 s)
  and :336 (200 ms), `ComponentContractSoonTest` :21 (20 ms). No other module overrides it.
- `CC`'s twelve `soon(` calls (line | condition | test):
  204 `pending.get() != null` theSendCompletesOnlyAfterTheHandlerAcknowledged; 256 `invoked.get() == 1`
  aHandlerThatAnswersNullFailsTheSendAndStopStillCompletes; 279 `seen.size() == 1`
  aBodyAboveTheTreeLimitStreamsThroughIntact;
  299 `seen.size() == 2` aSecondSendOfTheSameEnvelopeDeliversTheSameBodyAgain; 393 `handlers.size() >= 2` and 398
  `together.size() == 2` theHandlerRunsOnAVirtualThreadOfItsOwnPerDelivery; 433 `invoked.get() == 1`
  stopWaitsForTheDeliveryInFlightAndItsAnswerReachesTheSender; 464 `invoked.get() == 1`
  aDeliveryCancelledWhileTheConsumerStopsFailsTheSendRetryable; 510 `seen.size() == 2` aSendCarriesTheMessageIdToThePartner;
  550 `delivered.get() >= 1` aSendWithNoTimeLeftIsNotStarted; 582 `answers.size() == 1` and 594 `answers.size() == 2`
  aSendIsCutWhenItsDeadlineRunsOutButNotWhileItIsRenewed.
- `CC`:515 is `if (plainOnes.size() == 1 && boundedOnes.size() == 1) {` in aSendCarriesTheMessageIdToThePartner (:496): no
  poll; its one missed branch of four is the same in every run measured.
- `PC` has about 45 `soon(` calls of the same shape (:108 in a helper, then :231 to :550 in its cases). Their missed
  branches did not move in any run measured.
- `ComponentContractSoonTest` has one test, `returnsTrueOnceTheConditionHoldsAndFalseWhenThePatienceIsSpent` (:25-36): a
  counting supplier (`polls.incrementAndGet() >= 3`), `soon(() -> false)` with 20 ms patience, and the default 10 s.
- Other waits in testkit main: `CC.settled` :99-106, `CC.bothEntered` :407-410, `PC.fire` :140-162, `PC.awaited` :165-170;
  no helper named `until` or `eventually`.

## Part 2 - the test that failed once in three runs (FIX-240)

- The test, PCS :365-369: `assertEquals(Set.of(PENDING, NACK_KEEPS, NACK_ENDS, BROKEN_HANDLER, FINAL_NACK, STOP_WAITS,
  STOPPED, DEDUPE), failing(v -> v.removedBeforeTheAnswer = true))`. In the failing run STOP_WAITS was not in the set.
- The broken variant: flag at PCS :98-99 ("removed right after the handler was called, before its answer is awaited");
  `Variant.Poller.take` :219-251 calls `deliver(in, promised)` at :230, which starts the handler on a new virtual thread
  (`deliver` :267-279, "variant-handler"), then on the round's thread `if (removedBeforeTheAnswer) dir.remove(f.name())`
  at :232, then `answer.await()` at :235. Nothing orders the removal and the handler's thread.
- The case, `PC.stopWaitsForTheDeliveryInFlightAndCompletesItsRemoval` :454-478: start; `fire()` :464;
  `soon(() -> seen.count() == 1)` :465; `stopped = consumer.stop()` :466; `assertFalse(stopped.isComplete())` :467;
  `assertEquals(Set.of("first.txt", "second.txt"), remaining(), "nothing is removed while the answer is pending")` :468;
  `answer.complete(Accepted)` :469; `settled(stopped)` :470; `remaining()` is `second.txt` :471-472; `seen.files` :473.
- Only :468 can see the early removal: after :469 a correct consumer has removed the file too. For :468 to fail, the
  round's thread must have run PCS :232 before the case's thread reads at :468; the case's thread gets there once the
  handler's thread recorded its call. A slow round thread lets the case pass against the broken variant.
- `PC.theFileStaysWhileTheAnswerIsPending` has the same kind of read at :255.
- `PollingConsumerContract` is extended by PCS's `VariantPollingContractTest`, `SftpPollingContractTest` and
  `MockPollingContractTest`.
- On record: BACKLOG row FIX-240 (the same test; "failed once and passed on an immediate re-run ... STOP_WAITS missed 1 of
  20 under load"); no PROGRESS or NOTES entry names it.

## Part 3 - `Journal`:1013 (a second scout, 2026-11-16)

Paths under `platform/runtime/src/`. J = `main/java/dev/rail8/runtime/journal/Journal.java`.

- `requireNoEarlierDirFailure(long gen)` J :1010-1021: under the lock,
  `while (!dirForcing.isEmpty() && dirForcing.first() < gen) done.awaitUninterruptibly();` then
  `if (dirFailedAt < gen) throw new JournalFailedException(...)`.
- `dirForcing` (a `TreeSet<Long>`, :301): `add(gen)` at :984 before the force, `remove(gen)` at :997 in the finally, then
  `done.signalAll()` :998. `gen = ++dirForces` at :983, returned by `forceSegments` :979-1003.
- Callers: only :1497 in `Spill.persist(Bound)` (:1472, synchronized), after `durably(Journal.this::forceSegments)` :1494.
  The other caller of `forceSegments` is the pre-open thread (:927).
- Tests that reach :1013 with `dirForcing` non-empty: `JournalDirForceOrderTest` :52-97 (class `Two`: A's force held by
  thread :75-82, B's after it :83-87, B released first :92; cases :100 and :111) and `JournalCloseAndDirForceTest`
  :155-197. Every other test holds a single force.
- The four outcomes: empty - by construction; non-empty and `first < gen` (wait) - by construction (`untilParked` :94);
  the loop's exit after waiting - by construction (:104, :116); non-empty and `first >= gen` (no wait) - no test releases
  a lower-generation force while a higher one is held, so only thread timing reaches it.
- Seams: `JournalFiles` (`JournalFiles.java`:18-22), taken by `Journal.open` (:345, :351, :377); `HeldFiles`
  (`holdForceOn(Path, long, Thread)` :235, `holdForceOn(Path, long)` :245, `holdForceAfter(String)` :256);
  `OwnDirForce` :27-82 (`Preopen` :45, `Persisting` :78, :81).
