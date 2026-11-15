# CONSIDER-24 — the scout's reading of the re-run window (2026-11-15), as handed back

Written to this file by the architect; the scout writes no file. Tree baa089c9. Paths are under `platform/`; "inferred"
and "not read" are the scout's own marks. Nothing here was run.

## 1. The end of a successful run (`core/flow/FlowWorker.java`)

- `FlowWorker.java:663-671`: `Trail trail = new Trail(...)`, `trail.apply(pipeline, ingress)`, then in `finally` (:669)
  `trail.close().toCompletionStage().toCompletableFuture().join();`, then `return failure == null ? Verdict.COMPLETE :
  failed(...)`.
- `Trail` does not write a row per step. It buffers every snapshot and writes them all at `close()`, or earlier when more
  than 256 are buffered (`Trail.java:42`, :88, :105-112). The final step's row is written with the other steps' rows.
- The write path: `Trail.write` (:160-163) calls `store.recordWaiting` (`PgMessageStore.java:87-88`), which is
  `writer.saveWaiting` - a `BatchingWriter` over `PgBatchSink`. One transaction per batch
  (`runtime/batch/PgBatchSink.java:51`, `PgTx.inTransaction`) carries `insert into messages ... on conflict (id, step,
  attempt) do nothing` (`PgMessageStore.java:52-56`).
- `close()` joins all the row writes, so the thread waits for the batch's commit. Only then does the verdict reach
  `settleOrTake` (`FlowWorker.java:557`, :570).
- An unkeyed item: `settle` (:742-746) calls `queue.completeUnkeyed(seq, token)` (`PgWorkQueue.java:516-523`), through a
  second `BatchingWriter` that sends `COMPLETE_BATCH` (:229-231, `delete from work ... where attempts = c.attempts`) as
  autocommit, no transaction (:555).
- An item with a partition key: `completeAndTakeNext` (:574-584), a separate transaction with a guarded delete
  (`PgWorkQueue.java:229`, :576).
- Nothing shares a transaction: the two commits are in different `BatchingWriter`s and different transactions. Between
  them there is only the second writer's batch wait.

## 2. The window's size

- The message store's writer: `BatchingWriter.Options.defaults()` (`Node.java:291`) - batch 100, window 10 ms, commit
  concurrency 4, backlog 6400 (`BatchingWriter.java:109`); max wait 10 s (:90). A save commits at once when nothing is in
  flight; otherwise it gathers until the batch is full, the window passes or a slot frees (`BatchingWriter.java:16-19`,
  :346).
- The work queue's completion writer: `Options(BATCH_MEMBERS=256, Duration.ZERO, BATCH_IN_FLIGHT=2, BATCH_BACKLOG=8192,
  BATCH_MAX_WAIT=5 s)` (`PgWorkQueue.java:353`, :217-223). A zero window means no timer: a row waits only behind the two
  statements in flight.
- So the gap is one pool round trip for the delete plus the wait behind an in-flight completion batch (inferred from
  the code; no measurement of the gap was found).

## 3. After a kill between the two commits

- The work row stays, locked by the dead node. `ELIGIBLE` (`PgWorkQueue.java:144-159`) takes a row only when
  `locked_until < now()` and the holder's `nodes.lease_until` is null. The lease's default is 30 s
  (`WorkerPolicy.java:21`), renewed every third of it (`FlowWorker.java:45`).
- `TAKE` (:162-163) sets `attempts = attempts + 1` and `doubts + 1`: a takeover counts as an attempt and runs at once
  (`FlowWorker.java:57`).
- The run starts from the ingress: :641 reads the timeline's ingress row, :647 sets the `attempt` prop when attempts > 1.
  D-135: "Each attempt reruns from the ingress".
- Rows afterwards: the second attempt writes under (id, step, attempt=2) (D-140; `PgMessageStore.java:56`). Two final
  rows under one id; the timeline orders by attempt, step (:59-60).
- No check detects "the previous attempt's final step is already recorded OK": `verdict()` (:635-672) reads the timeline
  only for the ingress row (:641) and has no skip branch. The place where the timeline is already read is right after
  :641 (named, not proposed).
- Past `bound(item)` the message is parked (:637, :648-654).

## 4. What the partner sees

- `HttpComponent.java:414` calls `e.withOutboundId()`; `Envelope.java:142-147` sets exactly one `x-message-id`, the
  message's id, the same on every attempt. Javadoc `Envelope.java:33-34`: "the partner dedupes a rerun on it".
- The attempt number is a prop (`Envelope.ATTEMPT`), not a header; `withOutboundId` copies the headers only, so no
  attempt number and no duplicate marker is sent (inferred; the other producers were not read).

## 5. Other sources of a re-run

- A kill before the final row commits: an unfinished run restarts from the ingress (`FlowWorker.java:641`) - the same
  path as section 3. The branch `in.isEmpty()` at :642-645 completes without running (not reached here).
- A lease that expires during a slow run: the renewal is fenced by the attempts token (:45-47; `RENEW` at
  `PgWorkQueue.java:233`). A renewal that fails is retried; a lease found lost is logged and no longer renewed (:47). So a
  second run is possible once a lost lease is claimed elsewhere.
- Fencing has two layers. The node's fence (a `BooleanSupplier`: `FlowWorker.java:149`, :206, :572;
  `FlowBuilder.java:675`, :754) blocks claims, settles and sends when closed. The row's fence is the attempts token on
  the complete, defer and hold statements (`CURRENT`, `PgWorkQueue.java:197`). A stale complete answers STALE (:562-566)
  and is only counted and logged (`FlowWorker.java:751`). Rows a fenced run already wrote stay (insert on conflict do
  nothing).
- A retry after a step's failure: `failed()` (:681-698) defers the row (`queue.defer` :756; `DEFER` at
  `PgWorkQueue.java:242`) - the same seq, the same id, a rerun from the ingress; D-135's defaults are 3 retries, PT5M. An
  Error, an interrupt or a `FencedException` leaves the item to its lease (:684).
- Intake: the dedupe claim `processed_ids ... on conflict do nothing` (`PgIntake.java:117-118`) is in the ingress rows'
  transaction. A double save is possible only when the client resends without a dedupe key. `LedgerMain` re-sends under
  a new id when its 202 was cut ("savedUnacked", `LedgerIT:180`).
- A DLQ retry: `restart()` (`PgWorkQueue.java:602-`) deletes the dlq row and inserts a work row in one transaction; the
  same id, one more run (D-135).
- Pollers (SFTP and others): `runtime/lease/PollerLeases.java`, a key held by one owner for a ttl (D-083). Redelivery on
  a change of owner: not read.
- Shutdown and drain: `stop()` and `close()` wait for items already claimed (`FlowWorker.java:251`, :344). Not read in
  detail.

## 6. LedgerIT / LedgerMain (`bench/`)

- `LedgerMain.java:270`: `node1.destroyForcibly()` (SIGKILL) after `--kill-after` ACKs. The partner answers the first
  kill-after/2 calls and holds every later one until node 1 is dead (:58). Node 2 then starts on an empty data directory.
- Counted: `finishedByNode2` (`LedgerIT:128-130`), `unfinished`, `workAtKill`, and `rerun` (:136: the id has two final
  rows, one before and one after `killedAt`). `agent/work/FIX-215-evidence.txt`: 60 / 54 / rerun 6; 59 / 58 / rerun 1;
  62 / 60 / rerun 2; seven runs with rerun 0.
- The re-runs would be rows whose final-row batch committed and whose delete batch had not been sent when SIGKILL hit;
  clusters of several ids fit the 100-member and 256-member batches (inferred, not checked).
- `LedgerIT:132-135` itself says "a run commits its final-step row first and deletes its work row afterwards". The
  strict counts come from the database snapshot at `LedgerMain.java:278` and :280; `LedgerIT:134-135` allows a one-id
  overlap from a commit Postgres completes after the snapshot.

## 7. One transaction?

- Both sides use a `Pool` (`PgMessageStore.java:67`, `PgWorkQueue.java:306`), built in `Node.java` from `pool` (that it is
  the same pool object: inferred, not confirmed).
- The message rows are batched across messages (up to 100 snapshots per transaction); the delete is batched across
  messages too (up to 256 rows per autocommit statement).
- Existing methods that write both kinds in one transaction: `PgIntake` (the ingress row plus the work row:
  `PgMessageStore.insert` :90-91, `PgWorkQueue.enqueue(SqlClient...)` :399); `PgWorkQueue.restart` (:602-: dlq delete plus
  work insert); `completeAndTakeNext` (:574-584: delete plus the next work row).
- No existing method writes a messages row and deletes a work row in one transaction. `PgDlq.park` (`PgDlq.java:61`) is a
  separate store call before `Verdict.COMPLETE` (`FlowWorker.java:653-654`).

## Not read, not sure

- Whether the node's message pool and work pool are one pool object.
- The `nodes` lease table's TTL, which decides when `ELIGIBLE`'s third condition lets another node take a dead node's row.
- `HttpComponent`'s and the other producers' full header handling.
- SFTP poller redelivery, the DLQ retry route in `AdminServer`, the worker's shutdown paths in detail.
- Any measurement of the gap itself.
- Whether the one-step bench flow's rows are flushed before 256 snapshots (the early flush is not exercised there).
