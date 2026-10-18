-- P1-05: the DLQ row carries the failed step's input envelope by reference (ARCHITECTURE §6: "DLQ row (by reference to the
-- body)"), so a retry needs no snapshot. V1's columns stay: `stage` is the step's name, `body_hash` the input body's hash.
alter table dlq add column hash_algorithm text        not null default 'sha256-64';
alter table dlq add column props          jsonb       not null default '{}';
alter table dlq add column keys           jsonb       not null default '{}';
alter table dlq add column body_key       text;                                  -- <message id>/<step>/<part>
alter table dlq add column body_size      bigint      not null default 0;
alter table dlq add column versions       jsonb       not null default '{}';     -- {"flow": "id@version", ...}
create index dlq_parked_at on dlq (parked_at, id);
