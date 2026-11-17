-- The node no longer deduplicates arrivals: no save reads or writes processed_ids, so the table goes, with its index.
drop table processed_ids;
