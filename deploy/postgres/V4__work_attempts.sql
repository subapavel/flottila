-- P1-08b (D-055): every hand-out of a work row counts an attempt; the attempts a row was handed out with are the holder's
-- fencing token for renewing its lease and for holding the row. A held row (held_at set) is never claimed again and, still
-- the oldest row of its key, blocks the rows behind it until a human deals with it (admin route in P1-09; SQL until then).
alter table work add column attempts int         not null default 0;
alter table work add column held_at  timestamptz;
