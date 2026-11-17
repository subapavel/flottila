# CONSIDER-24 step 1 — the staged death: the test-author's hand-back (2026-11-15)

Written to this file by the architect from the hand-back; the test-author's own logs are named below. Tree baa089c9
plus the two new test classes. Nothing under any `src/main`, nothing in `testkit`, no existing fixture touched.

All eight cases are green, as characterization cases are expected to be: they pin today's behaviour. The packet's
reading holds, case 3 included.

## Core, in memory — `FlowWorkerRerunWindowTest` (5 cases)

VERIFIED_PASS (the test-author's run): tests=5, errors=0, skipped=0, failures=0 — `CONSIDER-24-core.log`.

1. `aNodeDeadBetweenItsFinalRowAndItsUnkeyedCompleteIsRunAgainAtTheLeaseEndAndThePartnerGetsTheIdTwice` — at the death
   the store already holds the first node's final row (attempt 1 OK), the work row stays, one send. A second node takes
   nothing 1 ms before the lease's end; at the end it takes the row as attempt 2 (doubts 1) and runs it from the
   ingress body.
2. `aNodeDeadBetweenItsFinalRowAndItsFastPathCompleteIsRunAgainAtTheLeaseEndAndThePartnerGetsTheIdTwice` — the same for
   a keyed item, whose settle is `completeAndTakeNext`.
3. `aNodeDeadAfterThePartnerAnsweredAndBeforeItsRowsAreRecordedIsRunAgainAndTheHistoryShowsOnlyTheSecondRun` — the
   partner got the id twice and the store holds a final OK row for attempt 2 only: the wider window, which a count of
   "two final rows" does not see.
4. `aNodeDeadAfterItsCompleteIsNotRunAgainAndThePartnerGetsTheIdOnce` — the control.
5. `theRerunsEnvelopeDiffersFromTheFirstRunsOnlyByTheAttemptProp` — id, flow, step, headers, keys, body key, hash and
   size, attachments and the outbound headers are equal; only the props differ, by `Envelope.ATTEMPT` (absent, then 2).

## Runtime, Postgres — `RerunWindowIT` (3 cases)

VERIFIED_PASS three runs in a row (the test-author's): tests=3, errors=0, skipped=0, failures=0 each —
`CONSIDER-24-it-1.log`, `-2.log`, `-3.log`.

6. `theFinalRowIsCommittedAndVisibleWhileTheDeleteOfTheWorkRowStillWaits` — the test's own session reads `messages`
   as "attempt 1 ok" while `work` still reads the row held by the first node. Released, the same DELETE runs and the
   row goes.
7. `aNodeThatDiesBetweenTheTwoCommitsIsRunAgainByAnotherNodeAndThePartnerGetsTheIdTwice`.
8. `theSecondRequestCarriesTheFirstRequestsHeadersAndNothingSaysAgain` — the partner is a real HTTP listener; the
   second request's headers equal the first's in names, values and order, nothing excluded; the body is equal. Which
   headers were on the wire was not printed.

## What would turn red under a later decision

- Cases 1, 2: `assertEquals(List.of(in.id(), in.id()), w.sentIds())` and the two final rows `attempt 1 OK`,
  `attempt 2 OK`. Case 7: the same two assertions against Postgres and the HTTP partner.
- Case 3 stays as it is whatever is decided about the two commits: nothing was recorded for attempt 1.

## How the IT stages the death, and what it is not

- Not a true kill. `NodeTakeoverLedgerIT`'s child-node fixture is private to its package and several hundred lines.
  And on a journaled node another node never claims the dead node's items (the eligibility condition on the body's
  node): there the second run would be the same node's, after its restart. To be read by the scout before the draft.
- The death is "the node loses its database": in-process nodes, each with its own pool and named sessions. While the
  first node's DELETE waits behind `lock table work in share mode`, every one of its sessions is ended with
  `pg_terminate_backend`; its round fails, the worker is stopped, the pool closed. Only then is the stall released.
- The lease's end: `update work set locked_until = now() - interval '1 second'`, as `PgWorkQueueIT:93`; the second
  node's poll just before it takes nothing.
- Both nodes read bodies from the test's in-memory store (no journal).
- A probe, not kept: cutting the first node's sockets through the `PgWire` relay instead, closer to a SIGKILL. The
  backend kept waiting; after the release the row stayed and the backend ended (`CONSIDER-24-it-probe.log`). The
  test-author expected the DELETE to commit and does not know why it did not.

## Deviations from the packet

- The packet's IT command stops at core's JaCoCo floor under `-DskipTests`; the IT runs used
  `-Djacoco.haltOnFailure=false`.
- The core class's teardown fails the held save: one expected WARNING with a stack trace in the log.
- 850 lines in two new files; the diff is `agent/work/CONSIDER-24.diff`.
