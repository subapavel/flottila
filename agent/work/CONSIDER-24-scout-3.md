# CONSIDER-24 - scout 3: the other sources of a second run (2026-11-15, HEAD 8cfd066e)

The scout's hand-back as delivered; nothing was run. Paths are relative to the repository root. "inferred" and
"not read" are the scout's own marks.

## 1. Who runs a dead node's items, and when

- `ELIGIBLE` (`platform/runtime/src/main/java/dev/rail8/runtime/queue/PgWorkQueue.java:144-159`), the three
  conditions that matter here:
  - the lease ran out: `locked_until is null or < now()` (:150);
  - the body: `$5 is null or body_node is null or body_node = $5` (:151); `$5` is the claiming queue's node,
    `opened.node()` (`Node.java:294`);
  - the holder: `locked_by is null or = $1 or no nodes row with lease_until not null` (:152-153).
- Another node may take an item only if all of these hold: its `body_node` is null (a pre-V12 item, body in
  `bodies`) or the queue has no node; the holder's `nodes.lease_until` is null (released or no row); `locked_until`
  has passed.
- An item whose `body_node` names a node (a body in that node's journal) is never claimed by a peer (:48-51).
- The restarted node itself passes the holder test (`locked_by = $1`) with no `lease_until` check, so it takes its
  own rows once `locked_until` has passed. `PgMembership.RERUN_AFTER_OLD_LEASE` (:47-50) also pushes its unheld rows'
  `available_at` out to the old incarnation's `lease_until`.
- Replication: the Javadoc says "until CORE-5 a body is read on its own node" (:50). `docs/ARCHITECTURE.md:247`:
  "takeover from a copy and re-replication are CORE-5b2..5e (pending) - until then every body has one copy and a down
  node's items wait for it". No use of the peer copy in `ELIGIBLE` (grep of the queue directory).
- Timing - both clocks must pass, the later one decides:
  - the work lease: `WorkerPolicy.DEFAULT` 30 s (its Javadoc), renewed every lease/3;
  - the node lease: `RAIL8_NODE_LEASE` default PT30S, max PT10M; `RAIL8_NODE_LEASE_RENEW` default PT5S, at most a
    quarter of the lease (`NodeConfig.java:76-79`, :650-652, :698-699);
  - the node's fence closes lease/2 after its last renewed send (`NodeLease.java:13-21`);
  - a peer clears `lease_until` with RELEASE (`PgMembership.java:71`, `lease_until < now()`), named in its renew
    answer, and only if its own fence stayed open for a whole lease (`NodeLease.java:27-30`);
  - an item with a keyed `partition_key` also waits behind earlier rows of its key (:154-158).

## 2. The ledger

- Node 2 is the same node id: `nodeEnv(o, NODE_1, ...)` (`bench/src/main/java/dev/rail8/bench/LedgerMain.java:284`),
  data directory `ledger-node-2`, with `RAIL8_JOURNAL_DIR` set to node 1's journal (:285; comments at :114, :59-61).
- The bodies of the unfinished items therefore come from node 1's own journal. The ledger exercises the same-node
  restart, not another node's takeover and not replication.
- Node 2 re-runs rows once the work lease has run out (:64; DRAIN = lease + 5 min, :104). `LedgerIT` not read.

## 3. A holder that is alive and slow

- A renewal answering false (`platform/core/src/main/java/dev/rail8/core/flow/FlowWorker.java:890-905`) sets
  `Renewal.LOST` and logs a WARNING. Nothing else happens to the running thread: `LOST` is read only at :863 and
  :874; there is no interrupt (grep). A failed renewal is logged and retried lease/3 later.
- The run's sends are checked only against the node fence (`FlowBuilder.java:675`), not the row's attempts token.
  The row token guards only complete, defer, hold and renew (`CURRENT`, `PgWorkQueue.java:197`). A stale complete
  answers STALE and is counted (`FlowWorker.java:751`).
- Inferred: a lease lost while the node is alive lets a second claim run in parallel, and the first run keeps sending
  while its node fence is open.
- A send already on the wire is abandoned when the fence closes (`FencedWait`, `FlowBuilder.java:96-98`), not
  recalled. `FlowBuilder.java:754` not read.

## 4. Shutdown and drain

- `FlowWorker.stop` (:251) lets claimed items run on; `close()` (:344) stays pending until their runs are settled in
  the queue (:335-338).
- `Deployments.drain` (:1697-1706) bounds this by `RAIL8_STOP_DEADLINE`, default PT10S (`NodeConfig.java:43`). Past
  the deadline it logs a WARNING and returns: "their work rows stay claimed ... and run again once their lease ran
  out". The thread is not interrupted at this point (not read further).
- `Node.close` order (`Node.java:529-534`, :537-576): deployments, node lease, ..., the work completion batch
  (`closeWork`), the message store's flush. So a run whose rows are written but whose delete was not handed over by
  the deadline is a re-run.

## 5. Pollers

- `docs/ARCHITECTURE.md:227`: a takeover "may overlap it by that file: a duplicate, never a loss", and a dedupe key
  "turns that duplicate into an `Accepted` naming the first message (D-084)".
- `SftpComponent.java:158-164`: every sftp endpoint names FILE_KEY. A remove that finds the file gone counts as done
  (:426). The key is `"sftp://" + host + ":" + port + file.path() + "@" + file.mtime() + "#" + body.hash().hex()`
  (:399).
- `PgIntake.java:117-118` claims `processed_ids(scope=flow, sha256(key))` in the save's transaction. A rolled-back
  group leaves the key free (:91). So the dedupe catches it, as long as retention has not deleted the row
  (`Retention.java:40`, :227).

## 6. Intake

- The work row is in the group's transaction with the ingress row (`PgIntake.java:465-478`). A group that fails
  before its commit reruns each survivor alone in its own transaction (:76-80).
- A commit whose reply was lost fails every member as retryable but leaves them open until `pg_xact_status` is known
  (:80-83). Inferred: a client's resend without a dedupe key then gets a second id and a second row.
- Not read: the batch sink's own retry code.

## 7. Sync and at-most-once

- `docs/ARCHITECTURE.md:239`: sync - "nothing - the caller retries; the platform never resends it (D-156)". :241:
  at-most-once - "logged and dropped". :243: "Sync and at-most-once runs are not fenced."
- `Delivery.java:55`: they run with a `Trail` of their own, with no seed. Inferred from the table: none has a work row.

## 8. What `docs/ARCHITECTURE.md` says about duplicates

- :243: "the rerun keeps the message id and is a failed attempt of the flow's schedule"; "Each attempt reruns from
  the ingress, so earlier `to` steps are resent under the same id (O-9; ..." (the rest not quoted).
- :243: "In doubt is derived, not recorded: a hand-out of a row an earlier holder left unsettled counts a doubt
  (`work.doubts`)".
- :297: every producer sends `x-message-id` "so the partner can dedupe a rerun" (D-113).
- :255: "exactly-once = at-least-once + `processed_ids` dedupe table". :245: the consumer's dedupe key goes into
  `processed_ids`; a key already seen "answers the first message's id".
- :227 (pollers, section 5) and :356 ("duplicates dropped at the save by the file key").
- Changelog :82-83 (D-113, F-28): a send abandoned at the fence leaves the item "in doubt".
- No sentence addressed to a partner says "at-least-once means you may receive a message twice". The nearest are
  :297 and :243.
