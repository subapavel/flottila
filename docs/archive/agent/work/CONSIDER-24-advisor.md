# CONSIDER-24 - the architecture advisor's pass on the designer's draft (2026-11-16, HEAD 28e7ff9a)

The hand-back as delivered; read-only, nothing written. The draft: `agent/work/CONSIDER-24-designer.md`.

**Verdict: SOUND WITH CHANGES.** Documents (E), no header (D) and "not A" stand. B as drafted is not ready for the
owner: its guard proves nothing, FIX-a breaks the stated Postgres floor, and it decides a delivery from the recorder's
rows. C's scenario is not shown.

## Required changes

1. **B's guard (1) is neither needed nor sufficient.**
   - Today (read): no worker stage fails after the last step handed on. `Stage.of` sends, then hands on
     (Stage.java:21), so a last `to`'s OK row follows the partner's answer. Also FlowBuilder.java:534-538, :567-575;
     Recorder.java:74-105; Retry.java:77-80; StepEvent.java:60-66; MessageContext.java:68; Filter.java:25;
     Route.java:49-52. Not read: the Xslt/Xsd stages.
   - The `Stage` contract allows acting after `rest` (D-157's handler, :297's aggregator footer, any StepFactory).
     Then: the run fails after the last hand-on, and `trail.close()` still writes the last-step OK row
     (FlowWorker.java:668-669, Trail.java:105).
   - Any of a closed fence (:692-694), an Error, interrupt or FencedException (:683-685), a failed defer (:757-759) or
     a death before the defer leaves the row to its lease. The next hand-out counts a doubt (PgWorkQueue.java:162-163),
     B completes it, and the owed retry is lost with only an INFO line.
   - Change: B reads a mark written only when `trail.apply` returned normally (FlowWorker.java:665, :671), set by the
     worker on the still-buffered last row (Trail.java:83-91; more than 256 snapshots: no mark, no skip). The designer
     picks the carrier.
   - A run that returned normally is handed out again only if its settle did not land. So `Item.inDoubt`, FIX-a, the
     claim change, the claim bench and the same-version condition all go (the version condition re-sends a finished
     message under the new version).
2. **FIX-a as drafted breaks D-129.** ARCHITECTURE.md:249 (read): "Supported versions (D-129): PostgreSQL 17 and 18",
   CI floor `postgres:17.11`. `RETURNING old.` is PG 18 (unverified, as the draft says).
   - `doubts > 0` is no substitute: a doubt at attempt 1, attempt 2 fails after its last hand-on and is deferred
     (holder cleared, :238-239), attempt 3 has doubts = 1 and is skipped.
   - Nothing the claim returns today says "this hand-out counted a doubt" (ITEM, :165, running totals only). A PG 17
     form needs a column set in TAKE plus a migration. Moot with change 1.
3. **A conflict the draft's section 5 misses.** ARCHITECTURE.md:328 (section 6, F-55): the resume state is "the
   worker's own, never the recorder's".
   - Step rows are best-effort ("never retried", Trail.java:29-31) and absent at level errors/off or a per-step
     override (RecordingPolicy.java:55-61, Recorder.java:73, FlowBuilder.java:536).
   - So whether a death duplicates would depend on a monitoring setting. The finding names :328, and the :243
     sentence says "when the run's end is recorded".
4. **Offer "B folded into CONSIDER-13" as an option.** B is CONSIDER-13's last-step case (BACKLOG:532, accepted in
   D-142). Built alone it is a second "already sent, do not redo" mechanism to reconcile later, and CONSIDER-13
   removes the routine duplicates (every scheduled retry's earlier `to`).
   - State that B does nothing for split flows: child rows sit under `<parent>.<i>` (Split.java:44-46), the timeline
     is by exact id (PgMessageStore.java:59-60), and a rerun resends every child.
5. **The decision rule.** A lost message is not traded against a share, so change 1 is the gate. Then:
   - Use the ratio of sums, sum(1) / sum((1)+(2)), under the ledger load. Deaths hit in proportion to time in the
     window, so neither medians nor the closed-loop floor will do. Report the max: the completion batch has no time
     bound (:243).
   - Ask LedgerIT for the direct figure per kill: ids the partner got twice against ids with two final rows.
   - The ratio omits B's stronger effect. A doubt hand-out past the bound parks a delivered message as
     AttemptsExhausted (FlowWorker.java:637, :648-654). With `retries.count: 0` that is every death in the gap, and an
     operator's retry then sends it a third time.
   - The advisor's rule: build B if the share is about half or more, or if the owner wants that wrong park closed.
     Otherwise documents only. The 10 % threshold was set against a claim change that no longer exists.
6. **A: the rejection is right** (PgWorkQueue.java:205-206, :229-231; one `recordWaiting` per row, Trail.java:145-148).
   Add to "Rejected":
   - A is the only shape that cannot skip wrongly; the draft compares A and B only on what they close.
   - Unkeyed-only a1 keeps the store coupling for half the items.
   - Settle-first (delete, then rows): a death between leaves a delivered message with no rows of its run and no
     rerun to write them, a permanent monitoring gap.
7. **C is not a row yet.** A worker that re-claims a seq it still runs adopts the claim, with no second run
   (FlowWorker.java:403-407, :860-866). A peer is eligible only once the holder node's `lease_until` is cleared
   (PgWorkQueue.java:152-153), after its fence closed.
   - The overlap needs a second worker under the same holder id on a live node (inferred: a redeploy whose old run
     outlives the stop deadline plus the lease). Stage it first, as step 1 did.
   - If reachable it is worse than the death case: two concurrent runs, and on a keyed flow the successor can start
     while the first still sends.
   - Fake clock: "a send is refused after the last answered renew plus lease/2" is testable. "Never both reach the
     producer" holds on one clock only; word it as the D-112 residual.
8. **E.** The :297 sentence is true for today's producers: http (HttpComponent.java:414) and mock. sftp has none
   before Phase 4 (SftpComponent.java:168-170), so "every producer" must leave a headerless transport's carrier to its
   own row. Add:
   - the routine cause first (a scheduled retry), and a split's children;
   - an operator's retry has no limit in time (D-156), so a partner's dedupe memory has no safe expiry;
   - a partner that cannot dedupe will sometimes act twice. The choices are an idempotent operation on its side or
     at-most-once, which loses instead.
   - Mark it `draft - review at hand-over`.

## Risks

- Expensive to reverse and unmarked: the skip itself once operators rely on it, the mark's carrier (Snapshot or
  schema), and, without change 1, a hidden rule on every future Stage.
- The draft's tests miss: a failure after the last hand-on followed by a closed fence or an Error (only the deferred
  variant is tested); level errors on the last step; a split flow; an operator's retry dying in the gap (it now
  completes instead of re-parking).
- The property `...AtMostTwoOtherwise` is false for two deaths before the rows (three sends).
- D-156: attempts stay monotonic across an operator's retry (attempts = k, max k + 1, PgWorkQueue.java:286-297), so B
  cannot complete a retry from an older run's rows, provided the check stays "attempts - 1" and the in-memory twin
  numbers the same (not read).
- Measurement: (1) excludes the stop-deadline and closed-fence cases B also closes; (2) mixes the node's clock and
  the database's; the trigger's cost sits inside the gap.

## Open questions

- Q1 (the owner's; reword): "A message already delivered and recorded as finished is sent once more after a node's
  death and, on its last attempt, listed as failed. Should the platform trust its record and not send again? This
  helps only when that record was already written." The advisor's answer: yes, with change 1, preferably inside
  CONSIDER-13; otherwise documents only.
- Q2 (the owner's): yes. Say "before we noted it", not "our write".
- Q3 (the architect's): no row. Decide it with FIX-267, which owns the record of a retry.
- Q4 (the owner's): no, as the draft argues.
- Q5 (not yet a question): the scenario is unshown (change 7).
- FILTERED (the architect's): gone with change 1's mark.
- "Does any stage throw after its rest": none today (change 1), but never fall back to `doubts > 0`.
- Missing owner's question: what is a partner that cannot dedupe told (change 8)?

**Not read by the advisor:** the Xslt/Xsd stages, Recorder.java:120+ (a body above the threshold; inferred from
Trail.java:160-162 that the row is written without its body), InMemoryWorkQueue, NodeLease, PgMembership,
Deployments.drain, F-55, LedgerIT, the step-2 numbers, whether RENEW shares the pool.
