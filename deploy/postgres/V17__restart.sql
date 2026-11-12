-- CORE-14b (D-135 P8, O7, draft): the same-id restart of a parked message. POST /admin/dlq/{id}/restart deletes the message's DLQ
-- row and inserts its work row again in one transaction: same id, step 1, attempts k (the attempts it was parked after) and
-- max_attempts k + 1, the row's own bound on its hand-outs — its next hand-out is its last. Null (every earlier row, every other
-- insert): the flow's bound (delivery.retries count + 1). No default, no index (O4).
alter table work add column max_attempts int;
-- The partition key of the work item a worker parked, which the restart enqueues the row under again (the tail of its key). Null
-- for an unkeyed item, a park outside a worker and every row parked before V17 (a keyed flow's such row is restarted only by /retry).
alter table dlq add column partition_key text;
