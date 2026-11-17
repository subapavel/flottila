# FIX-267 - the architecture advisor's second pass (2026-11-16), as delivered; wrapped, not reworded

**Verdict on the second pass: SOUND WITH CHANGES.** A is a real defect, B's bounds are right in kind, and C is the
honest shape rather than over-building. One claim in B is wrong and would let an act commit off its standby; the probe
has a hole for rows without a key; the rows contradict A. Paths are under the repository root.

## Changes asked for

1. **B: the local timeout also covers the COMMIT.** The second pass says the standby wait "is not cut by it"
   (`agent/work/FIX-267-designer-2.md:146-148`). The code says the pin exists so that "no timeout cancels that wait"
   (`platform/runtime/src/main/java/dev/rail8/runtime/node/Node.java:396-398`), and the commit is sent inside the same
   transaction (`platform/runtime/src/main/java/dev/rail8/runtime/pg/PgTx.java:31-34`), while the local value still
   holds. Scenario under `ha`: the standby lags past PT5S, the wait is cancelled, the act is committed locally and
   answers 200 or 202; after a failover the cancelled message is back in the failed list with no audit row. Wanted: the
   act's last statement before the commit sets the timeout back to 0, and ARCHITECTURE says "the act's statements are
   bounded, its commit never". Test: a deferred constraint trigger records `current_setting('statement_timeout')` at
   commit. (Postgres semantics from memory, not run.)
2. **B: the bound is per statement, not per act.** "Also bounds a lock wait" is right, and it covers the row lock and
   the advisory key lock alike. But the act has three or four statements, so its worst case is a multiple of the key.
   Wanted: say so. `SET` takes no bind parameter, so use `set_config(..., true)` (unverified).
3. **B: the trigger is not sound.** One warm point at 1M rows against a configurable PT5S will almost never fire and
   says nothing at 10M. Guard A also puts the scan into `/retry`, which was O(1)
   (`platform/runtime/src/main/java/dev/rail8/runtime/queue/PgWorkQueue.java:290-301`). A partner outage with a
   multi-million backlog of an unkeyed flow then answers 503 to every retry and cancel exactly when operators act.
   Wanted: the bench records ms per 1M rows, warm and cold, and the backlog at which PT5S is reached. Rule: the seq
   column becomes a row if that backlog is below a number the architect states (suggested: the largest per-flow backlog
   any gate or bench run has held). The 503 names the key.
4. **Probe hole: a DLQ row without its key.** Every row parked before V17 has a null key
   (`deploy/postgres/V17__restart.sql:6-8`), though its work row may be keyed. The second pass leaves the predicate
   open ("plus `partition_key=$3` when keyed", "the unkeyed one uses `work_flow_seq`", `FIX-267-designer-2.md:27-28`,
   `:43-44`). If a null key becomes `partition_key is null`, the probe misses the keyed row; the cancel deletes the DLQ
   row and the unsettled row reruns. Wanted: "a null DLQ key probes by id and flow only", with one contract case for it.
5. **A: real in a living system, stageable, no new deadlock; one residual to name.**
   - Real without a death: an interrupted park "may still land" and the item is left to its lease
     (`platform/core/src/main/java/dev/rail8/core/flow/FlowWorker.java:699-703`); a failed settle does the same
     (`:747-749`). Both leave the DLQ row beside a leased row for a whole lease.
   - Locks: the order stays key lock, then DLQ row (`PgWorkQueue.java:605-606`); the worker's complete takes only the
     key lock (`:205-206`); the probe is a snapshot read.
   - Unkeyed: nothing serialises the probe against a settle, and nothing needs to. A row of a parked id is created
     only behind the DLQ row lock, so a racing settle gives at worst a false Unsettled.
   - Residual: after the refusal, the old row reruns alone within its bound (`FlowWorker.java:637`), delivers, and the
     DLQ row stays. A later `/retry` delivers twice. Wanted: the finding says the guard does not close this, and
     records either acceptance under D-160 or a CONSIDER row ("a rerun of an id with a DLQ row completes unsent": one
     primary-key read per rerun beside the timeline read at `:641`).
6. **The rows contradict A.** A says "the first row, on its own" (`FIX-267-designer-2.md:12`), but 267a bundles the
   guard with the new type, the move, the bounds and about 20 test files (`:94-99`, `:109-118`). 267a's `Done(Event)`
   (`:72`) also has no audit row to carry until 267b (`:119-120`). Wanted:
   - 267a0 [B]: the guard in place in RESTART, answering with a typed failure as the lost body does
     (`PgWorkQueue.java:612`), mapped to 409; the finding; the `DlqRestartIT` red case. No interface changes.
   - 267a: the type, the moved `retry` with its audit row, the bounds.
   - 267b: `cancel`, `events`, the index, `Dlq.remove` out. 267c: the routes.
7. **C: one leak to close.** The only cycle rule is over modules
   (`platform/archtest/src/test/java/dev/rail8/archtest/Rules.java:117`) and core's surface rule allows core-to-core
   (`:78-83`), so `core.dlq` depending on `core.queue` passes; `core.queue` imports nothing from `core.dlq`. The
   in-memory lock is enough given the invariant. But a public `InMemoryWorkQueue.append`
   (`FIX-267-designer-2.md:84-85`) keeps the unguarded restart alive: a test or the simulation can append for a parked
   id, and the twin is green where Postgres refuses. Wanted: replace `row` plus `append` with one method that appends
   unless a row of the id exists and returns that row, under the queue's own monitor. The jqwik property drives park
   and settle on the two stores directly, never through `FailedMessages`. `PgFailedMessages` taking `PgWorkQueue` for
   `lockKeys` is fine.
8. **Small contradictions with what was taken.**
   - "The audit row of both acts in their own statements" (`:119-120`) reads against the first draft's one statement
     with the delete (`PgWorkQueue.java:258-262`). Wanted: "in the act's own statement".
   - `cancel` with `by.reason()` absent has no stated failure. Wanted: `IllegalArgumentException`, mapped to 400.
   - The audit detail without `error` is not restated in the second pass; keep it in the packet.
   - Consistent with the changes taken: `Event` carries the flow (`:76`), the loser's read comes after the act
     (`:74-75`), actor and reason are 400 (`:136`).

## Row order

Keep FIX-264 and FIX-265 before the type move. Moving first means rewriting FIX-265's packet paths
(`PgWorkQueue.java:292`, `WorkQueue.java:274`) and colliding with FIX-264's test churn. Nothing is gained: there is no
release and no production data (`docs/findings/F-55.md:63-64`). With the split, 267a0 can go as soon as FIX-263
passes; FIX-265 then deletes its clause from a statement with one more CTE, a one-line merge. Put 267a0 before FIX-268
so the gate does not measure a `/retry` without the guard. Until 267a lands, 267a0's scan runs unbounded; say that in
the row.

## Not read

FIX-268's dependency column, `FlowBuilder.java:500-502`, the 20 decorators, `DlqRestartIT`, `PgWorkQueueRestartIT`,
`core.node`'s imports, any jar or Postgres documentation. Nothing was run.
