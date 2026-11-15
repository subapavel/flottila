# FIX-215 — scout's facts (2026-11-15, read-only; packages io.rail8.*)

## LedgerMain.java — order: kill, exit, join, snapshot, release, node 2
- :270 `node1.destroyForcibly()`, :271 exit value, :272 `load.join()`, :277 `ackedAtKill = List.copyOf(sending.acked)`.
- :278 one statement `select clock_timestamp(), (select count(*) from work)` gives killedAt (DB clock) and workAtKill.
- :280 `unfinishedAtKill = missing(pool, ackedAtKill).size()`, a separate, later query. MISSING (:124-125): the ACKed ids with no
  `messages` row `step_name = 'partner' and outcome = 'ok'`. Node 2 starts at :284-287.

## LedgerIT.java aKilledNodeLosesNoAckedMessage (:103-164)
- :128-130 finishedByNode2 = count(distinct id) of ACKed ids whose step-0 row is before killedAt and that have a final row
  (`partner`, `ok`) created after killedAt.
- :131 `finishedByNode2 >= 1`; :132 `finishedByNode2 <= unfinished` ("only what was unfinished at the kill is finished after it").
- :144 missing empty; :146-147 dlq 0, work 0; :148-150 distinct final ids == acked (its message: "a lease re-run's duplicate row
  shares its id"); :157-162 savedUnacked, duplicates.
- BASE-1 run 3 (agent/work/BASE-1-verify-all.log:81437-81451): workAtKill 66, unfinishedAtKill 60, finishedByNode2 66, finalRows 200,
  missing [], work 0, savedUnacked 0, partnerCalls 207, duplicates 7, resent 7.

## The runtime's order at the last step
- Recorder.java:83 records the OK snapshot; Trail buffers it (Trail.java:23-24).
- FlowWorker.java:663-671: `trail.close()...join()` (:669), THEN the verdict COMPLETE (:671). Trail.java:145-167 writes the rows;
  PgMessageStore.java:87-88 -> BatchingWriter (commit :381, promise :393). Insert `on conflict (id, step, attempt) do nothing`.
- Then FlowWorker.java:742-746 settles: PgWorkQueue COMPLETE_BATCH, autocommit (:229-231; answered after its batch commits :509-512).
- So the final row is committed in its own transaction before the work row's delete is sent. A kill between the two leaves a work row
  beside a final row; the takeover runs the item again with attempt = item.attempts (FlowWorker.java:647) and writes a second final row.
  LedgerMain.java:294 comments "an item is completed only after its trail closed".

## The design
- ARCHITECTURE §4 (line 240): a takeover "counts an attempt and runs at once"; "Each attempt reruns from the ingress, so earlier `to`
  steps are resent under the same id (O-9; CONSIDER-13)".
- D-101 (3)-(4): "Duplicates remain only for sends in flight at a real crash to partners that do not dedupe, and are visible."
- D-135: each attempt reruns from the ingress. D-140 / changelog 3.19: a same-id rerun's attempts keep their own snapshot rows,
  `messages` keyed (id, step, attempt).
- Nothing promises that a finished message is never run again or that a second final row cannot exist.

## The ledger's flow
- NodeBenchMain.java:587-606: flow `bench`, delivery `at-least-once`, recording all, payload keep, steps `[to partner]`, no partitionBy.
- The partner (LedgerMain.java:449-483) is a Vert.x echo in the bench JVM that counts calls.

## History
- 2026-10-28 P1-10b created LedgerIT. FIX-184 (BACKLOG row): "LedgerIT:132 fails on HEAD as well (1 of 3 vs 2 of 3)"; its hypothesis
  was the instant of the count. No entry records an edit to :132.
- Correction to MAIN-1-scout.md section 1: MigrationsIT.java:140 is the `bodies` table; the insert at PgMessageStore.java:53 omits
  created_at, so the column default applies.
