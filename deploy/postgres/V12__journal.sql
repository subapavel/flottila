-- CORE-4c1 (D-105, CORE-3 item 5): the body journal's rows. Bytes live in the node's journal segments, never here; the one
-- bytea is a tenant key wrapped under the master key (D-106). Stop-all upgrade like V11: no pre-V12 node writes to a V12
-- database. `bodies` is unchanged (it takes no more inserts from CORE-4c2 on; its rows go by age as today until CORE-11).

-- one journal per node: NODE in the journal directory names this id; a row without NODE, or another id, refuses the start
create table journal_nodes (
    node       text not null primary key,
    journal_id uuid not null
);

-- every segment of a node, written before its file exists; a row without its file is dropped at start, a file without its
-- row unlinked. copies: the nodes holding a byte-identical copy (CORE-5), fixed when the row is written
create table journal_segments (
    node       text        not null,
    segment    bigint      not null,
    copies     text[]      not null,
    created_at timestamptz not null default now(),
    primary key (node, segment)
);

-- a kept body reference: every row is a kept ref (no keep column); FIX-35 compares bytes by reading the record (no hash column).
-- lost_at: given up by an accepted loss (CORE-4c2), then it pins nothing
create table body_refs (
    message_id  text        not null,
    step        int         not null,
    part        text        not null,
    node        text        not null,
    segment     bigint      not null,
    position    bigint      not null,
    length      bigint      not null,
    archived_at timestamptz,
    lost_at     timestamptz,
    created_at  timestamptz not null default now(),
    primary key (message_id, step, part)
);
-- what pins a segment (the sweeper, CORE-4d): unarchived refs by segment
create index body_refs_unarchived on body_refs (node, segment, created_at) where archived_at is null;
-- the retention job's scan by age (V10's pattern: led by created_at, ending in the key)
create index body_refs_created_at on body_refs (created_at, message_id, step, part);

-- a tenant's key, made on its first use, wrapped under the master key (AES-256-GCM, the AAD the key id and the tenant); the
-- key id is the record header's u32 tenant key id, taken from journal_key_ids before the wrap
create sequence journal_key_ids as integer;
create table journal_keys (
    key_id     integer     not null primary key,
    tenant     text        not null,
    wrapped    bytea       not null,
    created_at timestamptz not null default now()
);
-- one key per tenant: two nodes making a tenant's key at once agree on one
create unique index journal_keys_tenant on journal_keys (tenant);

-- a work item's body ref (null = a pre-V12 item: its body is read from `bodies`, claimable by any node). No index on these
-- until CORE-GATE measures the need
alter table work add column body_node text;
alter table work add column body_segment bigint;
alter table work add column body_position bigint;
alter table work add column body_length bigint;
