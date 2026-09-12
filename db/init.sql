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
