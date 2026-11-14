# Packet FIX-71 (class B) — BatchingWriter: refused windows commit, Errors fail only their own item

Module: `flottila-core`. Row: `agent/BACKLOG.md`, FIX-71, audited LIVE on 2026-11-14.
Policy: **D-104** in `docs/DECISIONS.md` — read it in full before anything else. It is decided;
this packet implements it. Parent: FIX-69.

## (b) The policy, already decided

When the window `timer.schedule` throws — `RuntimeException` or `Error` — the batches the drain
just took are **committed, never failed**. They stay in flight, reach the sink in the order
taken, and whatever stayed queued rides their completions (`afterCommit` → `drainLocked`,
`BatchingWriter.java:274-281`), which is the same reliance the empty-batch branch already has at
`:203-204`. FIX-69's invariant — nothing stays queued with no commit in flight and no window —
must still hold, with no item failed.

What this replaces: today one refusal fails up to `maxBacklog` rows plus every waiter (drain loop
`:194-214`, waiters pulled in at `:228-232`), counted in `PgMessageStore.failed()` (`:87`) and
never retried. D-104 records what is sacrificed instead (tempo, and the newest rows under
sustained load) and why that trade is one-sided for trail rows.

Shape the designer proposed, to follow unless you find it wrong:
- `drainLocked` loses its `while(true)`: one take, one arm attempt, return.
- `Refused`, `Drained.refused` and the refused half of `dispatch` (`:239-241`) go away.
- The result becomes `Drained<T>(List<List<Pending<T>>> commit, Throwable windowRefusal)`, with
  the refusal logged and counted in `dispatch`.

Observability, as D-104 fixed it: a counter `windowsRefused()` always rises, and a WARNING is
logged **on the first refusal of an episode and on recovery** — not once per refusal. Two lines
per episode, so a permanently refusing timer cannot flood the log, and silence is still
impossible (F-13).

## (a) The Error-catch inconsistency — three sites, not one

- `waitLocked` at `:143`: the catch at `:147` takes only `RuntimeException`, so an `Error` from
  the deadline's `timer.schedule` escapes to the `saveWaiting` caller as a throw, while the window
  path at `:202` catches `RuntimeException | Error`. **The window path is right**; widen `:147` to
  the identical `RuntimeException | Error`.
- `commit()` at `:268` catches only `RuntimeException`, so an `Error` from `sink.write` escapes
  `dispatch` and leaves that batch counted in `inFlight` and `flying` with its items pending
  forever. This site is **not in the row** — the design found it, and the (b) policy makes it
  reachable by an OOM-ing timer, so it must be widened in this same task or (b) breaks the "every
  future completes" property.
- The rule to pin: *no public method of `BatchingWriter` ever completes by throwing; every throw
  becomes the item's failure cause.* Not swallowed — `Trail.write` logs it with the cause
  (`Trail.java:164`).
- Settled, do not re-open: `Future.future(handler)` captures an `Error` (exception table
  `Class java/lang/Throwable` → `Promise.tryFail`, measured with `javap -c` on
  vertx-core-5.1.10.jar), so a message's answer is always decided and no change is needed at
  `SequentialIntake.java:47` for this.

## (c) Dispatch order

- Must hold: within one drain, the batches reach the sink **oldest first, in the order
  `takeLocked` produced them**. The writer's global order is queue (FIFO) then waiters (FIFO), and
  a waiter exists only when the queue is full (`:132-133`, `:228-232`), so every queued item is
  older than every waiter and the concatenation of batches in hand-off order equals arrival order.
- What pins it: a sink that records the lists it is handed and completes only on demand. With
  `commitConcurrency 2` and two full batches, `sink.batches == [[0..n-1],[n..2n-1]]`.
- Documented **non**-guarantee, do not assert it: a sink that completes inline re-enters
  `afterCommit` → `drainLocked` → `dispatch` from inside the loop, so a younger batch can reach
  the sink before the outer loop's next one. True today. Leave it true and say so in the javadoc.

## Tests this task must end with

1. `aRefusedWindowCommitsTheTakenBatchAndEveryItemSucceeds`
2. `aPermanentlyRefusedWindowDrainsTheWholeBacklogAndEveryWaiter` — `backlog() == 0`,
   `waiting() == 0`, no item failed with the timer's cause
3. `anErrorFromTheWindowScheduleIsCommittedAndTheWriterStaysUsable`
4. `aRefusedWindowIsCountedAndNotSilent` — `windowsRefused()` rises once per refusal, and the
   WARNING appears on the first refusal and on recovery only
5. `anErrorFromAWaiterDeadlineScheduleFailsOnlyThatSaveWithoutThrowing` — `assertDoesNotThrow`, a
   failed future carrying that `Error`, `waiting() == 0`, the writer still usable
6. `anErrorFromTheSinkFailsOnlyItsBatchAndReleasesItsSlot` — the third (a) site: the items fail,
   `inFlight() == 0`, the writer still usable
7. `oneDrainHandsItsBatchesToTheSinkOldestFirst` — (c), with the deferred recording sink
8. jqwik, extending `BatchingWriterPropertyTest`:
   `everyItemReachesTheSinkOnceAndTheirConcatenationIsArrivalOrder`, with the window timer
   refusing on a generated flag — this pins (b)'s liveness and (c)'s order together

No contract-suite case: `BatchingWriter` has one implementation, and the `MessageStore` contract
cannot see a window timer (`InMemoryMessageStore` has no writer).

A fake clock drives every deadline and every backoff. No sleeps.

## Declared conflict — authorised, with conditions

D-104 supersedes FIX-69's "fail the taken batch". **FIX-69's recorded acceptance and these test
sites assert the opposite and are now wrong expectations**: `BatchingWriterWindowRefusalTest.java`
`:28-32` (the class javadoc, which calls the old behaviour "the architect's decision"), `:178`,
`:236`, `:251`. Changing them is authorised here, under CLAUDE.md's "a wrong expectation with the
reason in the commit message": each changed expectation is named in the hand-back with the reason,
and nothing else in that file is touched. If a case cannot be rewritten without losing what it
protected, stop and say so instead of deleting it.

Rewrite in the same task: the javadoc of `save`/`saveWaiting` at `:99-101` and `:110-114`, which
still promises a failure "with the timer's exception when its batch was taken", and
`drainLocked`'s at `:187-191`.

## Out of scope

Wiring `windowsRefused()` into `/health` — that would add a JSON field to
`AdminServer.HealthProbe` (`AdminServer.java:181`, `:481`), a wire surface D-104 deliberately
left alone. The counter is enough for a test.

## Allowed paths

- `flottila-core/src/main/java/io/flottila/core/batch/BatchingWriter.java`
- the `BatchingWriter*Test` classes under `flottila-core/src/test/java/io/flottila/core/batch/`
