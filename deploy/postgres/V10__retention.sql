-- P2-07 (D-088, F-20): the retention job deletes by age in keyset batches. Each table it deletes from gets one plain btree
-- index led by created_at and ending in the table's key, so a batch's `created_at < cutoff and (created_at, key) > cursor
-- order by created_at, key limit n` is one index range. Nothing is partitioned (F-20). Each index adds to the cost of an
-- insert into its table (measured in bench/RESULTS.md, P2-07).
create index messages_created_at on messages (created_at, id, step);
create index bodies_created_at on bodies (created_at, message_id, step, part);
create index processed_ids_created_at on processed_ids (created_at, scope, key_hash);
