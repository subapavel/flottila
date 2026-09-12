-- Store-and-forward + per-step payload snapshots. One row per (message, step).
create table messages (
    id          text        not null,
    step        int         not null,
    flow        text        not null,
    headers     jsonb       not null default '{}',
    props       jsonb       not null default '{}',
    keys        jsonb       not null default '{}',
    -- attachment references only (name -> {contentType, size, storageRef}); content is a later step
    attachments jsonb       not null default '{}',
    body        bytea       not null default ''::bytea,
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
