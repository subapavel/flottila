# FIX-235 — the designer's draft (class C), as handed back 2026-11-15

Written to this file by the architect, unchanged except for the wrapping; the designer writes no file. Line numbers refer
to `platform/core/src/main/java/dev/rail8/core/batch/BatchingWriter.java` unless another file is named. Not read by the
designer: the body of PgIntake `fail(m, …)`, FakeTimer past :68, PgBatchSink past :49-70. "Red today" means red when the
code is read; no test was run.

## 1. Proposal

- Q1, one place is enough. Every dispatch path (enqueue:193, flush:255, onWindow:376, afterCommit:398) goes through
  dispatch:331 to commit:379. A null answer only escapes at :388, where `written.onComplete` is called on null. Between
  the catch (:387) and :388 add: `if (written == null) written = Future.failedFuture(new NullPointerException(
  "BatchSink.write answered null for a batch of " + items.size() + " item(s), not a Future (D-146)"));`
- What that one check gives: (a) the batch's items fail with that NPE at :400. (b) inFlight goes down once, at :394: one
  handler, one completion. (c) dispatch:331 carries on, so the drain's other batches reach the sink in the order taken.
  (d) The failed future completes inline, as the sink's throw already does (ErrorContainmentTest:170,174), so
  drainLocked:396 takes whatever stayed queued. (e) dispatch:398 no longer throws, so :399-401 complete the earlier
  batch. The writer does not log it. That matches the sink's throw today, which :385-386 does not log either; the cause
  reaches each item's future (see Conflicts).
- Q2, contain (option i), `RuntimeException | Error` as at :385. :181 becomes `try { bytes = weight.applyAsLong(item); }
  catch (RuntimeException | Error e) { return Future.failedFuture(e); }`. It stays before the lock, so no caller code
  runs under the lock. Nothing is queued and no deadline is scheduled. `saveWaiting` does not change the answer, because
  :181 runs before the lock and before any wait.
- Callers: no caller's weight function has a reachable throw. PgWorkQueue:352 and PgMessageStore:77 use the count
  constructor, whose weight is `item -> 1L` (:129). PgIntake:274 passes `Member::weight`, a record accessor
  (PgIntake:142). Leaving the throw uncontained would bypass the failure handling the callers already have:
  PgWorkQueue:519-521 would never answer `m`, and PgIntake:360 would skip `fail(m, …)`.
- Q3, null item stays thrown (NPE at :180). It is `save`'s own argument, there is no item whose future could carry it,
  and every caller already rules it out (PgMessageStore:82,88 call requireNonNull first; PgWorkQueue:518 and
  PgIntake:357 build the item).
- Q3, negative weight becomes a failed future: :182 becomes `return Future.failedFuture(new IllegalArgumentException(
  "a negative weight " + bytes))`. It is the weight function's answer, not an argument, so it should behave like that
  function's throw and like the sink's null answer.
- Proposed D-146 amendment, replacing the bracket: "[Amended by FIX-235: no public method of BatchingWriter completes by
  throwing, except save/saveWaiting on a null item (NullPointerException, nothing queued), an argument check like those
  of the constructors and Options. Every other fault - a throw of the sink, the timer or the weight function,
  RuntimeException and Error alike, a sink that answers null, a negative weight - becomes the failure cause of the item
  or batch it belongs to and of nothing else; a fault that fails no item (a refused window or cancel) is counted and
  logged, two lines per episode.]"
- Proposed Javadoc for :35-41: "Not contained: a null item, thrown at the caller of save/saveWaiting as
  NullPointerException with nothing queued - the only throw of either. Outside the guarantee, not a throw: a sink whose
  Future never completes keeps its batch in flight."
- Other Javadoc edits: :33 adds the three new contained faults; :134 "never negative" becomes "a negative one fails that
  save"; :149-165 name the two new failure causes; :43-45 adds that a sink which throws or answers null completes inline
  too. BatchSink.java:17 adds: "answers a Future, never null; a null answer fails the batch".
- Q4, other ways out found, none listed today:
  - (a) A checked exception thrown sneakily by the sink, the timer or the weight function gets past every
    `catch (RuntimeException | Error)` (:205, :277, :346, :385).
  - (b) :388 turns a failed result with a null cause into a success (`failure == null` leads to tryComplete at :400).
    Whether Vert.x 5.1.10 can produce such a result is unverified; 4.x replaced a null cause with NoStackTraceThrowable
    (from memory).
  - (c) A timer that returns a null handle is contained but misreported: :276 schedules the window again on every drain
    (tempo only), and a null deadline from :204 later throws an NPE at :345 that is logged as a refused cancel.
  - (d) Not a throw: queuedBytes (:297, :315) and `bytes + weight` (:316) overflow for weights near Long.MAX_VALUE, so a
    batch can exceed batchBytes.
  - None found in close:260-262, expire:363-368 or onWindow:370-377.

## 2. Tests (in BatchingWriterErrorContainmentTest unless another class is named)

- `aSinkThatAnswersNullFailsOnlyItsBatchWithACauseThatNamesIt`: idle writer, sink answers null for "bad". No throw; bad
  fails with an NPE whose message contains "answered null"; inFlight 0; a later save commits. Red: save throws at :388.
- `aSinkThatAnswersNullForOneBatchOfADrainStillHandsTheOthersToTheSink`: weighed writer, batchSize 10, batchBytes 10,
  concurrency 3, weight 100 for "BIG" and 1 otherwise. With busy in flight and s queued, save(BIG) takes [s] and [BIG] in
  one drain (:307-316); the sink answers null for [s]. Then the sink saw [[busy],[s],[BIG]], inFlight is 2, everything
  settles and inFlight ends at 0. Red: save(BIG) throws, [BIG] is never handed over, inFlight sticks at 3.
- `aSinkThatAnswersNullFromACompletionListenerCompletesTheEarlierBatchAndStrandsNothing`: concurrency 1, batchSize 1.
  With a in flight and bad, c queued, completing a's gate leaves a succeeded, bad failed, c handed over and committed,
  inFlight 0. Red: a and bad never complete and c is stranded.
- `aSinkThatAnswersNullOnTheWindowOrAFlushFailsOnlyThatBatch`: @ParameterizedTest over {clock.advance(WINDOW), flush()}.
  No throw, bad fails, a commits, the flush completes. Red: the NPE leaves FakeTimer:68 (it runs the task inline) or
  leaves flush.
- `aThrowingWeightFailsThatSaveWithItsOwnCauseAndQueuesNothing`: {IllegalStateException, InternalError} × {save,
  saveWaiting on a full backlog}. assertSame on the cause; backlog and waiting unchanged; no deadline pending; sink
  untouched. Red: thrown at :181.
- `BatchingWriterWeightTest.aNegativeWeightFailsThatSaveAndQueuesNothing` replaces :21-37: the save fails with an
  IllegalArgumentException naming -1. Red: thrown at :182.
- `aNullItemIsThrownAtTheCallerAndQueuesNothing`: NPE from save(null) and saveWaiting(null), nothing queued. Already
  green: it pins the decision and kills a mutant that removes :180.
- Property test, `BatchingWriterPropertyTest.everyItemSettlesAndNoSlotLeaksWhateverTheSinkAnswers`: each batch randomly
  succeeds, fails, throws or answers null. Every future completes, inFlight and backlog end at 0, each item is handed
  over once, and the failed items are exactly those of the faulty batches. Red: a null answer strands its batch.

## 3. Rejected

- A catch in dispatch: it cannot tell whether afterCommit already ran (risk of a double decrement), and it fails no item.
- Moving `onComplete` into the try: an already-completed future runs afterCommit before any throw, so the catch would
  settle the batch twice.
- `requireNonNull` inside the try: same result through throw-and-catch; the null check after the catch is one branch.
- IllegalStateException as the cause: no gain over NPE, which the Javadoc already names at :38.
- A WARNING for every null answer: one log line per batch while the sink stays broken.
- Leaving the weight function's throw uncontained (option ii): contradicts D-146 for a callback supplied by the same
  party as the sink, and bypasses the callers' failure handling.
- Containing only RuntimeException from the weight function: it would add a third rule beside the sink's and the timer's
  RuntimeException-and-Error.
- A null item as a failed future: there is no item to fail, and the class's own constructor checks (:141-145) throw.
- Leaving the negative weight thrown: that would give one callback two different rules.

## 4. Expensive to reverse (mark draft - review at hand-over)

- The rewording of D-146, which is owner-approved policy.
- The observable behaviour of the public `save`/`saveWaiting` in rail8-core (@since 0.1): the negative weight moves from
  a throw to a failed future. None of the three callers catches it.
- No signature, SQL, persisted or wire format changes.

## 5. Conflicts

- D-146 says "logged with that cause, never swallowed", but the sink's throw is not logged today (:385-386) and the
  proposal does not log the new faults either. The amendment reads "logged" as covering only faults that fail no item;
  that rewords an owner-approved sentence.
- BatchingWriterWeightTest:30 (CORE-6b, written by the implementer) pins the throw. Per CLAUDE.md's test rule, the
  test-author must rewrite it and give this decision as the reason.
- ARCHITECTURE.md:258: no conflict; it says nothing about containment.

## 6. Open (the designer's questions to the architect)

1. Logging: do you accept that the writer does not log a fault that fails an item, or must it log (a WARNING, two lines
   per episode, one episode per site)?
2. Sneaky checked throws (Q4 a): widen the five catches to `catch (Throwable t)` in this row (PIT-neutral; test with a
   sneaky IOException from the sink and from the weight function), or list them under "Not contained"?
3. The null cause (Q4 b): javap `FailedFuture.<init>` and `FutureImpl.tryFail` in vertx-core-5.1.10 first. If such a
   result can arrive, pass the AsyncResult to afterCommit and fail with a named cause. In this row or a new one?
4. The weight overflow (Q4 d): a new row, or out of scope?

## The architect's measurement for open question 3 (javap, 2026-11-15)

`javap -c -p` on `~/.m2/repository/io/vertx/vertx-core/5.1.10/vertx-core-5.1.10.jar`:
- `io.vertx.core.impl.future.FailedFuture(ContextInternal, Throwable)`: `cause = t != null ? t : new
  NoStackTraceThrowable(null)` (bytecode 5-22).
- `io.vertx.core.impl.future.FutureImpl.tryFail(Throwable)`: a null argument is replaced by `new
  NoStackTraceThrowable(null)` before `completeInternal(null, cause)` (bytecode 0-16).

So neither `Future.failedFuture(null)` nor `promise.fail((Throwable) null)` of Vert.x 5.1.10 yields a failed result with
a null cause. Not checked: every other caller of `completeInternal` inside Vert.x, and a `Future` implemented outside
Vert.x (the interface is open).
