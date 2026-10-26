-- P1-09a (D-057 a): the admin API pages key search and the held work rows. Key search is in insertion order: every
-- key row gets a number, `seq`. V1..V4 wrote one key row per (message, step) carrying the key; adding the identity
-- column numbers the existing rows in the order they were written (one table rewrite, physical order), then every
-- duplicate (name, value, message_id) goes, keeping its first row (the lowest seq), and the triple becomes unique —
-- from now on a message's later steps insert nothing (`on conflict do nothing`, keys sorted per batch: D-044).
alter table message_keys add column seq bigint generated always as identity;
delete from message_keys later using message_keys earlier
 where later.name = earlier.name and later.value = earlier.value and later.message_id = earlier.message_id and later.seq > earlier.seq;
alter table message_keys add constraint message_keys_name_value_message unique (name, value, message_id);
-- the search index becomes (name, value, seq): it serves `where name and value order by seq` and the cursor's `seq >`;
-- the unique index serves the conflict check, so V1's (name, value) index would only slow every key insert down
drop index message_keys_name_value;
create index message_keys_name_value on message_keys (name, value, seq);

-- held work rows (D-055) for the admin listing (P1-09c): few, so a partial index
create index work_held on work (seq) where held_at is not null;
