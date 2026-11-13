-- FIX-175 (P1, D-140): each attempt of a same-id rerun (a worker's retry, D-135; a DLQ /retry, D-136) keeps its own snapshot rows.
-- The attempt is the run's Envelope.ATTEMPT, 1 for a first run (absent). messages is keyed by (id, step, attempt); a resent row of
-- the same attempt still first-wins (ON CONFLICT (id, step, attempt) DO NOTHING). Stop-all upgrade like V11 and V12: no pre-V19
-- node writes to a V19 database (its ON CONFLICT (id, step) names a key that is gone: 42P10). No other table changes:
-- message_keys, bodies and retention key on the message id. Flyway runs this file in one transaction on Postgres.
alter table messages add column attempt int not null default 1;
-- a pre-V19 row (a rerun's row that reached a step the first run had not) carries its attempt in its props: copied when it is a JSON
-- number written as an int of at least 1 (at most 9 digits, so the cast cannot overflow); a string (even "2"), a 0, a fraction or
-- no attempt stays 1
update messages set attempt = (props->>'attempt')::int
 where jsonb_typeof(props->'attempt') = 'number' and props->>'attempt' ~ '^[1-9][0-9]{0,8}$';
alter table messages add constraint messages_attempt_check check (attempt >= 1);
alter table messages drop constraint messages_pkey;
alter table messages add primary key (id, step, attempt);
