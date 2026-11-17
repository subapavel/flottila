# CONSIDER-24 - the designer's draft (2026-11-16, HEAD 8cfd066e; 23 reads; nothing written)

The hand-back as delivered. R = read, with path:line; I = inferred. Cases are those of `agent/work/CONSIDER-24-step1.md`.

## 1. Proposal: B plus documents. Not A. C and D are separate decisions.

**A. One transaction** is possible but not worth it. All three shapes close the same sub-window that B closes (the gap
between the two commits) and none of them touches case 3.
- (a1) The work delete joins the message store's batch (`PgBatchSink` tx, scout :51). This needs a new core
  `MessageStore` write that carries (seq, token) and returns the stale answer per member, plus its in-memory twin.
  Trail writes each snapshot as its own `recordWaiting` (R Trail.java:147, :163), so one run's rows can land in
  different batches; the last flush would have to become one member. The fence check moves to before the rows are
  handed over. The dependency rule holds (all in runtime), but section 8's store separation does not. The keyed path
  cannot join: `completeAndTakeNext` takes the next row under the key's advisory lock and returns it to the chain
  (R PgWorkQueue.java:205-206, FlowWorker.java:588), so keyed items either keep two commits or lose D-075's fast
  path. Red: cases 1, 6, 7; case 2 only if keyed joins; cases 5 and 8 lose their staging.
- (a2) The final rows go with the queue's completion batch: the autocommit delete (R PgWorkQueue.java:229-231)
  becomes a transaction full of message inserts. The same coupling the other way round, the same keyed problem, the
  same red cases.
- (a3) A "settled" mark in the rows' transaction that the claim reads: a new SQL schema, an extra index write per
  message, and a join in the FIX-130-tuned claim (R :168-175). B beats it: the final row already is the mark and the
  worker already reads it.

**B. A check at the start of a run** is sound under five conditions. Cost: no new statement. `verdict()` already reads
the whole timeline on every claim (R FlowWorker.java:641); the check filters that list before the exhausted branch
(R :648), so a finished message past its bound is settled, not parked. Settle as `Verdict.COMPLETE` through today's
`settle` (token and fence, R :742-746), log one INFO line, write no row.
- (1) This hand-out counted a doubt. A deferred attempt is a known failure and must run again even if its last step
  says OK: the OK row is written at hand-on, before `rest.apply`, and a FAILED row only when nothing was handed on
  (R Recorder.java:83, :88). So a failure an enclosing stage throws after the last hand-on leaves "last step OK" and
  no FAILED row.
  - `Item` carries only the running total `doubts` (R WorkQueue.java:45). TAKE derives the doubt from the old
    `locked_by` (R PgWorkQueue.java:162-163). So add `Item.inDoubt`, from `RETURNING old.locked_by is not null`:
    PG 18 syntax, unverified. Tests run postgres:18.6 (R testkit PostgresImage.java:13); the production minimum
    version is not read.
- (2) The row is attempt = attempts - 1, step = the deployed pipeline's last index, outcome OK, and `versions.flow`
  equals the running id@version (R Snapshot.java:38). A redeploy between attempts means it runs again.
- (3) Recording OFF writes no step rows (R Recorder.java:73), so the check never fires: safe, ineffective. The same
  for a level that skips the last OK row (R :83; the levels themselves not read).
- (4) Split children are recorded under `<parent>.<index>` (ARCH :297), so the parent has no last-step row and runs
  again (I).
- (5) A D-157 handled run: CONSIDER-15 must name the row that ends it; until then the check does not fire (safe).
- Closes: the gap between the two commits, keyed and unkeyed (cases 1, 2, 7); a COMPLETE verdict left unsettled under
  a closed fence (R FlowWorker.java:572-578); a stop past `RAIL8_STOP_DEADLINE` after the rows were written (scout 3,
  section 4).
- Does not close: case 3. The partner's commit and the platform's are two systems with no shared transaction. A death
  between them leaves the platform no record to read, so only the partner's dedupe on `x-message-id` closes it. Nor
  does it close a living slow holder whose rows commit after the second claim's timeline read.
- The number that changes this: the measured gap as a share of the span from the last send's answer to the work
  delete. If case 3's window dominates (the gap under about 10 % of that span), B removes too few duplicates to pay
  for a claim change: documents only, B optional. If the claim bench with the new RETURNING drops beyond noise: drop
  FIX-a, see Open.

**C. The living, slow holder** is its own row (FIX-d): "a worker run makes no producer send after its row's lease
could have passed. It reckons that lease like the node fence (last renew answered + lease/2, D-112), fails the send
with `FencedException` and leaves the item. A holder whose renewals stall past the lease and a second claim never
both reach the producer." Today only the node fence is checked (scout 3, section 3: FlowBuilder.java:675;
`Renewal.LOST` is only logged, FlowWorker.java:890-905).

**D. What a partner is told**: documents only - the same id, no header. A partner that dedupes on the id gains
nothing from a header. One that does not would have to read a header anyway, so it can read the id it already gets.
An attempt number cannot say "you had it before": attempt 2 after a defer (say the partner answered 503) and attempt
2 after a death look the same on the wire. `x-message-attempt` would also change request bytes for partners that
dedupe on a request hash, turn case 8 red, and bind every producer to a public header (D-113: not renameable). Adding
it later is cheap; removing it is not, so wait.

**E. Documents**, needed in either variant.
- ARCH :297, after "dedupe a rerun": "At-least-once can deliver a message to a partner more than once. A node that
  dies after the partner answered and before the run is settled, a retry after a failure, and an operator's retry
  each send it again with the same `x-message-id`; nothing on the wire marks it as a repeat. A partner that must not
  act twice dedupes on that id."
- ARCH :243, for the flow author: "every `to` step before a failure is sent again on each retry."
- With B, also at :243: "A hand-out in doubt whose previous attempt recorded the flow's last step OK under the same
  flow version is completed without running."
- Finding: the documents-only variant needs none (it states what D-101/D-135 imply). B needs one, because it changes
  ":243 / D-135: each attempt reruns from the ingress".

## 2. Tests (rewritten from today's characterization cases 1, 2, 7 by name)

- `aDoubtWhosePreviousAttemptFinishedIsCompletedWithoutRunning` (cases 1, 2 rewritten: one send, one final row).
- `RerunWindowIT.aNodeThatDiesBetweenTheTwoCommitsIsNotRunAgain` (case 7 rewritten).
- `aDeferredAttemptWithItsLastStepOkStillRunsAgain`; `aDoubtUnderAnotherFlowVersionRunsAgain`;
  `aDoubtWithRecordingOffRunsAgain`; `aFinishedDoubtPastItsBoundIsCompletedNotParked`.
- Unchanged: case 3 (still delivers twice) and case 4.
- Contract suite, Pg and in-memory: `aHandOutSaysWhetherItCountedADoubt`.
- Property (jqwik): `sendsAreOneWhenTheDeathFollowsTheRowsCommitAndAtMostTwoOtherwise`.

## 3. Rejected

- A (a1/a2): the keyed fast path cannot join, it restructures two measured writers, and it closes no more than B.
- (a3): a new schema and a claim join for B's effect.
- An attempt header, alone or with documents: see D.
- A row recording the skip: nothing ran or was sent, and the history already shows the finished run truthfully.
- Replay or a second id: excluded by D-156.

## 4. Expensive to reverse

- `RETURNING old.` sets a minimum Postgres version of 18 (unverified).
- `Item.inDoubt` in core `WorkQueue` (internal, moderate).
- The documented promise to partners, "dedupe on `x-message-id`", is public: `draft - review at hand-over`.

## 5. Conflicts

- B against ARCH :243 and D-135 ("each attempt reruns from the ingress"; "a takeover hand-out ... runs at once"):
  needs the finding.
- D-156 ("the message's own log says what happened"): B writes no timeline row for the skip. The owner's call (Open 3).
- CLAUDE "measured cost": B adds no statement but changes the claim's RETURNING, so a claim bench is needed.

## 6. Open - the owner's questions, the designer's default in brackets

1. When a node dies after a run finished but before it was ticked off, should the platform check first instead of
   sending again? [yes, B]
2. Is it acceptable that a death between the partner's answer and our write still sends twice, with partners told to
   dedupe on the id? [yes, it cannot be closed]
3. Should the skipped second attempt show in the message's own history? [no, an INFO line in the node log]
4. Should a partner be told the attempt number? [no]
5. Must a slow but living node stop sending once its message could have been taken over? [yes, FIX-d]
- The architect's, not the owner's: does any worker stage throw after its rest returned (FlowBuilder not read)? If a
  scout proves none does, drop FIX-a and use `doubts > 0` as the guard.
- Should FILTERED (a run that ended early) also count as finished? [no for now]

## G. Rows

The B variant, in this order:
- FIX-c (A), the finding plus the ARCH sentences in E: they are in the text, with a changelog entry.
- FIX-a (B): "every hand-out says whether it counted a doubt; Pg and in-memory pass one contract case; the claim
  bench is unchanged within noise in bench/RESULTS.md".
- FIX-b (B): "the check in `verdict()`; the tests above are green; cases 3 and 4 are unchanged".
- FIX-d (C): C's acceptance; independent of the others.

Documents only:
- FIX-c without the B sentence and without a finding: the :297 and :243 sentences plus a line stating `LedgerIT`'s
  "rerun" count as expected.
- FIX-d.

Not read by the designer: FlowBuilder's stage shapes, the recording levels, `PgMessageStore.timeline`, the production
Postgres version, the PG 18 `RETURNING old` documentation.
