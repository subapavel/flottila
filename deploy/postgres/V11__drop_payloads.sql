-- P2-07c (D-097, F-23): `payloads` held hash, size and algorithm only (bytes live in `bodies` since V2/V6) and no product
-- code read it; its foreign key from messages.body_hash raced the retention job. Each messages row now carries its hash's
-- algorithm itself; dedup stays visible as equal (hash_algorithm, body_hash) across rows. Stop-all upgrade: no pre-V11 node
-- writes to a V11 database (it would still insert into payloads). Flyway runs this file in one transaction on Postgres
-- (PostgreSQLDatabase.supportsDdlTransactions); the order below keeps every intermediate state readable anyway: the column
-- is filled before the FK goes, the table goes last.
alter table messages add column hash_algorithm text not null default 'sha256-64';   -- as `Hasher.algorithm()` names it; V2's default
update messages m set hash_algorithm = p.algorithm from payloads p where p.hash = m.body_hash and p.algorithm <> 'sha256-64';
alter table messages drop constraint messages_body_hash_fkey;   -- V1's inline `references payloads (hash)`, name read from pg_constraint
drop table payloads;   -- no cascade: anything else still depending on it fails the migration
