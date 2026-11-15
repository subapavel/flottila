# FIX-262 — the architecture advisor's pass over the designer's draft (2026-11-15), as handed back

Written to this file by the architect, unchanged except for the wrapping; the advisor writes no file. Tree baa089c9.
Paths are from the repository's root; Java is under `platform/<module>/src/main/java/dev/rail8/`. The row is at
`agent/BACKLOG.md:514`.

**VERDICT: SOUND WITH CHANGES.** The direction and the Q4/Q5 code claims hold. Three statements of the draft are false
against the code, the test and backlog lists are incomplete, and it promises a "log" of retries that nothing writes.

## Required changes, most important first

1. **Keep `Delivery.admission()`.** The draft's "only caller is FlowBuilder:457" is wrong:
   `platform/core/src/test/java/dev/rail8/core/flow/DeliveryTest.java` reads it 9 times (:1488, :1508, :1589, :1637 and
   more) as the probe that a permit came back. Removing it breaks or forces rewrites of non-replay admission tests. Fix
   only the Javadoc at `core/flow/Delivery.java:146`.
2. **262b's test list is incomplete; it will not compile as written.** Not named by the draft:
   - `FlowBuilderStepEventTest` :339-399 and :449-469 call `b.replay` inside mixed cases.
   - `DeploymentsRestoreTest` :404, :462, :500 use `deployments.replay(..)` as the "flow runs" probe.
   - `DeploymentsRollbackTest` (:370, :468, :556, :1023), `DeploymentsRedeployTest` (:407, :525, :695) and
     `DeploymentsProducersEdgeTest` (:70, :81) assert producer counts that include the replay's set (6 becomes 3, and
     so on).
   - Require a per-case scout pass over all 50 test files with the word before 262a's packet. The scout and the
     designer both say they did not read the bodies, so "every needed property is moved" cannot be shown yet.
3. **"Retry 409, removed by retention" is unreachable; do not carry it over.**
   - Retention never deletes a DLQ row, nor the message, body or ref it names (`runtime/store/Retention.java:31-45`;
     `RetentionIT` :560, :581, :772).
   - So ARCH edit (5) at :327 and the :374 rewording state a case that cannot occur. Delete the F-20 exception clause
     at :374; keep 409 only for a lost body (`runtime/queue/PgWorkQueue.java:612`).
   - Rewrite `ReplayRetentionIT:211` as the positive property: a parked message older than the retention still answers
     202 on `/retry` and delivers after a retention run, plus GET 404 for an aged body of a finished message.
   - ARCH :718: "retry window" is false for the same reason (the DLQ row pins the body, not a window). Reword that open
     point rather than swap the word.
4. **"Recording is for monitoring only" is false as worded.** Every at-least-once run and every retry is rebuilt from
   the recorded ingress row. Without it the item is completed with a WARNING (`core/flow/FlowWorker.java:641-646`).
   Write: recording is for monitoring, except the at-least-once ingress row, which is the durable save runs start from,
   whatever the level.
5. **CONSIDER-13 is missed entirely.** It is accepted by the owner (D-142; `agent/BACKLOG.md:525`): resume a retry from
   the failed step, needing a durable resume envelope.
   - F-55 must say this envelope will be the worker's own, never the recorder's. Otherwise "no step N-1 body is needed"
     contradicts an accepted row.
   - Narrow CONSIDER-14 instead of withdrawing it: its "parked at step n" half is CONSIDER-13's.
   - Decide per-step `Parking` there. `FlowBuilder.build()` has no main caller, so `Parking.wrap` becomes test-only
     code.
6. **The message's "log" does not exist and `/retry` records nothing.** The RESTART statement writes no audit row
   (`PgWorkQueue.java:290-301`), `AdminServer.retry` takes no actor (`runtime/admin/AdminServer.java:772-802`; discard
   does, :705), and ARCH :386 says "there is no message log".
   - After a successful manual retry nothing says an operator resent it, who, or when. The changelog row's "recorded in
     its log" claims otherwise.
   - Mark it pending in the text, and widen 262e to "the operator's events on a message: retry's record and cancel" so
     one persisted shape serves both (the branch's D-108 had `resent` and `cancelled`).
7. **Gate.** The recorded evidence for check 7 (`HANDOVER.md:78`, "retry COMPLETE") is the answer format of the old
   new-copy route (`AdminServer.java:841`). The same-id `/retry` answers 202 `{id, attempt}` (:801). So no valid
   evidence exists for either half. Add a class-G row after 262c that re-measures check 7 and CORE-GATE condition 6
   (`docs/GATE.md:98-99`) and records the log path. Add `agent/packets/GATE.md` to 262d.
8. **ARCH :729 must not be "Closed".** The draft itself says the storage half is the owner's, and the item is parked by
   the owner. Reword it ("recompute a step's payload for display; never a re-send; parked") and leave it open.
   - Also name ARCH :419 (mapping trace re-runs a mapping over the stored step input) under "stays, not a message's
     replay", and let edit (1) at :327 say a skipped body also blocks the next step's trace.
   - D-064's stated reason (replay after a restart) becomes "a kept body stays readable after a restart". The D-066
     run-end waits at :425 lose replay as a reason: note it for CORE-8.
   - ARCH :685 is a phase's history: delete "replay", do not write "same-id retry" (it came later).
9. **Backlog sweep is incomplete.** Open rows that still build on replay, beyond FIX-138b and CONSIDER-14: CORE-13
   (:139, "refuses replay from the next step"), P2-10/P2-10b (:236-241, replay action, "lost replay body" variant),
   FIX-182 (:365), FIX-39 (:404), the row at :410 (`replay.close()`), the row at :412 (reserved prop `replayOf`),
   CONSIDER-15 (:212).
   - FIX-182 already owns "work pool held on `/retry` gives 503". If 262a's rewritten NodePoolWaitIT case is red on
     existing code, that is a behaviour fix: split it out, do not fold it in.
10. **Name the operator's hole precisely in F-55.**
    - The gap for a never-succeeding message is not opened by 262a. Today's DLQ replay removes the row only after the
      copy succeeded (`core/flow/Replay.java:154-159`), so such a row already stays forever and pins its message rows,
      bodies and refs.
    - What 262a newly strands: rows `/retry` refuses that a copy could have delivered. These are a flow redeployed as
      sync or at-most-once (:780), step != 1 or no attempt (:785; only `build()` or old replay rows produce these), and
      a pre-V17 keyed row (:789).
    - Already stuck before and after: flow not deployed (:779), body lost (:796).
    - 262e's brief must require that cancel works for every one of these, so it must need neither the deployed flow nor
      the body.

## The advisor's view on the eight points put to it

1. **Order.** The gap is acceptable with no release; do not build cancel first, and do not build a stopgap delete (it
   would be cancel without its marker, then changed). Run 262e's design right after the docs row, in parallel with the
   removals. Yes, the 422 dead ends are a hole the draft must name (change 10).
2. **Q4 is right.** ATTEMPT is written only at `FlowWorker.java:647` and :697; Replay strips it (`Replay.java:261`); a
   `route` prop named `attempt` is a String and fails `instanceof Integer` (`AdminServer.java:785`). `InMemoryWorkQueue`
   never had the guard, so removal makes real and in-memory agree. Nothing in sim, testkit, mock, bench or schemas uses
   `REPLAY_OF`. Remove it now.
3. **Q5 is right.** `FlowWorker` and `Replay` share package `core.flow`; no direction issue. In main, only
   `REPLAY_INPUT` (`FlowBuilder.java:470`) and `rebuild` lean on `Replay`.
4. **Q2** is right in the lines it lists; the misses are changes 3, 4, 5, 6 and 8.
5. **Q7.** Striking the replay sentence is forced by the owner's own decision. The added assertions are a proposal.
   Record both in an ISSUE (the ISSUE-3 precedent amended check 6), apply the strike as "draft - review at hand-over",
   and run the stronger measurement anyway.
6. **Rows.**
   - Two commits for 262a and 262b: each stays green (after 262a, `Replay` keeps `ReplayTest`, and
     `Deployments.replay` keeps its tests).
   - Raise 262c from A to B: it changes the RESTART statement and public api.
   - Deleting tests of withdrawn behaviour is not weakening, on two conditions: the test-author does it, and the
     per-case list exists first (change 2).
   - Properties at risk: permit return (change 1), producer close on rollback and redeploy (counts), the token on
     `/retry`, the virtual-thread offload of `/retry`.
7. **Conflicts.**
   - Ids: cite D-156 only in ARCH; no new ids, no bare branch ids.
   - Briefs: untouched; F-55 states precedence; a patch under `agent/patches/` for the owner.
   - Markers: one line at the head of D-136 ("replay half superseded by D-156") and of D-140 ("replay clauses dead,
     keying stands"); D-156 lists the other rows whose replay clauses fall (D-045, D-057(c), D-064, D-066, D-067,
     D-096, D-133, D-135).
8. **Alternatives, both rejected.**
   - `Replay` as a test tool keeps 265 lines, a second producer set per flow and an api key for what `rail8 test` does.
   - A developer-only replay from a step still sends to partners under a second id, which is the owner's objection; the
     dry form is CONSIDER-22.
   - Old `replayOf` props and "replay" step names are inert.
   - The site is already guarded: `ci/site_check.py:18` forbids the word (the scout's "no hits in ci/" is wrong; keep
     the rule). README :4 must not claim cancel before it exists: "captured and searchable".
   - Simpler cancel for 262e to weigh: one audited delete like `work.discard` (`PgWorkQueue.java:263-268`), state
     derived from the `audit` row, no new table.

## Risks accepted knowingly

- ARCH is ahead of the code for three commits.
- Dev databases keep rows with no operation until cancel.
- `Dlq.remove` has no main caller until 262e, and the sketched one-transaction cancel cannot use it anyway; 262e
  decides keep or drop.
- A coverage ratio may fall by deletion alone.

## Open questions: the architect's (the advisor's recommendation)

- One commit or two: two.
- Where rebuild lives: private static in `FlowWorker`.
- Ids: D-156 only.
- Narrow `Recorder.start`: later, as an A cleanup. Do not keep a step-1 test: there is no branch on the step
  (`core/record/Recorder.java:49-66`), so coverage is not at risk.
- Per-step Parking: a BACKLOG row now, decided with CONSIDER-13.
- 422 texts naming cancel: only in cancel's implementation row.
- "No decrease": literal on the module ratio, and no surviving line newly missed, per class, in the evidence file.

## Open questions: the owner's (all six are the owner's; the default in brackets)

- Counting [a failure]: "When an operator cancels a failed message, does it count as a failure of its flow, or as a
  category of its own?"
- Dry replay [stays parked, nothing built]: "With replay gone, do you still want the idea of storing only the input and
  recomputing step payloads for display, or drop it too?"
- Cancel scope [failed list only; reason required, since relaxing later is compatible]: "Is cancel only for messages in
  the failed list, or also for ones held or lost before they got there? Must the operator give a reason?"
- Gate [strike only]: "Check 7 asked for a replay from step 3. Is this enough instead: partner down, the message lands
  in the failed list, one retry delivers it under the same id, and its history shows every attempt?"
- CLAUDE.md [unchanged]: "The first paragraph still says 'replayable'. Apply the one-word patch?"
- Briefs [unchanged]: "Your two briefs mention replay in three places. Leave them as history or apply the patch?"

## Not checked by the advisor

- Bodies of NodeIT, InvoiceSyncReplayIT, NodePoolWaitIT, StepBodiesIT and DlqBodiesIT.
- ARCH :246 (the segment pin is the draft's citation).
- Whether `/retry` maps a pool refusal to 503.
- `Node.java:337`.
- Whether the sim digest iterates `Check` (no `ordinal()` or `values()` use found in sim main).
- Flyway's checksum behaviour.
- `agent/work/CONSIDER-15-designer.md`.
- The mapping repository.
