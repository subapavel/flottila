-- P1-04: the snapshot per step (ARCHITECTURE §8 write path) on the V1 tables. Bytes live in the BlobStore, keyed by
-- message id + step (D-041); `payloads` keeps the content hash for dedup and monitoring, no longer the body.
alter table payloads alter column body drop not null;
alter table payloads add column algorithm text not null default 'sha256-64'; -- the hash's algorithm as `Hasher.algorithm()` names it; the default is what the JDK-only hasher writes today (xxh3 is D-041's open point)

alter table messages add column step_name  text        not null default '';
alter table messages add column outcome    text        not null default 'ok';      -- ok | filtered | failed
alter table messages add column error      text;
alter table messages add column started_at timestamptz not null default now();   -- rows written before V2 (the bench's) carry the migration time
alter table messages add column wall_ns    bigint      not null default 0;
alter table messages add column cpu_ns     bigint;                                 -- null = unknown on this platform
alter table messages add column body_key   text;                                   -- <message id>/<step>/<part>; null = metadata-only
alter table messages add column body_size  bigint;
alter table messages add column versions   jsonb       not null default '{}';     -- {"flow": "id@version", ...}
