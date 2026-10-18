-- V1: the milestone-1 schema (proto-m1 db/init.sql), applied by Flyway from the runtime at start
-- (io.flottila.runtime.db.Migrations) and by the Testcontainers ITs. Later versions change it
-- (body references instead of inline payloads, versions, processed_ids, …); V1 is never edited.
-- Content-addressed payload store: one row per distinct content, written ON CONFLICT DO NOTHING.
create table payloads (
    hash       bytea       primary key,          -- xxh3-64, 8 bytes
    size       bigint      not null,
    body       bytea       not null,
    created_at timestamptz not null default now()
);

-- Store-and-forward + per-step snapshots. One row per (message, step); payload by reference.
create table messages (
    id          text        not null,
    step        int         not null,
    flow        text        not null,
    headers     jsonb       not null default '{}',
    props       jsonb       not null default '{}',
    keys        jsonb       not null default '{}',
    -- attachment references only (name -> {contentType, size, storageRef}); content is a later step
    attachments jsonb       not null default '{}',
    body_hash   bytea       not null references payloads (hash),
    created_at  timestamptz not null default now(),
    primary key (id, step)
);

-- Business correlation keys, separate and indexed: "invoice 4711 - where is it" in < 1 s.
create table message_keys (
    name       text not null,
    value      text not null,
    message_id text not null,
    step       int  not null
);
create index message_keys_name_value on message_keys (name, value);

-- Durable dead letter queue. body_hash points into payloads (no FK: park must never fail on
-- write ordering). Replay loads the step-k envelope from messages.
create table dlq (
    id        text        primary key,
    flow      text        not null,
    stage     text        not null,
    step      int         not null,
    error     text        not null,
    headers   jsonb       not null default '{}',
    body_hash bytea,
    parked_at timestamptz not null default now()
);

-- Work queue: SELECT ... FOR UPDATE SKIP LOCKED. A row is claimable when its lease is free and
-- it is the oldest row of its partition_key whose key has no active lease -> per-key serial,
-- cross-key parallel. Completion deletes the row; an expired lease re-delivers (at-least-once).
create table work (
    seq           bigserial   primary key,
    id            text        not null,
    flow          text        not null,
    step          int         not null,
    partition_key text,
    available_at  timestamptz not null default now(),
    locked_by     text,
    locked_until  timestamptz
);
create index work_flow_seq on work (flow, seq);
