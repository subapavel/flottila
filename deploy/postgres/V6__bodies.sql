-- P1-09h (D-058, D-061): the durable tier of at-least-once bodies. `BlobStore.persist` inserts a body here (`on conflict do
-- nothing`: the first durable bytes win for a key, as snapshots do, D-044) before the delivery answers Accepted; a key missing
-- from a node's local index is read from here, by any node. Keyed by message id, step and part — never by content. No code
-- path deletes from this table: only the retention job does (P2-07). The bytes are stored out of line and uncompressed
-- (storage external): bodies are mostly compressed already or small, and a read is a slice of the toast value.
create table bodies (message_id text not null, step int not null, part text not null,
                     bytes bytea not null, created_at timestamptz not null default now(),
                     primary key (message_id, step, part));
alter table bodies alter column bytes set storage external;
