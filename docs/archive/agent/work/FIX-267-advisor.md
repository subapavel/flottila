# FIX-267 - the architecture advisor's opposition (2026-11-16), as delivered; wrapped, not reworded

**Verdict: SOUND WITH CHANGES.** Option (a) and the work probe hold, and every file:line the draft cites that I opened
is correct. Three scenarios are unanswered (a swallowed resend, a loser's wrong answer, a cancel refused for an
unbounded time) and the in-memory twins diverge. Paths below are under the repository root.

## Changes asked for

1. **Dedupe key after a cancel (point 6, the worst trap).** A repeat under a taken key "succeeds with the id the key's
   row names" (`platform/runtime/src/main/java/dev/rail8/runtime/store/PgIntake.java:87-98`, `:117-121`). The key goes
   only when older than the cutoff and no `messages` row has its id (`.../store/Retention.java:225-234`). Scenario: the
   operator cancels M, the sender resends the corrected document under the same key, gets 202 with M's id, nothing runs
   and nothing is in the failed list. The window is max age minus M's age plus one interval. The draft states only the
   "after" (lines 46-48). Wanted: that sentence in ARCHITECTURE, and an owner question; default: the key stays until
   retention. Freeing it at cancel lets a sender's automatic repeat revive the message under a new id.
2. **The loser's answer.** If `last event` is a CTE of the cancel statement, a cancel that waited behind RESTART reads
   `audit` under its original snapshot (READ COMMITTED rechecks only the locked row; Postgres semantics from memory, not
   run). It then answers 404 instead of "409 retried at T by A". Wanted: "the last event is read by a statement issued
   after the cancel statement returns", and `cancelAndRestartOnOneRowOneWins` asserts the loser names the winner's event.
3. **Unsettled 409 has no bound; say it and name the exit.** A node that dies between the park and the settle
   (`platform/core/src/main/java/dev/rail8/core/flow/FlowWorker.java:697-698`, `:745`) leaves a row only that node
   claims (`platform/runtime/src/main/java/dev/rail8/runtime/queue/PgWorkQueue.java:127-129`; takeover is CORE-5c,
   pending, `agent/BACKLOG.md:129`). Cancel answers 409 until that node restarts. If it never does, the exit is the
   accepted loss, then `/admin/work/{seq}/discard`, then cancel. Wanted: that sentence in section 4, and the 409 carries
   `{seq, node, leased-until | held | lost}`.
4. **RESTART has the same hazard and no probe** (`PgWorkQueue.java:290-301`). A `/retry` while the old row is unsettled
   inserts a second work row for the id. The old row later re-parks against nothing
   (`platform/runtime/src/main/java/dev/rail8/runtime/store/PgDlq.java:61-62`), so the message is delivered and back in
   the failed list, or delivered twice. Wanted: a finding, and either the same probe in row b or a recorded acceptance
   beside D-160.
5. **The probe has no time bound.** Every session is pinned to `statement_timeout=0`
   (`platform/runtime/src/main/java/dev/rail8/runtime/node/Node.java:405`) and `work` has only `(flow, seq)`
   (`deploy/postgres/V1__m1_schema.sql:62`). With a backlog of millions (partner down, exactly when operators cancel)
   each cancel is a heap fetch per row of the flow. Holding the DLQ row lock meanwhile is harmless: it blocks only the
   same id. Wanted:
   - `set local statement_timeout` in the cancel's transaction, finite and configured; 503 on expiry, nothing written.
   - Keyed rows probe on `work_flow_key_seq` with the DLQ row's key (V17).
   - A plan IT (precedent `PgWorkQueueClaimPlanIT`) and one number at 1M rows in `bench/RESULTS.md`.
6. **The twins diverge.** `InMemoryWorkQueue.restart` appends and leaves the DLQ row
   (`platform/core/src/main/java/dev/rail8/core/queue/InMemoryWorkQueue.java:123-130`); the admin fixture is in-memory
   (`platform/runtime/src/test/java/dev/rail8/runtime/admin/AdminDlqRestartTest.java:91-92`).
   - In memory, `/retry` then cancel succeeds while the item runs; Postgres answers 409.
   - Row c's "timeline lists both events" cannot pass in memory: no retry event is recorded.
   - The property "no park, cancel, get brings a cancelled id back" holds only if `InMemoryDlq` refuses a park after a
     cancel, which `PgDlq` does not.
   Wanted: `events` leaves `Dlq` for one small core type with two implementations (audit-backed; in-memory, handed to
   both in-memory stores so both record) under one contract. The property is reworded to what both stores do, and the
   `Dlq` Javadoc says a later park recreates the row and the work probe is what excludes it.
7. **Timeline with `steps:[]`.** `flow` comes from the first snapshot
   (`platform/runtime/src/main/java/dev/rail8/runtime/admin/AdminServer.java:576`) and `Event` has no flow, so the 200
   loses a field clients read. Wanted: `Event` carries the flow. Also shape `events` entries as a subset of the brief's
   record (`docs/OPERATIONS-BRIEF.md:22-24`) so OPS-2 serves the field instead of superseding it.
8. **Audit detail copies `error` (up to 1024) into a table that is never deleted.** V16 says a detail "names, it never
   copies a body" (`deploy/postgres/V16__audit.sql:4`). Error texts quote payload values, which then outlive payload
   retention and bypass the payload right (`docs/OPERATIONS-BRIEF.md:130-131`). Wanted: drop `error` from the detail.
   Also add: "410 or 404 depends on the audit row; the refusal itself is the absent DLQ row", so a later audit retention
   is not a correctness question. That `audit` grows without bound stays D-126's open matter; say so.
9. **Actor (the most expensive of point 5).** A cancel without `X-Rail8-Actor` is stored for ever as `unknown`
   (`AdminServer.java:678-682`), and tightening later is incompatible (ISSUE-10 item 3's own rule). Wanted: 400 without
   an actor on cancel.
10. **Rows.**
    - `V20` is claimed by CORE-5b2a2 (`agent/BACKLOG.md:125`): number the migration at implementation.
    - FIX-269 still lists `Dlq.remove` (`agent/BACKLOG.md:522`): amend it.
    - Missing red tests: cancel succeeds for each of the five rows `/retry` refuses (`agent/packets/FIX-267.md:24-28`);
      retention after a cancel removes rows, bodies, refs and the key; the plan and timeout IT.
    - Row c need not replace FIX-263's case. The helper forbids only `/replay`, `use /` and `/retry`
      (`AdminDlqRestartTest.java:229-234`), and the packet asks for a refusal naming the cancel event, not the route.
      The 422 and 409 texts stay.
    - The order a, b, c is right.

**Point 1, the set of ways back.** The only inserts into `work` are the intake's (`PgWorkQueue.java:117-142`, called
from `PgIntake.java:471-478`) and RESTART (`:297`), so the journal's recovery has no path (grep only). Lease takeover
and re-park are covered by the probe, and a `/retry` in flight by the row lock. `Split` mints no id (grep only). A
redeploy is unchecked. **Point 7:** any member is sound; all state and the lock are in Postgres, and a fenced or leaving
node either reaches it or answers 503.

## Missed alternatives

- The DLQ row keeps the parker's work seq (PARK writes `item.seq()`, `FlowWorker.java:653`, `:697`). The probe is then
  by primary key, exact once RESTART uses the same guard. Trades a column and a larger `Parked`; old rows fall back to
  the scan.
- A closed DLQ row that retention exempts and removes with the message. The 410 comes from the row, PARK's `on
  conflict` blocks a re-park for free, and the record lives as long as the message. The draft's "pins for ever" is
  unsound as stated (the guard can exempt it); the rejection holds on size (five statements and `Parked`). Keep (a).
- Cancel settles the stale row itself under the key lock. Removes change 3's wait; trades lock order against restart
  and deleting a row a live worker holds. Not now.

## Risks

- A rerun after a final failure within its bound (`FlowWorker.java:637`, `:687`) can deliver while the DLQ row stays; a
  cancel then records "cancelled" for a delivered message. Existing behaviour; say it in the finding.
- FIX-268 measured before row c measures `/retry` answers that then change (its dependency is unchecked).
- The JSON body is the admin server's first buffered body (no `BodyHandler` in `AdminServer.java`, by grep). The Vert.x
  APIs are unverified by the draft and by me: javap first in row c.

## Open questions, recommended

- Owner: history goes by age after a cancel: yes. Retry's reason: optional. Undo: never; the sender's repeat is the
  undo, which is why change 1 matters.
- Architect: 410 for cancelled on both routes (permanent against transient without parsing text). JSON body.
- Not put to the owner and should be: the dedupe key after a cancel (change 1); whether every operator act must name
  its actor (default: required on cancel, optional on retry until section 9); whether the error text may be kept in
  audit (default: no).

## Not read

`Snapshot.java`, `PgMessageStore`, `FlowBuilder`'s per-step parking, the journal recovery code and `Deployments`, the
FIX-262 designer and advisor files, ARCHITECTURE lines 244, 370 and 504 in full, D-126 and D-135, any jar. Nothing was
run.
