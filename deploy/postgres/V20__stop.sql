-- An ordered flow stops behind a message that failed for good: its worker parks the message and marks its work row stopped, in
-- one statement. A stopped row is held — never claimed, renewed or completed, and still the oldest row of its key, so every later
-- row of the key waits behind it — and keyed. Only an operator's act moves the key on: a retry hands the row back in place, a
-- cancel deletes it; a release refuses it. Null: not stopped (every row before V20).
alter table work add column stopped_at timestamptz;
alter table work add constraint work_stopped_check check (stopped_at is null or (held_at is not null and partition_key is not null));
-- The stopped rows by seq, partial as work_held (V5): GET /admin/work/stopped pages them without scanning work.
create index work_stopped on work (seq) where stopped_at is not null;
