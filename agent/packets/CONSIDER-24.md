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
  -Dfailsafe.failIfNoSpecifiedTests=false -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.haltOnFailure=false >
  agent/work/CONSIDER-24-it.log 2>&1` (without the last flag the run stops at core's coverage floor, because
  `-DskipTests` leaves core uncovered). Delete `platform/runtime/target/failsafe-reports` before each run. Never
  `-Dtest=` on the runtime module.
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

## Step 1 - done (2026-11-15)

Eight cases, all green as written; the reading holds, case 3 included: `agent/work/CONSIDER-24-step1.md`. The IT
stages the death as "the node loses its database", not as a kill, and says why. One fact found on the way, for the
scout and the draft: on a journaled node another node never claims a dead node's items (the eligibility condition on
the body's node), so there the second run is the same node's after its restart.

## Step 2 - the window measured (implementer, then the verifier)

Per completed message: the time between the commit of its final rows and the delete of its work row. On this machine,
natively, under two loads; recorded in `bench/RESULTS.md` with machine, date, configuration and commit.

The method, with the least code:
- Exact, if the bench's Postgres can run with `track_commit_timestamp = on`: one trigger on `work`, after delete,
  writes the message's id into a side table in the deleting transaction. A message's gap is
  `pg_xact_commit_timestamp(side.xmin) - pg_xact_commit_timestamp(m.xmin)`, `m` its final-step row of attempt 1 in
  `messages`. No trigger on `messages`.
- Otherwise an upper bound: a trigger on `messages` and one on `work` stamp `clock_timestamp()`. An insert's stamp
  precedes its batch's commit, so the figure bounds the window from above - the entry says so.
- Prefer an SQL file - `bench/sql/settle-gap.sql`: install; and a report of count, p50, p95, p99, max, and how many
  completed messages have no pair - with the existing bench mains and their flags. Bench Java changes only if the
  mains leave no moment between the node's migration and the load to install a trigger: then stop and hand back the
  design first (a flag such as `--after-migration-sql <file>`; a test-author goes first).
- Nothing under `platform/*/src/main` changes. The trigger adds an insert per delete: say what that costs (the same
  load with and without it, throughput compared).

The loads: (a) the ledger's load without the kill - what `LedgerIT`'s kills saw 1, 6 and 2 re-runs under; (b) one
message at a time, closed loop: the floor. At least 20 000 messages each after a warm-up, or the main's defaults - say
which. How the native Postgres is run for a bench: the latest entries of `bench/RESULTS.md` and the bench notes of
`agent/NOTES.md`; find it, do not guess.

Paths: `bench/sql/**`. Hand-back (at most 40 lines): the files; the exact commands; one dry run of each load with the
report's numbers and the output's path; what `track_commit_timestamp` needed; which method; `git diff --stat`.
Then the verifier runs each load three times and hands back the numbers and the log paths; the architect writes the
entry.

## Step 3 - the other sources (scout), then the draft (designer, then the advisor)

Named by the first scout, to be confirmed or ruled out in one line each: a lease that ends during a slow run; a retry
after a step's failure (by design, D-135); a DLQ retry (the operator's, by design); intake's dedupe claim; a poller's
change of owner (not read); shutdown and drain (not read in detail). And who runs a dead node's items: on a journaled
node the body is on the dead node's disk - another node, or the same node after its restart, and after how long.

The draft answers: can the final rows and the work row's delete share a transaction, at what cost to the two batching
writers and the measured path; would a check "an earlier attempt recorded its final step OK" at the run's start be
sound, and what it closes (the sub-window between the two commits only - a death before the rows commit still
delivers twice); what a partner could be told (an attempt number on the wire) and what that changes for a partner
that dedupes on the id. The owner decides; the session writes the question in plain words.

## Step 2 - done (2026-11-16)

`bench/sql/settle-gap.sql` (the implementer; `agent/work/CONSIDER-24-step2.md`), exact by commit timestamps; no Java
changed. The verifier ran each load three times; the entry is `bench/RESULTS.md`, "CONSIDER-24". Under the ledger's
load: 4.2-4.5 ms on average from the final step's end to the commit of the rows ("record"), then 2.4-2.5 ms to the
delete of the work row ("settle"); settle is 36 % of the two together (29 % at one message at a time).

## Step 3 - done (2026-11-16): the sources, the draft, the advisor, and what the architect takes

Inputs: `agent/work/CONSIDER-24-scout-3.md`, `agent/work/CONSIDER-24-designer.md`, `agent/work/CONSIDER-24-advisor.md`
(SOUND WITH CHANGES, eight required).

The other sources of a second run, each named or ruled out:
- A dead node's items are run by that node after its restart: a journaled body is never a peer's before CORE-5b2..5e.
  They become eligible when the work lease (30 s) and the node's lease (30 s) have both passed. The ledger exercises
  exactly this (its node 2 is node 1's id on node 1's journal).
- A stop that passes `RAIL8_STOP_DEADLINE` (10 s) leaves its claimed rows to run again after the lease: the same
  second run, without a death.
- A scheduled retry after a failure runs from the ingress and sends the earlier `to` steps again (D-135): the routine
  source, CONSIDER-13's. The operator's retry: by design.
- A poller's change of owner: the file's dedupe key catches the duplicate while its `processed_ids` row lives.
  Intake: one accepted request never makes two work rows. Sync and at-most-once: no work row, never sent again.
- A repeat under another id - inferred by the scout (`PgIntake.java:80-83`), not staged: a sender whose answer was
  lost (a commit whose reply did not arrive) and that sends again without a dedupe key makes a second message. The
  partner cannot recognise it by `x-message-id`; the sender's dedupe key is its cure (D-084). FIX-270 says so.
- A second run beside a living, slow one: not shown. The scout inferred it; the advisor read that a worker adopts a
  claim of a seq it still runs and that a peer is eligible only after the holder's fence closed. Staged first: FIX-273.
- Read by the advisor, not staged: a death in the gap on the message's last allowed attempt parks a delivered message
  as exhausted, and an operator's retry sends it a third time. Staged by FIX-271.

What the architect takes (D-160; the owner's part is ISSUE-11):
1. Nothing under `src/main` changes by this row.
2. One transaction for the rows and the delete: rejected, as the draft and the advisor both say - although it is the
   only shape that cannot skip a run wrongly (the advisor's change 6, taken whole). Why all the same: a keyed item
   would keep two commits or lose its fast path (D-075); a form for unkeyed items only keeps the two stores coupled
   for half the items; both measured writers are rebuilt; and it closes only the part between the two commits.
   Settle-first is rejected too: a death between leaves a delivered message with no history of its run. ISSUE-11
   item 1 tells the owner that this shape exists and what it costs.
3. The draft's check "an earlier attempt recorded its final step OK - complete, do not run" is not built: the advisor's
   changes 1 to 3 are taken whole. Its safe form - a mark the worker writes only when the run returned normally - is
   CONSIDER-13's last-step case (change 4), noted on that row. By the advisor's rule (build it alone at about half the
   window or more) the measured 36 % says: not alone.
4. No attempt number on the wire (the draft's D).
5. The documents (the draft's E with the advisor's change 8): FIX-270, class C because it changes ARCHITECTURE; the
   draft and the pass exist.
6. The draft's row for the living holder (C) is not written as a fix: FIX-273 stages it first (change 7).
7. The ledger reports both kinds of second delivery: FIX-272 (change 5).
8. The eight characterization cases stay as they are: they are the cases a later decision would turn red.

Not taken: nothing of the advisor's eight is refused. The draft's test list (its section 2) and its property are not
carried into a row - they belong to the check that is not built; CONSIDER-13's packet starts from the advisor's list
of what they missed.
