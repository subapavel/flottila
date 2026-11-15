# Packet CONSIDER-24 (class C) — the re-run window at a node's death: the cause shown, then measured, then a draft

Row: `agent/BACKLOG.md` CONSIDER-24. The owner, 2026-11-15, in the session: "zjisti příčinu" - find the cause.
`LedgerIT` saw 3 of 10 kills run 1, 6 and 2 of some sixty in-flight messages again (`agent/work/FIX-215-evidence.txt`).

The row's order: (1) the cause shown by a staged death, every other source of a second run named or ruled out;
(2) the window measured; (3) a designer's draft - can the two store calls become one transaction, what is a partner
told about a re-run; (4) the owner decides whether anything changes. Nothing under `src/main` changes in steps 1-3.

## The reading (from the code; `agent/work/CONSIDER-24-scout.md`, nothing run)

- A run's rows - every step's, the final one included - are written when the run's `Trail` closes
  (`FlowWorker.java:669`), through the message store's batching writer: one transaction per batch.
- Only then is the verdict settled: `queue.completeUnkeyed(seq, token)` for an item without a partition key
  (`FlowWorker.java:742-746`), `completeAndTakeNext` for a keyed one - another writer, another statement, no shared
  transaction.
- A node that dies between the two leaves the work row, locked. After the lease another node takes it (`attempts + 1`,
  `doubts + 1`) and the run starts again from the ingress (`FlowWorker.java:641`). Nothing looks whether an earlier
  attempt already recorded its final step. The second attempt's rows are keyed (id, step, attempt 2).
- The partner gets the same `x-message-id` (`Envelope.java:142-147`) and nothing that says "again".
- The architect's reading, to be confirmed or refuted by case 3: the window in which a death delivers twice is wider
  than `LedgerIT`'s "rerun" count (two final rows). A death after the partner answered and before the rows are
  written also delivers twice, and the history then shows one finished run.

## Step 1 - the staged death (test-author, class C)

These are characterization cases: they pin what the code does today, so they are green when written. Say so in the
hand-back; do not bend a case to make it red. A case that is red against your reading of this packet is a finding:
stop and hand it back with the observation. The cases are the ones a later decision would turn red (a run skipped
when an earlier attempt recorded its final step; one transaction for the row and the settle).

What the test code offers is in `agent/work/CONSIDER-24-scout-2.md` (read it first): `FlowWorkerTest.World`,
`KillableTimer.kill()`, `RecordingWorkQueue.onComplete`, `FlowWorkerCompleteUnkeyedTest.Entries`,
`HoldingMessageStore`, `FakeClock`, `RecordingComponent`; in the runtime `PgWorkQueueBatchedCompletionIT`'s
`shareLock()`/`rowLock(seq)` and `NodeTakeoverLedgerIT`'s child nodes.

**Core, in memory** - a new class `platform/core/src/test/java/dev/rail8/core/flow/FlowWorkerRerunWindowTest.java`.
In-memory queue and store, the fake clock for the lease, no sleep. A "death" is: the node's timers silenced and its
settle call never reaching the queue, staged at the seam named in each case. An at-least-once flow of one partner step.

1. Death after the final row is recorded and before the work row is completed, an item without a key. Before the
   lease's end a second worker's poll takes nothing. At the lease's end it takes the row as attempt 2 and runs from
   the ingress. Then: the partner received the same id twice; the store holds the final step's row with outcome OK
   for attempt 1 and for attempt 2 under the one id; after the second settle the queue is empty.
2. The same for an item with a partition key (`completeAndTakeNext`).
3. Death after the partner answered and before any row of the run is recorded (the store holds the write). The second
   worker runs it again: the partner received the id twice; the store holds a final OK row for attempt 2 only.
4. The control: death after the work row is completed. Past the lease the second worker takes nothing; the partner
   received the id once; one final row.
5. What a component can tell the second run by: compare the two envelopes the partner's component received in case
   1 - id, headers, body hash, props. Pin exactly what differs (the reading: only the prop `Envelope.ATTEMPT`, 2 on
   the second). If more differs, pin what you observe and report it.

**Runtime, Postgres** - a new class `platform/runtime/src/test/java/dev/rail8/runtime/flow/RerunWindowIT.java`. The
real `PgMessageStore` and `PgWorkQueue`, Testcontainers Postgres as the other ITs declare it.

6. The two commits are apart. Stall the delete of the work row (the seam of `PgWorkQueueBatchedCompletionIT`), let
   the run finish. From a connection of the test's own: `messages` holds the final step's row, outcome ok, attempt 1
   - committed and visible - while `work` still holds the item's row.
7. A death there delivers again. From case 6's state the node dies; the stall is released; the lease ends; a second
   worker takes the row. Then: the partner received two calls with the same id; `messages` holds final OK rows for
   attempts 1 and 2 of the one id; `work` is empty.
   - The death: prefer a true kill (a child node and `destroyForcibly`, as `NodeTakeoverLedgerIT`) if its fixture can
     be reused in about a hundred lines of the new class; otherwise the node loses its database (its pool closed, the
     worker abandoned). Say which and why.
   - The lease's end: the way the existing queue ITs end one (find it); a bounded wait on a condition, never a sleep.
8. What the partner is told, on the wire. If case 7's partner is a real HTTP listener, assert that the second
   request's headers equal the first's apart from what HTTP itself varies (name what you exclude) - no header says
   "again". If case 7 uses a recording component, put this case in a new class under
   `platform/components/http/src/test/` instead: an envelope of attempt 2 produces the same request headers as the
   first attempt's.

Each case's name says the behaviour in words. A comment at the head of each class: these pin today's behaviour for
CONSIDER-24 and name the row.

## Paths

The three new classes above. A fixture you need goes into the new class as a nested type. An addition to an existing
test fixture under `platform/core/src/test/**` or `platform/runtime/src/test/**` only if a nested type cannot do it:
additive, no existing method changed, named in the hand-back. Nothing under any `src/main`, nothing in `testkit`.

## Tree and build

Your tree is the worktree named in the call, not the main checkout (another writer works there). Docker is up. One
Maven at a time: every Maven command under `flock /tmp/rail8-maven.lock`. Output to a file under `agent/work/` of the
worktree; read its tail or the failing section only.

- Core, this class: `flock /tmp/rail8-maven.lock mvn -B -ntp -q -f platform/pom.xml -pl :rail8-core -am test
  -Dtest='FlowWorkerRerunWindowTest' -Dsurefire.failIfNoSpecifiedTests=false > agent/work/CONSIDER-24-core.log 2>&1`
- Runtime, this IT (about 4 minutes after the first build): `flock /tmp/rail8-maven.lock mvn -B -ntp -f
  platform/pom.xml -Pit verify -pl :rail8-runtime -am -DskipTests -Dit.test='RerunWindowIT'
  -Dfailsafe.failIfNoSpecifiedTests=false -Dsurefire.failIfNoSpecifiedTests=false > agent/work/CONSIDER-24-it.log
  2>&1`. Delete `platform/runtime/target/failsafe-reports` before each run. Never `-Dtest=` on the runtime module.
- A core test run rewrites the tracked `platform/core/.jqwik-database`: `git checkout -- platform/core/.jqwik-database`
  afterwards.
- Library APIs: Context7 first, `javap` on the resolved jar decides; never from memory.

## Hand-back (at most 40 lines)

- The cases' names, per class; for each, green or red as observed, with the totals line and the log path
  (`VERIFIED_PASS` / `EXECUTED_FAIL`). The IT green three times in a row (`CONSIDER-24-it-1..3.log`).
- For cases 1, 2 and 7: the assertion that would fail if a run were skipped when an earlier attempt recorded its
  final step. For case 3: whether the architect's reading holds, in one sentence.
- Case 5 and case 8: what differs between the first and the second delivery, exactly as observed.
- How the death and the lease's end were staged in the IT, and why.
- Anything of this packet that could not be staged, and why. `git diff --stat`; the diff saved as
  `agent/work/CONSIDER-24.diff` in the worktree (`git add -N` the new files first).

## Step 2 - the window measured (after step 1; its own call)

Per message, the time from the final row's insert to the work row's delete, under `LedgerMain`'s load: triggers that
stamp both with `clock_timestamp()` (as `NodeTakeoverLedgerIT`'s ledger triggers), no change under `platform/*/src/main`.
An insert's stamp precedes its batch's commit, so the figure bounds the window from above; say so. Run natively,
recorded in `bench/RESULTS.md` with machine, date, configuration and commit: p50, p99, max, and the count.

## Step 3 - the other sources (scout), then the draft (designer, then the advisor)

Named by the first scout, to be confirmed or ruled out in one line each: a lease that ends during a slow run; a retry
after a step's failure (by design, D-135); a DLQ retry (the operator's, by design); intake's dedupe claim; a poller's
change of owner (not read); shutdown and drain (not read in detail).

The draft answers: can the final rows and the work row's delete share a transaction, at what cost to the two batching
writers and the measured path; would a check "an earlier attempt recorded its final step OK" at the run's start be
sound, and what it closes (the sub-window between the two commits only - a death before the rows commit still
delivers twice); what a partner could be told (an attempt number on the wire) and what that changes for a partner
that dedupes on the id. The owner decides; the session writes the question in plain words.
